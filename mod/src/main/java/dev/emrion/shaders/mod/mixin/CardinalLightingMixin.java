package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Why: Iris's MixinClientLevel: with {@code oldLighting=false} every face is shaded like UP (no baked directional shading). */
@Mixin(CardinalLighting.class)
public class CardinalLightingMixin {
	@ModifyVariable(method = "byFace", at = @At("HEAD"), argsOnly = true)
	private Direction emrion$noDirectionalShading(Direction face) {
		return EmrionVertexFormats.directionalShadingOff() ? Direction.UP : face;
	}
}
