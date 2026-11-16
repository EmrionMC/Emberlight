// RT water caustics for Complementary r5: replaces the pattern the pack bakes into shadowcolor0.
// The receiver traces to the water surface above it (real depth); light refracted by the pack's wave field lands at
// L(x) = x + depth * r(x), and the caustic is 1 / |det dL/dx|: focuses with depth, noise-free, moves with the waves.
// Light colour and level stay the pack's (mean shadowcolor0 around the receiver).

// Inverse of the pack's GetShadowPos (own copy of emrRtShadowOrigin so both effects can share a stage).
vec3 emrRtCaOrigin(vec3 shadowPos) {
	vec3 c = shadowPos * 2.0 - 1.0;
	c.z /= 0.2;
	c.xy *= (1.0 - shadowMapBias) / (1.0 - length(c.xy) * shadowMapBias);
	vec3 v = mat3(shadowProjectionInverse) * c + shadowProjectionInverse[3].xyz;
	return mat3(shadowModelViewInverse) * v + shadowModelViewInverse[3].xyz;
}

// Distance to the closest water surface (block id emrRt_caustics_water) along dir; other translucents are passed. -1 = none.
float emrRtCaWater(vec3 o, vec3 dir) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsNoneEXT, 0x02u, o, 0.02, dir, 64.0);
	for (int guard = 0; guard < 16 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		EmrRtVerts v = emrRtVerts(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false));
		uint base = (uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)) >> 1u) * 4u;
		if (emrRtId(v, base) == emrRt_caustics_water) rayQueryConfirmIntersectionEXT(rq);
	}
	return rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? -1.0 : rayQueryGetIntersectionTEXT(rq, true);
}

// Height-field slope of the water at world xz (surface height y): the pack's own water normal octaves (lifted from its
// gbuffers_water), at the pack's water scale and wind.
vec2 emrRtCaSlope(vec2 xz, float y) {
	vec2 waterPosM = 0.08 * (xz + 2.0 * y);
	vec2 wind = vec2(0.0, -0.045 * frameTimeCounter * emrRt_caustics_speed);
	@waterNormals
	return (normalMed + 0.5 * normalSmall + normalBig) * emrRt_caustics_bump;
}

// Where light entering the surface at xz lands, depth below it (light direction inc, into the water).
vec2 emrRtCaLand(vec2 xz, float y, float depth, vec3 inc) {
	vec2 s = emrRtCaSlope(xz, y);
	vec3 r = refract(inc, normalize(vec3(-s.x, 1.0, -s.y)), 1.0 / 1.333);
	return xz + r.xz * depth / max(-r.y, 0.05);
}

// Caustic intensity at receiver p (1 = flat water), or -1 when no water lies between p and the light.
float emrRtCaIntensity(vec3 p) {
	vec3 l = normalize(shadowModelViewInverse[2].xyz);
	if (l.y <= 0.05) return -1.0;
	float t = emrRtCaWater(p, l);
	if (t < 0.0) return -1.0;
	float y = p.y + t * l.y + cameraPosition.y, depth = t * l.y;
	vec3 inc = -l;
	vec3 r0 = refract(inc, vec3(0.0, 1.0, 0.0), 1.0 / 1.333);
	vec2 off0 = r0.xz * depth / -r0.y;
	vec2 x = p.xz + cameraPosition.xz - off0; // where unperturbed light reaching p entered
	x -= emrRtCaLand(x, y, depth, inc) - x - off0; // one fixed-point step towards the perturbed entry point
	const float e = 0.04;
	vec2 dx = emrRtCaLand(x + vec2(e, 0.0), y, depth, inc) - emrRtCaLand(x - vec2(e, 0.0), y, depth, inc);
	vec2 dz = emrRtCaLand(x + vec2(0.0, e), y, depth, inc) - emrRtCaLand(x - vec2(0.0, e), y, depth, inc);
	float det = abs(dx.x * dz.y - dx.y * dz.x) / (4.0 * e * e);
	float c = min(1.0 / max(det, 0.01), emrRt_caustics_max);
	return mix(1.0, c, emrRt_caustics_strength * (1.0 - smoothstep(32.0, 64.0, length(p.xz))));
}

// The water's light colour without the pack's pattern: mean shadowcolor0 on a 1.5-block ring (clear texels left out).
vec3 emrRtCaTint(vec3 p, vec3 fallback) {
	vec3 l = normalize(shadowModelViewInverse[2].xyz);
	vec3 a = normalize(cross(l, vec3(0.0, 0.0, 1.0))), b = cross(l, a);
	vec3 sum = vec3(0.0);
	float n = 0.0;
	for (int i = 0; i < 8; i++) {
		float ang = float(i) * 0.785398;
		vec3 c = texture2D(shadowcolor0, GetShadowPos(p + (a * cos(ang) + b * sin(ang)) * 1.5).xy).rgb;
		if (min(c.r, min(c.g, c.b)) < 0.98) { sum += c; n += 1.0; }
	}
	return n > 0.0 ? sum / n : fallback;
}

// SampleShadow runs up to 2 x shadowSamples times per pixel: compute the caustic once per receiver (within 0.25 blocks).

vec3 emrRtCaMemoPos = vec3(1e9);
vec4 emrRtCaMemo = vec4(0.0);

vec3 emrRtCausticColor(vec3 shadowPos) {
	vec3 map = texture2D(shadowcolor0, shadowPos.st).rgb;
	vec3 p = emrRtCaOrigin(shadowPos);
	if (distance(p, emrRtCaMemoPos) > 0.25) {
		emrRtCaMemoPos = p;
		float c = emrRtCaIntensity(p);
		emrRtCaMemo = c < 0.0 ? vec4(-1.0) : vec4(emrRtCaTint(p, map), c);
	}
	return emrRtCaMemo.a < 0.0 ? map : emrRtCaMemo.rgb * emrRtCaMemo.a;
}
