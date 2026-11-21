package dev.emrion.shaders.mod.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class GbufferPartitionTest {
	@Test
	void unionThatFitsIsOneGroupByIndex() {
		Map<String, Integer> g = new HashMap<>();
		var groups = GbufferPipelines.partition(new TreeMap<>(Map.of("a", List.of("colortex10", "colortex2"), "b", List.of("colortex0"))), 8, g);
		assertEquals(List.of(List.of("colortex0", "colortex2", "colortex10")), groups);
		assertEquals(Map.of("a", 0, "b", 0), g);
	}

	@Test
	void blissSplitsIntoGroupsThatFit() {
		Map<String, List<String>> out = new TreeMap<>();
		out.put("gbuffers_terrain", List.of("colortex1", "colortex8", "colortex15"));
		out.put("gbuffers_entities", List.of("colortex1", "colortex8", "colortex15", "colortex2"));
		out.put("gbuffers_water", List.of("colortex2", "colortex7", "colortex11", "colortex14"));
		out.put("gbuffers_textured", List.of("colortex2", "colortex9"));
		out.put("gbuffers_skybasic", List.of("colortex0"));
		Map<String, Integer> g = new HashMap<>();
		var groups = GbufferPipelines.partition(out, 8, g);
		assertTrue(groups.size() > 1);
		for (var e : out.entrySet()) {
			assertTrue(groups.get(g.get(e.getKey())).containsAll(e.getValue()), e.getKey());
		}
		groups.forEach(x -> assertTrue(x.size() <= 8));
	}
}
