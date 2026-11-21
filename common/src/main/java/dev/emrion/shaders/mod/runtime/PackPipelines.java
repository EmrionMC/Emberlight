package dev.emrion.shaders.mod.runtime;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.pipeline.GbufferPipelines;
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
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything of a pack dimension that does not depend on the window size, built on background threads: layouts, one
 * VkPipeline per composite-like or compute program, and helper pipelines. Nothing is created lazily in a frame.
 * Gbuffers programs are built per vanilla pipeline they replace ({@link GbufferPipelines}).
 */
public final class PackPipelines {
	/** MC's main render target format (MainTarget): what final writes. */
	public static final GpuFormat SCREEN_FORMAT = GpuFormat.RGBA8_UNORM;

	// not final: a constructor that fails half-way releases what it built (handles still 0 are skipped by vkDestroy*)
	public long setLayout, emptySetLayout, layout;
	/** Push-descriptor layout (one combined image sampler at set 0 binding 0) for the helper passes. */
	public long helperSetLayout, helperLayout;
	/** Compute clears of unsigned-integer storage images (push descriptor: one storage image; push constant: the value). */
	public long clearSetLayout, clearLayout, clear2d, clear3d;
	/** program name → VkPipeline (graphics for composite-like, compute for compute). */
	public final Map<String, Long> pipelines = new java.util.concurrent.ConcurrentHashMap<>();
	/** Compute programs compiled as ray generation shaders (RtRaygen): their SBT; the handle is in {@link #pipelines} too. */
	public final Map<String, dev.emrion.shaders.mod.rt.RtRaygen.Pipeline> raygen = new java.util.concurrent.ConcurrentHashMap<>();
	/** VkFormat → texel copy pipeline into that format. */
	public final Map<Integer, Long> blit = new java.util.concurrent.ConcurrentHashMap<>();
	/** Area-filter mip generation per colour format of the mipmapped targets (FrameExecutor.mipmaps). */
	public final Map<Integer, Long> mip = new java.util.concurrent.ConcurrentHashMap<>();
	public GbufferPipelines gbuffers;
	/** Constant default vertex attributes for gbuffers inputs the vanilla formats lack (GeometryPipelines.DEFAULTS). */
	public dev.emrion.shaders.mod.vk.RawBuffer defaults;

	private final VulkanContext ctx;
	/** Pack programs built so far and to build in the running foreground build (one at a time, on the pack loader): the loading panel's x/y. */
	public static final java.util.concurrent.atomic.AtomicInteger BUILT = new java.util.concurrent.atomic.AtomicInteger(),
			TOTAL = new java.util.concurrent.atomic.AtomicInteger();
	/** The build stops once this is true (null: never). */
	private final java.util.function.@Nullable BooleanSupplier cancelled;
	/** False: a foreground build (all cores, counts {@link #BUILT}); true: a background build (2 low-priority threads). */
	private final boolean background;

	/** {@code vanilla}: every vanilla pipeline that may draw in a world phase, compiled (collected on the render thread). */
	public PackPipelines(VulkanContext ctx, Path cacheDir, Manifest manifest, Manifest.Dimension dim,
			Map<RenderPipeline, FrontendRenderPipeline> vanilla, java.util.function.@Nullable BooleanSupplier cancelled, boolean background) {
		this.ctx = ctx;
		this.cancelled = cancelled;
		this.background = background;
		if (!background) {
			BUILT.set(0);
			TOTAL.set(0);
		}
		try {
			build(cacheDir, manifest, dim, vanilla);
		} catch (RuntimeException | Error failed) {
			net.minecraft.client.Minecraft.getInstance().execute(this::destroyLater);
			throw failed;
		}
		ctx.pipelineCache().save();
	}

