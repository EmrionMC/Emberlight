package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: PBR sprites need Iris's normal/labPBR mipmaps instead of MC's colour mipmaps (SpriteContents.increaseMipLevel). */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
	@Accessor("byMipLevel")
	void emrion$setByMipLevel(NativeImage[] levels);
}
