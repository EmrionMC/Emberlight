package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: ExtendedBufferBuilder writes the extra elements into the vertex MC just began (vertex package). */
@Mixin(BufferBuilder.class)
public interface BufferBuilderAccessor {
	@Accessor("vertexPointer")
	long emrion$vertexPointer();
}
