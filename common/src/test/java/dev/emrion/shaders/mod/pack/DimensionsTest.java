package dev.emrion.shaders.mod.pack;

import static org.junit.jupiter.api.Assertions.*;

import dev.emrion.shaders.format.Manifest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DimensionsTest {
	@Test
	void exactBeatsGlobAndMissingPlansAreSkipped() {
		Map<String, Manifest.Dimension> dims = new java.util.HashMap<>();
		dims.put("world0", null);
		dims.put("world-1", null);
		var m = new Manifest(1, null, null, null, null, null, null, null, null, null, List.of(
				new Manifest.DimensionMapping("*:*", "world0"),
				new Manifest.DimensionMapping("minecraft:the_nether", "world-1"),
				new Manifest.DimensionMapping("minecraft:*end*", "world1")), dims);
		assertEquals("world-1", Dimensions.folder(m, "minecraft:the_nether"));
		assertEquals("world0", Dimensions.folder(m, "minecraft:overworld"));
		assertEquals("world0", Dimensions.folder(m, "minecraft:the_end"), "world1 has no plan, so the next glob wins");
	}
}
