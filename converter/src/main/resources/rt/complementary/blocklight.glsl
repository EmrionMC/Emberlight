// RT block-light shadows for Complementary r5. The pack's block light keeps its colours and falloff; RT only removes light a
// ray proves blocked: of the pack's light P, the part no visible source explains (P - sum w_i v_i) is removed as far as
// blocked sources account for it (sum w_i (1 - v_i)). Light from unknown sources (entities) stays. Light grid: RtLights.
// Light grid: RtLights, declared in bl_buffer.glsl (emrRtLightCell, emrRtLight, emrRtLightFade).

// Opaque or alpha-tested terrain along the ray (cutout alpha 0.1 at mip 0, like the shadow pass).
// Lava (ids 10068, 10070) never blocks, or a pool would shadow itself.
float emrRtLightVisibility(vec3 origin, vec3 dir, float tMax) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT, 0x01u, origin, 0.0, dir, tMax);
	for (int guard = 0; guard < 32 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		EmrRtVerts v = emrRtVerts(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false));
		uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		int id = emrRtId(v, tri.x);
		if (id == 10068 || id == 10070) continue;
		vec2 uv = emrRtBaryUv(v, tri, rayQueryGetIntersectionBarycentricsEXT(rq, false));
		if (textureLod(emrRtAtlas, uv, 0.0).a >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	return rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
}

// Unshadowed weight of a source at p, on the pack's lightmap scale: vanilla light falls one level per block, so L1 distance
// (Euclidean let distant visible sources "explain" light behind occluders).
float emrRtLightWeight(vec4 l, vec3 p) {
	vec3 d = abs(p - l.xyz);
	float x = max(floor(l.w) - max(d.x + d.y + d.z - 1.0, 0.0), 0.0) / 15.0;
	return x * x * x;
}

// One shadow ray from p to source l; it stops at the source's block so the emitter's own model never shadows itself.
float emrRtLightRay(vec3 p, vec3 n, vec4 l, vec3 target) {
	vec3 d = normalize(target - p);
	vec3 inv = 1.0 / d, t0 = (l.xyz - 0.5 - p) * inv, t1 = (l.xyz + 0.5 - p) * inv, tn = min(t0, t1);
	float enter = max(max(tn.x, tn.y), tn.z);
	if (enter <= 0.02) return 1.0; // p is on or in the source block
	if (dot(d, n) <= 0.0) return 0.0; // the source is behind the surface
	return emrRtLightVisibility(p, d, enter - 0.01);
}

// Visibility of source l: a disk facing p, 16 fixed sample points (no flicker or grain). 4 go first; the other 12 only
// where they disagree (penumbra), so lit and fully shadowed areas cost 4 rays.
const vec2 emrRtLightDisk[16] = vec2[16](vec2(0.7700, 0.0000), vec2(0.0000, 0.7700), vec2(-0.7700, 0.0000), vec2(0.0000, -0.7700), vec2(0.6668, 0.3850), vec2(0.3850, 0.6668), vec2(-0.3850, 0.6668), vec2(-0.6668, 0.3850), vec2(-0.6668, -0.3850), vec2(-0.3850, -0.6668), vec2(0.3850, -0.6668), vec2(0.6668, -0.3850), vec2(0.2121, 0.2121), vec2(-0.2121, 0.2121), vec2(-0.2121, -0.2121), vec2(0.2121, -0.2121));

float emrRtLightSource(vec3 p, vec3 n, vec4 l, bool refine) {
	vec3 w = normalize(l.xyz - p);
	// continuous orthonormal basis around w (Duff et al. 2017)
	float sg = w.z >= 0.0 ? 1.0 : -1.0, a = -1.0 / (sg + w.z), b = w.x * w.y * a;
	vec3 t = fract(l.w) * vec3(1.0 + sg * w.x * w.x * a, sg * b, -sg * w.x), u = fract(l.w) * vec3(b, sg + w.y * w.y * a, -w.y);
	float v = 0.0;
	for (int j = 0; j < 16; j++) { // one call site keeps the inlined ray query loop small
		if (j == 4 && (!refine || v == 0.0 || v == 4.0)) return v * 0.25;
		v += emrRtLightRay(p, n, l, l.xyz + emrRtLightDisk[j].x * t + emrRtLightDisk[j].y * u);
	}
	return v * 0.0625;
}

