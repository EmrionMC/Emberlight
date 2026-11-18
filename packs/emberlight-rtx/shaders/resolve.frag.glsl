// Translucent surfaces (water: ray-traced reflection, screen-space refraction, absorption; glass/ice: tinted
// transmission + ray-traced reflection), then fog/volumetrics, underwater fog and the forward layer (particles, hand).
#include "/lib/rt.glsl"
#include "/lib/sky.glsl"
#include "/lib/fog.glsl"
#include "/lib/medium.glsl"

layout(location = EL_OUT_lit) out vec4 outLit;

vec3 reflection(vec3 p, vec3 rd, float skyLight) {
	// the first-person body shows in water and glass too, except from a surface it stands in (swimming: the rays would
	// start inside it)
	vec3 eye = el_eyePosition - el_cameraPosition;
	bool inBody = all(lessThan(abs(p.xz - eye.xz), vec2(0.6))) && p.y > eye.y - 2.0 && p.y < eye.y + 0.4;
	ElHit h = el_traceClosest(p, rd, 256.0, inBody ? MASK_SOLID : MASK_SOLID | EL_MASK_PLAYER);
	if (h.hit) return shadeHit(h, rd, true, true);
	return skyRadiance(rd) * smoothstep(0.05, 0.6, skyLight);
}

