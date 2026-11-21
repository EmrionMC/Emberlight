package dev.emrion.shaders.mod.mixin;

import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Why: a PBR atlas built after the block atlas fast-forwards its animation states and redraws them (PbrAtlases.upload). */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {
	@Accessor("animatedTexturesStates")
	List<SpriteContents.AnimationState> emrion$animationStates();

	@Invoker("uploadAnimationFrames")
	void emrion$uploadAnimationFrames();
}
