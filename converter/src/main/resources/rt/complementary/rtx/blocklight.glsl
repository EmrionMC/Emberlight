// RTX block light in the gbuffers, appended after bl_buffer.glsl, blocklight.glsl and rtx/bl_common.glsl: each known source
// lights with its own colour, falloff, N.L and soft RT shadows, tinted by stained glass. The pack's block light keeps its
// scale: it is multiplied per channel by S / U x strength, clamped to [ambient, strength], where U = every source in reach
// unshadowed and facing (hidden ones gated, rtx/bl_common.glsl emrBlOpen) and S = the same with N.L, shadows and glass. S / U
// comes from the ReSTIR passes (rtx/bl_restir_*.csh, run before the gbuffers on this frame's surfaces) through their
// denoiser; this only looks it up for the fragment. RAYTRACING.md §32.

uint emrBlMask() { return emrRt_blocklight_entities != 0 ? 0x07u : 0x03u; } // rtx/bl_common.glsl

uniform sampler2D emrBlOutSampler;
uniform sampler2D emrBlGSampler;
const ivec2 EMR_BL_RING[8] = ivec2[8](ivec2(1, 0), ivec2(-1, 0), ivec2(0, 1), ivec2(0, -1), ivec2(1, 1), ivec2(-1, 1), ivec2(1, -1), ivec2(-1, -1));

// The filtered ratio at this fragment: its own pixel if the passes' view ray found the same surface (distance and normal),
// else the nearest pixel on a surface facing the same way within 1-8 pixels (what the view ray did not see as raster drew it:
// an alpha-tested edge at another mip, an entity over the BLAS budget). a = 1 where block light applies, 0 where no
// source reaches; false: nothing matches (the hand, particles). hidden: the view ray found a surface well in front of this
// fragment, which raster will draw over it (overdraw; the Deep Dark corridor shaded thousands of these through the fallback).
bool emrRtxBlLookup(vec3 playerPos, vec3 n, out vec4 r, out bool hidden) {
	ivec2 size = textureSize(emrBlGSampler, 0), p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), size - 1);
	float d = length(playerPos);
	vec4 g = texelFetch(emrBlGSampler, p, 0);
	r = texelFetch(emrBlOutSampler, p, 0);
	hidden = g.w > 0.0 && d > g.w + 0.3 + 0.05 * d;
	if (g.w > 0.0 && abs(g.w - d) < 0.05 + 0.02 * d && dot(g.xyz, n) >= 0.7) return true;
	for (int s = 1; s <= 8; s *= 2)
		for (int i = 0; i < 8; i++) {
			ivec2 q = clamp(p + EMR_BL_RING[i] * s, ivec2(0), size - 1);
			g = texelFetch(emrBlGSampler, q, 0);
			if (g.w <= 0.0 || abs(g.w - d) > 0.2 + 0.1 * d || !(dot(g.xyz, n) >= 0.9)) continue;
			r = texelFetch(emrBlOutSampler, q, 0);
			return true;
		}
	return false;
}

// Raster draws over this fragment later: this pixel's view ray (rtx/bl_restir_a.csh) found a surface well in front of it.
// The distance is the rasterised surface's (gl_FragCoord): parallax mapping moves playerPos into the surface. RTX GI has a
// copy (rtx/gi_apply.glsl emrRtxGiHidden).
bool emrRtxBlHidden() {
	ivec2 size = textureSize(emrBlGSampler, 0);
	float w = texelFetch(emrBlGSampler, clamp(ivec2(gl_FragCoord.xy), ivec2(0), size - 1), 0).w;
	vec4 v = gbufferProjectionInverse * vec4(gl_FragCoord.xy / vec2(size) * 2.0 - 1.0, gl_FragCoord.z * 2.0 - 1.0, 1.0);
	float d = length(v.xyz / v.w);
	return w > 0.0 && d > w + emrRt_blocklight_hiddenMargin + 0.05 * d;
}