void main() {
	ivec2 px = ivec2(gl_FragCoord.xy);
	vec4 lit0 = texelFetch(lit, px, 0);
	vec3 c = lit0.rgb;
	float through = 1.0; // how much of the opaque pixel (and its bloom gain) still shows
	float depth = texelFetch(el_depthNoHand, px, 0).r;
	vec3 world = el_screenToWorld(el_uv, depth);
	vec3 v = normalize(world);
	bool underwater = el_eyeInWater == 1;

	vec4 ta = texelFetch(tAlbedo, px, 0);
	if (ta.a > 0.0) {
		// the surface itself: el_depth holds the translucents' depth; where the hand (window depth < 0.56) covers it,
		// the opaque depth stands in
		float sd = texelFetch(el_depth, px, 0).r;
		if (sd < 0.56) sd = depth;
		vec3 surf = el_screenToWorld(el_uv, sd);
		vec4 tn = texelFetch(tNormal, px, 0);
		vec3 n = el_decodeNormal(tn.xy);
		int kind = int(tn.z * 16.0 + 0.5);
		float skyLight = tn.w;
		vec3 tint = el_srgbToLinear(ta.rgb);
		vec3 p = surf + n * 0.03;
		float nv = abs(dot(n, -v));
		if (kind == ID_WATER) {
			// refraction: bend the lookup by the waves' tilt (normal minus the flat face normal), but only onto things
			// behind the surface. The whole normal shifted everything below a flat surface by a constant (a displaced
			// copy of the submerged half of a boat when looking down at it)
			vec3 face = abs(n.y) > 0.5 ? vec3(0.0, sign(n.y), 0.0) : abs(n.x) > abs(n.z) ? vec3(sign(n.x), 0.0, 0.0) : vec3(0.0, 0.0, sign(n.z));
			vec3 nView = mat3(el_view) * (n - face);
			vec2 ruv = el_uv + nView.xy * 0.12 / (1.0 + length(surf) * 0.02);
			float rd = texture(el_depthOpaque, ruv).r;
			if (rd <= sd || any(lessThan(ruv, vec2(0.0))) || any(greaterThan(ruv, vec2(1.0)))) { ruv = el_uv; rd = texelFetch(el_depthOpaque, px, 0).r; }
			vec3 behind = texture(lit, ruv).rgb;
			vec3 bottom = el_screenToWorld(ruv, rd);
			float path = el_isSky(rd) ? 64.0 : length(bottom - surf);
			if (underwater) path = 0.0; // looking up out of the water: the air behind needs no absorption
			vec3 T = exp(-vec3(0.38, 0.10, 0.06) * path * 1.2);
			vec3 water = tint * (0.4 + 0.6 * daylight);
			vec3 inscatter = water * (lightColor() * max(lightDir().y, 0.0) * 0.06 + skyAmbient(vec3(0.0, 1.0, 0.0)) * 0.35) * skyLight + water * 0.004;
			vec3 refr = behind * T + inscatter * (1.0 - T);
			vec3 rdir = reflect(v, n);
			if (!underwater && rdir.y < 0.02) rdir = normalize(vec3(rdir.x, 0.02, rdir.z));
			float F = 0.02 + 0.98 * pow(1.0 - nv, 5.0);
			if (underwater) F = nv < 0.66 ? 1.0 : F; // total internal reflection outside Snell's window
			vec3 refl = reflection(p, rdir, underwater ? 0.0 : skyLight);
			c = mix(refr, refl, clamp(F * rtxBoost(), 0.0, 1.0));
			through = 1.0 - clamp(F * rtxBoost(), 0.0, 1.0);
		} else if (kind == 5) { // nether portal: a glowing, swirling sheet
			c = c * 0.35 + tint * (1.5 + 2.5 * ta.a) * EMISSIVE_STRENGTH * rtxBoost();
		} else {
			float a = ta.a;
			if (dot(n, v) > 0.0) n = -n;
			nv = abs(dot(n, -v));
			// the surface's own diffuse light: only for opaque texels (the frame of a glass block). A clear tinted
			// medium adds none; on stained glass (alpha ~0.6) it made walls milky. Glass frame and streak texels are
			// more opaque than the pane between them (stained 0.64 vs 0.4): they blend their lit colour by alpha
			float frame = smoothstep(0.8, 0.98, a);
			if (isGlassId(kind)) frame = max(frame, a * smoothstep(0.5, 0.6, a));
			vec3 own = frameLight(tint, n, skyLight);
			float ior = kind == ID_ICE ? 1.31 : 1.5;
			float f0 = pow((ior - 1.0) / (ior + 1.0), 2.0);
			float F = f0 + (1.0 - f0) * pow(1.0 - nv, 5.0);
			vec3 base;
#if REFRACTION == REFRACTION_HIGH
			if (isThick(kind) && !underwater && frame < 1.0) {
				base = mix(thickTransmission(surf, n, v, kind, tint, a, skyLight), own, frame);
			} else
#endif
			{
				vec3 tnorm = tint / max(max(tint.r, max(tint.g, tint.b)), 1e-3);
				base = mix(c * mix(vec3(1.0), tnorm * 0.85, clamp(a * 1.3, 0.0, 1.0)), own, frame);
			}
			vec3 refl = reflection(p, reflect(v, n), skyLight);
			c = mix(base, refl, clamp(F * rtxBoost(), 0.0, 1.0));
			through = (1.0 - frame) * (1.0 - clamp(F * rtxBoost(), 0.0, 1.0));
		}
	}

	if (underwater) {
		// WATER medium (lib/fog.glsl): coloured transmittance to the bottom or the surface seen from below, plus the
		// in-scattered light of the volumetric pass (sun shafts), which it marched with the same coefficients
		float d = el_isSky(depth) ? 96.0 : length(world);
		float wsd = texelFetch(el_depth, px, 0).r;
		if (ta.a > 0.0 && wsd >= 0.56 && wsd < depth) d = min(d, length(el_screenToWorld(el_uv, wsd)));
		d = min(d, 96.0);
		vec3 Tw = exp(-waterExtinction() * d);
#ifdef VOLUMETRICS
		c = c * Tw + texture(vol, el_uv).rgb;
#else
		c = c * Tw + waterScatter() / waterExtinction() * skyAmbient(vec3(0.0, 1.0, 0.0)) * (0.15 + 0.85 * el_eyeBrightness.y) * 0.3 * (1.0 - Tw);
#endif
	} else {
		// aerial perspective (the atmosphere's haze on distant terrain), then fog and god rays
		if (!el_isSky(depth)) aerialPerspective(c, v, length(world));
#ifdef VOLUMETRICS
		vec4 fog = texture(vol, el_uv);
		c = c * fog.a + fog.rgb;
#else
		float dist = el_isSky(depth) ? 160.0 : length(world);
		float T = exp(-fogDensity(el_cameraPosition.y) * dist);
		c = c * T + fogTint(v) * airAlbedo() * (1.0 - T);
#endif
		if (el_eyeInWater == 2) c = mix(c, vec3(4.0, 0.9, 0.1), 1.0 - exp(-length(world) * 0.8));
	}

	vec4 f = texelFetch(fwd, px, 0);
	c = c * (1.0 - f.a) + f.rgb;
	// a: bloom gain (lighting.frag.glsl), only for what still shows of the pixel (not on a glass frame or the hand).
	// shortcut: refracted water keeps this pixel's gain, not the one of the pixel it shows
	outLit = vec4(c, 1.0 + (lit0.a - 1.0) * through * (1.0 - f.a));
}
