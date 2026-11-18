// Procedural water surface: a few travelling wave trains plus noise, and rings around the player when it moves in water.
#ifndef RTX_WATER
#define RTX_WATER 1

// travelling sine trains: direction, wave number, angular speed, amplitude. The short ones carry the curvature that
// focuses sunlight into caustics (wavesCurvature). Directions chosen so the five short trains' crests are ~35 degrees
// apart and no two trains beat into a long stripe (|k_i +- k_j| >= 3.3): with two nearly antiparallel trains the
// caustic web lined up in parallel rows four blocks apart.
const int WAVE_TRAINS = 7;
const vec4 WAVE_DIR_K[WAVE_TRAINS] = vec4[](vec4(-0.868, -0.497, 0.9, 1.3), vec4(0.676, -0.737, 1.7, 1.9),
	vec4(-0.628, -0.778, 3.1, 2.7), vec4(-0.903, 0.429, 5.3, 3.6), vec4(0.977, 0.215, 7.9, 4.4), vec4(-0.077, -0.997, 11.0, 5.1),
	vec4(-0.502, 0.865, 9.3, 4.8));
const float WAVE_AMP[WAVE_TRAINS] = float[](0.050, 0.030, 0.014, 0.007, 0.0040, 0.0025, 0.0030);

// Slow domain warp (up to ~1 block, gradient <= 0.4) shared by the height and the curvature: bends the straight crests
// so the pattern never repeats in rows. The curvature ignores the warp's Jacobian (the web stays a web, just bent).
vec2 waveWarp(vec2 p, float t) {
	return p + vec2(0.6 * sin(dot(p, vec2(0.23, 0.31)) + 0.21 * t) + 0.4 * sin(dot(p, vec2(-0.41, 0.17)) + 0.13 * t),
		0.6 * sin(dot(p, vec2(-0.29, 0.19)) + 0.17 * t) + 0.4 * sin(dot(p, vec2(0.13, 0.43)) - 0.11 * t));
}

float waveHeight(vec2 p, float t) {
	float h = 0.0;
	vec2 q = waveWarp(p, t);
	for (int i = 0; i < WAVE_TRAINS; i++) h += sin(dot(q, WAVE_DIR_K[i].xy) * WAVE_DIR_K[i].z + t * WAVE_DIR_K[i].w) * WAVE_AMP[i];
	h += (vnoise(p * 3.0 + t * vec2(0.7, 0.4)) - 0.5) * 0.020;
	h += (vnoise(p * 7.0 - t * vec2(0.5, 0.9)) - 0.5) * 0.008;
	return h;
}

// Second derivatives (xx, zz, xz) of the sine trains of waveHeight, analytic. Without the noise terms: bilinear noise
// has a gradient that jumps at every noise texel, which focuses light into a grid of lines.
vec3 wavesCurvature(vec2 p, float t) {
	vec3 c = vec3(0.0);
	p = waveWarp(p, t);
	for (int i = 0; i < WAVE_TRAINS; i++) {
		vec2 k = WAVE_DIR_K[i].xy * WAVE_DIR_K[i].z;
		c -= WAVE_AMP[i] * sin(dot(p, k) + t * WAVE_DIR_K[i].w) * vec3(k.x * k.x, k.y * k.y, k.x * k.y);
	}
	return c * WATER_WAVES;
}

// Caustic irradiance factor (1 = flat water) where light that left a wavy surface point p travels dist blocks: the
// light of a surface patch lands on a patch distorted by the Jacobian of x -> x + dist * bend * grad h(x), so the
// irradiance scales by 1 / |det J|. bend: about 0.25 for refraction into water, 2 for a mirror reflection.
// Divided by its mean over the water plane at that focus distance, so the web redistributes the light instead of adding
// to it: undivided, a pool floor 4 blocks down got 2.3x the sunlight on average (1/|det| is convex: the bright lines
// outweigh the dark gaps, and the clamp keeps folds from cancelling). CAUSTIC_MEAN: that mean for s = dist * bend *
// WATER_WAVES = 0, 0.5 .. 10 (these warped wave trains, averaged over 1024^2 points of a 256-block plane at three times).
const float CAUSTIC_MEAN[21] = float[](1.0, 1.038, 1.182, 1.509, 1.887, 2.147, 2.22, 2.156, 2.028, 1.878, 1.726, 1.58,
	1.446, 1.324, 1.216, 1.119, 1.032, 0.955, 0.886, 0.824, 0.768);
float causticMean(float dist, float bend) {
	float s = clamp(dist * bend * WATER_WAVES * 2.0, 0.0, 19.999);
	int i = int(s);
	return mix(CAUSTIC_MEAN[i], CAUSTIC_MEAN[i + 1], s - float(i));
}
float causticFactor(vec2 p, float t, float dist, float bend) {
	vec3 c = wavesCurvature(p, t) * dist * bend;
	float det = (1.0 + c.x) * (1.0 + c.y) - c.z * c.z;
	return 1.0 / max(abs(det), 0.2) / causticMean(dist, bend);
}

// p: absolute world xz; rel: camera-relative position of the surface point
vec3 waterNormal(vec2 p, vec3 rel, float t) {
	const float e = 0.05;
	float h0 = waveHeight(p, t);
	float hx = waveHeight(p + vec2(e, 0.0), t), hz = waveHeight(p + vec2(0.0, e), t);
	vec2 g = vec2(hx - h0, hz - h0) / e * WATER_WAVES;
	// rings around the player while it moves through the water
	vec3 feet = el_eyePosition - el_cameraPosition - vec3(0.0, 1.0, 0.0);
	vec2 d = rel.xz - feet.xz;
	float r = length(d);
	float speed = length(el_cameraPosition - el_prevCameraPosition) / max(el_frameTime, 1e-3);
	float near = (1.0 - smoothstep(0.5, 2.5, abs(rel.y - feet.y))) * smoothstep(0.3, 2.0, speed);
	if (near > 0.0 && r > 0.01) g += d / r * cos(r * 9.0 - t * 12.0) * exp(-r * 0.7) * 0.5 * near;
	return normalize(vec3(-g.x, 1.0, -g.y));
}
#endif