// The pack's light times the ratio q = S / U, as blocklight:strength and chroma calibrate it, faded to the far factor.
vec3 emrRtxBlApply(vec3 pack, vec3 q, vec3 far, float fade) {
	if (emrRt_blocklight_chroma != 1.0) { // how much of the ratio's own hue reaches the pack's light (1 = all, 0 = brightness only)
		float lr = emrBlLum(q * pack) / max(emrBlLum(pack), 1e-6);
		q = lr * pow(max(q, vec3(1e-4)) / max(lr, 1e-4), vec3(emrRt_blocklight_chroma));
	}
	vec3 f = clamp(q * emrRt_blocklight_strength, vec3(emrRt_blocklight_ambient), vec3(emrRt_blocklight_strength));
	return pack * mix(far, f, fade);
}

vec3 emrRtxBlockLight(vec3 pack, vec3 playerPos, vec3 n, bool lightSource, float lm) {
	if (lightSource || gl_HelperInvocation) return pack; // helper lanes only feed derivatives, nobody differentiates this
	vec3 p = playerPos + n * 0.02;
	// far away the factor fades to emrRt_blocklight_far, about what the traced sources give a lit wall on average
	float fade = emrRtLightFade(p);
	vec3 far = vec3(emrRt_blocklight_far);
	if (fade <= 0.0) return pack * far;
	vec3 none = pack * mix(far, vec3(1.0), fade); // no known source: the pack's light near, the far factor far away
	vec4 r;
	bool hidden, found = emrRtxBlLookup(playerPos, n, r, hidden);
	if (!found && hidden && emrRt_blocklight_debug == 0) return none; // drawn over later: not worth the loop below
	if (!found) { // no traced surface: the sources unshadowed (S without visibility)
		uint first, end;
		if (!emrBlCellRange(p, first, end)) return none;
		vec3 u = emrBlOpen(p, n, lm, first, end);
		if (emrBlLum(u) < 1e-6) return none;
		r = vec4(emrBlLightOpen(playerPos, n) / max(u, vec3(1e-5)), 1.0);
	}
	if (emrRt_blocklight_debug == 1) return r.rgb * 0.5; // the ratio S / U
	if (emrRt_blocklight_debug == 2) return found ? vec3(0.0, r.a, 0.0) : hidden ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0); // red: fallback, blue: hidden (drawn over), green: traced
	return r.a < 0.5 ? none : emrRtxBlApply(pack, r.rgb, far, fade);
}

// A refraction hit's block light (gbuffers_water, rtx/blocklight_apply_hit.glsl): the ReSTIR passes never saw this point, so
// S is estimated here from 4 samples of emrBlLightAt (a source drawn by weight, one shadow ray each; TAA / DLSS average the
// noise) over the same U as the passes, then calibrated like terrain (emrRtxBlApply).
vec3 emrRtxBlockLightHit(vec3 pack, vec3 playerPos, vec3 n, bool lightSource, float lm) {
	if (lightSource || gl_HelperInvocation) return pack;
	vec3 p = playerPos + n * 0.02;
	float fade = emrRtLightFade(p);
	vec3 far = vec3(emrRt_blocklight_far);
	if (fade <= 0.0) return pack * far;
	vec3 none = pack * mix(far, vec3(1.0), fade);
	uint first, end;
	if (!emrBlCellRange(p, first, end)) return none;
	vec3 u = emrBlOpen(p, n, lm, first, end);
	if (emrBlLum(u) < 1e-6) return none;
	uint seed = emrBlHash(uvec3(uvec2(gl_FragCoord.xy), uint(frameCounter)));
	vec3 s = vec3(0.0);
	for (int k = 0; k < 4; k++) s += emrBlLightAt(playerPos, n, seed);
	return emrRtxBlApply(pack, s * 0.25 / max(u, vec3(1e-5)), far, fade);
}
