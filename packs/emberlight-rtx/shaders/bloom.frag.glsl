// Half-resolution copy of the HDR image for the bloom mip chain (Karis average against fireflies). Emissive surfaces
// enter it amplified (NEON_GLOW): the coloured halo around neon and lamps that the RTX look is known for, without
// blooming a bright sky or a sunlit wall as much.
#include "/lib/materials.glsl"

layout(location = EL_OUT_bloom) out vec4 outBloom;

void main() {
	ivec2 p = ivec2(gl_FragCoord.xy) * 2;
	ivec2 m = ivec2(el_screenSize) - 1;
	vec3 s = vec3(0.0);
	float w = 0.0;
	for (int i = 0; i < 4; i++) {
		ivec2 q = min(p + ivec2(i & 1, i >> 1), m);
		vec3 c = max(texelFetch(lit, q, 0).rgb, vec3(0.0));
		if (!el_isSky(texelFetch(el_depth, q, 0).r)) c *= 1.0 + 8.0 * NEON_GLOW * decodeMat(texelFetch(gMat, q, 0)).emission;
		float k = 1.0 / (1.0 + el_luminance(c) * 0.25);
		s += c * k;
		w += k;
	}
	outBloom = vec4(min(s / w, vec3(6.0e4)), 1.0);
}
