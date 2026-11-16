// RT GI pass 2, RTX (gi_temporal.csh plus block-light channel C): temporal accumulation over emrRt_gi_frames frames where
// the reprojected surface matches (distance and normal).

layout(local_size_x = 8, local_size_y = 8) in;
const vec2 workGroupsRender = vec2($res, $res);
layout(rgba16f) uniform readonly image2D emrGiRA;
layout(rgba16f) uniform readonly image2D emrGiRB;
layout(rgba16f) uniform readonly image2D emrGiRC;
layout(rgba16f) uniform readonly image2D emrGiGcur;
layout(rgba16f) uniform readonly image2D emrGiHA;
layout(rgba16f) uniform readonly image2D emrGiHB;
layout(rgba16f) uniform readonly image2D emrGiHC;
layout(rgba16f) uniform readonly image2D emrGiGprev;
layout(rgba16f) uniform writeonly image2D emrGiTA;
layout(rgba16f) uniform writeonly image2D emrGiTB;
layout(rgba16f) uniform writeonly image2D emrGiTC;
// RtLights header: [0] = min corner of the box where block lights were just added or removed + history cap there (0 = none), [1] = max corner
layout(std430) readonly buffer EmrRtLights {
	vec4 emrRtLightOrigin;
	ivec4 emrRtLightDims;
	vec4 emrRtLightChange[2];
};

void main() {
	ivec2 p = ivec2(gl_GlobalInvocationID.xy);
	ivec2 size = imageSize(emrGiRA);
	if (any(greaterThanEqual(p, size))) return;
	vec4 g = imageLoad(emrGiGcur, p);
	vec4 a = imageLoad(emrGiRA, p), b = imageLoad(emrGiRB, p), c3 = imageLoad(emrGiRC, p);
	if (g.w <= 0.0) {
		imageStore(emrGiTA, p, vec4(0.0));
		imageStore(emrGiTB, p, vec4(0.0));
		imageStore(emrGiTC, p, vec4(0.0));
		return;
	}
	vec3 pos = emrGiPos(p, g.w);
	vec3 prev = pos + cameraPosition - previousCameraPosition;
	vec4 c = gbufferPreviousProjection * (gbufferPreviousModelView * vec4(prev, 1.0));
	float d = length(prev);
	vec4 ha = vec4(0.0), hb = vec4(0.0), hc = vec4(0.0);
	float ws = 0.0;
	if (c.w > 0.0) {
		vec2 hp = emrGiTexel(c, vec2(size)) - 0.5;
		ivec2 o = ivec2(floor(hp));
		vec2 f = hp - vec2(o);
		for (int i = 0; i < 4; i++) {
			ivec2 q = o + ivec2(i & 1, i >> 1);
			if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
			vec4 gq = imageLoad(emrGiGprev, q);
			float w = ((i & 1) == 1 ? f.x : 1.0 - f.x) * ((i >> 1) == 1 ? f.y : 1.0 - f.y);
			if (gq.w <= 0.0 || abs(gq.w - d) > 0.05 + 0.03 * d || !(dot(gq.xyz, g.xyz) >= 0.9)) continue; // !(>=): NaN rejects
			ha += w * imageLoad(emrGiHA, q);
			hb += w * imageLoad(emrGiHB, q);
			hc += w * imageLoad(emrGiHC, q);
			ws += w;
		}
	}
	float len = b.a; // this frame's samples in frames (more than 1 where the trace boosted young history)
	// a light placed or removed nearby: the history holds the old light, so keep only a few frames of it (the trace then boosts)
	vec4 ch = emrRtLightChange[0];
	float cap = ch.w > 0.0 && all(greaterThanEqual(pos, ch.xyz)) && all(lessThanEqual(pos, emrRtLightChange[1].xyz)) ? ch.w : float(emrRt_gi_frames);
	if (ws > 0.05) {
		ha /= ws;
		hb /= ws;
		hc /= ws;
		len = min(hb.a + b.a, cap);
		float t = min(b.a / len, 1.0);
		a = mix(ha, a, t);
		b.rgb = mix(hb.rgb, b.rgb, t);
		c3 = mix(hc, c3, t);
	}
	imageStore(emrGiTA, p, a);
	imageStore(emrGiTB, p, vec4(b.rgb, len));
	imageStore(emrGiTC, p, c3);
}
