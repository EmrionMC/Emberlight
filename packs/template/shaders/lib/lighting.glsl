// Shared lighting for the deferred pass and the forward (translucent) pass.
#ifndef TEMPLATE_LIGHTING
#define TEMPLATE_LIGHTING 1

uniform float daylight; // custom uniform from emberlight.json

#if defined(EL_DIMENSION_DEFAULT)
// Radiance of the shadow light: the sun by day, a dim blue moon by night.
vec3 lightRadiance() { return mix(vec3(0.03, 0.05, 0.10), SUN_COLOR * 1.6, daylight); }

// Sky without the sun disc (ambient light).
vec3 skyAmbient(vec3 dir) {
	vec3 zenith = mix(vec3(0.004, 0.006, 0.015), vec3(0.18, 0.35, 0.80), daylight);
	vec3 horizon = mix(vec3(0.02, 0.025, 0.04), vec3(0.65, 0.75, 0.90), daylight);
	return mix(horizon, zenith, pow(clamp(dir.y, 0.0, 1.0), 0.5));
}

vec3 skyRadiance(vec3 dir) {
	float sun = smoothstep(0.9990, 0.9996, dot(dir, el_sunDirection));
	return skyAmbient(dir) + sun * SUN_COLOR * 40.0 * daylight;
}
#elif defined(EL_DIMENSION_NETHER)
vec3 lightRadiance() { return vec3(0.0); }
vec3 skyAmbient(vec3 dir) { return vec3(0.12, 0.03, 0.02); }
vec3 skyRadiance(vec3 dir) { return skyAmbient(dir); }
#else
vec3 lightRadiance() { return vec3(0.0); }
vec3 skyAmbient(vec3 dir) { return vec3(0.04, 0.02, 0.06); }
vec3 skyRadiance(vec3 dir) { return skyAmbient(dir); }
#endif

// albedo linear; n world normal; lm = (block, sky) light 0-1; emission 0-1; ao 0-1; sunVis 0-1.
vec3 shade(vec3 albedo, vec3 n, vec2 lm, float emission, float ao, float sunVis) {
	vec3 direct = lightRadiance() * max(dot(n, el_lightDirection), 0.0) * sunVis;
	vec3 ambient = skyAmbient(normalize(n + vec3(0.0, 1.0, 0.0))) * (lm.y * lm.y) * (0.35 + 0.15 * n.y);
#if !defined(EL_DIMENSION_DEFAULT)
	ambient += skyAmbient(n) * 0.5;
#endif
	vec3 block = vec3(1.0, 0.62, 0.32) * pow(lm.x, 2.5) * 1.8;
	return albedo * (direct + (ambient + block) * ao) + albedo * emission * 4.0;
}

vec3 applyFog(vec3 color, vec3 world) {
#ifdef FOG
	float f = 1.0 - exp(-max(length(world) - el_far * 0.4, 0.0) / (el_far * 0.25));
	return mix(color, skyAmbient(normalize(vec3(world.x, max(world.y, 0.0), world.z))), clamp(f, 0.0, 1.0));
#else
	return color;
#endif
}
#endif
