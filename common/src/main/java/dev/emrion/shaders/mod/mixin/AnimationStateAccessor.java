package dev.emrion.shaders.mod.mixin;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: after fast-forwarding a PBR animation, its current frame must be drawn even if the last tick did not change it; the fast-forward skips whole cycles. */
@Mixin(SpriteContents.AnimationState.class)
public interface AnimationStateAccessor {
	@Accessor("isDirty")
	void emrion$setDirty(boolean dirty);

	/** With subFrame: back at (0, 0) means one full animation cycle passed. */
	@Accessor("frame")
	int emrion$frame();

	@Accessor("subFrame")
	int emrion$subFrame();
}
