package dev.emrion.shaders.mod.pbr;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Iris's PBRTextureManager.getOrLoadHolder: a miss returns null (the caller uses defaults) and queues the key; {@link #resolve}
 * (next frame start) loads every queued key at once. Keys are compared by identity (GPU textures).
 */
final class NextFrameCache<K, V> {
	private final Map<K, V> values = new IdentityHashMap<>();
	private final Set<K> pending = Collections.newSetFromMap(new IdentityHashMap<>());

	@Nullable V get(K key) {
		V v = values.get(key);
		if (v == null) pending.add(key);
		return v;
	}

	/** Loads the queued keys; {@code load} must return a value (a "nothing" value included) for each key it is given. */
	void resolve(Function<Set<K>, Map<K, V>> load) {
		if (pending.isEmpty()) return;
		values.putAll(load.apply(Collections.unmodifiableSet(pending)));
		pending.clear();
	}

	/** Forgets everything; {@code close} sees every loaded value once. */
	void clear(Consumer<V> close) {
		values.values().forEach(close);
		values.clear();
		pending.clear();
	}

	int size() {
		return values.size();
	}
}
