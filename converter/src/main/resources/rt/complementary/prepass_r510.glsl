// r5.10: the pack's own test only drops empty texels and leaves the cutout test (> 0.5) to the draw's alpha test, which the
// prepass return skips: the prepass pipeline's ref is 2 + the draw's own ref (0 for solid), so its coverage stays the same.
if (color.a <= 0.00001) discard;
if (emr_draw.emr_alphaTestRef > 1.5) { if (!(color.a > emr_draw.emr_alphaTestRef - 2.0)) discard; gl_FragData[0] = vec4(3.0); return; }
