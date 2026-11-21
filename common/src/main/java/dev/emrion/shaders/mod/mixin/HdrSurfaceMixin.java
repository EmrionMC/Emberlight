package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.emrion.shaders.mod.hdr.Hdr;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: HDR output needs an HDR swapchain colour space and an encoded image in place of MC's SDR main target (Hdr). */
@Mixin(VulkanGpuSurface.class)
abstract class HdrSurfaceMixin {
	@Shadow private boolean swapchainSuboptimal;
	@Shadow private boolean swapchainOutOfDate;
	/** This frame's encoded image for MC's blit, or 0. */
	@Unique private long emrion$hdrSource;

	@WrapOperation(method = "configure", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int emrion$hdrSwapchain(VkDevice device, VkSwapchainCreateInfoKHR info, VkAllocationCallbacks alloc, LongBuffer out, Operation<Integer> original) {
		Hdr.chooseSwapchain(device, info);
		int r = original.call(device, info, alloc, out);
		Hdr.swapchainCreated(device, r, r == 0 ? out.get(0) : 0, info.imageFormat());
		return r;
	}

	@Inject(method = "blitFromTexture", at = @At("HEAD"))
	private void emrion$hdrEncode(CommandEncoderBackend encoder, GpuTextureView view, CallbackInfo ci) {
		emrion$hdrSource = 0;
		if (Hdr.takeReconfigure()) swapchainSuboptimal = true; // HDR settings changed: MC recreates the swapchain after this frame
		if (swapchainOutOfDate) return;
		emrion$hdrSource = Hdr.beforeBlit(((VulkanGpuTextureView) view).vkImageView(), view.getWidth(0), view.getHeight(0));
	}

	@WrapOperation(method = "blitFromTexture", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCmdBlitImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JIJILorg/lwjgl/vulkan/VkImageBlit$Buffer;I)V"))
	private void emrion$hdrBlit(VkCommandBuffer cb, long src, int srcLayout, long dst, int dstLayout, VkImageBlit.Buffer regions, int filter, Operation<Void> original) {
		original.call(cb, emrion$hdrSource != 0 ? emrion$hdrSource : src, srcLayout, dst, dstLayout, regions, filter); // both GENERAL
	}
}
