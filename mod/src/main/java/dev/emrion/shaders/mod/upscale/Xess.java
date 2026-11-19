package dev.emrion.shaders.mod.upscale;

import static dev.emrion.shaders.mod.EmrionShadersClient.LOG;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.zip.ZipFile;
import org.lwjgl.vulkan.VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderIntegerDotProductFeatures;

/**
 * Intel XeSS Super Resolution (docs/UPSCALING.md §12). Intel ships the runtime ({@code libxess.dll}) for Windows only. It is
 * downloaded on request from Intel's GitHub release (the SDK zip, pinned SHA-256), checked again before loading, and called
 * directly through FFM: its C API needs no shim.
 */
public final class Xess {
	private Xess() {}

	static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
	static final Path DIR = Path.of(System.getProperty("emrion.upscale.xessDir",
			net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("emberlight/xess").toString()));
	public static final String TAG = "v3.0.2";
	public static final String LICENCE_URL = "https://github.com/intel/xess/blob/" + TAG + "/LICENSE.txt";
	/** The SDK zip of the release (Intel publishes no separate runtime download); only two files are kept from it. */
	public static final DlssFiles.Remote ZIP = new DlssFiles.Remote("XeSS_SDK_3.0.2.zip", "XeSS_SDK_3.0.2.zip", 76839749,
			"88b8a373f30e33f3558a77a93e634f11b8132fc3047ea1a8edeead32b8471990");
	static final String BASE_URL = "https://github.com/intel/xess/releases/download/" + TAG + "/";
	static final String DLL = "libxess.dll";
	private static final String DLL_SHA256 = "251659dd84a3e84de67c886a4186e01f3eca49b00641906fe38bb6b807e5d5b7";
	static final String LICENCE_FILE = "INTEL-XESS-LICENSE.txt";
	/** Intel's licence asks for its copyright notice wherever the runtime is redistributed or offered. */
	public static final String NOTICE = "Intel(R) Xe Super Sampling (XeSS) SDK: Copyright (C) 2025 Intel Corporation.";

	private static final String NOT_DOWNLOADED = "Intel's XeSS runtime is not downloaded yet";
	private static final String DAMAGED = DLL + " does not match Intel's " + TAG + " release, download it again";
	private static volatile String reason = initialReason();
	/** Why the last context creation failed; cleared by a settings change ({@link #retryCreate}). */
	private static volatile String createFailure;
	/** Set at device creation (VulkanBackendRtMixin) when MC enabled {@link #FEATURE_SET}. */
	public static volatile boolean enabledOnDevice;

	private static String initialReason() {
		if (!WINDOWS || !"amd64".equals(System.getProperty("os.arch"))) return "Windows x86_64 only (Intel ships no Linux runtime)";
		return Files.isRegularFile(DIR.resolve(DLL)) ? null : NOT_DOWNLOADED;
	}

