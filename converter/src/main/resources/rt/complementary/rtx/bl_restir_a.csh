// RTX block light, ReSTIR pass 1 (prepare_f): per pixel the surface (view ray), U (the normaliser of the pack's light, every
// source in reach) and an initial sample drawn over every source in reach by its unshadowed contribution (weighted reservoir
// sampling over the cell's whole list: an exact draw, M = 1), its visibility (an occluded sample is dropped, so reuse spreads
// visible ones), then temporal reuse of last frame's final reservoir at the reprojected point.
layout(local_size_x = 8, local_size_y = 8) in;
const vec2 workGroupsRender = vec2($res, $res);
layout(rgba32ui) uniform writeonly uimage2D emrBlRes;
layout(rgba32ui) uniform readonly uimage2D emrBlResH;
layout(rgba16f) uniform writeonly image2D emrBlG;
layout(rgba16f) uniform readonly image2D emrBlGH;
layout(rgba16f) uniform writeonly image2D emrBlU;

// One candidate of the cell's list (source i with its emrBlPos / emrBlCol records): U's parts and the weighted draw.
// emrBlSourceAt and emrBlColor inlined on the loaded records.
void emrBlCandidate(uint i, vec4 bp, vec4 bc, vec3 q, vec3 qa, vec3 n, inout vec3 front, inout vec3 flush, inout vec3 back,
		inout float best, inout float bestFlush, inout float sum, inout float chosen, inout uint li, inout uint h) {
	vec4 l = vec4(bp.xyz + emrRtLightOrigin.xyz, bp.w);
	float rr = fract(l.w);
	if (bc.a < 0.0) l = vec4(l.xyz + clamp(q - l.xyz, -rr, rr) + n * 0.5, l.w);
	else {
		float hh = dot(n, l.xyz - q);
		if (!(rr < 0.3 || hh >= -0.05 || hh < -0.7)) l = vec4(l.xyz + clamp(q - l.xyz, -rr, rr) + n * 0.5, floor(l.w) + 0.28);
	}
	vec3 dv = l.xyz - q;
	float level = floor(l.w), dd = length(dv), r = dd / max(level, 1.0), win = max(1.0 - r * r, 0.0);
	float base = level / 15.0 * win * win / (1.0 + EMR_BL_FALLOFF * dd * dd);
	if (base <= 0.0) return;
	vec3 c = bc.rgb * bc.rgb * abs(bc.a);
	vec3 a = abs(l.xyz - qa);
	if (l.w != bp.w) { // a vanilla emitter flush with the surface, as emrBlOpen
		flush += c * base;
		bestFlush = max(bestFlush, level - (a.x + a.y + a.z));
	} else if (emrBlTwoSided || dot(n, l.xyz - q) >= -0.05) {
		front += c * base;
		if (bc.a > 0.0) best = max(best, level - (a.x + a.y + a.z));
	} else back += c * base;
	float nd = dot(n, dv) / max(dd, 1e-3);
	float tgt = emrBlLum(c) * base * (emrBlTwoSided ? max((abs(nd) + EMR_BL_WRAP) / (1.0 + EMR_BL_WRAP), EMR_BL_PLANT_NDL)
		: clamp((nd + EMR_BL_WRAP) / (1.0 + EMR_BL_WRAP), 0.0, 1.0));
	if (tgt <= 0.0) return;
	sum += tgt;
	h = h * 747796405u + 2891336453u;
	if (float(h >> 8u) / 16777216.0 * sum < tgt) {
		li = i;
		chosen = tgt;
	}
}

// The cell's list staged through shared memory, 64 records at a time, when every pixel of the 8x8 group with a surface reads
// the same cell (most groups): one coalesced load per record instead of a dependent index -> position -> colour chain per
// pixel and source. Groups that straddle cells walk the list directly. Same candidates in the same order either way.
// Staging also drops the sources that reach no pixel of the group (blocklight:groupCull): a source past its range from the
// group's box (plus the up to ~1.3 blocks emrBlCandidate may move it) has window 0 at every pixel, so the result is the same,
// only the order of the kept candidates changes. A cell list holds every source reaching any of its 8x8x8 blocks; a pixel
// group usually reaches only part of them.
shared uint emrBlSFirst, emrBlSEnd, emrBlSMixed, emrBlSN;
shared uint emrBlSIdx[64];
shared vec4 emrBlSPos[64], emrBlSCol[64];
shared uint emrBlSBox[6]; // the group's q box: min xyz, max xyz as order-preserving uints

uint emrBlOrd(float f) { uint u = floatBitsToUint(f); return (u & 0x80000000u) != 0u ? ~u : u | 0x80000000u; }
float emrBlUnord(uint u) { return uintBitsToFloat((u & 0x80000000u) != 0u ? u & 0x7FFFFFFFu : ~u); }

