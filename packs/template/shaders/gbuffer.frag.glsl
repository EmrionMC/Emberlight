in vec2 uv;
in vec2 light;
in vec4 tint;
in vec3 normal;
flat in float emission;

layout(location = EL_OUT_gAlbedo) out vec4 outAlbedo;
layout(location = EL_OUT_gNormal) out vec4 outNormal;
layout(location = EL_OUT_gLight) out vec4 outLight;

void main() {
	vec4 albedo = el_albedo(uv) * vec4(tint.rgb, 1.0);
	if (albedo.a < el_alphaCutoff()) discard;
#if defined(EL_CLASS_ENTITIES) || defined(EL_CLASS_BLOCK_ENTITIES)
	albedo.rgb = mix(albedo.rgb, el_entityColor().rgb, el_entityColor().a); // hurt flash
#endif
	// LabPBR emission from a resource pack's _s texture, else the block's light level
	float pbrEmission = el_material(el_specularMap(uv)).emission;
	outAlbedo = vec4(albedo.rgb, 1.0);
	outNormal = vec4(el_encodeNormal(normalize(normal)), 0.0, 1.0);
	outLight = vec4(light, max(emission * 0.5, pbrEmission), tint.a); // separateAo: vertex AO in alpha
}
