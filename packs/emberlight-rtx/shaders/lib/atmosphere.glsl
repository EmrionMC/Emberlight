// Physically based sky: an Earth-like atmosphere (Rayleigh + Mie + ozone) after Bruneton & Neyret 2008 and Hillaire 2020.
// Three small LUTs are rebuilt every frame in the "begin" phase (sky_lut.comp.glsl):
//   skyTrans  256x64   transmittance from a point (altitude, cos zenith) to the top of the atmosphere
//   skyMs     32x32    Hillaire's multiple-scattering term Psi_ms(altitude, cos sun zenith)
//   skyView   256x256  sky radiance around the camera, sun and moon light together, in pack units
// Everything that needs the sky (the visible sky, the sun and moon colour, GI miss rays, ambient, reflections, fog
// tint, aerial perspective) reads these, so the lighting always matches the sky. Units: kilometres.
#ifndef RTX_ATMOSPHERE
#define RTX_ATMOSPHERE 1

const float ATM_PI = 3.14159265;
const float ATM_GROUND = 6360.0, ATM_TOP = 6460.0;
const float ATM_ALT = 0.25;                                          // camera altitude above the planet (km)
const vec3 RAYLEIGH_SCATTER = vec3(5.802, 13.558, 33.1) * 1e-3;     // per km at sea level, scale height 8 km
const float MIE_SCATTER = 3.996e-3, MIE_ABSORB = 4.40e-3;           // scale height 1.2 km
const vec3 OZONE_ABSORB = vec3(0.650, 1.881, 0.085) * 1e-3;         // tent profile, 25 km +- 15 km
const float MIE_G = 0.8;
const vec3 GROUND_ALBEDO = vec3(0.25, 0.27, 0.22);
// Pack units: the sun's irradiance outside the atmosphere (the old sunColor() peaked at ~5.5) and how much brighter the
// sky is drawn than the physical ratio to the sun (a game sky at a physical 1:60 makes every shadow black).
const float SUN_SCALE = 6.0, SKY_GAIN = 5.0;

// rain: haze (more Mie) and an overcast, greyer sky
float mieScale() { return 1.0 + 6.0 * rainSmooth; }

// rgb extinction and scattering at altitude h (km)
void atmMedium(float h, out vec3 scR, out float scM, out vec3 ext) {
	float dR = exp(-h / 8.0), dM = exp(-h / 1.2) * mieScale(), dO = max(0.0, 1.0 - abs(h - 25.0) / 15.0);
	scR = RAYLEIGH_SCATTER * dR;
	scM = MIE_SCATTER * dM;
	ext = scR + (MIE_SCATTER + MIE_ABSORB) * dM + OZONE_ABSORB * BLUE_HOUR * dO;
}

float phaseRayleigh(float c) { return 3.0 / (16.0 * ATM_PI) * (1.0 + c * c); }
float phaseHG(float c, float g) { float k = 1.0 + g * g - 2.0 * g * c; return (1.0 - g * g) / (4.0 * ATM_PI * k * sqrt(k)); }

// distance along d from o to the sphere of radius R (far intersection), -1 if missed
float raySphere(vec3 o, vec3 d, float R, bool nearHit) {
	float b = dot(o, d), c = dot(o, o) - R * R, disc = b * b - c;
	if (disc < 0.0) return -1.0;
	float s = sqrt(disc);
	return nearHit ? -b - s : -b + s;
}

