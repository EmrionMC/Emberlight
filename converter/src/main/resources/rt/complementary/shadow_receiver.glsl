vec3 shadowPos = GetShadowPos(playerPosM); if (subsurfaceMode == 0) { emrRtRecvPos = playerPos; emrRtRecvN = worldGeoNormal; emrRtRecvXY = shadowPos.xy; } else emrRtRecvXY = vec2(-1.0);