	private void build(Path cacheDir, Manifest manifest, Manifest.Dimension dim, Map<RenderPipeline, FrontendRenderPipeline> vanilla) {
		defaults = new dev.emrion.shaders.mod.vk.RawBuffer(ctx, 32, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, true);
		dev.emrion.shaders.mod.pipeline.GeometryPipelines.writeDefaults(defaults.mapped);
		var set1 = new Descriptors.LayoutBuilder();
		for (var r : dim.resources()) set1.add(r.binding(), descriptorType(r.kind()), VK_SHADER_STAGE_ALL);
		setLayout = set1.build(ctx, false);
		emptySetLayout = new Descriptors.LayoutBuilder().build(ctx, false);
		var push = manifest.uniforms().pushConstants();
		layout = Descriptors.pipelineLayout(ctx, push == null ? 0 : push.size(), emptySetLayout, setLayout);
		helperSetLayout = new Descriptors.LayoutBuilder().add(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT).build(ctx, true);
		helperLayout = Descriptors.pipelineLayout(ctx, 0, helperSetLayout);
		clearSetLayout = new Descriptors.LayoutBuilder().add(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT).build(ctx, true);
		clearLayout = Descriptors.pipelineLayout(ctx, 16, clearSetLayout);
		if (ctx.storageWriteWithoutFormat) { // the clear shaders need it; without it FrameExecutor clears by transfer
			clear2d = computeHelper("emrion_clear2d");
			clear3d = computeHelper("emrion_clear3d");
		}

		Map<String, Manifest.Target> targets = new HashMap<>();
		dim.targets().forEach(t -> targets.put(t.name(), t));
		long cache = ctx.pipelineCache().handle;
		int threads = background ? 2 : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
		try (ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
			Thread t = new Thread(r, "Emberlight pipeline builder");
			t.setDaemon(true);
			if (background) t.setPriority(Thread.MIN_PRIORITY);
			return t;
		})) {
			List<CompletableFuture<Void>> jobs = new ArrayList<>();
			for (var e : dim.programs().entrySet()) {
				var p = e.getValue();
				if (!p.kind().equals("composite") && !p.kind().equals("compute")) continue; // geometry programs: GbufferPipelines
				counted(TOTAL);
				jobs.add(CompletableFuture.runAsync(() -> {
					checkCancelled();
					pipelines.put(e.getKey(), build(cacheDir, e.getKey(), p, targets, cache));
					counted(BUILT);
				}, pool));
			}
			List<Integer> blitFormats = new ArrayList<>(List.of(VulkanConst.toVk(SCREEN_FORMAT)));
			var ct0 = targets.get("colortex0");
			if (ct0 != null) blitFormats.add(VulkanConst.toVk(Formats.gpu(ct0.format())));
			for (int f : blitFormats.stream().distinct().toList()) jobs.add(CompletableFuture.runAsync(() -> blit.put(f, helper("emrion_blit", f)), pool));
			for (int f : targets.values().stream().filter(t -> t.mipLevels() != 1 && (t.kind().equals("color") || t.kind().equals("shadow_color")) && !Formats.isInteger(Formats.gpu(t.format())))
					.map(t -> VulkanConst.toVk(Formats.gpu(t.format()))).distinct().toList())
				jobs.add(CompletableFuture.runAsync(() -> mip.put(f, helper("emrion_mip", f)), pool));
			gbuffers = new GbufferPipelines(ctx, cacheDir, manifest, dim, setLayout, vanilla, pool, this);
			CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).join();
		}
	}

	/** Before each pipeline: stops a cancelled build (it fails with a CancellationException). */
	public void checkCancelled() {
		if (cancelled != null && cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("pipeline build stopped");
	}

	/** Progress of a foreground build only. */
	public void counted(java.util.concurrent.atomic.AtomicInteger counter) {
		if (!background) counter.incrementAndGet();
	}

	static int descriptorType(String kind) {
		return switch (kind) {
			case "uniform_buffer_dynamic" -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
			case "sampled_image" -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
			case "storage_image" -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			case "storage_buffer" -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			case "acceleration_structure" -> org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
			default -> throw new IllegalArgumentException("unknown resource kind " + kind);
		};
	}

	private long build(Path cacheDir, String name, Manifest.Program p, Map<String, Manifest.Target> targets, long cache) {
		if (p.kind().equals("compute")) {
			byte[] spv = bytes(cacheDir, p.stages().compute().spv());
			long cs = ShaderModules.create(ctx, spv);
			try {
				if (dev.emrion.shaders.mod.rt.RtRaygen.isRaygen(spv)) {
					var rg = dev.emrion.shaders.mod.rt.RtRaygen.create(ctx, cs, layout, cache);
					raygen.put(name, rg);
					dev.emrion.shaders.mod.EmrionShadersClient.LOG.debug("{} runs as a ray generation shader", name);
					return rg.handle();
				}
				return ComputePipelines.create(ctx, cs, layout, cache);
			} finally {
				ShaderModules.destroy(ctx, cs);
			}
		}
		long vs = module(cacheDir, p.stages().vertex().variants().get("fullscreen")), fs = 0; // destroying 0 is a no-op
		try {
			fs = module(cacheDir, p.stages().fragment().spv());
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

	private long computeHelper(String name) {
		long cs = ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/" + name + ".comp.spv"));
		try {
			return ComputePipelines.create(ctx, cs, clearLayout, ctx.pipelineCache().handle);
		} finally {
			ShaderModules.destroy(ctx, cs);
		}
	}

	/** Helper fullscreen pass from the mod jar. */
	private long helper(String frag, int colorFormat) {
		long vs = ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/fullscreen.vert.spv")), fs = 0;
		try {
			fs = ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/" + frag + ".frag.spv"));
			return new GraphicsPipelineBuilder().stage(VK_SHADER_STAGE_VERTEX_BIT, vs).stage(VK_SHADER_STAGE_FRAGMENT_BIT, fs).layout(helperLayout)
					.colors(new int[] {colorFormat}).build(ctx, ctx.pipelineCache().handle);
		} finally {
			ShaderModules.destroy(ctx, vs);
			ShaderModules.destroy(ctx, fs);
		}
	}

	public static Blend blend(Manifest.BlendMode m) {
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
		return ShaderModules.create(ctx, bytes(cacheDir, spv));
	}

	private static byte[] bytes(Path cacheDir, String spv) {
		if (spv == null) throw new IllegalStateException("program stage without SPIR-V");
		try {
			return Files.readAllBytes(cacheDir.resolve(spv));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Render thread only (the gbuffers pipelines are MC pipelines). */
	public void destroyLater() {
		if (gbuffers != null) gbuffers.destroyLater();
		if (defaults != null) defaults.destroyLater();
		ctx.destroyLater(() -> {
			pipelines.values().forEach(p -> vkDestroyPipeline(ctx.vk, p, null));
			raygen.values().forEach(r -> r.sbt().destroyLater());
			blit.values().forEach(p -> vkDestroyPipeline(ctx.vk, p, null));
			mip.values().forEach(p -> vkDestroyPipeline(ctx.vk, p, null));
			vkDestroyPipeline(ctx.vk, clear2d, null);
			vkDestroyPipeline(ctx.vk, clear3d, null);
			vkDestroyPipelineLayout(ctx.vk, clearLayout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, clearSetLayout, null);
			vkDestroyPipelineLayout(ctx.vk, layout, null);
			vkDestroyPipelineLayout(ctx.vk, helperLayout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, setLayout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, emptySetLayout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, helperSetLayout, null);
		});
	}
}
