package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.emrion.shaders.mod.debug.VramReport;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: -Demrion.debug.vram names every MC buffer's VMA allocation by its label (chunk sections, uniforms, …). */
@Mixin(VulkanGpuBuffer.Direct.class)
public abstract class VramBufferLabelMixin {
	@Shadow @Final private long vmaAllocation;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void emrion$name(VulkanDevice device, @Nullable Supplier<String> label, int usage, long size, boolean hostVisible, CallbackInfo ci) {
		if (VramReport.ENABLED) VramReport.name(device.vma(), vmaAllocation, "buf " + (label == null ? "?" : label.get()));
	}
}
