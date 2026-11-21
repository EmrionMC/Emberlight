package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.rt.RtCapability;
import dev.emrion.shaders.mod.runtime.Formats;
import net.caffeinemc.mods.sodium.client.gpu.arena.ArenaAggregator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Why: RT reads Sodium's arena buffers by device address; arenas are reused between geometry and index, so all get the bit. */
@Mixin(ArenaAggregator.class)
public class ArenaAggregatorRtMixin {
	@ModifyArg(method = "getBufferOfSizeAtLeast", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createBuffer(Ljava/util/function/Supplier;IJ)Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"), index = 1)
	private int emrion$rtInput(int usage) {
		return RtCapability.enabled ? usage | Formats.USAGE_RT_INPUT : usage;
	}
}
