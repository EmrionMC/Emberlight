in vec4 tint;

layout(location = EL_OUT_gAlbedo) out vec4 outAlbedo;
layout(location = EL_OUT_gNormal) out vec4 outNormal;
layout(location = EL_OUT_gLight) out vec4 outLight;

void main() {
	if (tint.a < 0.1) discard;
	outAlbedo = vec4(tint.rgb, 1.0);
	outNormal = vec4(el_encodeNormal(vec3(0.0, 1.0, 0.0)), 0.0, 1.0);
	outLight = vec4(0.0, 1.0, 0.0, 1.0);
}
