#include "/lib/materials.glsl"
// Untextured lines and shapes (block outline): flat, unlit-looking.
in vec4 tint;
layout(location = EL_OUT_gAlbedo) out vec4 outAlbedo;
layout(location = EL_OUT_gNormal) out vec4 outNormal;
layout(location = EL_OUT_gMat) out vec4 outMat;
void main() {
	if (tint.a < 0.1) discard;
	outAlbedo = vec4(tint.rgb, 1.0);
	outNormal = encodeGNormal(vec3(0.0, 1.0, 0.0), vec3(0.0, 1.0, 0.0), vec2(0.0, 1.0));
	outMat = vec4(0.0, 10.0 / 255.0, 0.0, 0.0);
}
