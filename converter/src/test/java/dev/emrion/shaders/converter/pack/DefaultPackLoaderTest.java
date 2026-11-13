package dev.emrion.shaders.converter.pack;

import dev.emrion.shaders.converter.glsl.PreprocessedStage;
import dev.emrion.shaders.converter.glsl.ProgramGroup;
import dev.emrion.shaders.converter.glsl.StageType;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultPackLoaderTest {
	@TempDir
	Path tmp;

	private void write(String path, String content) throws Exception {
		Path p = tmp.resolve("pack").resolve(path);
		Files.createDirectories(p.getParent());
		Files.writeString(p, content);
	}

	private ResolvedPack load(String settings, Diagnostics d) throws Exception {
		Path s = null;
		if (settings != null) {
			s = tmp.resolve("pack.txt");
			Files.writeString(s, settings);
		}
		return new DefaultPackLoader().load(PackSource.open(tmp.resolve("pack")), s,
				StandardMacros.build(StandardMacros.Environment.DEFAULT), d);
	}

	private void basePack() throws Exception {
		write("shaders/lib/settings.glsl", """
				#define BLOOM
				//#define FOG // fog
				#define QUALITY 2 //[1 2 3]
				const int shadowMapResolution = 1024; //[1024 2048]
				#ifdef BLOOM
				#endif
				#ifdef FOG
				#endif
				""");
		write("shaders/program/c.glsl", """
				#include "/lib/settings.glsl"
				#ifdef FRAGMENT_SHADER
				/* DRAWBUFFERS:01 */
				#if QUALITY == 3
				/* RENDERTARGETS: 5 */
				#endif
				#ifdef IRIS_FEATURE_SSBO
				int ssbo;
				#endif
				#ifdef IRIS_FEATURE_HIGHER_SHADOWCOLOR
				int hsc;
				#endif
				#endif
				void main() {}
				""");
		for (String w : new String[]{"world0", "world-1"}) {
			for (String p : new String[]{"composite", "composite2", "gbuffers_terrain"}) {
				write("shaders/" + w + "/" + p + ".vsh", "#version 130\n#define VERTEX_SHADER\n#include \"/program/c.glsl\"\n");
				write("shaders/" + w + "/" + p + ".fsh", "#version 130\n#define FRAGMENT_SHADER\n#include \"/program/c.glsl\"\n");
			}
		}
		write("shaders/world0/shadowcomp.csh", "#version 430\nlayout(local_size_x = 8, local_size_y = 4) in;\nconst ivec3 workGroups = ivec3(1, 2, 3);\nvoid main() {}\n");
		write("shaders/world0/shadowcomp_a.csh", "#version 430\nvoid main() {}\n");
		write("shaders/world0/composite.csh", "#version 430\nvoid main() {}\n");
		write("shaders/shaders.properties", """
				iris.features.optional = SSBO
				program.world0/composite2.enabled = FOG
				#if QUALITY == 3
				quality.high = true
				#endif
				screen = QUALITY [MORE]
				profile.LOW = QUALITY=1 !BLOOM
				""");
	}

	private static ResolvedPack.ProgramSource program(List<ResolvedPack.ProgramSource> list, String name, StageType stage) {
		return list.stream().filter(p -> p.name().equals(name) && p.stages().stream().anyMatch(s -> s.stage() == stage)
				|| p.name().equals(name) && p.stages().isEmpty()).findFirst().orElseThrow();
	}

	@Test
	void loadsProgramsOptionsAndDirectives() throws Exception {
		basePack();
		Diagnostics d = new Diagnostics();
		ResolvedPack rp = load("QUALITY=3\nUNKNOWN_KEY=1\n", d);
		assertFalse(d.hasErrors(), d.all().toString());
		assertEquals(List.of("world0", "world-1"), List.copyOf(rp.dimensions().keySet()));
		assertEquals(List.of(new Manifest.DimensionMapping("minecraft:overworld", "world0"), new Manifest.DimensionMapping("*:*", "world0"),
				new Manifest.DimensionMapping("minecraft:the_nether", "world-1")), rp.dimensionMap());

		List<ResolvedPack.ProgramSource> w0 = rp.dimensions().get("world0");
		assertEquals(List.of("shadowcomp", "shadowcomp_a", "gbuffers_terrain", "composite_csh", "composite", "composite2"),
				w0.stream().map(ResolvedPack.ProgramSource::name).toList());
		assertTrue(d.all().stream().noneMatch(x -> x.message().contains("compute alongside fullscreen")));
		assertFalse(program(w0, "composite2", StageType.FRAGMENT).enabled(), "program.world0/composite2.enabled = FOG");
		assertTrue(rp.dimensions().get("world-1").stream().allMatch(ResolvedPack.ProgramSource::enabled));

		ResolvedPack.ProgramSource comp = program(w0, "composite", StageType.FRAGMENT);
		assertEquals(List.of(StageType.VERTEX, StageType.FRAGMENT), comp.stages().stream().map(PreprocessedStage::stage).toList());
		PreprocessedStage fsh = comp.stages().get(1);
		assertEquals(ProgramGroup.COMPOSITE, fsh.group());
		assertEquals("shaders/world0/composite.fsh", fsh.sourcePath());
		assertEquals(List.of(5), fsh.directives().drawBuffers(), "QUALITY=3 from the settings enables RENDERTARGETS");
		assertTrue(fsh.text().startsWith("#version 130\n"));
		assertTrue(fsh.text().contains("int ssbo;"), "optional feature declared by the pack");
		assertFalse(fsh.text().contains("int hsc;"), "usable but not declared → not defined for programs");
		int line = fsh.text().split("\n").length;
		assertEquals("shaders/program/c.glsl", fsh.lineMap().file(line));

		PreprocessedStage sc = program(w0, "shadowcomp", StageType.COMPUTE).stages().getFirst();
		assertEquals(ProgramGroup.SHADOWCOMP, sc.group());
		assertArrayEquals(new int[]{8, 4, 1}, sc.directives().localSize());
		assertArrayEquals(new int[]{1, 2, 3}, sc.directives().workGroups());

		assertEquals("true", rp.properties().get("quality.high"));
		assertEquals("FOG", rp.properties().get("program.world0/composite2.enabled"));

		Manifest.Options o = rp.options();
		assertEquals("pack.txt", o.settingsFile());
		assertEquals("3", o.values().get("QUALITY"));
		assertEquals("true", o.values().get("BLOOM"));
		assertEquals("1024", o.values().get("shadowMapResolution"));
		assertEquals(List.of("UNKNOWN_KEY"), o.unknownSettings());
		assertEquals(Map.of("QUALITY", "1", "BLOOM", "false"), o.profiles().get("LOW"));
		assertEquals(List.of("QUALITY", "[MORE]"), o.screens().get(""));
		Manifest.OptionDecl q = o.declared().stream().filter(x -> x.name().equals("QUALITY")).findFirst().orElseThrow();
		assertEquals("define_value", q.kind());
		assertEquals("int", q.type());
		assertEquals("shaders/lib/settings.glsl", q.file());
		assertEquals(3, q.line());
		Manifest.OptionDecl fog = o.declared().stream().filter(x -> x.name().equals("FOG")).findFirst().orElseThrow();
		assertEquals("define_bool", fog.kind());
		assertEquals("fog", fog.comment());
		assertEquals("const", o.declared().stream().filter(x -> x.name().equals("shadowMapResolution")).findFirst().orElseThrow().kind());
	}

	@Test
	void profileDisablesPrograms() throws Exception {
		basePack();
		write("shaders/shaders.properties", "profile.LOW = QUALITY=1 !program.world0/composite\n");
		ResolvedPack rp = load("QUALITY=1\n", new Diagnostics());
		assertFalse(program(rp.dimensions().get("world0"), "composite", StageType.FRAGMENT).enabled());
	}

	@Test
	void missingIncludeIsAnError() throws Exception {
		write("shaders/final.vsh", "#version 130\nvoid main() {}\n");
		write("shaders/final.fsh", "#version 130\n#include \"/lib/nope.glsl\"\nvoid main() {}\n");
		Diagnostics d = new Diagnostics();
		ResolvedPack rp = load(null, d);
		assertEquals(List.of(""), List.copyOf(rp.dimensions().keySet()), "no world folders: the root program set");
		assertEquals(List.of(new Manifest.DimensionMapping("*:*", "")), rp.dimensionMap());
		Diagnostic e = d.all().stream().filter(x -> x.severity() == Diagnostic.Severity.ERROR).findFirst().orElseThrow();
		assertEquals("shaders/final.fsh", e.file());
		assertEquals(2, e.line());
		assertEquals("final", e.program());
	}

	@Test
	void dimensionPropertiesAndColorSpaces() throws Exception {
		write("shaders/dimension.properties", "dimension.overworld = *\ndimension.nether = the_nether\ndimension.missing = mod:x\n");
		write("shaders/overworld/final.vsh", "void main() {}\n");
		write("shaders/overworld/final.fsh", "#ifdef COLOR_SPACE_SRGB\nint cs = COLOR_SPACE_REC2020;\n#endif\n");
		write("shaders/nether/final.vsh", "void main() {}\n");
		write("shaders/nether/final.fsh", "void main() {}\n");
		write("shaders/shaders.properties", "supportsColorCorrection = true\n");
		ResolvedPack rp = load(null, new Diagnostics());
		assertEquals(List.of("overworld", "nether"), List.copyOf(rp.dimensions().keySet()));
		assertTrue(rp.dimensionMap().contains(new Manifest.DimensionMapping("minecraft:the_nether", "nether")));
		assertTrue(rp.dimensionMap().stream().noneMatch(m -> m.folder().equals("missing")));
		String fsh = rp.dimensions().get("overworld").getFirst().stages().get(1).text();
		assertTrue(fsh.contains("int cs = 3;"), fsh);
	}

	@Test
	void fragmentOnlyProgramGetsLegacyVertexShader() throws Exception {
		write("shaders/final.fsh", "void main() {}\n");
		Diagnostics d = new Diagnostics();
		ResolvedPack rp = load(null, d);
		PreprocessedStage v = rp.dimensions().get("").getFirst().stages().getFirst();
		assertEquals(StageType.VERTEX, v.stage());
		assertTrue(v.text().contains("ftransform()"));
	}

	@Test
	void packConstsFollowIrisOrder() throws Exception {
		write("shaders/composite.vsh", "void main() {}\n");
		write("shaders/composite.fsh", "const int colortex0Format = RGBA16F;\n");
		write("shaders/gbuffers_basic.vsh", "void main() {}\n");
		write("shaders/gbuffers_basic.fsh", "const int colortex0Format = RGBA8;\nconst bool colortex1Clear = false;\n");
		ResolvedPack rp = load(null, new Diagnostics());
		Map<String, String> c = PackConsts.merge(rp.dimensions().get(""));
		assertEquals("RGBA16F", c.get("colortex0Format"), "composite is read after gbuffers");
		assertEquals("false", c.get("colortex1Clear"));
	}

	@Test
	void tessellationStagesNeedTheFeatureFlag() throws Exception {
		for (String ext : new String[]{"vsh", "tcs", "tes", "gsh", "fsh"}) {
			write("shaders/gbuffers_terrain." + ext, "#version 400\nvoid main() {}\n");
			write("shaders/composite." + ext, "#version 400\nvoid main() {}\n");
		}
		Diagnostics d = new Diagnostics();
		var stages = load(null, d).dimensions().get("").stream().filter(p -> p.name().equals("gbuffers_terrain")).findFirst().orElseThrow()
				.stages().stream().map(PreprocessedStage::stage).toList();
		assertEquals(List.of(StageType.VERTEX, StageType.GEOMETRY, StageType.FRAGMENT), stages);
		write("shaders/shaders.properties", "iris.features.optional = TESSELATION_SHADERS\n"); // Iris accepts the old spelling
		ResolvedPack rp = load(null, d);
		stages = rp.dimensions().get("").stream().filter(p -> p.name().equals("gbuffers_terrain")).findFirst().orElseThrow()
				.stages().stream().map(PreprocessedStage::stage).toList();
		assertEquals(List.of(StageType.VERTEX, StageType.TESS_CONTROL, StageType.TESS_EVAL, StageType.GEOMETRY, StageType.FRAGMENT), stages);
		var composite = rp.dimensions().get("").stream().filter(p -> p.name().equals("composite")).findFirst().orElseThrow();
		assertEquals(2, composite.stages().size(), "composite keeps only .vsh/.fsh");
		assertTrue(d.all().stream().anyMatch(x -> x.message().contains("only supported in gbuffers and shadow")));
	}
}
