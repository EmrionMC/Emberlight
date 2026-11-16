vec3 sceneLighting = lightColorM * shadowLightMult + emrRtGi(ambientColorM * ambientMult, lightColorM, ambientColorM, playerPos, worldGeoNormal);
