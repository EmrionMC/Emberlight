// RTX block light, shared library (after bl_buffer.glsl): the full source list, each source's unshadowed weight, shadow rays
// that take on the colour of stained glass, and one-sample estimates of the light at a point. Used by the ReSTIR passes
// (rtx/bl_restir_*.csh), RTX GI (bounce hits, rtx/gi_common.glsl) and the gbuffers' fallback (rtx/blocklight.glsl): every pass
// sees the same sources with the same weights. RAYTRACING.md §32.
//
// Light units ("RTX block-light units"): source i at p contributes colour_i^2 * power_i * weight_i(p, n) * visibility, where
// weight = level/15 * window * 1/(1 + 0.2 d^2) * wrapped N.L; a level-15 white vanilla source right at p gives 1.

const float EMR_BL_WRAP = 0.5;    // wrapped N.L (wrap 0.5, with strength 1.9 keeps the pack's mean level in a torch hall, §23)
const float EMR_BL_FALLOFF = 0.2; // 1 / (1 + k d^2)
const float EMR_BL_SIZE = 1.6;    // a source is sampled over a disk of this times its radius (0.45 full blocks, 0.15 others)
const float EMR_BL_GLASS = 1.6;   // how strongly stained glass colours the light passing it
const float EMR_BL_FLUSH_NDL = 0.6; // N.L floor for floors and ceilings lit by a flush emitter (single lava / sea lantern / glowstone cell within 5 % of RT off)

// The surface is a cutout plant (crops, grass, flowers, leaves): lit from both sides, as the pack and vanilla light plants
// (shade_direction_override up). One-sided, every crop quad facing away from the lamps lost all block light and a lit farm
// went dark olive at night. Set per pixel by the ReSTIR passes (bl_restir.glsl emrBlPrimary, emrBlU.a) and for the pack's
// foliage in the gbuffers' fallback (rtx/blocklight_apply_terrain.glsl); false elsewhere.
bool emrBlTwoSided = false;
const float EMR_BL_PLANT_NDL = 0.6; // N.L floor there: vanilla shades plants as facing up, the pack ignores their direction

float emrBlLum(vec3 c) {
	return dot(c, vec3(0.2125, 0.7154, 0.0721));
}

// PCG-RXS-M-XS: uniform [0, 1).
float emrBlRand(inout uint s) {
	s = s * 747796405u + 2891336453u;
	uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
	return float(((w >> 22u) ^ w) >> 8u) / 16777216.0;
}

uint emrBlHash(uvec3 v) {
	uint h = v.x * 1664525u + v.y * 22695477u + v.z * 2891336453u + 1013904223u;
	h ^= h >> 16u;
	h *= 0x7feb352du;
	h ^= h >> 15u;
	h *= 0x846ca68bu;
	return h ^ (h >> 16u);
}

uint emrBlCount() {
	return uint(emrRtLightOrigin.w);
}

// Source i: block centre in playerPos space, w = range level + source radius.
vec4 emrBlSource(uint i) {
	vec4 l = emrBlPos[i];
	return vec4(l.xyz + emrRtLightOrigin.xyz, l.w);
}

// Where source i lights p (surface normal n) from: its block centre, or for a source only the pack's coloured light knows,
// the air in front of the point of its block nearest p, half a block out along n. The pack's floodfill starts in the air next
// to such a block, so an ore set flush in a wall or floor lights that surface around it; from the block itself the light
// grazed the surface (a third of the pack's halo, darker cells) and from its centre it lay behind the face. Shadow rays still
// take the block from emrBlSource (emrBlRay). A full vanilla emitter set flush into p's surface (a lava pool in the floor, its
// top 1/9 below it; glowstone in a wall) lights it the same way: its centre lay behind the surface, every shadow ray went into
// the floor, and the lava cave was 40 % darker than RT off. Its disk shrinks to stay in front of the surface (radius 0.45:
// a third of the disk lay behind it and shadowed the halo).
vec4 emrBlSourceAt(uint i, vec3 p, vec3 n) {
	vec4 l = emrBlSource(i);
	float r = fract(l.w);
	if (emrBlCol[i].a < 0.0) return vec4(l.xyz + clamp(p - l.xyz, -r, r) + n * 0.5, l.w);
	float h = dot(n, l.xyz - p);
	if (r < 0.3 || h >= -0.05 || h < -0.7) return l;
	return vec4(l.xyz + clamp(p - l.xyz, -r, r) + n * 0.5, floor(l.w) + 0.28);
}

