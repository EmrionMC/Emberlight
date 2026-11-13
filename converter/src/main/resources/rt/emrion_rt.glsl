// Emrion RT helper library (RAYTRACING.md §3.3), inserted once per patched stage. Emrion code, no pack code.
// Bindings are assigned by the converter like any other set-1 resource (emrRtTlas -> emrion_tlas,
// EmrRtSections -> emrion_rt_sections, emrRtAtlas -> block atlas).
layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer EmrRtVerts { uint d[]; };
layout(std430) readonly buffer EmrRtSections { uvec4 emrRtSection[]; };
uniform accelerationStructureEXT emrRtTlas;
uniform sampler2D emrRtAtlas;

// EMRION_BLOCK vertex (56 B = 14 words): UV0 at word 4, mc_Entity (RG16_SINT) at word 8
vec2 emrRtUv(EmrRtVerts v, uint i) {
	return vec2(uintBitsToFloat(v.d[i * 14u + 4u]), uintBitsToFloat(v.d[i * 14u + 5u]));
}

// 1 = nothing opaque between origin and origin + dir * tMax. Cutout candidates are alpha-tested against the atlas (mip 0,
// cutoff 0.1 like the shadow pass); instance mask 0x01 = solid + cutout terrain (translucent is 0x02 and never traced here).
float emrRtVisibility(vec3 origin, vec3 dir, float tMin, float tMax) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT, 0x01u, origin, tMin, dir, tMax);
	for (int guard = 0; guard < 32 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		uvec4 sec = emrRtSection[rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false)];
		EmrRtVerts v = EmrRtVerts(sec.xy);
		uint prim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false));
		uint base = (prim >> 1u) * 4u;
		if (emrRtSkip(int(v.d[base * 14u + 8u] << 16u) >> 16)) continue;
		uvec3 tri = (prim & 1u) == 0u ? uvec3(0u, 1u, 2u) : uvec3(2u, 3u, 0u);
		vec2 b = rayQueryGetIntersectionBarycentricsEXT(rq, false);
		vec2 uv = emrRtUv(v, base + tri.x) * (1.0 - b.x - b.y) + emrRtUv(v, base + tri.y) * b.x + emrRtUv(v, base + tri.z) * b.y;
		if (textureLod(emrRtAtlas, uv, 0.0).a >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	return rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
}
