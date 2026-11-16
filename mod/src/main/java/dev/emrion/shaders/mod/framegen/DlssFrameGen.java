package dev.emrion.shaders.mod.framegen;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.upscale.Dlss;
import dev.emrion.shaders.mod.vk.RawCommands;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;

/** NVIDIA DLSS Frame Generation through the NGX shim; presenting and pacing are ours ({@link Presenter}). */
final class DlssFrameGen implements Generator {
	private static final ValueLayout.OfLong PTR = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
	private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT;
	/** -Demrion.framegen.dlssMvScale=uv: hand the motion vectors as UV offsets (scale 1) instead of render pixels. */
	private static final boolean MV_UV = "uv".equals(System.getProperty("emrion.framegen.dlssMvScale"));

	private static volatile @Nullable String reason = initialReason();
	private static MethodHandle available, create, evaluate;

	private final VulkanContext ctx;
	private final long handle;
	private final Arena arena = Arena.ofShared();
	private final MemorySegment images = arena.allocate(ValueLayout.JAVA_LONG, 10), formats = arena.allocate(ValueLayout.JAVA_INT, 5),
			matrices = arena.allocate(ValueLayout.JAVA_FLOAT, 64), camera = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
	private final Matrix4f tmp = new Matrix4f();

	private static @Nullable String initialReason() {
		String r = Dlss.unavailable();
		if (r != null) return r;
		return Dlss.has(Dlss.FG_MODEL) ? null : "NVIDIA's DLSS Frame Generation file is missing (download it, then restart)";
	}

	/** Why DLSS FG cannot run here, or null when it may (NGX init and feature creation can still fail). */
	static @Nullable String unavailable() {
		String r = reason;
		return r != null ? r : Dlss.unavailable();
	}

	static void fail(String why) {
		reason = why;
	}

	private static synchronized void bind(VulkanContext ctx) {
		String why = Dlss.init(ctx);
		if (why != null) throw new IllegalStateException(why);
		if (available != null) return;
		available = Dlss.function("emrion_ngx_dlssg_available", FunctionDescriptor.of(INT, ValueLayout.ADDRESS))
				.orElseThrow(() -> new IllegalStateException("the DLSS shim has no frame generation (rerun mod/native/build-dlss.sh)"));
		create = Dlss.function("emrion_ngx_dlssg_create", FunctionDescriptor.of(INT, PTR, INT, INT, INT, INT, INT, ValueLayout.ADDRESS)).orElseThrow();
		evaluate = Dlss.function("emrion_ngx_dlssg_evaluate", FunctionDescriptor.of(INT, PTR, PTR, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT,
				INT, INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, FLOAT, FLOAT, FLOAT, FLOAT, INT)).orElseThrow();
	}

	DlssFrameGen(VulkanContext ctx, int rw, int rh, int dw, int dh) {
		this.ctx = ctx;
		try {
			bind(ctx);
			MemorySegment needs = arena.allocate(INT);
			int ok = (int) available.invokeExact(needs);
			if (ok != 1) throw new IllegalStateException(needs.get(INT, 0) != 0 ? "the driver is too old for DLSS FG"
					: "DLSS Frame Generation not supported on this GPU (needs RTX 40 or newer)");
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			VkCommandBuffer cb = RawCommands.begin(ctx);
			int r = Dlss.onBigStack(() -> (int) create.invokeExact(cb.address(), dw, dh, rw, rh, VK_FORMAT_R8G8B8A8_UNORM, out));
			RawCommands.submit(ctx, cb);
			if (Dlss.failedResult(r)) throw new IllegalStateException("DLSS FG feature creation failed (0x" + Integer.toHexString(r) + ")");
			handle = out.get(ValueLayout.ADDRESS, 0).address();
			Dlss.track(handle);
		} catch (RuntimeException e) {
			arena.close();
			throw e;
		} catch (Throwable t) {
			arena.close();
			throw new IllegalStateException("DLSS FG feature creation failed: " + t, t);
		}
	}

