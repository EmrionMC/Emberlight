// RTX block light, denoiser pass 1 (prepare_h): temporal accumulation of the ratio S / U where last frame's surface matches
// (reprojected, distance and normal), at most emrRt_blocklight_frames frames (3 where a light was just placed or removed). The
// history is held to the spread of this frame's 3x3 neighbourhood, so a moving shadow or a light switched off does not trail.
layout(local_size_x = 8, local_size_y = 8) in;
const vec2 workGroupsRender = vec2($res, $res);
layout(rgba16f) uniform readonly image2D emrBlR;
layout(rgba16f) uniform readonly image2D emrBlG;
layout(rgba16f) uniform readonly image2D emrBlGH;
layout(rgba16f) uniform readonly image2D emrBlRH;
layout(rgba16f) uniform writeonly image2D emrBlT;

void main() {
	ivec2 p = ivec2(gl_GlobalInvocationID.xy), size = imageSize(emrBlG);
	if (any(greaterThanEqual(p, size))) return;
	vec4 g = imageLoad(emrBlG, p), r = imageLoad(emrBlR, p);
	if (g.w <= 0.0 || r.a <= 0.0) {
		imageStore(emrBlT, p, vec4(0.0));
		return;
	}
	EmrBlRays rays = emrBlRays(size);
	vec3 pos = emrBlTapPos(rays, p, g.w);
	vec4 h = vec4(0.0);
	float ws = 0.0;
	vec2 hp;
	if (emrBlPrevTexel(pos, size, hp)) {
		ivec2 o = ivec2(floor(hp - 0.5));
		vec2 f = hp - 0.5 - vec2(o);
		float d = length(pos + cameraPosition - previousCameraPosition);
		for (int i = 0; i < 4; i++) {
			ivec2 q = o + ivec2(i & 1, i >> 1);
			if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
			vec4 gq = imageLoad(emrBlGH, q), hq = imageLoad(emrBlRH, q);
			if (gq.w <= 0.0 || hq.a <= 0.0 || abs(gq.w - d) > 0.05 + 0.03 * d || !(dot(gq.xyz, g.xyz) >= 0.9)) continue;
			float w = ((i & 1) == 1 ? f.x : 1.0 - f.x) * ((i >> 1) == 1 ? f.y : 1.0 - f.y);
			h += w * hq;
			ws += w;
		}
	}
	float len = 1.0;
	vec3 c = r.rgb;
	if (ws > 0.05) {
		h /= ws;
		// spread of this frame's neighbourhood (same surface only)
		vec3 m1 = vec3(0.0), m2 = vec3(0.0);
		float k = 0.0;
		for (int y = -1; y <= 1; y++)
			for (int x = -1; x <= 1; x++) {
				ivec2 q = clamp(p + ivec2(x, y), ivec2(0), size - 1);
				vec4 gq = imageLoad(emrBlG, q), rq = imageLoad(emrBlR, q);
				if (rq.a <= 0.0 || !emrBlSimilar(g, pos, gq, emrBlTapPos(rays, q, gq.w))) continue;
				m1 += rq.rgb;
				m2 += rq.rgb * rq.rgb;
				k += 1.0;
			}
		m1 /= k;
		vec3 sigma = sqrt(max(m2 / k - m1 * m1, 0.0));
		vec3 held = clamp(h.rgb, m1 - emrRt_blocklight_clamp * sigma - 0.02, m1 + emrRt_blocklight_clamp * sigma + 0.02);
		vec4 ch = emrRtLightChange[0];
		bool changed = ch.w > 0.0 && all(greaterThanEqual(pos, ch.xyz)) && all(lessThanEqual(pos, emrRtLightChange[1].xyz));
		len = min(h.a + 1.0, changed ? ch.w : float(emrRt_blocklight_frames));
		c = mix(held, r.rgb, 1.0 / len);
	}
	imageStore(emrBlT, p, vec4(c, len));
}
