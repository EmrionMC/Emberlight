#include "/lib/common.glsl"
#include "/lib/materials.glsl"
#include "/lib/water.glsl"

in vec2 uv;
in vec2 light;
in vec4 tint;
in vec3 normal;
in vec3 world;
flat in int blockId;

layout(location = EL_OUT_tAlbedo) out vec4 outAlbedo;
layout(location = EL_OUT_tNormal) out vec4 outNormal;

void main() {
	vec4 albedo = el_albedo(uv) * tint;
	vec3 n = normalize(normal);
	if (!gl_FrontFacing) n = -n;
	int kind = blockId >= 1 && blockId <= 8 && blockId != 6 ? blockId : 6; // 6: other translucents
	if (blockId == ID_WATER) {
		// the water's colour comes from the biome tint; its texture only adds a little variation
		albedo = vec4(tint.rgb * (0.75 + 0.25 * el_luminance(el_albedo(uv).rgb)), 1.0);
		if (abs(n.y) > 0.5) {
			vec3 w = waterNormal(world.xz + el_cameraPosition.xz, world, el_time);
			n = n.y > 0.0 ? w : -w;
		}
	} else if (isGlassId(blockId) && albedo.a < 0.05) {
		// the clear centre of plain glass: kept as a clear pane (no tint) so it gets its thin Fresnel reflection too;
		// discarding it left only the frame texels reflecting, a mosaic of reflection with holes
		albedo = vec4(1.0, 1.0, 1.0, 0.0);
	} else if (albedo.a < el_alphaCutoff()) discard;
	outAlbedo = vec4(albedo.rgb, max(albedo.a, 0.02));
	outNormal = vec4(el_encodeNormal(n), float(kind) / 16.0, light.y);
}
