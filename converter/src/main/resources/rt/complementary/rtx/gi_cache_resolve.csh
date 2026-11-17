// RTX GI radiance cache, resolve (gi:cache, rtx/gi_cache.glsl): per entry, this frame's samples blended into the resolved
// light, at most emrRt_gi_cacheFrames frames of history (counted in this frame's samples: the old light keeps at most
// cacheFrames - 1 times their weight). A sample from a light-change box drops the history: the trace skips the cache
// there while the box lasts, and the entry restarts from the new light (unused until it has cacheMin samples). Entries
// without a sample for emrRt_gi_cacheStale frames are evicted. The sums are cleared for the next update.
layout(local_size_x = 8, local_size_y = 8) in;
const ivec3 workGroups = ivec3(128, 128, 1);

void main() {
	uint i = gl_GlobalInvocationID.x + gl_GlobalInvocationID.y * 1024u;
	if (imageLoad(emrGiCk, emrGcAt(i, 0)).r == 0u) return; // empty: its sums are 0 too
	uint cw = imageLoad(emrGiCs, emrGcAt(i, 9)).r;
	float m = float(cw & 0x7FFFFFFFu);
	vec4 a = imageLoad(emrGiCr, emrGcAt(i, 0)), b = imageLoad(emrGiCr, emrGcAt(i, 1)), c = imageLoad(emrGiCr, emrGcAt(i, 2));
	if (m == 0.0) {
		b.w += 1.0;
		if (b.w > emrRt_gi_cacheStale) {
			imageStore(emrGiCk, emrGcAt(i, 0), uvec4(0u));
			a = b = c = vec4(0.0);
		}
		imageStore(emrGiCr, emrGcAt(i, 1), b);
		imageStore(emrGiCr, emrGcAt(i, 0), a);
		imageStore(emrGiCr, emrGcAt(i, 2), c);
		return;
	}
	vec3 s[3];
	for (int r = 0; r < 3; r++) {
		s[r] = vec3(imageLoad(emrGiCs, emrGcAt(i, r * 3)).r, imageLoad(emrGiCs, emrGcAt(i, r * 3 + 1)).r, imageLoad(emrGiCs, emrGcAt(i, r * 3 + 2)).r) / EMR_GC_SCALE;
		for (int q = 0; q < 3; q++) imageStore(emrGiCs, emrGcAt(i, r * 3 + q), uvec4(0u));
	}
	imageStore(emrGiCs, emrGcAt(i, 9), uvec4(0u));
	float old = (cw & 0x80000000u) != 0u ? 0.0 : min(a.w, m * (emrRt_gi_cacheFrames - 1.0)), w = 1.0 / (old + m);
	a.rgb = (a.rgb * old + s[0]) * w;
	b.rgb = (b.rgb * old + s[1]) * w;
	c.rgb = (c.rgb * old + s[2]) * w;
	imageStore(emrGiCr, emrGcAt(i, 0), vec4(a.rgb, min(old + m, 60000.0)));
	imageStore(emrGiCr, emrGcAt(i, 1), vec4(b.rgb, 0.0));
	imageStore(emrGiCr, emrGcAt(i, 2), vec4(c.rgb, 0.0));
}
