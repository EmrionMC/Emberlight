package dev.emrion.shaders.mod.framegen;

import static org.lwjgl.vulkan.VK10.*;

import dev.emrion.shaders.mod.vk.FfxPass;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.RawImage;
import java.nio.ByteBuffer;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

/**
 * Java port of the host logic of AMD FidelityFX {@code ffx_frameinterpolation.cpp} (SDK v1.1.4, MIT, Copyright (C) 2024
 * Advanced Micro Devices, Inc.), in the interpolation-only setup of {@code ffx_fsr3.cpp}.
 */
final class FrameInterpolation {
	private static final int CB_SIZE = 176;
	private final FfxPasses f;
	final int renderW, renderH, displayW, displayH;
	private final FfxPass reconstructAndDilate, setup, reconstructPrevDepth, gameMvField, ofMvField, disocclusion, interpolation,
			inpaintingPyramid, inpainting, gameVectorFieldPyramid;
	private final RawImage reconstructedDepthInterpolated, gameMvX, gameMvY, pyramid, ofMvX, ofMvY, previousSource, inpaintingMask,
			disocclusionMask, defaultDistortion, dilatedDepth, dilatedMotion, reconstructedPrevDepth;
	private final RawBuffer counters;
	private final long[] pyramidMipViews = new long[13];
	private long dispatchCount;

	FrameInterpolation(FfxPasses f, int rw, int rh, int dw, int dh, int backbufferFormat) {
		this.f = f;
		renderW = rw;
		renderH = rh;
		displayW = dw;
		displayH = dh;
		reconstructAndDilate = f.pass("ffx_frameinterpolation_reconstruct_and_dilate_pass");
		setup = f.pass("ffx_frameinterpolation_setup_pass");
		reconstructPrevDepth = f.pass("ffx_frameinterpolation_reconstruct_previous_depth_pass");
		gameMvField = f.pass("ffx_frameinterpolation_game_motion_vector_field_pass");
		ofMvField = f.pass("ffx_frameinterpolation_optical_flow_vector_field_pass");
		disocclusion = f.pass("ffx_frameinterpolation_disocclusion_mask_pass");
		interpolation = f.pass("ffx_frameinterpolation_pass");
		inpaintingPyramid = f.pass("ffx_frameinterpolation_compute_inpainting_pyramid_pass");
		inpainting = f.pass("ffx_frameinterpolation_inpainting_pass");
		gameVectorFieldPyramid = f.pass("ffx_frameinterpolation_compute_game_vector_field_inpainting_pyramid_pass");

		reconstructedDepthInterpolated = f.image(VK_FORMAT_R32_UINT, rw, rh, 1, "fi reconstructed depth interpolated frame");
		gameMvX = f.image(VK_FORMAT_R32_UINT, rw, rh, 1, "fi game motion vector field x");
		gameMvY = f.image(VK_FORMAT_R32_UINT, rw, rh, 1, "fi game motion vector field y");
		int pw = Math.max(1, dw / 2), ph = Math.max(1, dh / 2);
		int mips = 32 - Integer.numberOfLeadingZeros(Math.max(pw, ph)); // mipCount 0 in the SDK = the full chain
		pyramid = f.image(VK_FORMAT_R16G16B16A16_SFLOAT, pw, ph, mips, "fi inpainting pyramid");
		for (int i = 0; i < pyramidMipViews.length; i++) pyramidMipViews[i] = f.mipView(pyramid, Math.min(i, mips - 1));
		ofMvX = f.image(VK_FORMAT_R32_UINT, rw, rh, 1, "fi optical flow motion vector field x");
		ofMvY = f.image(VK_FORMAT_R32_UINT, rw, rh, 1, "fi optical flow motion vector field y");
		previousSource = f.image(backbufferFormat, dw, dh, 1, "fi previous interpolation source");
		inpaintingMask = f.image(VK_FORMAT_R8_UNORM, dw, dh, 1, "fi inpainting mask");
		disocclusionMask = f.image(VK_FORMAT_R8G8_UNORM, rw, rh, 1, "fi disocclusion mask");
		defaultDistortion = f.image(VK_FORMAT_R8G8_UNORM, 1, 1, 1, "fi default distortion field");
		ByteBuffer zero = org.lwjgl.system.MemoryUtil.memCalloc(2);
		f.upload(defaultDistortion, zero);
		org.lwjgl.system.MemoryUtil.memFree(zero);
		// shared resources of an interpolation-only context (ffxFrameInterpolationGetSharedResourceDescriptions)
		dilatedDepth = f.image(VK_FORMAT_R32_SFLOAT, rw, rh, 1, "fi dilated depth");
		dilatedMotion = f.image(VK_FORMAT_R16G16_SFLOAT, rw, rh, 1, "fi dilated motion vectors");
		reconstructedPrevDepth = f.image(VK_FORMAT_R32_UINT, rw, rh, 1, "fi reconstructed previous nearest depth");
		counters = f.buffer(8, "fi counters");
	}

