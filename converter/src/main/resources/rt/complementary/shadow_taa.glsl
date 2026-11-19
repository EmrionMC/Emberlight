// SHADOW_QUALITY >= 2 with TAA: rays per pixel from the RT option, the pack's TAA accumulates the penumbra.
emrRtTerrainMin = leaves ? 1.0 : 0.0; // (also where the receiver hook does not run, e.g. LOD programs)
return emrRtShadowTerm(shadowPos, offset * 1.3875, InterleavedGradientNoiseForShadows(), emrRt_shadows_rays, 0.01, colorMult, colorPow);
