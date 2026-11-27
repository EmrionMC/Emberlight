package dev.emrion.shaders.mod.upscale;

import static dev.emrion.shaders.mod.EmrionShadersClient.LOG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Loads the NGX shim (bundled, extracted to {@code <gameDir>/emberlight/dlss}) and NVIDIA's models from there. */
public final class Dlss {
	private Dlss() {}

	static final Path DIR = Path.of(System.getProperty("emrion.upscale.dlssDir",
			dev.emrion.shaders.mod.platform.Platform.get().gameDir().resolve("emberlight/dlss").toString()));
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
	static final String SHIM = WINDOWS ? "emrion_ngx.dll" : "libemrion_ngx.so";
	/** NVIDIA's model libraries are named by prefix: libnvidia-ngx-dlss.so.310.x / nvngx_dlss.dll. */
	public static final String MODEL = WINDOWS ? "nvngx_dlss." : "libnvidia-ngx-dlss.so";
	public static final String FG_MODEL = WINDOWS ? "nvngx_dlssg." : "libnvidia-ngx-dlssg.so";
	/** DLSS Ray Reconstruction ("DLSS-D"). */
	public static final String RR_MODEL = WINDOWS ? "nvngx_dlssd." : "libnvidia-ngx-dlssd.so";
	public static final String BUILD_SCRIPT = WINDOWS ? "mod/native/build-dlss.ps1" : "mod/native/build-dlss.sh";
	private static final Linker LINKER = Linker.nativeLinker();
	private static final ValueLayout.OfLong PTR = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
	private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT;

	// above reason: initialReason() fills them, and static fields initialise in textual order
	/** NVIDIA's files that matched their pinned SHA-256 at the last {@link #verifyModels}. */
	private static volatile Set<String> verified = Set.of();
	/** A file NGX could load is not NVIDIA's original: DLSS stays off until it is downloaded again. */
	private static volatile boolean badModel;
	private static volatile String reason = initialReason();
	/** Why the last DLSS feature creation failed (VRAM full, ...); cleared by a settings change, so it is retried once then. */
	private static volatile String createFailure;
	private static SymbolLookup lib;
	static MethodHandle init, available, create, evaluate, evaluateMask, release, log, rrAvailable, rrCreate, rrEvaluate, setPreset;
	/** Why Ray Reconstruction cannot run (null = not known to fail); cleared by a settings change like {@link #createFailure}. */
	private static volatile String rrFailure;
	/** NGX's answer whether this GPU/driver supports RR: null = not asked yet, "" = yes, else why not (never cleared). */
	private static volatile String rrSupport;

	private static String initialReason() {
		boolean os = WINDOWS || System.getProperty("os.name", "").toLowerCase().contains("linux");
		if (!os || !"amd64".equals(System.getProperty("os.arch"))) return "Linux or Windows x86_64 only";
		try {
			DlssFiles.extractShim(DlssFiles.shimResource(WINDOWS), DIR.resolve(SHIM));
		} catch (java.io.IOException e) {
			// fail closed: the file there is not the bundled shim (e.g. an older one in use by another running game)
			LOG.warn("DLSS: could not extract the shim to {}", DIR, e);
			return "could not install the DLSS bridge into " + DIR + " (" + e.getMessage() + ")";
		}
		if (!Files.isRegularFile(DIR.resolve(SHIM))) return "this build has no DLSS bridge for this OS yet (or run " + BUILD_SCRIPT + ")";
		String bad = verifyModels();
		if (bad != null) return bad;
		if (!has(MODEL)) return NOT_DOWNLOADED;
		return null;
	}

	private static final String NOT_DOWNLOADED = "NVIDIA's DLSS files are not downloaded yet";

	/**
	 * Hashes every NGX library in the DLSS directory against the pinned hashes before NGX may load from there (a modpack could
	 * ship a modified one); null, or why DLSS must not run. ~0.1 s for the three models, once per start and after a download.
	 */
	private static String verifyModels() {
		var v = new java.util.HashSet<String>();
		String bad = DlssFiles.verify(DIR, DlssFiles.downloads(WINDOWS), WINDOWS ? "nvngx_" : "libnvidia-ngx-", v);
		verified = Set.copyOf(v);
		badModel = bad != null;
		if (bad != null) LOG.error("DLSS: {}", bad);
		return bad;
	}

	/** Whether a verified NVIDIA file starting with {@code prefix} (the model names carry the version) is in the DLSS directory. */
	public static boolean has(String prefix) {
		return verified.stream().anyMatch(n -> n.startsWith(prefix));
	}

