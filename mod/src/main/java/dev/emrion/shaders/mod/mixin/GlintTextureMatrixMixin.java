package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.pipeline.GlintPass;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Why: MC 26 draws base and glint in one pass with the glint matrix as TextureMat, which packs apply to the base texture.
 * With a pack the base draw gets the identity and the glint matrix goes to GlintPass.
 */
@Mixin(RenderType.class)
abstract class GlintTextureMatrixMixin {
	@Shadow
	public abstract com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline();

	@WrapOperation(method = "writeDynamicTransforms", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private GpuBufferSlice emrion$baseTextureMatrix(DynamicGpuData data, Matrix4f modelView, Matrix4f texture, Operation<GpuBufferSlice> original) {
		if (!EmrionShadersClient.active() || !GeometryHooks.packRendering() || !GlintPass.fused(pipeline())) return original.call(data, modelView, texture);
		// GLINT_SPECIAL items carry their own foil UVs (ENTITY_GLINT_SPECIAL): the ENTITY-format glint pass cannot draw them
		if (pipeline().getVertexFormatBinding(0) == com.mojang.blaze3d.vertex.DefaultVertexFormat.ENTITY) GlintPass.written(original.call(data, modelView, texture));
		return original.call(data, modelView, new Matrix4f());
	}

	@Inject(method = "prepare", at = @At("RETURN"))
	private void emrion$glintPrepared(CallbackInfoReturnable<PreparedRenderType> cir) {
		GlintPass.prepared(cir.getReturnValue());
	}
}
