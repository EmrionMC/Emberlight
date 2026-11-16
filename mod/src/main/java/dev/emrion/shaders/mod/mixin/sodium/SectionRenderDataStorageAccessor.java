package dev.emrion.shaders.mod.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gpu.arena.BufferSegment;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: RT on the Sodium path (compat.sodium.SodiumRt) sees a section's mesh appear and go through its vertex allocation. */
@Mixin(SectionRenderDataStorage.class)
public interface SectionRenderDataStorageAccessor {
	@Accessor("vertexAllocations") BufferSegment[] emrion$vertexAllocations();
}
