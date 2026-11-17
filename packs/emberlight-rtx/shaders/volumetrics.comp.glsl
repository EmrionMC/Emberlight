// Half resolution: ray-marched in-scattering of sun/moon light with one shadow ray per step (god rays through leaves,
// windows and cave openings, tinted by stained glass on the way) plus ambient fog, then temporal accumulation.
// Two participating media (lib/fog.glsl): AIR (scalar extinction, biome-tinted albedo, HG phase) and, with the camera
// under water, WATER (coloured absorption + scattering, sun shafts through the waves).
// vol.rgb = in-scattered light, vol.a = transmittance.
// The march start is jittered per pixel and frame (interleaved gradient noise), and the result is accumulated over
// frames in volHist (reprojected, rejected by view distance): a single 8-step march per frame was grain that DLSS let
// through as soon as the camera moved. volHist holds two half-res frames (ping-pong by frame parity: a compute pass
// cannot read its neighbours' history from the image it writes).
#include "/lib/rt.glsl"
#include "/lib/fog.glsl"

layout(std430, binding = EL_BUFFER_volHist) buffer VolHistBlock { uvec4 volData[]; };

const float VOL_FAR = 160.0;

// Step i of the march: the intervals [i/N, (i+1)/N] (squared: denser near the camera) tile the whole ray to maxDist,
// only the sample point t is jittered inside its interval (jittered boundaries never reached the end: 6 % of the
// distance missing at 16 steps, 23 % at 4). Returns the interval length.
float marchStep(int i, float maxDist, float jitter, out float t) {
	float n = float(VOL_STEPS), s0 = float(i) / n, s1 = float(i + 1) / n, s = (float(i) + jitter) / n;
	t = maxDist * s * s;
	return maxDist * (s1 * s1 - s0 * s0);
}

