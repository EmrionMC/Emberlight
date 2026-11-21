package dev.emrion.shaders.converter.cli;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.converter.spirv.ShadercSpirvCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static dev.emrion.shaders.converter.cli.TargetPackConversionTest.ROOT;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Complementary Unbound r5.10 + Euphoria Patches 1.11.2 (skipped when testpacks/ lacks it): the same table as r5.9.3. */
class TargetPackR510RtTest {
	static final Path PACK = ROOT.resolve("unbound-euphoria-r510");
	static final Path SETTINGS = ROOT.resolve("ComplementaryUnbound_r5.10 + EuphoriaPatches_1.11.2.txt");
	/** sha256 over the RT-off output, as TargetPackRtTest.RT_OFF_GOLDEN. */
	static final String RT_OFF_GOLDEN = "b0c2e2bca7e79a98e111bfae3dfbd67061b9172e6137d7aeabffc83c156e8dce";
	/** What RtConfig.request() asks for with the user's settings (RTX style, full shadows, high GI, 8 block-light rays). */
	static final Set<String> USER_RT = Set.of("shadows", "shadows:full", "shadows:rays=4", "shadows:entities", "shadows:rtx", "reflections",
			"reflections:halfRate=1", "materials", "shafts", "refraction", "refraction:entities", "refraction:glass=0", "caustics", "ao",
			"blocklight", "blocklight:rays=8", "blocklight:entities", "blocklight:rtx", "gi:rtx", "gi:entities", "gi:cache", "gi", "gi:rays=2",
			"gi:res=1.0", "prepass", "upscale");

	static Converter.Result convert(Path out, Path settings, Set<String> rt) {
		try (var compiler = new ShadercSpirvCompiler()) {
			return ConverterCli.defaultConverter(compiler).convert(new Converter.Request(PACK, settings, out, null, Converter.ProgressListener.NONE, false, rt));
		}
	}

	@Test
	void rtOffOutputMatchesGolden(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out, SETTINGS, Set.of());
		assertNull(r.manifest().rt());
		assertEquals(RT_OFF_GOLDEN, TargetPackRtTest.dirHash(r.outputDir()));
	}

	@Test
	void everyEffectOfTheUsersSettingsApplies(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK) && Files.isRegularFile(SETTINGS));
		var r = convert(out, SETTINGS, USER_RT);
		assertEquals(0, r.stagesFailed());
		assertEquals(Map.of(), r.manifest().rt().unavailable());
		assertTrue(r.manifest().rt().effects().containsAll(Set.of("prepass", "shafts", "shadows", "reflections", "gi", "blocklight", "upscale")),
				r.manifest().rt().effects().toString());
		String c1 = Files.readString(r.outputDir().resolve("src/world0/composite1.fsh.glsl"));
		assertTrue(c1.contains("emrRtShaftVisibility(scenePos)") && c1.contains("emrRt_shafts_strength"), "shaft patches in r5.10's GetVolumetricLight");
		String fsh = Files.readString(r.outputDir().resolve("src/world0/gbuffers_terrain.fsh.glsl"));
		// r5.10 tests only alpha > 0.00001 itself: the prepass applies the draw's own ref (carried above 2) before it returns
		assertTrue(fsh.contains("if (emr_draw.emr_alphaTestRef > 1.5) { if (!(color.a > emr_draw.emr_alphaTestRef - 2.0)) discard;"), "r5.10 prepass");
		assertFalse(fsh.contains("color.a <= 0.5"), "not the r5.9.3 body");
		// the shading pass (ref -2 - draw ref) skips the converter's alpha test: it must repeat the prepass's test on the same value,
		// at the same point, or mipmapped fringe texels the prepass dropped get shaded without depth (fogless outlines, grass overlay)
		String anchor = fsh.lines().filter(l -> l.contains("emr_draw.emr_alphaTestRef > 1.5")).findFirst().orElseThrow();
		assertTrue(anchor.contains("if (emr_draw.emr_alphaTestRef < -1.5 && !(color.a > -2.0 - emr_draw.emr_alphaTestRef)) discard;"), anchor);
	}

	@Test
	void prepassSkipsTheSnowDitherWithThePackDefaults(@TempDir Path out) throws Exception {
		assumeTrue(Files.isDirectory(PACK));
		var d = convert(out, null, Set.of("prepass"));
		assertEquals(Set.of("prepass"), Set.copyOf(d.manifest().rt().effects()), d.manifest().rt().unavailable().toString());
		assertTrue(Files.readString(d.outputDir().resolve("src/world0/gbuffers_terrain.fsh.glsl"))
				.contains("if (dither + 0.01 < snowFadeOut && emr_draw.emr_alphaTestRef > -1.5) discard;"));
	}
}
