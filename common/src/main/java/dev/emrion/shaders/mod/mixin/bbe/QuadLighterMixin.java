package dev.emrion.shaders.mod.mixin.bbe;

import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Why: Better Block Entities bakes vanilla's entity diffuse light (0.4 + 0.6 x two fixed lamps) into the chest, shulker, bell...
 * quads it meshes, as MC does for block entities without shaders. With a pack the pack lights block entities from their
 * normals, so that baked shade came on top: the chest walls of a storage were up to half as bright as without BBE.
 */
@Pseudo
@Mixin(targets = "betterblockentities.client.chunk.util.QuadLighter", remap = false)
public class QuadLighterMixin {
	@Inject(method = "computeDirectionalLight", at = @At("HEAD"), cancellable = true, require = 0)
	private static void emrion$noBakedShade(CallbackInfoReturnable<Float> cir) {
		if (EmrionVertexFormats.active()) cir.setReturnValue(1f);
	}
}
