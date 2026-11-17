#include "/lib/lighting.glsl"

in vec2 uv;
in vec2 light;
in vec4 tint;
in vec3 normal;
in vec3 world;
flat in int blockId;

layout(location = EL_OUT_lit) out vec4 outLit;

void main() {
	vec4 albedo = el_albedo(uv) * tint;
	if (albedo.a < el_alphaCutoff()) discard;
	vec3 n = normalize(normal);
	if (blockId == 1) albedo = vec4(0.10, 0.25, 0.40, 0.55); // water (materials.blocks 1)
	vec3 c = shade(el_srgbToLinear(albedo.rgb), n, light, 0.0, 1.0, smoothstep(0.85, 0.95, light.y));
	outLit = vec4(applyFog(c, world), albedo.a);
}
