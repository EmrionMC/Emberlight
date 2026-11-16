package dev.emrion.shaders.mod.framegen;

import static dev.emrion.shaders.mod.vk.Barriers.*;
import static org.lwjgl.vulkan.VK10.*;

import dev.emrion.shaders.converter.spirv.SpirvReflection;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.vk.ComputePipelines;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.RawCommands;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.ShaderModules;
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
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * The FidelityFX Vulkan backend reduced to what the FG ports need: AMD's compute passes with bindings found by name through
 * SPIR-V reflection, and one full compute barrier after every dispatch.
 */
final class FfxPasses {
	private static final String SPV = "/assets/emberlight/spv/fsr3fg/";
	static final int USAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

	private record Binding(int binding, int type, String name) {}

	final class Pass {
		final String name;
		final long setLayout, layout, pipeline;
		final List<Binding> bindings = new ArrayList<>();

		Pass(String name) {
			this.name = name;
			byte[] spv = ShaderModules.resource(SPV + name + ".spv");
			var builder = new Descriptors.LayoutBuilder();
			for (var d : SpirvReflection.reflect(spv).descriptors()) {
				int type = switch (d.type()) {
					case "separate_image" -> VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
					case "storage_image" -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
					case "uniform_buffer" -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
					case "storage_buffer" -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
					case "separate_sampler" -> VK_DESCRIPTOR_TYPE_SAMPLER;
					default -> throw new IllegalStateException("FFX " + name + ": unexpected descriptor " + d);
				};
				bindings.add(new Binding(d.binding(), type, d.name()));
				builder.add(d.binding(), type, VK_SHADER_STAGE_COMPUTE_BIT);
			}
			setLayout = builder.build(ctx, true);
			layout = Descriptors.pipelineLayout(ctx, 0, setLayout);
			long module = ShaderModules.create(ctx, spv);
			pipeline = ComputePipelines.create(ctx, module, layout, ctx.pipelineCache().handle);
			ShaderModules.destroy(ctx, module);
		}
	}

	final VulkanContext ctx;
	private final String label;
	final UboRing ubo;
	private final long pointSampler, linearSampler;
	private final List<Pass> passes = new ArrayList<>();
	private final List<RawImage> images = new ArrayList<>();
	private final List<RawBuffer> buffers = new ArrayList<>();
	private final List<Long> views = new ArrayList<>();
	/** Current bindings: image views by binding name; buffers and constant buffers (UBO ring offsets) by block name. */
	final Map<String, Long> bind = new HashMap<>(), bufferBind = new HashMap<>(), cbBind = new HashMap<>();

	FfxPasses(VulkanContext ctx, String label, long uboBytesPerFrame) {
		this.ctx = ctx;
		this.label = label;
		ubo = new UboRing(ctx, uboBytesPerFrame);
		pointSampler = sampler(VK_FILTER_NEAREST);
		linearSampler = sampler(VK_FILTER_LINEAR);
	}

	Pass pass(String name) {
		Pass p = new Pass(name);
		passes.add(p);
		return p;
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
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkImageViewCreateInfo.calloc(stack).sType$Default().image(img.image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(img.format);
			info.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(level).levelCount(1).baseArrayLayer(0).layerCount(1);
			var p = stack.mallocLong(1);
			if (vkCreateImageView(ctx.vk, info, null, p) != VK_SUCCESS) throw new IllegalStateException("vkCreateImageView " + label + " mip");
			views.add(p.get(0));
			return p.get(0);
		}
	}

