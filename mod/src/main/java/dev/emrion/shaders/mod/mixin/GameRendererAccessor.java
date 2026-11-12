package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Why: Iris draws the first-person hand inside the level (before translucents and after them); MC's method is private. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Invoker("renderItemInHand")
	void emrion$renderItemInHand(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthTextureView);
}
