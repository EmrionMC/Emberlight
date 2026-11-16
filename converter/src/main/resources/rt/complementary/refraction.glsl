// RT refraction for Complementary r5. gbuffers_water bends the view ray at each water fragment (and glass, if on), traces
// the hit and lights it with the pack's own DoLighting at the hit (sun with its RT or map shadow, sky, block light, GI),
// AO and fog, and writes the water over it as one opaque colour: nothing behind the surface is looked up on screen.
// composite1 only moves the pack's depth behind the surface to the traced path (for its fog and light shafts).

// Closest hit (mask 0x01 solid + cutout, 0x02 translucent, 0x04 entities); solids are alpha-tested, translucents always hit.
// Returns t < 0 on a miss.
float emrRtRfTrace(vec3 o, vec3 d, float tMin, float tMax, uint mask, out int inst, out uint prim, out vec2 bary) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsNoneEXT, mask, o, tMin, d, tMax);
	for (int guard = 0; guard < 32 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		EmrRtVerts v = emrRtVerts(ci);
		uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		// translucent candidates are always confirmed; terrain and entities alpha-tested (clear glass is alpha 0)
		if ((mask & 0x02u) != 0u || emrRtHitAlpha(ci, v, tri, rayQueryGetIntersectionBarycentricsEXT(rq, false)) >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	inst = 0; prim = 0u; bary = vec2(0.0);
	if (rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT) return -1.0;
	inst = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true);
	prim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true));
	bary = rayQueryGetIntersectionBarycentricsEXT(rq, true);
	return rayQueryGetIntersectionTEXT(rq, true);
}

// Whether anything lies on the ray within [tMin, tMax]: stops at the first hit. Terrain is alpha-tested as in emrRtRfTrace,
// entities are not: their triangles are the coverage, and their alpha test failed along the edges the rasteriser drew (the
// squid's tentacles), which dropped those pixels back to the unrefracted image: a second, displaced outline of the mob.
bool emrRtRfAny(vec3 o, vec3 d, float tMin, float tMax, uint mask) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT, mask, o, tMin, d, tMax);
	for (int guard = 0; guard < 32 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		if (emrRtIsEntity(ci) || emrRtHitAlpha(ci, emrRtVerts(ci), emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false))),
				rayQueryGetIntersectionBarycentricsEXT(rq, false)) >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	return rayQueryGetIntersectionTypeEXT(rq, true) != gl_RayQueryCommittedIntersectionNoneEXT;
}

// The nearest translucent face that is not water (glass, ice, slime, honey) within [tMin, tMax], t < 0 when none.
float emrRtRfGlass(vec3 o, vec3 d, float tMin, float tMax, out int inst, out uint prim, out vec2 bary) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsNoneEXT, 0x02u, o, tMin, d, tMax);
	for (int guard = 0; guard < 32 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		uint p = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false));
		EmrRtVerts v = emrRtVerts(ci);
		uvec3 tri = emrRtTri(p);
		if (emrRtId(v, tri.x) != 32000 && emrRtHitAlpha(ci, v, tri, rayQueryGetIntersectionBarycentricsEXT(rq, false)) > 0.001)
			rayQueryConfirmIntersectionEXT(rq);
	}
	inst = 0; prim = 0u; bary = vec2(0.0);
	if (rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT) return -1.0;
	inst = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true);
	prim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true));
	bary = rayQueryGetIntersectionBarycentricsEXT(rq, true);
	return rayQueryGetIntersectionTEXT(rq, true);
}

// Geometric normal of the hit triangle, facing against d (instances are pure translations).
vec3 emrRtRfNormal(int inst, uint prim, vec3 d) {
	EmrRtVerts v = emrRtVerts(inst);
	uvec3 tri = emrRtTri(prim);
	vec3 a = emrRtPos(v, tri.x);
	vec3 n = normalize(cross(emrRtPos(v, tri.y) - a, emrRtPos(v, tri.z) - a));
	return dot(n, d) > 0.0 ? -n : n;
}

