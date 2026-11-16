// RTX sun shadows, appended after shadows.glsl: sunlight passes translucent terrain and takes its colour; the penumbra comes
// from a fixed sun cone (contact hardening). Water is left to the shadow map.


// Transmittance of one translucent face: the texel's hue (normalised to its brightest channel, so glass tints rather than
// darkens) weighted by its alpha and the strength option.
vec3 emrRtxShGlass(vec4 c) {
	vec3 hue = pow(c.rgb / max(max(c.r, c.g), max(c.b, 1e-3)), vec3(emrRt_shadows_saturation));
	return mix(vec3(1.0), hue, clamp(c.a * emrRt_shadows_glass, 0.0, 1.0));
}

vec3 emrRtxShTint; // emrRtxShVisibility: product of the translucent faces the ray passed (1 = none)
float emrRtxShWaterT; // emrRtxShVisibility: nearest water face any ray passed since the caller reset it (1e9 = none)

float emrRtxShVisibility(vec3 origin, vec3 dir, float tMin, float tMax, uint mask) {
	vec3 tint = vec3(1.0);
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT, mask | 0x02u, origin, tMin, dir, tMax);
	for (int guard = 0; guard < 48 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		EmrRtVerts v = emrRtVerts(ci);
		uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		vec2 b = rayQueryGetIntersectionBarycentricsEXT(rq, false);
		bool entity = emrRtIsEntity(ci);
		if (!entity && emrRt_shadows_skip(emrRtId(v, tri.x))) continue;
		if (!entity && emrRtSection[ci].z == 2u) { // translucent terrain: tint, never block
			if (emrRtId(v, tri.x) == emrRt_shadows_water) {
				emrRtxShWaterT = min(emrRtxShWaterT, rayQueryGetIntersectionTEXT(rq, false));
				continue;
			}
			tint *= emrRtxShGlass(textureLod(emrRtAtlas, emrRtBaryUv(v, tri, b), 0.0) * vec4(emrRtColor(v, tri.x).rgb, 1.0));
			continue;
		}
		if (emrRtHitAlpha(ci, v, tri, b) >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	emrRtxShTint = tint;
	return rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
}

// Coloured sun visibility; the map adds the water colour and caustics only where no glass coloured the light already.
vec3 emrRtxShadowTerm(vec3 shadowPos, float n, int rays, float tMin, bool useMap, float colorMult, float colorPow) {
	if (gl_HelperInvocation) return vec3(1.0); // helper lanes only feed derivatives, nobody differentiates this
	if (emrRtHitRays > 0) rays = emrRtHitRays;
	vec3 origin = emrRtShadowOrigin(shadowPos);
	vec3 l = normalize(shadowModelViewInverse[2].xyz);
	vec3 t = normalize(cross(l, abs(l.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
	vec3 bt = cross(l, t);
	vec3 vis = vec3(0.0);
	bool glass = false;
	emrRtxShWaterT = 1e9;
	for (int i = 0; i < rays; i++) {
		vec2 d = emrRtDisk(fract(n + float(i) / float(rays))) * emrRt_shadows_sun;
		float v = emrRtxShVisibility(origin, normalize(l + t * d.x + bt * d.y), tMin, 512.0, emrRt_shadows_entities != 0 ? 0x0Du : 0x01u);
		vis += v * emrRtxShTint;
		glass = glass || (v > 0.0 && emrRtxShTint != vec3(1.0));
	}
	vis /= float(rays);
	if (!useMap || glass || max(vis.r, max(vis.g, vis.b)) <= 0.0) return vis;
	if (shadow2D(shadowtex1, shadowPos).x > 0.5) return vis * SampleShadow(shadowPos, colorMult, colorPow);
	// The map's opaque depth has a mob or the player in it that these rays passed (its map shadow is wider or offset). Under
	// water plain sunlight there drew an untinted rim around squid and player shadows: take the water's light from the map
	// on a ring around the receiver instead, where its opaque depth is clear.
	if (emrRtxShWaterT < 1e8) {
		vec3 p = emrRtShadowOrigin(shadowPos), a = normalize(cross(l, vec3(0.0, 0.0, 1.0))), b = cross(l, a), sum = vec3(0.0);
		float k = 0.0;
		for (int i = 0; i < 8; i++) {
			vec3 q = GetShadowPos(p + (a * cos(float(i) * 0.785398) + b * sin(float(i) * 0.785398)) * 1.5);
			if (shadow2D(shadowtex1, q).x > 0.5) { sum += SampleShadow(q, colorMult, colorPow); k += 1.0; }
		}
		if (k > 0.0) return vis * sum / k;
	}
	return vis;
}
