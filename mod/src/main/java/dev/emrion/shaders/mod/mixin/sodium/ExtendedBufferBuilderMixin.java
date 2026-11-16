package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.vertex.ExtendedBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Why: Sodium's intrinsics bypass our per-vertex ids and normals; opting out makes it use the VertexConsumer calls.
 * shortcut: one Sodium WARN per class and no fast path, add an ENTITY/GLYPH serializer (as Iris) if entity-heavy scenes need it.
 */
@Mixin(ExtendedBufferBuilder.class)
public class ExtendedBufferBuilderMixin {
	public boolean sodium$canUseIntrinsics() { // Sodium 0.9.3-beta.5 prefixes its VertexBufferWriter methods
		return false;
	}
}