	@Override
	public void dispatch(VkCommandBuffer cb, Inputs in, RawImage hudless, long backbufferImage, long backbufferView, RawImage output) {
		set(0, backbufferImage, backbufferView, VK_FORMAT_R8G8B8A8_UNORM);
		set(1, hudless.image, hudless.view, hudless.format);
		set(2, in.depthImage(), in.depthView(), in.depthFormat());
		set(3, in.motionImage(), in.motionView(), org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SFLOAT);
		set(4, output.image, output.view, output.format);
		// JOML stores column-major for column vectors = NGX's row-major for row vectors (post-multiplication): copy as is
		putMatrix(0, tmp.set(in.projection()));
		putMatrix(16, tmp.set(in.projection()).invert());
		putMatrix(32, tmp.set(in.clipToPrevClip()));
		putMatrix(48, tmp.set(in.clipToPrevClip()).invert());
		var cam = Minecraft.getInstance().gameRenderer.mainCamera();
		var pos = cam.position();
		var up = cam.upVector();
		var fwd = cam.forwardVector();
		var left = cam.leftVector();
		float[] c = {(float) pos.x, (float) pos.y, (float) pos.z, up.x(), up.y(), up.z(), -left.x(), -left.y(), -left.z(), fwd.x(), fwd.y(), fwd.z(),
				in.projection().m32() / (in.projection().m22() - 1f), in.projection().m32() / (in.projection().m22() + 1f),
				(float) (2 * Math.atan(1 / in.projection().m11())), (float) in.renderW() / in.renderH()};
		for (int i = 0; i < c.length; i++) camera.setAtIndex(ValueLayout.JAVA_FLOAT, i, c[i]);
		float sx = MV_UV ? 1f : in.renderW(), sy = MV_UV ? 1f : in.renderH();
		int r;
		try {
			r = (int) evaluate.invokeExact(cb.address(), handle, images, formats, in.renderW(), in.renderH(), in.displayW(), in.displayH(), matrices,
					camera, in.jitterX(), in.jitterY(), sx, sy, in.reset() ? 1 : 0);
		} catch (Throwable t) {
			throw new IllegalStateException("DLSS FG evaluate failed: " + t, t);
		}
		if (Dlss.failedResult(r)) throw new IllegalStateException("DLSS FG evaluate failed (0x" + Integer.toHexString(r) + ")");
		Profiler.mark(cb, "DLSS FG");
		if (!BLEND_UI) compose(cb, hudless.view, backbufferView, output);
	}

	/** -Demrion.framegen.dlssUi=blend: keep DLSS-G's own GUI handling (it blends GUI that changes between two frames). */
	private static final boolean BLEND_UI = "blend".equals(System.getProperty("emrion.framegen.dlssUi"));
	private long uiSetLayout, uiLayout, uiPipeline;

	/** fg_ui_compose.comp: the current frame's GUI over the interpolated image. */
	private void compose(VkCommandBuffer cb, long hudless, long backbuffer, RawImage output) {
		if (uiPipeline == 0) {
			int cs = org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT, st = org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
			uiSetLayout = new dev.emrion.shaders.mod.vk.Descriptors.LayoutBuilder().add(0, st, cs)
					.add(1, org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, cs).add(2, st, cs).build(ctx, true);
			uiLayout = dev.emrion.shaders.mod.vk.Descriptors.pipelineLayout(ctx, 0, uiSetLayout);
			long module = dev.emrion.shaders.mod.vk.ShaderModules.create(ctx, dev.emrion.shaders.mod.vk.ShaderModules.resource("/assets/emberlight/spv/fg_ui_compose.comp.spv"));
			uiPipeline = dev.emrion.shaders.mod.vk.ComputePipelines.create(ctx, module, uiLayout, ctx.pipelineCache().handle);
			dev.emrion.shaders.mod.vk.ShaderModules.destroy(ctx, module);
		}
		FfxPasses.barrier(cb);
		try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
			var writes = org.lwjgl.vulkan.VkWriteDescriptorSet.calloc(3, stack);
			long[] views = {hudless, backbuffer, output.view};
			for (int i = 0; i < 3; i++) writes.get(i).sType$Default().dstBinding(i)
					.descriptorType(i == 1 ? org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE : org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
					.descriptorCount(1).pImageInfo(org.lwjgl.vulkan.VkDescriptorImageInfo.calloc(1, stack).imageView(views[i])
							.imageLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL));
			org.lwjgl.vulkan.VK10.vkCmdBindPipeline(cb, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE, uiPipeline);
			org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE, uiLayout, 0, writes);
		}
		org.lwjgl.vulkan.VK10.vkCmdDispatch(cb, (output.width + 7) / 8, (output.height + 7) / 8, 1);
		FfxPasses.barrier(cb);
	}

	/** Slot {@code i} of the shim's image / view / format arrays. */
	private void set(int i, long image, long view, int format) {
		images.setAtIndex(ValueLayout.JAVA_LONG, 2 * i, image);
		images.setAtIndex(ValueLayout.JAVA_LONG, 2 * i + 1, view);
		formats.setAtIndex(ValueLayout.JAVA_INT, i, format);
	}

	private void putMatrix(int offset, Matrix4f m) {
		for (int col = 0; col < 4; col++)
			for (int row = 0; row < 4; row++) matrices.setAtIndex(ValueLayout.JAVA_FLOAT, offset + col * 4 + row, m.get(col, row));
	}

	@Override
	public void destroyLater() {
		long h = handle, p = uiPipeline, l = uiLayout, sl = uiSetLayout;
		ctx.destroyLater(() -> {
			Dlss.releaseFeature(h);
			arena.close();
			if (p != 0) {
				org.lwjgl.vulkan.VK10.vkDestroyPipeline(ctx.vk, p, null);
				org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout(ctx.vk, l, null);
				org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout(ctx.vk, sl, null);
			}
		});
	}
}
