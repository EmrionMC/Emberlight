package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.upscale.ObjectMotion;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Why: the one place where an entity and its render state meet; the state keeps the entity id for object motion vectors. */
@Mixin(EntityRenderer.class)
public class EntityRendererMotionMixin {
	@Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("RETURN"))
	private void emrion$entityId(Entity entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir) {
		((ObjectMotion.Owned) cir.getReturnValue()).emrion$setEntityId(entity.getId());
	}
}
