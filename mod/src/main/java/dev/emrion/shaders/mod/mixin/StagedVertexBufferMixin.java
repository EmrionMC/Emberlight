package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.EntityIds;
import dev.emrion.shaders.mod.vertex.ExtendedBufferBuilder;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Why: every feature draw (entities, block entities, items) gets its vertex format and BufferBuilder here. While the world or
 * hand frame is prepared with a pack active, ENTITY draws become EMRION_ENTITY and world text GLYPH, written by ExtendedBufferBuilder.
 */
@Mixin(StagedVertexBuffer.class)
public class StagedVertexBufferMixin {
	@ModifyVariable(method = "appendDraw(Lcom/mojang/renderpearl/api/vertex/VertexFormat;Lcom/mojang/renderpearl/api/pipeline/PrimitiveTopology;Lcom/mojang/blaze3d/vertex/VertexSorting;)Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;",
			at = @At("HEAD"), argsOnly = true)
	private VertexFormat emrion$entityFormat(VertexFormat format) {
		if (!EntityIds.extending) return format;
		return format == DefaultVertexFormat.ENTITY ? EmrionVertexFormats.ENTITY
				: format == DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR ? EmrionVertexFormats.GLYPH : format;
	}

	@WrapOperation(method = "getVertexBuilder", at = @At(value = "NEW", target = "com/mojang/blaze3d/vertex/BufferBuilder"))
	private BufferBuilder emrion$extendedBuilder(ByteBufferBuilder buffer, PrimitiveTopology topology, VertexFormat format,
			Operation<BufferBuilder> original) {
		return format == EmrionVertexFormats.ENTITY || format == EmrionVertexFormats.GLYPH ? new ExtendedBufferBuilder(buffer, topology, format, null) : original.call(buffer, topology, format);
	}
}
