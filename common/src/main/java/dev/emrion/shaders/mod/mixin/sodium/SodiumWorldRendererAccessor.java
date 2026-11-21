package dev.emrion.shaders.mod.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: our shadow pass draws Sodium's region buffers itself (compat.sodium.SodiumShadow). */
@Mixin(SodiumWorldRenderer.class)
public interface SodiumWorldRendererAccessor {
	@Accessor("renderSectionManager") RenderSectionManager emrion$sections();
	@Accessor("uniformBufferManager") UniformBufferManager emrion$uniforms();
	@Accessor("lastFogParameters") FogParameters emrion$fog();
	@Accessor("useTranslucencySorting") boolean emrion$sorting();
}
