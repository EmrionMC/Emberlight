// RT GI: replaces DoLighting's flat ambient with last frame's filtered indirect light, reprojected, scaled by the pack's
// ambient and light colours. Where no history matches the pack's ambient stays.
uniform sampler2D emrGiFASampler;
uniform sampler2D emrGiFBSampler;
uniform sampler2D emrGiGprevSampler;

vec3 emrRtGi(vec3 packAmbient, vec3 lightCol, vec3 ambientCol, vec3 playerPos, vec3 n) {
	vec3 prev = playerPos + cameraPosition - previousCameraPosition;
	vec4 c = gbufferPreviousProjection * (gbufferPreviousModelView * vec4(prev, 1.0));
	if (c.w <= 0.0) return packAmbient;
	ivec2 size = textureSize(emrGiGprevSampler, 0);
	vec2 hp = (c.xy / c.w * 0.5 + 0.5) * vec2(size) - 0.5;
	ivec2 o = ivec2(floor(hp));
	vec2 f = hp - vec2(o);
	float d = length(prev);
	vec4 a = vec4(0.0), b = vec4(0.0);
	float ws = 0.0;
	for (int i = 0; i < 4; i++) {
		ivec2 q = o + ivec2(i & 1, i >> 1);
		if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
		vec4 g = texelFetch(emrGiGprevSampler, q, 0);
		if (g.w <= 0.0 || abs(g.w - d) > 0.1 + 0.04 * d || !(dot(g.xyz, n) >= 0.7)) continue; // !(>=): NaN rejects
		float w = ((i & 1) == 1 ? f.x : 1.0 - f.x) * ((i >> 1) == 1 ? f.y : 1.0 - f.y) + 0.001;
		a += w * texelFetch(emrGiFASampler, q, 0);
		b += w * texelFetch(emrGiFBSampler, q, 0);
		ws += w;
	}
	if (emrRt_gi_debug == 1) return vec3(ws > 0.0 ? 2.0 : 0.0, 0.0, ws > 0.0 ? 0.0 : 2.0);
	if (ws <= 0.0) return packAmbient;
	a /= ws;
	b /= ws;
	// The pack squares sceneLighting, so ambient lives in sqrt space: sum linearly, return the square root. Open sky gives
	// exactly the pack's ambient; emrRt_gi_sky > 1 keeps half-open surfaces brighter.

	vec3 gi = sqrt(max(ambientCol * ambientCol * (min(a.a * emrRt_gi_sky, 1.0) + b.rgb) + lightCol * lightCol * a.rgb, 0.0)) * emrRt_gi_strength;
	if (emrRt_gi_debug == 2) return gi * 4.0;
	return mix(packAmbient, gi, clamp(ws * 4.0, 0.0, 1.0));
}
