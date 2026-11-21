package dev.emrion.shaders.mod.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.emrion.shaders.mod.rt.LightColors;
import dev.emrion.shaders.mod.rt.RtLights;
import dev.emrion.shaders.mod.vertex.BlockContext;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Why: the block being meshed is known only here, on the worker thread (as Iris's MixinChunkMeshBuildTask); it goes into
 * BlockContext for the vertex mixins, and RtLights collects emitters here.
 */
@Mixin(ChunkBuilderMeshingTask.class)
public class ChunkBuilderMeshingTaskMixin {
	@WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;renderModel(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V"))
	private void emrion$block(BlockRenderer renderer, BlockStateModel model, BlockState state, BlockPos pos, BlockPos local, Operation<Void> original) {
		if (EmrionVertexFormats.active()) BlockContext.get().block(state, local.getX(), local.getY(), local.getZ());
		original.call(renderer, model, state, pos, local);
	}

	@WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/FluidRenderer;render(Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;)V"))
	private void emrion$fluid(FluidRenderer renderer, LevelSlice slice, BlockState state, FluidState fluid, BlockPos pos, BlockPos local,
			TranslucentGeometryCollector collector, ChunkBuildBuffers buffers, Operation<Void> original) {
		if (EmrionVertexFormats.active()) BlockContext.get().fluid(state, fluid, local.getX(), local.getY(), local.getZ());
		original.call(renderer, slice, state, fluid, pos, local, collector, buffers);
	}

	private static final String EXECUTE = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;";

	@Inject(method = EXECUTE, at = @At("HEAD"))
	private void emrion$lightsBegin(ChunkBuildContext context, CancellationToken token, CallbackInfoReturnable<ChunkBuildOutput> cir) {
		RtLights.begin();
	}

	@WrapOperation(method = EXECUTE, at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState emrion$lightsBlock(LevelSlice slice, int x, int y, int z, Operation<BlockState> original) {
		BlockState state = original.call(slice, x, y, z);
		if (LightColors.candidate(state)) RtLights.block(state, new BlockPos(x, y, z), slice);
		return state;
	}

	@Inject(method = EXECUTE, at = @At("RETURN"))
	private void emrion$lightsEnd(ChunkBuildContext context, CancellationToken token, CallbackInfoReturnable<ChunkBuildOutput> cir) {
		if (cir.getReturnValue() != null) RtLights.end(cir.getReturnValue().section.getPosition()); // null: cancelled, nothing changes
	}
}
