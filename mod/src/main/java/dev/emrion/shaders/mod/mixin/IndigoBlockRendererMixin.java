package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.vertex.BlockContext;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.SmoothLight;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: under Iris, Sodium meshes chunk blocks with its smooth lighting and, with {@code separateAo}, AO in the vertex alpha.
 * We do the same for Indigo's smooth-lit quads; flat quads keep Indigo's lighting.
 */
@Mixin(value = AltModelBlockRendererImpl.class, remap = false)
public class IndigoBlockRendererMixin {
	@Shadow private BlockAndTintGetter level;
	@Shadow private BlockPos pos;
	@Shadow private BlockState blockState;
	@Unique private final float[] emrion$xyz = new float[12], emrion$br = new float[4];
	@Unique private final int[] emrion$lm = new int[4];

	@Inject(method = "shadeQuad", at = @At("HEAD"), cancellable = true)
	private void emrion$sodiumLighting(MutableQuadViewImpl quad, boolean ao, boolean emissive, boolean vanillaShade, CallbackInfo ci) {
		if (!ao || !EmrionVertexFormats.active() || !BlockContext.get().inSection) return;
		for (int i = 0; i < 4; i++) {
			emrion$xyz[i * 3] = quad.x(i);
			emrion$xyz[i * 3 + 1] = quad.y(i);
			emrion$xyz[i * 3 + 2] = quad.z(i);
		}
		SmoothLight.get().light(level, blockState, pos, quad.lightFace(), quad.shadeDirectionOverride(), emrion$xyz, emrion$br, emrion$lm);
		boolean separate = EmrionVertexFormats.separateAo();
		for (int i = 0; i < 4; i++) {
			int c = quad.color(i);
			quad.color(i, separate ? ARGB.color(Math.round(emrion$br[i] * 255.0f), c) : ARGB.scaleRGB(c, emrion$br[i]));
			quad.lightmap(i, emissive ? LightCoordsUtil.FULL_BRIGHT : maxLight(quad.lightmap(i), emrion$lm[i]));
		}
		ci.cancel();
	}

	/** Per-channel maximum of two packed light coords (Sodium's ColorHelper.maxBrightness). */
	@Unique
	private static int maxLight(int a, int b) {
		return Math.max(a & 0xFFFF, b & 0xFFFF) | Math.max(a >>> 16, b >>> 16) << 16;
	}
}
