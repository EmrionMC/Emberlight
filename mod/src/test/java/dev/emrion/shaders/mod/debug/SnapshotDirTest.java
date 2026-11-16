package dev.emrion.shaders.mod.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotDirTest {
	@Test
	void sameSecondGetsASuffix(@TempDir Path base) throws Exception {
		assertEquals("2026-11-16_12-00-00", Snapshot.newDir(base, "2026-11-16_12-00-00").getFileName().toString());
		assertEquals("2026-11-16_12-00-00-2", Snapshot.newDir(base, "2026-11-16_12-00-00").getFileName().toString());
		assertEquals("2026-11-16_12-00-00-3", Snapshot.newDir(base, "2026-11-16_12-00-00").getFileName().toString());
	}
}
