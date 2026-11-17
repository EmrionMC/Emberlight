// Participating media, as Bedrock's volumetric fog describes them: AIR and WATER, each with scattering and absorption
// coefficients (per block) and a Henyey-Greenstein anisotropy; plus aerial perspective from the atmosphere LUTs.
// biomeHumid, biomeDry, biomeCold, humidity: custom uniforms (emberlight.json) from the biome's temperature and downfall.
#ifndef RTX_FOG
#define RTX_FOG 1
#include "/lib/common.glsl"

// low ground mist on clear mornings (worldTime ~22500-3500), thickest in humid biomes (forests, swamps, jungles)
float morningMist() {
	float t = mod(float(el_worldTime) + 1500.0, 24000.0);
	return smoothstep(0.0, 800.0, t) * (1.0 - smoothstep(2500.0, 4500.0, t)) * (1.0 - rainSmooth);
}

// AIR extinction (per block) at a world height
float fogDensity(float worldY) {
#if defined(EL_DIMENSION_NETHER)
	return 0.010 * FOG_DENSITY;
#elif defined(EL_DIMENSION_END)
	return 0.0022 * FOG_DENSITY;
#else
	float h = el_sunDirection.y;
	float dusk = smoothstep(-0.15, 0.05, h) * (1.0 - smoothstep(0.05, 0.4, h));
	float height = exp(-max(worldY - 62.0, 0.0) / 60.0);
	float d = 0.0005 + 0.0015 * dusk + 0.006 * rainSmooth + 0.004 * el_thunder + 0.002 * caveFactor
	        + 0.0010 * biomeHumid + 0.0010 * biomeDry;
	float mist = 0.014 * morningMist() * mix(0.25, 1.0, humidity) * (1.0 - biomeDry) * (1.0 - caveFactor)
	           * exp(-max(worldY - 63.0, 0.0) / 9.0);
	return (d * mix(0.35, 1.0, height) + mist) * FOG_DENSITY * (0.7 + 0.3 * RTX_INTENSITY);
#endif
}

// AIR single-scattering albedo: tinted by the biome (green-grey swamp and jungle haze, warm desert dust, pale snow air)
vec3 airAlbedo() {
#if defined(EL_DIMENSION_DEFAULT)
	vec3 a = mix(vec3(1.0), vec3(0.78, 0.94, 0.66), biomeHumid);
	a *= mix(vec3(1.0), vec3(1.0, 0.80, 0.56), biomeDry);
	return a * mix(vec3(1.0), vec3(0.94, 0.97, 1.04), biomeCold);
#else
	return vec3(1.0);
#endif
}

// Henyey-Greenstein (ANISOTROPY = g, Bedrock's default for air is 0.75) plus an isotropic share, scaled so 1 = a
// fully isotropic medium's 4 pi p
float fogPhase(float mu) {
	return 0.25 + 0.35 * 4.0 * PI * phaseHG(mu, ANISOTROPY);
}

// ---- WATER ----------------------------------------------------------------------------------------------------------
const vec3 WATER_ABSORB = vec3(0.28, 0.048, 0.030); // per block: red is gone after ~4 blocks, blue-green carries
const float WATER_G = 0.6;                          // Bedrock's default water anisotropy
// scattering: blue-green; under water the biome's water fog colour (swamps murky green, warm oceans turquoise)
vec3 waterScatter() {
	vec3 c = vec3(0.30, 0.75, 1.0);
	if (el_eyeInWater == 1) {
		vec3 f = el_srgbToLinear(el_fogColor);
		c = mix(c, f / max(max(f.r, max(f.g, f.b)), 1e-3), 0.6);
	}
	return c * 0.016;
}
vec3 waterExtinction() { return WATER_ABSORB + waterScatter(); }

// ---- aerial perspective ----------------------------------------------------------------------------------------------
// The atmosphere between the camera and distant terrain: its extinction at the camera's altitude over dist blocks
// (AERIAL_PERSPECTIVE x 20 m per block, a game's compressed scale) and the sky radiance of that direction as the
// light scattered in (the infinite-path limit of the same atmosphere).
void aerialPerspective(inout vec3 c, vec3 v, float dist) {
#if defined(EL_DIMENSION_DEFAULT)
	if (AERIAL_PERSPECTIVE <= 0.0) return;
	vec3 ext = RAYLEIGH_SCATTER * exp(-ATM_ALT / 8.0) + (MIE_SCATTER + MIE_ABSORB) * exp(-ATM_ALT / 1.2) * mieScale();
	vec3 T = exp(-ext * dist * 0.02 * AERIAL_PERSPECTIVE * (1.0 - 0.85 * caveFactor));
	vec3 sky = skyBase(normalize(vec3(v.x, max(v.y, 0.03), v.z)));
	c = c * T + sky * (1.0 - T);
#endif
}
#endif
