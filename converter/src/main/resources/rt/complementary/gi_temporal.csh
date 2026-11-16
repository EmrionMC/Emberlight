// RT GI pass 2: temporal accumulation. Reprojects into last frame's history (camera motion; terrain is static) and keeps
// it where the surface matches (distance and normal), up to emrRt_gi_frames frames.
layout(local_size_x = 8, local_size_y = 8) in;
const vec2 workGroupsRender = vec2($res, $res);
layout(rgba16f) uniform readonly image2D emrGiRA;
layout(rgba16f) uniform readonly image2D emrGiRB;
layout(rgba16f) uniform readonly image2D emrGiGcur;
layout(rgba16f) uniform readonly image2D emrGiHA;
layout(rgba16f) uniform readonly image2D emrGiHB;
layout(rgba16f) uniform readonly image2D emrGiGprev;
layout(rgba16f) uniform writeonly image2D emrGiTA;
layout(rgba16f) uniform writeonly image2D emrGiTB;

void main() {
	ivec2 p = ivec2(gl_GlobalInvocationID.xy);
	ivec2 size = imageSize(emrGiRA);
	if (any(greaterThanEqual(p, size))) return;
	vec4 g = imageLoad(emrGiGcur, p);
	vec4 a = imageLoad(emrGiRA, p), b = imageLoad(emrGiRB, p);
	if (g.w <= 0.0) {
		imageStore(emrGiTA, p, vec4(0.0));
		imageStore(emrGiTB, p, vec4(0.0));
		return;
	}
	vec3 pos;
	float z;
	emrGiSurface(p, pos, z);
	vec3 prev = pos + cameraPosition - previousCameraPosition;
	vec4 c = gbufferPreviousProjection * (gbufferPreviousModelView * vec4(prev, 1.0));
	float d = length(prev);
	vec4 ha = vec4(0.0), hb = vec4(0.0);
	float ws = 0.0;
	if (c.w > 0.0) {
		vec2 hp = (c.xy / c.w * 0.5 + 0.5) * vec2(size) - 0.5;
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
			ws += w;
		}
	}
	float len = 1.0;
	if (ws > 0.05) {
		ha /= ws;
		hb /= ws;
		len = min(hb.a + 1.0, float(emrRt_gi_frames));
		a = mix(ha, a, 1.0 / len);
		b.rgb = mix(hb.rgb, b.rgb, 1.0 / len);
	}
	imageStore(emrGiTA, p, a);
	imageStore(emrGiTB, p, vec4(b.rgb, len));
}
