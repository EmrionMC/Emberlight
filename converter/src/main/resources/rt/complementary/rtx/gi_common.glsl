// RTX GI, appended after gi_common.glsl: rays take on stained-glass colour, hits are lit by one weighted block light and add
// last frame's indirect light (multiple bounces over frames). Channel C carries block light in RTX units.

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

// Transmittance to o + d * tMax (any hit): 0 behind opaque or alpha-tested terrain, glass colours multiplied (lava never blocks).
vec3 emrGiTransmit(vec3 o, vec3 d, float tMax) {
	vec3 tint = vec3(1.0);
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT, 0x03u, o, 0.0, d, tMax);
	for (int guard = 0; guard < 48 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		EmrRtVerts cv = emrRtVerts(ci);
		uvec3 ct = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		int id = emrRtId(cv, ct.x);
		if (id == 10068 || id == 10070) continue;
		vec4 c = textureLod(emrRtAtlas, emrRtBaryUv(cv, ct, rayQueryGetIntersectionBarycentricsEXT(rq, false)), 0.0);
		if (emrRtSection[ci].z == 2u) {
			if (id != 32000) tint *= emrGiGlass(c * vec4(emrRtColor(cv, ct.x).rgb, 1.0));
		} else if (c.a >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	return rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? tint : vec3(0.0);
}

// This frame's primary surface along the view ray d from o, traced before the gbuffers so they never borrow last frame's GI:
// what the depth buffer will hold, solid and cutout terrain and entities (alpha-tested at mip 0). Not mask 0x08: the
// first-person body is in the TLAS for its shadow but not drawn, and its arms and held item hit from the eye covered half
// the screen with the wrong GI surface, a dark hand shape. n is the face normal, facing the camera like emrGiNormal.
bool emrGiPrimary(vec3 o, vec3 d, out float t, out vec3 n) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsNoneEXT, 0x05u, o, 0.0, d, 1024.0);
	for (int guard = 0; guard < 48 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		EmrRtVerts cv = emrRtVerts(ci);
		uvec3 ct = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		if (emrRtHitAlpha(ci, cv, ct, rayQueryGetIntersectionBarycentricsEXT(rq, false)) >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	t = 0.0;
	n = vec3(0.0);
	if (rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT) return false;
	t = rayQueryGetIntersectionTEXT(rq, true);
	EmrRtVerts v = emrRtVerts(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true));
	uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true)));
	vec3 p0 = emrRtPos(v, tri.x); // the triangle's own plane, as depth gives it (vertex normals of plants and entities differ)
	n = normalize(cross(emrRtPos(v, tri.y) - p0, emrRtPos(v, tri.z) - p0));
	if (!(dot(n, n) > 0.5)) n = -d; // !(>): NaN from a degenerate triangle
	if (dot(n, d) > 0.0) n = -n;
	return true;
}

// Block light leaving the surface at p (normal n, albedo): a light block's own glow, plus the light arriving there from the
// full source list (rtx/bl_common.glsl emrBlLightAt: one source drawn by its unshadowed contribution, one shadow ray; the
// temporal and spatial filters average the draws). Faded with distance like RTX block light.
vec3 emrGiBlockLight(vec3 p, vec3 n, vec3 albedo, inout uvec3 seed) {
	float fade = emrRtLightFade(p);
	if (fade <= 0.0) return vec3(0.0);
	uint s = emrBlHash(seed);
	seed.x = s;
	uint li, inside = emrBlSourceIn(p - n * 0.02); // the block behind the hit: a light block's face or a torch's model
	vec3 q = p + n * 0.02;
	float sum = emrBlPick(q, n, s, li);
	// no glow from a block only the pack's coloured light knows: its floodfill already lights the room (stems room +31 % -> +13 %)
	vec3 glow = inside == ~0u || emrBlPackOnly(inside) ? vec3(0.0) : emrBlColor(inside) * floor(emrBlPos[inside].w) / 15.0 * emrRt_gi_glow;
	if (li == ~0u) return glow * fade;
	vec3 c = emrBlColor(li);
	vec3 v = emrBlRay(q, n, emrBlSource(li), emrBlDiskPoint(emrBlSourceAt(li, q, n), q, emrBlDisk(vec2(emrBlRand(s), emrBlRand(s)))));
	return (glow + albedo * c / max(emrBlLum(c), 1e-6) * sum * v) * fade;
}

