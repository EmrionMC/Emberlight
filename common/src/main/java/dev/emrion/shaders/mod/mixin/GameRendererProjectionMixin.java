package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.autotest.AutoTest;
import dev.emrion.shaders.mod.uniforms.CapturedProjection;
import dev.emrion.shaders.mod.uniforms.UniformDebug;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: Iris's hand projection reuses the bob matrix, which exists only as a local in {@code renderLevel}, so we copy it out.
 * With a pack, bob, hurt tilt and nausea go into the model view instead of the projection (Iris MixinModelViewBobbing):
 * packs reconstruct view positions with only the diagonal of gbufferProjectionInverse, so a bobbed projection made their
 * shadows and reflections sway with the bob.
 */
@Mixin(GameRenderer.class)
public class GameRendererProjectionMixin {
	@Unique
	private boolean emrion$bobToView;
	@Unique
	private final Matrix4f emrion$viewBob = new Matrix4f();

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void emrion$head(CallbackInfo ci) {
		emrion$bobToView = EmrionShadersClient.active() && EmrionVertexFormats.active(); // a pack renders (Iris: isPackInUseQuick)
		var level = ((GameRenderer) (Object) this).gameRenderState().levelRenderState;
		var e = level.cameraRenderState.entityRenderState;
		if (!Float.isNaN(AutoTest.bobWalk)) { // test hooks: a fixed bob phase, hurt tilt, nausea skew
			e.backwardsInterpolatedWalkDistance = AutoTest.bobWalk;
			e.bob = AutoTest.bobAmount;
		}
		if (!Float.isNaN(AutoTest.hurtTime)) {
			e.hurtTime = AutoTest.hurtTime;
			e.hurtDuration = 10;
			e.hurtDir = AutoTest.hurtDir;
		}
		if (!Float.isNaN(AutoTest.nausea)) {
			level.playerRenderState.nauseaEffectIntensity = AutoTest.nausea;
			level.playerRenderState.spinningEffectAngle = AutoTest.nauseaAngle;
		}
	}

	/** {@code projectionMatrix.mul(bobStack.last().pose())}: the only Matrix4f.mul in renderLevel. */
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;", ordinal = 0, remap = false))
	private Matrix4f emrion$bob(Matrix4f projection, Matrix4fc bob, Operation<Matrix4f> original) {
		CapturedProjection.BOB.set(bob);
		if (!emrion$bobToView) {
			Matrix4f r = original.call(projection, bob);
			if (EmrionShadersClient.active()) dev.emrion.shaders.mod.upscale.VanillaUpscale.jitter(projection); // no pack: upscaling jitter
			return r;
		}
		emrion$viewBob.set(bob);
		return projection;
	}

	/** Nausea/portal skew: onto the view bob like Iris. */
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;rotate(FLorg/joml/Vector3fc;)Lorg/joml/Matrix4f;", remap = false))
	private Matrix4f emrion$spinRotate(Matrix4f projection, float angle, Vector3fc axis, Operation<Matrix4f> original) {
		if (!emrion$bobToView) return original.call(projection, angle, axis);
		emrion$viewBob.rotate(angle, axis);
		return projection;
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;scale(FFF)Lorg/joml/Matrix4f;", remap = false))
	private Matrix4f emrion$spinScale(Matrix4f projection, float x, float y, float z, Operation<Matrix4f> original) {
		if (!emrion$bobToView) return original.call(projection, x, y, z);
		emrion$viewBob.scale(x, y, z);
		return projection;
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
		if (emrion$bobToView) {
			CameraRenderState cs = ((GameRenderer) (Object) this).gameRenderState().levelRenderState.cameraRenderState;
			cs.viewRotationMatrix.mulLocal(emrion$viewBob);
		}
		if (UniformDebug.ENABLED) UniformDebug.onLevelRender();
	}
}
