// RTX GI without the radiance cache (gi:cache off): every bounce hit is lit by its own rays (rtx/gi_common.glsl).
bool emrGiCacheGet(vec3 p, vec3 n, float t, bool atomic, out vec3 sun, out vec3 sky, out vec3 blk) {
	sun = sky = blk = vec3(0.0);
	return false;
}
