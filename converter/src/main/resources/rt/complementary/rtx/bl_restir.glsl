// RTX block light, ReSTIR DI (Bitterli et al. 2020; NVIDIA RTXDI as the reference design): shared by its compute passes
// (rtx/bl_restir_*.csh, prepare_f..i), after bl_buffer.glsl and rtx/bl_common.glsl. They run before the gbuffers, on this
// frame's view (no depth yet: the surface of a pixel comes from its view ray), at the render resolution.
//
// Reservoir (rgba32ui): x = source (full-list index, ~0u = none), y = the sample's point on the source's disk (2 x unorm16),
// z = W (float bits, the unbiased contribution weight: the estimate is f(y) W), w = M (float bits, confidence).
// Images: emrBlG (normal, distance to the camera; 0 = no surface), emrBlU (U rgb, a = 1 where block light applies, 2 on a
// two-sided cutout surface),
// emrBlRes (this frame's reservoir after temporal reuse), emrBlResH (last frame's final reservoirs), emrBlR (raw ratio S / U,
// a = valid), emrBlT / emrBlRH (temporal accumulation, a = history length), emrBlOut (filtered ratio, read by the gbuffers),
// emrBlGH (last frame's geometry).

uint emrBlMask() { return emrRt_blocklight_entities != 0 ? 0x07u : 0x03u; } // rtx/bl_common.glsl

uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferPreviousModelView;
uniform mat4 gbufferPreviousProjection;
uniform vec3 cameraPosition;
uniform vec3 previousCameraPosition;
uniform int frameCounter;

const uvec4 EMR_BL_EMPTY = uvec4(~0u, 0u, 0u, 0u);

// The view ray through pixel p of an image of size `size` (through the near plane: finite with any far plane).
vec3 emrBlDir(ivec2 p, ivec2 size) {
	vec2 uv = (vec2(p) + 0.5) / vec2(size);
	vec4 v = gbufferProjectionInverse * vec4(uv * 2.0 - 1.0, -1.0, 1.0);
	return normalize(mat3(gbufferModelViewInverse) * (v.xyz / v.w));
}

// dist is the distance to the player (emrBlG.w), not along the view ray: the ray starts at the eye, off the player by the view bob.
vec3 emrBlPixelPos(ivec2 p, ivec2 size, float dist) {
	vec3 e = gbufferModelViewInverse[3].xyz, d = emrBlDir(p, size);
	float b = dot(e, d);
	return e + d * (sqrt(max(b * b - dot(e, e) + dist * dist, 0.0)) - b); // |e + d t| = dist
}

// emrBlDir before its normalize, as o + p.x * dx + p.y * dy: a perspective projection's inverse gives every (x, y, -1, 1)
// the same w (jitter included), so the ray is affine in the pixel. Filter taps use it (emrBlTapPos) instead of two matrix products.
struct EmrBlRays { vec3 o, dx, dy; };
EmrBlRays emrBlRays(ivec2 size) {
	vec2 s = 2.0 / vec2(size);
	vec4 v = gbufferProjectionInverse * vec4(s * 0.5 - 1.0, -1.0, 1.0); // pixel (0, 0)
	mat3 m = mat3(gbufferModelViewInverse);
	return EmrBlRays(m * (v.xyz / v.w), m * (gbufferProjectionInverse[0].xyz * (s.x / v.w)), m * (gbufferProjectionInverse[1].xyz * (s.y / v.w)));
}

vec3 emrBlTapPos(EmrBlRays r, ivec2 p, float dist) { // = emrBlPixelPos(p, size, dist)
	vec3 e = gbufferModelViewInverse[3].xyz, d = normalize(r.o + r.dx * float(p.x) + r.dy * float(p.y));
	float b = dot(e, d);
	return e + d * (sqrt(max(b * b - dot(e, e) + dist * dist, 0.0)) - b);
}

// The pixel (texel units) where playerPos-space point x was last frame.
bool emrBlPrevTexel(vec3 x, ivec2 size, out vec2 t) {
	vec4 c = gbufferPreviousProjection * (gbufferPreviousModelView * vec4(x + cameraPosition - previousCameraPosition, 1.0));
	t = (c.xy / c.w * 0.5 + 0.5) * vec2(size);
	return c.w > 0.0;
}

