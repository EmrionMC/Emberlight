vec3 sceneLighting = lightColorM * shadowLightMult + emrRtxGi(ambientColorM * ambientMult, lightColorM, ambientColorM, playerPos, worldGeoNormal, blocklightCol); blockLighting += emrRtxGiBlock;