// ---- transmittance LUT mapping (Bruneton 2017 parameterisation: dense near the horizon) ---------------------------------
const float ATM_H = 1131.4; // sqrt(ATM_TOP^2 - ATM_GROUND^2)
vec2 transUv(float r, float mu) {
	float rho = sqrt(max(r * r - ATM_GROUND * ATM_GROUND, 0.0));
	float disc = r * r * (mu * mu - 1.0) + ATM_TOP * ATM_TOP;
	float d = max(-r * mu + sqrt(max(disc, 0.0)), 0.0);
	float dMin = ATM_TOP - r, dMax = rho + ATM_H;
	return vec2((d - dMin) / max(dMax - dMin, 1e-4), rho / ATM_H);
}
void transUvInv(vec2 uv, out float r, out float mu) {
	float rho = ATM_H * uv.y;
	r = sqrt(rho * rho + ATM_GROUND * ATM_GROUND);
	float dMin = ATM_TOP - r, dMax = rho + ATM_H;
	float d = dMin + uv.x * (dMax - dMin);
	mu = d == 0.0 ? 1.0 : clamp((ATM_H * ATM_H - rho * rho - d * d) / (2.0 * r * d), -1.0, 1.0);
}
// transmittance to space from radius r along cos zenith mu; 0 behind the planet (soft over a few tenths of a degree)
vec3 atmTransmittance(float r, float mu) {
	vec2 uv = transUv(r, mu);
	vec2 s = vec2(textureSize(skyTrans, 0));
	vec3 t = textureLod(skyTrans, (uv * (s - 1.0) + 0.5) / s, 0.0).rgb;
	float muHorizon = -sqrt(max(1.0 - ATM_GROUND * ATM_GROUND / (r * r), 0.0));
	return t * smoothstep(muHorizon - 0.004, muHorizon + 0.004, mu);
}

vec3 atmMultiScatter(float r, float muS) {
	vec2 uv = vec2(muS * 0.5 + 0.5, (r - ATM_GROUND) / (ATM_TOP - ATM_GROUND));
	return textureLod(skyMs, (uv * 31.0 + 0.5) / 32.0, 0.0).rgb;
}

// ---- sky-view LUT mapping -------------------------------------------------------------------------------------------
// u: angle around the vertical from +x (the sun and moon travel in the x-y plane: the pack has no sun path rotation,
// so the sky is mirror-symmetric in z and half the circle is enough); v: latitude, square-root spaced toward the horizon
vec2 skyViewUv(vec3 d) {
	float u = acos(clamp(d.x * inversesqrt(max(dot(d.xz, d.xz), 1e-8)), -1.0, 1.0)) / ATM_PI;
	float lat = asin(clamp(d.y, -1.0, 1.0));
	float v = 0.5 + 0.5 * sign(lat) * sqrt(abs(lat) / (0.5 * ATM_PI));
	return vec2(u, v);
}
vec3 skyViewDir(vec2 uv) {
	float a = uv.x * ATM_PI, l = uv.y * 2.0 - 1.0;
	float lat = sign(l) * l * l * 0.5 * ATM_PI;
	return vec3(cos(lat) * cos(a), sin(lat), cos(lat) * sin(a));
}
vec3 skyViewLookup(vec3 d) {
	vec2 s = vec2(textureSize(skyView, 0));
	return textureLod(skyView, (skyViewUv(d) * (s - 1.0) + 0.5) / s, 0.0).rgb;
}

// ---- lights ---------------------------------------------------------------------------------------------------------
// fraction of the moon's disc that is lit: phase 0 full .. 4 new; never quite dark (earthshine, a game's night)
float moonIllumination() { return mix(0.12, 1.0, 0.5 + 0.5 * cos(float(el_moonPhase) * ATM_PI * 0.25)); }
const vec3 MOON_TINT = vec3(0.45, 0.60, 1.0); // moonlight drawn bluish (the eye's night vision), as games do
vec3 moonIrradiance() { return MOON_TINT * 0.45 * MOON_INTENSITY * moonIllumination(); }

// sun colour after the atmosphere at the camera: physical reddening; GOLDEN_HOUR scales its saturation (1 = physical)
vec3 atmSunColor(vec3 sunDir) {
	vec3 t = atmTransmittance(ATM_GROUND + ATM_ALT, sunDir.y);
	float l = max(dot(t, vec3(0.2126, 0.7152, 0.0722)), 1e-5);
	t = l * pow(max(t / l, vec3(0.0)), vec3(GOLDEN_HOUR));
	return t * SUN_SCALE * SUN_INTENSITY;
}
#endif
