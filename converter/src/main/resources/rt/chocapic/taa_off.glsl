// Upscaling: FSR/DLSS replaces the TAA resolve (the history stays this frame; Bliss/Eclipse take this path with TAA off too)
return vec4(texture(colortex3, texcoord).rgb, 1.0);
