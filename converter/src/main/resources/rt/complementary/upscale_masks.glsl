// M8: the pack's TAA is replaced by FSR/DLSS; its history target (colortex2) carries the reactive (r) and
// transparency-and-composition (g) masks instead, read by the upscaler (docs/UPSCALING.md section 2).
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
temp = vec3(emrReactive, emrComposition, 0.0);
