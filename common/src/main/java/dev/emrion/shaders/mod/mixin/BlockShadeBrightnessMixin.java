package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.pack.PackManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Why: the pack's ambientOcclusionLevel scales MC's baked ambient occlusion as in Iris (MixinBlockStateBehavior): vanilla and
 * Sodium both read a block's shade brightness here. MakeUp sets it to 0 with its own AO on; without this its terrain was
 * up to 20 % darker.
 */
@Mixin(value = BlockBehaviour.BlockStateBase.class, priority = 990)
public class BlockShadeBrightnessMixin {
	@Inject(method = "getShadeBrightness", at = @At("RETURN"), cancellable = true)
	private void emrion$aoLevel(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		float l = PackManager.aoLevel;
		if (l != 1f) cir.setReturnValue(1f - l * (1f - cir.getReturnValueF()));
	}
}
