package dev.emrion.shaders.mod.framegen;

import static org.lwjgl.vulkan.VK10.*;

import dev.emrion.shaders.mod.vk.RawImage;
import java.nio.ByteBuffer;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Java port of the host logic of AMD FidelityFX {@code ffx_opticalflow.cpp} (SDK v1.1.4, MIT, Copyright (C) 2024 Advanced
 * Micro Devices, Inc.). Output: {@link #flow} (one vector per 8×8 block) and {@link #scd} (scene change detection).
 */
final class OpticalFlow {
	static final int BLOCK = 8;
	private static final int LEVELS = 7, HISTOGRAM_BINS = 256, HISTOGRAMS_PER_DIM = 3, HISTOGRAM_SHIFTS = 3, MAX_QUEUED_FRAMES = 16;

	private final FfxPasses f;
	private final int w, h;
	private final FfxPasses.Pass prepareLuma, inputPyramid, scdHistogram, scdDivergence, search, filter, scale;
	private final RawImage[][] input = new RawImage[2][LEVELS], flowLevels = new RawImage[2][LEVELS];
	private final RawImage scdHist, scdPrevHist, scdTemp;
	final RawImage flow, scd;
	private final int[][] flowSize = new int[LEVELS][2];
	private boolean first = true;
	private int frameIndex, resourceFrameIndex;

	OpticalFlow(FfxPasses f, int w, int h) {
		this.f = f;
		this.w = w;
		this.h = h;
		inputPyramid = f.pass("ffx_opticalflow_compute_luminance_pyramid_pass");
		prepareLuma = f.pass("ffx_opticalflow_prepare_luma_pass");
		scdHistogram = f.pass("ffx_opticalflow_generate_scd_histogram_pass");
		scdDivergence = f.pass("ffx_opticalflow_compute_scd_divergence_pass");
		search = f.pass("ffx_opticalflow_compute_optical_flow_advanced_pass_v5");
		filter = f.pass("ffx_opticalflow_filter_optical_flow_pass_v5");
		scale = f.pass("ffx_opticalflow_scale_optical_flow_advanced_pass_v5");
		flowSize[0][0] = (w + BLOCK - 1) / BLOCK;
		flowSize[0][1] = (h + BLOCK - 1) / BLOCK;
		for (int i = 1; i < LEVELS; i++) { // the C++ code's FFX_ALIGN_UP(x, 2) / 2 for the resources = (x + 1) / 2 for the dispatches
			flowSize[i][0] = (flowSize[i - 1][0] + 1) / 2;
			flowSize[i][1] = (flowSize[i - 1][1] + 1) / 2;
		}
		for (int s = 0; s < 2; s++) {
			for (int l = 0; l < LEVELS; l++) {
				input[s][l] = f.image(VK_FORMAT_R8_UINT, w >> l, h >> l, 1, "of input " + (s + 1) + " level " + l);
				flowLevels[s][l] = f.image(VK_FORMAT_R16G16_SINT, flowSize[l][0], flowSize[l][1], 1, "of flow " + (s + 1) + " level " + l);
			}
		}
		int histWidth = HISTOGRAM_BINS * HISTOGRAMS_PER_DIM * HISTOGRAMS_PER_DIM;
		scdHist = f.image(VK_FORMAT_R32_UINT, histWidth, 1, 1, "of scd histogram");
		scdPrevHist = f.image(VK_FORMAT_R32_SFLOAT, histWidth, 1, 1, "of scd previous histogram");
		scdTemp = f.image(VK_FORMAT_R32_UINT, 3, 1, 1, "of scd temp");
		flow = f.image(VK_FORMAT_R16G16_SINT, flowSize[0][0], flowSize[0][1], 1, "of result");
		scd = f.image(VK_FORMAT_R32_UINT, 3, 1, 1, "of scd output");
	}

	private void constants(int level, int levelCount) {
		ByteBuffer c = f.constants("cbOF", 32);
		c.putInt(0, w).putInt(4, h).putInt(8, level).putInt(12, levelCount).putInt(16, frameIndex).putInt(20, 0) // transfer function: sRGB-like LDR
				.putFloat(24, 0f).putFloat(28, 1f);
	}

	/** Records the optical flow of {@code colorView} (display size) against the previous call's input. */
	void dispatch(VkCommandBuffer cb, long colorView, boolean reset) {
		var b = f.bind;
		b.put("r_input_color", colorView);
		boolean resetAccumulation = reset || first;
		first = false;
		frameIndex = resetAccumulation ? 0 : frameIndex + 1;
		if (resetAccumulation) {
			for (RawImage i : new RawImage[] {scdTemp, scd, scdHist}) FfxPasses.clearUint(cb, i, 0);
			FfxPasses.clearFloat(cb, scdPrevHist, 0f);
			for (RawImage[] set : input) for (RawImage i : set) FfxPasses.clearUint(cb, i, 0);
			FfxPasses.barrier(cb);
		}
		int[] spd = FfxPasses.spdSetup(w, h, 4);
		constants(0, LEVELS);
		f.constants("cbOF_SPD", 32).putInt(0, spd[3]).putInt(4, spd[2]).putInt(8, 0).putInt(12, 0).putInt(16, spd[2]);

		b.put("rw_optical_flow_scd_histogram", scdHist.view);
		b.put("rw_optical_flow_scd_previous_histogram", scdPrevHist.view);
		b.put("rw_optical_flow_scd_temp", scdTemp.view);
		b.put("rw_optical_flow_scd_output", scd.view);
		boolean odd = (resourceFrameIndex & 1) != 0;
		RawImage[] in = input[odd ? 1 : 0], prevIn = input[odd ? 0 : 1];
		b.put("rw_optical_flow_input", in[0].view);
		for (int l = 1; l < LEVELS; l++) b.put("rw_optical_flow_input_level_" + l, in[l].view);
		b.put("r_optical_flow_input", in[0].view);
		b.put("r_optical_flow_previous_input", prevIn[0].view);
		b.put("r_optical_flow", flowLevels[odd ? 1 : 0][0].view);
		b.put("rw_optical_flow", flowLevels[odd ? 1 : 0][0].view);
		b.put("r_optical_flow_previous", flowLevels[odd ? 0 : 1][0].view);

		f.run(cb, prepareLuma, ((w + 1) / 2 + 15) / 16, ((h + 1) / 2 + 15) / 16, 1);
		f.run(cb, inputPyramid, spd[0], spd[1], 1);
		int strataWidth = (w / 4) / HISTOGRAMS_PER_DIM;
		f.run(cb, scdHistogram, (strataWidth + 31) / 32, 16, HISTOGRAMS_PER_DIM * HISTOGRAMS_PER_DIM);
		f.run(cb, scdDivergence, HISTOGRAMS_PER_DIM * HISTOGRAMS_PER_DIM, HISTOGRAM_SHIFTS, 1);

		for (int level = LEVELS - 1; level >= 0; level--) {
			boolean oddLevel = (level & 1) != 0;
			RawImage[] inA = input[odd ? 1 : 0], inB = input[odd ? 0 : 1];
			RawImage[] flowA = flowLevels[odd != oddLevel ? 1 : 0], flowB = flowLevels[odd != oddLevel ? 0 : 1];
			constants(level, LEVELS);
			b.put("r_optical_flow_input", inA[level].view);
			b.put("r_optical_flow_previous_input", inB[level].view);
			b.put("rw_optical_flow", flowA[level].view);
			b.put("r_optical_flow_previous", flowB[level].view);
			int lw = Math.max(w >> level, 1), lh = Math.max(h >> level, 1);
			f.run(cb, search, ((lw + 3) / 4 * 16 + 63) / 64, (lh + 15) / 16, 1);

			b.put("r_optical_flow_previous", flowA[level].view);
			b.put("rw_optical_flow", level == 0 ? flow.view : flowB[level].view);
			f.run(cb, filter, (flowSize[level][0] + 15) / 16, (flowSize[level][1] + 3) / 4, 1);

			if (level > 0) {
				b.put("r_optical_flow", flowB[level].view);
				b.put("rw_optical_flow_next_level", flowB[level - 1].view);
				f.run(cb, scale, (flowSize[level - 1][0] + 3) / 4, (flowSize[level - 1][1] + 3) / 4, 1);
			}
		}
		resourceFrameIndex = (resourceFrameIndex + 1) % MAX_QUEUED_FRAMES;
	}
}