void main() {
	ivec2 p = ivec2(gl_GlobalInvocationID.xy), size = imageSize(emrBlG);
	uint lid = gl_LocalInvocationIndex;
	// no early return before the barriers below: inactive invocations still take part in the staging
	bool go = false;
	vec3 n = vec3(0.0), pos = vec3(0.0), q = vec3(0.0), qa = vec3(0.0);
	float t = 0.0, lm = 0.0;
	uint first = 0u, end = 0u;
	if (all(lessThan(p, size))) {
		vec3 eye = gbufferModelViewInverse[3].xyz, d = emrBlDir(p, size);
		if (!emrBlPrimary(eye, d, t, n, lm)) {
			imageStore(emrBlG, p, vec4(0.0));
			imageStore(emrBlU, p, vec4(0.0));
			imageStore(emrBlRes, p, EMR_BL_EMPTY);
		} else {
			pos = eye + d * t;
			q = pos + n * 0.02;
			qa = pos + n * 0.5;
			imageStore(emrBlG, p, vec4(n, length(pos)));
			if (emrRtLightFade(q) <= 0.0 || !emrBlCellRange(q, first, end)) {
				imageStore(emrBlU, p, vec4(0.0));
				imageStore(emrBlRes, p, EMR_BL_EMPTY);
			} else go = true;
		}
	}
	if (lid == 0u) {
		emrBlSFirst = ~0u;
		emrBlSEnd = 0u;
		emrBlSMixed = 0u;
		emrBlSBox[0] = emrBlSBox[1] = emrBlSBox[2] = ~0u;
		emrBlSBox[3] = emrBlSBox[4] = emrBlSBox[5] = 0u;
	}
	barrier();
	if (go) {
		atomicMin(emrBlSFirst, first);
		atomicMax(emrBlSEnd, end);
		if (emrRt_blocklight_groupCull != 0) for (int c = 0; c < 3; c++) {
			atomicMin(emrBlSBox[c], emrBlOrd(q[c]));
			atomicMax(emrBlSBox[c + 3], emrBlOrd(q[c]));
		}
	}
	barrier();
	if (go && (first != emrBlSFirst || end != emrBlSEnd)) atomicOr(emrBlSMixed, 1u);
	barrier();
	uint sFirst = emrBlSFirst, sEnd = emrBlSEnd;
	bool staged = emrBlSMixed == 0u && sFirst < sEnd; // the same in the whole group
	// one pass over the cell: U's parts (rtx/bl_common.glsl emrBlOpen) and the weighted draw (emrBlPick)
	vec3 front = vec3(0.0), flush = vec3(0.0), back = vec3(0.0);
	float best = -99.0, bestFlush = -99.0, sum = 0.0, chosen = 0.0;
	uint li = ~0u, h = emrBlHash(uvec3(p, uint(frameCounter)));
	if (staged) {
		// short lists (a lava pool's cells) lose more to the extra barrier than the cull saves
		bool cull = emrRt_blocklight_groupCull != 0 && sEnd - sFirst > 64u;
		vec3 bmin = vec3(0.0), bmax = vec3(0.0);
		if (cull) {
			bmin = vec3(emrBlUnord(emrBlSBox[0]), emrBlUnord(emrBlSBox[1]), emrBlUnord(emrBlSBox[2]));
			bmax = vec3(emrBlUnord(emrBlSBox[3]), emrBlUnord(emrBlSBox[4]), emrBlUnord(emrBlSBox[5]));
		}
		for (uint b0 = sFirst; b0 < sEnd; b0 += 64u) {
			barrier(); // the previous chunk is read by everyone
			if (cull) {
				if (lid == 0u) emrBlSN = 0u;
				barrier();
				if (b0 + lid < sEnd) {
					uint i = emrBlIndex[b0 + lid];
					vec4 bp = emrBlPos[i];
					vec3 l = bp.xyz + emrRtLightOrigin.xyz;
					if (distance(clamp(l, bmin, bmax), l) < max(floor(bp.w), 1.0) + 1.5) {
						uint k = atomicAdd(emrBlSN, 1u);
						emrBlSIdx[k] = i;
						emrBlSPos[k] = bp;
						emrBlSCol[k] = emrBlCol[i];
					}
				}
			} else if (b0 + lid < sEnd) {
				uint i = emrBlIndex[b0 + lid];
				emrBlSIdx[lid] = i;
				emrBlSPos[lid] = emrBlPos[i];
				emrBlSCol[lid] = emrBlCol[i];
			}
			barrier();
			if (go) {
				uint cnt = cull ? emrBlSN : min(64u, sEnd - b0);
				for (uint j = 0u; j < cnt; j++)
					emrBlCandidate(emrBlSIdx[j], emrBlSPos[j], emrBlSCol[j], q, qa, n, front, flush, back, best, bestFlush, sum, chosen, li, h);
			}
		}
	} else if (go) {
		for (uint k = first; k < end; k++) {
			uint i = emrBlIndex[k];
			emrBlCandidate(i, emrBlPos[i], emrBlCol[i], q, qa, n, front, flush, back, best, bestFlush, sum, chosen, li, h);
		}
	}
	if (!go) return;
	vec3 u = front + flush * clamp((lm * 15.0 - best - 1.0) * 0.5, 0.0, 1.0)
		+ back * clamp((lm * 15.0 - max(best, bestFlush) - 1.0) * 0.5, 0.0, 1.0);
	if (emrBlLum(u) < 1e-6) {
		imageStore(emrBlU, p, vec4(0.0));
		imageStore(emrBlRes, p, EMR_BL_EMPTY);
		return;
	}
	imageStore(emrBlU, p, vec4(u, emrBlTwoSided ? 2.0 : 1.0));
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
