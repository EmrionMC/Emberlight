// RT GI pass, RTX (plus channel C): a-trous step 1, emrGiTA/TB -> emrGiFA/FB.
layout(local_size_x = 8, local_size_y = 8) in;
const vec2 workGroupsRender = vec2($res, $res);
layout(rgba16f) uniform readonly image2D emrGiTA;
layout(rgba16f) uniform readonly image2D emrGiTB;
layout(rgba16f) uniform readonly image2D emrGiTC;
layout(rgba16f) uniform writeonly image2D emrGiFA;
layout(rgba16f) uniform writeonly image2D emrGiFB;
layout(rgba16f) uniform writeonly image2D emrGiFC;
layout(rgba16f) uniform writeonly image2D emrGiHA;
layout(rgba16f) uniform writeonly image2D emrGiHB;
layout(rgba16f) uniform writeonly image2D emrGiHC;
layout(rgba16f) uniform writeonly image2D emrGiGprev;
vec4 emrGiSrcA(ivec2 q) { return imageLoad(emrGiTA, q); }
vec4 emrGiSrcB(ivec2 q) { return imageLoad(emrGiTB, q); }
vec4 emrGiSrcC(ivec2 q) { return imageLoad(emrGiTC, q); }

void main() {
	ivec2 size = imageSize(emrGiTA);
	ivec2 p;
	bool inside;
	vec4 a, b, c;
	emrGiAtrous(size, 1, p, inside, a, b, c);
	if (!inside) return;
	imageStore(emrGiFA, p, a);
	imageStore(emrGiFB, p, b);
	imageStore(emrGiFC, p, c);
	// this frame's temporal result and geometry become next frame's history (and the gbuffers' lookup geometry)
	imageStore(emrGiHA, p, imageLoad(emrGiTA, p));
	imageStore(emrGiHB, p, imageLoad(emrGiTB, p));
	imageStore(emrGiHC, p, imageLoad(emrGiTC, p));
	imageStore(emrGiGprev, p, imageLoad(emrGiGcur, p));
}
