// RT GI pass 1: emrRt_gi_rays cosine-weighted rays per texel from the opaque depth (one bounce, sun + sky at the hit).
layout(local_size_x = 8, local_size_y = 8) in;
const vec2 workGroupsRender = vec2($res, $res);
layout(rgba16f) uniform writeonly image2D emrGiRA;
layout(rgba16f) uniform writeonly image2D emrGiRB;
layout(rgba16f) uniform writeonly image2D emrGiGcur;

void main() {
	ivec2 p = ivec2(gl_GlobalInvocationID.xy);
	if (any(greaterThanEqual(p, imageSize(emrGiRA)))) return;
	vec3 pos;
	float z;
	if (!emrGiSurface(p, pos, z)) {
		imageStore(emrGiRA, p, vec4(0.0));
		imageStore(emrGiRB, p, vec4(0.0));
		imageStore(emrGiGcur, p, vec4(0.0));
		return;
	}
	vec3 n = emrGiNormal(emrGiFull(p), pos);
	float dist = length(pos);
	vec3 o = pos + n * (0.01 + dist * 0.002);
	vec3 l = normalize(mat3(gbufferModelViewInverse) * shadowLightPosition);
	vec3 sun = vec3(0.0), sky = vec3(0.0);
	float open = 0.0;
	// beyond emrRt_gi_farDist blocks half the rays: the temporal history (emrRt_gi_frames) absorbs the extra noise there
	int rays = emrRt_gi_farDist > 0.0 && dist > emrRt_gi_farDist ? max(emrRt_gi_rays / 2, 1) : emrRt_gi_rays;
	for (int i = 0; i < rays; i++) {
		uvec3 h = emrGiPcg(uvec3(p, uint(frameCounter) * uint(emrRt_gi_rays) + uint(i)));
		vec3 s, k;
		if (emrGiBounce(o, emrGiCosine(n, vec2(h.xy & 0xFFFFu) / 65536.0), emrGiTMax(), l, s, k)) {
			sun += s;
			sky += k;
		} else open += 1.0;
	}
	float r = 1.0 / float(rays);
	imageStore(emrGiRA, p, vec4(sun * r, open * r));
	imageStore(emrGiRB, p, vec4(sky * r, 1.0));
	imageStore(emrGiGcur, p, vec4(n, dist));
}
