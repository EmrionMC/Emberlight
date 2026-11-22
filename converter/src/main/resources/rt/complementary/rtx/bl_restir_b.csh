// RTX block light, ReSTIR pass 2 (prepare_g): spatial reuse from a few neighbours on the same surface, the final shadow ray,
// and the ratio S / U the gbuffers apply to the pack's light. The final reservoir becomes next frame's history (an occluded
// sample without weight). Reservoirs carry visibility (an occluded sample has W 0 but keeps its M), so the 1/Z normalisation
// counts a neighbour's M only where the chosen sample is visible from that neighbour too (ray-traced bias correction, RTXDI):
// with plain 1/M, a lit pixel next to a shadow divided its light by the shadowed neighbours' M, and through the temporal
// history that compounded into a dark band along every shadow edge (blocklight:bias=0 for the old weights).
layout(local_size_x = 8, local_size_y = 8) in;
const vec2 workGroupsRender = vec2($res, $res);
layout(rgba32ui) uniform readonly uimage2D emrBlRes;
layout(rgba32ui) uniform writeonly uimage2D emrBlResH;
layout(rgba16f) uniform readonly image2D emrBlG;
layout(rgba16f) uniform readonly image2D emrBlU;
layout(rgba16f) uniform writeonly image2D emrBlR;

// Reference (blocklight:ref=1): every source of the cell, 32 disk points each, no reuse.
vec3 emrBlReference(vec3 q, vec3 n, inout uint seed) {
	uint first, end;
	vec3 s = vec3(0.0);
	if (!emrBlCellRange(q, first, end)) return s;
	for (uint k = first; k < end; k++) {
		uint i = emrBlIndex[k];
		vec4 l = emrBlSourceAt(i, q, n);
		float w = emrBlWeight(l, q, n);
		if (w <= 0.0) continue;
		vec3 v = vec3(0.0);
		for (int j = 0; j < 32; j++) v += emrBlRay(q, n, emrBlSource(i), emrBlDiskPoint(l, q, emrBlDisk(vec2(emrBlRand(seed), emrBlRand(seed)))));
		s += emrBlColor(i) * w * v / 32.0;
	}
	return s;
}

