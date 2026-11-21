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

/** Plain Complementary Reimagined r5.10 (skipped when testpacks/ lacks it): the same table as Unbound r5.10. */
class TargetPackReimaginedR510RtTest {
	static final Path PACK = ROOT.resolve("reimagined-r510");
	static final Path SETTINGS = ROOT.resolve("ComplementaryReimagined_r5.10.zip.txt");
	/** sha256 over the RT-off output, as TargetPackRtTest.RT_OFF_GOLDEN. */
	static final String RT_OFF_GOLDEN = "1fd89532270a3c25e01a4d3b002b937cbbe9e9c608a627c8510446cd97f4c379";
	/** The same RT request as the Unbound test; the settings file is the Unbound one minus the keys Reimagined lacks. */
	static final Set<String> USER_RT = TargetPackR510RtTest.USER_RT;

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
