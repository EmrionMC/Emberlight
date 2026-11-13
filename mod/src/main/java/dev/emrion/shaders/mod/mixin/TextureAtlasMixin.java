package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.pbr.PbrAtlases;
import dev.emrion.shaders.mod.pbr.PbrTextures;
import dev.emrion.shaders.mod.pipeline.GeometryPipelines;
import dev.emrion.shaders.mod.vk.SparseAtlas;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: the PBR atlases mirror their atlas. {@code upload} RETURN is where a reload's stitched atlas layout becomes final
 * (and every cached per-texture PBR map is stale); {@code cycleAnimationFrames} TAIL is the atlas's animation tick (PBR
 * frames advance in the same tick);
 * {@code createTexture} TAIL fills a new PBR atlas with Iris's default before MC blits the sprites into it, and records every
 * atlas texture so atlasSize can tell atlases from plain textures. {@code upload} HEAD hands the sprite rectangles to
 * SparseAtlas, which backs only the tiles they touch.
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin extends AbstractTexture {
	@Inject(method = "upload", at = @At("HEAD"))
	private void emrion$sparse(SpriteLoader.Preparations prep, CallbackInfo ci) {
		if (!EmrionShadersClient.active()) return;
		int[][] rects = prep.regions().values().stream().map(s -> {
			int padding = Math.round(s.getU0() * prep.width()) - s.getX(); // u0 = (x + padding) / width
			return new int[] {s.getX(), s.getY(), s.contents().width() + 2 * padding, s.contents().height() + 2 * padding};
		}).toArray(int[][]::new);
		SparseAtlas.expect(prep.width(), prep.height(), prep.mipLevel() + 1, rects);
	}

	@Inject(method = "upload", at = @At("RETURN"))
	private void emrion$upload(SpriteLoader.Preparations preparations, CallbackInfo ci) {
		if (!EmrionShadersClient.active() || (Object) this instanceof PbrAtlases.PbrAtlas) return;
		PbrTextures.clear();
		PbrAtlases.onAtlasUpload((TextureAtlas) (Object) this, preparations);
	}

	@Inject(method = "cycleAnimationFrames", at = @At("TAIL"))
	private void emrion$tick(CallbackInfo ci) {
		if (EmrionShadersClient.active() && !((Object) this instanceof PbrAtlases.PbrAtlas)) PbrAtlases.onAtlasTick((TextureAtlas) (Object) this);
	}

	@Inject(method = "createTexture", at = @At("TAIL"))
	private void emrion$created(CallbackInfo ci) {
		SparseAtlas.clearExpected();
		GeometryPipelines.ATLAS_TEXTURES.add(getTexture()); // atlasSize is non-zero only for atlas textures (Iris)
		if ((Object) this instanceof PbrAtlases.PbrAtlas pbr) RenderSystem.getDevice().createCommandEncoder().clearColorTexture(getTexture(), pbr.clear);
	}
}
