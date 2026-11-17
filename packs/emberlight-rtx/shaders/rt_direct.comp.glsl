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
				refl.a = path;
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
}
