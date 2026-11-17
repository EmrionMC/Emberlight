// DLSS Ray Reconstruction guides (docs/NATIVE_FORMAT.md §14.4), only compiled while RR runs. After post: the colour RR
// denoises is the finished SDR frame in "lit", so the albedos are sRGB-encoded like it (colour / albedo stays the lighting).
// Opaque gbuffer surfaces: their diffuse and pre-integrated specular albedo, shading normal and roughness. Sky, water,
// glass, particles and the hand: the colour itself as albedo (RR then keeps them as they are), rough.
#include "/lib/common.glsl"
#include "/lib/materials.glsl"

void main() {
	ivec2 px = ivec2(gl_GlobalInvocationID.xy);
	if (any(greaterThanEqual(px, ivec2(el_screenSize)))) return;
	vec3 color = texelFetch(lit, px, 0).rgb;
	float depth = texelFetch(el_depth, px, 0).r;
	vec3 world = el_screenToWorld((vec2(px) + 0.5) / el_screenSize, depth);
	if (el_isSky(depth)) {
		imageStore(el_rrDiffuse_img, px, vec4(color, 1.0));
		imageStore(el_rrSpecular_img, px, vec4(0.0));
		imageStore(el_rrNormal_img, px, vec4(0.0));
		return;
	}
	vec4 ta = texelFetch(tAlbedo, px, 0);
	if (ta.a > 0.0 || texelFetch(fwd, px, 0).a > 0.5) {
		vec3 n = ta.a > 0.0 ? el_decodeNormal(texelFetch(tNormal, px, 0).xy) : -normalize(world);
		imageStore(el_rrDiffuse_img, px, vec4(color, 1.0));
		imageStore(el_rrSpecular_img, px, vec4(0.0));
		imageStore(el_rrNormal_img, px, vec4(n, 1.0));
		return;
	}
	vec3 albedo = el_srgbToLinear(texelFetch(gAlbedo, px, 0).rgb);
	GNormal g = decodeGNormal(texelFetch(gNormal, px, 0));
	Mat m = decodeMat(texelFetch(gMat, px, 0));
	vec3 sn = specularNormal(g.n, g.geo); // the normal lighting.frag.glsl reflects about
	float nv = max(dot(sn, -normalize(world)), 1e-3);
	vec3 fe = envBrdf(matF0(m, albedo), 1.0 - m.smoothness, nv); // as lighting.frag.glsl weighs its reflections
	vec3 kd = isMetal(m.f0) ? vec3(0.0) : 1.0 - fe;
	imageStore(el_rrDiffuse_img, px, vec4(el_linearToSrgb(albedo * kd), 1.0));
	imageStore(el_rrSpecular_img, px, vec4(el_linearToSrgb(min(fe, vec3(1.0))), 1.0));
	imageStore(el_rrNormal_img, px, vec4(sn, 1.0 - m.smoothness)); // perceptual roughness
}
