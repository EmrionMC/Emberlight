// RT GI pass 1, RTX: emrRt_gi_rays cosine-weighted rays per texel (rtx/gi_common.glsl emrGiBounceRtx). A = (sun bounce, sky
// visibility through clear air), B = (sky bounce + sky seen through glass, weight in frames), C = (block light bounce, 1).
layout(local_size_x = 8, local_size_y = 8) in;
const vec2 workGroupsRender = vec2($res, $res);
layout(rgba16f) uniform writeonly image2D emrGiRA;
layout(rgba16f) uniform writeonly image2D emrGiRB;
layout(rgba16f) uniform writeonly image2D emrGiRC;
layout(rgba16f) uniform writeonly image2D emrGiGcur;

// History length (frames) last frame had where p reprojects to, 0 if none (screen edge while turning, disocclusion):
// the same reprojection and surface test as the temporal pass.
float emrGiPrevLen(vec3 pos, vec3 n, ivec2 size) {
	vec3 prev = pos + cameraPosition - previousCameraPosition;
	vec4 c = gbufferPreviousProjection * (gbufferPreviousModelView * vec4(prev, 1.0));
	if (c.w <= 0.0) return 0.0;
	ivec2 o = ivec2(floor(emrGiTexel(c, vec2(size)) - 0.5));
	float d = length(prev), len = 0.0;
	for (int i = 0; i < 4; i++) {
		ivec2 q = o + ivec2(i & 1, i >> 1);
		if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
		vec4 gq = imageLoad(emrGiGprev, q);
		if (gq.w <= 0.0 || abs(gq.w - d) > 0.05 + 0.03 * d || !(dot(gq.xyz, n) >= 0.9)) continue;
		len = max(len, imageLoad(emrGiHB, q).a);
	}
	return len;
}

void main() {
	ivec2 p = ivec2(gl_GlobalInvocationID.xy);
	if (any(greaterThanEqual(p, imageSize(emrGiRA)))) return;
	// runs before the gbuffers (prepare): no depth yet, the surface comes from the view ray
	vec3 n, eye = gbufferModelViewInverse[3].xyz, d = emrGiDir(p);
	float z;
	bool surface = emrGiPrimary(eye, d, z, n);
	vec3 pos = eye + d * z;
	if (!surface) {
		imageStore(emrGiRA, p, vec4(0.0));
		imageStore(emrGiRB, p, vec4(0.0));
		imageStore(emrGiRC, p, vec4(0.0));
		imageStore(emrGiGcur, p, vec4(0.0));
		return;
	}
	float dist = length(pos);
	vec3 o = pos + n * (0.01 + dist * 0.002);
	vec3 l = normalize(mat3(gbufferModelViewInverse) * shadowLightPosition);
	vec3 sun = vec3(0.0), sky = vec3(0.0), blk = vec3(0.0);
	float open = 0.0;
	uvec3 seed = uvec3(p, uint(frameCounter) * 7919u);
	// beyond emrRt_gi_farDist blocks half the rays: the temporal history absorbs the extra noise there
	int rays = emrRt_gi_farDist > 0.0 && dist > emrRt_gi_farDist ? max(emrRt_gi_rays / 2, 1) : emrRt_gi_rays;
	// Young history gets emrRt_gi_boost times the rays (half that while it is under 4 frames), so a texel entering the
	// screen starts close to converged instead of 1-2 rays blurred by the filter; the temporal pass weights by ray count.
	float young = emrRt_gi_boost > 1 ? emrGiPrevLen(pos, n, imageSize(emrGiRA)) : 99.0;
	if (young < 4.0) rays *= young < 1.0 ? emrRt_gi_boost : max(emrRt_gi_boost / 2, 1);
	for (int i = 0; i < rays; i++) {
		uvec3 h = emrGiPcg(uvec3(p, uint(frameCounter) * uint(emrRt_gi_rays * emrRt_gi_boost) + uint(i)));
		vec3 s, k, bl, tint;
		if (emrGiBounceRtx(o, emrGiCosine(n, vec2(h.xy & 0xFFFFu) / 65536.0), emrGiTMax(), l, seed, s, k, bl, tint)) {
			sun += s;
			sky += k;
			blk += bl;
		} else if (tint == vec3(1.0)) open += 1.0;
		else sky += tint;
	}
	float r = 1.0 / float(rays);
	imageStore(emrGiRA, p, vec4(sun * r, open * r));
	imageStore(emrGiRB, p, vec4(sky * r, max(float(rays) / float(emrRt_gi_rays), 1.0))); // a: weight in frames
	imageStore(emrGiRC, p, vec4(blk * r, 1.0));
	imageStore(emrGiGcur, p, vec4(n, dist));
}
