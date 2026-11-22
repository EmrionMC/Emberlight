package dev.emrion.shaders.mod.upscale;

import static dev.emrion.shaders.mod.vk.Barriers.*;
import static org.lwjgl.vulkan.VK10.*;

import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.vk.FfxPass;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.UboRing;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * AMD FidelityFX FSR 3.1 upscaler: Java port of the host logic of {@code ffx_fsr3upscaler.cpp} (FidelityFX SDK v1.1.4, MIT,
 * Copyright (C) 2024 Advanced Micro Devices, Inc.). Bindings are resolved by name through SPIR-V reflection, as the SDK does.
 */
final class Fsr3Upscaler extends Upscaler {
	private static final String SPV = "/assets/emberlight/spv/fsr3/";
	private static final int USAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
	private static final int MAX_QUEUED_FRAMES = 16;

	private FfxPass prepareInputs, lumaPyramid, shadingChangePyramid, shadingChange, prepareReactivity, lumaInstability, accumulate,
			accumulateSharpen, rcas;
	private long pointSampler, linearSampler;
	private UboRing ubo;
	// internal resources (ffx_fsr3upscaler.cpp fsr3upscalerCreate)
	private final RawImage[] accumulation = new RawImage[2], luma = new RawImage[2], upscaled = new RawImage[2], lumaHistory = new RawImage[2];
	private RawImage intermediate, shadingChangeImg, newLocks, spdMips, farthestDepthMip1, spdAtomic, dilatedReactive, lanczosLut,
			defaultReactivity, defaultExposure, frameInfo, dilatedDepth, dilatedMotion, reconstructedPrevDepth;
	private final long[] spdMipViews = new long[6];
	private final List<RawImage> all = new ArrayList<>();
	private boolean first = true;
	private int resourceFrameIndex;
	private float jitterPhaseCount, frameIndex;
	private final float[] jitter = new float[2];
	private int prevRenderW, prevRenderH, prevDisplayW, prevDisplayH;

	Fsr3Upscaler(VulkanContext ctx, int rw, int rh, int dw, int dh) {
		super(ctx, rw, rh, dw, dh);
		try {
			init(rw, rh, dw, dh);
		} catch (RuntimeException | Error failed) { // e.g. out of GPU memory half-way: release what was created
			destroyLater();
			throw failed;
		}
	}

