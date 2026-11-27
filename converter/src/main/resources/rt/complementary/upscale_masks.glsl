// M8: the pack's TAA is replaced by FSR/DLSS; its history target (colortex2) carries the reactive (r) and
// transparency-and-composition (g) masks instead, read by the upscaler (docs/UPSCALING.md section 2).
// b: view-ray distance in blocks of the volumetric cloud a sky pixel shows (0 = none), so the motion pass moves clouds with
// the camera translation instead of as infinitely far sky (streaks and ghosting when flying fast).
int emrMat = int(texelFetch(colortex6, texelCoord, 0).g * 255.1);
float emrZ0 = texelFetch(depthtex0, texelCoord, 0).r;
float emrReactive = texelFetch(colortex12, texelCoord, 0).a * 0.8;
float emrComposition = 0.0;
if (z1 < 0.56) {
} else if (emrZ0 < z1) {
    emrComposition = emrMat == 241 ? 0.75 : 0.45;
} else if (emrMat == 253) {
    emrComposition = 0.35;
} else if (emrMat == 254) {
    emrComposition = 0.25;
}
float emrCloud = 0.0;
// colortex5.a still holds deferred1's cloud depth here only when composite1 keeps it (ENTITY_TAA_NOISY_CLOUD_FIX or a lens
// flare); otherwise composite1 wrote its volumetric light factor to every texel, which then equals the corner texel's
ivec2 emrCorner = ivec2(viewWidth, viewHeight) - 1;
float emrCloudDepth = texelFetch(colortex5, texelCoord, 0).a;
if (emrZ0 >= 1.0 && emrCloudDepth < 1.0 && texelCoord != emrCorner && emrCloudDepth != texelFetch(colortex5, emrCorner, 0).a)
    emrCloud = emrCloudDepth * emrCloudDepth * renderDistance;
temp = vec3(emrReactive, emrComposition, emrCloud);
