// The atmosphere LUTs (lib/atmosphere.glsl), one pass each in this order: LUT 0 transmittance, 1 multiple scattering
// (Hillaire 2020, sec. 5.5: second-order light from 64 directions with an isotropic phase, summed as a geometric series),
// 2 sky view (single scattering of sun and moon plus the multiple-scattering term, marched from the camera).
#include "/lib/common.glsl"
#include "/lib/exposure.glsl"

// LUT 0 and 1 depend only on the air (the compile-time coefficients and rainSmooth's haze), not on the sun: they are
// rebuilt when rainSmooth changed since the sky view pass stored it last frame (exposurePad0, + 1 so the zeroed buffer
// of a fresh pack never matches), every 120th frame (a recreated LUT image next to a kept buffer heals itself) and in
// texels that hold no valid value. They cost about 2 million integration steps per frame at high quality.
bool lutCurrent(vec4 texel) {
	bool valid = all(greaterThanEqual(texel.rgb, vec3(0.0))) && all(lessThan(texel.rgb, vec3(1e4))) && texel.a == 1.0;
	return valid && abs(exposurePad0 - (rainSmooth + 1.0)) < 1e-6 && el_frame % 120 != 0;
}

#if SKY_QUALITY == 0
const int VIEW_STEPS = 16, MS_DIRS = 16;
#elif SKY_QUALITY == 1
const int VIEW_STEPS = 32, MS_DIRS = 64;
#else
const int VIEW_STEPS = 48, MS_DIRS = 64;
#endif

// light from a source of irradiance e in direction l scattered at q toward -d
vec3 scatterAt(vec3 q, float r, vec3 d, vec3 l, vec3 e, vec3 scR, float scM) {
	if (dot(e, e) <= 0.0) return vec3(0.0);
	float muL = dot(q, l) / r, nu = dot(d, l);
	vec3 single = (scR * phaseRayleigh(nu) + scM * phaseHG(nu, MIE_G)) * atmTransmittance(r, muL);
	return e * (single + (scR + scM) * atmMultiScatter(r, muL));
}

