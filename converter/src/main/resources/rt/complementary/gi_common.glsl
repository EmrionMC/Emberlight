// RT global illumination, shared by the GI compute programs.
// Images (half or full resolution, emrRt_gi_res): R = raw trace, T/F = filter ping-pong, H = history, G = geometry.
// A = (sun bounce rgb [lightColor units], sky visibility), B = (sky bounce rgb [ambientColor units], history length),
// G = (geometric normal, distance to the camera; 0 = no surface).
uniform sampler2D depthtex0;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferPreviousProjection;
uniform mat4 gbufferPreviousModelView;
uniform vec3 cameraPosition;
uniform vec3 previousCameraPosition;
uniform vec3 shadowLightPosition;
uniform float viewWidth;
uniform float viewHeight;
uniform int frameCounter;

ivec2 emrGiFull(ivec2 p) {
	return min(ivec2((vec2(p) + 0.5) / emrRt_gi_res), ivec2(viewWidth, viewHeight) - 1);
}

// GI image coordinate (texel units) of a clip-space position.
vec2 emrGiTexel(vec4 c, vec2 size) {
	return (c.xy / c.w * 0.5 + 0.5) * size;
}

// RTX (its passes run before the gbuffers, without depth): the view ray through GI texel p, through the near plane (finite
// with any far plane), and the surface on it at distance dist (G.w).
vec3 emrGiDir(ivec2 p) {
	vec2 uv = (vec2(p) + 0.5) / floor(vec2(viewWidth, viewHeight) * emrRt_gi_res);
	vec4 v = gbufferProjectionInverse * vec4(uv * 2.0 - 1.0, -1.0, 1.0);
	return normalize(mat3(gbufferModelViewInverse) * (v.xyz / v.w));
}

// dist is the distance to the player (G.w), not along the view ray: the ray starts at the eye, off the player by the view bob.
vec3 emrGiPos(ivec2 p, float dist) {
	vec3 e = gbufferModelViewInverse[3].xyz, d = emrGiDir(p);
	float b = dot(e, d);
	return e + d * (sqrt(max(b * b - dot(e, e) + dist * dist, 0.0)) - b); // |e + d t| = dist
}

// emrGiDir before its normalize, as o + p.x * dx + p.y * dy: a perspective projection's inverse gives every (x, y, -1, 1)
// the same w (jitter included), so the ray is affine in the texel. Filter taps use it (emrGiTapPos) instead of two matrix products.
struct EmrGiRays { vec3 o, dx, dy; };
EmrGiRays emrGiRays() {
	vec2 s = 2.0 / floor(vec2(viewWidth, viewHeight) * emrRt_gi_res);
	vec4 v = gbufferProjectionInverse * vec4(s * 0.5 - 1.0, -1.0, 1.0); // texel (0, 0)
	mat3 m = mat3(gbufferModelViewInverse);
	return EmrGiRays(m * (v.xyz / v.w), m * (gbufferProjectionInverse[0].xyz * (s.x / v.w)), m * (gbufferProjectionInverse[1].xyz * (s.y / v.w)));
}

vec3 emrGiTapPos(EmrGiRays r, ivec2 p, float dist) { // = emrGiPos(p, dist)
	vec3 e = gbufferModelViewInverse[3].xyz, d = normalize(r.o + r.dx * float(p.x) + r.dy * float(p.y));
	float b = dot(e, d);
	return e + d * (sqrt(max(b * b - dot(e, e) + dist * dist, 0.0)) - b);
}

float emrPow32(float x) { // x^32 as five squarings
	x *= x; x *= x; x *= x; x *= x;
	return x * x;
}

vec3 emrGiPlayerPos(ivec2 fp, float z) {
	vec2 uv = (vec2(fp) + 0.5) / vec2(viewWidth, viewHeight);
	vec4 v = gbufferProjectionInverse * vec4(vec3(uv, z) * 2.0 - 1.0, 1.0);
	return mat3(gbufferModelViewInverse) * (v.xyz / v.w) + gbufferModelViewInverse[3].xyz;
}

vec3 emrGiPosAt(ivec2 fp) {
	fp = clamp(fp, ivec2(0), ivec2(viewWidth, viewHeight) - 1);
	return emrGiPlayerPos(fp, texelFetch(depthtex0, fp, 0).r);
}

// Geometric normal from depth: per axis the neighbour with the smaller step (no smearing across depth edges).
// At the screen border take the inner neighbour: a zero step gives a NaN normal, which passes every history test.

vec3 emrGiNormal(ivec2 fp, vec3 p) {
	vec3 r = emrGiPosAt(fp + ivec2(1, 0)) - p, l = p - emrGiPosAt(fp - ivec2(1, 0));
	vec3 u = emrGiPosAt(fp + ivec2(0, 1)) - p, d = p - emrGiPosAt(fp - ivec2(0, 1));
	ivec2 last = ivec2(viewWidth, viewHeight) - 1;
	vec3 dx = fp.x <= 0 ? r : fp.x >= last.x ? l : dot(r, r) < dot(l, l) ? r : l;
	vec3 dy = fp.y <= 0 ? u : fp.y >= last.y ? d : dot(u, u) < dot(d, d) ? u : d;
	vec3 n = normalize(cross(dx, dy));
	return dot(n, p) > 0.0 ? -n : n;
}

