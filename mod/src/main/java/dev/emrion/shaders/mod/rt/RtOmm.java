package dev.emrion.shaders.mod.rt;

import static org.lwjgl.vulkan.EXTOpacityMicromap.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.vk.Barriers;
import dev.emrion.shaders.mod.vk.ComputePipelines;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.ShaderModules;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.texture.SpriteLoader;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryTrianglesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureTrianglesOpacityMicromapEXT;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkMicromapBuildInfoEXT;
import org.lwjgl.vulkan.VkMicromapBuildSizesInfoEXT;
import org.lwjgl.vulkan.VkMicromapCreateInfoEXT;
import org.lwjgl.vulkan.VkMicromapUsageEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Opacity micromaps (VK_EXT_opacity_micromap) for cutout terrain: most alpha-tested candidate hits of the ray loops become
 * plain opaque hits or vanish in the traversal hardware. One micromap per block atlas layout: three uniform triangles
 * (unknown, opaque, transparent) and, per non-opaque static sprite, 24 triangles (the sprite mapped onto a triangle with each
 * order of three of its corners), 4-state at one subdivision level for all (BLAS usage counts must be exact, and only the
 * GPU knows which triangle uses which). rt_omm_data.comp fills them from the atlas, rt_omm_index.comp picks one per
 * triangle of a cutout section layer just before its BLAS build (RtScene.record). Opaque/transparent use the cutoff 0.1 of
 * every RT ray loop for cutout terrain; animated sprites and {@link #anyHitIds} stay all-unknown (alpha-tested as before).
 * <p>Render thread. A micromap lives while a BLAS references it ({@link Map#refs}); a new atlas layout gets a new one.
 */
public final class RtOmm {
	/** Optional device set, enabled with RT; {@link #supported} records the outcome at device creation. */
	public static final FeatureSet FEATURE_SET = new FeatureSet("Emberlight opacity micromaps", Set.of(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME),
			Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceOpacityMicromapFeaturesEXT.class), "micromap")));
	public static volatile boolean supported;

	private static final int MAX_LEVEL = 6;
	private static final long MAX_DATA = 64L << 20;
	private static final int ANIMATED = 0xFFFFFFFF, NO_SLOTS = 0xFFFFFFFE;
	private static final long MICROMAP_BUILD = VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT;
	private static final long AS_BUILD = KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;

	// ---- the block atlas layout, captured at upload (PbrAtlases.onAtlasUpload, render thread) -------------------------------

	private record Sprite(int x, int y, int w, int h, boolean animated, boolean opaque) {}

	private record Atlas(int width, int height, List<Sprite> sprites, int generation) {}

	private static @Nullable Atlas atlas;
	private static int generation;

	public static void onAtlasUpload(SpriteLoader.Preparations prep) {
		List<Sprite> list = new ArrayList<>();
		for (var s : prep.regions().values())
			list.add(new Sprite(Math.round(s.getU0() * prep.width()), Math.round(s.getV0() * prep.height()), s.contents().width(), s.contents().height(),
					s.contents().isAnimated(), s.contents().transparency().isOpaque()));
		atlas = new Atlas(prep.width(), prep.height(), List.copyOf(list), ++generation);
	}

	// ---- one micromap over one atlas layout ---------------------------------------------------------------------------------

	static final class Map {
		final int generation, level, count;
		long micromap;
		RawBuffer storage, tables;
		long spritesAddr, gridAddr, listAddr;
		int gridW, gridH, cellShift;
		long bytes;
		int refs;
		boolean current = true;

		Map(int generation, int level, int count) {
			this.generation = generation;
			this.level = level;
			this.count = count;
		}
	}

	private final VulkanContext ctx = VulkanContext.get();
	private final RtScene scene;
	private final long setLayout, layout, indexPipeline, dataPipeline;
	private final int maxLevel;
	private @Nullable Map current;
	private int failedGeneration = -1;
	private List<Integer> anyHitIds = List.of();
	private @Nullable RawBuffer anyHit;
	// stats
	long indexBytes, entries, buildNs;

	RtOmm(RtScene scene) {
		this.scene = scene;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var props = VkPhysicalDeviceOpacityMicromapPropertiesEXT.calloc(stack).sType$Default();
			vkGetPhysicalDeviceProperties2(ctx.vk.getPhysicalDevice(), VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(props));
			maxLevel = Math.min(MAX_LEVEL, props.maxOpacity4StateSubdivisionLevel());
		}
		int cs = VK_SHADER_STAGE_COMPUTE_BIT;
		setLayout = new Descriptors.LayoutBuilder().add(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, cs).build(ctx, true);
		layout = Descriptors.pipelineLayout(ctx, 68, setLayout);
		indexPipeline = pipeline("rt_omm_index.comp");
		dataPipeline = pipeline("rt_omm_data.comp");
		EmrionShadersClient.LOG.info("RT: opacity micromaps on (VK_EXT_opacity_micromap, 4-state up to level {})", maxLevel);
	}

	private static void vkGetPhysicalDeviceProperties2(org.lwjgl.vulkan.VkPhysicalDevice d, VkPhysicalDeviceProperties2 p) {
		org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2(d, p);
	}

	private long pipeline(String name) {
		long m = ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/" + name + ".spv"));
		long p = ComputePipelines.create(ctx, m, layout, ctx.pipelineCache().handle);
		ShaderModules.destroy(ctx, m);
		return p;
	}

	/** Block ids a patched shader decides per id (Manifest.Rt.anyHitIds); true if they changed (the caller rebuilds the BLAS). */
	boolean setAnyHitIds(List<Integer> ids) {
		if (ids.equals(anyHitIds)) return false;
		anyHitIds = List.copyOf(ids);
		if (anyHit != null) anyHit.destroyLater();
		anyHit = null;
		if (!ids.isEmpty()) {
			anyHit = new RawBuffer(ctx, ids.size() * 4L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true, "rt omm any-hit ids");
			for (int i = 0; i < ids.size(); i++) MemoryUtil.memPutInt(anyHit.mapped + i * 4L, ids.get(i));
		}
		return true;
	}

	/** The micromap for the current atlas layout, built into {@code cb} first if needed; null = no micromaps for these builds. */
	@Nullable Map prepare(VkCommandBuffer cb) {
		Atlas a = atlas;
		var view = dev.emrion.shaders.mod.pbr.PbrAtlases.blockAtlas();
		if (a == null || view == null) return null;
		if (current != null && current.generation == a.generation) return current;
		if (failedGeneration == a.generation) return null;
		if (current != null) {
			current.current = false;
			if (current.refs == 0) free(current);
			current = null;
		}
		long start = System.nanoTime();
		current = build(cb, a, ((VulkanGpuTextureView) view).vkImageView());
		if (current == null) failedGeneration = a.generation;
		buildNs = System.nanoTime() - start;
		return current;
	}

	private @Nullable Map build(VkCommandBuffer cb, Atlas a, long atlasView) {
		List<Sprite> sprites = a.sprites();
		List<Integer> slotted = new ArrayList<>();
		int maxDim = 1, bits = 0;
		for (int i = 0; i < sprites.size(); i++) {
			Sprite s = sprites.get(i);
			bits |= s.x | s.y | s.w | s.h;
			if (s.animated || s.opaque) continue;
			slotted.add(i);
			maxDim = Math.max(maxDim, Math.max(s.w, s.h));
		}
		int count = 3 + 24 * slotted.size();
		// texel-exact for the largest cutout sprite (2^L micro-triangle rows over its diagonal half), within the data budget
		int level = Math.clamp(32 - Integer.numberOfLeadingZeros(maxDim - 1) + 1, 3, Math.max(3, maxLevel));
		while (level > 3 && (long) count << (2 * level - 2) > MAX_DATA) level--;
		if (maxLevel < 3 || count > 4096 * 65535) {
			EmrionShadersClient.LOG.warn("RT: no opacity micromaps (max level {}, {} micromap triangles)", maxLevel, count);
			return null;
		}
		int shift = Math.min(Integer.numberOfTrailingZeros(bits == 0 ? 16 : bits), 4);
		int gridW = (a.width() + (1 << shift) - 1) >> shift, gridH = (a.height() + (1 << shift) - 1) >> shift;
		if ((long) gridW * gridH > (16 << 20)) {
			EmrionShadersClient.LOG.warn("RT: no opacity micromaps (atlas {}x{} needs a {}x{} sprite grid)", a.width(), a.height(), gridW, gridH);
			return null;
		}
		Map m = new Map(a.generation(), level, count);
		m.gridW = gridW;
		m.gridH = gridH;
		m.cellShift = shift;
		// tables: sprites (uvec4: x, y, w | h << 16, first slot / ANIMATED / NO_SLOTS), grid (sprite per cell), slotted list
		long spritesOff = 0, gridOff = align(sprites.size() * 16L, 16), listOff = align(gridOff + (long) gridW * gridH * 4, 16);
		m.tables = new RawBuffer(ctx, Math.max(16, listOff + slotted.size() * 4L), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true, "rt omm tables");
		long t = m.tables.mapped;
		for (int i = 0, slot = 0; i < sprites.size(); i++) {
			Sprite s = sprites.get(i);
			int first = s.animated ? ANIMATED : s.opaque ? NO_SLOTS : 3 + 24 * slot++;
			long p = t + spritesOff + i * 16L;
			MemoryUtil.memPutInt(p, s.x);
			MemoryUtil.memPutInt(p + 4, s.y);
			MemoryUtil.memPutInt(p + 8, s.w | s.h << 16);
			MemoryUtil.memPutInt(p + 12, first);
		}
		MemoryUtil.memSet(t + gridOff, 0xFF, (long) gridW * gridH * 4);
		for (int i = 0; i < sprites.size(); i++) {
			Sprite s = sprites.get(i);
			for (int gy = s.y >> shift; gy < Math.min(gridH, (s.y + s.h + (1 << shift) - 1) >> shift); gy++)
				for (int gx = s.x >> shift; gx < Math.min(gridW, (s.x + s.w + (1 << shift) - 1) >> shift); gx++)
					MemoryUtil.memPutInt(t + gridOff + ((long) gy * gridW + gx) * 4, i);
		}
		for (int i = 0; i < slotted.size(); i++) MemoryUtil.memPutInt(t + listOff + i * 4L, slotted.get(i));
		long base = scene.address(m.tables.handle);
		m.spritesAddr = base + spritesOff;
		m.gridAddr = base + gridOff;
		m.listAddr = base + listOff;

		long perTri = 1L << (2 * level - 2); // 4^L micro-triangles x 2 bits
		long dataSize = count * perTri;
		int inputUsage = VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
		RawBuffer data = new RawBuffer(ctx, dataSize + 256, inputUsage | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false, "rt omm data");
		RawBuffer triangles = new RawBuffer(ctx, count * 8L + 256, inputUsage, true, "rt omm triangles");
		long dataAddr = align(scene.address(data.handle), 256), triAddr = align(scene.address(triangles.handle), 256);
		long triHost = triangles.mapped + (triAddr - scene.address(triangles.handle));
		for (int i = 0; i < count; i++) { // VkMicromapTriangleEXT: uint32 dataOffset, uint16 subdivisionLevel, uint16 format
			MemoryUtil.memPutInt(triHost + i * 8L, (int) (i * perTri));
			MemoryUtil.memPutShort(triHost + i * 8L + 4, (short) level);
			MemoryUtil.memPutShort(triHost + i * 8L + 6, (short) VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var usage = VkMicromapUsageEXT.calloc(1, stack).count(count).subdivisionLevel(level).format(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
			var info = VkMicromapBuildInfoEXT.calloc(1, stack).sType$Default().type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT)
					.flags(VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT).mode(VK_BUILD_MICROMAP_MODE_BUILD_EXT).pUsageCounts(usage).triangleArrayStride(8)
					.usageCountsCount(1); // LWJGL does not set the count of pUsageCounts (it is shared with ppUsageCounts)
			var sizes = VkMicromapBuildSizesInfoEXT.calloc(stack).sType$Default();
			vkGetMicromapBuildSizesEXT(ctx.vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0), sizes);
			m.storage = new RawBuffer(ctx, sizes.micromapSize(), VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT, false, "rt omm micromap");
			var p = stack.mallocLong(1);
			RtScene.check(vkCreateMicromapEXT(ctx.vk, VkMicromapCreateInfoEXT.calloc(stack).sType$Default().buffer(m.storage.handle).offset(0)
					.size(sizes.micromapSize()).type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT), null, p), "vkCreateMicromapEXT");
			m.micromap = p.get(0);
			RawBuffer scratch = new RawBuffer(ctx, sizes.buildScratchSize() + 256, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false, "rt omm scratch");
			m.bytes = sizes.micromapSize() + dataSize + count * 8L;

			// the atlas (uploaded by earlier submissions) and the zeroed data before the fill
			Barriers.memory(cb, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK13.VK_ACCESS_2_MEMORY_WRITE_BIT, Barriers.TRANSFER | Barriers.COMPUTE,
					VK13.VK_ACCESS_2_MEMORY_READ_BIT | VK13.VK_ACCESS_2_MEMORY_WRITE_BIT);
			vkCmdFillBuffer(cb, data.handle, 0, VK_WHOLE_SIZE, 0);
			Barriers.memory(cb, Barriers.TRANSFER, Barriers.TRANSFER_WRITE, Barriers.COMPUTE, Barriers.STORAGE_READ | Barriers.STORAGE_WRITE);
			vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, dataPipeline);
			bindAtlas(cb, stack, atlasView);
			var pc = stack.calloc(68);
			pc.putLong(0, m.spritesAddr).putLong(8, m.listAddr).putLong(16, dataAddr).putInt(24, level).putInt(28, count).putFloat(32, eps(a));
			vkCmdPushConstants(cb, layout, VK_SHADER_STAGE_ALL, 0, pc);
			int y = Math.min(count, 4096);
			vkCmdDispatch(cb, (2 << (2 * level)) / 64, y, (count + y - 1) / y);
			Barriers.memory(cb, Barriers.COMPUTE, Barriers.STORAGE_WRITE, MICROMAP_BUILD, VK_ACCESS_2_MICROMAP_READ_BIT_EXT | VK13.VK_ACCESS_2_SHADER_READ_BIT);
			info.dstMicromap(m.micromap);
			info.data().deviceAddress(dataAddr);
			info.triangleArray().deviceAddress(triAddr);
			info.scratchData().deviceAddress(align(scene.address(scratch.handle), 256));
			vkCmdBuildMicromapsEXT(cb, info);
			Barriers.memory(cb, MICROMAP_BUILD, VK_ACCESS_2_MICROMAP_WRITE_BIT_EXT, AS_BUILD, VK_ACCESS_2_MICROMAP_READ_BIT_EXT);
			Profiler.mark(cb, "RT: opacity micromap build");
			scratch.destroyLater();
			// build inputs only: freed once the GPU is done with this build, the micromap keeps its own copy
			data.destroyLater();
			triangles.destroyLater();
		}
		EmrionShadersClient.LOG.info("RT: opacity micromap for the block atlas ({}x{}): {} of {} sprites mapped, {} micromap triangles at level {}, {} KiB",
				a.width(), a.height(), slotted.size(), sprites.size(), count, level, m.bytes >> 10);
		return m;
	}

	/** UV error of a vertex in texels: Sodium stores UVs in 15 bits; vanilla's floats are far below that. */
	private static float eps(Atlas a) {
		return Math.max(a.width(), a.height()) * 1.5f / 32768f;
	}

	private void bindAtlas(VkCommandBuffer cb, MemoryStack stack, long view) {
		long nearest = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)).vkSampler();
		var w = VkWriteDescriptorSet.calloc(1, stack);
		w.get(0).sType$Default().dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(nearest).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
		KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, w);
	}

	// ---- per BLAS -------------------------------------------------------------------------------------------------------------

	/** Gives a cutout entry an index buffer into {@code m} (filled by {@link #dispatchIndices} before its build). */
	void attach(RtScene.Entry e, Map m) {
		long size = e.quads * 8L;
		e.ommIndices = new RawBuffer(ctx, size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
				| VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, false, "rt omm indices");
		e.omm = m;
		m.refs++;
		indexBytes += size;
		entries++;
	}

	void release(RtScene.Entry e) {
		Map m = e.omm;
		if (m == null) return;
		built(e);
		entries--;
		e.omm = null;
		if (--m.refs == 0 && !m.current) free(m);
	}

	/** After e's BLAS build is recorded: its index buffer is a build input only (freed once the GPU is done with the build). */
	void built(RtScene.Entry e) {
		if (e.ommIndices == null) return;
		e.ommIndices.destroyLater();
		e.ommIndices = null;
		indexBytes -= e.quads * 8L;
	}

	/** Writes the index buffers of the batch's entries that have one; their BLAS builds follow in the same command buffer. */
	void dispatchIndices(VkCommandBuffer cb, List<RtScene.Entry> batch) {
		boolean any = false;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var pc = stack.calloc(68);
			for (RtScene.Entry e : batch) {
				Map m = e.omm;
				if (m == null) continue;
				if (!any) {
					var view = dev.emrion.shaders.mod.pbr.PbrAtlases.blockAtlas();
					if (view == null) return; // cannot happen: prepare saw it this frame
					// the sections' vertices (uploads, Sodium's position decode) before the reads
					Barriers.memory(cb, Barriers.TRANSFER | Barriers.COMPUTE | AS_BUILD, Barriers.TRANSFER_WRITE | Barriers.STORAGE_WRITE,
							Barriers.COMPUTE, Barriers.STORAGE_READ | Barriers.STORAGE_WRITE);
					vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, indexPipeline);
					bindAtlas(cb, stack, ((VulkanGpuTextureView) view).vkImageView());
					any = true;
				}
				Atlas a = atlas;
				pc.putLong(0, e.vertexAddr).putLong(8, scene.address(e.ommIndices.handle)).putLong(16, m.spritesAddr).putLong(24, m.gridAddr)
						.putLong(32, anyHit == null ? 0 : scene.address(anyHit.handle)).putInt(40, e.quads * 2).putInt(44, 0)
						.putInt(48, m.gridW).putInt(52, m.gridH).putInt(56, m.cellShift).putInt(60, anyHitIds.size()).putFloat(64, a == null ? 0.05f : eps(a));
				vkCmdPushConstants(cb, layout, VK_SHADER_STAGE_ALL, 0, pc);
				vkCmdDispatch(cb, (e.quads * 2 + 63) / 64, 1, 1);
			}
		}
		if (any) Barriers.memory(cb, Barriers.COMPUTE, Barriers.STORAGE_WRITE, AS_BUILD, VK13.VK_ACCESS_2_SHADER_READ_BIT | KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
	}

	/** Chains e's micromap into its triangle geometry (size query and build alike); {@code omm}/{@code usage} live as long as {@code t}. */
	static void chain(VkAccelerationStructureGeometryTrianglesDataKHR t, RtScene.Entry e, VkAccelerationStructureTrianglesOpacityMicromapEXT omm,
	                  VkMicromapUsageEXT.Buffer usage, long indexAddr) {
		Map m = e.omm;
		if (m == null) return;
		usage.get(0).count(e.quads * 2).subdivisionLevel(m.level).format(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
		omm.sType$Default().indexType(VK_INDEX_TYPE_UINT32).indexStride(4).baseTriangle(0).pUsageCounts(usage).usageCountsCount(1).micromap(m.micromap);
		omm.indexBuffer().deviceAddress(indexAddr);
		t.pNext(omm.address());
	}

	private void free(Map m) {
		long mm = m.micromap;
		ctx.destroyLater(() -> vkDestroyMicromapEXT(ctx.vk, mm, null));
		m.storage.destroyLater();
		m.tables.destroyLater();
	}

	void destroy() {
		if (current != null) {
			current.current = false;
			if (current.refs == 0) free(current);
		}
		current = null;
		if (anyHit != null) anyHit.destroyLater();
		ctx.destroyLater(() -> {
			vkDestroyPipeline(ctx.vk, indexPipeline, null);
			vkDestroyPipeline(ctx.vk, dataPipeline, null);
			vkDestroyPipelineLayout(ctx.vk, layout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, setLayout, null);
		});
	}

	String summary() {
		Map m = current;
		return String.format("OMM: micromap %d KiB (level %d), indices %d KiB for %d cutout layers, built in %.1f ms (CPU)",
				m == null ? 0 : m.bytes >> 10, m == null ? 0 : m.level, indexBytes >> 10, entries, buildNs / 1e6);
	}

	private static long align(long v, long a) {
		return (v + a - 1) / a * a;
	}
}
