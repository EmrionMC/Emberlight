package dev.emrion.shaders.mod.upscale;

import static dev.emrion.shaders.mod.vk.Barriers.*;
import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.framegen.FrameGen;
import dev.emrion.shaders.mod.framegen.FrameGenConfig;
import dev.emrion.shaders.mod.mixin.LevelRendererAccessor;
import dev.emrion.shaders.mod.mixin.RenderTargetAccessor;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.uniforms.CapturedProjection;
import dev.emrion.shaders.mod.vk.ComputePipelines;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.RawCommands;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.ShaderModules;
import dev.emrion.shaders.mod.vk.VulkanContext;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Upscaling and frame generation without a shader pack (docs/UPSCALING.md §10). The level renders into MC's main target with
 * render-size textures swapped in and a jittered projection; before the hand it is upscaled into the display-size textures,
 * so hand, screen effects, post effects and GUI draw at display size as usual. Every entry point is a no-op unless this
 * frame has no pack and an upscaler or frame generation is on.
 */
public final class VanillaUpscale {
	private VanillaUpscale() {}

	/** Clip z of MC's reverse-Z [0, 1] projection to GL forward [-1, 1]: z' = w - 2z (window depth 1 - d). */
	private static final Matrix4f FORWARD = new Matrix4f().m22(-2f).m32(1f);
	private static final Matrix4f glProjection = new Matrix4f(), modelView = new Matrix4f(), tmp = new Matrix4f(), tmpHand = new Matrix4f();

	/** This frame renders vanilla with upscaling or frame generation; with an upscaler (render target swapped in). */
	private static boolean frame, upscaling, swapped;
	private static @Nullable TextureTarget target;
	private static @Nullable Upscaler upscaler;
	private static int generation = -1, failedGeneration = -1;
	private static volatile @Nullable String failure;

	private static @Nullable RawImage depth, dummyMotion;
	private static @Nullable RawBuffer depthBuffer;
	private static long setLayout, layout, pipeline;

	/** Why the upscaler could not start without a pack (shown on the Upscaling page), or null. */
	public static @Nullable String failure() {
		return failure;
	}

	/** True while the last frame was upscaled without a pack. */
	public static boolean upscaling() {
		return frame && upscaling;
	}

	/** GameRenderer.render HEAD: a level frame that threw between swap and restore must not leave the main target swapped. */
	public static void frameStart() {
		if (swapped) swapBack();
	}

	/**
	 * renderLevel, right after {@code projection.mul(bob)}: decides this frame and jitters MC's level projection (a translation
	 * in NDC on the left, so bob, hurt tilt and nausea, multiplied on the right, are not jittered).
	 */
	public static void jitter(Matrix4f projection) {
		var mc = Minecraft.getInstance();
		var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		boolean fg = FrameGenConfig.effective() != FrameGenConfig.Mode.OFF;
		frame = EmrionShadersClient.active() && !PackManager.running() && !camera.isPanoramicMode && (UpscaleConfig.effective() != UpscaleConfig.Mode.OFF || fg);
		if (!frame) {
			release();
			return;
		}
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		upscaling = UpscaleConfig.effective() != UpscaleConfig.Mode.OFF && ensure(main.width, main.height);
		if (!upscaling) releaseUpscaler();
		Upscaler u = upscaler;
		if (upscaling && u != null) Upscaling.setSizes(u.renderW, u.renderH, main.width, main.height, true);
		else Upscaling.setSizes(main.width, main.height, main.width, main.height, false);
		glProjection.set(FORWARD).mul(camera.projectionMatrix);
		if (Upscaling.jitter(tmp.set(glProjection), tmpHand.identity())) // also sets Upscaling.unjittered (frame generation reads it)
			projection.mulLocal(tmp.translation(Upscaling.ndcJitterX(), Upscaling.ndcJitterY(), 0f));
	}

	/** LevelRenderer.render HEAD, after the pack's frame start: swap the render-size textures into MC's main target. */
	public static void beginLevel() {
		if (PackManager.running()) { // jitter() does not run while a pack renders: free the render-size target and upscaler here
			frame = false;
			release();
			return;
		}
		if (!frame) return;
		if (!upscaling || target == null) return;
		RenderTarget outline = ((LevelRendererAccessor) Minecraft.getInstance().levelRenderer).emrion$entityOutlineTarget();
		if (outline.width != target.width || outline.height != target.height) outline.resize(target.width, target.height);
		swap();
	}

