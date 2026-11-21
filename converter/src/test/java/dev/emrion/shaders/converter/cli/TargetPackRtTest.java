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

/** RT conversions of the target pack (skipped when testpacks/ is absent); RT off must stay byte-identical. */
class TargetPackRtTest {
	static final Path PACK = ROOT.resolve("unbound-euphoria");
	static final Path SETTINGS = ROOT.resolve("ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5.txt");
	/** sha256 over the RT-off output (paths + bytes); changes only with an intended output change and a Converter.VERSION bump. */

	static final String RT_OFF_GOLDEN = "3ebda573e3e4d5b1a30e69b34661a14016ca4137ad40aae8115ca7cb82fcfd85";

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
		// the ids an active skip list decides per id reach the mod (its opacity micromaps leave them alpha-tested)
		boolean skips = !fsh.contains("bool emrRt_shadows_skip(int id) { return false; }");
		assertEquals(skips, !r.manifest().rt().anyHitIds().isEmpty(), r.manifest().rt().anyHitIds().toString());
		if (skips) assertTrue(fsh.contains("id == " + r.manifest().rt().anyHitIds().getFirst()));
		var kinds = r.manifest().dimensions().get("world0").resources().stream().map(x -> x.kind() + ":" + x.builtin()).toList();
		assertTrue(kinds.contains("acceleration_structure:emrion_tlas"), kinds.toString());
		assertTrue(kinds.contains("storage_buffer:emrion_rt_sections"), kinds.toString());
	}

	@Test
	void prepassStopsTerrainAfterTheAlphaTest(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out, Set.of("prepass"));
		assertEquals(0, r.stagesFailed());
		assertEquals(List.of("prepass"), r.manifest().rt().effects(), r.manifest().rt().unavailable().toString());
		String fsh = Files.readString(r.outputDir().resolve("src/world0/gbuffers_terrain.fsh.glsl"));
		assertTrue(fsh.contains("if (emr_draw.emr_alphaTestRef > 1.5) { emr_FragData0 = vec4(3.0); return; }"), "prepass exit after the alpha test");
		assertFalse(fsh.contains("emrRt"), "bare: no RT library");
		// the pack's defaults (IPBR) have Euphoria's snow-layer dither discard after the alpha test: the shading pass skips it
		try (var compiler = new ShadercSpirvCompiler()) {
			var d = ConverterCli.defaultConverter(compiler).convert(new Converter.Request(PACK, null, out.resolve("defaults"), null,
					Converter.ProgressListener.NONE, false, Set.of("prepass")));
			assertEquals(List.of("prepass"), d.manifest().rt().effects(), d.manifest().rt().unavailable().toString());
			assertTrue(Files.readString(d.outputDir().resolve("src/world0/gbuffers_terrain.fsh.glsl"))
					.contains("if (dither + 0.25 < snowFadeOut && emr_draw.emr_alphaTestRef > -1.5) discard;"));
		}
	}

	@Test
	void upscaleRemovesThePacksTaaJitterAndFxaa(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out, Set.of("upscale"));
		assertEquals(0, r.stagesFailed());
		assertEquals(List.of("upscale"), r.manifest().rt().effects(), r.manifest().rt().unavailable().toString());
		var patched = r.manifest().rt().patched().get("upscale");
		assertTrue(patched.contains("world0/composite6.fsh") && patched.contains("world0/gbuffers_terrain.vsh"), patched.toString());
		String taa = Files.readString(r.outputDir().resolve("src/world0/composite6.fsh.glsl"));
		assertTrue(taa.startsWith("#version 450\n") && !taa.contains("emrRt"), "bare patches: no RT library, no ray queries");
		var kinds = r.manifest().dimensions().get("world0").resources().stream().map(x -> x.kind()).toList();
		assertTrue(!kinds.contains("acceleration_structure"), kinds.toString());
	}

	@Test
	void rtAoPatchesDeferred1(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out, Set.of("ao"));
		assertEquals(0, r.stagesFailed());
		assertEquals(List.of("ao"), r.manifest().rt().effects());
		assertTrue(r.manifest().rt().patched().get("ao").contains("world0/deferred1.fsh"), r.manifest().rt().patched().toString());
		String fsh = Files.readString(r.outputDir().resolve("src/world0/deferred1.fsh.glsl"));
		assertTrue(fsh.contains("emrRt_ao_strength = 100;"), "strength comes from the pack's SSAO_I");
		var kinds = r.manifest().dimensions().get("world0").resources().stream().map(x -> x.kind() + ":" + x.builtin()).toList();
		assertTrue(kinds.contains("sampled_image:emrion_rtao"), kinds.toString());
	}

	@Test
	void rtShadowsWithEntitiesBindTheEntityAtlas(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out, Set.of("shadows", "shadows:full", "shadows:entities"));
		assertEquals(0, r.stagesFailed());
		assertEquals(List.of("shadows", "shadows:entities", "shadows:full"), r.manifest().rt().effects());
		assertTrue(Files.readString(r.outputDir().resolve("src/world0/gbuffers_terrain.fsh.glsl")).contains("emrRt_shadows_entities = 1"));
		var kinds = r.manifest().dimensions().get("world0").resources().stream().map(x -> x.kind() + ":" + x.builtin()).toList();
		assertTrue(kinds.contains("sampled_image:emrion_rt_entity_atlas"), kinds.toString());
	}

	@Test
	void rtBlockLightConvertsAndPatchesDoLighting(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out, Set.of("blocklight", "shadows", "reflections")); // shares gbuffers stages with shadows
		assertEquals(List.of(), r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		assertEquals(0, r.stagesFailed());
		assertEquals(List.of("blocklight", "reflections", "shadows"), r.manifest().rt().effects(), r.manifest().rt().unavailable().toString());
		assertTrue(r.manifest().rt().patched().get("blocklight").contains("world0/gbuffers_terrain.fsh"));
		String fsh = Files.readString(r.outputDir().resolve("src/world0/gbuffers_terrain.fsh.glsl"));
		assertTrue(fsh.contains("blockLighting *= emrRtBlockLight(playerPos, worldGeoNormal, lightmap.x);"), "anchor body inserted");
		assertEquals(1, fsh.split("accelerationStructureEXT emrRtTlas", -1).length - 1, "one common library per stage");
		var kinds = r.manifest().dimensions().get("world0").resources().stream().map(x -> x.kind() + ":" + x.builtin()).toList();
		assertTrue(kinds.contains("storage_buffer:emrion_rt_lights"), kinds.toString());
	}

	@Test
	void blockLightAnchorMissIsUnavailable() throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		try (PackSource src = PackSource.open(PACK)) {
			var pack = new DefaultPackLoader().load(src, SETTINGS, StandardMacros.build(StandardMacros.Environment.DEFAULT), new Diagnostics());
			var good = RtPatchTable.load("rt/complementary-r5.json");
			var e = good.effects().get("blocklight");
			var p = e.patches().getFirst();
			var broken = new RtPatchTable(good.id(), good.detect(), java.util.Map.of("blocklight", new RtPatchTable.Effect(e.requires(), e.options(),
					e.helpers(), e.uniforms(), e.needs(), e.skipIds(), e.skipIdsWhen(), e.frees(), List.of(new RtPatchTable.Patch(p.function(), p.body(), null, null, false, "noSuchStatement = 1;", null)))));
			var r = RtPatcher.apply(pack, Set.of("blocklight"), broken);
			assertSame(pack, r.pack());
			assertTrue(r.rt().unavailable().get("blocklight").contains("noSuchStatement"), r.rt().unavailable().toString());
		}
	}

	@Test
	void rtGiConvertsWithComputesAndAmbientPatch(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out, Set.of("gi"));
		assertEquals(List.of(), r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		assertEquals(0, r.stagesFailed());
		assertEquals(List.of("gi"), r.manifest().rt().effects(), r.manifest().rt().unavailable().toString());
		var where = r.manifest().rt().patched().get("gi");
		assertTrue(where.contains("world0/gbuffers_terrain.fsh") && where.contains("world0/deferred_a.csh"), where.toString());
		// the Nether and End get GI too, with their own dimension constant (no sky: range-limited rays, gi_common.glsl)
		assertTrue(where.contains("world-1/deferred_a.csh") && where.contains("world1/gbuffers_terrain.fsh"), where.toString());
		assertTrue(Files.readString(r.outputDir().resolve("src/world-1/deferred_a.csh.glsl")).contains("const int emrRtDim = -1;"));
		assertTrue(Files.readString(r.outputDir().resolve("src/world0/deferred_a.csh.glsl")).contains("const int emrRtDim = 0;"));
		String fsh = Files.readString(r.outputDir().resolve("src/world0/gbuffers_terrain.fsh.glsl"));
		assertTrue(fsh.startsWith("#version 450\n") && fsh.contains("emrRtGi("), "gbuffers only sample the GI images");
		var programs = r.manifest().dimensions().get("world0").programs();
		for (String c : List.of("deferred_a", "deferred_b", "deferred_c", "deferred_d", "deferred_e")) assertTrue(programs.containsKey(c), c);
	}

	@Test
	void rtxStyleConvertsAndFaithfulHasNoRtxCode(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out.resolve("rtx"), Set.of("shadows", "shadows:entities", "shadows:rtx", "blocklight", "blocklight:rtx", "gi", "gi:rtx",
				"reflections", "materials", "shafts", "refraction", "refraction:entities", "refraction:glass=0"));
		assertEquals(List.of(), r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		assertEquals(0, r.stagesFailed());
		assertEquals(List.of("blocklight", "blocklight:rtx", "gi", "gi:rtx", "materials", "reflections", "refraction", "refraction:entities", "refraction:glass=0",
						"shadows", "shadows:entities", "shadows:rtx", "shafts"), r.manifest().rt().effects(), r.manifest().rt().unavailable().toString());
		String fsh = Files.readString(r.outputDir().resolve("src/world0/gbuffers_terrain.fsh.glsl"));
		for (String s : List.of("emrRtxShadowTerm(", "emrRtxBlockLight(", "emrRtxGi(", "specularMap.rg = emrRtxMaterial(mat);", "return mat == 10264")) assertTrue(fsh.contains(s), s);
		// RTX GI runs in prepare, before the gbuffers apply it (this frame's GI, no reprojection)
		assertTrue(Files.readString(r.outputDir().resolve("src/world0/prepare_a.csh.glsl")).contains("emrGiBounceRtx("));
		// RTX block light: ReSTIR in prepare_f..i, the gbuffers read its result; GI's bounce hits use the same source list
		for (String c : List.of("prepare_f", "prepare_g", "prepare_h", "prepare_i"))
			assertTrue(Files.readString(r.outputDir().resolve("src/world0/" + c + ".csh.glsl")).contains("emrBlIndex"), c);
		assertTrue(Files.readString(r.outputDir().resolve("src/world0/prepare_a.csh.glsl")).contains("emrBlPick("));
		assertTrue(fsh.contains("emrBlOutSampler"));
		// water: RTX block light only for a refraction hit's DoLighting (centerShadowBias marks it), estimated at the hit itself
		assertTrue(Files.readString(r.outputDir().resolve("src/world0/gbuffers_water.fsh.glsl")).contains("if (centerShadowBias) blockLighting = emrRtxBlockLightHit("));
		assertTrue(Files.readString(r.outputDir().resolve("src/world0/composite1.fsh.glsl")).contains("emrRt_shafts_strength"));
		// the End's volumetric light is its sky haze (Euphoria's beams), not sun shafts: no boost there
		assertFalse(Files.readString(r.outputDir().resolve("src/world1/composite1.fsh.glsl")).contains("emrRt_shafts_strength"));
		var targets = r.manifest().dimensions().get("world0").targets().stream().map(t -> t.name()).toList();
		assertTrue(targets.contains("emrGiFC") && targets.contains("emrBlResH"), targets.toString());
		// without :rtx the same effects contain none of it (Faithful = the pre-RTX conversion)
		var f = convert(out.resolve("faithful"), Set.of("shadows", "shadows:entities", "blocklight", "gi", "reflections"));
		assertEquals(0, f.stagesFailed());
		String ff = Files.readString(f.outputDir().resolve("src/world0/gbuffers_terrain.fsh.glsl"));
		assertFalse(ff.contains("emrRtx"), "faithful stage has RTX code");
	}

	@Test
	void rtxGiCacheAddsUpdateAndResolvePasses(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out.resolve("cache"), Set.of("shadows", "shadows:rtx", "blocklight", "blocklight:rtx", "gi", "gi:rtx", "gi:cache", "materials", "shafts"));
		assertEquals(0, r.stagesFailed());
		assertTrue(r.manifest().rt().effects().contains("gi:cache"), r.manifest().rt().unavailable().toString());
		Path src = r.outputDir().resolve("src/world0");
		assertTrue(Files.readString(src.resolve("prepare_a.csh.glsl")).contains("imageLoad(emrGiCk"));
		assertTrue(Files.readString(src.resolve("prepare_j.csh.glsl")).contains("imageAtomicCompSwap(emrGiCk"));
		assertTrue(Files.readString(src.resolve("prepare_k.csh.glsl")).contains("emrRt_gi_cacheStale"));
		var targets = r.manifest().dimensions().get("world0").targets().stream().map(t -> t.name()).toList();
		assertTrue(targets.containsAll(List.of("emrGiCk", "emrGiCs", "emrGiCr")), targets.toString());
		// without it: no cache images, the trace's lookup is the stub
		var o = convert(out.resolve("nocache"), Set.of("shadows", "shadows:rtx", "gi", "gi:rtx", "materials", "shafts"));
		assertFalse(Files.readString(o.outputDir().resolve("src/world0/prepare_a.csh.glsl")).contains("emrGiCk"));
	}

	@Test
	void rtxWithoutGiOrWithoutBlockLightConverts(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out.resolve("nogi"), Set.of("shadows", "shadows:rtx", "blocklight", "blocklight:rtx", "materials", "shafts"));
		assertEquals(0, r.stagesFailed());
		assertEquals(List.of("blocklight", "blocklight:rtx", "materials", "shadows", "shadows:rtx", "shafts"), r.manifest().rt().effects(),
				r.manifest().rt().unavailable().toString());
		var g = convert(out.resolve("nobl"), Set.of("shadows", "shadows:rtx", "gi", "gi:rtx", "gi:entities", "materials", "shafts"));
		assertEquals(0, g.stagesFailed());
		assertEquals(List.of("gi", "gi:entities", "gi:rtx", "materials", "shadows", "shadows:rtx", "shafts"), g.manifest().rt().effects(),
				g.manifest().rt().unavailable().toString());
		String fsh = Files.readString(g.outputDir().resolve("src/world0/gbuffers_terrain.fsh.glsl"));
		assertTrue(fsh.contains("emrRtxGi(") && !fsh.contains("emrRtxBlockLight("));
	}

	@Test
	void rtReflectionsAndShadowsShareComposite(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out, Set.of("reflections", "shadows"));
		assertEquals(List.of(), r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		assertEquals(0, r.stagesFailed());
		assertEquals(List.of("reflections", "shadows"), r.manifest().rt().effects());
		var refl = r.manifest().rt().patched().get("reflections");
		assertTrue(refl.contains("world0/composite.fsh") && refl.contains("world0/shadow.vsh"), refl.toString());
		String composite = Files.readString(r.outputDir().resolve("src/world0/composite.fsh.glsl"));
		assertTrue(composite.startsWith("#version 460\n"));
		assertEquals(1, composite.split("accelerationStructureEXT emrRtTlas", -1).length - 1, "one common library per stage");
		assertTrue(composite.contains("emrRtReflHit") && composite.contains("emrRtVisibility"));
		assertFalse(composite.contains("wsr_sampler"), "no voxel reads left in composite");
		try (Stream<Path> src = Files.list(r.outputDir().resolve("src/world0"))) { // per vertex variant
			var shadowVs = src.filter(f -> f.getFileName().toString().startsWith("shadow.vsh")).toList();
			assertFalse(shadowVs.isEmpty());
			for (Path f : shadowVs) assertTrue(Files.readString(f).startsWith("#version 450\n"), "bare patch: no helpers in " + f);
		}
		// the WSR voxel data is no longer read anywhere: shrunk instead of 810 MB + 2 images
		var targets = r.manifest().dimensions().get("world0").targets();
		for (var t : targets) {
			if (t.name().equals("bufferObject0")) assertEquals(16, t.bytes(), r.diagnostics().stream().filter(d -> d.message().startsWith("RT")).toList().toString());
			if (t.name().equals("wsr_img") || t.name().equals("wsr_lod_img")) assertArrayEquals(new int[]{1, 1, 1}, t.absoluteSize());
		}
		assertTrue(targets.stream().anyMatch(t -> t.name().equals("bufferObject0")));
	}

	@Test
	void rtRefractionAndCausticsConvert(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out, Set.of("refraction", "refraction:entities", "caustics", "shadows"));
		assertEquals(0, r.stagesFailed());
		assertEquals(List.of("caustics", "refraction", "refraction:entities", "shadows"), r.manifest().rt().effects());
		var patched = r.manifest().rt().patched().get("refraction");
		assertTrue(patched.contains("world0/composite1.fsh") && patched.contains("world0/gbuffers_water.fsh"), patched.toString());
		assertTrue(r.manifest().rt().patched().get("caustics").contains("world0/gbuffers_terrain.fsh"));
		String c1 = Files.readString(r.outputDir().resolve("src/world0/composite1.fsh.glsl"));
		assertTrue(c1.startsWith("#version 460\n"));
		// the hit is lit in gbuffers_water by the pack's DoLighting; composite1 reads no lit screen for it
		String water = Files.readString(r.outputDir().resolve("src/world0/gbuffers_water.fsh.glsl"));
		assertTrue(water.contains("emrRtRfFragment(") && water.contains("emrRtRfAoIn"), "water stage traces and lights the hit");
		assertFalse(c1.contains("emrRtRfScreen") || c1.contains("emrRtRfSame"), "no screen taps of the lit image");
	}

	@Test
	void brokenTableLeavesThePackUntouched() throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		try (PackSource src = PackSource.open(PACK)) {
			var pack = new DefaultPackLoader().load(src, SETTINGS, StandardMacros.build(StandardMacros.Environment.DEFAULT), new Diagnostics());
			var good = RtPatchTable.load("rt/complementary-r5.json");
			var e = good.effects().get("shadows");
			var brokenEffect = new RtPatchTable.Effect(e.requires(), e.options(), e.helpers(), e.uniforms(),
					Stream.concat(e.needs().stream(), Stream.of("noSuchPackFunction")).toList(), e.skipIds(), e.skipIdsWhen(), e.frees(), e.patches());
			var broken = new RtPatchTable(good.id(), good.detect(), java.util.Map.of("shadows", brokenEffect));
			var r = RtPatcher.apply(pack, Set.of("shadows"), broken);
			assertSame(pack, r.pack(), "an unavailable effect must not change a single stage");
			assertEquals(List.of(), r.rt().effects());
			assertTrue(r.rt().unavailable().get("shadows").contains("noSuchPackFunction"), r.rt().unavailable().toString());
			// and a function the pack does not have at all
			var none = new RtPatchTable(good.id(), good.detect(), java.util.Map.of("shadows", new RtPatchTable.Effect(e.requires(), e.options(),
					e.helpers(), e.uniforms(), e.needs(), e.skipIds(), e.skipIdsWhen(), e.frees(), List.of(new RtPatchTable.Patch("vec3 Nope(vec3)", e.patches().getFirst().body())))));
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