	private void init(int rw, int rh, int dw, int dh) {
		prepareInputs = pass("prepare_inputs");
		lumaPyramid = pass("luma_pyramid");
		shadingChangePyramid = pass("shading_change_pyramid");
		shadingChange = pass("shading_change");
		prepareReactivity = pass("prepare_reactivity");
		lumaInstability = pass("luma_instability");
		accumulate = pass("accumulate");
		accumulateSharpen = pass("accumulate_sharpen");
		rcas = pass("rcas");
		pointSampler = sampler(VK_FILTER_NEAREST);
		linearSampler = sampler(VK_FILTER_LINEAR);
		ubo = new UboRing(ctx, 3 * 256);

		int hw = rw / 2, hh = rh / 2;
		for (int i = 0; i < 2; i++) {
			accumulation[i] = img(VK_FORMAT_R8_UNORM, rw, rh, 1, "fsr accumulation " + i);
			luma[i] = img(VK_FORMAT_R16_SFLOAT, rw, rh, 1, "fsr luma " + i);
			upscaled[i] = img(VK_FORMAT_R16G16B16A16_SFLOAT, dw, dh, 1, "fsr internal upscaled " + i);
			lumaHistory[i] = img(VK_FORMAT_R16G16B16A16_SFLOAT, rw, rh, 1, "fsr luma history " + i);
		}
		intermediate = img(VK_FORMAT_R16_SFLOAT, rw, rh, 1, "fsr intermediate fp16");
		shadingChangeImg = img(VK_FORMAT_R8_UNORM, hw, hh, 1, "fsr shading change");
		newLocks = img(VK_FORMAT_R8_UNORM, dw, dh, 1, "fsr new locks");
		int mips = 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(hw, hh))); // full chain
		spdMips = img(VK_FORMAT_R16G16_SFLOAT, hw, hh, mips, "fsr spd mips");
		for (int i = 0; i < 6; i++) spdMipViews[i] = mipView(spdMips, Math.min(i, mips - 1));
		farthestDepthMip1 = img(VK_FORMAT_R16_SFLOAT, hw, hh, 1, "fsr farthest depth mip1");
		spdAtomic = img(VK_FORMAT_R32_UINT, 1, 1, 1, "fsr spd atomic");
		dilatedReactive = img(VK_FORMAT_R8G8B8A8_UNORM, rw, rh, 1, "fsr dilated reactive masks");
		lanczosLut = img(VK_FORMAT_R16_SNORM, 128, 1, 1, "fsr lanczos lut");
		defaultReactivity = img(VK_FORMAT_R8_UNORM, 1, 1, 1, "fsr default reactivity");
		defaultExposure = img(VK_FORMAT_R32G32_SFLOAT, 1, 1, 1, "fsr default exposure");
		frameInfo = img(VK_FORMAT_R32G32B32A32_SFLOAT, 1, 1, 1, "fsr frame info");
		dilatedDepth = img(VK_FORMAT_R32_SFLOAT, rw, rh, 1, "fsr dilated depth");
		dilatedMotion = img(VK_FORMAT_R16G16_SFLOAT, rw, rh, 1, "fsr dilated motion vectors");
		reconstructedPrevDepth = img(VK_FORMAT_R32_UINT, rw, rh, 1, "fsr reconstructed previous nearest depth");

		ByteBuffer lut = MemoryUtil.memAlloc(256);
		for (int i = 0; i < 128; i++) lut.putShort((short) Math.round(lanczos2(2f * i / 127f) * 32767f));
		upload(lanczosLut, lut.flip());
		MemoryUtil.memFree(lut);
		ByteBuffer zero = MemoryUtil.memCalloc(8); // reactivity 0; exposure (0, 0) = "use 1.0" in Exposure()
		upload(defaultReactivity, zero.limit(1));
		upload(defaultExposure, zero.clear()); // upload() consumed the buffer
		MemoryUtil.memFree(zero);
	}

	private FfxPass pass(String name) {
		return FfxPass.get(ctx, SPV, name, "FSR");
	}

	private RawImage img(int format, int w, int h, int mips, String label) {
		RawImage i = new RawImage(ctx, format, Math.max(1, w), Math.max(1, h), 1, mips, USAGE, label);
		all.add(i);
		return i;
	}

	private long mipView(RawImage img, int level) {
		return FfxPass.mipView(ctx, img, level, "fsr spd");
	}

	private long sampler(int filter) {
		return FfxPass.sampler(ctx, filter, "fsr");
	}

	private void upload(RawImage img, ByteBuffer data) {
		FfxPass.upload(ctx, img, data, "fsr");
	}

	private static float lanczos2(float x) {
		if (Math.abs(x) < 1e-6f) return 1f;
		double px = Math.PI * x;
		return (float) (Math.sin(px) / px * (Math.sin(0.5 * px) / (0.5 * px)));
	}

	@Override
	protected void evaluate(VkCommandBuffer cb, long colorImage, long colorView, int colorFormat, long depthImage, long depthView, boolean reset) {
		boolean odd = (resourceFrameIndex & 1) != 0;
		RawImage accumSrv = accumulation[odd ? 1 : 0], accumUav = accumulation[odd ? 0 : 1];
		RawImage upSrv = upscaled[odd ? 1 : 0], upUav = upscaled[odd ? 0 : 1];
		RawImage histSrv = lumaHistory[odd ? 1 : 0], histUav = lumaHistory[odd ? 0 : 1];
		RawImage curLuma = luma[odd ? 1 : 0], prevLuma = luma[odd ? 0 : 1];
		boolean resetAccumulation = reset || first;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (first) for (RawImage i : new RawImage[] {accumulation[0], accumulation[1], luma[0], luma[1]}) clear(cb, i, 0f);
			if (resetAccumulation) {
				if (!first) clear(cb, accumSrv, 0f); // first: cleared just above (a second clear would race it)
				FfxPass.clearFloat(cb, frameInfo, -1f, 1f, 0f, 0f);
			}
			FfxPass.clearUint(cb, reconstructedPrevDepth, Float.floatToRawIntBits(1f)); // forward depth: "far" is 1.0 (float bits in a uint)
			FfxPass.clearUint(cb, spdAtomic, 0);
			clear(cb, spdMips, 0f);
			memory(cb, TRANSFER, TRANSFER_WRITE, COMPUTE, SAMPLED_READ | STORAGE_READ | STORAGE_WRITE);
			first = false;

			// constants (Fsr3UpscalerConstants, std140 = the C layout)
			Matrix4fc p = projection();
			jitter[0] = Upscaling.jitterX();
			jitter[1] = Upscaling.jitterY();
			float prevJx = lastJitter[0], prevJy = lastJitter[1];
			int phases = Math.max(1, (int) (8.0 * Math.pow((double) displayW / renderW, 2.0)));
			if (resetAccumulation || jitterPhaseCount == 0) jitterPhaseCount = phases;
			else if (phases > jitterPhaseCount) jitterPhaseCount++;
			else if (phases < jitterPhaseCount) jitterPhaseCount--;
			frameIndex = resetAccumulation ? 0 : frameIndex + 1;
			float aspect = (float) renderW / renderH, tanHalfFovY = 1f / p.m11();
			float tanHalfFovX = tanHalfFovY * aspect;

			ubo.nextFrame(); // first, not after the passes: a throw below must not leave the region full for every later frame
			long cbOff = ubo.alloc(160);
			ByteBuffer c = MemoryUtil.memByteBuffer(ubo.address(cbOff), 160).order(java.nio.ByteOrder.nativeOrder());
			c.putInt(0, renderW).putInt(4, renderH).putInt(8, prevRenderW == 0 ? renderW : prevRenderW).putInt(12, prevRenderH == 0 ? renderH : prevRenderH);
			c.putInt(16, displayW).putInt(20, displayH).putInt(24, prevDisplayW == 0 ? displayW : prevDisplayW).putInt(28, prevDisplayH == 0 ? displayH : prevDisplayH);
			c.putInt(32, renderW).putInt(36, renderH).putInt(40, displayW).putInt(44, displayH);
			// device depth (GL window depth) -> view distance: d = (1 - m22)/2 + (m32/2) / -z  (also right for an infinite far plane)
			c.putFloat(48, (1f - p.m22()) * 0.5f).putFloat(52, p.m32() * 0.5f).putFloat(56, tanHalfFovX).putFloat(60, tanHalfFovY);
			c.putFloat(64, jitter[0]).putFloat(68, jitter[1]).putFloat(72, prevJx).putFloat(76, prevJy);
			c.putFloat(80, 1f).putFloat(84, 1f); // our motion vectors are already in UV units (scale / render size = 1)
			c.putFloat(88, (float) renderW / displayW).putFloat(92, (float) renderH / displayH);
			c.putFloat(96, 0f).putFloat(100, 0f); // jitter cancellation: off (unjittered motion vectors)
			c.putFloat(104, tanHalfFovX).putFloat(108, jitterPhaseCount);
			c.putFloat(112, Math.clamp(deltaMs / 1000f, 0f, 1f)).putFloat(116, 1f).putFloat(120, 1f).putFloat(124, frameIndex);
			c.putFloat(128, 1f).putFloat(132, 1f).putFloat(136, 1f).putFloat(140, 1f / 3f).putFloat(144, -1f / 3f);

			int[] spd = FfxPass.spdSetup(renderW, renderH, -1);
			long spdOff = ubo.alloc(32);
			MemoryUtil.memByteBuffer(ubo.address(spdOff), 32).order(java.nio.ByteOrder.nativeOrder())
					.putInt(0, spd[3]).putInt(4, spd[2]).putInt(8, 0).putInt(12, 0).putInt(16, renderW).putInt(20, renderH);

			float stops = -2f * UpscaleConfig.sharpness + 2f, s = (float) Math.pow(2.0, -stops);
			short h = Float.floatToFloat16(s);
			long rcasOff = ubo.alloc(16);
			MemoryUtil.memByteBuffer(ubo.address(rcasOff), 16).order(java.nio.ByteOrder.nativeOrder())
					.putInt(0, Float.floatToRawIntBits(s)).putInt(4, (h & 0xFFFF) | (h & 0xFFFF) << 16).putInt(8, 0).putInt(12, 0);

			Map<String, Long> srv = new HashMap<>(), uav = new HashMap<>(), cbs = new HashMap<>();
			srv.put("r_input_color_jittered", colorView);
			srv.put("r_input_motion_vectors", motion.view);
			srv.put("r_input_depth", depthView);
			srv.put("r_input_exposure", defaultExposure.view);
			srv.put("r_frame_info", frameInfo.view);
			srv.put("r_reactive_mask", masks != null ? masks.reactiveView() : defaultReactivity.view);
			srv.put("r_transparency_and_composition_mask", masks != null ? masks.compositionView() : defaultReactivity.view);
			srv.put("r_reconstructed_previous_nearest_depth", reconstructedPrevDepth.view);
			srv.put("r_dilated_motion_vectors", dilatedMotion.view);
			srv.put("r_dilated_depth", dilatedDepth.view);
			srv.put("r_internal_upscaled_color", upSrv.view);
			srv.put("r_accumulation", accumSrv.view);
			srv.put("r_luma_history", histSrv.view);
			srv.put("r_rcas_input", upUav.view);
			srv.put("r_lanczos_lut", lanczosLut.view);
			srv.put("r_spd_mips", spdMips.view);
			srv.put("r_dilated_reactive_masks", dilatedReactive.view);
			srv.put("r_new_locks", newLocks.view);
			srv.put("r_farthest_depth", intermediate.view);
			srv.put("r_farthest_depth_mip1", farthestDepthMip1.view);
			srv.put("r_shading_change", shadingChangeImg.view);
			srv.put("r_current_luma", curLuma.view);
			srv.put("r_previous_luma", prevLuma.view);
			srv.put("r_luma_instability", intermediate.view);
			uav.put("rw_reconstructed_previous_nearest_depth", reconstructedPrevDepth.view);
			uav.put("rw_dilated_motion_vectors", dilatedMotion.view);
			uav.put("rw_dilated_depth", dilatedDepth.view);
			uav.put("rw_internal_upscaled_color", upUav.view);
			uav.put("rw_accumulation", accumUav.view);
			uav.put("rw_luma_history", histUav.view);
			uav.put("rw_upscaled_output", output.view);
			uav.put("rw_dilated_reactive_masks", dilatedReactive.view);
			uav.put("rw_frame_info", frameInfo.view);
			uav.put("rw_spd_global_atomic", spdAtomic.view);
			uav.put("rw_new_locks", newLocks.view);
			uav.put("rw_shading_change", shadingChangeImg.view);
			uav.put("rw_farthest_depth", intermediate.view);
			uav.put("rw_farthest_depth_mip1", farthestDepthMip1.view);
			uav.put("rw_current_luma", curLuma.view);
			uav.put("rw_luma_instability", intermediate.view);
			for (int i = 0; i < 6; i++) uav.put("rw_spd_mip" + i, spdMipViews[i]);
			cbs.put("cbFSR3Upscaler", cbOff);
			cbs.put("cbSPD", spdOff);
			cbs.put("cbRCAS", rcasOff);

			int srcX = (renderW + 7) / 8, srcY = (renderH + 7) / 8, dstX = (displayW + 7) / 8, dstY = (displayH + 7) / 8;
			int scX = ((int) (renderW * 0.5f) + 7) / 8, scY = ((int) (renderH * 0.5f) + 7) / 8;
			boolean sharpen = UpscaleConfig.sharpness > 0f;
			run(cb, stack, prepareInputs, srv, uav, cbs, srcX, srcY);
			run(cb, stack, lumaPyramid, srv, uav, cbs, spd[0], spd[1]);
			run(cb, stack, shadingChangePyramid, srv, uav, cbs, spd[0], spd[1]);
			run(cb, stack, shadingChange, srv, uav, cbs, scX, scY);
			run(cb, stack, prepareReactivity, srv, uav, cbs, srcX, srcY);
			run(cb, stack, lumaInstability, srv, uav, cbs, srcX, srcY);
			run(cb, stack, sharpen ? accumulateSharpen : accumulate, srv, uav, cbs, dstX, dstY);
			if (sharpen) run(cb, stack, rcas, srv, uav, cbs, (displayW + 15) / 16, (displayH + 15) / 16);
		}
		lastJitter[0] = jitter[0];
		lastJitter[1] = jitter[1];
		prevRenderW = renderW;
		prevRenderH = renderH;
		prevDisplayW = displayW;
		prevDisplayH = displayH;
		resourceFrameIndex = (resourceFrameIndex + 1) % MAX_QUEUED_FRAMES;
	}

	private final float[] lastJitter = new float[2];

	private void run(VkCommandBuffer cb, MemoryStack stack, FfxPass p, Map<String, Long> srv, Map<String, Long> uav, Map<String, Long> cbs, int x, int y) {
		var writes = VkWriteDescriptorSet.calloc(p.bindings.size(), stack);
		for (FfxPass.Binding b : p.bindings) {
			var w = writes.get().sType$Default().dstBinding(b.binding()).descriptorType(b.type()).descriptorCount(1);
			switch (b.type()) {
				case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER -> {
					Long off = cbs.entrySet().stream().filter(e -> b.name().toLowerCase(java.util.Locale.ROOT).startsWith(e.getKey().toLowerCase(java.util.Locale.ROOT))).map(Map.Entry::getValue).findFirst()
							.orElseThrow(() -> new IllegalStateException("FSR " + p.name + ": unknown constant buffer " + b.name()));
					w.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(ubo.buffer.handle).offset(off).range(ubo.range(off)));
				}
				case VK_DESCRIPTOR_TYPE_SAMPLER -> w.pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
						.sampler(b.name().contains("Linear") ? linearSampler : pointSampler));
				default -> {
					Long view = (b.type() == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE ? uav : srv).get(b.name());
					if (view == null) throw new IllegalStateException("FSR " + p.name + ": no resource for " + b.name());
					w.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
				}
			}
		}
		writes.flip();
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.pipeline);
		KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.layout, 0, writes);
		vkCmdDispatch(cb, Math.max(1, x), Math.max(1, y), 1);
		memory(cb, COMPUTE, STORAGE_WRITE, COMPUTE, SAMPLED_READ | STORAGE_READ | STORAGE_WRITE);
		Profiler.mark(cb, "FSR " + p.name);
	}

	private static void clear(VkCommandBuffer cb, RawImage img, float v) {
		FfxPass.clearFloat(cb, img, v, v, v, v);
	}

	@Override
	public void destroyLater() {
		super.destroyLater();
		all.forEach(RawImage::destroyLater);
		if (ubo != null) ubo.destroyLater(); // null after a failed construction
		long[] views = spdMipViews.clone();
		ctx.destroyLater(() -> { // the passes are shared (FfxPass)
			for (long v : views) vkDestroyImageView(ctx.vk, v, null);
			vkDestroySampler(ctx.vk, pointSampler, null);
			vkDestroySampler(ctx.vk, linearSampler, null);
		});
	}
}
