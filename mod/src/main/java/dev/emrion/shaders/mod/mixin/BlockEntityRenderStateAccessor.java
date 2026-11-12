package dev.emrion.shaders.mod.mixin;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: blockEntityId is the id of the block entity's block state, which the render state keeps private. */
@Mixin(BlockEntityRenderState.class)
public interface BlockEntityRenderStateAccessor {
	@Accessor("blockState")
	BlockState emrion$blockState();
}
