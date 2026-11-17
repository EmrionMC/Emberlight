// HDR -> display. The upscaler (DLSS/FSR/XeSS) takes this image, so it must be the finished, tonemapped frame.
layout(location = EL_OUT_ldr) out vec4 outColor;

vec3 aces(vec3 x) { return clamp(x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }

void main() {
	vec3 c = texelFetch(lit, ivec2(gl_FragCoord.xy), 0).rgb * EXPOSURE;
#if TONEMAP == TONEMAP_ACES
	c = aces(c);
#elif TONEMAP == TONEMAP_REINHARD
	c = c / (1.0 + c);
#else
	c = clamp(c, 0.0, 1.0);
#endif
	outColor = vec4(el_linearToSrgb(c), 1.0);
}