void main() {
	ivec2 p = ivec2(gl_GlobalInvocationID.xy);
#if LUT == 0
	ivec2 s = imageSize(skyTrans_img);
	if (any(greaterThanEqual(p, s)) || lutCurrent(imageLoad(skyTrans_img, p))) return;
	float r, mu;
	transUvInv(vec2(p) / vec2(s - 1), r, mu);
	vec3 o = vec3(0.0, r, 0.0), d = vec3(sqrt(max(1.0 - mu * mu, 0.0)), mu, 0.0);
	float t = max(raySphere(o, d, ATM_TOP, false), 0.0);
	vec3 od = vec3(0.0);
	const int N = 40;
	for (int i = 0; i < N; i++) {
		vec3 q = o + d * (t * (float(i) + 0.5) / float(N));
		vec3 scR, ext; float scM;
		atmMedium(length(q) - ATM_GROUND, scR, scM, ext);
		od += ext * (t / float(N));
	}
	imageStore(skyTrans_img, p, vec4(exp(-od), 1.0));
#elif LUT == 1
	ivec2 s = imageSize(skyMs_img);
	if (any(greaterThanEqual(p, s)) || lutCurrent(imageLoad(skyMs_img, p))) return;
	vec2 uv = vec2(p) / vec2(s - 1);
	float muS = uv.x * 2.0 - 1.0, r = mix(ATM_GROUND + 0.01, ATM_TOP - 0.01, uv.y);
	vec3 o = vec3(0.0, r, 0.0), l = vec3(sqrt(max(1.0 - muS * muS, 0.0)), muS, 0.0);
	vec3 l2 = vec3(0.0), fms = vec3(0.0);
	const int N = 20;
	for (int k = 0; k < MS_DIRS; k++) {
		float z = 1.0 - 2.0 * (float(k) + 0.5) / float(MS_DIRS), a = float(k) * 2.39996323;
		vec3 d = vec3(sqrt(1.0 - z * z) * cos(a), z, sqrt(1.0 - z * z) * sin(a));
		float tg = raySphere(o, d, ATM_GROUND, true);
		bool ground = tg > 0.0;
		float t = ground ? tg : max(raySphere(o, d, ATM_TOP, false), 0.0);
		float dt = t / float(N);
		vec3 T = vec3(1.0);
		for (int i = 0; i < N; i++) {
			vec3 q = o + d * (dt * (float(i) + 0.5));
			float rq = length(q);
			vec3 scR, ext; float scM;
			atmMedium(rq - ATM_GROUND, scR, scM, ext);
			vec3 sc = scR + scM;
			vec3 stepT = exp(-ext * dt), integ = (1.0 - stepT) / max(ext, vec3(1e-7));
			l2 += T * sc * atmTransmittance(rq, dot(q, l) / rq) * (1.0 / (4.0 * ATM_PI)) * integ;
			fms += T * sc * integ;
			T *= stepT;
		}
		if (ground) {
			vec3 g = normalize(o + d * t);
			l2 += T * atmTransmittance(ATM_GROUND, dot(g, l)) * max(dot(g, l), 0.0) * GROUND_ALBEDO / ATM_PI;
		}
	}
	l2 /= float(MS_DIRS);
	fms /= float(MS_DIRS);
	imageStore(skyMs_img, p, vec4(l2 / (1.0 - min(fms, vec3(0.99))), 1.0));
#else
	ivec2 s = imageSize(skyView_img);
	if (any(greaterThanEqual(p, s))) return;
	vec3 d = skyViewDir(vec2(p) / vec2(s - 1));
	vec3 o = vec3(0.0, ATM_GROUND + ATM_ALT, 0.0);
	vec3 sunL = el_sunDirection, moonL = el_moonDirection;
	vec3 eSun = vec3(SUN_SCALE * SUN_INTENSITY * SKY_GAIN);
	// moonlit air: Rayleigh already makes it blue, so a less blue tint than the direct moonlight (a blue x blue sky was
	// saturated ultramarine after eye adaptation)
	vec3 eMoon = moonIrradiance() / MOON_TINT * vec3(0.62, 0.70, 0.85) * SKY_GAIN;
	float tg = raySphere(o, d, ATM_GROUND, true);
	bool ground = tg > 0.0;
	float t = ground ? tg : max(raySphere(o, d, ATM_TOP, false), 0.0);
	vec3 L = vec3(0.0), T = vec3(1.0);
	float prev = 0.0;
	for (int i = 0; i < VIEW_STEPS; i++) {
		float f = (float(i) + 1.0) / float(VIEW_STEPS);
		float ti = t * f * f; // denser near the camera, where the air is thickest along low rays
		float dt = ti - prev;
		vec3 q = o + d * (ti - 0.5 * dt);
		prev = ti;
		float rq = length(q);
		vec3 scR, ext; float scM;
		atmMedium(rq - ATM_GROUND, scR, scM, ext);
		vec3 S = scatterAt(q, rq, d, sunL, eSun, scR, scM) + scatterAt(q, rq, d, moonL, eMoon, scR, scM);
		vec3 stepT = exp(-ext * dt);
		L += T * S * (1.0 - stepT) / max(ext, vec3(1e-7));
		T *= stepT;
	}
	if (ground) { // the far ground below the horizon, lit by sun and moon
		vec3 g = normalize(o + d * t);
		vec3 E = eSun * atmTransmittance(ATM_GROUND, dot(g, sunL)) * max(dot(g, sunL), 0.0)
		       + eMoon * atmTransmittance(ATM_GROUND, dot(g, moonL)) * max(dot(g, moonL), 0.0);
		L += T * E * GROUND_ALBEDO / ATM_PI;
	}
	imageStore(skyView_img, p, vec4(L, 1.0));
	if (p == ivec2(0)) exposurePad0 = rainSmooth + 1.0; // after LUT 0 and 1 of this frame: what they were built for
#endif
}
