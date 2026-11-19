// Full resolution: soft sun/moon shadows (one ray to a random point of the light's disc, tinted by glass) and glossy
// reflections (one GGX VNDF-sampled ray, its hit shaded with its own sun ray). Denoised in denoise.frag.glsl.
#define BL_QUAD 1
#include "/lib/rt.glsl"

shared vec4 blqP[64]; // the mirrors' final hits (block-light ray origin, normal packed in w), to pool their tests over the 2x2 quad
// a perfect mirror's final hit, its block light (times fF) still to add once the quad's tests are pooled
ElHit fh;
vec3 fF = vec3(0.0);
bool fq = false;
float maxL = 16.0; // the reflection's firefly clamp

void direct(ivec2 px, out vec4 sunOut, out vec4 refl, out float motion) {
	sunOut = vec4(1.0); refl = vec4(0.0); motion = 0.0;
	float depth = texelFetch(el_depthNoHand, px, 0).r;
	if (el_isSky(depth)) return;
	vec3 world = el_screenToWorld((vec2(px) + 0.5) / el_screenSize, depth);
	GNormal g = decodeGNormal(texelFetch(gNormal, px, 0));
	Mat m = decodeMat(texelFetch(gMat, px, 0));
	vec3 p = world + g.geo * rayLift(world);

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
	sunOut = vec4(vis, caustic);

	// ---- reflections ----
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
		hitBlockLightTraced = rough >= 0.005; // glossy: its history averages the one-ray block light (denoise.frag.glsl aRf)
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
				bool cached = !mirror && cachedGi(h.position, cg);
				fq = !hitBlockLightTraced && !cached; // its block light in main(), tested and summed with the quad's
				if (fq) fh = h;
				vec3 amb = cached ? cg : fq ? hitAmbientSky(h) : hitAmbient(h);
				// (the last silver mirror: its light only, the iron block's texture must not show at the end of the corridor)
				vec3 alb = matF0(hm, lin);
				if (mirror && abs(hm.f0 - 237.0) < 0.5) refl.rgb += thr * alb * amb;
				else refl.rgb += thr * shadeHitAmbA(h, rd, true, true, amb, alb);
				fF = thr * alb * hitAo(h);
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
		maxL = mix(16.0, 3.0, smoothstep(0.0, 0.15, rough));
	}
#endif
	// a mirror shows the reflected image, refl.a behind it: the upscaler's motion vectors follow that, not the surface
	bool mirror = m.smoothness >= 0.97 && isMetal(m.f0) && refl.a != 0.0; // negative = glowing hit, same distance
	motion = mirror ? length(world) + abs(refl.a) : 0.0;
}

void main() {
	ivec2 px = ivec2(gl_GlobalInvocationID.xy);
	bool inside = all(lessThan(px, ivec2(el_screenSize)));
	vec4 sunOut = vec4(1.0), refl = vec4(0.0);
	float motion = 0.0;
	if (inside) direct(px, sunOut, refl, motion);
#if REFLECTIONS != REFLECTIONS_OFF
	// the quad's members whose hits lie close by share the block light's visibility tests (lib/rt.glsl BL_QUAD); every
	// invocation reaches both barriers
	uint li = gl_LocalInvocationIndex;
	vec3 fp = fq ? fh.position + fh.normal * 0.05 : vec3(1e9); // (hitBlockLight's ray origin)
	blqP[li] = vec4(fp, uintBitsToFloat(packSnorm4x8(vec4(fq ? fh.normal : vec3(0.0), 0.0))));
	barrier();
	blQuad = int(li & 1u) | int((li >> 3) & 1u) << 1;
	blT = uint[2](0u, 0u);
	if (fq) {
		// close by and not facing away: not the other side of a door or a pane (the floor and wall at a corner share)
		for (int x = 1; x < 4; x++) {
			vec4 o = blqP[li ^ uint((x & 1) | (x >> 1) * 8)];
			if (distance(o.xyz, fp) < 0.4 && dot(unpackSnorm4x8(floatBitsToUint(o.w)).xyz, fh.normal) > -0.5) blNear |= 1u << (blQuad ^ x);
		}
		blPhase = 1;
		hitBlockLight(fh);
	}
	blqT[li] = uvec2(blT[0], blT[1]);
	barrier();
	if (fq) { blPhase = 2; refl.rgb += fF * hitBlockLight(fh); }
	// fireflies: a glossy ray that happens to hit a flame or a glowstone stays a bright speck through every filter
	refl.rgb *= min(1.0, maxL / max(el_luminance(refl.rgb), 1e-4));
#endif
	if (!inside) return;
	imageStore(sunRaw_img, px, sunOut);
	imageStore(reflRaw_img, px, refl);
	imageStore(el_motionDistance_img, px, vec4(motion));
}
