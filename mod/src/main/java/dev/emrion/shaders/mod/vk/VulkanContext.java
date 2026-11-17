package dev.emrion.shaders.mod.vk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.Destroyable;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import dev.emrion.shaders.mod.mixin.FrontendGpuDeviceAccessor;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/** Raw Vulkan handles of MC's device. Created lazily on the render thread, only on the Vulkan backend. */
public final class VulkanContext {
	private static volatile @Nullable VulkanContext instance;

	public final VulkanDevice device;
	public final VkDevice vk;
	public final long vma;
	public final VulkanQueue graphicsQueue;
	public final VulkanCommandEncoder encoder;
	/** Owned copy (MC frees its physical-device structs after device creation). Never freed: lives as long as the device. */
	public final VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc();
	/** VulkanFeatureSetsMixin requests this as optional, so MC enables it exactly when the device supports it. */
	public final boolean storageWriteWithoutFormat;
	/** Wireframe (polygon mode LINE); requested the same way. */
	public final boolean fillModeNonSolid;

	private VulkanContext(VulkanDevice device) {
		this.device = device;
		this.vk = device.vkDevice();
		this.vma = device.vma();
		this.graphicsQueue = device.graphicsQueue();
		this.encoder = device.createCommandEncoder();
		VK10.vkGetPhysicalDeviceProperties(vk.getPhysicalDevice(), properties);
		try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
			var features = org.lwjgl.vulkan.VkPhysicalDeviceFeatures.calloc(stack);
			VK10.vkGetPhysicalDeviceFeatures(vk.getPhysicalDevice(), features);
			storageWriteWithoutFormat = features.shaderStorageImageWriteWithoutFormat();
			fillModeNonSolid = features.fillModeNonSolid();
		}
	}

	public static VulkanContext get() {
		if (instance == null) {
			RenderSystem.assertOnRenderThread();
			instance = new VulkanContext((VulkanDevice) ((FrontendGpuDeviceAccessor) RenderSystem.getDevice()).emrion$backend());
		}
		return instance;
	}

	private volatile @Nullable PipelineCacheStore pipelineCache;

	/** The persisted cache's handle for MC's own pipelines (any thread); 0 before it exists and after shutdown. */
	public static long pipelineCacheHandle() {
		VulkanContext c = instance;
		PipelineCacheStore s = c == null ? null : c.pipelineCache;
		return s == null ? 0 : s.handle;
	}

	/** The shared, persisted VkPipelineCache (created on first use). */
	public PipelineCacheStore pipelineCache() {
		// -Demrion.pipelineCacheDir: one cache shared by several dev run dirs; the driver validates the data
		String dir = System.getProperty("emrion.pipelineCacheDir");
		if (pipelineCache == null) pipelineCache = new PipelineCacheStore(this, dir != null ? java.nio.file.Path.of(dir) : FabricLoader.getInstance().getGameDir().resolve("emberlight"));
		return pipelineCache;
	}

	/**
	 * Client shutdown: saves and destroys the pipeline cache if this session created one. Only once no background build can
	 * use it any more ({@code buildsDone}); otherwise it is saved and left to the driver's device teardown.
	 */
	public static void onShutdown(boolean buildsDone) {
		if (instance == null || instance.pipelineCache == null) return;
		instance.pipelineCache.save();
		if (!buildsDone) return;
		org.lwjgl.vulkan.VK10.vkDestroyPipelineCache(instance.vk, instance.pipelineCache.handle, null);
		instance.pipelineCache = null;
	}

	public VkPhysicalDeviceLimits limits() {
		return properties.limits();
	}

	/** Destroys after the submits that may still use it have retired (MC's 2-submit destroy queue). */
	public void destroyLater(Destroyable d) {
		encoder.queueForDestroy(d);
	}

	static void check(int result, String what) {
		if (result == VK10.VK_SUCCESS) return;
		String name = switch (result) {
			case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "out of memory";
			case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "out of GPU memory";
			case VK10.VK_ERROR_DEVICE_LOST -> "GPU device lost";
			default -> "VkResult " + result;
		};
		throw new IllegalStateException(what + " failed: " + name);
	}
}
