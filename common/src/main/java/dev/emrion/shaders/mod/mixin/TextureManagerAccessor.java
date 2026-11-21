package dev.emrion.shaders.mod.mixin;

import java.util.Map;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: a draw binds Sampler0 as a bare GpuTexture; PbrTextures finds the MC texture (and so its resource id) behind it here. */
@Mixin(TextureManager.class)
public interface TextureManagerAccessor {
	@Accessor("byPath")
	Map<Identifier, AbstractTexture> emrion$byPath();
}