vec3 emrRtRfWorld(vec3 viewPos) {
	return mat3(gbufferModelViewInverse) * viewPos + gbufferModelViewInverse[3].xyz;
}

// The refracted path from the surface point o (playerPos space) seen along d: nf the surface's flat face normal (world, either
// side), n the pack's wave normal (world). Water: the apparent-depth image (the world behind the surface scaled by eta along its
// normal), not Snell's law per pixel: Snell bends grazing and screen-edge rays far more than central ones, so straight floor
// edges curved into arcs that changed shape as the camera moved (a fisheye look); this image is an affine copy of the floor, as
// raised as real water shows it seen from above, and moves rigidly (upscale_motion.comp puts the virtual image at the same depth).
// The waves tilt the face normal only slightly: a stronger tilt bent the floor by up to half a block. Glass is a slab: the ray
// leaves parallel to d through the exit face, shifted by the path inside. Total internal reflection (false): the pack's
// reflection covers it.
bool emrRtRfPath(vec3 d, vec3 nf, vec3 n, bool water, inout vec3 o, out vec3 r, out float inside) {
	if (dot(nf, d) > 0.0) nf = -nf;
	if (dot(n, d) > 0.0) n = -n;
	inside = 0.0;
	if (water) nf = normalize(nf + (0.02 * emrRt_refraction_waves) * (n - nf));
	float eta = water ? (isEyeInWater == 1 ? emrRt_refraction_ior : 1.0 / emrRt_refraction_ior) : 1.0 / emrRt_refraction_glassIor;
	r = refract(d, nf, eta);
	if (r == vec3(0.0)) return false;
	if (water) {
		r = normalize(d + (1.0 / eta - 1.0) * dot(d, nf) * nf);
		return true;
	}
	int inst; uint prim; vec2 b;
	float te = emrRtRfTrace(o, r, 0.002, 4.0, 0x02u, inst, prim, b);
	if (te > 0.0) { o += r * te; inside = te; }
	r = d;
	return true;
}

// UV2 (block, sky) / 240 of vertex i; entities (EMRION_ENTITY) hold UV1 (overlay) at word 6 and UV2 at 7. Not emrRtLight(): the
// RTX block-light helper's light buffer hides that name.
vec2 emrRtRfLight(int ci, EmrRtVerts v, uint i) {
	uint l = emrRtSection[ci].z >= 8u ? v.p.d[i * 14u + 7u] : emrRtWord(v, i, 6u, 4u);
	return (v.sod ? vec2(l & 0xFFu, (l >> 8u) & 0xFFu) : vec2(l & 0xFFFFu, l >> 16u)) / 240.0;
}

