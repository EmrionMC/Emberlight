package dev.emrion.shaders.converter.cli;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.spirv.ShadercSpirvCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** M1 acceptance: the user's pack with the user's settings converts with 0 errors (skipped when testpacks/ is absent). */
class TargetPackConversionTest {
	static final Path ROOT = Path.of(System.getProperty("emrion.testpacks", "../testpacks"));

	@Test
	void unboundEuphoriaConvertsWithoutErrors(@TempDir Path out) {
		Path pack = ROOT.resolve("unbound-euphoria");
		Path settings = ROOT.resolve("ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5.txt");
		assumeTrue(Files.isDirectory(pack) && Files.isRegularFile(settings));
		try (var compiler = new ShadercSpirvCompiler()) {
			var result = ConverterCli.defaultConverter(compiler)
					.convert(new Converter.Request(pack, settings, out, null, Converter.ProgressListener.NONE, false));
			var errors = result.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList();
			assertEquals(java.util.List.of(), errors);
			assertEquals(0, result.stagesFailed());
			assertTrue(result.stagesCompiled() >= 253, "stages compiled: " + result.stagesCompiled());
			for (String dim : java.util.List.of("world0", "world-1", "world1"))
				assertTrue(result.manifest().dimensions().get(dim).programs().containsKey("final"), dim + " has final");
			// gbuffers_terrain.fsh: COLORED_LIGHTING with world-space reflections → Iris's shadow safe zone (shadow.culling=reversed)
			assertEquals(64f, result.manifest().dimensions().get("world0").shadow().voxelDistance());
		}
	}
}
