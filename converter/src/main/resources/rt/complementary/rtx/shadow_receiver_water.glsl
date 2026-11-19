vec3 shadowPos = GetShadowPos(playerPosM); if (subsurfaceMode == 0) { emrRtRecvPos = playerPos; emrRtRecvN = worldGeoNormal; emrRtRecvXY = shadowPos.xy; } else emrRtRecvXY = vec2(-1.0);
// the water surface itself (shadows:waterRays, 0 = as everywhere); centerShadowBias marks a refraction hit lit here (see
// refraction_water_out.glsl). shortcut: with refraction:blockLight=0 and sunRays=0 hits get the water's count too
emrRtxRecvRays = mat == 32000 && !centerShadowBias ? emrRt_shadows_waterRays : 0;
