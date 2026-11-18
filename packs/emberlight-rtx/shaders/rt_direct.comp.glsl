// Full resolution: soft sun/moon shadows (one ray to a random point of the light's disc, tinted by glass) and glossy
// reflections (one GGX VNDF-sampled ray, its hit shaded with its own sun ray). Denoised in denoise.frag.glsl.
#include "/lib/rt.glsl"

void main() {
	ivec2 px = ivec2(gl_GlobalInvocationID.xy);
	if (any(greaterThanEqual(px, ivec2(el_screenSize)))) return;
	float depth = texelFetch(el_depth, px, 0).r;
	if (el_isSky(depth)) {
		imageStore(sunRaw_img, px, vec4(1.0));
		imageStore(reflRaw_img, px, vec4(0.0));
		imageStore(el_motionDistance_img, px, vec4(0.0));
		return;
	}
	vec3 world = el_screenToWorld((vec2(px) + 0.5) / el_screenSize, depth);
	GNormal g = decodeGNormal(texelFetch(gNormal, px, 0));
	Mat m = decodeMat(texelFetch(gMat, px, 0));
	vec3 p = world + g.geo * (0.004 + 0.0015 * length(world));

	// ---- sun / moon (the geometric normal decides what faces the light: normal maps shade, they do not self-shadow) ----
	// a: water caustics, not denoised (crisp, animated): >= 0 multiplies the sun light that came through water,
	// < 0 is minus the sunlight the water below reflects onto this point (lighting.frag.glsl)
	vec3 vis = vec3(0.0);
	float caustic = 1.0;
#if !defined(EL_DIMENSION_NETHER)
	vec3 l = lightDir();
	bool foliage = matThin(m.kind);
	if (dot(g.geo, l) > 0.0 || foliage) {
		vec3 dir = coneSample(l, cos(0.0085 * SHADOW_SOFTNESS * (1.0 + 0.5 * RTX_INTENSITY)), blueNoise2(px, 1));
		// the shaded side of a leaf only sees the sun through the foliage (subsurface scattering in lighting.frag.glsl);
		// worth the extra rays only where the camera looks toward the sun (forward scattering)
		if (foliage && dot(g.geo, l) <= 0.0 && SSS > 0.0 && dot(world, l) > 0.0) vis = vec3(foliageTransmission(world + l * 0.05, dir));
		else vis = sunTransmissionC(foliage ? world + l * 0.05 : p, dir, true, caustic);
	}
#if defined(EL_DIMENSION_DEFAULT)
	if (CAUSTICS > 0.0 && caustic == 1.0) {
		float rc = reflectedCaustic(p, g.geo, l, g.light.y);
		if (rc > 0.0) caustic = -rc;
	}
#endif
#endif
	imageStore(sunRaw_img, px, vec4(vis, caustic));

	// ---- reflections ----
	vec4 refl = vec4(0.0);
#if REFLECTIONS != REFLECTIONS_OFF
	float rough = roughnessOf(m.smoothness);
	if (rough <= REFL_MAX_ROUGH && m.smoothness > 0.05) {
		vec3 v = normalize(world);
		vec3 rd = glossyDir(v, specularNormal(g.n, g.geo), max(rough, 0.002), blueNoise2(px, 2));
		if (dot(rd, g.geo) < 0.01) rd = reflect(v, g.geo);
		// Mirrors in the mirror: a hit on another perfect metal mirror (Emberlight PBR's concrete) reflects on, up to
		// MIRROR_BOUNCES hits, so two facing mirrors show the corridor of images. a = the path length, which is where
		// the reflected image sits behind the surface (the denoiser reprojects the history by it).
		vec3 thr = vec3(1.0);
		float path = 0.0;
		for (int b = 0; b < MIRROR_BOUNCES; b++) {
			// the first-person body too: a mirror shows the player (shadows already trace it)
			ElHit h = el_traceClosest(p, rd, 256.0, MASK_SOLID | EL_MASK_PLAYER);
			// glass, ice, slime on the way (the first layer only): its frame texels shaded, a Fresnel sheen of the sky,
			// the rest tinted as resolve.frag.glsl tints the camera's view through it. No alpha test: the clear centre of
			// a glass block still reflects. Shortcut: glass behind water or a portal is not seen, a second ray would
			ElHit tg = el_traceSurface(p, rd, h.t, EL_MASK_TRANSLUCENT);
			if (tg.hit && !tg.entity && tg.blockId != ID_WATER && tg.blockId != ID_PORTAL) {
				vec4 ga = el_hitAlbedo(tg);
				vec3 gl = el_srgbToLinear(ga.rgb * tg.color.rgb);
				float frame = smoothstep(0.8, 0.98, ga.a);
				if (isGlassId(tg.blockId)) frame = max(frame, ga.a * smoothstep(0.5, 0.6, ga.a));
				float gf = 0.04 + 0.96 * pow(1.0 - abs(dot(tg.normal, rd)), 5.0);
				refl.rgb += thr * mix(gf * missRadiance(reflect(rd, tg.normal), tg.light.y), shadeHit(tg, rd, false, false), frame);
				thr *= (1.0 - frame) * (1.0 - gf) * mix(vec3(1.0), gl / max(max(gl.r, max(gl.g, gl.b)), 1e-3) * 0.85, clamp(ga.a * 1.3, 0.0, 1.0));
				if (max(thr.r, max(thr.g, thr.b)) < 0.02) { refl.a = path + tg.t; break; } // a frame texel: nothing behind it shows
			} else if (tg.hit && !tg.entity && tg.blockId == ID_WATER && tg.normal.y > 0.5) {
				// a lake seen from above: the sky's Fresnel sheen, the bottom absorbed and blued as resolve.frag.glsl shows
				// the camera's view (not refracted). Left out, the mirror showed the bare lake bottom, its seagrass black
				// (sky light fades with depth). shortcut: the surface seen from below or from the side stays left out
				vec3 water = el_srgbToLinear(tg.color.rgb * (0.75 + 0.25 * el_luminance(el_hitAlbedo(tg).rgb))) * (0.4 + 0.6 * daylight); // (translucent.frag.glsl's colour)
				vec3 T = exp(-vec3(0.38, 0.10, 0.06) * (h.t - tg.t) * 1.2);
				vec3 insc = water * (lightColor() * max(lightDir().y, 0.0) * 0.06 + skyAmbient(vec3(0.0, 1.0, 0.0)) * 0.35) * tg.light.y + water * 0.004;
				float F = 0.02 + 0.98 * pow(1.0 - abs(dot(tg.normal, rd)), 5.0);
				refl.rgb += thr * (F * missRadiance(reflect(rd, tg.normal), tg.light.y) + (1.0 - F) * insc * (1.0 - T));
				thr *= (1.0 - F) * T;
				if (max(thr.r, max(thr.g, thr.b)) < 0.02) { refl.a = path + tg.t; break; } // deep: the bottom does not show
			}
			if (!h.hit) { refl.rgb += thr * missRadiance(rd, g.light.y); refl.a = 256.0; break; }
			path += h.t;
			vec4 ha = el_hitAlbedo(h);
			vec3 srgb = ha.rgb * h.color.rgb, lin = el_srgbToLinear(srgb);
			Mat hm = hitMaterial(h, srgb);
			bool mirror = hm.smoothness >= 0.97 && isMetal(hm.f0);
			if (b + 1 == MIRROR_BOUNCES || !mirror) {
				// the reflected surface with the light it received on screen last frame (neon glow on a wall), else the
				// lightmap. Not for the last mirror of a chain: shaded as a wall, its cached light made bright panels with
				// black holes wherever the screen did not see it; the lightmap lets the corridor of images fade out.
				vec3 cg;
				// (the last silver mirror: its light only, the iron block's texture must not show at the end of the corridor)
				if (mirror && abs(hm.f0 - 237.0) < 0.5) refl.rgb += thr * matF0(hm, lin) * hitAmbient(h);
				else refl.rgb += thr * shadeHitAmb(h, rd, true, true, !mirror && cachedGi(h.position, cg) ? cg : hitAmbient(h));
				// a glowing hit: negative distance, so bloom gives the reflected neon its halo too (denoise.frag.glsl)
				refl.a = hm.emission > 0.25 && max(thr.r, max(thr.g, thr.b)) > 0.1 ? -path : path;
				break;
			}
			refl.rgb += thr * hitEmission(h, srgb, lin);
			thr *= fresnelSchlick(matF0(hm, lin), abs(dot(h.normal, rd)));
			if (max(thr.r, max(thr.g, thr.b)) < 0.02) { refl.a = path; break; }
			rd = reflect(rd, h.normal);
			p = h.position + h.normal * 0.003;
		}
		// fireflies: a glossy ray that happens to hit a flame or a glowstone stays a bright speck through every filter
		float maxL = mix(16.0, 3.0, smoothstep(0.0, 0.15, rough));
		refl.rgb *= min(1.0, maxL / max(el_luminance(refl.rgb), 1e-4));
	}
#endif
	imageStore(reflRaw_img, px, refl);
	// a mirror shows the reflected image, refl.a behind it: the upscaler's motion vectors follow that, not the surface
	bool mirror = m.smoothness >= 0.97 && isMetal(m.f0) && refl.a > 0.0;
	imageStore(el_motionDistance_img, px, vec4(mirror ? length(world) + refl.a : 0.0));
}