// Factor for the pack's block light at p (lightX = level / 15). Only the emrRt_blocklight_rays strongest sources are traced,
// weaker ones count as visible. Bounded in [ambient, 1]; nothing varies per frame, so no flicker.

float emrRtBlockLight(vec3 playerPos, vec3 n, float lightX) {
	// helper lanes only feed derivatives, and nothing after DoLighting takes one of the lighting
	if (gl_HelperInvocation) return 1.0;
	float packW = pow(clamp(lightX, 0.0, 1.0), 3.0); // emrRtLightWeight of the lightmap level
	if (packW < 1e-4) return 1.0;
	vec3 p = playerPos + n * 0.02;
	float fade = emrRtLightFade(p);
	if (fade <= 0.0) return 1.0;
	ivec3 dims = emrRtLightDims.xyz;
	vec3 g = (p - emrRtLightOrigin.xyz) / float(emrRtLightDims.w);
	ivec3 c = ivec3(floor(g));
	if (any(lessThan(c, ivec3(0))) || any(greaterThanEqual(c, dims))) return 1.0;
	uint base = uint((c.y * dims.z + c.z) * dims.x + c.x) * 32u;
	uint count = emrRtLightCell[base];
	// A full cell (31 sources, e.g. beside a lava lake) lacks sources that may light p unseen: nothing is proven blocked.
	if (count >= 31u) return 1.0;
	// one pass: total weight and the emrRt_blocklight_rays strongest sources, strongest first (ties keep the lower slot)
	float restW = 0.0, topW[emrRt_blocklight_rays];
	uint topI[emrRt_blocklight_rays];
	for (int k = 0; k < emrRt_blocklight_rays; k++) { topW[k] = 0.0; topI[k] = 0u; }
	for (uint i = 0u; i < count; i++) {
		float w = emrRtLightWeight(emrRtLight[emrRtLightCell[base + 1u + i]], p);
		restW += w;
		if (w <= topW[emrRt_blocklight_rays - 1]) continue;
		int k = emrRt_blocklight_rays - 1;
		for (; k > 0 && w > topW[k - 1]; k--) { topW[k] = topW[k - 1]; topI[k] = topI[k - 1]; }
		topW[k] = w;
		topI[k] = i;
	}
	if (restW <= 0.0) return 1.0; // no source in reach: the pack's light is not from a known block, keep it
	// far away a penumbra spans few pixels: the 4 ring points alone (emrRt_blocklight_refineDist blocks, 0 = always refine)
	bool refine = emrRt_blocklight_refineDist <= 0.0 || dot(playerPos, playerPos) < emrRt_blocklight_refineDist * emrRt_blocklight_refineDist;
	float visW = 0.0, blockW = 0.0;
	for (int k = 0; k < emrRt_blocklight_rays; k++) {
		float best = topW[k];
		// a source under 1/16 of the pack's light changes less than one sample step of the strongest: left untraced
		if (best <= packW * 0.0625) break;
		float v = emrRtLightSource(p, n, emrRtLight[emrRtLightCell[base + 1u + topI[k]]], refine);
		visW += best * v;
		blockW += best * (1.0 - v);
		restW -= best;
		if (visW >= packW) break; // visible sources explain all of the pack's light: nothing can be removed
	}
	float removed = min(max(packW - visW - restW, 0.0), blockW) / packW;
	return 1.0 - (1.0 - emrRt_blocklight_ambient) * removed * fade;
}
