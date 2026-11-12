package dev.emrion.shaders.mod.runtime;

import static dev.emrion.shaders.mod.vk.Barriers.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.uniforms.UniformSystem;
import dev.emrion.shaders.mod.vk.GpuTimer;
import dev.emrion.shaders.mod.vk.RawCommands;
import dev.emrion.shaders.mod.vk.RawPass;
import java.util.List;
import net.minecraft.client.Minecraft;
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
 * Runs a dimension's {@code once} and {@code frame} commands (FORMAT §7.4) on a {@link PackRuntime}. M2 split of the frame:
 * <ul>
 *   <li>{@link #frameStart} (GameRenderer.renderLevel HEAD): uniforms, then {@code once} and every command before the first
 *       {@code geometry} phase, in one command buffer;</li>
 *   <li>{@link #beforeHud} (before render3dHud clears MC's depth): MC's reverse-Z depth → depthtex0 as GL depth;</li>
 *   <li>{@link #afterLevel} (renderLevel TAIL): MC's colour → colortex0 (both sides), then the rest of the plan with the
 *       {@code geometry} phases skipped (MC rendered the world normally), in one command buffer.</li>
 * </ul>
 * Every pass declares what it reads and writes to {@link Hazards}, which emits the minimal barrier.
 */
public final class FrameExecutor {
	private static final boolean TIMINGS = Boolean.getBoolean("emrion.debug.timings");
	private static final long TIMINGS_INTERVAL_NS = Long.getLong("emrion.debug.timings.seconds", 5) * 1_000_000_000L;
	private static final long SHADER_STAGES = VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | FRAGMENT;

	private final PackRuntime rt;
	private final PackPipelines pp;
	private final Manifest.Dimension dim;
	private final UniformSystem uniforms;
	private final Hazards hazards = new Hazards();
	private final int firstGeometry;
	private final int[] dynamicOffsets = new int[2];
	private final GpuTimer timer;
	private final String[] labels;
	private final PackRuntime.Img mcColor = new PackRuntime.Img("minecraft main color", null);
	private boolean oncePending = true;
	private long lastLog = System.nanoTime();

	public FrameExecutor(PackRuntime rt) {
		this.rt = rt;
		this.pp = rt.pipelines;
		this.dim = rt.dim;
		this.uniforms = rt.uniforms;
		int g = 0;
		while (g < dim.frame().size() && !dim.frame().get(g).cmd().equals("geometry")) g++;
		firstGeometry = g;
		int marks = dim.frame().size() + dim.once().size() + 4;
		labels = new String[marks];
		timer = TIMINGS ? new GpuTimer(marks) : null;
	}

	// ---- hook entry points ---------------------------------------------------------------------------------------------

	public void frameStart(float partialTick) {
		uniforms.beginFrame(partialTick);
		rt.ring.nextFrame();
		var layout = rt.manifest.uniforms();
		long frameOffset = rt.ring.alloc(layout.size()), phaseOffset = rt.ring.alloc(layout.phase().size());
		uniforms.writeFrameBlock(MemoryUtil.memByteBuffer(rt.ring.address(frameOffset), layout.size()));
		uniforms.writePhaseBlock(MemoryUtil.memByteBuffer(rt.ring.address(phaseOffset), layout.phase().size()), UniformSystem.Phase.FULLSCREEN);
		dynamicOffsets[0] = (int) frameOffset;
		dynamicOffsets[1] = (int) phaseOffset;
		if (timer != null) {
			timer.beginFrame();
			logTimings();
		}

		VkCommandBuffer cb = begin();
		mark(cb, null);
		if (oncePending) {
			for (var c : dim.once()) run(cb, c);
			oncePending = false;
		}
		for (int i = 0; i < firstGeometry; i++) run(cb, dim.frame().get(i));
		RawCommands.submit(rt.ctx, cb);
	}

	public void beforeHud() {
		PackRuntime.Img depth = rt.image("depthtex0", "main");
		if (depth == null) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		long mcDepth = ((VulkanGpuTextureView) main.getDepthTextureView()).vkImageView();
		VkCommandBuffer cb = begin();
		hazards.write(depth, DEPTH, DEPTH_WRITE);
		hazards.flush(cb);
		RawPass.beginDepth(cb, depth.width, depth.height, depth.attach);
		helper(cb, pp.depthConvert, mcDepth);
		RawPass.end(cb);
		mark(cb, "MC world + import depth");
		RawCommands.submit(rt.ctx, cb);
	}

	public void afterLevel() {
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		mcColor.attach = mcColor.sampled = ((VulkanGpuTextureView) main.getColorTextureView()).vkImageView();
		mcColor.image = ((VulkanGpuTexture) main.getColorTexture()).vkImage();
		mcColor.width = main.width;
		mcColor.height = main.height;
		mcColor.vkFormat = VulkanConst.toVk(PackPipelines.SCREEN_FORMAT);
		VkCommandBuffer cb = begin();
		PackRuntime.Img[] colortex0 = rt.images.get("colortex0");
		if (colortex0 != null) {
			for (var img : colortex0) if (img != null) blit(cb, mcColor, img);
			mark(cb, "import color");
		}
		for (int i = firstGeometry; i < dim.frame().size(); i++) run(cb, dim.frame().get(i));
		RawCommands.submit(rt.ctx, cb);
	}

	private VkCommandBuffer begin() {
		hazards.reset();
		return RawCommands.begin(rt.ctx);
	}

	// ---- commands ------------------------------------------------------------------------------------------------------

	private void run(VkCommandBuffer cb, Manifest.Command c) {
		switch (c.cmd()) {
			case "geometry" -> { return; } // M3: MC renders the world normally
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

	private void mark(VkCommandBuffer cb, String label) {
		if (timer == null) return;
		if (label == null) marks = 0;
		labels[timer.mark(cb)] = label;
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
			if (labels[i] != null && !labels[i].startsWith("geometry")) sb.append("\n  ").append(String.format("%8.1f  %s", (t - prev) / 1000.0, labels[i]));
			prev = t;
		}
		sb.append(String.format("%n  %8.1f  total (frame start to end of plan, includes MC's world rendering in between)", prev / 1000.0));
		EmrionShadersClient.LOG.info(sb.toString());
	}

	public void close() {
		if (timer != null) timer.close();
	}
}
