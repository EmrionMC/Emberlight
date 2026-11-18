// RTX GI, appended after gi_common.glsl: rays take on stained-glass colour, hits are lit by one weighted block light and add
// last frame's indirect light (multiple bounces over frames). Channel C carries block light in RTX units.

uint emrBlMask() { return 0x03u; } // rtx/bl_common.glsl: the GI's block light at a bounce, terrain only

layout(rgba16f) uniform readonly image2D emrGiHA;
layout(rgba16f) uniform readonly image2D emrGiHB;
layout(rgba16f) uniform readonly image2D emrGiHC;
layout(rgba16f) uniform readonly image2D emrGiGprev;
float emrGiRand(inout uvec3 s) {
	s = emrGiPcg(s);
	return float(s.x & 0xFFFFu) / 65536.0;
}

vec3 emrGiGlass(vec4 c) {
	vec3 hue = pow(c.rgb / max(max(c.r, c.g), max(c.b, 1e-3)), vec3(1.5));
	return mix(vec3(1.0), hue, clamp(c.a * emrRt_gi_glass, 0.0, 1.0));
}

// Transmittance to o + d * tMax (any hit): 0 behind opaque or alpha-tested terrain (lava too: solid BLAS), glass colours
// multiplied. More candidates than the guard (no opacity micromaps, deep foliage) count as blocked: no light leaks.
vec3 emrGiTransmit(vec3 o, vec3 d, float tMax) {
	vec3 tint = vec3(1.0);
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT, 0x03u, o, 0.0, d, tMax);
	bool more;
	for (int guard = 0; (more = rayQueryProceedEXT(rq)) && guard < 48; guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		EmrRtVerts cv = emrRtVerts(ci);
		uvec3 ct = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		int id = emrRtId(cv, ct.x);
		vec4 c = textureLod(emrRtAtlas, emrRtBaryUv(cv, ct, rayQueryGetIntersectionBarycentricsEXT(rq, false)), 0.0);
		if (emrRtSection[ci].z == 2u) {
			if (id != 32000) tint *= emrGiGlass(c * vec4(emrRtColor(cv, ct.x).rgb, 1.0));
		} else if (c.a >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	return !more && rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? tint : vec3(0.0);
}

// This frame's primary surface along the view ray d from o, traced before the gbuffers so they never borrow last frame's GI:
// what the depth buffer will hold, solid and cutout terrain and entities (alpha-tested at mip 0). Not mask 0x08: the
// first-person body is in the TLAS for its shadow but not drawn, and its arms and held item hit from the eye covered half
// the screen with the wrong GI surface, a dark hand shape. n is the face normal, facing the camera like emrGiNormal.
// More candidates than the guard: no surface (the apply pass takes a neighbour's), not one that may lie behind the true one.
bool emrGiPrimary(vec3 o, vec3 d, out float t, out vec3 n) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsNoneEXT, 0x05u, o, 0.0, d, 1024.0);
	bool more;
	for (int guard = 0; (more = rayQueryProceedEXT(rq)) && guard < 48; guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		EmrRtVerts cv = emrRtVerts(ci);
		uvec3 ct = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		if (emrRtHitAlpha(ci, cv, ct, rayQueryGetIntersectionBarycentricsEXT(rq, false)) >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	t = 0.0;
	n = vec3(0.0);
	if (more || rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT) return false;
	t = rayQueryGetIntersectionTEXT(rq, true);
	EmrRtVerts v = emrRtVerts(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true));
	uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true)));
	vec3 p0 = emrRtPos(v, tri.x); // the triangle's own plane, as depth gives it (vertex normals of plants and entities differ)
	n = normalize(cross(emrRtPos(v, tri.y) - p0, emrRtPos(v, tri.z) - p0));
	if (!(dot(n, n) > 0.5)) n = -d; // !(>): NaN from a degenerate triangle
	if (dot(n, d) > 0.0) n = -n;
	return true;
}

