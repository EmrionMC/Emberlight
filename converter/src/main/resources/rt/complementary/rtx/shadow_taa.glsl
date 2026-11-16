// TAA averages the jittered rays; far away (emrRt_shadows_farDist blocks, 0 = off) half of them do
int rays = max(emrRt_shadows_rays, emrRt_shadows_minRays);
if (emrRt_shadows_farDist > 0.0 && length(emrRtShadowOrigin(shadowPos)) > emrRt_shadows_farDist) rays = max(rays / 2, 1);
return emrRtxShadowTerm(shadowPos, InterleavedGradientNoiseForShadows(), rays, leaves ? 1.0 : 0.01, true, colorMult, colorPow);