	/** True when only NVIDIA's models are missing or damaged, so the consent screen can offer to download them. */
	public static boolean needsDownload() {
		String r = reason;
		return (r == null || r.equals(NOT_DOWNLOADED) || badModel) && !(has(MODEL) && has(FG_MODEL) && has(RR_MODEL));
	}

	/** The models were downloaded: NGX's device extensions are only added at start, so DLSS needs a restart. */
	public static void downloaded() {
		String bad = verifyModels();
		if (bad != null) fail(bad);
		else if (reason != null) fail("restart Minecraft to finish installing DLSS");
	}

	/** Where the shim and NVIDIA's models are expected. */
	public static Path directory() {
		return DIR;
	}

	/** Why DLSS cannot run here, or null when it may (the NGX init at first use can still fail). */
	public static String unavailable() {
		String r = reason;
		return r != null ? r : createFailure;
	}

	/** DLSS feature creation failed: mode DLSS runs FSR until the next settings change (never retried per frame). */
	static void failCreate(String why) {
		createFailure = why;
	}

	/** Upscaling settings changed: a failed feature creation may be tried again. */
	public static void retryCreate() {
		createFailure = null;
		rrFailure = null;
	}

	/**
	 * Why DLSS Ray Reconstruction cannot run, or null when it may. Before NGX is initialised (first DLSS use) only the files
	 * are checked; after it, also the GPU/driver support NGX reports.
	 */
	public static String rrUnavailable() {
		String r = unavailable();
		if (r != null) return r;
		if (!has(RR_MODEL)) return "the Ray Reconstruction model is not downloaded yet";
		if (initialised && rrSupport == null) {
			try (Arena a = Arena.ofConfined()) {
				MemorySegment needs = a.allocate(INT);
				int ok = rrAvailable == null ? 0 : (int) rrAvailable.invokeExact(needs);
				rrSupport = ok == 1 ? "" : rrAvailable == null ? "the DLSS bridge is too old (no Ray Reconstruction)"
						: needs.get(INT, 0) != 0 ? "the driver is too old for Ray Reconstruction" : "Ray Reconstruction not supported on this GPU";
			} catch (Throwable t) {
				rrSupport = "Ray Reconstruction query failed: " + t;
			}
		}
		String support = rrSupport;
		return support != null && !support.isEmpty() ? support : rrFailure;
	}

	/** Ray Reconstruction failed (feature creation): DLSS Super Resolution runs until the next settings change. */
	static void failRr(String why) {
		rrFailure = why;
	}

	/** An NGX result code for a person, with its header name (nvsdk_ngx_defs.h). */
	static String describe(int r) {
		String name = switch (r & 0xFFFFF) {
			case 1 -> "feature not supported";
			case 2 -> "platform error (a Vulkan call inside NGX failed, usually GPU memory is full)";
			case 5 -> "invalid parameter";
			case 7 -> "NGX not initialised";
			case 11 -> "unable to initialise the feature (model library not found)";
			case 12 -> "driver or model out of date";
			case 13 -> "out of GPU memory";
			case 15 -> "cannot write the NGX data path";
			case 17 -> "denied by NVIDIA for this application";
			default -> "error";
		};
		return name + " (0x" + Integer.toHexString(r) + ")";
	}

	/** NGX's buffered log lines since the last call (empty with an older shim that lacks emrion_ngx_log). */
	static String ngxLog() {
		if (log == null) return "";
		try (Arena a = Arena.ofConfined()) {
			MemorySegment buf = a.allocate(8192);
			log.invokeExact(buf, 8192);
			return buf.getString(0).strip();
		} catch (Throwable t) {
			return "";
		}
	}

	static void fail(String why) {
		reason = why;
	}

