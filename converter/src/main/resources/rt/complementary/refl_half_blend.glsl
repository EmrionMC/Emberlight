// reduced-rate tiles: a skipped frame keeps the history as it is (its own reflection has no RT part, mostly sky: blending it
// in, even where the history is poor, brightens foliage); a traced frame takes halfRate times the pack's blend. A skipped
// frame over a poor history (disocclusion) keeps the history's old depth too, so the next traced frame still replaces it.
float emrBlend = min1(minBlendFactor / prevValid);
if (emrRtReflSkip) {
	reflectOutput.rgb = prevRef.rgb;
	reflectOutput.a = emrBlend < 0.5 ? linearZ1 : prevRef.a;
} else {
	if (emrRtReflHalf) emrBlend = min1(float(emrRt_reflections_halfRate) * emrBlend);
	reflectOutput.rgb = mix(prevRef.rgb, reflectOutput.rgb, emrBlend);
	reflectOutput.a = linearZ1;
}
