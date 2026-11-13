package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: {@code getTexture()} throws for textures that are registered but not loaded yet; PbrTextures scans all of them. */
@Mixin(AbstractTexture.class)
public interface AbstractTextureAccessor {
	@Accessor("texture")
	@Nullable GpuTexture emrion$texture();
}
