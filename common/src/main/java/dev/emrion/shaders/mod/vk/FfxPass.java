package dev.emrion.shaders.mod.vk;

import static org.lwjgl.vulkan.VK10.*;

import dev.emrion.shaders.converter.spirv.SpirvReflection;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/**
 * One AMD FidelityFX compute pass (FSR upscaler, FSR frame generation): push-descriptor layout and pipeline, bindings found
 * by name through SPIR-V reflection, as the SDK does. Size-independent, so built once per SPIR-V file and shared by every
 * upscaler and frame generator until {@link #shutdown}; a resize or mode change only recreates the images. Plus the
 * helpers both FFX hosts use.
 */
public final class FfxPass {
	public record Binding(int binding, int type, String name) {}

	public final String name;
	public final long setLayout, layout, pipeline;
	public final List<Binding> bindings;

	private static final Map<String, FfxPass> CACHE = new HashMap<>();

	private FfxPass(String name, long setLayout, long layout, long pipeline, List<Binding> bindings) {
		this.name = name;
		this.setLayout = setLayout;
		this.layout = layout;
		this.pipeline = pipeline;
		this.bindings = bindings;
	}

	/** The pass {@code dir + name + ".spv"}, built on first use; {@code label} prefixes errors ("FSR", "FFX"). */
	public static synchronized FfxPass get(VulkanContext ctx, String dir, String name, String label) {
		FfxPass p = CACHE.get(dir + name);
		if (p == null) CACHE.put(dir + name, p = create(ctx, dir, name, label));
		return p;
	}

	private static FfxPass create(VulkanContext ctx, String dir, String name, String label) {
		byte[] spv = ShaderModules.resource(dir + name + ".spv");
		var builder = new Descriptors.LayoutBuilder();
		List<Binding> bindings = new ArrayList<>();
		for (var d : SpirvReflection.reflect(spv).descriptors()) {
			int type = switch (d.type()) {
				case "separate_image" -> VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
				case "storage_image" -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
				case "uniform_buffer" -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
				case "storage_buffer" -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
				case "separate_sampler" -> VK_DESCRIPTOR_TYPE_SAMPLER;
				default -> throw new IllegalStateException(label + " " + name + ": unexpected descriptor " + d);
			};
			bindings.add(new Binding(d.binding(), type, d.name()));
			builder.add(d.binding(), type, VK_SHADER_STAGE_COMPUTE_BIT);
		}
		long setLayout = builder.build(ctx, true), layout = 0, module = 0;
		try {
			layout = Descriptors.pipelineLayout(ctx, 0, setLayout);
			module = ShaderModules.create(ctx, spv);
			return new FfxPass(name, setLayout, layout, ComputePipelines.create(ctx, module, layout, ctx.pipelineCache().handle), List.copyOf(bindings));
		} catch (RuntimeException | Error failed) {
			if (layout != 0) vkDestroyPipelineLayout(ctx.vk, layout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, setLayout, null);
			throw failed;
		} finally {
			if (module != 0) ShaderModules.destroy(ctx, module);
		}
	}

	/** Client stop: queues every shared pass for destruction before MC destroys the device. */
	public static synchronized void shutdown(VulkanContext ctx) {
		List<FfxPass> ps = List.copyOf(CACHE.values());
		CACHE.clear();
		ctx.destroyLater(() -> {
			for (FfxPass p : ps) {
				vkDestroyPipeline(ctx.vk, p.pipeline, null);
				vkDestroyPipelineLayout(ctx.vk, p.layout, null);
				vkDestroyDescriptorSetLayout(ctx.vk, p.setLayout, null);
			}
		});
	}

	// ---- shared host helpers -------------------------------------------------------------------------------------------

	/** Clamp-to-edge sampler with mips, nearest or linear. */
	public static long sampler(VulkanContext ctx, int filter, String label) {
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

	/** A view of mip {@code level} only. */
	public static long mipView(VulkanContext ctx, RawImage img, int level, String label) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkImageViewCreateInfo.calloc(stack).sType$Default().image(img.image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(img.format);
			info.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(level).levelCount(1).baseArrayLayer(0).layerCount(1);
			var p = stack.mallocLong(1);
			if (vkCreateImageView(ctx.vk, info, null, p) != VK_SUCCESS) throw new IllegalStateException("vkCreateImageView " + label + " mip");
			return p.get(0);
		}
	}

	/** Copies {@code data} into mip 0 of {@code img} (GENERAL layout) in its own submit. */
	public static void upload(VulkanContext ctx, RawImage img, ByteBuffer data, String label) {
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

	public static void clearFloat(VkCommandBuffer cb, RawImage img, float r, float g, float b, float a) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var value = VkClearColorValue.calloc(stack).float32(0, r).float32(1, g).float32(2, b).float32(3, a);
			vkCmdClearColorImage(cb, img.image, VK_IMAGE_LAYOUT_GENERAL, value, range(stack));
		}
	}

	/** Also what the SDK's float clear does to an integer image: the bits of the float land in the texel. */
	public static void clearUint(VkCommandBuffer cb, RawImage img, int v) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var value = VkClearColorValue.calloc(stack).uint32(0, v).uint32(1, v).uint32(2, v).uint32(3, v);
			vkCmdClearColorImage(cb, img.image, VK_IMAGE_LAYOUT_GENERAL, value, range(stack));
		}
	}

	private static VkImageSubresourceRange range(MemoryStack stack) {
		return VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(VK_REMAINING_MIP_LEVELS).layerCount(1);
	}

	/** ffxSpdSetup for a (0, 0, w, h) rect: {dispatchX, dispatchY, numWorkGroups, mips}; {@code mips < 0} = from the size. */
	public static int[] spdSetup(int w, int h, int mips) {
		int x = (w - 1) / 64 + 1, y = (h - 1) / 64 + 1;
		int m = mips >= 0 ? mips : (int) Math.min(Math.floor(Math.log(Math.max(w, h)) / Math.log(2)), 12);
		return new int[] {x, y, x * y, m};
	}
}