// Colour as it lights (squared: the pack adds block light before a square root) times the source's power.
vec3 emrBlColor(uint i) {
	vec4 c = emrBlCol[i];
	return c.rgb * c.rgb * abs(c.a);
}

// A source only the pack's coloured light knows (ores, lit repeaters and wire, ...): no vanilla light level.
bool emrBlPackOnly(uint i) {
	return emrBlCol[i].a < 0.0;
}

// Unshadowed weight of source l at p: (level / 15) / (1 + k d^2), windowed to 0 at its range, times a wrapped N.L.
float emrBlWeight(vec4 l, vec3 p, vec3 n) {
	float level = floor(l.w);
	vec3 dv = l.xyz - p;
	float d = length(dv);
	float r = d / max(level, 1.0);
	float win = max(1.0 - r * r, 0.0);
	float nd = dot(n, dv / max(d, 1e-3));
	float ndl = emrBlTwoSided ? max((abs(nd) + EMR_BL_WRAP) / (1.0 + EMR_BL_WRAP), EMR_BL_PLANT_NDL) : clamp((nd + EMR_BL_WRAP) / (1.0 + EMR_BL_WRAP), 0.0, 1.0);
	// A floor or ceiling around a flush emitter (lava pool, sea lantern or glowstone set into it, emrBlSourceAt) sees it at a
	// grazing angle, but the pack's light there has no direction: N.L at least EMR_BL_FLUSH_NDL. Not on walls: a glowstone wall's
	// own surface is already brighter than RT off. RAYTRACING.md §37.
	float fr = fract(l.w);
	if (fr > 0.25 && fr < 0.3 && abs(n.y) > 0.7) ndl = max(ndl, EMR_BL_FLUSH_NDL);
	return level / 15.0 * win * win / (1.0 + EMR_BL_FALLOFF * d * d) * ndl;
}

// The sources whose range reaches p's cell: emrBlIndex[first .. end). False outside the grid or with none.
bool emrBlCellRange(vec3 p, out uint first, out uint end) {
	ivec3 dims = emrRtLightDims.xyz, c = ivec3(floor((p - emrRtLightOrigin.xyz) / float(emrRtLightDims.w)));
	first = end = 0u;
	if (any(lessThan(c, ivec3(0))) || any(greaterThanEqual(c, dims))) return false;
	uint ci = uint((c.y * dims.z + c.z) * dims.x + c.x);
	first = emrBlCellStart[ci];
	end = emrBlCellStart[ci + 1u];
	return end > first;
}

vec3 emrBlGlass(vec4 c) {
	vec3 hue = c.rgb / max(max(c.r, c.g), max(c.b, 1e-3)); // the glass's own hue: the pack's light is already coloured
	return mix(vec3(1.0), hue, clamp(c.a * EMR_BL_GLASS, 0.0, 1.0));
}

// The instance mask of the visibility rays: 0x03 terrain, 0x07 with entities and block entities (blocklight:entities). Defined by
// the includer: rtx/blocklight.glsl and bl_restir.glsl from the blocklight option, rtx/gi_common.glsl terrain only.
uint emrBlMask();

