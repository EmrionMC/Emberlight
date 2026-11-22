package dev.emrion.shaders.mod.compat;

import dev.emrion.shaders.mod.EmrionShadersClient;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.jspecify.annotations.Nullable;

/**
 * BetterF3 shows our F3 lines in its "Misc left" module, which it caches (performance option, on by default) until marked dirty:
 * the cache key ignores the line texts, so changed lines stayed frozen. Reflection: BetterF3 is optional and has no API.
 */
public final class BetterF3 {
	private BetterF3() {}

	private static @Nullable MethodHandle markDirty = find();

	private static @Nullable MethodHandle find() {
		try {
			Class<?> base = Class.forName("me.cominixo.betterf3.modules.BaseModule");
			Object misc = base.getMethod("moduleById", String.class).invoke(null, "miscleft");
			return misc == null ? null : MethodHandles.publicLookup().findVirtual(base, "markDirty", MethodType.methodType(void.class)).bindTo(misc);
		} catch (ReflectiveOperationException | LinkageError e) {
			return null; // not installed, or a version without these
		}
	}

	/** Our lines changed: BetterF3 rebuilds its Misc left module this frame. */
	public static void refresh() {
		MethodHandle h = markDirty;
		if (h == null) return;
		try {
			h.invoke();
		} catch (Throwable t) {
			markDirty = null;
			EmrionShadersClient.LOG.warn("BetterF3: could not refresh its Misc module, our F3 lines may lag", t);
		}
	}
}
