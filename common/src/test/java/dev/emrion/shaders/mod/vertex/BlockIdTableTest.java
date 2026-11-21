package dev.emrion.shaders.mod.vertex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.emrion.shaders.format.Manifest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockIdTableTest {
	record S(String block, Map<String, String> props) {}

	static final S WHEAT_0 = new S("minecraft:wheat", Map.of("age", "0"));
	static final S WHEAT_7 = new S("minecraft:wheat", Map.of("age", "7"));
	static final S OAK_LOG = new S("minecraft:oak_log", Map.of("axis", "y"));
	static final S BIRCH_LOG = new S("minecraft:birch_log", Map.of("axis", "y"));
	static final S STONE = new S("minecraft:stone", Map.of());

	static final BlockIdTable.States<S> FAKE = new BlockIdTable.States<>() {
		final List<S> all = List.of(WHEAT_0, WHEAT_7, OAK_LOG, BIRCH_LOG, STONE);

		@Override
		public List<S> block(String id) { return all.stream().filter(s -> s.block.equals(id)).toList(); }

		@Override
		public List<S> tag(String id) { return id.equals("minecraft:logs") ? List.of(OAK_LOG, BIRCH_LOG) : List.of(); }

		@Override
		public String property(S state, String key) { return state.props.get(key); }
	};

	static Map<S, Integer> assign(Manifest.IdEntry... entries) {
		Map<S, Integer> out = new HashMap<>();
		BlockIdTable.assign(List.of(entries), FAKE, out);
		return out;
	}

	@Test
	void propertyPredicatesSelectStates() {
		Map<S, Integer> m = assign(new Manifest.IdEntry(10, List.of("minecraft:wheat:age=7")), new Manifest.IdEntry(11, List.of("minecraft:wheat")));
		assertEquals(10, m.get(WHEAT_7));
		assertEquals(11, m.get(WHEAT_0));
	}

	@Test
	void firstMatchWinsInListOrder() {
		Map<S, Integer> m = assign(new Manifest.IdEntry(20, List.of("minecraft:oak_log")), new Manifest.IdEntry(21, List.of("#minecraft:logs")));
		assertEquals(20, m.get(OAK_LOG));
		assertEquals(21, m.get(BIRCH_LOG));
	}

	@Test
	void tagsUnknownNamesAndMissingProperties() {
		Map<S, Integer> m = assign(new Manifest.IdEntry(30, List.of("#minecraft:logs:axis=y", "minecraft:nope", "#minecraft:nope")),
				new Manifest.IdEntry(31, List.of("minecraft:stone:facing=north"))); // stone has no facing: predicate ignored (Iris)
		assertEquals(30, m.get(OAK_LOG));
		assertEquals(30, m.get(BIRCH_LOG));
		assertEquals(31, m.get(STONE));
		assertFalse(m.containsKey(WHEAT_0));
		assertEquals(Map.of(), assign(new Manifest.IdEntry(40, List.of("#minecraft:logs:axis=x"))));
	}
}
