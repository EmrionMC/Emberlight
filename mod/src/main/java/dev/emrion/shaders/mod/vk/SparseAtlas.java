package dev.emrion.shaders.mod.vk;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.debug.VramReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBindSparseInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkSparseImageMemoryBind;
import org.lwjgl.vulkan.VkSparseImageMemoryBindInfo;
import org.lwjgl.vulkan.VkSparseImageMemoryRequirements;
import org.lwjgl.vulkan.VkSparseImageOpaqueMemoryBindInfo;
import org.lwjgl.vulkan.VkSparseMemoryBind;

/**
 * Large texture atlases (MC's and the PBR copies) as sparse, aliased images: a 64 KiB tile gets its own memory only if a
 * sprite rectangle (with padding, plus one texel) touches it at that mip level; every other tile is bound to one shared
 * page. Lossless by construction: MC writes atlases only inside sprite rectangles (sprite blits) or uniformly (the PBR
 * default-value clear, which also fills the shared page), so every texel holds the same value as in a plain image; the
 * texels outside all sprites were never written in a plain base atlas either.
 *
 * <p>TextureAtlas.upload HEAD calls {@link #expect}; the next VulkanGpuTexture of that size on the render thread is created here
 * instead of by VMA ({@link #create}); its destroy frees the memory ({@link #destroyed}). Any failure falls back to a plain image.
 */
public final class SparseAtlas {
	/**
	 * Opt-in (-Demrion.perf.sparseAtlases=true): enabling sparseBinding on the device alone costs about 10 % GPU time on
	 * NVIDIA (615.78, RTX 4070 Ti), even with no sparse image; at 256x the VRAM saved does not win that back (BENCHMARK.md M5b).
	 */
	public static final boolean ENABLED = Boolean.getBoolean("emrion.perf.sparseAtlases");
	/** Smaller atlases stay plain (vanilla atlases are at most 4096² at 16x): nothing worth saving. */
	private static final long MIN_TEXELS = 4096L * 4096;
	/** The device features sparse atlases need; VulkanFeatureSetsMixin requests them (optional) unless left out here. */
	public static final String FEATURES = ENABLED ? "sparseBinding,sparseResidencyImage2D,sparseResidencyAliased" : "";

	private record Rect(int x, int y, int w, int h) {}

	private record Pending(int width, int height, int mipLevels, List<Rect> rects) {}

	private static @Nullable Pending pending;
	private static @Nullable Boolean supported;
	/** vkImage → its memory. */
	private static final Map<Long, Long> MEMORY = new java.util.concurrent.ConcurrentHashMap<>();

	private SparseAtlas() {}

	/** The atlas about to be created: {@code rects} are the sprite rectangles (x, y, w, h with padding) at level 0. */
	public static void expect(int width, int height, int mipLevels, int[][] rects) {
		pending = null;
		if (!ENABLED || (long) width * height < MIN_TEXELS) return;
		List<Rect> list = new ArrayList<>(rects.length);
		for (int[] r : rects) list.add(new Rect(r[0], r[1], r[2], r[3]));
		pending = new Pending(width, height, mipLevels, list);
	}

	public static void clearExpected() {
		pending = null;
	}

