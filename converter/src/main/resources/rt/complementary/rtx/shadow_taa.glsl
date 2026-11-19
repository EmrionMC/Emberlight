// TAA averages the jittered rays; far away (emrRt_shadows_farDist blocks at the default FOV, 0 = off) half of them do. The
// distance counts as it looks on screen: zoomed in (Tweakeroo, spyglass) far shadows are big and kept their grain otherwise.
int rays = max(emrRt_shadows_rays, emrRt_shadows_minRays);
if (emrRtxRecvRays > 0) rays = min(rays, emrRtxRecvRays); // the water surface: shadows:waterRays, halved far away too
if (emrRt_shadows_farDist > 0.0 && length(emrRtRayOrigin(shadowPos)) * 1.428 / gbufferProjection[1][1] > emrRt_shadows_farDist) rays = max(rays / 2, 1);
// Leaf receivers: two rays (shadows:leafRays, 0 = all of them). Overdraw makes leaves most of the shadow rays in foliage,
// and TAA averages two rays there to the same image as four (one ray left speckles in the canopy)
if (leaves && emrRt_shadows_leafRays > 0) rays = min(rays, emrRt_shadows_leafRays);
emrRtTerrainMin = leaves ? 1.0 : 0.0; // (also where the receiver hook does not run, e.g. LOD programs)
return emrRtxShadowTerm(shadowPos, InterleavedGradientNoiseForShadows(), rays, 0.01, true, colorMult, colorPow);
