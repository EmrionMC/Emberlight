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
	private static @Nullable VulkanContext instance;

	public final VulkanDevice device;
	public final VkDevice vk;
	public final long vma;
	public final VulkanQueue graphicsQueue;
	public final VulkanCommandEncoder encoder;
	/** Owned copy (MC frees its physical-device structs after device creation). Never freed: lives as long as the device. */
	public final VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc();

	private VulkanContext(VulkanDevice device) {
		this.device = device;
		this.vk = device.vkDevice();
		this.vma = device.vma();
		this.graphicsQueue = device.graphicsQueue();
		this.encoder = device.createCommandEncoder();
		VK10.vkGetPhysicalDeviceProperties(vk.getPhysicalDevice(), properties);
	}

	public static VulkanContext get() {
		if (instance == null) {
			RenderSystem.assertOnRenderThread();
			instance = new VulkanContext((VulkanDevice) ((FrontendGpuDeviceAccessor) RenderSystem.getDevice()).emrion$backend());
		}
		return instance;
	}

	private @Nullable PipelineCacheStore pipelineCache;

	/** The shared, persisted VkPipelineCache (created on first use). */
	public PipelineCacheStore pipelineCache() {
		if (pipelineCache == null) pipelineCache = new PipelineCacheStore(this, FabricLoader.getInstance().getGameDir().resolve("emrionshaders"));
		return pipelineCache;
	}

	/** Saves the pipeline cache if this session created one (called on client shutdown). */
	public static void onShutdown() {
		if (instance != null && instance.pipelineCache != null) instance.pipelineCache.save();
	}

	public VkPhysicalDeviceLimits limits() {
		return properties.limits();
	}

	/** Destroys after the submits that may still use it have retired (MC's 2-submit destroy queue). */
	public void destroyLater(Destroyable d) {
		encoder.queueForDestroy(d);
	}

	static void check(int result, String what) {
		if (result != VK10.VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + result);
	}
}