void main() {
	ivec2 p = ivec2(gl_GlobalInvocationID.xy), size = imageSize(emrBlG);
	if (any(greaterThanEqual(p, size))) return;
	vec4 g = imageLoad(emrBlG, p), u = imageLoad(emrBlU, p);
	if (g.w <= 0.0 || u.a <= 0.0) {
		imageStore(emrBlR, p, vec4(0.0));
		imageStore(emrBlResH, p, EMR_BL_EMPTY);
		return;
	}
	vec3 n = g.xyz, pos = emrBlPixelPos(p, size, g.w), q = pos + n * 0.02;
	emrBlTwoSided = u.a > 1.5;
	uint seed = emrBlHash(uvec3(p, uint(frameCounter) + 0x9E3779B9u));
	if (emrRt_blocklight_ref != 0) {
		imageStore(emrBlR, p, vec4(emrBlReference(q, n, seed) / max(u.rgb, vec3(1e-5)), 1.0));
		imageStore(emrBlResH, p, EMR_BL_EMPTY);
		return;
	}
	uvec4 r = imageLoad(emrBlRes, p);
	float m = uintBitsToFloat(r.w), target = r.x == ~0u ? 0.0 : emrBlTarget(r.x, q, n);
	float wsum = target * uintBitsToFloat(r.z) * m;
	// spatial reuse: neighbours on a disk (radius emrRt_blocklight_radius px), rotated per pixel and frame
	const int K = emrRt_blocklight_rays >= 8 ? 6 : emrRt_blocklight_rays >= 4 ? 4 : 2;
	// a full temporal history (M = 1 + mcap after pass A) needs few neighbours: blocklight:spatialMin of them (0 = always K),
	// each a bias ray less (RTXDI does the same); young history and the light-change box keep all K
	vec4 ch = emrRt_blocklight_spatialMin > 0 ? emrRtLightChange[0] : vec4(0.0);
	bool changed = ch.w > 0.0 && all(greaterThanEqual(pos, ch.xyz)) && all(lessThanEqual(pos, emrRtLightChange[1].xyz));
	int kn = emrRt_blocklight_spatialMin > 0 && m > float(emrRt_blocklight_mcap) && !changed ? min(K, emrRt_blocklight_spatialMin) : K;
	vec3 nq[K], qq[K]; // the used neighbours' surfaces, for the bias correction
	float mqs[K];
	int sel = -1; // which neighbour the sample came from (-1: this pixel)
	float a0 = 6.2831853 * emrBlRand(seed);
	for (int j = 0; j < K; j++) mqs[j] = 0.0;
	for (int j = 0; j < kn; j++) {
		float a = a0 + 2.39996323 * float(j), rad = emrRt_blocklight_radius * sqrt((float(j) + emrBlRand(seed)) / float(kn));
		ivec2 qp = p + ivec2(round(rad * vec2(cos(a), sin(a))));
		if (any(lessThan(qp, ivec2(0))) || any(greaterThanEqual(qp, size)) || qp == p) continue;
		vec4 gq = imageLoad(emrBlG, qp);
		vec3 pq = emrBlPixelPos(qp, size, gq.w);
		// a pixel radius spans blocks on far or grazing surfaces: a neighbour lit by other sources there made blotches that the
		// temporal history carried on for many frames
		if (!emrBlSimilar(g, pos, gq, pq) || emrRt_blocklight_reach > 0.0 && distance(pq, pos) > emrRt_blocklight_reach) continue;
		if ((imageLoad(emrBlU, qp).a > 1.5) != emrBlTwoSided) continue; // a plant beside a wall in its plane: other light support
		uvec4 rq = imageLoad(emrBlRes, qp);
		if (rq.x == ~0u) continue;
		float mq = uintBitsToFloat(rq.w), tq = emrBlTarget(rq.x, q, n), wq = tq * uintBitsToFloat(rq.z) * mq;
		m += mq;
		mqs[j] = mq;
		nq[j] = gq.xyz;
		qq[j] = pq + gq.xyz * 0.02;
		if (wq <= 0.0) continue;
		wsum += wq;
		if (emrBlRand(seed) * wsum < wq) {
			r = rq;
			target = tq;
			sel = j;
		}
	}
	float z = m;
	if (emrRt_blocklight_bias != 0 && r.x != ~0u && target > 0.0) {
		// Z: this pixel's M, plus each neighbour's where the chosen sample has a target and is visible there (the neighbour it
		// came from saw it: its W > 0)
		z = m;
		vec2 dk = emrBlDiskOf(r);
		for (int j = 0; j < kn; j++) {
			if (mqs[j] <= 0.0 || j == sel) continue;
			vec4 lj = emrBlSourceAt(r.x, qq[j], nq[j]);
			if (emrBlWeight(lj, qq[j], nq[j]) <= 0.0 || emrBlLum(emrBlRay(qq[j], nq[j], emrBlSource(r.x), emrBlDiskPoint(lj, qq[j], dk))) <= 0.0) z -= mqs[j];
		}
	}
	float w = target > 0.0 && wsum > 0.0 ? wsum / (z * target) : 0.0;
	vec3 s = vec3(0.0);
	bool visible = false;
	if (r.x != ~0u && w > 0.0) {
		vec4 l = emrBlSourceAt(r.x, q, n);
		vec3 v = emrBlRay(q, n, emrBlSource(r.x), emrBlDiskPoint(l, q, emrBlDiskOf(r)));
		visible = emrBlLum(v) > 0.0;
		s = emrBlColor(r.x) * emrBlWeight(l, q, n) * v * w;
	}
	imageStore(emrBlR, p, vec4(s / max(u.rgb, vec3(1e-5)), 1.0));
	imageStore(emrBlResH, p, r.x == ~0u ? EMR_BL_EMPTY : emrBlPack(r.x, emrBlDiskOf(r), visible ? w : 0.0, min(m, float(emrRt_blocklight_mcap))));
}
