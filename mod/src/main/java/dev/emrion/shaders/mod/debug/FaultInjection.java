package dev.emrion.shaders.mod.debug;

/**
 * Dev-only failure injection for the robustness tests (docs/BENCHMARK.md, M6):
 * <pre>
 * emrion.debug.throwIn=levelStart,phase,shadow,finish  throw once in each of these FrameExecutor entry points,
 * emrion.debug.throwAfter=200                           after this many level frames with a pack
 * emrion.debug.failAlloc=N                              the N-th raw image allocation (1 = first) fails with
 *                                                       VK_ERROR_OUT_OF_DEVICE_MEMORY (as a full GPU would)
 * </pre>
 */
public final class FaultInjection {
	private FaultInjection() {}

	private static final java.util.Set<String> THROW_IN = new java.util.HashSet<>(java.util.Arrays.asList(System.getProperty("emrion.debug.throwIn", "").split(",")));
	private static final int THROW_AFTER = Integer.getInteger("emrion.debug.throwAfter", 200);
	private static final int FAIL_ALLOC = Integer.getInteger("emrion.debug.failAlloc", 0);
	private static int frames, allocs;

	/** Called once per level frame by the FrameExecutor. */
	public static void frame() {
		frames++;
	}

	public static void maybeThrow(String point) {
		if (frames < THROW_AFTER || !THROW_IN.remove(point)) return;
		throw new IllegalStateException("injected failure in " + point + " (-Demrion.debug.throwIn)");
	}

	/** True if this raw image allocation should fail. */
	public static boolean failAlloc() {
		return FAIL_ALLOC > 0 && ++allocs == FAIL_ALLOC;
	}
}