// Block light leaving the surface at p (normal n, albedo): the light arriving there from the full source list
// (rtx/bl_common.glsl emrBlPick: one source drawn by its unshadowed contribution, one shadow ray; the temporal and spatial
// filters average the draws), faded with distance like RTX block light. Light blocks add nothing here: RTX block light
// (ReSTIR) already lights every surface from every emitter, so their glow at a bounce hit counted them twice, and the light
// "arriving" at a glowstone or lava face came mostly from itself and its neighbours in the same surface (§36's flush rule):
// a 45-block glowstone wall made the room +96 % brighter than RT off. Full-block vanilla emitters count as the strongest
// of them: they tile into walls, floors and pools, and vanilla light (which the pack's brightness follows) is the max over
// sources, so N such blocks light like one, not N times as much. Torches and lanterns, which stand apart, the pack-only
// sources and lava and magma (whose coloured floodfills the pack sums, LightColors.packSums) are summed. RAYTRACING.md §37.
vec3 emrGiBlockLight(vec3 p, vec3 n, vec3 albedo, inout uvec3 seed) {
	float fade = emrRtLightFade(p);
	if (fade <= 0.0) return vec3(0.0);
	uint inside = emrBlSourceIn(p - n * 0.02); // the block behind the hit: a light block's face or a torch's model
	if (inside != ~0u && !emrBlPackOnly(inside)) return vec3(0.0);
	uint s = emrBlHash(seed);
	seed.x = s;
	uint li;
	float fullMax, fullSum;
	vec3 q = p + n * 0.02;
	float sum = emrBlPick(q, n, s, li, fullMax, fullSum);
	if (li == ~0u) return vec3(0.0);
	if (!emrBlPackOnly(li) && fract(emrBlPos[li].w) > 0.445) sum *= min(fullMax / fullSum, 1.0); // fullSum >= the drawn target > 0
	vec3 c = emrBlColor(li);
	vec3 v = emrBlRay(q, n, emrBlSource(li), emrBlDiskPoint(emrBlSourceAt(li, q, n), q, emrBlDisk(vec2(emrBlRand(s), emrBlRand(s)))));
	return albedo * c / max(emrBlLum(c), 1e-6) * sum * v * fade;
}

// A bounce ray's closest surface: position (the ray's hit), normal facing the ray, linear albedo, sky lightmap, distance.
struct EmrGiHit {
	vec3 p;
	vec3 n;
	vec3 albedo;
	float skyLm;
	float t;
};

// Traces one RTX bounce ray along d: 0 = no hit within tMax (sky; tint = the glass the ray passed), 1 = the back of a solid
// face (inside a wall: no light; also more candidates than the guard, rather than sky or a surface behind the true hit),
// 2 = a lit surface h. tint = the glass in front of the hit (the nearest 4 faces: traversal order is unspecified).
int emrGiTraceRtx(vec3 o, vec3 d, float tMax, out EmrGiHit h, out vec3 tint) {
	h = EmrGiHit(vec3(0.0), vec3(0.0), vec3(0.0), 0.0, 0.0);
	tint = vec3(1.0);
	float gt[4];
	vec3 gc[4];
	int glassN = 0;
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsNoneEXT, 0x03u, o, 0.0, d, tMax);
	bool more;
	for (int guard = 0; (more = rayQueryProceedEXT(rq)) && guard < 48; guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		EmrRtVerts cv = emrRtVerts(ci);
		uvec3 ct = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		vec4 c = textureLod(emrRtAtlas, emrRtBaryUv(cv, ct, rayQueryGetIntersectionBarycentricsEXT(rq, false)), 0.0);
		if (emrRtSection[ci].z == 2u) { // translucent: remembered, applied below if in front of the closest opaque hit
			if (emrRtId(cv, ct.x) != 32000) {
				float tc = rayQueryGetIntersectionTEXT(rq, false);
				int j = glassN; // full: this face replaces the farthest one kept, if nearer
				if (glassN == 4) for (int i = 0; i < 4; i++) if (gt[i] > tc && (j == 4 || gt[i] > gt[j])) j = i;
				if (j < 4) {
					gt[j] = tc;
					gc[j] = emrGiGlass(c * vec4(emrRtColor(cv, ct.x).rgb, 1.0));
					glassN = max(glassN, j + 1);
				}
			}
		} else if (c.a >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	if (more) return 1;
	bool hit = rayQueryGetIntersectionTypeEXT(rq, true) != gl_RayQueryCommittedIntersectionNoneEXT;
	float tHit = hit ? rayQueryGetIntersectionTEXT(rq, true) : tMax;
	for (int i = 0; i < glassN; i++) if (gt[i] < tHit) tint *= gc[i];
	if (!hit) return 0;
	int custom = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true);
	EmrRtVerts v = emrRtVerts(custom);
	uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true)));
	vec2 b = rayQueryGetIntersectionBarycentricsEXT(rq, true);
	vec3 w = vec3(1.0 - b.x - b.y, b.x, b.y);
	vec3 vtint = emrRtColor(v, tri.x).rgb * w.x + emrRtColor(v, tri.y).rgb * w.y + emrRtColor(v, tri.z).rgb * w.z;
	vec3 n = emrRtNormal(v, tri.x);
	if (dot(n, d) > 0.0) {
		if (emrRtSection[custom].z == 0u) return 1;
		n = -n;
	}
	uint lw = emrRtWord(v, tri.x, v.ent ? 7u : 6u, 4u); // emrRtLight(v, i): hidden here by the light buffer's emrRtLight[]
	h.skyLm = clamp(float(v.sod ? (lw >> 8u) & 0xFFu : lw >> 16u) / 240.0, 0.0, 1.0);
	vec3 albedo = textureLod(emrRtAtlas, emrRtBaryUv(v, tri, b), 2.0).rgb * vtint;
	h.albedo = albedo * albedo;
	h.p = o + d * tHit;
	h.n = n;
	h.t = tHit;
	return 2;
}

