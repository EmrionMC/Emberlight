package dev.emrion.shaders.mod.mixin;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: after fast-forwarding a PBR animation, its current frame must be drawn even if the last tick did not change it. */
@Mixin(SpriteContents.AnimationState.class)
public interface AnimationStateAccessor {
	@Accessor("isDirty")
	void emrion$setDirty(boolean dirty);
}