// Surface at half-res texel p: false for sky and the hand.
bool emrGiSurface(ivec2 p, out vec3 pos, out float z) {
	ivec2 fp = emrGiFull(p);
	z = texelFetch(depthtex0, fp, 0).r;
	pos = emrGiPlayerPos(fp, z);
	return z < 1.0 && z > 0.56;
}

uvec3 emrGiPcg(uvec3 v) {
	v = v * 1664525u + 1013904223u;
	v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
	v ^= v >> 16u;
	v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
	return v;
}

vec3 emrGiCosine(vec3 n, vec2 u) {
	vec3 t = normalize(cross(n, abs(n.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
	vec3 b = cross(n, t);
	float r = sqrt(u.x), a = 6.2831853 * u.y;
	return normalize(t * (r * cos(a)) + b * (r * sin(a)) + n * sqrt(max(0.0, 1.0 - u.x)));
}

float emrGiSmooth(float x) {
	return x * x * (3.0 - 2.0 * x);
}

// The Nether and End have no sky in the pack's lighting: its ambient lights every surface alike (ambientMult 1, no sky
// lightmap). There a ray ends after emrRt_gi_range blocks (a miss is that ambient, so open counts as local occlusion, not
// sky visibility) and a hit reflects the ambient in full. The Nether has no sun (lightColor 0): no shadow ray to it.
float emrGiTMax() {
	return emrRtDim == 0 ? 64.0 : emrRt_gi_range;
}

float emrGiHitSky(float skyLm) {
	return emrRtDim == 0 ? emrGiSmooth(skyLm) : 1.0;
}

// Ray queries cannot be passed to functions, so the alpha-test loop (mask 0x01 = solid + cutout terrain; cutout candidates
// tested at mip 0 like the shadow pass) is written out in both users. More candidates than the guard (no opacity micromaps,
// deep foliage) count as blocked: the rest may hold the blocker, and a leak is worse than a missing bounce.
bool emrGiVisible(vec3 o, vec3 d, float tMax) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT, 0x01u, o, 0.0, d, tMax);
	bool more;
	for (int guard = 0; (more = rayQueryProceedEXT(rq)) && guard < 32; guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		EmrRtVerts cv = emrRtVerts(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false));
		uvec3 ct = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		if (textureLod(emrRtAtlas, emrRtBaryUv(cv, ct, rayQueryGetIntersectionBarycentricsEXT(rq, false)), 0.0).a >= 0.1)
			rayQueryConfirmIntersectionEXT(rq);
	}
	return !more && rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT;
}

// One bounce along d: false = no hit within tMax (sky). On a hit, sun (lightColor units) and sky (ambientColor units)
// radiance leaving the hit surface towards the ray origin. A solid face hit from behind returns black (no leaks through
// walls whose outside face is all the BLAS has).
bool emrGiBounce(vec3 o, vec3 d, float tMax, vec3 l, out vec3 sun, out vec3 sky) {
	sun = vec3(0.0);
	sky = vec3(0.0);
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsNoneEXT, 0x01u, o, 0.0, d, tMax);
	bool more;
	for (int guard = 0; (more = rayQueryProceedEXT(rq)) && guard < 32; guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		EmrRtVerts cv = emrRtVerts(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false));
		uvec3 ct = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		if (textureLod(emrRtAtlas, emrRtBaryUv(cv, ct, rayQueryGetIntersectionBarycentricsEXT(rq, false)), 0.0).a >= 0.1)
			rayQueryConfirmIntersectionEXT(rq);
	}
	if (more) return true; // black, as inside a wall
	if (rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT) return false;
	int custom = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true);
	EmrRtVerts v = emrRtVerts(custom);
	uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true)));
	vec2 b = rayQueryGetIntersectionBarycentricsEXT(rq, true);
	vec3 w = vec3(1.0 - b.x - b.y, b.x, b.y);
	// vertex colour (biome tint, AO), UV2 (block, sky), face normal
	vec3 tint = emrRtColor(v, tri.x).rgb * w.x + emrRtColor(v, tri.y).rgb * w.y + emrRtColor(v, tri.z).rgb * w.z;
	vec3 n = emrRtNormal(v, tri.x);
	if (dot(n, d) > 0.0) {
		if (emrRtSection[custom].z == 0u) return true; // solid face from behind: inside a wall
		n = -n;                                         // cutout (plants, leaves): both sides lit
	}
	float skyLm = clamp(float(emrRtLight(v, tri.x).y) / 240.0, 0.0, 1.0);
	vec3 albedo = textureLod(emrRtAtlas, emrRtBaryUv(v, tri, b), 2.0).rgb * tint;
	albedo *= albedo; // the pack's colours are gamma-like; bounce energy uses linear albedo
	vec3 hit = o + d * rayQueryGetIntersectionTEXT(rq, true);
	float ndl = dot(n, l);
	if (emrRtDim != -1 && ndl > 0.0 && emrGiVisible(hit + n * 0.01, l, 256.0)) sun = albedo * ndl;
	sky = albedo * emrGiHitSky(skyLm);
	return true;
}
