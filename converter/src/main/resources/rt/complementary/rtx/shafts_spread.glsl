// The pack spreads its near samples over the shadow distance (100 blocks in caves and rooms) and stops at the first wall: in
// a room only a handful landed in front of the wall, and each pixel's dithered handful gave grain that the TAA or upscaler
// shows while the camera moves (dark rooms worst, the haze there is all shaft). Short rays now spread emrRt_shafts_samples
// of them up to the wall (fewer than the pack's count: the samples past the wall cost nothing before either); the pack's
// slice weights follow the actual distances, so the fog amount stays.
if (rayEnd < activeThreshold) t = (float(i) + dither) / min(float(nearSamples), emrRt_shafts_samples);
nextDistance = pow(t, sampleDistribution) * min(activeThreshold, rayEnd);