	private static synchronized SymbolLookup lib() {
		if (lib == null) {
			lib = SymbolLookup.libraryLookup(DIR.resolve(SHIM), Arena.global());
			init = fn("emrion_ngx_init", FunctionDescriptor.of(INT, PTR, PTR, PTR, PTR, PTR, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT));
			available = fn("emrion_ngx_available", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
			create = fn("emrion_ngx_create", FunctionDescriptor.of(INT, PTR, INT, INT, INT, INT, INT, INT, ValueLayout.ADDRESS));
			evaluate = fn("emrion_ngx_evaluate", FunctionDescriptor.of(INT, PTR, PTR, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT, INT, INT,
					FLOAT, FLOAT, FLOAT, FLOAT, INT, FLOAT));
			release = fn("emrion_ngx_release", FunctionDescriptor.of(INT, PTR));
			// shims built before the reactive mask lack it: DLSS then runs without the bias mask
			evaluateMask = lib.find("emrion_ngx_evaluate_mask").map(a -> LINKER.downcallHandle(a, FunctionDescriptor.of(INT, PTR, PTR,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT, INT, INT, FLOAT, FLOAT, FLOAT, FLOAT, INT, FLOAT))).orElse(null);
			// shims built before Ray Reconstruction lack it: RR is then reported unavailable
			rrAvailable = lib.find("emrion_ngx_rr_available").map(a -> LINKER.downcallHandle(a, FunctionDescriptor.of(INT, ValueLayout.ADDRESS))).orElse(null);
			rrCreate = lib.find("emrion_ngx_rr_create").map(a -> LINKER.downcallHandle(a, FunctionDescriptor.of(INT, PTR, INT, INT, INT, INT, INT, INT,
					ValueLayout.ADDRESS))).orElse(null);
			rrEvaluate = lib.find("emrion_ngx_rr_evaluate").map(a -> LINKER.downcallHandle(a, FunctionDescriptor.of(INT, PTR, PTR, ValueLayout.ADDRESS,
					ValueLayout.ADDRESS, INT, INT, INT, INT, FLOAT, FLOAT, FLOAT, FLOAT, INT, FLOAT, INT, ValueLayout.ADDRESS))).orElse(null);
			// shims built before the preset choice lack it: NGX then picks the preset
			setPreset = lib.find("emrion_ngx_set_preset").map(a -> LINKER.downcallHandle(a, FunctionDescriptor.ofVoid(INT))).orElse(null);
			log = lib.find("emrion_ngx_log").map(a -> LINKER.downcallHandle(a, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT))).orElse(null);
		}
		return lib;
	}

	/** A shim function for other users of NGX (frame generation); empty when the shim lacks it. */
	public static java.util.Optional<MethodHandle> function(String name, FunctionDescriptor d) {
		lib();
		return lib.find(name).map(a -> LINKER.downcallHandle(a, d));
	}

	/** NGX init for other features; null or why NGX cannot run. */
	public static String init(dev.emrion.shaders.mod.vk.VulkanContext ctx) {
		return ensureInit(ctx);
	}

	/** Registers an NGX feature handle so {@link #shutdown} releases it; {@link #releaseFeature} releases it earlier. */
	public static void track(long handle) {
		features.add(handle);
	}

	public static void releaseFeature(long handle) {
		release(handle);
	}

	public static boolean failedResult(int ngxResult) {
		return failed(ngxResult);
	}