// Base colour (albedo x vertex tint) of a traced hit at the mip the rasteriser would pick, its alpha, interpolated vertex
// colour alpha (the vanilla AO, separateAo) and lightmap (block, sky as the pack's lmCoord: UV2 / 240).
vec4 emrRtRfAlbedo(int ci, uint prim, vec2 b, vec3 dir, float pathLen, out float ao, out vec2 lm) {
	EmrRtVerts v = emrRtVerts(ci);
	uvec3 tri = emrRtTri(prim);
	vec3 w = vec3(1.0 - b.x - b.y, b.x, b.y);
	vec3 n = emrRtRfNormal(ci, prim, dir);
	// texels per pixel at this distance and slope (16 texels per block), one mip softer: the pack's water blurs the floor a
	// little, and the sharper floor flickered more in motion
	float lod = max(0.0, 1.0 + log2(16.0 * 2.0 * pathLen / (viewHeight * gbufferProjection[1][1] * max(abs(dot(n, dir)), 0.05))));
	vec2 uv = emrRtBaryUv(v, tri, b);
	vec4 albedo = emrRtSection[ci].z == 8u ? textureLod(emrRtEntityAtlas, uv, lod) : textureLod(emrRtAtlas, uv, lod);
	if (emrRtSection[ci].z == 1u) { // cutout: filtering mixed in the colour of clear texels (magenta): opaque texels only
		vec2 tc = uv * vec2(textureSize(emrRtAtlas, 0)) - 0.5, f = fract(tc);
		vec4 sum = vec4(0.0);
		for (int j = 0; j < 4; j++) {
			ivec2 o2 = ivec2(j & 1, j >> 1);
			vec4 t = texelFetch(emrRtAtlas, ivec2(floor(tc)) + o2, 0);
			float wt = (o2.x == 1 ? f.x : 1.0 - f.x) * (o2.y == 1 ? f.y : 1.0 - f.y) * step(0.1, t.a);
			sum += vec4(t.rgb, 1.0) * wt;
		}
		albedo.rgb = sum.rgb / max(sum.a, 1e-4);
	}
	vec4 col = emrRtColor(v, tri.x) * w.x + emrRtColor(v, tri.y) * w.y + emrRtColor(v, tri.z) * w.z;
	ao = col.a;
	lm = emrRtRfLight(ci, v, tri.x) * w.x + emrRtRfLight(ci, v, tri.y) * w.y + emrRtRfLight(ci, v, tri.z) * w.z;
	lm = clamp(lm, 0.0, 1.0);
	return vec4(albedo.rgb * col.rgb, albedo.a);
}

// RT AO at a hit, as the mod's RT AO pass computes it for the floor the straight view shows (rt_ao.comp: cosine rays of
// 1 block, open = mean (hit distance)^2, cutout hits nearer than 0.3 and fluids pass), but with a fixed set of directions
// instead of noise accumulated over frames: refracted pixels have no history of their own, and noise read as grain.
// Ray i: (hit distance)^2.
float emrRtRfAoRay(vec3 origin, vec3 t, vec3 bt, vec3 n, int i) {
	vec2 u = fract(vec2(0.5, 0.25) + float(i) * vec2(0.7548777, 0.5698403));
	float rr = sqrt(u.x), a = 6.2831853 * u.y;
	vec3 dir = normalize(t * (rr * cos(a)) + bt * (rr * sin(a)) + n * sqrt(max(0.0, 1.0 - u.x)));
	float tMin = 0.0;
	for (int pass = 0; pass < 3; pass++) {
		rayQueryEXT q;
		rayQueryInitializeEXT(q, emrRtTlas, gl_RayFlagsNoneEXT, 0x01u, origin, tMin, dir, 1.0);
		for (int guard = 0; guard < 16 && rayQueryProceedEXT(q); guard++) {
			if (rayQueryGetIntersectionTypeEXT(q, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
			if (rayQueryGetIntersectionTEXT(q, false) < 0.3) continue;
			int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(q, false);
			EmrRtVerts v = emrRtVerts(ci);
			uvec3 tr = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(q, false)));
			// mc_Entity.y = 1: a fluid (lava) is never confirmed, so the ray finds what lies behind it (rt_ao.comp: a second pass)
			if ((emrRtWord(v, tr.x, 8u, 5u) >> 16u) != 1u && emrRtHitAlpha(ci, v, tr, rayQueryGetIntersectionBarycentricsEXT(q, false)) >= 0.1)
				rayQueryConfirmIntersectionEXT(q);
		}
		if (rayQueryGetIntersectionTypeEXT(q, true) == gl_RayQueryCommittedIntersectionNoneEXT) return 1.0;
		float s = rayQueryGetIntersectionTEXT(q, true);
		// an opacity micromap commits opaque cutout texels without the test above: a near one passes as there, trace on behind it
		if (!(s < 0.3 && emrRtSection[rayQueryGetIntersectionInstanceCustomIndexEXT(q, true)].z == 1u)) return s * s;
		tMin = s + 0.001;
	}
	return 1.0;
}

