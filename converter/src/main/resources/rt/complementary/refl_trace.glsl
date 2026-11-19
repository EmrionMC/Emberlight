// voxelRayTrace: the closest TLAS hit replaces the voxel walk; the pack's getShadedReflection shades it (face data from the hit
// vertex through the patched getFaceData). A hit the pack rejects (transparent at its lod) lets the ray continue behind it.
if (emrRtReflSkip) { traceLength = 999999.0; return vec4(0.0); } // reduced-rate tile, skipped this frame (composite keeps its history)
float emrTMin = 0.001;
for (int emrI = 0; emrI < 4; emrI++) {
	float emrT; vec3 emrN; uint emrId;
	if (!emrRtReflHit(playerPos, rayDir, emrTMin, emrT, emrN, emrId)) break;
	traceLength = emrT;
	vec3 intersection = playerPos + (emrT - 0.001) * rayDir;
	// texture bounds placed so the pack's own UV formula (bounds + 2 * rad * getLocalTexCoord) lands on the exact hit UV
	vec2 emrAtlas = vec2(textureSize(emrRtAtlas, 0));
	vec2 emrRad = emrRtHitRad * vec2(1.0, emrAtlas.x / emrAtlas.y);
	emrRtFace.textureBounds = vec3(emrRtHitUv - 2.0 * emrRad * getLocalTexCoord(fract(intersection + cameraPositionBestFract), emrN), emrRtHitRad);
	emrRtHitRays = emrRt_reflections_sunRays; // RTX sun rays at the hit (0 = the terrain's count); TAA and the pack's reflection history average them
	vec4 reflection = getShadedReflection(ivec3(playerToSceneVoxel(intersection - 0.01 * emrN)), playerPos, intersection, rayDir, emrN,
			emrId > 10u ? emrId : 1u, dither);
	emrRtHitRays = 0;
	if (emrId == uint(EMR_RT_PORTAL) && reflection.a > -0.5) { // its own glow, as the pack's netherPortal.glsl: no light in it
		vec4 emrC = textureLod(emrRtAtlas, emrRtHitUv, 0.0);
		float emrE = emrC.r * emrC.r * emrC.r * emrC.r;
		reflection.rgb = emrC.rgb * emrC.rgb * vec3(1.25, 1.0, 0.65) * clamp(emrE * 120.0, 0.03, 1.2) * 3.2; // a little below the portal seen directly
	}
	// a hit the pack keeps gets its alpha back at 1: the pack fades at the voxel volume's edge around the hit, the TLAS has no
	// such edge. Then the pack's own hit handling (start-point fade, fog, return), lifted from its voxelRayTrace.
	reflection.a = reflection.a > -0.5 ? 1.0 : reflection.a;
	@packHit
	emrTMin = emrT + 0.01;
}
@packMiss
