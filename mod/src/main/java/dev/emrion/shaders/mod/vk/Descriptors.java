package dev.emrion.shaders.mod.vk;

import static org.lwjgl.vulkan.VK10.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Descriptor set layouts, pipeline layouts and descriptor writes. All images are in GENERAL layout (MC's model). */
public final class Descriptors {
	private Descriptors() {}

	public static final class LayoutBuilder {
		private record Entry(int binding, int type, int stages) {}
		private final List<Entry> entries = new ArrayList<>();

		public LayoutBuilder add(int binding, int vkDescriptorType, int vkStages) {
			entries.add(new Entry(binding, vkDescriptorType, vkStages));
			return this;
		}

		/** {@code push}: a push-descriptor layout (vkCmdPushDescriptorSetKHR), as MC uses for set 0. */
		public long build(VulkanContext ctx, boolean push) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				var bindings = VkDescriptorSetLayoutBinding.calloc(entries.size(), stack);
				for (Entry e : entries) bindings.get().binding(e.binding).descriptorType(e.type).descriptorCount(1).stageFlags(e.stages);
				bindings.flip();
				var info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings)
						.flags(push ? KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR : 0);
				LongBuffer p = stack.mallocLong(1);
				VulkanContext.check(vkCreateDescriptorSetLayout(ctx.vk, info, null, p), "vkCreateDescriptorSetLayout");
				return p.get(0);
			}
		}
	}

	/** Push constants use {@code VK_SHADER_STAGE_ALL}, like MC (its {@code pushConstants} passes that stage mask). */
	public static long pipelineLayout(VulkanContext ctx, int pushConstantSize, long... setLayouts) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayouts));
			if (pushConstantSize > 0) {
				info.pPushConstantRanges(VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_ALL).offset(0).size(pushConstantSize));
			}
			LongBuffer p = stack.mallocLong(1);
			VulkanContext.check(vkCreatePipelineLayout(ctx.vk, info, null, p), "vkCreatePipelineLayout");
			return p.get(0);
		}
	}

	public static void writeImage(VulkanContext ctx, long set, int binding, int type, long view, long sampler) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var image = VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
			var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(set).dstBinding(binding)
					.descriptorType(type).descriptorCount(1).pImageInfo(image);
			vkUpdateDescriptorSets(ctx.vk, write, null);
		}
	}

	public static void writeBuffer(VulkanContext ctx, long set, int binding, int type, long buffer, long offset, long range) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer).offset(offset).range(range);
			var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(set).dstBinding(binding)
					.descriptorType(type).descriptorCount(1).pBufferInfo(info);
			vkUpdateDescriptorSets(ctx.vk, write, null);
		}
	}
}