// Direct light arriving at the hit h (irradiance, before its albedo): sun (lightColor units), sky (ambientColor units),
// block light (RTX units).
void emrGiDirectRtx(EmrGiHit h, vec3 l, inout uvec3 seed, out vec3 sun, out vec3 sky, out vec3 blk) {
	vec3 hp = h.p + h.n * 0.01;
	float ndl = dot(h.n, l);
	sun = emrRtDim != -1 && ndl > 0.0 ? ndl * emrGiTransmit(hp, l, 256.0) : vec3(0.0);
	sky = vec3(emrGiHitSky(h.skyLm));
	blk = emrGiBlockLight(hp, h.n, vec3(1.0), seed);
}

// One RTX bounce along d. false = no hit within tMax (sky; tint = the glass the ray passed). On a hit: sun, sky and block
// light leaving the hit towards the ray origin, already multiplied by the glass in between. With the radiance cache
// (gi:cache, rtx/gi_cache.glsl) the hit's whole incoming light (direct and every further bounce) comes from the cache where
// it has an entry; otherwise direct light is traced here and further bounces come from last frame's screen-space GI.
bool emrGiBounceRtx(vec3 o, vec3 d, float tMax, vec3 l, inout uvec3 seed, out vec3 sun, out vec3 sky, out vec3 blk, out vec3 tint) {
	sun = vec3(0.0);
	sky = vec3(0.0);
	blk = vec3(0.0);
	EmrGiHit h;
	int r = emrGiTraceRtx(o, d, tMax, h, tint);
	if (r == 0) return false;
	if (r == 1) return true;
	if (!emrGiCacheGet(h.p, h.n, h.t, false, sun, sky, blk)) {
		emrGiDirectRtx(h, l, seed, sun, sky, blk);
		// last frame's indirect light at the hit (multiple bounces): where the hit is on screen and the history matches it
		vec3 prev = h.p + cameraPosition - previousCameraPosition;
		vec4 cp = gbufferPreviousProjection * (gbufferPreviousModelView * vec4(prev, 1.0));
		if (cp.w > 0.0) {
			ivec2 size = imageSize(emrGiHA);
			ivec2 q = ivec2(emrGiTexel(cp, vec2(size)));
			if (all(greaterThanEqual(q, ivec2(0))) && all(lessThan(q, size))) {
				vec4 g = imageLoad(emrGiGprev, q);
				float dist = length(prev);
				if (g.w > 0.0 && abs(g.w - dist) < 0.1 + 0.05 * dist && dot(g.xyz, h.n) > 0.7) {
					sun += imageLoad(emrGiHA, q).rgb * emrRt_gi_bounce;
					sky += imageLoad(emrGiHB, q).rgb * emrRt_gi_bounce;
					blk += imageLoad(emrGiHC, q).rgb * emrRt_gi_bounce;
				}
			}
		}
	}
	vec3 f = h.albedo * tint;
	sun *= f;
	sky *= f;
	blk *= f;
	return true;
}
