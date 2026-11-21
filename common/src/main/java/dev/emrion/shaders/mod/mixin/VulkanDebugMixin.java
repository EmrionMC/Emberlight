package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Why: MC's debug callback returns VK_TRUE on validation errors, so the layer skips the offending call (a skipped submit
 * hangs the frame, a skipped barrier causes follow-up errors). The spec says to return VK_FALSE; only runs with a layer.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanDebug$Enabled")
public class VulkanDebugMixin {
	@ModifyReturnValue(method = "onDebugMessage", at = @At("RETURN"))
	private int emrion$neverAbort(int original) {
		return 0;
	}
}
