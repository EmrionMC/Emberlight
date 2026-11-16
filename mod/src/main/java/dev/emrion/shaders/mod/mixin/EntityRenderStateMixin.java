package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.upscale.ObjectMotion;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Why: object motion vectors match an entity's vertices across frames by its id, which the render state does not carry. */
@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements ObjectMotion.Owned {
	@Unique private int emrion$entityId = -1;

	@Override
	public int emrion$entityId() {
		return emrion$entityId;
	}

	@Override
	public void emrion$setEntityId(int id) {
		emrion$entityId = id;
	}
}
