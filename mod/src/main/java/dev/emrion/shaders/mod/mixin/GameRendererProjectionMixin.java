package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.uniforms.CapturedProjection;
import dev.emrion.shaders.mod.uniforms.UniformDebug;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: gbufferProjection (and the WORLD phase gl_ProjectionMatrix) is the level projection <i>after</i> MC multiplies in
 * view bobbing, hurt tilt and the nausea/portal skew, and Iris's hand projection reuses the same bob matrix. Both only
 * exist as locals in {@code renderLevel} (bobHurt/bobView are private), so we copy them out as they are used.
 * The debug hook runs the uniform snapshot at the point where Iris begins level rendering.
 */
@Mixin(GameRenderer.class)
public class GameRendererProjectionMixin {
	/** {@code projectionMatrix.mul(bobStack.last().pose())}: the only Matrix4f.mul in renderLevel. */
	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;", ordinal = 0, remap = false))
	private Matrix4fc emrion$captureBob(Matrix4fc bob) {
		CapturedProjection.BOB.set(bob);
		return bob;
	}

	/** {@code levelProjectionMatrixBuffer.getBuffer(projectionMatrix)}: the final level projection (reverse-Z). */
	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f emrion$captureProjection(Matrix4f projection) {
		CapturedProjection.LEVEL.set(projection);
		CapturedProjection.valid = true;
		return projection;
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"))
	private void emrion$beforeLevel(CallbackInfo ci) {
		if (UniformDebug.ENABLED) UniformDebug.onLevelRender();
	}
}
