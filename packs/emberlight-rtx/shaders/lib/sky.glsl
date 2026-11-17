// The visible sky: base sky + sun and moon discs, stars and a cloud layer.
#ifndef RTX_SKY
#define RTX_SKY 1
#include "/lib/common.glsl"

#if defined(EL_DIMENSION_DEFAULT)
vec3 clouds(vec3 d, vec3 c) {
#ifdef CLOUDS
	if (d.y <= 0.01) return c;
	float h = 220.0 - el_cameraPosition.y;
	if (h <= 0.0) return c;
	float t = h / d.y;
	vec2 p = (el_cameraPosition.xz + d.xz * t) * 0.004 + vec2(el_time * 0.004, 0.0);
	float dens = fbm(p * 3.0);
	float cover = mix(0.56, 0.36, rainSmooth);
	float a = smoothstep(cover, cover + 0.22, dens) * smoothstep(0.01, 0.12, d.y);
	if (a <= 0.0) return c;
	// lighter towards the sun, darker where the cloud is thick
	float thick = smoothstep(cover, cover + 0.45, dens);
	vec3 l = el_lightDirection;
	float mu = dot(d, l);
	vec3 lit = lightColor() * (0.30 + 0.5 * pow(max(mu, 0.0), 6.0)) * (1.0 - 0.6 * thick) + skyAmbient(vec3(0.0, 1.0, 0.0)) * 0.9;
	return mix(c, lit, a * 0.92);
#else
	return c;
#endif
}

// stars on the celestial sphere, turning with the sun (around the z axis), dimmed by the air they shine through
vec3 stars(vec3 d) {
	vec3 e1 = el_sunDirection, e2 = cross(vec3(0.0, 0.0, 1.0), e1); // the frame the sun stands still in
	vec3 q = vec3(dot(d, e1), dot(d, e2), d.z) * 300.0;
	vec3 cell = floor(q);
	float r = hash12(cell.xy + cell.z * 17.31);
	float s = step(0.9975, r) * (1.0 - smoothstep(0.0, 0.5, length(fract(q) - 0.5)));
	float tw = 0.7 + 0.3 * sin(el_time * 3.0 + r * 100.0);
	vec3 col = mix(vec3(1.0, 0.8, 0.6), vec3(0.75, 0.85, 1.0), fract(r * 7919.0)); // cool and warm stars
	return col * s * tw * 2.5 * (1.0 - daylight) * (1.0 - rainSmooth) * atmTransmittance(ATM_GROUND + ATM_ALT, d.y);
}

// Sun disc with limb darkening (I(mu) = 1 - u (1 - mu), u per channel: the limb is darker and redder in blue)
vec3 sunDisc(vec3 d) {
	const float cosR = 0.99965; // angular radius ~1.5 degrees: a game sun, larger than the real 0.27
	float c = dot(d, el_sunDirection);
	if (c < cosR - 0.0001) return vec3(0.0);
	float r = clamp(sqrt(max(1.0 - c * c, 0.0)) / sqrt(1.0 - cosR * cosR), 0.0, 1.0);
	float mu = sqrt(max(1.0 - r * r, 0.0));
	vec3 limb = 1.0 - vec3(0.50, 0.60, 0.72) * (1.0 - mu);
	return sunColor() * limb * 60.0 * (1.0 - smoothstep(0.97, 1.0, r)) * (1.0 - rainSmooth);
}

// Moon: a lit sphere whose terminator follows el_moonPhase (0 full .. 4 new .. 7), with darker maria
vec3 moonDisc(vec3 d) {
	const float cosR = 0.99950;
	vec3 m = el_moonDirection;
	float c = dot(d, m);
	if (c < cosR - 0.0001) return vec3(0.0);
	mat3 f = tbn(-m); // z toward the camera
	vec2 xy = (transpose(f) * d).xy / sqrt(1.0 - cosR * cosR);
	float rr = dot(xy, xy);
	if (rr > 1.0) return vec3(0.0);
	vec3 nrm = vec3(xy, sqrt(1.0 - rr));
	float ph = float(el_moonPhase) * PI * 0.25; // 0 = lit from the camera's side, pi = from behind
	vec3 sunL = vec3(sin(ph), 0.0, cos(ph));
	float lit = smoothstep(-0.05, 0.12, dot(nrm, sunL));
	float maria = 0.6 + 0.4 * smoothstep(0.38, 0.62, fbm(xy * 3.0 + 7.0));
	vec3 col = vec3(0.85, 0.88, 0.95) * maria * (lit * 1.3 + 0.025); // a faint earthshine on the dark part (brighter clipped the maria to white)
	return col * MOON_INTENSITY * (1.0 - rainSmooth) * atmTransmittance(ATM_GROUND + ATM_ALT, m.y) * (1.0 - smoothstep(0.96, 1.0, rr));
}
#endif

vec3 skyRadiance(vec3 d) {
	vec3 c = skyBase(d);
#if defined(EL_DIMENSION_DEFAULT)
	c += sunDisc(d) + moonDisc(d);
	if (dot(d, el_moonDirection) < 0.99945) c += stars(d); // none through the moon's dark side
	c = clouds(d, c);
#elif defined(EL_DIMENSION_END)
	// a slowly turning vortex of glowing cloud with the light in its eye
	mat3 f = tbn(END_LIGHT_DIR);
	vec3 dl = transpose(f) * d;
	float r = acos(clamp(dl.z, -1.0, 1.0));             // angle from the eye, 0..pi
	float a = atan(dl.y, dl.x) + r * 3.2 - el_time * 0.04; // spiral arms
	vec2 sp = vec2(cos(a), sin(a)) * (2.0 + r * 5.0) + vec2(r * 9.0, 0.0);
	float arms = 0.5 + 0.5 * cos(a * 3.0 + fbm(sp * 0.8) * 4.0);
	float dens = smoothstep(0.40, 0.80, fbm(sp * 0.7 + el_time * 0.01) * (0.55 + 0.7 * arms));
	float ring = smoothstep(0.08, 0.35, r) * (1.0 - smoothstep(1.4, 2.4, r));
	vec3 glow = mix(vec3(1.0, 0.55, 1.0), vec3(0.35, 0.12, 0.7), smoothstep(0.2, 1.6, r));
	c = mix(c, glow * (2.4 - 1.0 * r), dens * ring * 0.9);
	c += vec3(1.0, 0.75, 1.0) * exp(-r * r * 60.0) * 6.0;  // the eye
	vec3 q = d * 200.0;
	vec3 cell = floor(q);
	float star = hash12(cell.xy + cell.z * 13.7);
	c += vec3(0.7, 0.5, 1.0) * step(0.997, star) * (1.0 - dens * ring) * (1.0 - smoothstep(0.0, 0.5, length(fract(q) - 0.5))) * 1.5;
#endif
	return c;
}
#endif
