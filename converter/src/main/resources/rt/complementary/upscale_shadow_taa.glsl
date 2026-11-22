// The pack's own kernel, plus up to three more rotated sets (16 tap pairs in all) where the pixel moves fast on screen and the
// first set saw a penumbra (taps differ). In fast motion (elytra) the upscaler cannot average the few jittered taps over frames,
// so the edge turned grainy and soft; still or slow pixels keep the pack's kernel, which the upscaler accumulates as before.
// The rotations are stratified, so the averaged image stays the same, and pixels without a penumbra get the pack's result.
// Speed: camera motion only (the pixel reprojected with the previous matrices), in render pixels per frame.
// Replaces the pack's tap loop; each tap's statements are lifted from the pack (@shadowTap*), with gradientNoise rotated per set.
// Each tap is summed on its own (shadow reset, then added back in the pack's order) so the result is the pack's sum, and the
// pack's division after the loop divides by all sets (shadowSamples is not used after it). Absent where RT shadows replaced
// the function.
int emrSets = 1;
float emrLo = 1.0, emrHi = 0.0, emrNoise = gradientNoise;
for (int k = 0; k < emrSets; k++) {
    gradientNoise = fract(emrNoise + 0.25 * float(k));
    for (int i = 0; i < shadowSamples; i++) {
        @shadowTapOffset
        vec3 emrSum = shadow;
        shadow = vec3(0.0);
        @shadowTapPlus
        vec3 a = shadow;
        shadow = vec3(0.0);
        @shadowTapMinus
        vec3 b = shadow;
        shadow = emrSum + a;
        shadow += b;
        float va = max(a.r, max(a.g, a.b)), vb = max(b.r, max(b.g, b.b));
        emrLo = min(emrLo, min(va, vb));
        emrHi = max(emrHi, max(va, vb));
    }
    if (k == 0 && emrHi - emrLo > 0.1) {
        vec3 emrNdc = vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), gl_FragCoord.z) * 2.0 - 1.0;
        vec4 emrView = gbufferProjectionInverse * vec4(emrNdc, 1.0);
        vec4 emrPrev = gbufferPreviousProjection * (gbufferPreviousModelView * vec4((gbufferModelViewInverse * vec4(emrView.xyz / emrView.w, 1.0)).xyz
            + cameraPosition - previousCameraPosition, 1.0));
        float emrPx = emrPrev.w > 0.0 ? length((emrNdc.xy - emrPrev.xy / emrPrev.w) * vec2(viewWidth, viewHeight)) * 0.5 : 1e3;
        // one more set per 4 px/frame of motion: none below 4 px, all from 12 px on
        emrSets = min(int(min(emrPx, 12.0) * 0.25) + 1, clamp(16 / shadowSamples, 1, 4));
    }
}
shadowSamples *= emrSets;
