package dev.emrion.shaders.mod.compat.sodium;

import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import org.jspecify.annotations.Nullable;

/** A Sodium multi-draw batch filled in distance bands (DefaultChunkRendererMixin): band b's commands are [bands[b], bands[b + 1]). */
public interface BandedBatch {
	@Nullable int[] emrion$bands();

	void emrion$setBands(@Nullable int[] bands);

	/** Draws commands [from, to). */
	void emrion$draw(DrawContext context, int from, int to);
}
