// Bloom, eye adaptation, tonemapping, colour grading. Output is the finished SDR frame (the upscaler's input).
#include "/lib/common.glsl"
#include "/lib/exposure.glsl"

layout(location = EL_OUT_lit) out vec4 outColor; // the finished SDR frame replaces the HDR one

// ACES fitted (Stephen Hill's RRT+ODT fit)
vec3 acesFitted(vec3 c) {
	const mat3 inM = mat3(0.59719, 0.07600, 0.02840, 0.35458, 0.90834, 0.13383, 0.04823, 0.01566, 0.83777);
	const mat3 outM = mat3(1.60475, -0.10208, -0.00327, -0.53108, 1.10813, -0.07276, -0.07367, -0.00605, 1.07602);
	c = inM * c;
	vec3 a = c * (c + 0.0245786) - 0.000090537;
	vec3 b = c * (0.983729 * c + 0.4329510) + 0.238081;
	return clamp(outM * (a / b), 0.0, 1.0);
}

void main() {
	vec3 c = texelFetch(lit, ivec2(gl_FragCoord.xy), 0).rgb;
	// bloom: a wide sum over the mip chain, energy-conserving blend
	vec3 b = vec3(0.0);
	float wsum = 0.0;
	for (int i = 1; i <= 7; i++) {
		float w = 1.0 / (1.0 + float(i) * 0.35);
		b += textureLod(bloom, el_uv, float(i)).rgb * w;
		wsum += w;
	}
	b /= wsum;
	float amount = clamp(0.045 * BLOOM * (0.5 + 0.5 * RTX_INTENSITY), 0.0, 0.6);
	c = mix(c, b, amount);

	float e = EXPOSURE;
#ifdef AUTO_EXPOSURE
	e *= exposureValue > 0.0 ? exposureValue : 1.0;
#endif
	c *= e;
	// a touch of extra saturation and contrast scaled by RTX intensity
	float lum = el_luminance(c);
	c = max(mix(vec3(lum), c, 1.0 + 0.10 * RTX_INTENSITY), 0.0);
	c = acesFitted(c);
	vec2 q = el_uv - 0.5;
	c *= 1.0 - dot(q, q) * 0.35 * (0.5 + 0.5 * RTX_INTENSITY);
	outColor = vec4(el_linearToSrgb(c), 1.0);
}
