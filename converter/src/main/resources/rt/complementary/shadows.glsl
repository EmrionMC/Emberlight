// RT sun/moon shadows for Complementary r5.

// 1 = nothing opaque between origin and origin + dir * tMax. Cutout candidates are alpha-tested (mip 0, cutoff 0.1 like the
// shadow pass); mask 0x01 = solid + cutout terrain (translucent is 0x02 and never traced here), | 0x04 = entities.
float emrRtVisibilityMask(vec3 origin, vec3 dir, float tMin, float tMax, uint mask) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT, mask, origin, tMin, dir, tMax);
	for (int guard = 0; guard < 32 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		EmrRtVerts v = emrRtVerts(ci);
		uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		if (!emrRtIsEntity(ci) && emrRt_shadows_skip(emrRtId(v, tri.x))) continue;
		if (emrRtHitAlpha(ci, v, tri, rayQueryGetIntersectionBarycentricsEXT(rq, false)) >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	return rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
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

// Point in the unit disk from one noise value (spiral); the pack's noise rotates every frame under TAA.
vec2 emrRtDisk(float n) {
	float a = 6.2831853 * fract(n * 2.427);
	return vec2(cos(a), sin(a)) * sqrt(n);
}

// Fraction of rays towards the shadow light (cone around it; half-angle grows with the pack's filter offset) that escape.
// tMin: leaves start one block out, so a leaf block's own faces do not shadow it (the pack lets leaves pass the map the same
// way with its leaves depth offset and lights them as subsurface).
float emrRtSunVisibility(vec3 shadowPos, float offset, float n, int rays, float tMin) {
	vec3 origin = emrRtShadowOrigin(shadowPos);
	vec3 l = normalize(shadowModelViewInverse[2].xyz);
	vec3 t = normalize(cross(l, abs(l.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
	vec3 b = cross(l, t);
	float cone = offset * emrRt_shadows_cone;
	float vis = 0.0;
	for (int i = 0; i < rays; i++) {
		vec2 d = emrRtDisk(fract(n + float(i) / float(rays))) * cone;
		vis += emrRtVisibilityMask(origin, normalize(l + t * d.x + b * d.y), tMin, 512.0, emrRt_shadows_entities != 0 ? 0x0Du : 0x01u);
	}
	return vis / float(rays);
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
	return vis * (shadow2D(shadowtex1, mapPos).x > 0.5 ? SampleShadow(mapPos, colorMult, colorPow) : vec3(1.0));
}
