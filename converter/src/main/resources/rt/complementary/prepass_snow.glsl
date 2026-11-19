// Euphoria's distant snow-layer dither (IPBR): not in the shading pass after the prepass, which already gave the texel depth.
if (dither + 0.25 < snowFadeOut && emr_draw.emr_alphaTestRef > -1.5) discard;