	/**
	 * Creates the image of an expected atlas as a sparse image and binds its memory; returns 0 (and leaves
	 * {@code info} unchanged) if this image is not the expected atlas or sparse residency is unavailable.
	 */
	public static long create(VulkanDevice device, VkImageCreateInfo info) {
		Pending p = pending;
		if (p == null || info.imageType() != VK_IMAGE_TYPE_2D || info.extent().width() != p.width || info.extent().height() != p.height
				|| info.mipLevels() != p.mipLevels || info.arrayLayers() != 1 || info.format() != VK_FORMAT_R8G8B8A8_UNORM) return 0;
		pending = null;
		if (!supported(device)) return 0;
		int oldFlags = info.flags();
		long image = 0, memory = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			info.flags(oldFlags | VK_IMAGE_CREATE_SPARSE_BINDING_BIT | VK_IMAGE_CREATE_SPARSE_RESIDENCY_BIT | VK_IMAGE_CREATE_SPARSE_ALIASED_BIT);
			var pImage = stack.mallocLong(1);
			if (vkCreateImage(device.vkDevice(), info, null, pImage) != VK_SUCCESS) return fail(info, oldFlags, "vkCreateImage");
			image = pImage.get(0);
			info.flags(oldFlags);

			var req = VkMemoryRequirements.malloc(stack);
			vkGetImageMemoryRequirements(device.vkDevice(), image, req);
			var count = stack.mallocInt(1);
			vkGetImageSparseMemoryRequirements(device.vkDevice(), image, count, null);
			var sparse = VkSparseImageMemoryRequirements.malloc(count.get(0), stack);
			vkGetImageSparseMemoryRequirements(device.vkDevice(), image, count, sparse);
			VkSparseImageMemoryRequirements color = null;
			for (var s : sparse) {
				if ((s.formatProperties().aspectMask() & VK_IMAGE_ASPECT_METADATA_BIT) != 0) {
					vkDestroyImage(device.vkDevice(), image, null);
					return fail(info, oldFlags, "metadata aspect required");
				}
				if ((s.formatProperties().aspectMask() & VK_IMAGE_ASPECT_COLOR_BIT) != 0) color = s;
			}
			if (color == null) {
				vkDestroyImage(device.vkDevice(), image, null);
				return fail(info, oldFlags, "no color sparse requirements");
			}
			int gw = color.formatProperties().imageGranularity().width(), gh = color.formatProperties().imageGranularity().height();
			int tailLod = Math.min(color.imageMipTailFirstLod(), p.mipLevels);
			long page = req.alignment();
			if (page != (long) gw * gh * 4) {
				vkDestroyImage(device.vkDevice(), image, null);
				return fail(info, oldFlags, "tile size " + gw + "x" + gh + " vs alignment " + page);
			}

			// tiles to back per level (below the mip tail)
			List<int[]> own = new ArrayList<>(); // {level, x, y, w, h}
			long totalTiles = 0;
			for (int level = 0; level < tailLod; level++) {
				int lw = Math.max(1, p.width >> level), lh = Math.max(1, p.height >> level);
				int tx = (lw + gw - 1) / gw, ty = (lh + gh - 1) / gh;
				totalTiles += (long) tx * ty;
				boolean[] mark = new boolean[tx * ty];
				for (Rect r : p.rects) {
					int x0 = Math.max(0, (r.x >> level) - 1), y0 = Math.max(0, (r.y >> level) - 1);
					int x1 = Math.min(lw, (r.x >> level) + (r.w >> level) + 1), y1 = Math.min(lh, (r.y >> level) + (r.h >> level) + 1);
					for (int y = y0 / gh; y <= (y1 - 1) / gh; y++) for (int x = x0 / gw; x <= (x1 - 1) / gw; x++) mark[y * tx + x] = true;
				}
				for (int y = 0; y < ty; y++) for (int x = 0; x < tx; x++) {
					if (!mark[y * tx + x]) continue;
					own.add(new int[] {level, x * gw, y * gh, Math.min(gw, lw - x * gw), Math.min(gh, lh - y * gh)});
				}
				// the remaining tiles of this level alias page 0
				for (int y = 0; y < ty; y++) for (int x = 0; x < tx; x++) {
					if (mark[y * tx + x]) continue;
					own.add(new int[] {level, x * gw, y * gh, Math.min(gw, lw - x * gw), Math.min(gh, lh - y * gh), 0});
				}
			}
			long ownPages = own.stream().filter(t -> t.length == 5).count();
			long tailSize = tailLod < p.mipLevels ? color.imageMipTailSize() : 0;
			long tailPages = (tailSize + page - 1) / page;
			long bytes = (1 + ownPages + tailPages) * page;

			req.size(bytes);
			var allocInfo = VmaAllocationCreateInfo.calloc(stack).preferredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
			PointerBuffer pAlloc = stack.mallocPointer(1);
			var ai = VmaAllocationInfo.malloc(stack);
			if (Vma.vmaAllocateMemory(device.vma(), req, allocInfo, pAlloc, ai) != VK_SUCCESS) {
				vkDestroyImage(device.vkDevice(), image, null);
				return fail(info, oldFlags, "vmaAllocateMemory " + (bytes >> 20) + " MiB");
			}
			memory = pAlloc.get(0);
			VramReport.name(device.vma(), memory, "sparse atlas " + p.width + "x" + p.height);
			long mem = ai.deviceMemory(), base = ai.offset();

			var binds = VkSparseImageMemoryBind.calloc(own.size());
			try {
				long next = 1;
				for (int i = 0; i < own.size(); i++) {
					int[] t = own.get(i);
					var b = binds.get(i);
					b.subresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(t[0]).arrayLayer(0);
					b.offset().set(t[1], t[2], 0);
					b.extent().set(t[3], t[4], 1);
					b.memory(mem).memoryOffset(base + (t.length == 5 ? next++ : 0) * page);
				}
				var imageBind = VkSparseImageMemoryBindInfo.calloc(1, stack).image(image).pBinds(binds);
				var bindInfo = VkBindSparseInfo.calloc(stack).sType$Default().pImageBinds(imageBind);
				if (tailSize > 0) {
					var tail = VkSparseMemoryBind.calloc(1, stack).resourceOffset(color.imageMipTailOffset()).size(tailSize).memory(mem)
							.memoryOffset(base + (1 + ownPages) * page);
					bindInfo.pImageOpaqueBinds(VkSparseImageOpaqueMemoryBindInfo.calloc(1, stack).image(image).pBinds(tail));
				}
				var pFence = stack.mallocLong(1);
				vkCreateFence(device.vkDevice(), VkFenceCreateInfo.calloc(stack).sType$Default(), null, pFence);
				// Sparse binds are not ordered with later submits: wait here, before MC records anything with the image.
				int r = vkQueueBindSparse(device.graphicsQueue().vkQueue(), bindInfo, pFence.get(0));
				if (r == VK_SUCCESS) r = vkWaitForFences(device.vkDevice(), pFence, true, Long.MAX_VALUE);
				vkDestroyFence(device.vkDevice(), pFence.get(0), null);
				if (r != VK_SUCCESS) {
					vkDestroyImage(device.vkDevice(), image, null);
					Vma.vmaFreeMemory(device.vma(), memory);
					return fail(info, oldFlags, "vkQueueBindSparse " + r);
				}
			} finally {
				binds.free();
			}
			MEMORY.put(image, memory);
			long plain = 0;
			for (int level = 0; level < p.mipLevels; level++) plain += 4L * Math.max(1, p.width >> level) * Math.max(1, p.height >> level);
			EmrionShadersClient.LOG.info("Sparse atlas {}x{} ({} mips, {} sprites): {} of {} tiles backed, {} MiB instead of {} MiB", p.width, p.height,
					p.mipLevels, p.rects.size(), ownPages, totalTiles, bytes >> 20, plain >> 20);
			return image;
		}
	}

	private static long fail(VkImageCreateInfo info, int oldFlags, String why) {
		info.flags(oldFlags);
		EmrionShadersClient.LOG.warn("Sparse atlas not used ({}): plain image", why);
		return 0;
	}

	private static boolean supported(VulkanDevice device) {
		if (supported == null) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				// MC enabled these as optional features exactly when supported (VulkanFeatureSetsMixin)
				var f = VkPhysicalDeviceFeatures.calloc(stack);
				vkGetPhysicalDeviceFeatures(device.vkDevice().getPhysicalDevice(), f);
				var props = org.lwjgl.vulkan.VkPhysicalDeviceProperties.malloc(stack);
				vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), props);
				supported = FEATURES.contains("sparseBinding") && FEATURES.contains("sparseResidencyImage2D") && FEATURES.contains("sparseResidencyAliased")
						&& f.sparseBinding() && f.sparseResidencyImage2D() && f.sparseResidencyAliased()
						&& props.sparseProperties().residencyStandard2DBlockShape();
			}
		}
		return supported;
	}

	/** VulkanGpuTexture.destroy TAIL (the image is gone and no submit uses it any more): frees a sparse atlas's memory. */
	public static void destroyed(VulkanDevice device, long image) {
		Long memory = MEMORY.remove(image);
		if (memory != null) Vma.vmaFreeMemory(device.vma(), memory);
	}
}
