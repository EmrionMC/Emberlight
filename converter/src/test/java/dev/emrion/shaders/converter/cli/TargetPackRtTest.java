package dev.emrion.shaders.converter.cli;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.converter.glsl.RtPatcher;
import dev.emrion.shaders.converter.pack.DefaultPackLoader;
import dev.emrion.shaders.converter.pack.PackSource;
import dev.emrion.shaders.converter.pack.StandardMacros;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.converter.rt.RtPatchTable;
import dev.emrion.shaders.converter.spirv.ShadercSpirvCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static dev.emrion.shaders.converter.cli.TargetPackConversionTest.ROOT;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** M7 RT-2 acceptance on the user's pack (skipped when testpacks/ is absent): RT off is unchanged, RT shadows convert cleanly. */
class TargetPackRtTest {
	static final Path PACK = ROOT.resolve("unbound-euphoria");
	static final Path SETTINGS = ROOT.resolve("ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5.txt");
	/**
	 * sha256 over the RT-off cache directory (paths + bytes) as master produced it before M7 RT-2. Changes only when converter
	 * output changes on purpose (then Converter.VERSION is bumped too); RT work must never change it.
	 */
	static final String RT_OFF_GOLDEN = "60db95f957050ad9b59c9d3515fc9416acbe7fabf07c3df91b80ff4879071475";

	static Converter.Result convert(Path out, Set<String> rt) {
		try (var compiler = new ShadercSpirvCompiler()) {
			return ConverterCli.defaultConverter(compiler).convert(new Converter.Request(PACK, SETTINGS, out, null, Converter.ProgressListener.NONE, false, rt));
		}
	}

	@Test
	void rtOffOutputMatchesGolden(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out, Set.of());
		assertNull(r.manifest().rt());
		assertEquals(RT_OFF_GOLDEN, dirHash(r.outputDir()));
	}

	@Test
	void rtShadowsConvertAndPatchTerrain(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out, Set.of("shadows"));
		assertEquals(List.of(), r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		assertEquals(0, r.stagesFailed());
		assertTrue(r.stagesCompiled() >= 253, "stages compiled: " + r.stagesCompiled());
		assertEquals(List.of("shadows"), r.manifest().rt().effects());
		assertTrue(r.manifest().rt().patched().get("shadows").contains("world0/gbuffers_terrain.fsh"));
		String fsh = Files.readString(r.outputDir().resolve("src/world0/gbuffers_terrain.fsh.glsl"));
		assertTrue(fsh.startsWith("#version 460\n"));
		assertTrue(Files.readString(r.outputDir().resolve("src/world0/composite1.fsh.glsl")).startsWith("#version 450\n"), "unpatched stages stay 450");
		var kinds = r.manifest().dimensions().get("world0").resources().stream().map(x -> x.kind() + ":" + x.builtin()).toList();
		assertTrue(kinds.contains("acceleration_structure:emrion_tlas"), kinds.toString());
		assertTrue(kinds.contains("storage_buffer:emrion_rt_sections"), kinds.toString());
	}

	@Test
	void brokenTableLeavesThePackUntouched() throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		try (PackSource src = PackSource.open(PACK)) {
			var pack = new DefaultPackLoader().load(src, SETTINGS, StandardMacros.build(StandardMacros.Environment.DEFAULT), new Diagnostics());
			var good = RtPatchTable.load("rt/complementary-r5.json");
			var e = good.effects().get("shadows");
			var brokenEffect = new RtPatchTable.Effect(e.requires(), e.options(), e.helpers(), e.uniforms(),
					Stream.concat(e.needs().stream(), Stream.of("noSuchPackFunction")).toList(), e.skipIds(), e.skipIdsWhen(), e.patches());
			var broken = new RtPatchTable(good.id(), good.detect(), java.util.Map.of("shadows", brokenEffect));
			var r = RtPatcher.apply(pack, Set.of("shadows"), broken);
			assertSame(pack, r.pack(), "an unavailable effect must not change a single stage");
			assertEquals(List.of(), r.rt().effects());
			assertTrue(r.rt().unavailable().get("shadows").contains("noSuchPackFunction"), r.rt().unavailable().toString());
			// and a function the pack does not have at all
			var none = new RtPatchTable(good.id(), good.detect(), java.util.Map.of("shadows", new RtPatchTable.Effect(e.requires(), e.options(),
					e.helpers(), e.uniforms(), e.needs(), e.skipIds(), e.skipIdsWhen(), List.of(new RtPatchTable.Patch("vec3 Nope(vec3)", e.patches().getFirst().body())))));
			assertSame(pack, RtPatcher.apply(pack, Set.of("shadows"), none).pack());
			assertTrue(RtPatcher.apply(pack, Set.of("shadows"), null).rt().unavailable().containsKey("shadows"));
		}
	}

	static String dirHash(Path dir) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		try (Stream<Path> s = Files.walk(dir)) {
			for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
				md.update(dir.relativize(f).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
				md.update((byte) 0);
				md.update(Files.readAllBytes(f));
			}
		}
		return HexFormat.of().formatHex(md.digest());
	}
}
