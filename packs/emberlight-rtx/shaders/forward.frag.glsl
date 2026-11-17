#include "/lib/common.glsl"

in vec2 uv;
in vec2 light;
in vec4 tint;
in vec3 normal;
flat in float emission;

layout(location = EL_OUT_fwd) out vec4 outFwd;

void main() {
	vec4 albedo = el_albedo(uv) * tint;
	if (albedo.a < max(el_alphaCutoff(), 0.004)) discard;
#if defined(EL_CLASS_HAND) || defined(EL_CLASS_ENTITIES_TRANSLUCENT)
	albedo.rgb = mix(albedo.rgb, el_entityColor().rgb, el_entityColor().a);
#endif
	vec3 a = el_srgbToLinear(albedo.rgb);
	vec3 n = normalize(normal);
#if defined(EL_CLASS_WEATHER)
	// rain and snow: lit by the sky, slightly glowing so the streaks read in the dark
	outFwd = vec4(a * (skyAmbient(vec3(0.0, 1.0, 0.0)) * 1.4 * light.y + lightmapBlock(light.x)) + a * 0.02, albedo.a * 0.55);
#else
	vec3 l = lightDir();
	float sunVis = smoothstep(0.82, 0.95, light.y);
#if defined(EL_CLASS_PARTICLES)
	// opaque particles (rain splashes, block dust, crit sparks) are tiny camera-facing quads: lit forward like the rain
	// itself instead of in the gbuffer, where each was a lone surface the half-res GI and its filters left dark
	vec3 c = a * (lightColor() * sunVis * 0.5 + skyAmbient(vec3(0.0, 1.0, 0.0)) * 1.2 * light.y + lightmapBlock(light.x)) + a * 0.02;
	// rain splashes and drips: their sprite is saturated dark blue (0, 54, 204), which under warm light and a night sky
	// became opaque dark-blue squares. Shade those texels as water drops: pale, half see-through, catching the light.
	float water = smoothstep(0.35, 0.6, albedo.b - max(albedo.r, albedo.g * 0.8));
	if (water > 0.0) {
		vec3 drop = vec3(0.55, 0.7, 1.0) * (skyAmbient(vec3(0.0, 1.0, 0.0)) * 1.5 * light.y + lightmapBlock(light.x) + lightColor() * sunVis * 0.3 + 0.01);
		c = mix(c, drop, water);
		albedo.a *= 1.0 - 0.5 * water;
	}
#else
	vec3 c = a * (lightColor() * max(dot(n, l), 0.0) * sunVis * 0.9 + skyAmbient(n) * light.y * light.y + lightmapBlock(light.x));
#endif
#if defined(EL_CLASS_PARTICLES_TRANSLUCENT)
	c += a * smoothstep(0.9, 1.0, light.x) * 1.5 * EMISSIVE_STRENGTH; // flames, lava drips, sparks glow
#endif
	c += a * emission * EMISSIVE_STRENGTH * 2.0;
#if defined(EL_CLASS_GLINT)
	c = a * 2.0; albedo.a *= 0.5;
#endif
	outFwd = vec4(c, albedo.a);
#endif
}
