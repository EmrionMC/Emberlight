package dev.emrion.shaders.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadPropertiesTest {
	@Test
	void malformedEscapeFallsBackInsteadOfThrowing(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("emberlight.properties");
		Files.writeString(f, "rt.device=false\npack=\\u12G4\nlater=1\n");
		var p = EmrionShadersClient.readProperties(f);
		assertEquals("false", p.getProperty("rt.device")); // keys before the bad line survive
		assertNull(p.getProperty("later"));
	}

	@Test
	void missingFileIsEmpty(@TempDir Path dir) {
		assertTrue(EmrionShadersClient.readProperties(dir.resolve("none")).isEmpty());
	}
}
