package dev.emrion.shaders.mod.vk;

import static org.lwjgl.vulkan.VK10.*;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Dynamic-rendering pass recording for raw fullscreen passes. Attachments are in GENERAL and keep their contents. */
public final class RawPass {
	private RawPass() {}

	/** Begins rendering into {@code colorViews} (loadOp LOAD, storeOp STORE) and sets the full-size viewport and scissor. */
	public static void begin(VkCommandBuffer cb, int width, int height, long... colorViews) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var atts = VkRenderingAttachmentInfo.calloc(colorViews.length, stack);
			for (long v : colorViews) {
				atts.get().sType$Default().imageView(v).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
						.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
			}
			atts.flip();
			var info = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(atts);
			info.renderArea().extent().set(width, height);
			KHRDynamicRendering.vkCmdBeginRenderingKHR(cb, info);
			vkCmdSetViewport(cb, 0, VkViewport.calloc(1, stack).width(width).height(height).maxDepth(1.0F));
			var scissor = VkRect2D.calloc(1, stack);
			scissor.extent().set(width, height);
			vkCmdSetScissor(cb, 0, scissor);
		}
	}

	public static void end(VkCommandBuffer cb) {
		KHRDynamicRendering.vkCmdEndRenderingKHR(cb);
	}

	/** Pushes {@code views[i]} at binding i of set 0 as {@code type} (combined image sampler or storage image). */
	public static void pushImages(VkCommandBuffer cb, int bindPoint, long layout, int type, long sampler, long... views) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var writes = VkWriteDescriptorSet.calloc(views.length, stack);
			for (int i = 0; i < views.length; i++) {
				var image = VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(views[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
				writes.get(i).sType$Default().dstBinding(i).descriptorType(type).descriptorCount(1).pImageInfo(image);
			}
			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, bindPoint, layout, 0, writes);
		}
	}
}
