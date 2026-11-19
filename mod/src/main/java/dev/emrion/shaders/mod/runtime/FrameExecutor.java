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
import dev.emrion.shaders.mod.pbr.PbrTextures;
import dev.emrion.shaders.mod.pipeline.GeometryPipelines;
import dev.emrion.shaders.mod.uniforms.UniformSystem;
import dev.emrion.shaders.mod.debug.FaultInjection;
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
 * Runs a dimension's {@code once} and {@code frame} commands on a {@link PackRuntime}, in plan order, split at the
 * {@code geometry} phases that MC's own draw code renders:
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
	/** A/B switches for benchmarking; both on by default. */
	private static final boolean DEFER_CLEARS = !"false".equals(System.getProperty("emrion.perf.deferClears")),
			COMPUTE_CLEARS = !"false".equals(System.getProperty("emrion.perf.computeClears"));
	private static final long SHADER_STAGES = VK_PIPELINE_STAGE_2_PRE_RASTERIZATION_SHADERS_BIT | FRAGMENT; // VS, TCS, TES, GS
	/** A RawPass colour attachment: loadOp LOAD reads it (COLOR_ATTACHMENT_READ), then the pass writes it (and blending reads it). */
	static final long LOADED_WRITE = COLOR_WRITE | COLOR_READ;
	/** Iris WorldRenderingPhase has 24 ordinals. */
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
	/** A pass that runs before the shadow pass ends reads the TLAS (see {@link #readsTlasBeforeShadowEnd}). */
	private final boolean tlasBeforeShadowEnd;
	/** Frame-start clears of raw images moved behind their last use in the frame (see {@link DeferredClears}). */
	private final DeferredClears deferred;
	private boolean firstFrame = true;
	/** The RT AO pass ran this frame (it runs once, before the first pass that samples emrion_rtao). */
	private boolean aoDone;

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
		tlasBeforeShadowEnd = readsTlasBeforeShadowEnd(dim);
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
		FaultInjection.maybeThrow("shadow");
		long t = Profiler.cpuStart(), raw = Profiler.rawSoFar();
		ShadowPass.render(this, level);
		Profiler.shadowEnd(t, raw);
		var rtScene = dev.emrion.shaders.mod.rt.RtScene.get();
		if (rtScene != null) {
			VkCommandBuffer cb = begin();
			rtScene.refreshEntities(cb);
			RawCommands.submit(rt.ctx, cb);
		}
	}

	/** True if this dimension's plan draws a shadow pass (Iris then turns off vanilla entity shadows). */
	public boolean hasShadowPass() {
		return hasShadowPass;
	}

	// ---- hook entry points ---------------------------------------------------------------------------------------------

	/** LevelRenderer.render HEAD: the level projection of this frame is known, no MC render pass is open. */
	public void levelStart(float partialTick) {
		FaultInjection.frame();
		FaultInjection.maybeThrow("levelStart");
		long t = Profiler.cpuStart();
		uniforms.beginFrame(partialTick);
		rt.ring.nextFrame();
		for (int[] p : phaseOffsets) java.util.Arrays.fill(p, -1);
		aoDone = false;
		dev.emrion.shaders.mod.pipeline.TerrainPrepass.newFrame();
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
		GeometryPipelines.EXTRA_SAMPLERS[3] = rt.pbrSampler(false);
		GeometryPipelines.EXTRA_SAMPLERS[4] = rt.pbrSampler(true);
		PbrTextures.onNewFrame();
		GeometryPipelines.defaultsBuffer = pp.defaults.handle;
		Profiler.cpuEnd(Profiler.UNIFORMS, t);

		t = Profiler.cpuStart();
		VkCommandBuffer cb = begin();
		Profiler.mark(cb, "MC: before the level");
		var rtScene = dev.emrion.shaders.mod.rt.RtScene.get();
		if (rtScene != null) {
			rtScene.buildTlas(cb, tlasBeforeShadowEnd || oncePending);
			Profiler.mark(cb, "RT: TLAS build");
		}
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
			if (!rt.skip[i]) run(cb, dim.frame().get(i));
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
		FaultInjection.maybeThrow("phase");
		runRange(cursor, j);
		cursor = j + 1;
		return dim.frame().get(j);
	}

	/** After the level's frame graph: composites, final (into MC's main colour target) and the end-of-frame copies. */
	public void finish() {
		if (cursor < 0) return;
		FaultInjection.maybeThrow("finish");
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
		rt.swapFlippedTargets();
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
			if (!rt.skip[i]) run(cb, c);
			Profiler.mark(cb, frameLabels[i]);
		}
		if (cb != null) RawCommands.submit(rt.ctx, cb);
		Profiler.cpuEnd(Profiler.RAW, t);
	}

	// ---- geometry phases -----------------------------------------------------------------------------------------------

	/**
	 * Opens the gbuffers render pass of a geometry command: every gbuffers target on the side the command's flip state reads
	 * (gbuffers write the read side, as Iris), plus depthtex0; in the SHADOW context the shadowcolor targets (main)
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
		groupFlip = flip;
		group = 0;
		GeometryHooks.attachmentGroups = !shadow && pp.gbuffers.groups.size() > 1;
		GeometryPipelines.set = rt.sets[g.flipState()];
		this.context = ctx;
		this.phase = phase;
		this.stage = -1;
		setStage(stage);
		phaseLabel = frameLabels[dim.frame().indexOf(g)];
		dev.emrion.shaders.mod.debug.TerrainStats.phase = phaseLabel;
		return pass;
	}

	private Manifest.FlipState groupFlip;
	private int group;

	/**
	 * Before {@code p} is set in the open gbuffers pass whose attachments come in groups: if {@code p} draws into another group,
	 * ends the rendering on {@code cb} and resumes it with that group's attachments (same depth, contents loaded). Returns the
	 * new attachment list for MC's pass, or null if nothing changed.
	 */
	public @Nullable List<RenderPassDescriptor.Attachment<java.util.Optional<Vector4fc>>> switchGroup(VkCommandBuffer cb,
			com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline p) {
		int g = pp.gbuffers.group(p);
		if (g < 0 || g == group) return null;
		group = g;
		var d = RenderPassDescriptor.builder(() -> "emrion group");
		for (String t : pp.gbuffers.groups.get(g)) d.withColorAttachment(rt.read(t, groupFlip).attachView);
		var views = d.build().colorAttachments();
		var depth = (VulkanGpuTextureView) rt.image("depthtex0", "main").attachView;
		RawPass.end(cb);
		// attachment writes of one rendering instance are not ordered against the next one's without a barrier
		memory(cb, COLOR_OUT | DEPTH, COLOR_WRITE | DEPTH_WRITE, COLOR_OUT | DEPTH,
				COLOR_READ | COLOR_WRITE | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | DEPTH_WRITE);
		RawPass.resume(cb, depth.getWidth(0), depth.getHeight(0), depth.vkImageView(),
				views.stream().mapToLong(a -> ((VulkanGpuTextureView) a.textureView()).vkImageView()).toArray());
		return views;
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
		GeometryHooks.attachmentGroups = false;
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
		if (rt.rtAo != null && !aoDone && c.program() != null && readsRtAo(dim.programs().get(c.program()), c.flipState())) {
			rt.rtAo.dispatch(cb, hazards, rt.image("depthtex0", "main"), rt.atlasView(), uniforms.gbufferProjection(), uniforms.gbufferModelView(),
					rt.upscaler != null && rt.upscaler.rayReconstruction());
			aoDone = true;
		}
		switch (c.cmd()) {
			case "geometry" -> { return; } // drawn by MC through the hooks (advanceTo / beginPhase)
			case "clear" -> clear(cb, firstFrame ? c.targets() : deferred.start().getOrDefault(c, c.targets()));
			case "compute" -> compute(cb, c);
			case "fullscreen" -> fullscreen(cb, c);
			case "final" -> finalPass(cb, c);
			case "mipmaps" -> mipmaps(cb, rt.image(c.target(), c.side()));
			case "copy" -> {
				// end-of-frame copies (no flipState) of swapped targets are replaced by the image swap
				if (c.flipState() != null || !rt.swapped.contains(c.target())) copy(cb, rt.image(c.target(), c.from()), rt.image(c.target(), c.to()));
			}
			case "copy_depth" -> copy(cb, rt.image(c.from(), "main"), rt.image(c.to(), "main"));
			default -> throw new IllegalStateException("unknown frame command " + c.cmd());
		}
	}

	/**
	 * Whether a frame command before the last shadow phase, or a shadow program, binds the TLAS (or the RT AO image, whose
	 * pass traces it). Otherwise nothing reads the levelStart TLAS before the shadow pass's entities rebuild it.
	 */
	static boolean readsTlasBeforeShadowEnd(Manifest.Dimension dim) {
		java.util.Set<Integer> tlas = new java.util.HashSet<>();
		for (var r : dim.resources()) if (r.kind().equals("acceleration_structure") || RtAoPass.BUILTIN.equals(r.builtin())) tlas.add(r.binding());
		java.util.function.Predicate<Manifest.Program> reads = p -> p != null && p.set1().stream().anyMatch(tlas::contains);
		int last = -1;
		for (int i = 0; i < dim.frame().size(); i++)
			if (dim.frame().get(i).cmd().equals("geometry") && dim.frame().get(i).phase() != null && dim.frame().get(i).phase().startsWith("shadow")) last = i;
		for (int i = 0; i < last; i++) if (dim.frame().get(i).program() != null && reads.test(dim.programs().get(dim.frame().get(i).program()))) return true;
		return dim.programs().values().stream().anyMatch(p -> "shadow".equals(p.kind()) && reads.test(p));
	}

	private boolean readsRtAo(Manifest.Program p, int flip) {
		var res = rt.bindingResource[flip];
		for (int b : p.set1()) if (b < res.length && res[b] == rt.rtAo.output) return true;
		return false;
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
						boolean is3d = img.target.dims() == 3; // the view type, not the depth: an [8, 8, 1] 3D image has a 3D view
						vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, is3d ? pp.clear3d : pp.clear2d);
						RawPass.pushImages(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pp.clearLayout, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, img.attach);
						vkCmdPushConstants(cb, pp.clearLayout, VK_SHADER_STAGE_ALL, 0,
								stack.ints((int) color[0], (int) color[1], (int) color[2], (int) color[3]));
						vkCmdDispatch(cb, ceilDiv(img.width, 8), ceilDiv(img.height, 8), is3d ? ceilDiv(img.depth, 4) : 1);
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

	/**
	 * Raw unsigned-integer 2D/3D images (the voxel volumes) are cleared by a compute pass, which raw-pass barriers do not wait on;
	 * 1D ones by transfer (no clear shader for that view type).
	 */
	private static boolean computeClear(PackRuntime.Img img) {
		return COMPUTE_CLEARS && dev.emrion.shaders.mod.vk.VulkanContext.get().storageWriteWithoutFormat && img.raw != null && img.target.format().endsWith("UI")
				&& img.target.dims() >= 2;
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
			vkCmdBindDescriptorSets(cb, bindPoint, pp.layout, 1, stack.longs(setOverride != 0 ? setOverride : rt.sets[flip]), stack.ints(dynamicOffsets));
		}
	}

	private void compute(VkCommandBuffer cb, Manifest.Command c) {
		var p = dim.programs().get(c.program());
		var rg = pp.raygen.get(c.program()); // the pass runs as a raygen shader (RtRaygen)
		int bind = rg != null ? org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR : VK_PIPELINE_BIND_POINT_COMPUTE;
		declareSet1(p, c.flipState(), rg != null ? dev.emrion.shaders.mod.vk.Barriers.RAYGEN : COMPUTE);
		hazards.flush(cb);
		vkCmdBindPipeline(cb, bind, pp.pipelines.get(c.program()));
		bindSet(cb, bind, c.flipState());
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
		var lim = rt.ctx.limits();
		// GL rejects bad counts (GL_INVALID_VALUE); on Vulkan they can hang the GPU, so skip like Iris does
		if (Integer.compareUnsigned(x, lim.maxComputeWorkGroupCount(0)) > 0 || Integer.compareUnsigned(y, lim.maxComputeWorkGroupCount(1)) > 0
				|| Integer.compareUnsigned(z, lim.maxComputeWorkGroupCount(2)) > 0) return; // negative = huge unsigned
		if (rg != null) {
			if ((long) x * local[0] * y * local[1] * z * local[2] > 1 << 30) return; // (maxRayDispatchInvocationCount is at least 2^30)
			for (int i = 0; i < 3; i++) if (local[i] > lim.maxComputeWorkGroupSize(i)) return; // (each launch dimension: count x size limits)
			dev.emrion.shaders.mod.rt.RtRaygen.trace(cb, rg, x * local[0], y * local[1], z * local[2]);
		} else vkCmdDispatch(cb, x, y, z);
	}

	private static int ceilDiv(int a, int b) {
		return Math.max(1, (a + b - 1) / b);
	}

	private void fullscreen(VkCommandBuffer cb, Manifest.Command c) {
		var p = dim.programs().get(c.program());
		declareSet1(p, c.flipState(), SHADER_STAGES);
		long[] views = new long[c.writes().size()];
		PackRuntime.Img first = null;
		int areaW = Integer.MAX_VALUE, areaH = Integer.MAX_VALUE;
		for (var w : c.writes()) {
			var img = rt.image(w.target(), w.side());
			hazards.write(img, COLOR_OUT, LOADED_WRITE);
			views[w.location()] = img.attach;
			if (first == null) first = img;
			areaW = Math.min(areaW, img.width); // the render area must fit every attachment (GL: their intersection)
			areaH = Math.min(areaH, img.height);
		}
		hazards.flush(cb);
		draw(cb, p, c.program(), c.flipState(), first.width, first.height, areaW, areaH, views);
	}

	private void finalPass(VkCommandBuffer cb, Manifest.Command c) {
		if (rt.upscaler != null) upscale(cb);
		if (dev.emrion.shaders.mod.framegen.FrameGenConfig.mode != dev.emrion.shaders.mod.framegen.FrameGenConfig.Mode.OFF) { // inputs for frame generation
			var depth = rt.image("depthtex0", "main");
			dev.emrion.shaders.mod.framegen.FrameGen.capture(rt.width, rt.height, depth.image, depth.sampled, depth.vkFormat,
					rt.upscaler != null ? rt.upscaler.mv : null, uniforms.gbufferModelView());
		}
		if (c.program() == null) { // no final program: Iris copies colortex0's read side to the screen
			blit(cb, rt.upscaler != null ? rt.upscaled : rt.read("colortex0", rt.flipState(c.flipState())), mcColor);
			return;
		}
		var p = dim.programs().get(c.program());
		declareSet1(p, c.flipState(), SHADER_STAGES);
		hazards.write(mcColor, COLOR_OUT, LOADED_WRITE);
		if (rt.upscaler == null) {
			hazards.flush(cb);
			draw(cb, p, c.program(), c.flipState(), mcColor.width, mcColor.height, mcColor.width, mcColor.height, mcColor.attach);
			return;
		}
		hazards.read(rt.upscaled, FRAGMENT, SAMPLED_READ);
		hazards.flush(cb);
		// final at window size: its own set (the cut target -> upscaled image) and frame block (window-size viewWidth/viewHeight)
		int frame = dynamicOffsets[0];
		dynamicOffsets[0] = finalFrameBlock(frame);
		setOverride = rt.finalSet;
		try {
			draw(cb, p, c.program(), c.flipState(), mcColor.width, mcColor.height, mcColor.width, mcColor.height, mcColor.attach);
		} finally {
			setOverride = 0;
			dynamicOffsets[0] = frame;
		}
	}

	/** Motion vectors and FSR/DLSS from the cut target (render size) to rt.upscaled (window size). */
	private void upscale(VkCommandBuffer cb) {
		var depth = rt.image("depthtex0", "main");
		var behind = rt.image("depthtex1", "main"); // null when the pack never reads depthtex1
		if (behind == null) behind = depth;
		hazards.read(rt.upscaleInput, COMPUTE, SAMPLED_READ);
		hazards.read(depth, COMPUTE, SAMPLED_READ);
		if (behind != depth) hazards.read(behind, COMPUTE, SAMPLED_READ);
		if (rt.masks != null) hazards.read(rt.masks, COMPUTE, SAMPLED_READ);
		// a native pack's mirror image distance; not for Ray Reconstruction, which wants the surface's motion (it estimates the reflections' own)
		var distance = rt.upscaler.rayReconstruction() ? null : rt.image(dev.emrion.shaders.converter.nativefmt.NativeManifest.MOTION_IMAGE, "main");
		if (distance != null) hazards.read(distance, COMPUTE, SAMPLED_READ);
		var rrNormal = rt.rrFlip == null ? null : rt.read("colortex4", rt.rrFlip);
		var rrMaterial = rt.rrFlip == null ? null : rt.read("colortex6", rt.rrFlip);
		if (rrNormal != null) {
			hazards.read(rrNormal, COMPUTE, SAMPLED_READ);
			hazards.read(rrMaterial, COMPUTE, SAMPLED_READ);
		}
		if (rt.rrNative != null) for (var g : rt.rrNative) hazards.read(g, COMPUTE, SAMPLED_READ);
		hazards.write(rt.upscaled, COMPUTE, STORAGE_WRITE);
		hazards.flush(cb);
		rt.upscaler.dispatch(cb, rt.upscaleInput.image, rt.upscaleInput.sampled, rt.upscaleInput.vkFormat, depth.image, depth.sampled, behind.sampled,
				distance != null ? distance.sampled : 0, uniforms.gbufferModelView(), rt.masks == null ? null
						: new dev.emrion.shaders.mod.upscale.Upscaler.Masks(rt.masks.image, rt.masks.vkFormat, rt.masks.sampled, rt.compositionMask),
				!rt.upscaler.rayReconstruction() ? null : rt.rrNative != null
						? new dev.emrion.shaders.mod.upscale.Upscaler.RrInputs(rt.atlasView(), rt.rrNative[2].sampled, rt.rrNative[0].sampled, false,
								uniforms.gbufferProjection(), uniforms.gbufferModelView(), rt.rrNative[1].sampled)
						: new dev.emrion.shaders.mod.upscale.Upscaler.RrInputs(rt.atlasView(),
						rrNormal != null ? rrNormal.sampled : 0, rrMaterial != null ? rrMaterial.sampled : 0, rrNormal != null,
						uniforms.gbufferProjection(), uniforms.gbufferModelView(), 0));
		Profiler.mark(cb, "upscale");
	}

	/** A copy of this frame's uniform block with viewWidth/viewHeight/iris_ScreenSize at the window size; returns its ring offset. */
	private int finalFrameBlock(int frameOffset) {
		var layout = rt.manifest.uniforms();
		long o = rt.ring.alloc(layout.size());
		MemoryUtil.memCopy(rt.ring.address(frameOffset), rt.ring.address(o), layout.size());
		var b = MemoryUtil.memByteBuffer(rt.ring.address(o), layout.size()).order(java.nio.ByteOrder.nativeOrder());
		for (var m : layout.members()) {
			switch (m.name()) {
				case "viewWidth" -> b.putFloat(m.offset(), rt.displayWidth);
				case "viewHeight" -> b.putFloat(m.offset(), rt.displayHeight);
				case "iris_ScreenSize" -> b.putFloat(m.offset(), rt.displayWidth).putFloat(m.offset() + 4, rt.displayHeight);
				default -> {}
			}
		}
		return (int) o;
	}

	private long setOverride;

	/** {@code w × h}: the first output's size (viewport); {@code areaW × areaH}: the render area, inside every output. */
	private void draw(VkCommandBuffer cb, Manifest.Program p, String program, int flip, int w, int h, int areaW, int areaH, long... views) {
		RawPass.begin(cb, areaW, areaH, views);
		float[] off = p.viewportOffset();
		if (p.viewportScale() != 1f && p.viewportScale() > 0f || off != null || areaW != w || areaH != h) {
			int[] v = viewport(w, h, areaW, areaH, p.viewportScale() > 0f ? p.viewportScale() : 1f, off);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				vkCmdSetViewport(cb, 0, VkViewport.calloc(1, stack).x(v[0]).y(v[1]).width(v[2]).height(v[3]).maxDepth(1f));
				var scissor = VkRect2D.calloc(1, stack);
				scissor.offset().set(v[4], v[5]);
				scissor.extent().set(v[6], v[7]);
				vkCmdSetScissor(cb, 0, scissor);
			}
		}
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pp.pipelines.get(program));
		bindSet(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, flip);
		vkCmdDraw(cb, 6, 1, 0, 0); // a quad as in Iris: packs reshape it from its 0..1 UVs (Shrimple's bloom tiles)
		RawPass.end(cb);
	}

	/**
	 * {viewport x, y, width, height, scissor x, y, width, height}. Iris CompositeRenderer: glViewport(size·offset, size·scale) of
	 * the first output; our images keep GL's row order, so y is not mirrored. The scissor stays inside the render area.
	 */
	static int[] viewport(int w, int h, int areaW, int areaH, float scale, float @org.jspecify.annotations.Nullable [] off) {
		int sw = Math.max(1, (int) (w * scale)), sh = Math.max(1, (int) (h * scale));
		int x = off == null ? 0 : (int) (w * off[0]), y = off == null ? 0 : (int) (h * off[1]);
		int sx = Math.min(Math.max(0, x), areaW), sy = Math.min(Math.max(0, y), areaH);
		return new int[] {x, y, sw, sh, sx, sy, Math.min(sw, areaW - sx), Math.min(sh, areaH - sy)};
	}

	/** Texel copy through a fullscreen pass (converts formats, e.g. MC's RGBA8 → colortex0's R11G11B10F). */
	private void blit(VkCommandBuffer cb, PackRuntime.Img src, PackRuntime.Img dst) {
		Long pipeline = pp.blit.get(dst.vkFormat);
		if (pipeline == null) throw new IllegalStateException("no blit pipeline for format " + dst.vkFormat);
		hazards.read(src, FRAGMENT, SAMPLED_READ);
		hazards.write(dst, COLOR_OUT, LOADED_WRITE);
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
		Long areaPipeline = img.isDepth || BLIT_MIPS ? null : pp.mip.get(img.vkFormat);
		if (areaPipeline != null && img.tex != null) {
			areaMipmaps(cb, img, areaPipeline);
			return;
		}
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

	/** -Demrion.perf.blitMips=true: the old vkCmdBlitImage chain (A/B timing; wrong on odd sizes). */
	private static final boolean BLIT_MIPS = Boolean.getBoolean("emrion.perf.blitMips");

	/** Mip chain with glGenerateMipmap's box filter over each texel's full footprint (odd sizes included), level i-1 -> i. */
	private void areaMipmaps(VkCommandBuffer cb, PackRuntime.Img img, long pipeline) {
		hazards.write(img, COLOR_OUT | FRAGMENT, LOADED_WRITE | SAMPLED_READ);
		hazards.flush(cb);
		for (int level = 1; level < img.mips; level++) {
			if (level > 1) memory(cb, COLOR_OUT, COLOR_WRITE, FRAGMENT, SAMPLED_READ); // previous level written
			int w = Math.max(1, img.width >> level), h = Math.max(1, img.height >> level);
			RawPass.begin(cb, w, h, img.levelView(level));
			vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
			RawPass.pushImages(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pp.helperLayout, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, rt.nearestSampler(),
					img.levelView(level - 1));
			vkCmdDraw(cb, 3, 1, 0, 0);
			RawPass.end(cb);
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
