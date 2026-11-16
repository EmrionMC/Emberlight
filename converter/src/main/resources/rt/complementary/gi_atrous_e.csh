// RT GI pass: a-trous step 4, emrGiRA/RB -> emrGiFA/FB.
layout(local_size_x = 8, local_size_y = 8) in;
const vec2 workGroupsRender = vec2($res, $res);
layout(rgba16f) uniform readonly image2D emrGiRA;
layout(rgba16f) uniform readonly image2D emrGiRB;
layout(rgba16f) uniform writeonly image2D emrGiFA;
layout(rgba16f) uniform writeonly image2D emrGiFB;
vec4 emrGiSrcA(ivec2 q) { return imageLoad(emrGiRA, q); }
vec4 emrGiSrcB(ivec2 q) { return imageLoad(emrGiRB, q); }

void main() {
	if (emrRt_gi_filter == 0) return; // Ray Reconstruction (no a-trous): C already wrote the result to F, D and E would only copy it F -> R -> F
	ivec2 size = imageSize(emrGiRA);
	ivec2 p;
	bool inside;
	vec4 a, b;
	emrGiAtrous(size, 4, p, inside, a, b);
	if (!inside) return;
	imageStore(emrGiFA, p, a);
	imageStore(emrGiFB, p, b);
}