// Transmittance along the ray: 0 behind opaque terrain (lava too: it is in the solid BLAS, opaque geometry; the ray ends at
// the source's block, so a pool does not shadow itself) and, with emrBlMask 0x04, behind entities and block entities (chests,
// signs, beds), times the colour of every translucent face passed. More candidates than the guard count as blocked.
vec3 emrBlTransmit(vec3 origin, vec3 dir, float tMax) {
	vec3 tint = vec3(1.0);
	rayQueryEXT rq;
	// a plant's light passes other plants (instance mask 0x20: solid terrain only): a lit crop field or meadow is one scattering
	// volume, which the pack's flood light shows; one-bounce shadows of every blade left all but the nearest plants dark
	uint mask = emrBlTwoSided ? (emrBlMask() & ~0x01u) | 0x20u : emrBlMask();
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT, mask, origin, 0.0, dir, tMax);
	bool more;
	for (int guard = 0; (more = rayQueryProceedEXT(rq)) && guard < 48; guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		EmrRtVerts v = emrRtVerts(ci);
		uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		if (emrRtIsEntity(ci)) {
			if (emrRtHitAlpha(ci, v, tri, rayQueryGetIntersectionBarycentricsEXT(rq, false)) >= 0.1) rayQueryConfirmIntersectionEXT(rq);
			continue;
		}
		vec4 c = textureLod(emrRtAtlas, emrRtBaryUv(v, tri, rayQueryGetIntersectionBarycentricsEXT(rq, false)), 0.0);
		if (emrRtSection[ci].z == 2u) tint *= emrBlGlass(c * vec4(emrRtColor(v, tri.x).rgb, 1.0));
		else if (c.a >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	return !more && rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? tint : vec3(0.0);
}

// Visibility of target (a point of source l's area) from p. The ray ends where it enters the source's block, so the emitter's
// own model never shadows it; a point beside the block (the disk is wider than a full block) ends at the point itself, else
// the ray crossed the room and creased the light where p crossed the block's face planes (§26).
vec3 emrBlRay(vec3 p, vec3 n, vec4 l, vec3 target) {
	if (emrBlTwoSided && dot(target - p, n) < -0.02) { // light from behind a plant (p is 0.02 in front of it): start on its far side
		p -= n * 0.04;
		n = -n;
	}
	vec3 d = normalize(target - p);
	vec3 inv = 1.0 / d, t0 = (l.xyz - 0.5 - p) * inv, t1 = (l.xyz + 0.5 - p) * inv, tn = min(t0, t1), tf = max(t0, t1);
	float enter = max(max(tn.x, tn.y), tn.z);
	bool hit = enter < min(min(tf.x, tf.y), tf.z);
	if (hit && enter <= 0.02) return vec3(1.0);
	if (dot(d, n) <= (hit ? -0.2 : 0.0)) return vec3(0.0); // grazing rays into the face of a flush source (emrBlSourceAt) are traced
	return emrBlTransmit(p, d, hit ? enter - 0.01 : length(target - p));
}

// A point of the unit disk, uniform over its area, from two uniform numbers.
vec2 emrBlDisk(vec2 u) {
	float a = 6.2831853 * u.x;
	return sqrt(u.y) * vec2(cos(a), sin(a));
}

// The point d (unit disk) of source l's disk facing p: radius EMR_BL_SIZE x the source radius (continuous basis, Duff et al. 2017).
vec3 emrBlDiskPoint(vec4 l, vec3 p, vec2 d) {
	vec3 w = normalize(l.xyz - p);
	float sg = w.z >= 0.0 ? 1.0 : -1.0, a = -1.0 / (sg + w.z), b = w.x * w.y * a;
	vec3 t = vec3(1.0 + sg * w.x * w.x * a, sg * b, -sg * w.x), u = vec3(b, sg + w.y * w.y * a, -w.y);
	return l.xyz + fract(l.w) * EMR_BL_SIZE * (d.x * t + d.y * u);
}

// Target function of resampling: source i's unshadowed contribution at p (luminance).
float emrBlTarget(uint i, vec3 p, vec3 n) {
	return emrBlLum(emrBlColor(i)) * emrBlWeight(emrBlSourceAt(i, p, n), p, n);
}

// The source whose block holds p (~0u if none): one hash probe, not a scan of the cell (RtLights.blockSlot).
uint emrBlSourceIn(vec3 p) {
	ivec3 b = ivec3(floor(p - emrRtLightOrigin.xyz));
	uint slot = emrBlHash(uvec3(b)) & 32767u;
	for (int guard = 0; guard < 64; guard++, slot = (slot + 1u) & 32767u) {
		uint e = emrBlBlock[slot];
		if (e == 0u) break;
		if (ivec3(floor(emrBlPos[e - 1u].xyz)) == b) return e - 1u;
	}
	return ~0u;
}

// Draws one source of p's cell with probability proportional to its target (weighted reservoir sampling). Up to 16 sources
// it is exact; a longer list (dense ore veins, a lit storage hall) gets 16 candidates drawn uniformly, resampled by target
// (RIS), so the cost stays flat. Returns the estimate's scale: the sum of the targets (exact), or N / 16 times the
// candidates' sum (RIS); 0 when no source reaches p. li = the source. fullMax = the largest target among the full-block
// vanilla emitters the pack does not sum (not lava or magma, RtLights), fullSum = their summed targets (scaled like the return value).
float emrBlPick(vec3 p, vec3 n, inout uint seed, out uint li, out float fullMax, out float fullSum) {
	li = ~0u;
	fullMax = fullSum = 0.0;
	uint first, end;
	if (!emrBlCellRange(p, first, end)) return 0.0;
	uint count = end - first, m = min(count, 16u);
	float sum = 0.0;
	uint h = emrBlHash(uvec3(seed, first, end));
	seed = h;
	for (uint j = 0u; j < m; j++) {
		uint k = first + j;
		if (count > 16u) {
			h = h * 747796405u + 2891336453u;
			k = first + min(uint(float(h >> 8u) / 16777216.0 * float(count)), count - 1u);
		}
		uint i = emrBlIndex[k];
		float t = emrBlLum(emrBlColor(i)) * emrBlWeight(emrBlSourceAt(i, p, n), p, n);
		if (t <= 0.0) continue;
		sum += t;
		if (!emrBlPackOnly(i) && fract(emrBlPos[i].w) > 0.445) { // full, not summed by the pack (radius 0.44: lava, magma)
			fullMax = max(fullMax, t);
			fullSum += t;
		}
		h = h * 747796405u + 2891336453u;
		if (float(h >> 8u) / 16777216.0 * sum < t) li = i;
	}
	fullSum *= float(count) / float(m);
	return sum * float(count) / float(m);
}

// Block light arriving at p (playerPos space; n = the surface normal, facing where the light comes from): one source drawn
// by its unshadowed contribution among all sources in reach, one shadow ray to a random point of its area. An unbiased
// estimate of S = sum of colour^2 * power * weight(p, n) * visibility in RTX block-light units (see the top); average it over
// frames or pixels. Stained glass colours it. For a stable result, a caller accumulates it (GI does, temporally and
// spatially). The stable interface for other passes (RAYTRACING.md §32); include bl_buffer.glsl and this file.
vec3 emrBlLightAt(vec3 p, vec3 n, inout uint seed) {
	uint li;
	float fullMax, fullSum;
	vec3 q = p + n * 0.02;
	float sum = emrBlPick(q, n, seed, li, fullMax, fullSum);
	if (li == ~0u) return vec3(0.0);
	vec3 c = emrBlColor(li);
	vec3 v = emrBlRay(q, n, emrBlSource(li), emrBlDiskPoint(emrBlSourceAt(li, q, n), q, emrBlDisk(vec2(emrBlRand(seed), emrBlRand(seed)))));
	return c / max(emrBlLum(c), 1e-6) * sum * v;
}

// The same without shadows, exact (every source in reach summed).
vec3 emrBlLightOpen(vec3 p, vec3 n) {
	uint first, end;
	vec3 q = p + n * 0.02, s = vec3(0.0);
	if (!emrBlCellRange(q, first, end)) return s;
	for (uint k = first; k < end; k++) {
		uint i = emrBlIndex[k];
		s += emrBlColor(i) * emrBlWeight(emrBlSourceAt(i, q, n), q, n);
	}
	return s;
}

// U, the normaliser of RTX block light: the sources unshadowed and as if facing p, in the colour they light with. A source
// behind the surface never lights it, and the pack's light holds it only where it flows around an edge (the far side of a
// pillar): sources behind count only as far as the pack's vanilla level lm (0..1) exceeds what the vanilla sources in front
// give it in vanilla terms (level - L1 distance from the air block in front, one level of slack; §23). Sources only the pack
// knows have no vanilla level and never explain it. A vanilla emitter flush with the surface (emrBlSourceAt: a lava pool in the
// floor) sits between the two: gated by the sources in front (a lava pool in the next room's floor, behind a wall, darkened a
// torch-lit floor here), and it explains lm for the sources behind (the lava falling on below a pool floor).
vec3 emrBlOpen(vec3 p, vec3 n, float lm, uint first, uint end) {
	vec3 front = vec3(0.0), flush = vec3(0.0), back = vec3(0.0);
	vec3 q = p + n * 0.5; // the air block in front, where the lightmap value lives
	float best = -99.0, bestFlush = -99.0;
	for (uint k = first; k < end; k++) {
		uint i = emrBlIndex[k];
		vec4 l = emrBlSourceAt(i, p, n);
		vec3 u = emrBlColor(i) * emrBlWeight(l, p, normalize(l.xyz - p));
		vec3 d = abs(l.xyz - q);
		if (l.w != emrBlPos[i].w) { // flush: emrBlSourceAt shrank its disk
			flush += u;
			bestFlush = max(bestFlush, floor(l.w) - (d.x + d.y + d.z));
		} else if (emrBlTwoSided || dot(n, l.xyz - p) >= -0.05) { // -0.05: a pack-only source's face flush with the surface is in front
			front += u;
			if (!emrBlPackOnly(i)) best = max(best, floor(l.w) - (d.x + d.y + d.z));
		} else back += u;
	}
	return front + flush * clamp((lm * 15.0 - best - 1.0) * 0.5, 0.0, 1.0)
		+ back * clamp((lm * 15.0 - max(best, bestFlush) - 1.0) * 0.5, 0.0, 1.0);
}
