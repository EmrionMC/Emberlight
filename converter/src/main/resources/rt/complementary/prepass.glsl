// The prepass pipeline (alpha ref 2, colour masked) stops right after the pack's alpha test with an alpha that passes the
// converter's test. Its coverage is final: the shading pass after it (alpha ref -2) skips every later discard, so a texel whose
// alpha drops later (POM resampling, seasons) is shaded instead of leaving a hole with depth but no colour.
if (color.a <= 0.5) discard;
if (emr_draw.emr_alphaTestRef > 1.5) { gl_FragData[0] = vec4(3.0); return; }
