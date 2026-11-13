package dev.emrion.shaders.mod.runtime;

import static dev.emrion.shaders.mod.vk.Barriers.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.pipeline.GbufferPipelines;
import dev.emrion.shaders.mod.pipeline.GeometryPipelines;
import dev.emrion.shaders.mod.uniforms.UniformSystem;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.vk.RawCommands;
import dev.emrion.shaders.mod.vk.RawPass;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

/**
 * Runs a dimension's {@code once} and {@code frame} commands (FORMAT §7.4) on a {@link PackRuntime}, in plan order, split at
 * the {@code geometry} phases that MC's own draw code renders (ARCHITECTURE §5.5):
 * <ul>
 *   <li>{@link #levelStart} (LevelRenderer.render HEAD): uniforms, {@code once}, every command before the first world phase;</li>
 *   <li>{@link #advanceTo} (from the hooks, outside MC render passes): the raw commands up to a phase, then the hook opens
 *       the phase's gbuffers pass with {@link #beginPhase} and MC draws into it;</li>
 *   <li>{@link #finish} (after the level's frame graph ran): the rest, i.e. composites, final and the end-of-frame copies.</li>
 * </ul>
 * The shadow phases are drawn by {@link ShadowPass} before MC's main pass. Every raw pass declares what it reads and writes
 * to {@link Hazards}, which emits the minimal barrier inside our command buffers; between them and MC's passes (shadow
 * geometry → shadowcomp, shadowcomp → gbuffers) MC's global barrier at every render-pass end and our
 * {@link dev.emrion.shaders.mod.vk.RawCommands#submit} fences everything, including the vertex-stage image/SSBO stores.
 */
public final class FrameExecutor {
	/** A/B switches for the benchmark (docs/BENCHMARK.md); both on by default. */
	private static final boolean DEFER_CLEARS = !"false".equals(System.getProperty("emrion.perf.deferClears")),
			COMPUTE_CLEARS = !"false".equals(System.getProperty("emrion.perf.computeClears"));
	private static final long SHADER_STAGES = VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | FRAGMENT;
	/** Iris WorldRenderingPhase ordinals have 24 entries (iris-spec §1.4). */
	private static final int STAGES = 24;
	/** EmrionPhase blocks per frame at most: FULLSCREEN plus every (WORLD/HAND/SHADOW, renderStage). */
	public static final int PHASE_BLOCKS = 1 + 3 * STAGES;

	private final PackRuntime rt;
	private final PackPipelines pp;
	private final Manifest.Dimension dim;
	private final UniformSystem uniforms;
	private final Hazards hazards = new Hazards();
	private final int[] dynamicOffsets = new int[2];
	/** GPU profiler label per frame / once command (built once: no string building per frame). */
	private final String[] frameLabels, onceLabels;
	private final PackRuntime.Img mcColor = new PackRuntime.Img("minecraft main color", null);
	private boolean oncePending = true;
	private final boolean hasShadowPass;
	/** Frame-start clears of raw images moved behind their last use in the frame (see {@link DeferredClears}). */
	private final DeferredClears deferred;
	private boolean firstFrame = true;

	/** Next frame command to run; -1 outside a level render. */
	private int cursor = -1;
	/** Per frame: EmrionPhase ring offset per (phase, renderStage), -1 = not written yet. */
	private final int[][] phaseOffsets = new int[UniformSystem.Phase.values().length][STAGES];
	private GbufferPipelines.@Nullable Context context;
	private UniformSystem.Phase phase = UniformSystem.Phase.WORLD;
	private int stage;

	public FrameExecutor(PackRuntime rt) {
		this.rt = rt;
		this.pp = rt.pipelines;
		this.dim = rt.dim;
		this.uniforms = rt.uniforms;
		frameLabels = dim.frame().stream().map(FrameExecutor::label).toArray(String[]::new);
		onceLabels = dim.once().stream().map(FrameExecutor::label).toArray(String[]::new);
		hasShadowPass = dim.frame().stream().anyMatch(c -> "shadow_opaque".equals(c.phase()));
		deferred = new DeferredClears(dim, name -> {
			PackRuntime.Img[] sides = rt.images.get(name);
			if (!DEFER_CLEARS) return false;
			return sides != null && sides[0].raw != null && sides[1] == null;
		});
		deferred.start().forEach((c, kept) -> frameLabels[dim.frame().indexOf(c)] = label(new Manifest.Command("clear", null, null, null, null,
				null, null, null, null, kept)));
	}

