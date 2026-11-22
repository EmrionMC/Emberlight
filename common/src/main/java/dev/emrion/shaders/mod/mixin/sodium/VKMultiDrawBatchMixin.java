package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.compat.sodium.BandedBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.VKMultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkMultiDrawIndexedInfoEXT;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Why: lets DefaultChunkRendererMixin draw a sub-range of a region's commands (one distance band at a time). */
@Mixin(VKMultiDrawBatch.class)
public class VKMultiDrawBatchMixin implements BandedBatch {
	@Shadow @Final private long pCommands;
	@Unique private int @Nullable [] emrion$bands;
	/** A view of the command array, kept: MC reads only its address (position) and takes the count from the call. */
	@Unique private java.nio.@Nullable IntBuffer emrion$commands;

	@Override
	public int @Nullable [] emrion$bands() {
		return emrion$bands;
	}

	@Override
	public void emrion$setBands(int @Nullable [] bands) {
		emrion$bands = bands;
	}

	@Override
	public void emrion$draw(DrawContext context, int from, int to) {
		int ints = VkMultiDrawIndexedInfoEXT.SIZEOF / 4;
		var b = emrion$commands;
		if (b == null || b.capacity() < to * ints) emrion$commands = b = MemoryUtil.memIntBuffer(pCommands, to * ints);
		context.getPass().multiDrawIndexed(b.position(from * ints), 1, 0, to - from);
	}
}
