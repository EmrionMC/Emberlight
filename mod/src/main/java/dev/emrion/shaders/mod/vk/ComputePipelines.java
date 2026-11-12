package dev.emrion.shaders.mod.vk;

import static org.lwjgl.vulkan.VK10.*;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;

public final class ComputePipelines {
	private ComputePipelines() {}

	public static long create(VulkanContext ctx, long module, long layout, long pipelineCache) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(layout);
			info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
			LongBuffer p = stack.mallocLong(1);
			VulkanContext.check(vkCreateComputePipelines(ctx.vk, pipelineCache, info, null, p), "vkCreateComputePipelines");
			return p.get(0);
		}
	}
}