	private void deferredClears(VkCommandBuffer cb, int i) {
		var ops = deferred.before()[i];
		if (ops == null) return;
		clear(cb, ops);
		Profiler.mark(cb, "clear (deferred, end of last use)");
	}

	public PackPipelines pipelines() {
		return pp;
	}

	public Manifest manifest() {
		return rt.manifest;
	}

	public Manifest.Dimension dimension() {
		return dim;
	}

	UniformSystem uniforms() {
		return uniforms;
	}

	/** Before MC's main pass (Iris renderShadows): the shadow geometry phases, if the plan has them. */
	public void renderShadows(net.minecraft.client.renderer.LevelRenderer level) {
		if (cursor < 0) return;
		long t = Profiler.cpuStart(), raw = Profiler.rawSoFar();
		ShadowPass.render(this, level);
		Profiler.shadowEnd(t, raw);
	}

	/** True if this dimension's plan draws a shadow pass (Iris then turns off vanilla entity shadows). */
	public boolean hasShadowPass() {
		return hasShadowPass;
	}

	// ---- hook entry points ---------------------------------------------------------------------------------------------

	/** LevelRenderer.render HEAD: the level projection of this frame is known, no MC render pass is open. */
	public void levelStart(float partialTick) {
		long t = Profiler.cpuStart();
		uniforms.beginFrame(partialTick);
		rt.ring.nextFrame();
		for (int[] p : phaseOffsets) java.util.Arrays.fill(p, -1);
		var layout = rt.manifest.uniforms();
		long frameOffset = rt.ring.alloc(layout.size());
		uniforms.writeFrameBlock(MemoryUtil.memByteBuffer(rt.ring.address(frameOffset), layout.size()).order(java.nio.ByteOrder.nativeOrder()));
		dynamicOffsets[0] = GeometryPipelines.DYNAMIC_OFFSETS[0] = (int) frameOffset;
		dynamicOffsets[1] = phaseOffset(UniformSystem.Phase.FULLSCREEN, 0);
		var gr = Minecraft.getInstance().gameRenderer;
		long nearest = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)).vkSampler();
		long linear = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)).vkSampler();
		GeometryPipelines.EXTRA_VIEWS[0] = rt.whiteView();
		GeometryPipelines.EXTRA_VIEWS[1] = ((VulkanGpuTextureView) gr.overlayTexture().getTextureView()).vkImageView();
		GeometryPipelines.EXTRA_VIEWS[2] = ((VulkanGpuTextureView) gr.lightmap()).vkImageView();
		GeometryPipelines.EXTRA_SAMPLERS[0] = GeometryPipelines.EXTRA_SAMPLERS[1] = nearest;
		GeometryPipelines.EXTRA_SAMPLERS[2] = linear;
		GeometryPipelines.defaultsBuffer = pp.defaults.handle;
		Profiler.cpuEnd(Profiler.UNIFORMS, t);

		t = Profiler.cpuStart();
		VkCommandBuffer cb = begin();
		Profiler.mark(cb, "MC: before the level");
		if (oncePending) {
			for (int i = 0; i < dim.once().size(); i++) {
				run(cb, dim.once().get(i));
				Profiler.mark(cb, onceLabels[i]);
			}
			oncePending = false;
		}
		int i = 0;
		for (; i < dim.frame().size() && !dim.frame().get(i).cmd().equals("geometry"); i++) {
			deferredClears(cb, i);
			run(cb, dim.frame().get(i));
			Profiler.mark(cb, frameLabels[i]);
		}
		cursor = i;
		RawCommands.submit(rt.ctx, cb);
		Profiler.cpuEnd(Profiler.RAW, t);
	}

	/** True between {@link #levelStart} and {@link #finish}. */
	public boolean inLevel() {
		return cursor >= 0;
	}

	/**
	 * Runs the raw commands up to the geometry command of {@code phase} and returns it; null (nothing run) if the rest of the
	 * plan has no such phase. Phases whose hook did not fire this frame (e.g. no sky in the Nether) are passed over.
	 */
	public Manifest.@Nullable Command advanceTo(String phaseName) {
		if (cursor < 0) return null;
		int j = cursor;
		while (j < dim.frame().size() && !(dim.frame().get(j).cmd().equals("geometry") && dim.frame().get(j).phase().equals(phaseName))) j++;
		if (j == dim.frame().size()) return null;
		runRange(cursor, j);
		cursor = j + 1;
		return dim.frame().get(j);
	}

	/** After the level's frame graph: composites, final (into MC's main colour target) and the end-of-frame copies. */
	public void finish() {
		if (cursor < 0) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		mcColor.attach = mcColor.sampled = ((VulkanGpuTextureView) main.getColorTextureView()).vkImageView();
		mcColor.image = ((VulkanGpuTexture) main.getColorTexture()).vkImage();
		mcColor.width = main.width;
		mcColor.height = main.height;
		mcColor.vkFormat = VulkanConst.toVk(PackPipelines.SCREEN_FORMAT);
		runRange(cursor, dim.frame().size());
		if (deferred.before()[dim.frame().size()] != null) {
			VkCommandBuffer cb = begin();
			deferredClears(cb, dim.frame().size());
			RawCommands.submit(rt.ctx, cb);
		}
		cursor = -1;
		firstFrame = false;
	}

	private void runRange(int from, int to) {
		long t = Profiler.cpuStart();
		VkCommandBuffer cb = null;
		for (int i = from; i < to; i++) {
			var c = dim.frame().get(i);
			if (c.cmd().equals("geometry")) continue;
			if (cb == null) {
				cb = begin();
				Profiler.mark(cb, "MC (between our passes)");
			}
			deferredClears(cb, i);
			run(cb, c);
			Profiler.mark(cb, frameLabels[i]);
		}
		if (cb != null) RawCommands.submit(rt.ctx, cb);
		Profiler.cpuEnd(Profiler.RAW, t);
	}

	// ---- geometry phases -----------------------------------------------------------------------------------------------

	/**
	 * Opens the gbuffers render pass of a geometry command: every gbuffers target on the side the command's flip state reads
	 * (gbuffers write the read side, iris-spec §2.2), plus depthtex0; in the SHADOW context the shadowcolor targets (main)
	 * plus shadowtex0. MC draws into it; the swap picks {@code ctx}'s pipelines.
	 * The caller binds MC's default uniforms if MC does not, closes the pass and calls {@link #endPhase}.
	 */
	public RenderPass beginPhase(Manifest.Command g, GbufferPipelines.Context ctx, UniformSystem.Phase phase, int stage) {
		Profiler.mark("MC (between our passes)");
		var flip = rt.flipState(g.flipState());
		var d = RenderPassDescriptor.builder(() -> "emrion " + g.phase());
		boolean shadow = ctx == GbufferPipelines.Context.SHADOW;
		for (String t : pp.gbuffers.targets(ctx)) d.withColorAttachment((shadow ? rt.image(t, "main") : rt.read(t, flip)).attachView);
		d.withDepthAttachment(rt.image(shadow ? "shadowtex0" : "depthtex0", "main").attachView);
		RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(d.build());
		GeometryPipelines.set = rt.sets[g.flipState()];
		this.context = ctx;
		this.phase = phase;
		this.stage = -1;
		setStage(stage);
		phaseLabel = frameLabels[dim.frame().indexOf(g)];
		return pass;
	}

	/** The draw context of the open phase (which pipelines the swap returns); null outside a phase. */
	public GbufferPipelines.@Nullable Context context() {
		return context;
	}

	/** Iris renderStage of the following draws (EmrionPhase.renderStage); re-binds set 1 if one of our pipelines is bound. */
	public void setStage(int s) {
		if (context == null || s == stage) return;
		stage = s;
		GeometryPipelines.DYNAMIC_OFFSETS[1] = phaseOffset(phase, s);
		GeometryPipelines.rebind();
	}

	public int stage() {
		return stage;
	}

	/** The phase's render pass is closed. */
	public void endPhase() {
		if (context == null) return;
		context = null;
		GeometryPipelines.unbind();
		Profiler.mark(phaseLabel);
	}

	private int phaseOffset(UniformSystem.Phase p, int s) {
		int off = phaseOffsets[p.ordinal()][s];
		if (off >= 0) return off;
		int size = rt.manifest.uniforms().phase().size();
		uniforms.setRenderStage(s);
		long o = rt.ring.alloc(size);
		uniforms.writePhaseBlock(MemoryUtil.memByteBuffer(rt.ring.address(o), size).order(java.nio.ByteOrder.nativeOrder()), p);
		return phaseOffsets[p.ordinal()][s] = (int) o;
	}

	private VkCommandBuffer begin() {
		hazards.reset();
		return RawCommands.begin(rt.ctx);
	}

	// ---- commands ------------------------------------------------------------------------------------------------------

	private void run(VkCommandBuffer cb, Manifest.Command c) {
		switch (c.cmd()) {
			case "geometry" -> { return; } // drawn by MC through the hooks (advanceTo / beginPhase)
			case "clear" -> clear(cb, firstFrame ? c.targets() : deferred.start().getOrDefault(c, c.targets()));
			case "compute" -> compute(cb, c);
			case "fullscreen" -> fullscreen(cb, c);
			case "final" -> finalPass(cb, c);
			case "mipmaps" -> mipmaps(cb, rt.image(c.target(), c.side()));
			case "copy" -> copy(cb, rt.image(c.target(), c.from()), rt.image(c.target(), c.to()));
			case "copy_depth" -> copy(cb, rt.image(c.from(), "main"), rt.image(c.to(), "main"));
			default -> throw new IllegalStateException("unknown frame command " + c.cmd());
		}
	}

	private static String label(Manifest.Command c) {
		if (c.cmd().equals("geometry")) return "gbuffers " + c.phase() + " (MC draws)";
		if (c.cmd().equals("clear")) return "clear " + c.targets().stream().map(Manifest.ClearOp::name).collect(java.util.stream.Collectors.joining(","));
		return c.program() != null ? c.cmd() + " " + c.program() : c.cmd() + (c.target() != null ? " " + c.target() : c.to() != null ? " " + c.to() : "");
	}

	private void clear(VkCommandBuffer cb, List<Manifest.ClearOp> ops) {
		for (var op : ops) {
			PackRuntime.Buf buf = rt.buffers.get(op.name());
			if (buf != null) hazards.write(buf, TRANSFER, TRANSFER_WRITE);
			for (String side : sides(op.side())) {
				var img = rt.image(op.name(), side);
				if (img != null) hazards.write(img, computeClear(img) ? COMPUTE : TRANSFER, computeClear(img) ? STORAGE_WRITE : TRANSFER_WRITE);
			}
		}
		hazards.flush(cb);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (var op : ops) {
				PackRuntime.Buf buf = rt.buffers.get(op.name());
				if (buf != null) vkCmdFillBuffer(cb, buf.buffer.handle, 0, VK_WHOLE_SIZE, 0);
				for (String side : sides(op.side())) {
					var img = rt.image(op.name(), side);
					if (img == null) continue;
					var range = VkImageSubresourceRange.calloc(stack).aspectMask(img.isDepth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT)
							.levelCount(VK_REMAINING_MIP_LEVELS).layerCount(1);
					if (computeClear(img)) {
						float[] color = clearColor(op, img.target);
						vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, img.depth > 1 ? pp.clear3d : pp.clear2d);
						RawPass.pushImages(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pp.clearLayout, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, img.attach);
						vkCmdPushConstants(cb, pp.clearLayout, VK_SHADER_STAGE_ALL, 0,
								stack.ints((int) color[0], (int) color[1], (int) color[2], (int) color[3]));
						vkCmdDispatch(cb, ceilDiv(img.width, 8), ceilDiv(img.height, 8), img.depth > 1 ? ceilDiv(img.depth, 4) : 1);
						continue;
					}
					if (img.isDepth) {
						vkCmdClearDepthStencilImage(cb, img.image, VK_IMAGE_LAYOUT_GENERAL, VkClearDepthStencilValue.calloc(stack).depth(1f), range);
						continue;
					}
					float[] color = clearColor(op, img.target);
					var value = VkClearColorValue.calloc(stack);
					for (int i = 0; i < 4; i++) {
						if (Formats.isInteger(img.format)) value.int32(i, (int) color[i]);
						else value.float32(i, color[i]);
					}
					vkCmdClearColorImage(cb, img.image, VK_IMAGE_LAYOUT_GENERAL, value, range);
				}
			}
		}
	}

	/** Raw unsigned-integer images (the voxel volumes) are cleared by a compute pass, which raw-pass barriers do not wait on. */
	private static boolean computeClear(PackRuntime.Img img) {
		return COMPUTE_CLEARS && dev.emrion.shaders.mod.vk.VulkanContext.get().storageWriteWithoutFormat && img.raw != null && img.target.format().endsWith("UI");
	}

	private static List<String> sides(String side) {
		return "both".equals(side) ? List.of("main", "alt") : List.of(side == null ? "main" : side);
	}

	private float[] clearColor(Manifest.ClearOp op, Manifest.Target t) {
		if (op.color() != null) return op.color();
		if (t.clearFog()) {
			Vector4fc f = uniforms.clearFogColor();
			return new float[] {f.x(), f.y(), f.z(), f.w()};
		}
		return t.clearColor() != null ? t.clearColor() : new float[4];
	}

	/** Declares a program's set-1 accesses in this flip state. */
	private void declareSet1(Manifest.Program p, int flip, long stages) {
		var res = rt.bindingResource[flip];
		for (int b : p.set1()) {
			if (b >= res.length || res[b] == null) continue;
			boolean storage = !"sampled_image".equals(rt.bindingKind[b]);
			if (storage && p.writesStorage().contains(b)) hazards.write(res[b], stages, STORAGE_READ | STORAGE_WRITE);
			else hazards.read(res[b], stages, storage ? STORAGE_READ : SAMPLED_READ);
		}
	}

	private void bindSet(VkCommandBuffer cb, int bindPoint, int flip) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkCmdBindDescriptorSets(cb, bindPoint, pp.layout, 1, stack.longs(rt.sets[flip]), stack.ints(dynamicOffsets));
		}
	}

	private void compute(VkCommandBuffer cb, Manifest.Command c) {
		var p = dim.programs().get(c.program());
		declareSet1(p, c.flipState(), COMPUTE);
		hazards.flush(cb);
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pp.pipelines.get(c.program()));
		bindSet(cb, VK_PIPELINE_BIND_POINT_COMPUTE, c.flipState());
		int[] local = p.localSize() != null ? p.localSize() : new int[] {1, 1, 1};
		int x, y, z = 1;
		if (p.workGroups() != null) {
			x = p.workGroups()[0];
			y = p.workGroups()[1];
			z = p.workGroups()[2];
		} else { // Iris default: one invocation per screen pixel
			float[] s = p.workGroupsRender() != null ? p.workGroupsRender() : new float[] {1, 1};
			x = ceilDiv((int) Math.ceil(rt.width * s[0]), local[0]);
			y = ceilDiv((int) Math.ceil(rt.height * s[1]), local[1]);
		}
		vkCmdDispatch(cb, x, y, z);
	}

	private static int ceilDiv(int a, int b) {
		return Math.max(1, (a + b - 1) / b);
	}

	private void fullscreen(VkCommandBuffer cb, Manifest.Command c) {
		var p = dim.programs().get(c.program());
		declareSet1(p, c.flipState(), SHADER_STAGES);
		long[] views = new long[c.writes().size()];
		PackRuntime.Img first = null;
		for (var w : c.writes()) {
			var img = rt.image(w.target(), w.side());
			var mode = p.blend() == null ? null : p.blend().perTarget().getOrDefault(w.target(), p.blend().defaultMode());
			hazards.write(img, COLOR_OUT, COLOR_WRITE | (PackPipelines.blend(mode) != null ? COLOR_READ : 0));
			views[w.location()] = img.attach;
			if (first == null) first = img;
		}
		hazards.flush(cb);
		draw(cb, p, c.program(), c.flipState(), first.width, first.height, views);
	}

	private void finalPass(VkCommandBuffer cb, Manifest.Command c) {
		if (c.program() == null) { // no final program: Iris copies colortex0's read side to the screen
			blit(cb, rt.read("colortex0", rt.flipState(c.flipState())), mcColor);
			return;
		}
		var p = dim.programs().get(c.program());
		declareSet1(p, c.flipState(), SHADER_STAGES);
		hazards.write(mcColor, COLOR_OUT, COLOR_WRITE);
		hazards.flush(cb);
		draw(cb, p, c.program(), c.flipState(), mcColor.width, mcColor.height, mcColor.attach);
	}

	private void draw(VkCommandBuffer cb, Manifest.Program p, String program, int flip, int w, int h, long... views) {
		RawPass.begin(cb, w, h, views);
		if (p.viewportScale() != 1f && p.viewportScale() > 0f) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				int sw = Math.max(1, (int) (w * p.viewportScale())), sh = Math.max(1, (int) (h * p.viewportScale()));
				vkCmdSetViewport(cb, 0, VkViewport.calloc(1, stack).width(sw).height(sh).maxDepth(1f));
				var scissor = VkRect2D.calloc(1, stack);
				scissor.extent().set(sw, sh);
				vkCmdSetScissor(cb, 0, scissor);
			}
		}
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pp.pipelines.get(program));
		bindSet(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, flip);
		vkCmdDraw(cb, 3, 1, 0, 0);
		RawPass.end(cb);
	}

	/** Texel copy through a fullscreen pass (converts formats, e.g. MC's RGBA8 → colortex0's R11G11B10F). */
	private void blit(VkCommandBuffer cb, PackRuntime.Img src, PackRuntime.Img dst) {
		Long pipeline = pp.blit.get(dst.vkFormat);
		if (pipeline == null) throw new IllegalStateException("no blit pipeline for format " + dst.vkFormat);
		hazards.read(src, FRAGMENT, SAMPLED_READ);
		hazards.write(dst, COLOR_OUT, COLOR_WRITE);
		hazards.flush(cb);
		RawPass.begin(cb, dst.width, dst.height, dst.attach);
		helper(cb, pipeline, src.sampled);
		RawPass.end(cb);
	}

	private void helper(VkCommandBuffer cb, long pipeline, long view) {
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
		RawPass.pushImages(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pp.helperLayout, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, rt.nearestSampler(), view);
		vkCmdDraw(cb, 3, 1, 0, 0);
	}

	/** Full mip chain by successive linear blits (nearest for integer formats), level i-1 → i. */
	private void mipmaps(VkCommandBuffer cb, PackRuntime.Img img) {
		if (img == null || img.mips <= 1) return;
		hazards.write(img, TRANSFER, TRANSFER_READ | TRANSFER_WRITE);
		hazards.flush(cb);
		int aspect = img.isDepth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
		int filter = img.isDepth || Formats.isInteger(img.format) ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (int level = 1; level < img.mips; level++) {
				if (level > 1) memory(cb, TRANSFER, TRANSFER_WRITE, TRANSFER, TRANSFER_READ); // previous level written
				var region = VkImageBlit.calloc(1, stack);
				region.srcSubresource().aspectMask(aspect).mipLevel(level - 1).layerCount(1);
				region.srcOffsets(1).set(Math.max(1, img.width >> (level - 1)), Math.max(1, img.height >> (level - 1)), 1);
				region.dstSubresource().aspectMask(aspect).mipLevel(level).layerCount(1);
				region.dstOffsets(1).set(Math.max(1, img.width >> level), Math.max(1, img.height >> level), 1);
				vkCmdBlitImage(cb, img.image, VK_IMAGE_LAYOUT_GENERAL, img.image, VK_IMAGE_LAYOUT_GENERAL, region, filter);
			}
		}
	}

	private void copy(VkCommandBuffer cb, PackRuntime.Img src, PackRuntime.Img dst) {
		if (src == null || dst == null || src == dst) return;
		hazards.read(src, TRANSFER, TRANSFER_READ);
		hazards.write(dst, TRANSFER, TRANSFER_WRITE);
		hazards.flush(cb);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int aspect = src.isDepth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
			var region = VkImageCopy.calloc(1, stack);
			region.srcSubresource().aspectMask(aspect).layerCount(1);
			region.dstSubresource().aspectMask(aspect).layerCount(1);
			region.extent().set(Math.min(src.width, dst.width), Math.min(src.height, dst.height), Math.min(src.depth, dst.depth));
			vkCmdCopyImage(cb, src.image, VK_IMAGE_LAYOUT_GENERAL, dst.image, VK_IMAGE_LAYOUT_GENERAL, region);
		}
	}

	private String phaseLabel;

	public void close() {}
}
