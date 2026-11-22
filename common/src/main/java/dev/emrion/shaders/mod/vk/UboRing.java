package dev.emrion.shaders.mod.vk;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VK10;

/**
 * Persistently mapped UBO ring for {@code UNIFORM_BUFFER_DYNAMIC} bindings. One region per frame in flight (+1 being
 * recorded); allocation is a bump pointer, so there is no per-frame allocation. Call {@link #nextFrame()} at most once per frame.
 */
public final class UboRing {
	/** MC keeps 2 submits in flight; the region of the frame being recorded makes 3. */
	public static final int REGIONS = VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT + 1;

	public final RawBuffer buffer;
	private final long regionSize;
	private final long alignment;
	private int region;
	private long cursor;

	public UboRing(VulkanContext ctx, long bytesPerFrame) {
		this.alignment = ctx.limits().minUniformBufferOffsetAlignment();
		this.regionSize = align(bytesPerFrame, alignment);
		this.buffer = new RawBuffer(ctx, regionSize * REGIONS, VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true, "emrion ubo ring");
	}

	public void nextFrame() {
		region = (region + 1) % REGIONS;
		cursor = 0;
	}

	/** Returns the buffer offset (the dynamic offset to bind); write the data at {@link #address(long)}. */
	public long alloc(long size) {
		long offset = cursor;
		cursor = align(cursor + size, alignment);
		if (cursor > regionSize) throw new IllegalStateException("UBO ring overflow: " + cursor + " > " + regionSize + " bytes per frame");
		return region * regionSize + offset;
	}

	/** Descriptor range for a binding at {@code offset}: the rest of its region (whole-buffer ranges can pass maxUniformBufferRange). */
	public long range(long offset) {
		return regionSize - offset % regionSize;
	}

	public long address(long offset) {
		return buffer.mapped + offset;
	}

	private static long align(long v, long a) {
		return (v + a - 1) / a * a;
	}

	public void destroyLater() {
		buffer.destroyLater();
	}
}
