package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import dev.emrion.shaders.mod.rt.RtCapability;
import dev.emrion.shaders.mod.runtime.Formats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Why: BLAS builds read MC's terrain vertex heaps in place. Heaps live as long as the SectionRenderDispatcher, so the usage
 * bits are set at creation whenever the device is RT-capable. Vertex heaps only (usage 32); index heaps untouched.
 */
@Mixin(UberGpuBuffer.UberGpuBufferHeap.class)
public class UberGpuBufferRtMixin {
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createBuffer(Ljava/util/function/Supplier;IJ)Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"), index = 1)
	private int emrion$rtInput(int usage) {
		return RtCapability.enabled && (usage & 32) != 0 ? usage | Formats.USAGE_RT_INPUT : usage;
	}
}
