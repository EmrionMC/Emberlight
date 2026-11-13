// RT sun/moon shadows for Complementary r5 (RAYTRACING.md §4.1). Uses only the pack symbols listed in "needs".

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
		vis += emrRtVisibility(origin, normalize(l + t * d.x + b * d.y), tMin, 512.0);
	}
	return vis / float(rays);
}

// Hybrid: RT visibility times one jittered tap of the pack's map lookup (entities, translucent colour, caustics).
// Full: RT visibility; the map only adds translucent colour where its opaque depth says lit.
vec3 emrRtShadowTerm(vec3 shadowPos, float offset, float n, int rays, float tMin, float colorMult, float colorPow) {
	float vis = emrRtSunVisibility(shadowPos, offset, n, rays, tMin);
	if (vis <= 0.0) return vec3(0.0);
	vec3 mapPos = vec3(shadowPos.xy + emrRtDisk(n) * offset, shadowPos.z);
	if (emrRt_shadows_full == 0) return vis * SampleShadow(mapPos, colorMult, colorPow);
	return vis * (shadow2D(shadowtex1, mapPos).x > 0.5 ? SampleShadow(mapPos, colorMult, colorPow) : vec3(1.0));
}
