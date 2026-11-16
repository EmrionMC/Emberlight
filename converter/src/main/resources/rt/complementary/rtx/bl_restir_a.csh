// RTX block light, ReSTIR pass 1 (prepare_f): per pixel the surface (view ray), U (the normaliser of the pack's light, every
// source in reach) and an initial sample drawn over every source in reach by its unshadowed contribution (weighted reservoir
// sampling over the cell's whole list: an exact draw, M = 1), its visibility (an occluded sample is dropped, so reuse spreads
// visible ones), then temporal reuse of last frame's final reservoir at the reprojected point.
layout(local_size_x = 8, local_size_y = 8) in;
const vec2 workGroupsRender = vec2(1.0, 1.0);
layout(rgba32ui) uniform writeonly uimage2D emrBlRes;
layout(rgba32ui) uniform readonly uimage2D emrBlResH;
layout(rgba16f) uniform writeonly image2D emrBlG;
layout(rgba16f) uniform readonly image2D emrBlGH;
layout(rgba16f) uniform writeonly image2D emrBlU;

void main() {
	ivec2 p = ivec2(gl_GlobalInvocationID.xy), size = imageSize(emrBlG);
	if (any(greaterThanEqual(p, size))) return;
	vec3 n, eye = gbufferModelViewInverse[3].xyz, d = emrBlDir(p, size);
	float t, lm;
	if (!emrBlPrimary(eye, d, t, n, lm)) {
		imageStore(emrBlG, p, vec4(0.0));
		imageStore(emrBlU, p, vec4(0.0));
		imageStore(emrBlRes, p, EMR_BL_EMPTY);
		return;
	}
	vec3 pos = eye + d * t, q = pos + n * 0.02, qa = pos + n * 0.5;
	float dist = length(pos);
	imageStore(emrBlG, p, vec4(n, dist));
	uint first, end;
	if (emrRtLightFade(q) <= 0.0 || !emrBlCellRange(q, first, end)) {
		imageStore(emrBlU, p, vec4(0.0));
		imageStore(emrBlRes, p, EMR_BL_EMPTY);
		return;
	}
	// one pass over the cell: U's parts (rtx/bl_common.glsl emrBlOpen) and the weighted draw (emrBlPick)
	vec3 front = vec3(0.0), back = vec3(0.0);
	float best = -99.0, sum = 0.0, chosen = 0.0;
	uint li = ~0u, h = emrBlHash(uvec3(p, uint(frameCounter)));
	for (uint k = first; k < end; k++) {
		uint i = emrBlIndex[k];
		vec4 l = emrBlSourceAt(i, q, n);
		vec3 dv = l.xyz - q;
		float level = floor(l.w), dd = length(dv), r = dd / max(level, 1.0), win = max(1.0 - r * r, 0.0);
		float base = level / 15.0 * win * win / (1.0 + EMR_BL_FALLOFF * dd * dd);
		if (base <= 0.0) continue;
		vec3 c = emrBlColor(i);
		if (dot(n, l.xyz - q) >= -0.05) {
			front += c * base;
			vec3 a = abs(l.xyz - qa);
			if (emrBlCol[i].a > 0.0) best = max(best, level - (a.x + a.y + a.z));
		} else back += c * base;
		float tgt = emrBlLum(c) * base * clamp((dot(n, dv) / max(dd, 1e-3) + EMR_BL_WRAP) / (1.0 + EMR_BL_WRAP), 0.0, 1.0);
		if (tgt <= 0.0) continue;
		sum += tgt;
		h = h * 747796405u + 2891336453u;
		if (float(h >> 8u) / 16777216.0 * sum < tgt) {
			li = i;
			chosen = tgt;
		}
	}
	vec3 u = front + back * clamp((lm * 15.0 - best - 1.0) * 0.5, 0.0, 1.0);
	if (emrBlLum(u) < 1e-6) {
		imageStore(emrBlU, p, vec4(0.0));
		imageStore(emrBlRes, p, EMR_BL_EMPTY);
		return;
	}
	imageStore(emrBlU, p, vec4(u, 1.0));
	uint seed = h;
	vec2 dk = emrBlDisk(vec2(emrBlRand(seed), emrBlRand(seed)));
	float wsum = 0.0, m = 0.0, target = 0.0;
	uvec4 res = EMR_BL_EMPTY;
	if (li != ~0u) {
		// visibility reuse: an occluded initial sample keeps its count but no weight
		bool vis = emrRt_blocklight_visRay == 0 || emrBlLum(emrBlRay(q, n, emrBlSource(li), emrBlDiskPoint(emrBlSourceAt(li, q, n), q, dk))) > 0.0;
		wsum = vis ? sum : 0.0; // target * W with W = sum / target
		res = emrBlPack(li, dk, 0.0, 1.0);
		target = chosen;
	}
	m = 1.0;
	// temporal reuse: the nearest of the 2x2 texels around the reprojected point whose surface matches
	vec2 hp;
	if (emrBlPrevTexel(pos, size, hp)) {
		ivec2 o = ivec2(floor(hp - 0.5));
		vec2 f = hp - 0.5 - vec2(o);
		ivec2 order = ivec2(f.x < 0.5 ? 0 : 1, f.y < 0.5 ? 0 : 1);
		vec3 prevPos = pos + cameraPosition - previousCameraPosition;
		float pd = length(prevPos);
		for (int j = 0; j < 4; j++) {
			ivec2 qp = o + (order ^ ivec2(j & 1, j >> 1));
			if (any(lessThan(qp, ivec2(0))) || any(greaterThanEqual(qp, size))) continue;
			vec4 g = imageLoad(emrBlGH, qp);
			if (g.w <= 0.0 || abs(g.w - pd) > 0.05 + 0.03 * pd || !(dot(g.xyz, n) >= 0.9)) continue; // !(>=): NaN rejects
			uvec4 hr = emrBlValid(imageLoad(emrBlResH, qp), emrRtLightChange[1].w > 0.0);
			if (hr.x == ~0u) break;
			// a light placed or removed nearby: the history's weights miss it, keep only a few frames of them
			vec4 ch = emrRtLightChange[0];
			bool changed = ch.w > 0.0 && all(greaterThanEqual(pos, ch.xyz)) && all(lessThanEqual(pos, emrRtLightChange[1].xyz));
			float mh = min(uintBitsToFloat(hr.w), changed ? ch.w : float(emrRt_blocklight_mcap));
			float th = emrBlTarget(hr.x, q, n), wh = th * uintBitsToFloat(hr.z) * mh;
			// an occluded history sample that is visible now: the occluder went away, its M only counts the candidates it hid
			// (without this a revealed light came back over ~20 frames)
			if (emrRt_blocklight_bias != 0 && wh <= 0.0 && th > 0.0
					&& emrBlLum(emrBlRay(q, n, emrBlSource(hr.x), emrBlDiskPoint(emrBlSourceAt(hr.x, q, n), q, emrBlDiskOf(hr)))) > 0.0) break;
			m += mh;
			if (wh > 0.0) {
				wsum += wh;
				h = h * 747796405u + 2891336453u;
				if (float(h >> 8u) / 16777216.0 * wsum < wh) {
					res = hr;
					target = th;
				}
			}
			break;
		}
	}
	float w = target > 0.0 && wsum > 0.0 ? wsum / (m * target) : 0.0;
	imageStore(emrBlRes, p, res.x == ~0u ? EMR_BL_EMPTY : emrBlPack(res.x, emrBlDiskOf(res), w, m));
}