float emrRtRfAo(vec3 p, vec3 n) {
	if (emrRt_refraction_aoRays <= 0) return 1.0;
	vec3 t = normalize(cross(n, abs(n.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
	vec3 bt = cross(n, t);
	vec3 origin = p + n * (0.004 + 0.002 * length(p));
	float open = 0.0;
	for (int i = 0; i < emrRt_refraction_aoRays; i++) open += emrRtRfAoRay(origin, t, bt, n, i);
	return pow(open / float(emrRt_refraction_aoRays), float(emrRt_refraction_aoStrength) * 0.004);
}

// Distance of the opaque surface behind this pixel (depthtex1), 0 where there is none (sky).
float emrRtRfDistBehind() {
	float z = texelFetch(depthtex1, ivec2(gl_FragCoord.xy), 0).r;
	if (z >= 1.0) return 0.0;
	vec4 v = gbufferProjectionInverse * vec4(vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), z) * 2.0 - 1.0, 1.0);
	return length(v.xyz / v.w);
}

// Light level the hit's block emits (at_midBlock.w, terrain only): AO never dims a light, as in rt_ao.comp.
float emrRtRfEmission(int ci, uint prim) {
	EmrRtVerts v = emrRtVerts(ci);
	uint i = emrRtTri(prim).x;
	return v.sod ? float(v.p.d[i * 10u + 9u] >> 24u) : unpackHalf2x16(v.p.d[i * 14u + 13u]).y;
}

// What gbuffers_water traced for this fragment (emrRtRfFragment), for the fog distance and the final colour.
float emrRtRfT = -1.0, emrRtRfPathLen;
int emrRtRfInst;
uint emrRtRfPrim;
vec2 emrRtRfB;
vec3 emrRtRfO, emrRtRfR;
bool emrRtRfDone = false;
// While the pack's DoLighting lights a hit: its vanilla AO (< 0 = the fragment's own) and whether it takes the terrain's AO curve.
float emrRtRfAoIn = -1.0;
bool emrRtRfTerrain = false;

// Traces the refracted path of this water or glass fragment once (playerPos p, view-space flat and wave normals).
bool emrRtRfFragment(vec3 p, vec3 geoNormalView, vec3 normalView, bool water) {
	if (emrRtRfDone) return emrRtRfT > 0.0;
	emrRtRfDone = true;
	float lp = length(p);
	if (lp < 1e-4 || (!water && emrRt_refraction_glass == 0) || smoothstep(0.4 * far, 0.6 * far, lp) >= 1.0) return false;
	vec3 d = p / lp, o = p, r;
	float inside;
	if (!emrRtRfPath(d, mat3(gbufferModelViewInverse) * geoNormalView, mat3(gbufferModelViewInverse) * normalView, water, o, r, inside)) return false;
	uint mask = emrRt_refraction_entities != 0 ? 0x05u : 0x01u;
	int inst; uint prim; vec2 b;
	float t = emrRtRfTrace(o, r, 0.01, 256.0, mask, inst, prim, b);
	if (t < 0.0) return false; // nothing behind within the TLAS: the pack's unrefracted image
	// glass behind glass, or water behind glass: its colour is not known here, keep the pack's image
	int gi; uint gp; vec2 gb;
	if (!water && emrRtRfTrace(o, r, 0.01, t, 0x02u, gi, gp, gb) > 0.0) return false;
	// The surface the straight view shows behind this fragment must be in the TLAS (else, e.g. a section not built yet, keep it
	// unrefracted): checked on the straight ray, not by comparing the refracted hit's distance with it (that failed wherever the
	// bent ray legitimately passes a step edge or a squid the straight ray hits: a second copy of them).
	float behind = emrRtRfDistBehind(), tsMax = behind * 1.03 + 0.3 - lp - inside;
	if (behind > 0.0 && (tsMax <= 0.01 || !emrRtRfAny(o, d, 0.01, tsMax, mask))) return false;
	emrRtRfT = t; emrRtRfInst = inst; emrRtRfPrim = prim; emrRtRfB = b; emrRtRfO = o; emrRtRfR = r;
	emrRtRfPathLen = lp + inside + t;
	return true;
}
