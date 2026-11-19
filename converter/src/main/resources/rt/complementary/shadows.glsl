// RT sun/moon shadows for Complementary r5.

// Leaf receivers (shadow_receiver_terrain.glsl): terrain closer than this along the ray does not count, so a leaf's own
// faces and the leaves around it within a block do not shadow it (the pack lights leaves as subsurface; its map lets them
// pass with the leaves depth offset). Entities count from tMin (emrRtNearEntities): a tMin of 1 for the whole ray let no mob
// or player right next to a leaf shadow it, and only the TAA path did it, so the shadow quality changed what shadowed leaves.
float emrRtTerrainMin = 0.0;

// 1 = nothing opaque between origin and origin + dir * tMax. Cutout candidates are alpha-tested (mip 0, cutoff 0.1 like the
// shadow pass); mask 0x01 = solid + cutout terrain (translucent is 0x02 and never traced here), | 0x04 = entities.
float emrRtVisibilityMask(vec3 origin, vec3 dir, float tMin, float tMax, uint mask) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT, mask, origin, tMin, dir, tMax);
	bool more;
	for (int guard = 0; (more = rayQueryProceedEXT(rq)) && guard < 32; guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		EmrRtVerts v = emrRtVerts(ci);
		uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		if (!emrRtIsEntity(ci) && emrRt_shadows_skip(emrRtId(v, tri.x))) continue;
		if (emrRtHitAlpha(ci, v, tri, rayQueryGetIntersectionBarycentricsEXT(rq, false)) >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	// more candidates than the guard (deep foliage) count as blocked: the rest may hold the blocker (lit specks under canopies)
	return !more && rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
}

// Visibility v of a ray traced from max(tMin, emrRtTerrainMin), times the entities between tMin and there. (A separate short
// ray: skipping the near leaf faces inside one query spent its candidate budget on them, and a spent budget reads as blocked.)
float emrRtNearEntities(float v, vec3 origin, vec3 dir, float tMin, uint mask) {
	return v > 0.0 && emrRtTerrainMin > tMin && (mask & 0x0Cu) != 0u ? v * emrRtVisibilityMask(origin, dir, tMin, emrRtTerrainMin, mask & 0x0Cu) : v;
}

float emrRtVisibility(vec3 origin, vec3 dir, float tMin, float tMax) {
	return emrRtVisibilityMask(origin, dir, tMin, tMax, 0x01u);
}

// Inverse of the pack's GetShadowPos: shadow-map coordinates (with the pack's biases applied) back to player space,
// which is TLAS space (instances are placed relative to the camera).
vec3 emrRtShadowOrigin(vec3 shadowPos) {
	vec3 c = shadowPos * 2.0 - 1.0;
	c.z /= 0.2;
	c.xy *= (1.0 - shadowMapBias) / (1.0 - length(c.xy) * shadowMapBias);
	vec3 v = mat3(shadowProjectionInverse) * c + shadowProjectionInverse[3].xyz;
	return mat3(shadowModelViewInverse) * v + shadowModelViewInverse[3].xyz;
}

// The surface DoLighting is lighting, and the shadowPos.xy it got for it (complementary-r5.json sets both where DoLighting
// calls GetShadowPos; other callers, such as reflected surfaces, pass other positions and keep the old origin). shadowPos
// carries the pack's shadow-map biases, a normal offset of 0.12 blocks growing to a full block at 60 blocks plus walls lifted
// far away: rays from there skipped contact shadows (feet, grass, the ground under a leaf layer) and moved shadow edges,
// which showed once zoomed in. The lift here follows the pixel footprint (0.002 per block at 70 degrees FOV, less zoomed in).
// Leaves and plants keep the pack's origin (their own block must not shadow them, see emrRtTerrainMin).
vec3 emrRtRecvPos = vec3(0.0), emrRtRecvN = vec3(0.0);
vec2 emrRtRecvXY = vec2(-1.0);

vec3 emrRtRayOrigin(vec3 shadowPos) {
	return shadowPos.xy == emrRtRecvXY ? emrRtRecvPos + emrRtRecvN * (0.01 + 0.0029 * length(emrRtRecvPos) / gbufferProjection[1][1]) : emrRtShadowOrigin(shadowPos);
}

// Point in the unit disk from one noise value (spiral); the pack's noise rotates every frame under TAA.
vec2 emrRtDisk(float n) {
	float a = 6.2831853 * fract(n * 2.427);
	return vec2(cos(a), sin(a)) * sqrt(n);
}

// Sample i of a pixel's rays over the unit disk (Vogel spiral): rotated by n, the pack's noise, with the radius stratum
// jittered by m (emrRtDiskJitter). emrRtDisk(n + i / rays) put all of a pixel's rays on one spiral curve, so the penumbra
// took a few fixed levels per pixel and showed as wavy contour bands, plain to see once zoomed in.
vec2 emrRtDiskSample(int i, int rays, float n, float m) {
	float a = 6.2831853 * n + float(i) * 2.3999632;
	return vec2(cos(a), sin(a)) * sqrt((float(i) + m) / float(rays));
}

// The radius jitter: interleaved gradient noise over the transposed pixel grid (independent of n), moving per frame only
// where the pack's own noise does. The pack keeps it still on entities, the hand and without TAA, where its TAA does not
// accumulate (it skips entity pixels): a moving jitter there would flicker.
float emrRtDiskJitter(float n) {
	bool still = abs(n - fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))))) < 1e-5;
	return fract(52.9829189 * fract(dot(gl_FragCoord.yx, vec2(0.06711056, 0.00583715))) + (still ? 0.0 : 0.7548777 * float(frameCounter % 3600)));
}