// This frame's primary surface along the view ray (as raster will draw it: solid and cutout terrain and entities, alpha-tested at
// mip 0; not the first-person body, mask 0x08), its face normal (facing the camera) and the vanilla block light there (0..1, the
// hit's vertex lightmap: the gbuffers' lightmap.x, for U's gate).
bool emrBlPrimary(vec3 o, vec3 d, out float t, out vec3 n, out float lm) {
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
	t = lm = 0.0;
	n = vec3(0.0);
	// more candidates than the guard: no surface (the gbuffers' fallback), not one that may lie behind the true one
	if (more || rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT) return false;
	t = rayQueryGetIntersectionTEXT(rq, true);
	int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true);
	EmrRtVerts v = emrRtVerts(ci);
	uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true)));
	vec3 p0 = emrRtPos(v, tri.x);
	n = normalize(cross(emrRtPos(v, tri.y) - p0, emrRtPos(v, tri.z) - p0));
	if (!(dot(n, n) > 0.5)) n = -d; // !(>): NaN from a degenerate triangle
	if (dot(n, d) > 0.0) n = -n;
	vec2 b = rayQueryGetIntersectionBarycentricsEXT(rq, true);
	vec3 w = vec3(1.0 - b.x - b.y, b.x, b.y);
	bool entity = emrRtIsEntity(ci); // EMRION_ENTITY: UV1 (overlay) is word 6, UV2 word 7
	// cutout plants and leaves (rtx/bl_common.glsl): the pack's foliage ids 10003 to 10031 as a bit set (refraction_water_out.glsl),
	// plus the glowing plants the pack also gives subsurfaceMode 1: cave vines (10628-10635) and torchflowers (10768-10771). Their
	// cross quads lie edge-on to a berry in the same column, so one-sided they got no N.L and the next vine blocked every ray;
	// not trapdoors, bars or grates, whose back face would let the light through them
	int id = entity ? 0 : emrRtId(v, tri.x);
	emrBlTwoSided = !entity && emrRtSection[ci].z == 1u && (uint(id - 10003) < 29u && (0x141553F5u >> uint(id - 10003) & 1u) != 0u
		|| uint(id - 10628) < 8u || uint(id - 10768) < 4u);
	for (int k = 0; k < 3; k++) {
		uint i = tri[k];
		uint l = entity ? v.p.d[i * 14u + 7u] : emrRtWord(v, i, 6u, 4u);
		lm += w[k] * float(v.sod && !entity ? l & 0xFFu : l & 0xFFFFu);
	}
	lm = clamp(lm / 240.0, 0.0, 1.0);
	return true;
}

// The geometry tests of reuse: q's surface (normal and distance, from emrBlG) lies on the same plane as p's.
bool emrBlSimilar(vec4 g, vec3 pos, vec4 gq, vec3 pq) {
	return gq.w > 0.0 && dot(gq.xyz, g.xyz) > 0.9 && abs(dot(g.xyz, pq - pos)) < 0.05 + 0.02 * g.w;
}

uvec4 emrBlPack(uint li, vec2 d, float w, float m) {
	return uvec4(li, packUnorm2x16(d * 0.5 + 0.5), floatBitsToUint(w), floatBitsToUint(m));
}

vec2 emrBlDiskOf(uvec4 r) {
	return unpackUnorm2x16(r.y) * 2.0 - 1.0;
}

// A reservoir from an image: index checked against this frame's list (last frame's indices go through the remap table when
// the list was rebuilt), W and M finite. Garbage (a new image, a resized one) reads as empty.
uvec4 emrBlValid(uvec4 r, bool remap) {
	float w = uintBitsToFloat(r.z), m = uintBitsToFloat(r.w);
	if (remap && r.x < 16384u) r.x = emrBlRemap[r.x];
	if (r.x >= emrBlCount() || !(w >= 0.0 && w < 1e30) || !(m > 0.0 && m < 1e6)) return EMR_BL_EMPTY;
	return r;
}
