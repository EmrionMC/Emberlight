// RT style RTX: stronger light shafts, and near the camera their edges follow the same ray-traced scene as the lit surfaces.
// The pack marches its shadow map (texelFetch, no filtering, no bias): the shaft's edges sat a texel or more off the RT-lit
// patch they lead to, so the shaft floated beside the patch on the floor.

// 1 = nothing opaque (or alpha-tested cutout) between the air sample p and the sun. Mask 0x01: solid + cutout terrain; glass
// (0x02) lets the light through: where only glass blocks the map, the map value stays and the pack tints the shaft as before.
float emrRtShaftVisibility(vec3 p) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT, 0x01u, p, 0.0, normalize(shadowModelViewInverse[2].xyz), 512.0);
	bool more;
	for (int guard = 0; (more = rayQueryProceedEXT(rq)) && guard < 32; guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
		int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
		EmrRtVerts v = emrRtVerts(ci);
		uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
		if (!emrRtIsEntity(ci) && emrRt_shafts_skip(emrRtId(v, tri.x))) continue; // plants the surface rays skip too
		if (emrRtHitAlpha(ci, v, tri, rayQueryGetIntersectionBarycentricsEXT(rq, false)) >= 0.1) rayQueryConfirmIntersectionEXT(rq);
	}
	// a spent candidate budget (deep foliage) counts as blocked
	return !more && rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
}