	private static MethodHandle fn(String name, FunctionDescriptor d) {
		return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new IllegalStateException("no " + name + " in the DLSS shim")), d);
	}

	/** Device extensions NGX needs; its VK_EXT_buffer_device_address clashes with core bufferDeviceAddress, so the core feature is used. */
	public static Set<String> deviceExtensions() {
		if (reason != null) return Set.of();
		try (Arena a = Arena.ofConfined()) {
			lib();
			MethodHandle h = LINKER.downcallHandle(lib.find("emrion_ngx_required_extensions").orElseThrow(),
					FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			MemorySegment ic = a.allocate(INT), ie = a.allocate(ValueLayout.ADDRESS), dc = a.allocate(INT), de = a.allocate(ValueLayout.ADDRESS);
			int r = (int) h.invokeExact(ic, ie, dc, de);
			if ((r & 0xFFF00000) == 0xBAD00000) throw new IllegalStateException("NGX error 0x" + Integer.toHexString(r));
			int n = dc.get(INT, 0);
			MemorySegment list = de.get(ValueLayout.ADDRESS, 0).reinterpret(8L * n);
			Set<String> out = new java.util.TreeSet<>();
			for (int i = 0; i < n; i++) out.add(list.getAtIndex(ValueLayout.ADDRESS, i).reinterpret(256).getString(0));
			out.remove("VK_EXT_buffer_device_address");
			return out;
		} catch (Throwable t) {
			fail("DLSS shim failed to load: " + t.getMessage());
			return Set.of();
		}
	}

	private static boolean initialised;
	/** NGX itself is up (init returned success), even when DLSS SR then turned out unavailable: {@link #shutdown} must still run. */
	private static boolean ngxUp;

	/** NGX init on MC's device, once; returns null or why DLSS cannot run (also stored for the UI). */
	static synchronized String ensureInit(dev.emrion.shaders.mod.vk.VulkanContext ctx) {
		if (reason != null || initialised) return reason;
		if (ctx.properties.vendorID() != 0x10DE) {
			fail("not an NVIDIA GPU");
			return reason;
		}
		if (!enabledOnDevice) {
			fail("the device was created without NGX's extensions (restart after installing the DLSS files)");
			return reason;
		}
		try (Arena a = Arena.ofShared()) {
			lib();
			var dev = ctx.vk;
			var pd = dev.getPhysicalDevice();
			long gipa = org.lwjgl.vulkan.VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
			long gdpa = 0; // optional: NGX resolves it through gipa
			Path logs = DIR.resolve("logs");
			Files.createDirectories(logs);
			MemorySegment logPath = a.allocateFrom(logs.toString()), dirPath = a.allocateFrom(DIR.toString());
			int r = onBigStack(() -> (int) init.invokeExact(pd.getInstance().address(), pd.address(), dev.address(), gipa, gdpa, logPath, dirPath,
					Boolean.getBoolean("emrion.upscale.dlssLog") ? 1 : 0));
			// a failure means NGX is down again (the shim shuts it down when the capability query after init fails)
			ngxUp = !failed(r);
			if (failed(r)) {
				fail("NGX init failed: " + describe(r));
				LOG.error("DLSS: {}; NGX log:\n{}", reason, ngxLog());
				return reason;
			}
			MemorySegment needs = a.allocate(INT);
			int ok = (int) available.invokeExact(needs);
			if (ok != 1) {
				fail(needs.get(INT, 0) != 0 ? "the driver is too old for this DLSS version" : "DLSS Super Resolution not supported on this GPU");
				return reason;
			}
			initialised = true;
			return null;
		} catch (Throwable t) {
			fail("NGX init failed: " + t);
			return reason;
		}
	}

	public static final String FEATURE_SET = "Emberlight DLSS";
	/** NVIDIA's RTX SDK licence asks for this attribution wherever DLSS is offered. */
	public static final String TRADEMARKS = "NVIDIA, NVIDIA RTX and DLSS are trademarks of NVIDIA Corporation.";

	public interface NativeCall {
		int call() throws Throwable;
	}

	/** NGX's model loading needs more stack than a JVM thread's default 1 MiB (it crashes on the render thread). */
	public static int onBigStack(NativeCall c) throws Throwable {
		int[] r = new int[1];
		Throwable[] err = new Throwable[1];
		Thread t = new Thread(null, () -> {
			try {
				r[0] = c.call();
			} catch (Throwable e) {
				err[0] = e;
			}
		}, "Emberlight NGX", 64L << 20);
		t.start();
		// never return while the worker runs: the caller then closes the arena holding the call's arguments and outputs
		boolean interrupted = false;
		while (t.isAlive()) {
			try {
				t.join();
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}
		if (interrupted) Thread.currentThread().interrupt();
		if (err[0] != null) throw err[0];
		return r[0];
	}

	/** Live NGX features (handles); released at the latest by {@link #shutdown}. */
	static final java.util.Set<Long> features = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Releases a feature once (from the destroy queue, or from {@link #shutdown}, whichever comes first). */
	static void release(long handle) {
		if (!features.remove(handle)) return;
		try {
			int r = (int) release.invokeExact(handle);
		} catch (Throwable ignored) {
			// nothing to recover
		}
	}

	/** Client stopping, before MC destroys the device: NGX's threads and resources must be gone first or it crashes. */
	public static synchronized void shutdown(dev.emrion.shaders.mod.vk.VulkanContext ctx) {
		if (!ngxUp) return;
		org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(ctx.vk);
		for (Long h : java.util.List.copyOf(features)) release(h);
		try {
			MethodHandle h = LINKER.downcallHandle(lib.find("emrion_ngx_shutdown").orElseThrow(), FunctionDescriptor.of(INT, PTR));
			int r = onBigStack(() -> (int) h.invokeExact(ctx.vk.address()));
			LOG.info("DLSS: NGX shut down (0x{})", Integer.toHexString(r));
		} catch (Throwable ignored) {
			// exiting anyway
		}
		initialised = ngxUp = false;
		fail("shut down");
	}

	/** Set at device creation (VulkanBackendRtMixin) when MC enabled the "Emberlight DLSS" set. */
	public static volatile boolean enabledOnDevice;

	static boolean failed(int ngxResult) {
		return (ngxResult & 0xFFF00000) == 0xBAD00000;
	}
}
