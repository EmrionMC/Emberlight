package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.emrion.shaders.mod.vertex.BlockContext;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.SmoothLight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: under Iris, Sodium meshes fluids: water gets smooth lighting (AO and blended light per vertex), every fluid face a fixed
 * brightness (sides 0.8 / 0.6, top and bottom 1) instead of MC's cardinal shading, and with {@code separateAo=true} that
 * brightness goes into the vertex alpha with the plain tint in rgb (DefaultFluidRenderer + XHFPTerrainVertex). Chunk meshes only.
 */
@Mixin(FluidRenderer.class)
public class FluidRendererMixin {
	@Shadow
	private void vertex(VertexConsumer builder, float x, float y, float z, int color, float u, float v, int lightCoords) {
		throw new AssertionError();
	}

	/** MC scales the tint by its cardinal shading here; Sodium applies its own brightness in addFace instead. */
	@WrapOperation(method = "tesselate", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;scaleRGB(IF)I"))
	private int emrion$plainTint(int color, float scale, Operation<Integer> original) {
		return BlockContext.get().fluidPos != null ? color : original.call(color, scale);
	}

	@Inject(method = "addFace", at = @At("HEAD"), cancellable = true)
	private void emrion$sodiumFluidFace(VertexConsumer builder, float x0, float y0, float z0, float u0, float v0, float x1, float y1, float z1,
			float u1, float v1, float x2, float y2, float z2, float u2, float v2, float x3, float y3, float z3, float u3, float v3, int color,
			int lightCoords, boolean addBackFace, CallbackInfo ci) {
		BlockContext c = BlockContext.get();
		if (c.fluidPos == null) return;
		BlockPos pos = c.fluidPos;
		float ox = pos.getX() & 15, oy = pos.getY() & 15, oz = pos.getZ() & 15;
		float[] xyz = {x0 - ox, y0 - oy, z0 - oz, x1 - ox, y1 - oy, z1 - oz, x2 - ox, y2 - oy, z2 - oz, x3 - ox, y3 - oy, z3 - oz};
		Direction dir = faceDirection(xyz);
		float[] br = new float[4];
		int[] lm = {lightCoords, lightCoords, lightCoords, lightCoords};
		boolean smooth = c.fluidState.is(FluidTags.WATER) && Minecraft.getInstance().options.ambientOcclusion().get();
		if (smooth) {
			SmoothLight.get().light(c.fluidLevel, c.fluidLevel.getBlockState(pos), pos, dir, Direction.UP, xyz, br, lm,
					dir.getAxis().isVertical() ? SmoothLight.NON_PARALLEL : SmoothLight.ALIGNED);
		} else {
			java.util.Arrays.fill(br, c.fluidLevel.cardinalLighting().byFace(Direction.UP));
		}
		float side = dir.getAxis() == Direction.Axis.Z ? 0.8f : dir.getAxis() == Direction.Axis.X ? 0.6f : 1.0f;
		boolean separate = EmrionVertexFormats.separateAo();
		int[] col = new int[4];
		for (int i = 0; i < 4; i++) {
			float b = br[i] * side;
			col[i] = separate ? ARGB.color(Math.round(b * 255.0f), color) : ARGB.scaleRGB(color, b);
		}
		vertex(builder, x0, y0, z0, col[0], u0, v0, lm[0]);
		vertex(builder, x1, y1, z1, col[1], u1, v1, lm[1]);
		vertex(builder, x2, y2, z2, col[2], u2, v2, lm[2]);
		vertex(builder, x3, y3, z3, col[3], u3, v3, lm[3]);
		if (addBackFace) {
			vertex(builder, x0, y0, z0, col[0], u0, v0, lm[0]);
			vertex(builder, x3, y3, z3, col[3], u3, v3, lm[3]);
			vertex(builder, x2, y2, z2, col[2], u2, v2, lm[2]);
			vertex(builder, x1, y1, z1, col[1], u1, v1, lm[1]);
		}
		ci.cancel();
	}

	/** The face a fluid quad belongs to, from its winding (MC's faces are counter-clockwise seen from outside). */
	private static Direction faceDirection(float[] p) {
		float ax = p[3] - p[0], ay = p[4] - p[1], az = p[5] - p[2], bx = p[6] - p[0], by = p[7] - p[1], bz = p[8] - p[2];
		return Direction.getApproximateNearest(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx);
	}
}
