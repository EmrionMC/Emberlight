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
import dev.emrion.shaders.mod.vk.GpuTimer;
import dev.emrion.shaders.mod.vk.RawCommands;
import dev.emrion.shaders.mod.vk.RawPass;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
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
 * Shadow phases are skipped until M4 (the plan's clears leave shadowtex at 1.0 = lit). Every raw pass declares what it reads
 * and writes to {@link Hazards}, which emits the minimal barrier.
 */
public final class FrameExecutor {
	private static final boolean TIMINGS = Boolean.getBoolean("emrion.debug.timings");
	private static final long TIMINGS_INTERVAL_NS = Long.getLong("emrion.debug.timings.seconds", 5) * 1_000_000_000L;
	private static final long SHADER_STAGES = VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | FRAGMENT;
	/** Iris WorldRenderingPhase ordinals have 24 entries (iris-spec §1.4). */
	private static final int STAGES = 24;
	/** EmrionPhase blocks per frame at most: FULLSCREEN plus every (WORLD/HAND, renderStage). */
	public static final int PHASE_BLOCKS = 1 + 2 * STAGES;

	private final PackRuntime rt;
	private final PackPipelines pp;
	private final Manifest.Dimension dim;
	private final UniformSystem uniforms;
	private final Hazards hazards = new Hazards();
	private final int[] dynamicOffsets = new int[2];
	private final GpuTimer timer;
	private final String[] labels;
	private final PackRuntime.Img mcColor = new PackRuntime.Img("minecraft main color", null);
	private boolean oncePending = true;
	private long lastLog = System.nanoTime();

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
		int marks = 2 * dim.frame().size() + dim.once().size() + 4;
		labels = new String[marks];
		timer = TIMINGS ? new GpuTimer(marks) : null;
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

	// ---- hook entry points ---------------------------------------------------------------------------------------------

	/** LevelRenderer.render HEAD: the level projection of this frame is known, no MC render pass is open. */
	public void levelStart(float partialTick) {
		uniforms.beginFrame(partialTick);
		rt.ring.nextFrame();
		for (int[] p : phaseOffsets) java.util.Arrays.fill(p, -1);
		var layout = rt.manifest.uniforms();
		long frameOffset = rt.ring.alloc(layout.size());
		uniforms.writeFrameBlock(MemoryUtil.memByteBuffer(rt.ring.address(frameOffset), layout.size()).order(java.nio.ByteOrder.nativeOrder()));
		dynamicOffsets[0] = GeometryPipelines.DYNAMIC_OFFSETS[0] = (int) frameOffset;
		dynamicOffsets[1] = phaseOffset(UniformSystem.Phase.FULLSCREEN, 0);
		var atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTexture();
		GeometryPipelines.atlasWidth = atlas.getWidth(0);
		GeometryPipelines.atlasHeight = atlas.getHeight(0);
		var gr = Minecraft.getInstance().gameRenderer;
		long nearest = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)).vkSampler();
		long linear = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)).vkSampler();
		GeometryPipelines.EXTRA_VIEWS[0] = rt.whiteView();
		GeometryPipelines.EXTRA_VIEWS[1] = ((VulkanGpuTextureView) gr.overlayTexture().getTextureView()).vkImageView();
		GeometryPipelines.EXTRA_VIEWS[2] = ((VulkanGpuTextureView) gr.lightmap()).vkImageView();
		GeometryPipelines.EXTRA_SAMPLERS[0] = GeometryPipelines.EXTRA_SAMPLERS[1] = nearest;
		GeometryPipelines.EXTRA_SAMPLERS[2] = linear;
		GeometryPipelines.defaultsBuffer = pp.defaults.handle;
		if (timer != null) {
			timer.beginFrame();
			logTimings();
			marks = 0;
		}

		VkCommandBuffer cb = begin();
		mark(cb, null);
		if (oncePending) {
			for (var c : dim.once()) run(cb, c);
			oncePending = false;
		}
		int i = 0;
		while (i < dim.frame().size() && !isWorldPhase(dim.frame().get(i))) run(cb, dim.frame().get(i++));
		cursor = i;
		RawCommands.submit(rt.ctx, cb);
	}

	private static boolean isWorldPhase(Manifest.Command c) {
		return c.cmd().equals("geometry") && !c.phase().startsWith("shadow");
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
		cursor = -1;
	}

	private void runRange(int from, int to) {
		VkCommandBuffer cb = null;
		for (int i = from; i < to; i++) {
			var c = dim.frame().get(i);
			if (c.cmd().equals("geometry")) continue;
			if (cb == null) cb = begin();
			run(cb, c);
		}
		if (cb != null) RawCommands.submit(rt.ctx, cb);
	}

	// ---- geometry phases -----------------------------------------------------------------------------------------------

	/**
	 * Opens the gbuffers render pass of a geometry command: every gbuffers target on the side the command's flip state reads
	 * (gbuffers write the read side, iris-spec §2.2), plus depthtex0. MC draws into it; the swap picks {@code ctx}'s pipelines.
	 * The caller binds MC's default uniforms if MC does not, closes the pass and calls {@link #endPhase}.
	 */
	public RenderPass beginPhase(Manifest.Command g, GbufferPipelines.Context ctx, UniformSystem.Phase phase, int stage) {
		markMc(null);
		var flip = rt.flipState(g.flipState());
		var d = RenderPassDescriptor.builder(() -> "emrion " + g.phase());
		for (String t : pp.gbuffers.targets) d.withColorAttachment(rt.read(t, flip).attachView);
		d.withDepthAttachment(rt.image("depthtex0", "main").attachView);
		RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(d.build());
		GeometryPipelines.set = rt.sets[g.flipState()];
		this.context = ctx;
		this.phase = phase;
		this.stage = -1;
		setStage(stage);
		phaseLabel = "geometry " + g.phase();
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
		markMc(phaseLabel);
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
			case "clear" -> clear(cb, c.targets());
			case "compute" -> compute(cb, c);
			case "fullscreen" -> fullscreen(cb, c);
			case "final" -> finalPass(cb, c);
			case "mipmaps" -> mipmaps(cb, rt.image(c.target(), c.side()));
			case "copy" -> copy(cb, rt.image(c.target(), c.from()), rt.image(c.target(), c.to()));
			case "copy_depth" -> copy(cb, rt.image(c.from(), "main"), rt.image(c.to(), "main"));
			default -> throw new IllegalStateException("unknown frame command " + c.cmd());
		}
		mark(cb, c.program() != null ? c.cmd() + " " + c.program() : c.cmd() + (c.target() != null ? " " + c.target() : c.to() != null ? " " + c.to() : ""));
	}

	private void clear(VkCommandBuffer cb, List<Manifest.ClearOp> ops) {
		for (var op : ops) {
			PackRuntime.Buf buf = rt.buffers.get(op.name());
			if (buf != null) hazards.write(buf, TRANSFER, TRANSFER_WRITE);
			for (String side : sides(op.side())) {
				var img = rt.image(op.name(), side);
				if (img != null) hazards.write(img, TRANSFER, TRANSFER_WRITE);
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

	// ---- GPU timings ---------------------------------------------------------------------------------------------------

	private int marks;
	private String phaseLabel;

	private void mark(VkCommandBuffer cb, String label) {
		if (timer == null) return;
		labels[timer.mark(cb)] = label;
		marks++;
	}

	/** Timestamp through MC's encoder (outside render passes), around the geometry phases MC records. */
	private void markMc(String label) {
		if (timer == null) return;
		labels[timer.mark()] = label;
		marks++;
	}

	private void logTimings() {
		if (System.nanoTime() - lastLog < TIMINGS_INTERVAL_NS || marks < 2) return;
		lastLog = System.nanoTime();
		StringBuilder sb = new StringBuilder("GPU pass times (us):");
		long prev = 0;
		for (int i = 1; i < marks; i++) {
			long t = timer.nanosSinceFirst(i);
			if (t < 0) return; // not available yet
			sb.append("\n  ").append(String.format("%8.1f  %s", (t - prev) / 1000.0, labels[i] == null ? "(MC, outside our passes)" : labels[i]));
			prev = t;
		}
		sb.append(String.format("%n  %8.1f  total (level start to end of plan)", prev / 1000.0));
		EmrionShadersClient.LOG.info(sb.toString());
	}

	public void close() {
		if (timer != null) timer.close();
	}
}
