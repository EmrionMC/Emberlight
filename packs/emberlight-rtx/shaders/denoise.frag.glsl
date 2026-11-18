// Temporal part of the denoiser: joint-bilateral upsample of the half-res GI, small bilateral gathers for the sun
// visibility and the reflections, then temporal accumulation with reprojection. History is rejected per bilinear tap by
// view distance (disocclusion); the sun visibility is clamped toward this frame while the camera moves; at rest
// everything keeps accumulating far longer, so a still image converges and a moving one does not ghost. The GI is filtered further by atrous.frag.glsl.
// Structure after SVGF (Schied et al. 2017), simplified.
#include "/lib/common.glsl"
#include "/lib/materials.glsl"

// DLSS Ray Reconstruction denoises instead (NATIVE_FORMAT.md §14.4): sun and reflections raw, no gathers. The GI keeps its
// temporal history (only the a-trous passes go): it is traced at half resolution, so its upsampled noise is blotchy and
// spatially correlated, which RR keeps as detail (4- to 16-frame histories boiled on lit walls, UPSCALING.md §11.5)
#ifdef EL_RR_ACTIVE
const bool RR = true;
#else
const bool RR = false;
#endif

layout(location = EL_OUT_giAcc) out vec4 outGi;
layout(location = EL_OUT_shadowAcc) out vec4 outShadow;
layout(location = EL_OUT_reflAcc) out vec4 outRefl;

ivec2 giJitter() { int f = el_frame & 3; return ivec2(f & 1, f >> 1); }

