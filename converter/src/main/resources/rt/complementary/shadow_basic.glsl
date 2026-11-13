// SHADOW_QUALITY 0: no shadow colour in the pack either.
float n = InterleavedGradientNoiseForShadows();
float vis = emrRtSunVisibility(shadowPos, offset, n, max(emrRt_shadows_rays, 4), 0.01);
if (emrRt_shadows_full != 0 || vis <= 0.0) return vec3(vis);
return vec3(vis * shadow2D(shadowtex0, vec3(shadowPos.xy + emrRtDisk(n) * offset, shadowPos.z)).x);