void main() {
	ivec2 hp = ivec2(gl_GlobalInvocationID.xy);
	ivec2 vs = imageSize(vol_img);
	if (any(greaterThanEqual(hp, vs))) return;
	ivec2 px = min(hp * 2, ivec2(el_screenSize) - 1);
	float depth = texelFetch(el_depthNoHand, px, 0).r;
	vec2 uv = (vec2(hp) + 0.5) / vec2(vs);
	vec3 world = el_screenToWorld(uv, el_isSky(depth) ? 0.9999 : depth);
	vec3 v = normalize(world);
	float maxDist = el_isSky(depth) ? VOL_FAR : min(length(world), VOL_FAR);
	float jitter = fract(ign(vec2(hp) + 5.588238 * float(el_frame & 63)) + 0.61803399 * float((el_frame >> 6) & 15));
	vec3 l = lightDir();
	vec3 lc = lightColor();
	vec3 scatter = vec3(0.0);
	float T = 1.0;
	if (el_eyeInWater == 1) {
		// WATER medium: absorption + blue-green scattering (HG g = 0.6). Sun shafts: each sample's sunlight crosses the
		// wave surface above it (one ray to the surface: refraction caustic of the waves there = the shafts' pattern,
		// absorption over the path below it) and must reach the sky (one RT shadow ray). The ray ends at the water
		// surface seen from below (el_depth holds it; the hand's depth is skipped) or the opaque scene.
		float sd = texelFetch(el_depth, px, 0).r;
		if (sd >= 0.56 && sd < depth) maxDist = min(maxDist, length(el_screenToWorld(uv, sd)));
		maxDist = min(maxDist, 96.0);
		vec3 sigS = waterScatter(), sigT = waterExtinction();
		float ph = 4.0 * PI * phaseHG(dot(v, l), WATER_G) * 0.5 + 0.5;
		vec3 amb = skyAmbient(vec3(0.0, 1.0, 0.0)) * (0.15 + 0.85 * el_eyeBrightness.y) * 0.3;
		vec3 Tw = vec3(1.0);
		for (int i = 0; i < VOL_STEPS; i++) {
			float t; float ds = marchStep(i, maxDist, jitter, t);
			vec3 p = v * t;
			vec3 sunL = vec3(0.0);
			float depthBelow = max(63.0 - el_cameraPosition.y - p.y, 0.5); // sea level, unless the ray up finds the surface
			if (dot(lc, lc) > 1e-6 && l.y > 0.02) {
#ifdef UNDERWATER_RAYS
				ElHit h = el_traceClosest(p, l, 64.0, EL_MASK_TRANSLUCENT);
				if (h.hit && h.blockId == ID_WATER) {
					depthBelow = h.t * l.y;
					float caus = refractionCaustic(h.position, h.t, length(p), true);
					if (el_traceVisibility(p, l, 512.0, MASK_SOLID)) // below and above the surface in one ray
						sunL = lc * exp(-sigT * h.t) * caus * ph * 2.2 * rtxBoost();
				}
#else
				sunL = lc * exp(-sigT * depthBelow / l.y) * ph * 2.2;
#endif
			}
			vec3 stepT = exp(-sigT * ds);
			scatter += Tw * sigS * (sunL + amb * exp(-sigT * depthBelow)) * (1.0 - stepT) / sigT;
			Tw *= stepT;
		}
		T = 1.0; // resolve applies the (coloured) water transmittance itself
	} else {
	// AIR medium: height falloff, weather, caves, morning mist, biome tint (lib/fog.glsl)
	float phase = fogPhase(dot(v, l));
	vec3 albedo = airAlbedo();
	vec3 amb = fogTint(v) * mix(0.6, 0.1, caveFactor);
	for (int i = 0; i < VOL_STEPS; i++) {
		float t; float ds = marchStep(i, maxDist, jitter, t);
		vec3 p = v * t;
		float dens = fogDensity(p.y + el_cameraPosition.y);
		vec3 sunL = vec3(0.0);
#if !defined(EL_DIMENSION_NETHER)
		if (dot(lc, lc) > 1e-6) sunL = lc * phase * 0.8 * (0.6 + 0.4 * RTX_INTENSITY) * sunTransmission(p, l, false);
#endif
		float stepT = exp(-dens * ds);
		scatter += T * (1.0 - stepT) * (sunL + amb) * albedo;
		T *= stepT;
	}
	}

	// ---- temporal ----
	uint n = uint(vs.x * vs.y);
	uint wr = uint(el_frame & 1) * n, rd = n - wr;
	vec4 cur = vec4(scatter, T);
	if (2u * n <= uint(volData.length())) {
		vec2 mv = cameraMotion(v * maxDist);
		float rest = 1.0 - smoothstep(0.02, 0.5, length(mv * el_screenSize));
		float expected = min(length(v * maxDist + el_cameraPosition - el_prevCameraPosition), VOL_FAR);
		vec2 hpos = (uv + mv) * vec2(vs) - 0.5;
		ivec2 hb = ivec2(floor(hpos));
		vec2 hf = hpos - vec2(hb);
		vec4 h = vec4(0.0);
		float hn = 0.0, hw = 0.0;
		for (int i = 0; i < 4; i++) {
			ivec2 o = ivec2(i & 1, i >> 1);
			ivec2 q = hb + o;
			vec2 bw = mix(1.0 - hf, hf, vec2(o));
			float w = bw.x * bw.y;
			if (w < 1e-3 || any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, vs))) continue;
			uvec4 d = volData[rd + uint(q.y * vs.x + q.x)];
			if (d.w == 0u || abs(uintBitsToFloat(d.z) - expected) > 0.05 * expected + 0.1) continue;
			h += vec4(unpackHalf2x16(d.x), unpackHalf2x16(d.y)) * w;
			hn += float(d.w) * w;
			hw += w;
		}
		float cnt = 0.0;
		if (hw > 0.05) {
			h /= hw;
			cnt = hn / hw * min(hw * 2.0, 1.0);
			cur = mix(h, cur, 1.0 / min(cnt + 1.0, mix(8.0, 24.0, rest)));
		}
		volData[wr + uint(hp.y * vs.x + hp.x)] = uvec4(packHalf2x16(cur.rg), packHalf2x16(cur.ba), floatBitsToUint(maxDist), uint(min(cnt + 1.0, 64.0)));
	}
	imageStore(vol_img, hp, cur);
}
