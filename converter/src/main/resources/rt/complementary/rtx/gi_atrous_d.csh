// RT GI pass, RTX (plus channel C): a-trous step 2, emrGiFA/FB -> emrGiRA/RB.
layout(local_size_x = 8, local_size_y = 8) in;
const vec2 workGroupsRender = vec2($res, $res);
layout(rgba16f) uniform readonly image2D emrGiFA;
layout(rgba16f) uniform readonly image2D emrGiFB;
layout(rgba16f) uniform readonly image2D emrGiFC;
layout(rgba16f) uniform writeonly image2D emrGiRA;
layout(rgba16f) uniform writeonly image2D emrGiRB;
layout(rgba16f) uniform writeonly image2D emrGiRC;
vec4 emrGiSrcA(ivec2 q) { return imageLoad(emrGiFA, q); }
vec4 emrGiSrcB(ivec2 q) { return imageLoad(emrGiFB, q); }
vec4 emrGiSrcC(ivec2 q) { return imageLoad(emrGiFC, q); }

void main() {
	if (emrRt_gi_filter == 0) return; // Ray Reconstruction (no a-trous): C already wrote the result to F, D and E would only copy it F -> R -> F
	ivec2 size = imageSize(emrGiFA);
	ivec2 p;
	bool inside;
	vec4 a, b, c;
	emrGiAtrous(size, 2, p, inside, a, b, c);
	if (!inside) return;
	imageStore(emrGiRA, p, a);
	imageStore(emrGiRB, p, b);
	imageStore(emrGiRC, p, c);
}
