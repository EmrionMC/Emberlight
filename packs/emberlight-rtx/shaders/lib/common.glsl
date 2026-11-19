// Shared constants, random numbers, sky, BRDF. Included by every stage that lights something.
#ifndef RTX_COMMON
#define RTX_COMMON 1
// daylight (0 night .. 1 day), caveFactor (0 open sky at the eye .. 1 none), rainSmooth: custom uniforms (emberlight.json)


const float PI = 3.14159265;

// ---- random numbers -------------------------------------------------------------------------------------------------
uint rtxHash(uint x) {
	x ^= x >> 16u; x *= 0x7feb352du; x ^= x >> 15u; x *= 0x846ca68bu; x ^= x >> 16u;
	return x;
}
// per pixel and frame; call next() for each new dimension
struct Rng { uint s; };
Rng rngInit(ivec2 px, int salt) { return Rng(rtxHash(uint(px.x) * 1973u + uint(px.y) * 9277u + uint(el_frame) * 26699u + uint(salt) * 7919u)); }
float rnd(inout Rng r) { r.s = rtxHash(r.s + 0x9e3779b9u); return float(r.s >> 8u) * (1.0 / 16777216.0); }
vec2 rnd2(inout Rng r) { return vec2(rnd(r), rnd(r)); }

// interleaved gradient noise (Jimenez 2014), shifted per frame: low-discrepancy across neighbouring pixels, which the
// spatial filters average far better than white noise
float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }
// The second dimension is IGN over the transposed pixel grid: IGN of a shifted q is (almost) a fixed offset of the first,
// so (ign(q), ign(q + c)) lay on a few lines of the unit square and the hemisphere samples followed the pixel position
// (rings of fireflies around small emitters, at the distance where that one direction met the light).
vec2 blueNoise2(ivec2 px, int salt) {
	vec2 q = vec2(px) + 5.588238 * float(el_frame & 63) + float(salt) * vec2(23.0, 41.0);
	vec2 q2 = vec2(px.yx) * vec2(1.0, -1.0) + 7.31 * float(el_frame & 63) + float(salt) * vec2(59.0, 17.0);
	// both dimensions move on every 64 frames: x alone repeated, so the sun cone and GI hemisphere radii a pixel ever
	// took were the same 64 (a small opening stayed too dark or kept the same speckles)
	float k = float((el_frame >> 6) & 15);
	return vec2(fract(ign(q) + 0.75487767 * k), fract(ign(q2) + 0.61803399 * k));
}

float hash12(vec2 p) { return float(rtxHash(floatBitsToUint(p.x) * 31u + rtxHash(floatBitsToUint(p.y)))) * (1.0 / 4294967296.0); }

// smooth value noise from el_noise (white noise, bilinear)
float vnoise(vec2 p) { return textureLod(el_noise, p / 256.0, 0.0).r; }
float fbm(vec2 p) {
	float s = 0.0, a = 0.5;
	for (int i = 0; i < 5; i++) { s += a * vnoise(p); p = p * 2.03 + vec2(17.1, 3.7); a *= 0.5; }
	return s;
}

