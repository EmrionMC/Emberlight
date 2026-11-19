package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.emrion.shaders.mod.framegen.FrameGen;
import dev.emrion.shaders.mod.framegen.Presenter;
import it.unimi.dsi.fastutil.longs.LongList;
import java.nio.IntBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: frame generation dispatches after the GUI and presents from its own thread; swapchain access goes through Presenter.LOCK. */
@Mixin(VulkanGpuSurface.class)
abstract class FrameGenSurfaceMixin {
	@Shadow private long swapchain;
	@Shadow private int swapchainWidth;
	@Shadow private int swapchainHeight;
	@Shadow @org.spongepowered.asm.mixin.Final private LongList swapchainImages;
	@Shadow private long[] presentSemaphores;
	@Shadow private int currentImageIndex;
	@Shadow private @Nullable SurfaceException eatenException;
	@Shadow private boolean swapchainSuboptimal;
	@Shadow private boolean swapchainOutOfDate;

	/** Whether the current swapchain was created with frame generation's extra images. */
	@org.spongepowered.asm.mixin.Unique private boolean emrion$fgImages;

	/**
	 * With FG up to SLOTS images are held when the next one is acquired (MC's frames handed to the presenter, not yet presented,
	 * plus MC's or the presenter's next); Vulkan only guarantees an acquire while at most images - minImageCount are held.
	 * With 2 extra the presenter waited its 1 s acquire timeout and the render thread with it (fg-wait hitches).
	 */
	@org.spongepowered.asm.mixin.injection.ModifyArg(method = "configure", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;minImageCount(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"))
	private int emrion$fgImageCount(int count, @com.llamalad7.mixinextras.sugar.Local org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR caps) {
		emrion$fgImages = dev.emrion.shaders.mod.framegen.FrameGenConfig.effective() != dev.emrion.shaders.mod.framegen.FrameGenConfig.Mode.OFF;
		if (!emrion$fgImages) return count;
		int want = Math.max(count, caps.minImageCount() + Presenter.SLOTS);
		if (caps.maxImageCount() != 0 && want > caps.maxImageCount())
			dev.emrion.shaders.mod.EmrionShadersClient.LOG.warn("Frame generation: the surface allows only {} swapchain images ({} wanted); some interpolated frames will be skipped",
					caps.maxImageCount(), want);
		return caps.maxImageCount() == 0 ? want : Math.min(want, caps.maxImageCount());
	}

	/** Logs the present mode while frame generation runs: FIFO caps what reaches the screen at the refresh rate. */
	@org.spongepowered.asm.mixin.injection.ModifyArg(method = "configure", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;presentMode(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"))
	private int emrion$fgPresentMode(int mode, @com.llamalad7.mixinextras.sugar.Local org.lwjgl.vulkan.VkSwapchainCreateInfoKHR info) {
		if (emrion$fgImages) dev.emrion.shaders.mod.EmrionShadersClient.LOG.info("Frame generation swapchain: present mode {} ({}), {}x{}, at least {} images",
				mode, switch (mode) { case 0 -> "immediate"; case 1 -> "mailbox"; case 2 -> "FIFO"; case 3 -> "FIFO relaxed"; default -> "?"; },
				swapchainWidth, swapchainHeight, info.minImageCount());
		return mode;
	}

	@Inject(method = "blitFromTexture", at = @At("HEAD"))
	private void emrion$fgDispatch(CommandEncoderBackend encoder, GpuTextureView view, CallbackInfo ci) {
		if (swapchainOutOfDate) return;
		FrameGen.beforeBlit(((VulkanGpuTexture) view.texture()).vkImage(), ((VulkanGpuTextureView) view).vkImageView());
	}

	@Inject(method = "blitFromTexture", at = @At("TAIL"))
	private void emrion$fgSignal(CommandEncoderBackend encoder, GpuTextureView view, CallbackInfo ci) {
		FrameGen.afterBlit((VulkanCommandEncoder) encoder);
	}

	@Inject(method = "present", at = @At("HEAD"), cancellable = true)
	private void emrion$fgPresent(CallbackInfo ci) {
		if (swapchainOutOfDate) return; // MC throws as usual
		// frame generation switched on or off: let MC recreate the swapchain with the right image count
		if (emrion$fgImages != (dev.emrion.shaders.mod.framegen.FrameGenConfig.effective() != dev.emrion.shaders.mod.framegen.FrameGenConfig.Mode.OFF)) swapchainSuboptimal = true;
		if (!FrameGen.present(swapchain, swapchainImages.toLongArray(), swapchainWidth, swapchainHeight, currentImageIndex,
				presentSemaphores[currentImageIndex])) return;
		currentImageIndex = -1;
		int r = FrameGen.takePresentResult();
		if (r == -1000001004) { // VK_ERROR_OUT_OF_DATE_KHR: what MC's present() does
			swapchainSuboptimal = true;
			swapchainOutOfDate = true;
			eatenException = new SurfaceException("Failed to present image, swapchain out of date");
		} else if (r == 1000001003) { // VK_SUBOPTIMAL_KHR
			swapchainSuboptimal = true;
		}
		ci.cancel();
	}

	@Inject(method = {"configure", "close"}, at = @At("HEAD"))
	private void emrion$fgDrain(CallbackInfo ci) {
		Presenter.drain();
		Presenter.waitIdle();
	}

	@WrapOperation(method = "acquireNextTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"))
	private int emrion$fgAcquire(VkDevice device, long swapchain, long timeout, long semaphore, long fence, IntBuffer index, Operation<Integer> original) {
		if (!Presenter.running()) return original.call(device, swapchain, timeout, semaphore, fence, index);
		return Presenter.pollAcquire(() -> original.call(device, swapchain, 0L, semaphore, fence, index));
	}

	@WrapOperation(method = "present", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int emrion$fgPresentLock(VkQueue queue, VkPresentInfoKHR info, Operation<Integer> original) {
		Presenter.LOCK.lock();
		try {
			return original.call(queue, info);
		} finally {
			Presenter.LOCK.unlock();
		}
	}
}