// One RTX bounce along d. false = no hit within tMax (sky; tint = the glass the ray passed). On a hit: sun, sky and block
// light leaving the hit towards the ray origin, already multiplied by the glass in between.
bool emrGiBounceRtx(vec3 o, vec3 d, float tMax, vec3 l, inout uvec3 seed, out vec3 sun, out vec3 sky, out vec3 blk, out vec3 tint) {
	sun = vec3(0.0);
	sky = vec3(0.0);
	blk = vec3(0.0);
	tint = vec3(1.0);
	float gt[4];
	vec3 gc[4];
	int glassN = 0;
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsNoneEXT, 0x03u, o, 0.0, d, tMax);
	for (int guard = 0; guard < 48 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		EmrRtVerts cv = emrRtVerts(ci);
		uvec3 ct = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		vec4 c = textureLod(emrRtAtlas, emrRtBaryUv(cv, ct, rayQueryGetIntersectionBarycentricsEXT(rq, false)), 0.0);
		if (emrRtSection[ci].z == 2u) { // translucent: remembered, applied below if in front of the closest opaque hit
			if (glassN < 4 && emrRtId(cv, ct.x) != 32000) {
				gt[glassN] = rayQueryGetIntersectionTEXT(rq, false);
				gc[glassN] = emrGiGlass(c * vec4(emrRtColor(cv, ct.x).rgb, 1.0));
				glassN++;
			}
		} else if (c.a >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	bool hit = rayQueryGetIntersectionTypeEXT(rq, true) != gl_RayQueryCommittedIntersectionNoneEXT;
	float tHit = hit ? rayQueryGetIntersectionTEXT(rq, true) : tMax;
	for (int i = 0; i < glassN; i++) if (gt[i] < tHit) tint *= gc[i];
	if (!hit) return false;
	int custom = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true);
	EmrRtVerts v = emrRtVerts(custom);
	uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true)));
	vec2 b = rayQueryGetIntersectionBarycentricsEXT(rq, true);
	vec3 w = vec3(1.0 - b.x - b.y, b.x, b.y);
	vec3 vtint = emrRtColor(v, tri.x).rgb * w.x + emrRtColor(v, tri.y).rgb * w.y + emrRtColor(v, tri.z).rgb * w.z;
	vec3 n = emrRtNormal(v, tri.x);
	if (dot(n, d) > 0.0) {
		if (emrRtSection[custom].z == 0u) return true;
		n = -n;
	}
	uint lw = emrRtWord(v, tri.x, 6u, 4u); // emrRtLight(v, i): hidden here by the light buffer's emrRtLight[]
	float skyLm = clamp(float(v.sod ? (lw >> 8u) & 0xFFu : lw >> 16u) / 240.0, 0.0, 1.0);
	vec3 albedo = textureLod(emrRtAtlas, emrRtBaryUv(v, tri, b), 2.0).rgb * vtint;
	albedo *= albedo;
	vec3 hitP = o + d * tHit;
	vec3 hp = hitP + n * 0.01;
	float ndl = dot(n, l);
	if (emrRtDim != -1 && ndl > 0.0) sun = albedo * ndl * emrGiTransmit(hp, l, 256.0);
	sky = albedo * emrGiHitSky(skyLm);
	blk = emrGiBlockLight(hp, n, albedo, seed);
	// last frame's indirect light at the hit (multiple bounces): where the hit is on screen and the history matches it
	vec3 prev = hitP + cameraPosition - previousCameraPosition;
	vec4 cp = gbufferPreviousProjection * (gbufferPreviousModelView * vec4(prev, 1.0));
	if (cp.w > 0.0) {
		ivec2 size = imageSize(emrGiHA);
		ivec2 q = ivec2(emrGiTexel(cp, vec2(size)));
		if (all(greaterThanEqual(q, ivec2(0))) && all(lessThan(q, size))) {
			vec4 g = imageLoad(emrGiGprev, q);
			float dist = length(prev);
			if (g.w > 0.0 && abs(g.w - dist) < 0.1 + 0.05 * dist && dot(g.xyz, n) > 0.7) {
				sun += albedo * imageLoad(emrGiHA, q).rgb * emrRt_gi_bounce;
				sky += albedo * imageLoad(emrGiHB, q).rgb * emrRt_gi_bounce;
				blk += albedo * imageLoad(emrGiHC, q).rgb * emrRt_gi_bounce;
			}
		}
	}
	sun *= tint;
	sky *= tint;
	blk *= tint;
	return true;
}
