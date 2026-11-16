package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.emrion.shaders.mod.vertex.BlockContext;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.ExtendedBufferBuilder;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.emrion.shaders.mod.rt.RtLights;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.SectionPos;

/**
 * Why: while a pack is active, section meshes are built in EMRION_BLOCK, and each vertex needs the block
 * being meshed (id, fluid flag, emission, position) — known only here, on the worker thread that compiles the section.
 */
@Mixin(SectionCompiler.class)
public class SectionCompilerMixin {
	@WrapOperation(method = "getOrBeginLayer", at = @At(value = "NEW", target = "com/mojang/blaze3d/vertex/BufferBuilder"))
	private BufferBuilder emrion$extendedBuilder(ByteBufferBuilder buffer, PrimitiveTopology topology, VertexFormat format,
			Operation<BufferBuilder> original) {
		if (!EmrionVertexFormats.active()) return original.call(buffer, topology, format);
		return new ExtendedBufferBuilder(buffer, topology, EmrionVertexFormats.TERRAIN, BlockContext.get());
	}

	@WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
	private void emrion$fluid(FluidRenderer renderer, BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output, BlockState state,
			FluidState fluid, Operation<Void> original) {
		if (!EmrionVertexFormats.active()) {
			original.call(renderer, level, pos, output, state, fluid);
			return;
		}
		BlockContext c = BlockContext.get();
		c.fluid(state, fluid, pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
		c.fluidLevel = level;
		c.fluidPos = pos;
		c.fluidState = fluid;
		try {
			original.call(renderer, level, pos, output, state, fluid);
		} finally {
			c.fluidLevel = null;
			c.fluidPos = null;
			c.fluidState = null;
		}
	}

	@WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
	private void emrion$block(ModelBlockRenderer renderer, BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level, BlockPos pos,
			BlockState state, BlockStateModel model, long seed, Operation<Void> original) {
		if (!EmrionVertexFormats.active()) {
			original.call(renderer, output, x, y, z, level, pos, state, model, seed);
			return;
		}
		BlockContext c = BlockContext.get();
		c.block(state, pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
		c.inSection = true;
		try {
			original.call(renderer, output, x, y, z, level, pos, state, model, seed);
		} finally {
			c.inSection = false;
		}
	}

	// RT block light (RtLights): the emissive blocks of the section, collected only while an RtScene exists
	@Inject(method = "compile", at = @At("HEAD"))
	private void emrion$lightsBegin(SectionPos sectionPos, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack builders,
			CallbackInfoReturnable<SectionCompiler.Results> cir) {
		RtLights.begin();
	}

	@WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState emrion$lightsBlock(RenderSectionRegion region, BlockPos pos, Operation<BlockState> original) {
		BlockState state = original.call(region, pos);
		RtLights.block(state, pos, region);
		return state;
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void emrion$lightsEnd(SectionPos sectionPos, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack builders,
			CallbackInfoReturnable<SectionCompiler.Results> cir) {
		RtLights.end(sectionPos);
	}
}
