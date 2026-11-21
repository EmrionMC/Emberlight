// r5.10's distant snow-layer dither (0.01 instead of 0.25): skipped in the shading pass after the prepass, as prepass_snow.glsl.
if (dither + 0.01 < snowFadeOut && emr_draw.emr_alphaTestRef > -1.5) discard;