	private long sampler(int filter) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(filter).minFilter(filter)
					.mipmapMode(filter == VK_FILTER_LINEAR ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
					.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
					.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxAnisotropy(1).minLod(0).maxLod(VK_LOD_CLAMP_NONE);
			var p = stack.mallocLong(1);
			if (vkCreateSampler(ctx.vk, info, null, p) != VK_SUCCESS) throw new IllegalStateException("vkCreateSampler " + label);
			return p.get(0);
		}
	}

	void upload(RawImage img, ByteBuffer data) {
		var staging = new RawBuffer(ctx, data.remaining(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true, label + " upload");
		MemoryUtil.memByteBuffer(staging.mapped, data.remaining()).put(data);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var region = VkBufferImageCopy.calloc(1, stack);
			region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.imageExtent().set(img.width, img.height, 1);
			var cb = RawCommands.begin(ctx);
			vkCmdCopyBufferToImage(cb, staging.handle, img.image, VK_IMAGE_LAYOUT_GENERAL, region);
			RawCommands.submit(ctx, cb);
		}
		staging.destroyLater();
	}

	/** Stages a constant block (std140 = the SDK's C layout) and returns a little-endian view to fill; binds it as {@code name}. */
	ByteBuffer constants(String name, int size) {
		long off = ubo.alloc(size);
		cbBind.put(name, off);
		return MemoryUtil.memByteBuffer(ubo.address(off), size).order(ByteOrder.nativeOrder());
	}

	void run(VkCommandBuffer cb, Pass p, int x, int y, int z) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var writes = VkWriteDescriptorSet.calloc(p.bindings.size(), stack);
			for (Binding b : p.bindings) {
				var w = writes.get().sType$Default().dstBinding(b.binding()).descriptorType(b.type()).descriptorCount(1);
				String block = b.name().endsWith("_t") ? b.name().substring(0, b.name().length() - 2) : b.name();
				switch (b.type()) {
					case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER -> w.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(ubo.buffer.handle)
							.offset(require(cbBind, block, p)).range(VK_WHOLE_SIZE));
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

	private static long require(Map<String, Long> m, String name, Pass p) {
		Long v = m.get(name);
		if (v == null) throw new IllegalStateException("FFX " + p.name + ": no resource bound for " + name);
		return v;
	}

	static void barrier(VkCommandBuffer cb) {
		memory(cb, COMPUTE | TRANSFER, STORAGE_WRITE | TRANSFER_WRITE, COMPUTE | TRANSFER, SAMPLED_READ | STORAGE_READ | STORAGE_WRITE | TRANSFER_READ | TRANSFER_WRITE);
	}

	static void clearFloat(VkCommandBuffer cb, RawImage img, float v) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var value = VkClearColorValue.calloc(stack).float32(0, v).float32(1, v).float32(2, v).float32(3, v);
			vkCmdClearColorImage(cb, img.image, VK_IMAGE_LAYOUT_GENERAL, value, range(stack));
		}
	}

	/** Also what the SDK's float clear does to an integer image: the bits of the float land in the texel. */
	static void clearUint(VkCommandBuffer cb, RawImage img, int v) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var value = VkClearColorValue.calloc(stack).uint32(0, v).uint32(1, v).uint32(2, v).uint32(3, v);
			vkCmdClearColorImage(cb, img.image, VK_IMAGE_LAYOUT_GENERAL, value, range(stack));
		}
	}

	private static VkImageSubresourceRange range(MemoryStack stack) {
		return VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(VK_REMAINING_MIP_LEVELS).layerCount(1);
	}

	/** ffxSpdSetup for a (0, 0, w, h) rect: {dispatchX, dispatchY, numWorkGroups, mips}; {@code mips < 0} = from the size. */
	static int[] spdSetup(int w, int h, int mips) {
		int x = (w - 1) / 64 + 1, y = (h - 1) / 64 + 1;
		int m = mips >= 0 ? mips : (int) Math.min(Math.floor(Math.log(Math.max(w, h)) / Math.log(2)), 12);
		return new int[] {x, y, x * y, m};
	}

	void destroyLater() {
		images.forEach(RawImage::destroyLater);
		buffers.forEach(RawBuffer::destroyLater);
		ubo.destroyLater();
		List<Long> vs = List.copyOf(views);
		List<Pass> ps = List.copyOf(passes);
		ctx.destroyLater(() -> {
			for (long v : vs) vkDestroyImageView(ctx.vk, v, null);
			vkDestroySampler(ctx.vk, pointSampler, null);
			vkDestroySampler(ctx.vk, linearSampler, null);
			for (Pass p : ps) {
				vkDestroyPipeline(ctx.vk, p.pipeline, null);
				vkDestroyPipelineLayout(ctx.vk, p.layout, null);
				vkDestroyDescriptorSetLayout(ctx.vk, p.setLayout, null);
			}
		});
	}
}
