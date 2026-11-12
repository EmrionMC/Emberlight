package dev.emrion.shaders.mod.vk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import dev.emrion.shaders.mod.mixin.VulkanQueryPoolAccessor;
import java.util.OptionalLong;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * GPU timestamps through MC's {@link GpuQueryPool}. Marks go into MC's command stream between our {@code execute()}d
 * buffers, so they bracket raw work too. Results are read {@link #LAG} frames later, never waiting on the GPU.
 */
public final class GpuTimer {
	/** Frames kept in the ring: 2 in flight plus the one being recorded. */
	private static final int LAG = UboRing.REGIONS;

	private final GpuQueryPool pool;
	private final int marksPerFrame;
	private final double nsPerTick;
	private final long[] lastNanos;
	private int frame;
	private int marks;

	public GpuTimer(int marksPerFrame) {
		this.marksPerFrame = marksPerFrame;
		this.pool = RenderSystem.getDevice().createTimestampQueryPool(marksPerFrame * LAG);
		this.nsPerTick = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
		this.lastNanos = new long[marksPerFrame];
	}

	/** Call once per frame before the first mark: reads back the oldest slot, then reuses it. */
	public void beginFrame() {
		frame = (frame + 1) % LAG;
		OptionalLong[] v = pool.getValues(frame * marksPerFrame, marksPerFrame);
		for (int i = 1; i < marksPerFrame; i++) {
			lastNanos[i] = v[i].isPresent() && v[0].isPresent() ? (long) ((v[i].getAsLong() - v[0].getAsLong()) * nsPerTick) : -1;
		}
		marks = 0;
	}

	/** Writes the next timestamp (outside a render pass). Returns its index within the frame. */
	public int mark() {
		if (marks >= marksPerFrame) throw new IllegalStateException("too many GPU timer marks");
		RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, frame * marksPerFrame + marks);
		return marks++;
	}

	/** Writes the next timestamp into one of our own command buffers (between raw passes, outside a render pass). */
	public int mark(VkCommandBuffer cb) {
		if (marks >= marksPerFrame) throw new IllegalStateException("too many GPU timer marks");
		int query = frame * marksPerFrame + marks;
		long vkPool = ((VulkanQueryPoolAccessor) pool).emrion$vkQueryPool();
		VK12.vkResetQueryPool(cb.getDevice(), vkPool, query, 1);
		KHRSynchronization2.vkCmdWriteTimestamp2KHR(cb, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, vkPool, query);
		return marks++;
	}

	/** Nanoseconds from mark 0 to mark {@code i}, from {@link #LAG} frames ago; -1 if not available. */
	public long nanosSinceFirst(int i) {
		return lastNanos[i];
	}

	public void close() {
		pool.close();
	}
}