	/** Per-frame camera and input description (FfxFrameInterpolationPrepareDescription + the dispatch's camera fields). */
	record Frame(long depthView, long motionView, float jitterX, float jitterY, Matrix4fc projection, float deltaMs, boolean reset) {}

	/** FrameInterpolationConstants, std140 = the C layout. */
	private void constants(Frame fr, boolean reset) {
		Matrix4fc p = fr.projection();
		float tanHalfFovY = 1f / p.m11(), aspect = (float) renderW / renderH;
		float fovY = (float) (2 * Math.atan(tanHalfFovY));
		// GL window depth: near/far of the projection (m22 = -(f+n)/(f-n), m32 = -2fn/(f-n)); an infinite far plane gives f = inf
		float near = p.m32() / (p.m22() - 1f), far = p.m22() == -1f ? Float.POSITIVE_INFINITY : p.m32() / (p.m22() + 1f);
		float horizontal = (float) (Math.atan(Math.tan(fovY / 2) * aspect) * 2);
		ByteBuffer c = f.constants("cbFI", CB_SIZE);
		c.putInt(0, renderW).putInt(4, renderH).putInt(8, displayW).putInt(12, displayH);
		c.putFloat(16, 1f / displayW).putFloat(20, 1f / displayH).putFloat(24, near).putFloat(28, Float.isInfinite(far) ? 1e8f : far);
		c.putInt(32, displayW).putInt(36, displayH).putInt(40, 0).putInt(44, reset ? 1 : 0);
		// device depth -> view distance d = c1 / (depth - c0), as in the upscaler (also right for an infinite far plane)
		c.putFloat(48, (1f - p.m22()) * 0.5f).putFloat(52, p.m32() * 0.5f).putFloat(56, tanHalfFovY * aspect).putFloat(60, tanHalfFovY);
		c.putFloat(64, fr.deltaMs()).putInt(68, 1).putInt(72, 1).putInt(76, 1); // HUD-less attached, 1x1 default distortion field
		c.putFloat(80, 1f / displayW).putFloat(84, 1f / displayH).putInt(88, OpticalFlow.BLOCK).putInt(92, 0);
		c.putInt(96, renderW).putInt(100, renderH).putInt(104, 0).putInt(108, 0);
		c.putInt(112, 0).putInt(116, 0).putInt(120, displayW).putInt(124, displayH);
		c.putFloat(128, 0f).putFloat(132, 1f).putFloat(136, 1f).putInt(140, 0); // debug bar colour; sRGB transfer function
		c.putFloat(144, 0f).putFloat(148, 1f).putFloat(152, (float) Math.tan(horizontal * 0.5f)).putInt(156, 0);
		c.putFloat(160, fr.jitterX()).putFloat(164, fr.jitterY()).putFloat(168, 1f).putFloat(172, 1f); // motion vectors already in UV units
	}

	/** ffxFrameInterpolationPrepare: dilated depth and motion vectors and the reconstructed previous depth of this frame. */
	void prepare(VkCommandBuffer cb, Frame fr) {
		constants(fr, false);
		f.bind.put("r_input_depth", fr.depthView());
		f.bind.put("r_input_motion_vectors", fr.motionView());
		f.bind.put("rw_dilated_depth", dilatedDepth.view);
		f.bind.put("rw_dilated_motion_vectors", dilatedMotion.view);
		f.bind.put("rw_reconstructed_depth_previous_frame", reconstructedPrevDepth.view);
		FfxPasses.clearUint(cb, reconstructedPrevDepth, Float.floatToRawIntBits(1f)); // forward depth: far = 1.0
		FfxPasses.barrier(cb);
		f.run(cb, reconstructAndDilate, (renderW + 7) / 8, (renderH + 7) / 8, 1);
	}