void main() {
	ivec2 px = ivec2(gl_FragCoord.xy);
	float depth = texelFetch(el_depth, px, 0).r;
	if (el_isSky(depth)) { outGi = vec4(0.0); outShadow = vec4(1.0, 1.0, 1.0, 1e5); outRefl = vec4(0.0); return; }
	vec3 world = el_screenToWorld(el_uv, depth);
	float dist = length(world);
	GNormal g = decodeGNormal(texelFetch(gNormal, px, 0));
	vec3 n = g.geo;
	Mat m = decodeMat(texelFetch(gMat, px, 0));
	float rough = roughnessOf(m.smoothness);
	ivec2 size = ivec2(el_screenSize);

	// ---- history ----
	// reprojected without the upscaler's jitter (cameraMotion): a still camera reads its own texel, unfiltered
	vec2 mv = cameraMotion(world);
	vec2 prevUv = el_uv + mv;
	float motion = length(mv * el_screenSize); // pixels
	float rest = 1.0 - smoothstep(0.02, 0.5, motion);
	float prevDistExpected = length(world + el_cameraPosition - el_prevCameraPosition);
	float hist = 0.0;
	vec4 hGi = vec4(0.0), hSh = vec4(0.0), hRf = vec4(0.0);
	// bilinear, every tap tested for disocclusion on its own: one centre test let the taps across a depth edge bleed in
	vec2 hpos = prevUv * el_screenSize - 0.5;
	ivec2 hb = ivec2(floor(hpos));
	vec2 hf = hpos - vec2(hb);
	float hw = 0.0;
	for (int i = 0; i < 4; i++) {
		ivec2 o = ivec2(i & 1, i >> 1);
		ivec2 q = hb + o;
		vec2 bw = mix(1.0 - hf, hf, vec2(o));
		float w = bw.x * bw.y;
		if (w < 1e-3 || any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
		vec4 s = texelFetch(shadowAcc, q, 0);
		if (abs(s.a - prevDistExpected) > 0.03 * prevDistExpected + 0.05) continue;
		hSh += s * w; hGi += texelFetch(giAcc, q, 0) * w; hRf += texelFetch(reflAcc, q, 0) * w;
		hw += w;
	}
	if (hw > 0.05) { hSh /= hw; hGi /= hw; hRf /= hw; hist = hGi.a * min(hw * 2.0, 1.0); } // partly valid: shorter history
	float wide = hist < 4.0 ? 2.0 : 1.0; // fresh pixels: wider spatial filter

	// ---- GI: joint bilateral upsample of the half-res samples ----
	ivec2 hsize = textureSize(giRaw, 0);
	ivec2 hc = px / 2;
	vec3 gi = vec3(0.0);
	float wsum = 0.0;
	int R = int(wide);
	for (int y = -R; y <= R; y++) for (int x = -R; x <= R; x++) {
		ivec2 q = clamp(hc + ivec2(x, y) * int(wide), ivec2(0), hsize - 1);
		ivec2 fq = min(q * 2 + giJitter(), size - 1);
		float dq = texelFetch(el_depth, fq, 0).r;
		if (el_isSky(dq)) continue;
		vec3 wq = el_screenToWorld((vec2(fq) + 0.5) / el_screenSize, dq);
		vec3 nq = decodeGNormal(texelFetch(gNormal, fq, 0)).geo;
		float plane = abs(dot(wq - world, n));
		float w = exp(-plane / (0.02 * dist + 0.05)) * pow(max(dot(n, nq), 0.0), 16.0) * exp(-0.3 * float(x * x + y * y));
		gi += texelFetch(giRaw, q, 0).rgb * w;
		wsum += w;
	}
	gi = wsum > 1e-4 ? gi / wsum : hGi.rgb;

	// ---- sun visibility: 3x3 bilateral at full res, with its min/max for the history clamp ----
	vec3 sh = vec3(0.0), shMin = vec3(1e9), shMax = vec3(-1e9);
	float ssum = 0.0;
	for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
		ivec2 q = clamp(px + ivec2(x, y) * int(wide), ivec2(0), size - 1);
		float dq = texelFetch(el_depth, q, 0).r;
		vec3 nq = decodeGNormal(texelFetch(gNormal, q, 0)).geo;
		vec3 wq = el_screenToWorld((vec2(q) + 0.5) / el_screenSize, dq);
		float w = exp(-abs(dot(wq - world, n)) / (0.01 * dist + 0.03)) * pow(max(dot(n, nq), 0.0), 32.0) * (x == 0 && y == 0 ? 2.0 : 1.0);
		vec3 s = texelFetch(sunRaw, q, 0).rgb;
		sh += s * w;
		ssum += w;
		if (w > 0.1) { shMin = min(shMin, s); shMax = max(shMax, s); }
	}
	sh /= max(ssum, 1e-4);
	if (RR) sh = texelFetch(sunRaw, px, 0).rgb;

	// ---- reflections: mirrors as they are, glossy ones gathered over the reflection lobe's footprint on screen ----
	// The lobe (GGX alpha = rough) spreads over about hitDistance * alpha in the world, which is that many pixels at
	// this view distance; 8 taps on a per-frame rotated spiral cover it (none below 1.5 px: the temporal pass suffices), weighted by plane, normal and smoothness so
	// the gather stays on this surface (radius idea after Stachowiak 2015, "Stochastic Screen-Space Reflections").
	vec4 rf = texelFetch(reflRaw, px, 0);
	// rt_direct.comp.glsl: the reflection shows a glowing surface. Gathered below as the glowing share of the radiance
	float glow = rf.a < 0.0 ? 1.0 : 0.0, gLum = el_luminance(rf.rgb), gSum = glow * gLum;
	rf.a = abs(rf.a);
	if (!RR && rough > 0.02 && rough <= REFL_MAX_ROUGH) { // rougher: no traced reflection (lighting uses its ambient estimate)
		float alpha = rough;
		float pixAngle = 2.0 / (el_projection[1][1] * el_screenSize.y);
		float radius = min(0.5 * min(rf.a, 32.0) * alpha / (max(dist, 0.3) * pixAngle), 12.0) * wide; // ~ half-width of the lobe
		vec3 acc = rf.rgb;
		float rsum = 1.0;
		float rot = ign(vec2(px) + float(el_frame & 31) * 5.588238) * 6.2831853;
		for (int i = 0; i < (radius < 1.5 ? 0 : 8); i++) {
			float r = sqrt((float(i) + 0.5) / 8.0) * radius;
			float a = rot + float(i) * 2.3999632; // golden angle
			ivec2 q = clamp(px + ivec2(round(vec2(cos(a), sin(a)) * r)), ivec2(0), size - 1);
			float dq = texelFetch(el_depth, q, 0).r;
			if (el_isSky(dq)) continue;
			vec3 wq = el_screenToWorld((vec2(q) + 0.5) / el_screenSize, dq);
			vec3 nq = decodeGNormal(texelFetch(gNormal, q, 0)).geo;
			float sq = texelFetch(gMat, q, 0).r;
			float w = pow(max(dot(n, nq), 0.0), 32.0) * exp(-abs(dot(wq - world, n)) / (0.01 * dist + 0.03))
				* exp(-abs(sq - m.smoothness) * 20.0) * min(rough * 10.0, 1.0);
			vec4 s = texelFetch(reflRaw, q, 0);
			acc += s.rgb * w;
			rsum += w;
			float sl = el_luminance(s.rgb) * w;
			gLum += sl;
			gSum += s.a < 0.0 ? sl : 0.0;
		}
		rf.rgb = acc / rsum;
		glow = gSum / max(gLum, 1e-6);
	}

	// ---- reflection history: reprojected by the reflected image, not the surface ----
	// A reflection seen in a floor sits behind the floor, at the hit distance along the view ray (exact for a plane
	// mirror, close enough for a glossy lobe); following the surface instead dragged it along with the camera (a
	// smeared glow on walls and floors while turning). Taps must still lie on this surface (view distance).
	if (!RR && hist > 0.0 && rest < 0.99 && rough <= REFL_MAX_ROUGH && m.smoothness > 0.05) {
		vec3 vp = world + normalize(world) * min(rf.a, 128.0);
		vec2 rpos = (el_uv + cameraMotion(vp)) * el_screenSize - 0.5;
		ivec2 rb = ivec2(floor(rpos));
		vec2 rfr = rpos - vec2(rb);
		vec4 acc = vec4(0.0);
		float aw = 0.0;
		for (int i = 0; i < 4; i++) {
			ivec2 o = ivec2(i & 1, i >> 1);
			ivec2 q = rb + o;
			vec2 bw = mix(1.0 - rfr, rfr, vec2(o));
			float w = bw.x * bw.y;
			if (w < 1e-3 || any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
			if (abs(texelFetch(shadowAcc, q, 0).a - prevDistExpected) > 0.08 * prevDistExpected + 0.1) continue;
			acc += texelFetch(reflAcc, q, 0) * w;
			aw += w;
		}
		if (aw > 0.25) hRf = acc / aw;
	}

	// ---- temporal ----
	// Diffuse GI and sun visibility do not depend on the view, so a moving camera keeps most of their history (it is
	// reprojected; disocclusions start over above). The old per-frame clamp of the GI history to 0.5-2x this frame's
	// one-sample estimate threw the history away wherever few paths find the light (a room lit by sun patches): grain
	// in motion. Only the sun visibility keeps a clamp (moving entity and player shadows), to its 3x3 neighbourhood.
	float n1 = hist + 1.0;
	float giCap = mix(float(GI_FRAMES), float(GI_FRAMES) * 3.0, rest);
	float aGi = 1.0 / min(n1, giCap);
	float aSh = RR ? 1.0 : 1.0 / min(n1, mix(12.0, 32.0, rest));
	// reflections move with the view: while moving, their history is clipped to this frame's 3x3 neighbourhood
	// (mean +- 1.5 sigma, variance clipping after Salvi 2016) instead of being cut to a few frames, which was grain
	float rfMove = rough < 0.02 ? 2.0 : clamp(rough * 200.0, 4.0, 16.0);
	// perfect mirrors trace one fixed ray per pixel: nothing to average, and history only ghosted the reflected image
	float aRf = RR || rough < 0.005 ? 1.0 : 1.0 / min(n1, mix(rfMove, 32.0, rest));
	if (!RR && hist > 0.0 && rest < 0.99 && rough <= REFL_MAX_ROUGH) {
		vec3 r1 = vec3(0.0), r2 = vec3(0.0);
		for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
			vec3 s = texelFetch(reflRaw, clamp(px + ivec2(x, y), ivec2(0), size - 1), 0).rgb;
			r1 += s; r2 += s * s;
		}
		r1 /= 9.0;
		vec3 rsd = sqrt(max(r2 / 9.0 - r1 * r1, 0.0)) * 1.5 + 0.01;
		hRf.rgb = mix(hRf.rgb, clamp(hRf.rgb, r1 - rsd, r1 + rsd), 1.0 - rest);
	}
	if (hist > 0.0) hSh.rgb = mix(hSh.rgb, clamp(hSh.rgb, shMin - 0.1, shMax + 0.1), 1.0 - rest);
	outGi = vec4(mix(hGi.rgb, gi, hist > 0.0 ? aGi : 1.0), min(n1, 255.0));
	outShadow = vec4(mix(hSh.rgb, sh, hist > 0.0 ? aSh : 1.0), dist);
	// a = how much of the reflection glows (lighting.frag.glsl passes it on to bloom)
	outRefl = mix(hRf, vec4(rf.rgb, glow), hist > 0.0 ? aRf : 1.0);
}
