// RTX block light, denoiser pass 2 (prepare_i): one edge-aware a-trous step (5x5, normal and plane distance like GI's filter),
// emrBlT -> emrBlOut, which the gbuffers read. This frame's accumulation and geometry become next frame's history.
layout(local_size_x = 8, local_size_y = 8) in;
const vec2 workGroupsRender = vec2(1.0, 1.0);
layout(rgba16f) uniform readonly image2D emrBlT;
layout(rgba16f) uniform readonly image2D emrBlG;
layout(rgba16f) uniform writeonly image2D emrBlOut;
layout(rgba16f) uniform writeonly image2D emrBlRH;
layout(rgba16f) uniform writeonly image2D emrBlGH;

void main() {
	ivec2 p = ivec2(gl_GlobalInvocationID.xy), size = imageSize(emrBlG);
	if (any(greaterThanEqual(p, size))) return;
	vec4 g = imageLoad(emrBlG, p), t = imageLoad(emrBlT, p);
	imageStore(emrBlRH, p, t);
	imageStore(emrBlGH, p, g);
	if (g.w <= 0.0 || t.a <= 0.0) {
		imageStore(emrBlOut, p, vec4(0.0));
		return;
	}
	if (emrRt_blocklight_filter == 0) { // DLSS Ray Reconstruction denoises (blocklight:filter=0)
		imageStore(emrBlOut, p, vec4(t.rgb, 1.0));
		return;
	}
	vec3 pos = emrBlPixelPos(p, size, g.w);
	const float k[3] = float[](0.375, 0.25, 0.0625);
	// young history (few frames) is noisier: a wider step
	int step = t.a < 4.0 ? 2 : 1;
	float tol = 0.02 + 0.004 * g.w;
	vec3 s = vec3(0.0);
	float ws = 0.0;
	for (int y = -2; y <= 2; y++)
		for (int x = -2; x <= 2; x++) {
			ivec2 q = p + ivec2(x, y) * step;
			if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
			vec4 gq = imageLoad(emrBlG, q), tq = imageLoad(emrBlT, q);
			if (gq.w <= 0.0 || tq.a <= 0.0) continue;
			vec3 pq = emrBlPixelPos(q, size, gq.w);
			float w = k[abs(x)] * k[abs(y)] * pow(max(dot(gq.xyz, g.xyz), 0.0), 32.0) * exp(-abs(dot(g.xyz, pq - pos)) / tol);
			s += w * tq.rgb;
			ws += w;
		}
	imageStore(emrBlOut, p, vec4(ws > 1e-4 ? s / ws : t.rgb, 1.0));
}
