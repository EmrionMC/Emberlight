// the water surface itself, not a refraction hit (centerShadowBias, see refraction_water_out.glsl). shortcut: with
// refraction:blockLight=0 hits lose the search too
emrRtxGiNoSearch = mat == 32000 && !centerShadowBias && emrRt_gi_waterSearch == 0;
vec3 sceneLighting = lightColorM * shadowLightMult + emrRtxGi(ambientColorM * ambientMult, lightColorM, ambientColorM, playerPos, worldGeoNormal, blocklightCol); blockLighting += emrRtxGiBlock;
