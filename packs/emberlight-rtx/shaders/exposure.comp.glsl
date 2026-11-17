// Eye adaptation: centre-weighted log-average luminance of the HDR image (16x16 samples), smoothed over time.
#include "/lib/common.glsl"
#include "/lib/exposure.glsl"

void main() {
	float s = 0.0, w = 0.0;
	for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
		vec2 uv = (vec2(x, y) + 0.5) / 16.0;
		float k = 1.0 - 0.6 * length(uv - 0.5);
		s += log2(max(el_luminance(texelFetch(lit, ivec2(uv * el_screenSize), 0).rgb), 1e-4)) * k;
		w += k;
	}
	float avg = exp2(s / w);
	// darker places get less than full compensation (night stays night, caves stay a little dark); 0.75 / 8x left lit
	// rooms murky next to their lamps, so indoors now adapts further
	float target = clamp(0.18 / pow(max(avg, 1e-4), 0.8), 0.3, 10.0);
#if defined(EL_DIMENSION_END)
	target = min(target, 1.4); // the End stays dark around its lit island and the glowing vortex
#endif
	float prev = exposureValue;
	float speed = target > prev ? 1.2 : 2.5;
	exposureValue = prev <= 0.0 || isnan(prev) ? target : mix(prev, target, 1.0 - exp(-el_frameTime * speed));
	exposureLum = avg;
}
