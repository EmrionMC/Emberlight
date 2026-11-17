// Deferred lighting: reads the gbuffer, writes HDR colour.
#include "/lib/lighting.glsl"

layout(location = EL_OUT_lit) out vec4 outLit;

void main() {
	ivec2 px = ivec2(gl_FragCoord.xy);
	float depth = texelFetch(el_depth, px, 0).r;
	vec3 world = el_screenToWorld(el_uv, depth);
	if (el_isSky(depth)) {
		outLit = vec4(skyRadiance(normalize(world)), 1.0);
		return;
	}
	vec3 albedo = el_srgbToLinear(texelFetch(gAlbedo, px, 0).rgb);
	vec3 n = el_decodeNormal(texelFetch(gNormal, px, 0).rg);
	vec4 l = texelFetch(gLight, px, 0);
#if defined(RT_SHADOWS) && defined(EL_DIMENSION_DEFAULT)
	float sunVis = texelFetch(rtShadow, px, 0).r;
#else
	float sunVis = smoothstep(0.85, 0.95, l.g); // no rays: sky light as a rough "outdoors" test
#endif
	outLit = vec4(applyFog(shade(albedo, n, l.rg, l.b, l.a, sunVis), world), 1.0);
}
