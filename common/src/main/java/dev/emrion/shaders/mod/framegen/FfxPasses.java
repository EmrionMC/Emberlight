package dev.emrion.shaders.mod.framegen;

import static dev.emrion.shaders.mod.vk.Barriers.*;
import static org.lwjgl.vulkan.VK10.*;

import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.vk.FfxPass;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.RawCommands;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.UboRing;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * The FidelityFX Vulkan backend reduced to what the FG ports need: AMD's compute passes with bindings found by name through
 * SPIR-V reflection, and one full compute barrier after every dispatch.
 */
final class FfxPasses {
	private static final String SPV = "/assets/emberlight/spv/fsr3fg/";
	static final int USAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

	final VulkanContext ctx;
	private final String label;
	final UboRing ubo;
	private final long pointSampler, linearSampler;
	private final List<RawImage> images = new ArrayList<>();
	private final List<RawBuffer> buffers = new ArrayList<>();
	private final List<Long> views = new ArrayList<>();
	/** Current bindings: image views by binding name; buffers and constant buffers (UBO ring offsets) by block name. */
	final Map<String, Long> bind = new HashMap<>(), bufferBind = new HashMap<>(), cbBind = new HashMap<>();

	FfxPasses(VulkanContext ctx, String label, long uboBytesPerFrame) {
		this.ctx = ctx;
		this.label = label;
		ubo = new UboRing(ctx, uboBytesPerFrame);
		pointSampler = FfxPass.sampler(ctx, VK_FILTER_NEAREST, label);
		linearSampler = FfxPass.sampler(ctx, VK_FILTER_LINEAR, label);
	}

	FfxPass pass(String name) {
		return FfxPass.get(ctx, SPV, name, "FFX");
	}

	RawImage image(int format, int w, int h, int mips, String name) {
		RawImage i = new RawImage(ctx, format, Math.max(1, w), Math.max(1, h), 1, mips, USAGE, label + " " + name);
		images.add(i);
		return i;
	}

	RawBuffer buffer(long size, String name) {
		RawBuffer b = new RawBuffer(ctx, size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false, label + " " + name);
		buffers.add(b);
		return b;
	}

	long mipView(RawImage img, int level) {
		long v = FfxPass.mipView(ctx, img, level, label);
		views.add(v);
		return v;
	}

	void upload(RawImage img, ByteBuffer data) {
		FfxPass.upload(ctx, img, data, label);
	}

	/** Stages a constant block (std140 = the SDK's C layout) and returns a little-endian view to fill; binds it as {@code name}. */
	ByteBuffer constants(String name, int size) {
		long off = ubo.alloc(size);
		cbBind.put(name, off);
		return MemoryUtil.memByteBuffer(ubo.address(off), size).order(ByteOrder.nativeOrder());
	}

	void run(VkCommandBuffer cb, FfxPass p, int x, int y, int z) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var writes = VkWriteDescriptorSet.calloc(p.bindings.size(), stack);
			for (FfxPass.Binding b : p.bindings) {
				var w = writes.get().sType$Default().dstBinding(b.binding()).descriptorType(b.type()).descriptorCount(1);
				String block = b.name().endsWith("_t") ? b.name().substring(0, b.name().length() - 2) : b.name();
				switch (b.type()) {
					case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER -> {
						long off = require(cbBind, block, p);
						w.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(ubo.buffer.handle).offset(off).range(ubo.range(off)));
					}
					case VK_DESCRIPTOR_TYPE_STORAGE_BUFFER -> w.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(require(bufferBind, block, p))
							.offset(0).range(VK_WHOLE_SIZE));
					case VK_DESCRIPTOR_TYPE_SAMPLER -> w.pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
							.sampler(b.name().contains("Linear") ? linearSampler : pointSampler));
					case VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE -> w.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(bind.getOrDefault(b.name(), dummy(b.name())))
							.imageLayout(VK_IMAGE_LAYOUT_GENERAL));
					default -> w.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(require(bind, b.name(), p)).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
				}
			}
			writes.flip();
			vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.pipeline);
			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.layout, 0, writes);
		}
		vkCmdDispatch(cb, Math.max(1, x), Math.max(1, y), Math.max(1, z));
		barrier(cb);
		Profiler.mark(cb, label + " " + p.name);
	}

	private final Map<String, Long> dummies = new HashMap<>();

	/** 1×1 zero texture for inputs the SDK leaves unset in the interpolation-only path. */
	private long dummy(String name) {
		boolean uint = name.contains("confidence") || name.contains("global_motion") || name.contains("scd") || name.contains("counter");
		return dummies.computeIfAbsent(uint ? "uint" : "float", k -> {
			RawImage i = image(uint ? VK_FORMAT_R32_UINT : VK_FORMAT_R16G16B16A16_SFLOAT, 1, 1, 1, "dummy " + k);
			var cb = RawCommands.begin(ctx);
			if (uint) clearUint(cb, i, 0);
			else clearFloat(cb, i, 0f);
			RawCommands.submit(ctx, cb);
			return i.view;
		});
	}

	private static long require(Map<String, Long> m, String name, FfxPass p) {
		Long v = m.get(name);
		if (v == null) throw new IllegalStateException("FFX " + p.name + ": no resource bound for " + name);
		return v;
	}

	static void barrier(VkCommandBuffer cb) {
		memory(cb, COMPUTE | TRANSFER, STORAGE_WRITE | TRANSFER_WRITE, COMPUTE | TRANSFER, SAMPLED_READ | STORAGE_READ | STORAGE_WRITE | TRANSFER_READ | TRANSFER_WRITE);
	}

	static void clearFloat(VkCommandBuffer cb, RawImage img, float v) {
		FfxPass.clearFloat(cb, img, v, v, v, v);
	}

	static void clearUint(VkCommandBuffer cb, RawImage img, int v) {
		FfxPass.clearUint(cb, img, v);
	}

	static int[] spdSetup(int w, int h, int mips) {
		return FfxPass.spdSetup(w, h, mips);
	}

	void destroyLater() {
		images.forEach(RawImage::destroyLater);
		buffers.forEach(RawBuffer::destroyLater);
		ubo.destroyLater();
		List<Long> vs = List.copyOf(views);
		ctx.destroyLater(() -> { // the passes are shared (FfxPass)
			for (long v : vs) vkDestroyImageView(ctx.vk, v, null);
			vkDestroySampler(ctx.vk, pointSampler, null);
			vkDestroySampler(ctx.vk, linearSampler, null);
		});
	}
}
