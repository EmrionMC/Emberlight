// r5.10: the pack's own test only drops empty texels and leaves the cutout test (> 0.5) to the draw's alpha test, which the
// prepass return skips: the prepass pipeline's ref is 2 + the draw's own ref (0 for solid), so its coverage stays the same.
// The shading pass (ref -2 - the draw's ref) skips the converter's alpha test, so it repeats the same test on the same alpha here:
// else mipmapped fringe texels (0 < alpha <= 0.5) the prepass dropped still pass its LEQUAL depth test in front of what lies behind.
if (color.a <= 0.00001) discard;
if (emr_draw.emr_alphaTestRef > 1.5) { if (!(color.a > emr_draw.emr_alphaTestRef - 2.0)) discard; gl_FragData[0] = vec4(3.0); return; }
if (emr_draw.emr_alphaTestRef < -1.5 && !(color.a > -2.0 - emr_draw.emr_alphaTestRef)) discard;
