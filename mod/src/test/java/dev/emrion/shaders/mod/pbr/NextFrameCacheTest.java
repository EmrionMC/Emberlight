package dev.emrion.shaders.mod.pbr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NextFrameCacheTest {
	@Test
	void missQueuesAndResolvesNextFrame() {
		var cache = new NextFrameCache<Object, String>();
		Object a = new Object(), b = new Object();
		assertNull(cache.get(a));
		assertNull(cache.get(a));
		assertNull(cache.get(b));
		List<Integer> batches = new ArrayList<>();
		cache.resolve(keys -> {
			batches.add(keys.size());
			Map<Object, String> m = new HashMap<>();
			for (Object k : keys) m.put(k, k == a ? "a" : "none");
			return m;
		});
		assertEquals(List.of(2), batches, "both misses load in one batch, a only once");
		assertEquals("a", cache.get(a));
		assertEquals("none", cache.get(b));
		cache.resolve(keys -> { throw new AssertionError("nothing pending"); });
	}

	@Test
	void identityKeysAndClearClosesEverything() {
		var cache = new NextFrameCache<String, String>();
		String k1 = new String("t"), k2 = new String("t");
		cache.get(k1);
		cache.resolve(keys -> Map.of(k1, "v1"));
		assertNull(cache.get(k2), "an equal but different texture object is a different texture");
		cache.resolve(keys -> Map.of(k2, "v2"));
		List<String> closed = new ArrayList<>();
		cache.clear(closed::add);
		assertEquals(2, closed.size());
		assertEquals(0, cache.size());
		assertNull(cache.get(k1));
	}

	@Test
	void pbrLocationInsertsTheSuffixBeforeTheExtension() {
		assertEquals("textures/entity/end_crystal/end_crystal_n.png", PbrTextures.withSuffix("textures/entity/end_crystal/end_crystal.png", "_n"));
		assertEquals("textures/a.b/c_s", PbrTextures.withSuffix("textures/a.b/c", "_s"));
	}
}