	/** renderLevel, before render3dHud: forward depth, upscale into the display-size main target, frame generation inputs. */
	public static void endLevel() {
		if (!frame) return;
		var mc = Minecraft.getInstance();
		var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		int rw = main.width, rh = main.height;
		VulkanContext ctx = VulkanContext.get();
		ensureDepth(ctx, rw, rh);
		// what the level really used = forward(P) · (P⁻¹ · unjittered level projection · view): bob and nausea become view terms
		tmp.translation(-Upscaling.ndcJitterX(), -Upscaling.ndcJitterY(), 0f).mul(CapturedProjection.LEVEL);
		camera.projectionMatrix.invert(modelView).mul(tmp).mul(camera.viewRotationMatrix);
		VkCommandBuffer cb = RawCommands.begin(ctx);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanCommandEncoder.memoryBarrier(cb, stack);
		}
		Upscaler u = upscaler;
		boolean upscaled = swapped && u != null;
		// paused frame generation does not read the forward depth (bypass only changes at the blit, after FrameGen.beforeGui)
		if (upscaled || !FrameGen.bypassed()) depthPass(cb, view(main.getDepthTextureView()), rw, rh, 0, dummyMotion.view);
		if (upscaled) {
			long colorImage = ((VulkanGpuTexture) main.getColorTexture()).vkImage();
			u.dispatch(cb, colorImage, view(main.getColorTextureView()), VK_FORMAT_R8G8B8A8_UNORM, depth.image, depth.view, depth.view, 0, modelView, null, null); // no Ray Reconstruction without a pack (no RT)
			swapBack();
			blit(cb, u.output, ((VulkanGpuTexture) main.getColorTexture()).vkImage(), main.width, main.height);
			Profiler.mark(cb, "vanilla upscale");
		}
		RawCommands.submit(ctx, cb);
		FrameGen.capture(rw, rh, depth.image, depth.view, VK_FORMAT_D32_SFLOAT, upscaled ? u.mv : null, modelView);
	}

	/** renderLevel TAIL (after the hand): hand pixels get hand depth and no motion, so frame generation does not drag the hand. */
	public static void afterHand() {
		if (!frame || !FrameGen.wantsHudless() || depth == null || FrameGen.bypassed()) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		VulkanContext ctx = VulkanContext.get();
		VkCommandBuffer cb = RawCommands.begin(ctx);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanCommandEncoder.memoryBarrier(cb, stack);
		}
		Upscaler u = upscaler;
		boolean mv = upscaling && u != null;
		depthPass(cb, view(main.getDepthTextureView()), depth.width, depth.height, mv ? 1 : 2, mv ? u.mv.image.view : dummyMotion.view);
		RawCommands.submit(ctx, cb);
	}

	private static long view(com.mojang.renderpearl.api.textures.GpuTextureView v) {
		return ((VulkanGpuTextureView) v).vkImageView();
	}

	/** vanilla_depth.comp into the buffer, then the buffer into the forward-depth image. */
	private static void depthPass(VkCommandBuffer cb, long srcView, int w, int h, int mode, long motionView) {
		long nearest = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)).vkSampler();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var writes = VkWriteDescriptorSet.calloc(3, stack);
			Upscaler.image(writes.get(0), stack, 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nearest, srcView);
			writes.get(1).sType$Default().dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
					.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(depthBuffer.handle).offset(0).range(VK_WHOLE_SIZE));
			Upscaler.image(writes.get(2), stack, 2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, motionView);
			vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, writes);
			vkCmdPushConstants(cb, layout, VK_SHADER_STAGE_ALL, 0, stack.ints(w, h, mode));
			vkCmdDispatch(cb, (w + 7) / 8, (h + 7) / 8, 1);
			memory(cb, COMPUTE, STORAGE_WRITE, TRANSFER, TRANSFER_READ);
			var region = VkBufferImageCopy.calloc(1, stack);
			region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1);
			region.imageExtent().set(w, h, 1);
			vkCmdCopyBufferToImage(cb, depthBuffer.handle, depth.image, VK_IMAGE_LAYOUT_GENERAL, region);
			memory(cb, TRANSFER | COMPUTE, TRANSFER_WRITE | STORAGE_WRITE, COMPUTE | FRAGMENT | TRANSFER, SAMPLED_READ | STORAGE_READ | TRANSFER_READ);
		}
	}

	private static void blit(VkCommandBuffer cb, RawImage src, long dst, int w, int h) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var region = VkImageBlit.calloc(1, stack);
			region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.srcOffsets(1).set(src.width, src.height, 1);
			region.dstOffsets(1).set(w, h, 1);
			vkCmdBlitImage(cb, src.image, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_GENERAL, region, VK_FILTER_NEAREST);
		}
	}

	/** The render target and upscaler for this display size and the current settings; false (and {@link #failure}) when it cannot run. */
	private static boolean ensure(int dw, int dh) {
		Upscaler u = upscaler;
		int gen = Upscaling.generation();
		if (u != null && generation == gen && u.displayW == dw && u.displayH == dh) return true;
		releaseUpscaler();
		if (failedGeneration == gen) return false;
		int[] r = Upscaling.renderSize(dw, dh);
		VulkanContext ctx = VulkanContext.get();
		for (int attempt = 0; attempt < 2 && upscaler == null; attempt++) {
			UpscaleConfig.Mode mode = UpscaleConfig.effective();
			try {
				upscaler = Upscaler.create(ctx, r[0], r[1], dw, dh, false);
			} catch (RuntimeException e) {
				EmrionShadersClient.LOG.error("Upscaler without a pack: {} failed", mode.label, e);
				if (UpscaleConfig.effective() == mode) { // DLSS that just failed falls back to FSR: one more try
					failure = mode.label + ": " + e.getMessage();
					failedGeneration = gen;
					return false;
				}
			}
		}
		if (upscaler == null) return false;
		failure = null;
		generation = gen;
		target = new TextureTarget("Emberlight render size", r[0], r[1], GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		EmrionShadersClient.LOG.info("Upscaling without a pack: {} {}x{} -> {}x{}", UpscaleConfig.effective().label, r[0], r[1], dw, dh);
		return true;
	}

	private static void ensureDepth(VulkanContext ctx, int w, int h) {
		if (pipeline == 0) {
			int cs = VK_SHADER_STAGE_COMPUTE_BIT;
			setLayout = new Descriptors.LayoutBuilder().add(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, cs).add(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, cs)
					.add(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, cs).build(ctx, true);
			layout = Descriptors.pipelineLayout(ctx, 12, setLayout);
			long module = ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/vanilla_depth.comp.spv"));
			pipeline = ComputePipelines.create(ctx, module, layout, ctx.pipelineCache().handle);
			ShaderModules.destroy(ctx, module);
			dummyMotion = new RawImage(ctx, VK_FORMAT_R16G16_SFLOAT, 1, 1, 1, 1, VK_IMAGE_USAGE_STORAGE_BIT, "vanilla dummy motion");
		}
		if (depth != null && depth.width == w && depth.height == h) return;
		releaseDepth();
		depth = new RawImage(ctx, VK_FORMAT_D32_SFLOAT, w, h, 1, 1, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
				"vanilla forward depth");
		depthBuffer = new RawBuffer(ctx, (long) w * h * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, false, "vanilla forward depth");
	}

	/** Exchanges the textures and size of MC's main target with the render-size target. */
	private static void swap() {
		RenderTarget a = Minecraft.getInstance().gameRenderer.mainRenderTarget(), b = target;
		var color = a.getColorTexture();
		var colorView = a.getColorTextureView();
		var depthTex = a.getDepthTexture();
		var depthView = a.getDepthTextureView();
		int w = a.width, h = a.height;
		var aa = (RenderTargetAccessor) a;
		var bb = (RenderTargetAccessor) b;
		aa.emrion$setColorTexture(b.getColorTexture());
		aa.emrion$setColorTextureView(b.getColorTextureView());
		aa.emrion$setDepthTexture(b.getDepthTexture());
		aa.emrion$setDepthTextureView(b.getDepthTextureView());
		a.width = b.width;
		a.height = b.height;
		bb.emrion$setColorTexture(color);
		bb.emrion$setColorTextureView(colorView);
		bb.emrion$setDepthTexture(depthTex);
		bb.emrion$setDepthTextureView(depthView);
		b.width = w;
		b.height = h;
		swapped = !swapped;
	}

	private static void swapBack() {
		if (swapped && target != null) swap();
		swapped = false;
	}

	private static void releaseUpscaler() {
		swapBack();
		if (upscaler != null) upscaler.destroyLater();
		upscaler = null;
		if (target != null) {
			target.destroyBuffers();
			target = null;
			var mc = Minecraft.getInstance();
			RenderTarget main = mc.gameRenderer.mainRenderTarget();
			RenderTarget outline = ((LevelRendererAccessor) mc.levelRenderer).emrion$entityOutlineTarget();
			if (outline.width != main.width || outline.height != main.height) outline.resize(main.width, main.height);
		}
		generation = -1;
	}

	private static void releaseDepth() {
		if (depth != null) depth.destroyLater();
		if (depthBuffer != null) depthBuffer.destroyLater();
		depth = null;
		depthBuffer = null;
	}

	private static void release() {
		if (upscaler == null && target == null && depth == null) return;
		releaseUpscaler();
		releaseDepth();
	}

	/** Client stopping, before NGX shuts down. */
	public static void shutdown() {
		release();
		if (dummyMotion != null) dummyMotion.destroyLater();
		dummyMotion = null;
		if (pipeline == 0) return;
		VulkanContext ctx = VulkanContext.get();
		long p = pipeline, l = layout, s = setLayout;
		ctx.destroyLater(() -> {
			vkDestroyPipeline(ctx.vk, p, null);
			vkDestroyPipelineLayout(ctx.vk, l, null);
			vkDestroyDescriptorSetLayout(ctx.vk, s, null);
		});
		pipeline = layout = setLayout = 0;
	}
}
