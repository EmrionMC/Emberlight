package dev.emrion.shaders.mod.runtime;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.vk.Blend;
import dev.emrion.shaders.mod.vk.ComputePipelines;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.GraphicsPipelineBuilder;
import dev.emrion.shaders.mod.vk.ShaderModules;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything of a pack dimension that does not depend on the window size, built on background threads (ARCHITECTURE §5.7):
 * the set-1 layout, the shared pipeline layout, one VkPipeline per composite-like or compute program (created in parallel
 * with the persisted VkPipelineCache) and the runtime's own helper pipelines. Nothing is ever created lazily in a frame.
 * Geometry programs (kind gbuffers/shadow) are M3: they need the vanilla pipeline they replace (GeometryPipelines.builderFor),
 * so they are built at the swap point from {@link #setLayout} and the program's per-variant modules.
 */
public final class PackPipelines {
	/** MC's main render target format (MainTarget): what final writes. */
	public static final GpuFormat SCREEN_FORMAT = GpuFormat.RGBA8_UNORM;

	public final long setLayout, emptySetLayout, layout;
	/** Push-descriptor layout (one combined image sampler at set 0 binding 0) for the helper passes. */
	public final long helperSetLayout, helperLayout;
	/** program name → VkPipeline (graphics for composite-like, compute for compute). */
	public final Map<String, Long> pipelines = new HashMap<>();
	/** VkFormat → texel copy pipeline into that format. */
	public final Map<Integer, Long> blit = new HashMap<>();
	public final long depthConvert;

	private final VulkanContext ctx;

	public PackPipelines(VulkanContext ctx, Path cacheDir, Manifest manifest, Manifest.Dimension dim) {
		this.ctx = ctx;
		var set1 = new Descriptors.LayoutBuilder();
		for (var r : dim.resources()) set1.add(r.binding(), descriptorType(r.kind()), VK_SHADER_STAGE_ALL);
		setLayout = set1.build(ctx, false);
		emptySetLayout = new Descriptors.LayoutBuilder().build(ctx, false);
		var push = manifest.uniforms().pushConstants();
		layout = Descriptors.pipelineLayout(ctx, push == null ? 0 : push.size(), emptySetLayout, setLayout);
		helperSetLayout = new Descriptors.LayoutBuilder().add(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT).build(ctx, true);
		helperLayout = Descriptors.pipelineLayout(ctx, 0, helperSetLayout);

		Map<String, Manifest.Target> targets = new HashMap<>();
		dim.targets().forEach(t -> targets.put(t.name(), t));
		long cache = ctx.pipelineCache().handle;
		int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			List<CompletableFuture<Void>> jobs = new ArrayList<>();
			Map<String, Long> built = new java.util.concurrent.ConcurrentHashMap<>();
			for (var e : dim.programs().entrySet()) {
				var p = e.getValue();
				if (!p.kind().equals("composite") && !p.kind().equals("compute")) continue; // geometry: M3
				jobs.add(CompletableFuture.runAsync(() -> built.put(e.getKey(), build(cacheDir, p, targets, cache)), pool));
			}
			Map<Integer, Long> blits = new java.util.concurrent.ConcurrentHashMap<>();
			List<Integer> blitFormats = new ArrayList<>(List.of(VulkanConst.toVk(SCREEN_FORMAT)));
			var ct0 = targets.get("colortex0");
			if (ct0 != null) blitFormats.add(VulkanConst.toVk(Formats.gpu(ct0.format())));
			for (int f : blitFormats.stream().distinct().toList()) jobs.add(CompletableFuture.runAsync(() -> blits.put(f, helper("emrion_blit", f)), pool));
			CompletableFuture<Long> depth = CompletableFuture.supplyAsync(() -> helper("emrion_depth", VK_FORMAT_UNDEFINED), pool);
			jobs.add(depth.thenAccept(x -> {}));
			CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).join();
			pipelines.putAll(built);
			blit.putAll(blits);
			depthConvert = depth.join();
		}
		ctx.pipelineCache().save();
	}

	static int descriptorType(String kind) {
		return switch (kind) {
			case "uniform_buffer_dynamic" -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
			case "sampled_image" -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
			case "storage_image" -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			case "storage_buffer" -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			default -> throw new IllegalArgumentException("unknown resource kind " + kind);
		};
	}

	private long build(Path cacheDir, Manifest.Program p, Map<String, Manifest.Target> targets, long cache) {
		if (p.kind().equals("compute")) {
			long cs = module(cacheDir, p.stages().compute().spv());
			try {
				return ComputePipelines.create(ctx, cs, layout, cache);
			} finally {
				ShaderModules.destroy(ctx, cs);
			}
		}
		long vs = module(cacheDir, p.stages().vertex().variants().get("fullscreen"));
		long fs = module(cacheDir, p.stages().fragment().spv());
		try {
			int[] formats;
			Blend[] blends;
			if (p.group().equals("final")) {
				formats = new int[] {VulkanConst.toVk(SCREEN_FORMAT)};
				blends = new Blend[1];
			} else {
				formats = new int[p.outputs().size()];
				blends = new Blend[formats.length];
				for (var o : p.outputs()) {
					var t = targets.get(o.target());
					if (t == null) throw new IllegalStateException("output " + o.target() + " has no target");
					GpuFormat f = Formats.gpu(t.format());
					formats[o.location()] = VulkanConst.toVk(f);
					var mode = p.blend() == null ? null : p.blend().perTarget().getOrDefault(o.target(), p.blend().defaultMode());
					blends[o.location()] = Formats.isInteger(f) ? null : blend(mode);
				}
			}
			return new GraphicsPipelineBuilder().stage(VK_SHADER_STAGE_VERTEX_BIT, vs).stage(VK_SHADER_STAGE_FRAGMENT_BIT, fs)
					.layout(layout).colors(formats, blends).build(ctx, cache);
		} finally {
			ShaderModules.destroy(ctx, vs);
			ShaderModules.destroy(ctx, fs);
		}
	}

	/** Helper fullscreen pass from the mod jar; {@code VK_FORMAT_UNDEFINED} = depth-only (writes gl_FragDepth). */
	private long helper(String frag, int colorFormat) {
		long vs = ShaderModules.create(ctx, ShaderModules.resource("/assets/emrionshaders/spv/fullscreen.vert.spv"));
		long fs = ShaderModules.create(ctx, ShaderModules.resource("/assets/emrionshaders/spv/" + frag + ".frag.spv"));
		try {
			var b = new GraphicsPipelineBuilder().stage(VK_SHADER_STAGE_VERTEX_BIT, vs).stage(VK_SHADER_STAGE_FRAGMENT_BIT, fs).layout(helperLayout);
			if (colorFormat == VK_FORMAT_UNDEFINED) b.depth(VK_FORMAT_D32_SFLOAT, true, true, VK_COMPARE_OP_ALWAYS);
			else b.colors(new int[] {colorFormat});
			return b.build(ctx, ctx.pipelineCache().handle);
		} finally {
			ShaderModules.destroy(ctx, vs);
			ShaderModules.destroy(ctx, fs);
		}
	}

	static Blend blend(Manifest.BlendMode m) {
		if (m == null || m.mode().equals("off")) return null;
		if (m.mode().equals("vanilla")) return Blend.ALPHA;
		return Blend.of(factor(m.src()), factor(m.dst()), factor(m.srcA()), factor(m.dstA()));
	}

	static int factor(String f) {
		return switch (f) {
			case "zero" -> VK_BLEND_FACTOR_ZERO;
			case "one" -> VK_BLEND_FACTOR_ONE;
			case "src_color" -> VK_BLEND_FACTOR_SRC_COLOR;
			case "one_minus_src_color" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
			case "dst_color" -> VK_BLEND_FACTOR_DST_COLOR;
			case "one_minus_dst_color" -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
			case "src_alpha" -> VK_BLEND_FACTOR_SRC_ALPHA;
			case "one_minus_src_alpha" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
			case "dst_alpha" -> VK_BLEND_FACTOR_DST_ALPHA;
			case "one_minus_dst_alpha" -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
			case "src_alpha_saturate" -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
			default -> throw new IllegalArgumentException("unknown blend factor " + f);
		};
	}

	private long module(Path cacheDir, String spv) {
		if (spv == null) throw new IllegalStateException("program stage without SPIR-V");
		try {
			return ShaderModules.create(ctx, Files.readAllBytes(cacheDir.resolve(spv)));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public void destroyLater() {
		ctx.destroyLater(() -> {
			pipelines.values().forEach(p -> vkDestroyPipeline(ctx.vk, p, null));
			blit.values().forEach(p -> vkDestroyPipeline(ctx.vk, p, null));
			vkDestroyPipeline(ctx.vk, depthConvert, null);
			vkDestroyPipelineLayout(ctx.vk, layout, null);
			vkDestroyPipelineLayout(ctx.vk, helperLayout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, setLayout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, emptySetLayout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, helperSetLayout, null);
		});
	}
}
