// RT reflections for Complementary r5: hits come from the TLAS and MC's vertex heaps instead of the voxel walk and SSBO;
// the pack's getShadedReflection still shades them.


// The face the patched getFaceData returns: what the pack's voxeliser would store for it, but per hit (interpolated vertex
// colour and lightmap) and for any geometry (slabs, plants, fences, blocks outside the voxel volume).
faceData emrRtFace;
vec2 emrRtHitUv;
float emrRtHitRad;

// Closest solid/cutout hit (mask 0x01, cutout alpha-tested at mip 0 like the shadow rays, no skip list: plants reflect). Fills
// emrRtFace.glColor/lightmap, emrRtHitUv (exact UV) and emrRtHitRad (sprite half size, as the voxeliser measures it).
bool emrRtReflHit(vec3 origin, vec3 dir, float tMin, out float t, out vec3 n, out uint id) {
	// NaN/inf origin or direction is undefined behaviour for ray queries (can stall traversal): no hit instead
	float dd = dot(dir, dir);
	if (any(isnan(origin)) || any(isinf(origin)) || isnan(dd) || isinf(dd) || dd < 1e-8 || !(tMin < 1024.0)) return false; // also tMin > tMax
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsNoneEXT, 0x01u, origin, tMin, dir, 1024.0); // > RD 32 diagonal
	for (int guard = 0; guard < 64 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		EmrRtVerts v = emrRtVerts(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false));
		uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		if (textureLod(emrRtAtlas, emrRtBaryUv(v, tri, rayQueryGetIntersectionBarycentricsEXT(rq, false)), 0.0).a >= 0.1)
			rayQueryConfirmIntersectionEXT(rq);
	}
	if (rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT) return false;
	t = rayQueryGetIntersectionTEXT(rq, true);
	EmrRtVerts v = emrRtVerts(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true));
	uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true)));
	vec2 b = rayQueryGetIntersectionBarycentricsEXT(rq, true);
	vec3 w = vec3(1.0 - b.x - b.y, b.x, b.y);
	vec3 p0 = emrRtPos(v, tri.x);
	n = normalize(cross(emrRtPos(v, tri.y) - p0, emrRtPos(v, tri.z) - p0));
	if (dot(n, dir) > 0.0) n = -n; // two-sided quads (plants) and back faces: the side the ray sees
	id = uint(max(emrRtId(v, tri.x), 0));
	emrRtHitUv = emrRtBaryUv(v, tri, b);
	emrRtHitRad = max(abs(emrRtUv(v, tri.x).x - emrRtMidUv(v, tri.x).x), 1e-5);
	emrRtFace.glColor = emrRtColor(v, tri.x).rgb * w.x + emrRtColor(v, tri.y).rgb * w.y + emrRtColor(v, tri.z).rgb * w.z;
	// UV2 (block, sky) as the pack's GetLightMapCoordinates sees it (/240), block light stored like the voxeliser (*0.99+0.001)
	vec2 lm = clamp((vec2(emrRtLight(v, tri.x)) * w.x + vec2(emrRtLight(v, tri.y)) * w.y + vec2(emrRtLight(v, tri.z)) * w.z) / 240.0, 0.0, 1.0);
	emrRtFace.lightmap = vec2(lm.x * 0.99 + 0.001, lm.y);
	return true;
}