	/** Required: VK_EXT_mutable_descriptor_type (plus shaderStorageImageWriteWithoutFormat, which the pack sets already request). */
	public static final FeatureSet FEATURE_SET = new FeatureSet("Emberlight XeSS", Set.of("VK_EXT_mutable_descriptor_type"),
			Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT.class), "mutableDescriptorType")));

	/** XeSS's own feature sets when the runtime is installed (empty otherwise, so the device is unchanged); int8 and dot product only speed up its DP4a path. */
	public static List<FeatureSet> featureSets() {
		if (reason != null) return List.of();
		return List.of(FEATURE_SET, new FeatureSet("Emberlight XeSS int8", Set.of(), Set.of(new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderInt8"))),
				new FeatureSet("Emberlight XeSS dot product", Set.of("VK_KHR_shader_integer_dot_product"),
						Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceShaderIntegerDotProductFeatures.class), "shaderIntegerDotProduct"))));
	}

	/** Why XeSS cannot run here, or null when it may (context creation can still fail). */
	public static String unavailable() {
		String r = reason;
		return r != null ? r : createFailure;
	}

	/** Only the download is missing (or the file is damaged): the Upscaling page offers it. */
	public static boolean needsDownload() {
		return NOT_DOWNLOADED.equals(reason) || DAMAGED.equals(reason);
	}

	/** Linux/macOS: the mode is not offered at all. */
	public static boolean platformSupported() {
		return WINDOWS;
	}

	static void failCreate(String why) {
		createFailure = why;
	}

	public static void retryCreate() {
		createFailure = null;
	}

	public static Path directory() {
		return DIR;
	}

	/**
	 * Downloads the SDK zip into {@link #DIR}, keeps {@code libxess.dll} (hash-checked) and Intel's licence, deletes the zip.
	 * The device extensions are only added at start, so XeSS needs a restart afterwards.
	 */
	public static void download(LongConsumer progress) throws IOException {
		DlssFiles.download(BASE_URL, List.of(ZIP), DIR, progress);
		Path zip = DIR.resolve(ZIP.name());
		try (ZipFile z = new ZipFile(zip.toFile())) {
			extract(z, "LICENSE.txt", DIR.resolve(LICENCE_FILE));
			extract(z, "bin/" + DLL, DIR.resolve(DLL));
		} finally {
			Files.deleteIfExists(zip);
		}
		if (!DlssFiles.sha256(DIR.resolve(DLL)).equals(DLL_SHA256)) {
			Files.deleteIfExists(DIR.resolve(DLL));
			throw new IOException(DLL + ": SHA-256 mismatch");
		}
		if (reason != null) reason = "restart Minecraft to finish installing XeSS";
	}

	private static void extract(ZipFile z, String entry, Path target) throws IOException {
		var e = z.getEntry(entry);
		if (e == null) throw new IOException(entry + " missing in " + ZIP.name());
		Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
		try (InputStream in = z.getInputStream(e)) {
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	private static final Linker LINKER = Linker.nativeLinker();
	private static final ValueLayout.OfLong PTR = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
	private static final java.lang.foreign.AddressLayout ADDR = ValueLayout.ADDRESS;
	private static SymbolLookup lib;
	static MethodHandle createContext, init, execute, destroyContext, setVelocityScale;

	/** Loads libxess.dll once, after checking its hash again (it lives in the game directory); null or why it cannot. */
	static synchronized String load() {
		if (unavailable() != null || lib != null) return unavailable();
		if (!enabledOnDevice) return fail("the device was created without XeSS's extensions (restart after installing XeSS)");
		try {
			if (!DlssFiles.sha256(DIR.resolve(DLL)).equals(DLL_SHA256)) return fail(DAMAGED);
			lib = SymbolLookup.libraryLookup(DIR.resolve(DLL), Arena.global());
			createContext = fn("xessVKCreateContext", FunctionDescriptor.of(INT, PTR, PTR, PTR, ADDR));
			init = fn("xessVKInit", FunctionDescriptor.of(INT, PTR, ADDR));
			execute = fn("xessVKExecute", FunctionDescriptor.of(INT, PTR, PTR, ADDR));
			destroyContext = fn("xessDestroyContext", FunctionDescriptor.of(INT, PTR));
			setVelocityScale = fn("xessSetVelocityScale", FunctionDescriptor.of(INT, PTR, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
			return null;
		} catch (IOException | RuntimeException e) {
			lib = null;
			LOG.error("XeSS: loading {} failed", DLL, e);
			return fail("loading " + DLL + " failed: " + e.getMessage());
		}
	}

	private static String fail(String why) {
		reason = why;
		return why;
	}

	private static MethodHandle fn(String name, FunctionDescriptor d) {
		return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new IllegalStateException("no " + name + " in " + DLL)), d);
	}

	/** xess_result_t for a person (xess.h); warnings (> 0) are not failures. */
	static String describe(int r) {
		return switch (r) {
			case -1 -> "unsupported device";
			case -2 -> "unsupported driver";
			case -4 -> "invalid argument";
			case -5 -> "out of GPU memory";
			case -6 -> "device error";
			case -10 -> "unsupported";
			case -11 -> "cannot load a library";
			default -> "error";
		} + " (" + r + ")";
	}
}
