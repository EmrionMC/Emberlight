// Replaces the SSAO estimate; keeps the pack's hand/entity cutoff and its AO strength slider (SSAO_I * 0.004).
if (z0 < 0.56) return 1.0;
return pow(texelFetch(emrRtAo, ivec2(gl_FragCoord.xy), 0).r, float(emrRt_ao_strength) * 0.004);
