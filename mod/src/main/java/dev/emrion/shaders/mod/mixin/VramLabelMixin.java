package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import dev.emrion.shaders.mod.debug.VramReport;
import dev.emrion.shaders.mod.vk.SparseAtlas;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: large texture atlases become sparse images (SparseAtlas: only tiles with sprites get memory), which needs the image
 * creation and destruction of MC's texture; -Demrion.debug.vram names every texture's VMA allocation by its label.
 */
@Mixin(VulkanGpuTexture.class)
public abstract class VramLabelMixin {
	@Shadow @Final private long vmaAllocation;
	@Shadow @Final private VulkanDevice device;

	@Shadow
	public abstract long vkImage();

	@WrapOperation(method = "<init>", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/util/vma/Vma;vmaCreateImage(JLorg/lwjgl/vulkan/VkImageCreateInfo;Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;Ljava/nio/LongBuffer;Lorg/lwjgl/PointerBuffer;Lorg/lwjgl/util/vma/VmaAllocationInfo;)I"))
	private int emrion$sparse(long allocator, VkImageCreateInfo info, VmaAllocationCreateInfo alloc, LongBuffer pImage, PointerBuffer pAllocation,
			VmaAllocationInfo allocInfo, Operation<Integer> original, @Local(argsOnly = true) VulkanDevice device) {
		long image = SparseAtlas.create(device, info);
		if (image == 0) return original.call(allocator, info, alloc, pImage, pAllocation, allocInfo);
		pImage.put(0, image);
		pAllocation.put(0, 0L); // vmaDestroyImage with a null allocation only destroys the image; SparseAtlas frees the memory
		return 0;
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void emrion$name(CallbackInfo ci) {
		if (vmaAllocation == 0) return;
		String label = ((VulkanGpuTexture) (Object) this).getLabel();
		VramReport.name(device.vma(), vmaAllocation, "tex " + label);
		dev.emrion.shaders.mod.vk.VramResidency.check(device.vma(), vmaAllocation, label);
	}

	@Inject(method = "destroy", at = @At("TAIL"))
	private void emrion$destroyed(CallbackInfo ci) {
		SparseAtlas.destroyed(device, vkImage());
	}
}
