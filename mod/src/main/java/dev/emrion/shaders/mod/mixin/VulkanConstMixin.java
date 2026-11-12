package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import dev.emrion.shaders.mod.runtime.Formats;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Why: MC's texture usage has no STORAGE bit, but packs write colortex/shadowcolor as images (colorimgN). Our textures pass
 * {@link Formats#USAGE_STORAGE} (unknown to the frontend, which does not validate bits) and get VK_IMAGE_USAGE_STORAGE_BIT here.
 */
@Mixin(VulkanConst.class)
public class VulkanConstMixin {
	@Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true)
	private static void emrion$storage(int usage, GpuFormat format, CallbackInfoReturnable<Integer> cir) {
		if ((usage & Formats.USAGE_STORAGE) != 0) cir.setReturnValue(cir.getReturnValueI() | VK10.VK_IMAGE_USAGE_STORAGE_BIT);
	}
}