// Fraction of rays towards the shadow light (cone around it; half-angle grows with the pack's filter offset) that escape.
float emrRtSunVisibility(vec3 shadowPos, float offset, float n, int rays, float tMin) {
	vec3 origin = emrRtRayOrigin(shadowPos);
	vec3 l = normalize(shadowModelViewInverse[2].xyz);
	vec3 t = normalize(cross(l, abs(l.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
	vec3 b = cross(l, t);
	float cone = offset * emrRt_shadows_cone;
	float vis = 0.0, m = emrRtDiskJitter(n);
	for (int i = 0; i < rays; i++) {
		vec2 d = emrRtDiskSample(i, rays, n, m) * cone;
		vec3 dir = normalize(l + t * d.x + b * d.y);
		uint mask = emrRt_shadows_entities != 0 ? 0x0Du : 0x01u;
		vis += emrRtNearEntities(emrRtVisibilityMask(origin, dir, max(tMin, emrRtTerrainMin), 512.0, mask), origin, dir, tMin, mask);
	}
	return vis / float(rays);
}

// Full RT: the colour the map adds where its opaque depth (s1, filtered) says lit, from translucent casters only. SampleShadow
// also returns the filtered opaque test itself, which dropped to ~0.5 along every map shadow edge: a dark stair-stepped
// line (map texels) inside the ray-traced light near each shadow, plain to see zoomed in.
// Here: the translucent share of the lit opaque area, (s1 - s0) / s1, takes SampleShadow's colour (its coloured term with
// the opaque test divided out); the rest stays white.
vec3 emrRtMapTint(vec3 p, float s1, float colorMult, float colorPow) {
	float s0 = shadow2D(shadowtex0, p).x;
	if (s0 >= s1 * 0.9999) return vec3(1.0);
	vec3 col = (SampleShadow(p, colorMult, colorPow) - s0) / max((1.0 - s0) * s1, 1e-4);
	return mix(vec3(1.0), col, (s1 - s0) / s1);
}

// Hybrid: RT visibility times one jittered tap of the pack's map lookup (entities, translucent colour, caustics).
// Full: RT visibility; the map only adds translucent colour where its opaque depth says lit.
// Hybrid traces only where the map tap lets light through. Helper lanes trace nothing (nobody differentiates this).

vec3 emrRtShadowTerm(vec3 shadowPos, float offset, float n, int rays, float tMin, float colorMult, float colorPow) {
	if (gl_HelperInvocation) return vec3(1.0);
	vec3 mapPos = vec3(shadowPos.xy + emrRtDisk(n) * offset, shadowPos.z);
	if (emrRt_shadows_full == 0) {
		vec3 map = SampleShadow(mapPos, colorMult, colorPow);
		return map == vec3(0.0) ? map : emrRtSunVisibility(shadowPos, offset, n, rays, tMin) * map;
	}
	float vis = emrRtSunVisibility(shadowPos, offset, n, rays, tMin);
	if (vis <= 0.0) return vec3(0.0);
	float s1 = shadow2D(shadowtex1, mapPos).x;
	return vis * (s1 > 0.5 ? emrRtMapTint(mapPos, s1, colorMult, colorPow) : vec3(1.0));
}