	/** Interpolates between the previous HUD-less image and {@code hudless}, with the backbuffer's GUI composed on top. */
	void dispatch(VkCommandBuffer cb, Frame fr, RawImage hudless, long backbufferView, RawImage output, OpticalFlow of) {
		boolean reset = dispatchCount == 0 || fr.reset();
		dispatchCount++;
		constants(fr, reset);
		var b = f.bind;
		b.put("r_present_backbuffer", backbufferView);
		b.put("r_current_interpolation_source", hudless.view);
		b.put("r_previous_interpolation_source", previousSource.view);
		b.put("r_dilated_depth", dilatedDepth.view);
		b.put("r_dilated_motion_vectors", dilatedMotion.view);
		b.put("r_reconstructed_depth_previous_frame", reconstructedPrevDepth.view);
		b.put("rw_reconstructed_depth_previous_frame", reconstructedPrevDepth.view);
		b.put("r_reconstructed_depth_interpolated_frame", reconstructedDepthInterpolated.view);
		b.put("rw_reconstructed_depth_interpolated_frame", reconstructedDepthInterpolated.view);
		b.put("rw_output", output.view);
		b.put("r_output", output.view);
		b.put("r_optical_flow", of.flow.view);
		b.put("r_optical_flow_scd", of.scd.view);
		b.put("r_input_distortion_field", defaultDistortion.view);
		b.put("r_disocclusion_mask", disocclusionMask.view);
		b.put("rw_disocclusion_mask", disocclusionMask.view);
		b.put("r_game_motion_vector_field_x", gameMvX.view);
		b.put("r_game_motion_vector_field_y", gameMvY.view);
		b.put("rw_game_motion_vector_field_x", gameMvX.view);
		b.put("rw_game_motion_vector_field_y", gameMvY.view);
		b.put("r_optical_flow_motion_vector_field_x", ofMvX.view);
		b.put("r_optical_flow_motion_vector_field_y", ofMvY.view);
		b.put("rw_optical_flow_motion_vector_field_x", ofMvX.view);
		b.put("rw_optical_flow_motion_vector_field_y", ofMvY.view);
		b.put("r_inpainting_mask", inpaintingMask.view);
		b.put("rw_inpainting_mask", inpaintingMask.view);
		b.put("r_inpainting_pyramid", pyramid.view);
		for (int i = 0; i < pyramidMipViews.length; i++) b.put("rw_inpainting_pyramid" + i, pyramidMipViews[i]);
		f.bufferBind.put("FrameInterpolationCounters", counters.handle);
		f.bufferBind.put("FrameInterpolationRWCounters", counters.handle);

		int rx = (renderW + 7) / 8, ry = (renderH + 7) / 8, dx = (displayW + 7) / 8, dy = (displayH + 7) / 8;
		int ofx = (int) (displayW / (float) OpticalFlow.BLOCK + 7) / 8, ofy = (int) (displayH / (float) OpticalFlow.BLOCK + 7) / 8;
		f.run(cb, setup, rx, ry, 1);
		if (!reset) {
			FfxPasses.clearUint(cb, reconstructedDepthInterpolated, Float.floatToRawIntBits(1f));
			FfxPasses.barrier(cb);
			f.run(cb, reconstructPrevDepth, rx, ry, 1);
			f.run(cb, gameMvField, rx, ry, 1);
			pyramidConstants(renderW, renderH);
			int[] spd = FfxPasses.spdSetup(renderW, renderH, -1);
			f.run(cb, gameVectorFieldPyramid, spd[0], spd[1], 1);
			f.run(cb, ofMvField, ofx, ofy, 1);
			f.run(cb, disocclusion, rx, ry, 1);
		}
		f.run(cb, interpolation, dx, dy, 1);
		pyramidConstants(displayW, displayH);
		int[] spd = FfxPasses.spdSetup(displayW, displayH, -1);
		f.run(cb, inpaintingPyramid, spd[0], spd[1], 1);
		f.run(cb, inpainting, dx, dy, 1);
		try (MemoryStack stack = MemoryStack.stackPush()) { // the interpolation source of the next frame
			var region = VkImageCopy.calloc(1, stack);
			region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.extent().set(displayW, displayH, 1);
			vkCmdCopyImage(cb, hudless.image, VK_IMAGE_LAYOUT_GENERAL, previousSource.image, VK_IMAGE_LAYOUT_GENERAL, region);
		}
		FfxPasses.barrier(cb);
	}

	private void pyramidConstants(int w, int h) {
		int[] spd = FfxPasses.spdSetup(w, h, -1);
		f.constants("cbInpaintingPyramid", 16).putInt(0, spd[3]).putInt(4, spd[2]).putInt(8, 0).putInt(12, 0);
	}
}
