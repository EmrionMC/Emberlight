// Only near a shadow edge of the map (a texel two away on either axis disagrees) can a ray change the result, which keeps it
// to a few rays per pixel. shortcut: a light leak the map has across a whole area (no edge) stays; trace every sample if such
// leaks show up. Where only glass blocks the map (RT sees through it), the map value stays and the pack tints the shaft.
if (nextDistance < emrRt_shafts_rtDist) {
	ivec2 emrRtC = ivec2(shadowPos.xy * shadowMapResolutionM);
	float emrRtE = 0.0;
	for (int emrRtI = 0; emrRtI < 4; emrRtI++) {
		ivec2 emrRtO = ivec2(emrRtI == 0 ? 2 : emrRtI == 1 ? -2 : 0, emrRtI == 2 ? 2 : emrRtI == 3 ? -2 : 0);
		emrRtE += abs(clamp((texelFetch(shadowtex0, clamp(emrRtC + emrRtO, ivec2(0), ivec2(shadowMapResolutionM) - 1), 0).x - shadowPos.z) * 65536.0, 0.0, 1.0) - shadowSample);
	}
	if (emrRtE > 0.01) {
		float emrRtV = emrRtShaftVisibility(scenePos);
		if (emrRtV == 0.0 || shadowSample > 0.0 || shadow2D(shadowtex1, shadowPos.xyz).z < 1.0) shadowSample = emrRtV;
		if (emrRtV == 0.0) shadowPos.z = 2.0; // the pack's glass test below must not light it again through the map
	}
}
localDensity = vec3(shadowSample);
