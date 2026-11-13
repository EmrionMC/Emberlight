// SHADOW_QUALITY 1 (no TAA accumulation): at least 4 rays.
return emrRtShadowTerm(shadowPos, offset, InterleavedGradientNoiseForShadows(), max(emrRt_shadows_rays, 4), 0.01, colorMult, colorPow);
