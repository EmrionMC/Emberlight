// SHADOW_QUALITY 0: no shadow colour in the pack either.
float n = InterleavedGradientNoiseForShadows();
if (gl_HelperInvocation) return vec3(1.0);
if (emrRtHand) return emrRtHandShadow(shadowPos, n, 1.0, 1.0);
if (emrRt_shadows_full == 0) {
	float map = shadow2D(shadowtex0, vec3(shadowPos.xy + emrRtDisk(n) * offset, shadowPos.z)).x;
	return map <= 0.0 ? vec3(0.0) : vec3(emrRtSunVisibility(shadowPos, offset, n, max(emrRt_shadows_rays, 4), 0.01) * map);
}
return vec3(emrRtSunVisibility(shadowPos, offset, n, max(emrRt_shadows_rays, 4), 0.01));
