package dev.emrion.shaders.mod.rt;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;

/**
 * Native passes with {@code "raygen": true} as ray generation shaders (request "raygen", NativeGlsl): a raygen-only RT pipeline,
 * no hit, miss or callable shaders, the traces stay ray queries; the SBT holds the one raygen record. On the RTX 4070 Ti the same
 * rt_gi code took up to 0.2 ms (30 %) less GPU time this way than as a compute dispatch (image identical); restir got slower. Built for
 * Shader Execution Reordering, which only exists in raygen shaders, but reorderThreadNV (hint: sky / cached / full shading,
 * or the hit's Morton cell) cost about as much as it saved, so the passes do not reorder.
 */
public final class RtRaygen {
	private RtRaygen() {}

	/** Optional device set, enabled with RT; {@link #supported} records the outcome at device creation. */
	public static final FeatureSet FEATURE_SET = new FeatureSet("Emberlight RT pipelines", Set.of(VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME),
			Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class), "rayTracingPipeline")));
	public static volatile boolean supported;

	/** A raygen-only pipeline and its SBT (one raygen record). */
	public record Pipeline(long handle, RawBuffer sbt, long raygenAddress, long raygenSize) {
		public void destroy(VulkanContext ctx) {
			vkDestroyPipeline(ctx.vk, handle, null);
			sbt.destroyLater();
		}
	}

	/** True when the SPIR-V's (first) entry point is a ray generation shader. */
	public static boolean isRaygen(byte[] spv) {
		ByteBuffer b = ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 20; i + 8 <= spv.length; ) {
			int w = b.getInt(i), count = w >>> 16;
			if ((w & 0xffff) == 15) return b.getInt(i + 4) == 5313; // OpEntryPoint, ExecutionModel RayGenerationKHR
			if (count == 0) return false;
			i += count * 4;
		}
		return false;
	}

	public static Pipeline create(VulkanContext ctx, long module, long layout, long cache) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var props = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
			org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2(ctx.vk.getPhysicalDevice(), VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(props));
			int handleSize = props.shaderGroupHandleSize(), base = props.shaderGroupBaseAlignment();
			var stage = org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo.calloc(1, stack).sType$Default()
					.stage(VK_SHADER_STAGE_RAYGEN_BIT_KHR).module(module).pName(stack.UTF8("main"));
			var group = org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR.calloc(1, stack).sType$Default()
					.type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(0)
					.closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
			var info = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack).sType$Default().pStages(stage).pGroups(group)
					.maxPipelineRayRecursionDepth(0).layout(layout); // (no traceRay: ray queries only)
			LongBuffer p = stack.mallocLong(1);
			RtScene.check(vkCreateRayTracingPipelinesKHR(ctx.vk, VK_NULL_HANDLE, cache, info, null, p), "vkCreateRayTracingPipelinesKHR");
			long pipeline = p.get(0);
			try {
			ByteBuffer handle = stack.malloc(handleSize);
			RtScene.check(vkGetRayTracingShaderGroupHandlesKHR(ctx.vk, pipeline, 0, 1, handle), "vkGetRayTracingShaderGroupHandlesKHR");
			// the raygen record must start at a multiple of shaderGroupBaseAlignment: room to slide it there
			long size = (handleSize + base - 1) / base * base;
			var sbt = new RawBuffer(ctx, size + base, VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true, "raygen SBT");
			long address = vkGetBufferDeviceAddress(ctx, sbt.handle, stack);
			long aligned = (address + base - 1) / base * base;
			MemoryUtil.memCopy(MemoryUtil.memAddress(handle), sbt.mapped + (aligned - address), handleSize);
			return new Pipeline(pipeline, sbt, aligned, size);
			} catch (RuntimeException e) {
				vkDestroyPipeline(ctx.vk, pipeline, null);
				throw e;
			}
		}
	}

	private static long vkGetBufferDeviceAddress(VulkanContext ctx, long buffer, MemoryStack stack) {
		return org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress(ctx.vk, VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
	}

	/** One raygen invocation per (x, y, z): like a compute dispatch of x * y * z invocations. */
	public static void trace(VkCommandBuffer cb, Pipeline p, int x, int y, int z) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var raygen = VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(p.raygenAddress).stride(p.raygenSize).size(p.raygenSize);
			var none = VkStridedDeviceAddressRegionKHR.calloc(stack);
			vkCmdTraceRaysKHR(cb, raygen, none, none, none, x, y, z);
		}
	}
}
