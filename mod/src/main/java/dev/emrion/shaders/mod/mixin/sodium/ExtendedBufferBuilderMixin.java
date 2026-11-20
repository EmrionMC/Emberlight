package dev.emrion.shaders.mod.mixin.sodium;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.emrion.shaders.mod.vertex.ExtendedBufferBuilder;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Why: Sodium's intrinsics (BufferBuilderMixin.push) would copy vertices past our per-vertex ids and per-quad normal, tangent
 * and mid UV; entity quads take them through ExtendedBufferBuilder.push instead, text opts out (VertexConsumer calls).
 * Sodium 0.9.3-beta.5 prefixes its VertexBufferWriter methods.
 */
@Mixin(ExtendedBufferBuilder.class)
public class ExtendedBufferBuilderMixin {
	public boolean sodium$canUseIntrinsics() {
		return ((ExtendedBufferBuilder) (Object) this).acceptsPush();
	}

	public void sodium$push(MemoryStack stack, long src, int count, VertexFormat format) {
		((ExtendedBufferBuilder) (Object) this).push(src, count, format);
	}
}
