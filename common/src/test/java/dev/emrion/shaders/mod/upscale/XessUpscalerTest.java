package dev.emrion.shaders.mod.upscale;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class XessUpscalerTest {
	/** XeSS 1.3+ maximum ratio per quality setting (xess_sr_developer_guide, dynamic input resolution). */
	private static final Map<Integer, Float> MAX_RATIO = Map.of(100, 3.0f, 101, 2.3f, 102, 2.0f, 103, 1.7f, 104, 1.5f, 105, 1.3f, 106, 1.0f);

	@Test
	void everyPresetMapsToAnXessSettingThatCoversItsRatio() {
		for (UpscaleConfig.Preset p : UpscaleConfig.Preset.values()) {
			float max = MAX_RATIO.get(XessUpscaler.quality(p));
			assertTrue(p.ratio <= max + 1e-6, p + " ratio " + p.ratio + " > XeSS max " + max);
			// the tightest setting that still covers it (XeSS tunes per setting)
			assertTrue(MAX_RATIO.values().stream().noneMatch(m -> m >= p.ratio - 1e-6 && m < max - 1e-6), p.toString());
		}
	}
}
