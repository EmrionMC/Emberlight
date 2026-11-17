// RTX GI radiance cache, update (gi:cache, rtx/gi_cache.glsl): one short path per 1/emrRt_gi_cacheRate^2 screen pixels (a
// random pixel of the tile each frame). From the camera's surface two bounce rays go on (cosine-weighted); a third ray's hit
// takes its light from the cache (or, uncached, its direct light). Each of the two bounce vertices gets one sample of the
// light arriving there: its direct light plus emrRt_gi_cacheBounce x what its own bounce ray brought back, in the units of the
// GI channels (A sun, B sky incl. sky seen through glass, C block light; rtx/gi_trace.csh). Runs after the GI passes: the
// cache is resolved (gi_cache_resolve.csh) for the next frame's trace.
layout(local_size_x = 8, local_size_y = 8) in;
const vec2 workGroupsRender = vec2($cacheRate, $cacheRate);

void main() {
	uvec3 seed = emrGiPcg(uvec3(gl_GlobalInvocationID.xy, uint(frameCounter) * 7919u + 0x68E31DA4u));
	vec2 px = (vec2(gl_GlobalInvocationID.xy) + vec2(seed.xy & 0xFFFFu) / 65536.0) / emrRt_gi_cacheRate;
	if (any(greaterThanEqual(px, vec2(viewWidth, viewHeight)))) return;
	vec4 vr = gbufferProjectionInverse * vec4(px / vec2(viewWidth, viewHeight) * 2.0 - 1.0, -1.0, 1.0);
	vec3 d = normalize(mat3(gbufferModelViewInverse) * (vr.xyz / vr.w)), eye = gbufferModelViewInverse[3].xyz, n;
	float z;
	if (!emrGiPrimary(eye, d, z, n)) return;
	vec3 l = normalize(mat3(gbufferModelViewInverse) * shadowLightPosition);
	vec3 o = eye + d * z + n * (0.01 + z * 0.002);
	// forward: vertices 0 and 1 with their direct light; then what reaches vertex 1 (cache or direct light at vertex 2)
	EmrGiHit h[2];
	vec3 ds[2], dk[2], db[2], tin[2];
	int count = 0;
	vec3 sun = vec3(0.0), sky = vec3(0.0), blk = vec3(0.0); // light leaving the last vertex towards the one before
	for (int k = 0; k < 3; k++) {
		vec3 dir = emrGiCosine(n, vec2(seed.xy >> 16u) / 65536.0);
		seed = emrGiPcg(seed);
		EmrGiHit hk;
		vec3 tint;
		int r = emrGiTraceRtx(o, dir, emrGiTMax(), hk, tint);
		if (r == 0) {
			if (tint != vec3(1.0)) sky = tint; // sky through glass, as the trace counts it
			break;
		}
		if (r == 1) break;
		if (k == 2) {
			if (!emrGcRead(hk.p, hk.n, sun, sky, blk)) emrGiDirectRtx(hk, l, seed, sun, sky, blk);
			vec3 f = hk.albedo * tint;
			sun *= f;
			sky *= f;
			blk *= f;
			break;
		}
		h[k] = hk;
		tin[k] = tint;
		emrGiDirectRtx(hk, l, seed, ds[k], dk[k], db[k]);
		count = k + 1;
		n = hk.n;
		o = hk.p + n * (0.01 + length(hk.p) * 0.002);
	}
	// backward: incoming light at each vertex, added to its voxel
	for (int k = count - 1; k >= 0; k--) {
		vec3 es = ds[k] + sun * emrRt_gi_cacheBounce, ek = dk[k] + sky * emrRt_gi_cacheBounce, eb = db[k] + blk * emrRt_gi_cacheBounce;
		emrGcAdd(h[k].p, h[k].n, es, ek, eb, seed);
		vec3 f = h[k].albedo * tin[k];
		sun = es * f;
		sky = ek * f;
		blk = eb * f;
	}
}
