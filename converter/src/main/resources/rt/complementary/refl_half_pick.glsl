// anchored on the pack's PBR_REFLECTIONS block like the blend patch, so the two always come together
emrRtReflHalf = opaqueSurface && !entityOrParticle && smoothnessD < emrRt_reflections_halfBelow;
ivec2 emrTile = ivec2(gl_FragCoord.xy) >> emrRt_reflections_halfTile;
int emrTileK = emrRt_reflections_halfRate == 2 ? emrTile.x + emrTile.y : (emrTile.x & 1) + 2 * (emrTile.y & 1);
if (emrRtReflHalf && (emrTileK + frameCounter) % emrRt_reflections_halfRate != 0) {
	// only where the pack will find a history: a skipped tile reprojects the reflection as a miss (refDist far - lViewPos, as
	// the pack does), so the history must lie on screen there; the screen edge revealed by a turn traces every frame
	vec4 emrPrev = gbufferModelViewInverse * vec4(viewPos.xyz + (far - lViewPos) * nViewPos, 1.0);
	emrPrev.xyz -= previousCameraPosition - cameraPosition;
	emrPrev = gbufferPreviousProjection * (gbufferPreviousModelView * emrPrev);
	vec3 emrPrevScreen = 0.5 * emrPrev.xyz / emrPrev.w + 0.5;
	emrPrevScreen.z = min(emrPrevScreen.z, 1.0);
	emrRtReflSkip = emrPrev.w > 0.0 && emrPrevScreen == clamp(emrPrevScreen, 0.0, 1.0);
}
