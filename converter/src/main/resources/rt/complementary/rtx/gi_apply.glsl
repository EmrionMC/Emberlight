// RT style RTX, GI in the pack's lighting: like emrRtGi (gi_apply.glsl), with sky light seen through glass in B and the
// bounced block light C, which emrRtxGi leaves in emrRtxGiBlock (pack blockLighting units) for the caller to add.
uniform sampler2D emrGiFCSampler;
vec3 emrRtxGiBlock = vec3(0.0);
const vec2 EMR_GI_RING[8] = vec2[8](vec2(1, 0), vec2(-1, 0), vec2(0, 1), vec2(0, -1), vec2(0.7, 0.7), vec2(-0.7, 0.7), vec2(0.7, -0.7), vec2(-0.7, -0.7));

vec3 emrRtxGi(vec3 packAmbient, vec3 lightCol, vec3 ambientCol, vec3 playerPos, vec3 n, vec3 baseCol) {
	emrRtxGiBlock = vec3(0.0);
	// The GI passes ran in prepare on this frame's view (emrGiGprev holds this frame's geometry by now): no reprojection.
	vec4 c = gbufferProjection * (gbufferModelView * vec4(playerPos, 1.0));
	if (c.w <= 0.0) return packAmbient;
	ivec2 size = textureSize(emrGiGprevSampler, 0);
	vec2 hp = (c.xy / c.w * 0.5 + 0.5) * vec2(size) - 0.5;
	ivec2 o = ivec2(floor(hp));
	vec2 f = hp - vec2(o);
	float d = length(playerPos);
	vec4 a = vec4(0.0), b = vec4(0.0), k = vec4(0.0);
	float ws = 0.0;
	for (int i = 0; i < 4; i++) {
		ivec2 q = o + ivec2(i & 1, i >> 1);
		if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
		vec4 g = texelFetch(emrGiGprevSampler, q, 0);
		if (g.w <= 0.0 || abs(g.w - d) > 0.1 + 0.04 * d || !(dot(g.xyz, n) >= 0.7)) continue;
		float w = ((i & 1) == 1 ? f.x : 1.0 - f.x) * ((i >> 1) == 1 ? f.y : 1.0 - f.y) + 0.001;
		a += w * texelFetch(emrGiFASampler, q, 0);
		b += w * texelFetch(emrGiFBSampler, q, 0);
		k += w * texelFetch(emrGiFCSampler, q, 0);
		ws += w;
	}
	if (ws <= 0.0) {
		// No GI surface matches (what the trace's view ray did not hit as raster drew it: an entity over the BLAS budget, a
		// mip-dependent cutout edge): take the nearest one facing the same way.

		ivec2 cq = clamp(o, ivec2(0), size - 1);
		float n0 = 0.0;
		for (int r = 2; r <= 32 && n0 == 0.0; r *= 2) {
			for (int i = 0; i < 8; i++) {
				ivec2 q = clamp(cq + ivec2(EMR_GI_RING[i] * float(r)), ivec2(0), size - 1);
				vec4 g = texelFetch(emrGiGprevSampler, q, 0);
				if (g.w <= 0.0 || abs(g.w - d) > 0.5 + 0.25 * d || !(dot(g.xyz, n) >= 0.9)) continue;
				// and in this surface's plane: an ice sheet (translucent, never a GI surface) took the GI of the iceberg steps
				// above it and of the sunken ones below, in triangles where one ring sample matched and the next did not
				vec4 v = gbufferProjectionInverse * vec4((vec2(q) + 0.5) / vec2(size) * 2.0 - 1.0, -1.0, 1.0);
				vec3 pq = gbufferModelViewInverse[3].xyz + normalize(mat3(gbufferModelViewInverse) * (v.xyz / v.w)) * g.w;
				if (abs(dot(n, pq - playerPos)) > 0.6 + 0.01 * d) continue;
				a += texelFetch(emrGiFASampler, q, 0);
				b += texelFetch(emrGiFBSampler, q, 0);
				k += texelFetch(emrGiFCSampler, q, 0);
				n0 += 1.0;
			}
		}
		if (n0 > 0.0) { // plain average, full weight
			a /= n0;
			b /= n0;
			k /= n0;
			ws = 1.0;
			if (emrRt_gi_debug == 3) return vec3(2.0, 0.0, 0.0);
		}
	}
	if (emrRt_gi_debug == 1) return vec3(ws > 0.0 ? 2.0 : 0.0, 0.0, ws > 0.0 ? 0.0 : 2.0);
	if (ws <= 0.0) return packAmbient;
	a /= ws;
	b /= ws;
	k /= ws;
	if (emrRt_gi_debug == 3) return vec3(0.0, 2.0 * b.a / float(emrRt_gi_frames), 0.0); // history length, green = full
	float fade = clamp(ws * 4.0, 0.0, 1.0);
	// k carries squared source colours (RTX units); the pack's block light is in their square root: its hue, k's brightness
	vec3 kh = pow(max(k.rgb, 0.0), vec3(emrRt_gi_blockHue));
	kh *= dot(k.rgb, vec3(0.2125, 0.7154, 0.0721)) / max(dot(kh, vec3(0.2125, 0.7154, 0.0721)), 1e-6);
	emrRtxGiBlock = kh * fade * 10.0 * dot(baseCol, vec3(0.2125, 0.7154, 0.0721)) * emrRt_gi_blockStrength;
	vec3 gi = sqrt(max(ambientCol * ambientCol * (min(a.a * emrRt_gi_sky, 1.0) + b.rgb) + lightCol * lightCol * a.rgb, 0.0)) * emrRt_gi_strength;
	if (emrRt_gi_debug == 2) return gi * 4.0;
	if (emrRt_gi_debug == 4) emrRtxGiBlock = vec3(0.0); // without GI block light
	if (emrRt_gi_debug == 5) { emrRtxGiBlock *= 4.0; return vec3(0.0); } // GI block light only (x4)
	return mix(packAmbient, gi, fade);
}