mat3 tbn(vec3 n) {
	vec3 t = normalize(cross(n, abs(n.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
	return mat3(t, cross(n, t), n);
}
vec3 cosineHemisphere(vec3 n, vec2 u) {
	float r = sqrt(u.x), a = 2.0 * PI * u.y;
	return normalize(tbn(n) * vec3(r * cos(a), r * sin(a), sqrt(max(1.0 - u.x, 0.0))));
}
vec3 coneSample(vec3 d, float cosMax, vec2 u) {
	float c = mix(1.0, cosMax, u.x), s = sqrt(max(1.0 - c * c, 0.0)), a = 2.0 * PI * u.y;
	return normalize(tbn(d) * vec3(s * cos(a), s * sin(a), c));
}

#include "/lib/atmosphere.glsl"

// ---- light and sky ----------------------------------------------------------------------------------------------------
#if defined(EL_DIMENSION_END)
const vec3 END_LIGHT_DIR = normalize(vec3(0.35, 0.8, 0.25));
#endif

vec3 lightDir() {
#if defined(EL_DIMENSION_END)
	return END_LIGHT_DIR;
#else
	return el_lightDirection;
#endif
}

#if defined(EL_DIMENSION_DEFAULT)
// sun and moon light at the camera, reddened by the atmosphere (lib/atmosphere.glsl)
vec3 sunColor() { return atmSunColor(el_sunDirection); }
vec3 moonColor() { return moonIrradiance() * atmTransmittance(ATM_GROUND + ATM_ALT, el_moonDirection.y); }
#else
vec3 sunColor() { return vec3(0.0); }
vec3 moonColor() { return vec3(0.0); }
#endif

// radiance of the shadow-casting light (sun by day, moon by night), already including the "per steradian" scale
vec3 lightColor() {
#if defined(EL_DIMENSION_DEFAULT)
	vec3 c = dot(el_lightDirection, el_sunDirection) > 0.5 ? sunColor() : moonColor();
	return c * (1.0 - 0.85 * rainSmooth);
#elif defined(EL_DIMENSION_END)
	return vec3(0.85, 0.35, 1.0) * 2.6;
#else
	return vec3(0.0);
#endif
}

#if defined(EL_DIMENSION_DEFAULT)
vec3 skyBase(vec3 d) {
	// below the horizon a game sees the void past the render distance, not the planet: continue the horizon's haze,
	// darkening downward (the LUT's own ground made a hard dark band under the horizon)
	vec3 c = skyViewLookup(vec3(d.x, max(d.y, 0.004), d.z)) * mix(1.0, 0.3, 1.0 - smoothstep(-0.4, 0.0, d.y))
	       + vec3(0.0012, 0.0018, 0.0035); // + airglow and starlight: a moonless night is not black
	// grey and darker in rain (the haze itself is in the LUTs)
	return mix(c, vec3(el_luminance(c)) * vec3(0.8, 0.85, 0.95), rainSmooth * 0.6) * (1.0 - 0.45 * rainSmooth);
}
#elif defined(EL_DIMENSION_NETHER)
vec3 skyBase(vec3 d) { return el_srgbToLinear(el_fogColor) * 0.6 + vec3(0.06, 0.012, 0.004); }
#else
vec3 skyBase(vec3 d) {
	float up = d.y * 0.5 + 0.5;
	float mu = max(dot(d, END_LIGHT_DIR), 0.0);
	vec3 c = mix(vec3(0.006, 0.001, 0.014), vec3(0.09, 0.015, 0.18), smoothstep(0.1, 1.0, up));
	return c + vec3(0.55, 0.12, 0.8) * pow(mu, 6.0) * 0.5 + vec3(1.0, 0.5, 1.0) * pow(mu, 60.0) * 1.5;
}
#endif

// what a surface sees of the sky (ambient), averaged over the hemisphere around n
vec3 skyAmbient(vec3 n) {
	return skyBase(normalize(n + vec3(0.0, 1.2, 0.0))) * 0.9;
}

// fog colour along a view direction
vec3 fogTint(vec3 d) {
#if defined(EL_DIMENSION_DEFAULT)
	return skyBase(normalize(vec3(d.x, max(d.y, 0.02) * 0.4, d.z)));
#elif defined(EL_DIMENSION_NETHER)
	return el_srgbToLinear(el_fogColor) * 0.9 + vec3(0.05, 0.01, 0.0);
#else
	return vec3(0.05, 0.025, 0.08);
#endif
}

// colour of torches and other warm block light in the lightmap proxy (bounce hits, translucents, fallback)
const vec3 BLOCK_LIGHT = vec3(1.0, 0.58, 0.28);
vec3 lightmapBlock(float l) { return BLOCK_LIGHT * (pow(l, 4.0) * 2.2 + pow(l, 12.0) * 2.0) * BLOCKLIGHT_STRENGTH; }

// ---- BRDF -----------------------------------------------------------------------------------------------------------
vec3 fresnelSchlick(vec3 f0, float c) { return f0 + (1.0 - f0) * pow(1.0 - clamp(c, 0.0, 1.0), 5.0); }

float ggxD(float nh, float a) { float a2 = a * a; float d = nh * nh * (a2 - 1.0) + 1.0; return a2 / (PI * d * d); }

// analytic environment BRDF (Karis, "Physically Based Shading on Mobile", 2014); rough: perceptual roughness
// (1 - smoothness), not the GGX alpha (the alpha made glossy surfaces reflect too much at grazing angles)
vec3 envBrdf(vec3 f0, float rough, float nv) {
	const vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022);
	const vec4 c1 = vec4(1.0, 0.0425, 1.04, -0.04);
	vec4 r = rough * c0 + c1;
	float a004 = min(r.x * r.x, exp2(-9.28 * nv)) * r.x + r.y;
	vec2 ab = vec2(-1.04, 1.04) * a004 + r.zw;
	return f0 * ab.x + ab.y;
}

// sun specular (GGX, Smith approximated), multiplied by n.l outside; a: GGX alpha (roughnessOf), as the reflection
// rays use it (squaring it again made the sun's highlight far narrower than the reflected lobe)
vec3 sunSpecular(vec3 n, vec3 v, vec3 l, float a, vec3 f0) {
	vec3 hv = l + v;
	if (dot(hv, hv) < 1e-8) return vec3(0.0); // light straight behind the surface as seen
	vec3 h = normalize(hv);
	a = max(a, 0.002);
	float nl = max(dot(n, l), 0.0), nv = max(dot(n, v), 1e-3);
	float k = a * 0.5;
	float vis = 1.0 / ((nl * (1.0 - k) + k) * (nv * (1.0 - k) + k));
	return fresnelSchlick(f0, dot(h, v)) * ggxD(max(dot(n, h), 0.0), a) * vis * 0.25 * nl;
}

// GGX visible normal sampling (Heitz 2018, "Sampling the GGX Distribution of Visible Normals")
vec3 sampleGgxVndf(vec3 v, float a, vec2 u) {
	vec3 vh = normalize(vec3(a * v.x, a * v.y, v.z));
	float lensq = vh.x * vh.x + vh.y * vh.y;
	vec3 t1 = lensq > 0.0 ? vec3(-vh.y, vh.x, 0.0) * inversesqrt(lensq) : vec3(1.0, 0.0, 0.0);
	vec3 t2 = cross(vh, t1);
	float r = sqrt(u.x), phi = 2.0 * PI * u.y;
	float p1 = r * cos(phi), p2 = r * sin(phi);
	float s = 0.5 * (1.0 + vh.z);
	p2 = (1.0 - s) * sqrt(1.0 - p1 * p1) + s * p2;
	vec3 nh = p1 * t1 + p2 * t2 + sqrt(max(0.0, 1.0 - p1 * p1 - p2 * p2)) * vh;
	return normalize(vec3(a * nh.x, a * nh.y, max(0.0, nh.z)));
}

// reflection direction for view direction v (from the camera to the surface), normal n, roughness r = (1 - smoothness)^2,
// which is already the GGX alpha (squaring it again made a brick wall a near mirror: a per-texel mosaic of reflections)
vec3 glossyDir(vec3 v, vec3 n, float rough, vec2 u) {
	if (rough < 0.02) return reflect(v, n);
	mat3 m = tbn(n);
	vec3 vl = transpose(m) * (-v);
	vec3 h = m * sampleGgxVndf(vl, rough, u);
	vec3 r = reflect(v, h);
	return dot(r, n) > 0.0 ? r : reflect(v, n);
}

// Last frame's uv minus this frame's of a camera-relative world position, without the upscaler's jitter. With DLSS/FSR
// the projection moves by up to a pixel every frame, so with the jitter a still camera looked like a moving one: the
// denoiser clamped and shortened its history on every frame (grain) and resampled it bilinearly (blur). Lighting buffers
// are smooth across a surface, so ignoring the sub-pixel jitter offset costs nothing.
vec2 unjitteredUv(mat4 P, mat4 V, vec3 p) {
	P[2][0] = 0.0; P[2][1] = 0.0; // the jitter is a clip-space translation: it sits in the z column (times -z)
	vec4 c = P * (V * vec4(p, 1.0));
	return c.xy / c.w * 0.5 + 0.5;
}
vec2 cameraMotion(vec3 world) {
	return unjitteredUv(el_prevProjection, el_prevView, world + el_cameraPosition - el_prevCameraPosition)
		- unjitteredUv(el_projection, el_view, world);
}

// How far a ray starts off the surface at view distance |world|: about a pixel's footprint (0.0015 blocks per block at 70
// degrees FOV, as before), so it shrinks when zoomed in instead of lifting the origin past the feet and thin details that
// the zoom shows large.
float rayLift(vec3 world) { return 0.004 + 0.00214 * length(world) / el_projection[1][1]; }

float rtxBoost() { return 0.6 + 0.4 * RTX_INTENSITY; }

#endif
