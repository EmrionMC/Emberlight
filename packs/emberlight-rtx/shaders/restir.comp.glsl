// ReSTIR DI, first half (lib/restir.glsl): initial candidates + visibility + temporal reuse, per half-res pixel.
#include "/lib/restir.glsl"

void main() {
	ivec2 hp = ivec2(gl_GlobalInvocationID.xy);
	if (any(greaterThanEqual(hp, imageSize(giRaw_img)))) return;
	ivec2 px;
	vec3 world, p;
	GNormal g;
	if (!rsSurface(hp, px, world, p, g)) { rsStore(hp, 0, RS_EMPTY); return; }
	vec3 n = g.geo;
	Rng rng = rngInit(hp, 5);

	// ---- initial candidates: uniform over the cell's sources (every one of them when they are few), random face point ----
	RsStream s = RS_NONE;
	uvec2 r = el_lightCell(p);
	uint count = r.y - r.x;
	if (count > 0u) {
		int cand = int(min(count, uint(LIGHT_SAMPLES)));
		bool every = count <= uint(LIGHT_SAMPLES);
		for (int i = 0; i < cand; i++) {
			uint k = every ? r.x + uint(i) : r.x + min(uint(rnd(rng) * float(count)), count - 1u);
			int li = el_lightIndex(k);
			ElLight L = el_light(li);
			vec3 off = lightFacePoint(L.position, clamp(L.radius, 0.1, 0.5), p, vec3(rnd2(rng), rnd(rng))) - L.position;
			float ph = rsTarget(li, off, p, n), fp = rsFaceProb(p - L.position, off);
			// w = pHat / pdf, pdf = face probability / count, averaged over cand
			rsAdd(s, li, off, fp > 0.0 ? ph / fp * float(count) / float(cand) : 0.0, ph, rnd(rng), 0);
		}
	}
	// candidate visibility (Bitterli §5): an occluded pick keeps its confidence but no weight, so lights behind walls
	// (the next house's torches in the same cell) never spread to the neighbours
	Reservoir cur = RS_EMPTY;
	cur.pos = p; cur.n = n; cur.M = 1.0;
	if (s.light >= 0 && s.pHat > 0.0 && rsVisible(p, n, s.light, s.off)) {
		cur.light = s.light; cur.off = s.off; cur.W = s.wsum / s.pHat;
	}

	// ---- temporal reuse: last frame's final reservoir where this surface was ----
	vec2 prevUv = (vec2(px) + 0.5) / el_screenSize + cameraMotion(world);
	Reservoir t = RS_EMPTY;
	if (all(greaterThan(prevUv, vec2(0.0))) && all(lessThan(prevUv, vec2(1.0)))) {
		ivec2 hq = min(ivec2(prevUv * el_screenSize) / 2, imageSize(giRaw_img) - 1);
		t = rsLoad(hq, 1);
		t.pos += el_prevCameraPosition - el_cameraPosition; // into this frame's player space
		t.light = el_lightFromPrev(t.light); // a removed source: no sample, the confidence stays
		if (t.light < 0) t.W = 0.0;
		if (!rsSimilar(p, n, t.pos, t.n)) t = RS_EMPTY;
		t.M = min(t.M, RS_MCAP);
	}
	Reservoir d[RS_MAXD];
	d[0] = cur; d[1] = t;
	rsStore(hp, 0, rsMerge(d, t.M > 0.0 ? 2 : 1, true, rng));
}
