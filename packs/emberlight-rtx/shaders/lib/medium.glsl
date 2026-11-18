// Thick translucent blocks (glass blocks, stained glass, ice, slime, honey) seen from outside: the view ray refracts
// into the block, travels to its back face (traced, so a wall of glass three blocks deep is three blocks of glass),
// is absorbed on the way (Beer-Lambert from the block's colour), may be totally internally reflected, refracts out and
// shows what lies behind (the lit screen pixel where that is visible, else a traced shade; water under a glass floor
// is refracted and absorbed too). Ice, slime and honey also scatter: sunlight that enters anywhere along the path is
// scattered toward the camera (single scattering, one sample per segment, Henyey-Greenstein forward lobe), so a
// backlit ice wall glows. Panes stay thin sheets (resolve.frag.glsl).
#ifndef RTX_MEDIUM
#define RTX_MEDIUM 1

// light on one side of a frame texel (no ray): sky, sun and the sunlit ground below (a frame faces sideways: half its
// light is bounce, which the GI gives opaque walls)
vec3 frameSide(vec3 n, vec3 l, float skyLight) {
	return skyAmbient(n) * skyLight * skyLight + lightColor() * (max(dot(n, l), 0.0) + max(l.y, 0.0) * 0.3 * (1.0 - max(n.y, 0.0))) * skyLight;
}
// light on the frame texels of glass: a thin glass edge, lit through the block from behind as well as from the front
// (lit from the front only, a shaded frame turned dark sky-blue instead of the bright frame of vanilla glass)
vec3 frameLight(vec3 albedoLin, vec3 n, float skyLight) {
	vec3 l = lightDir();
	return albedoLin * (frameSide(n, l, skyLight) + frameSide(-n, l, skyLight));
}

bool isThick(int kind) { return kind == ID_GLASS || kind == ID_ICE || kind == ID_SLIME || kind == ID_HONEY; }
float mediumIor(int kind) { return kind == ID_ICE ? 1.31 : kind == ID_GLASS ? 1.5 : 1.42; }

struct Medium { vec3 sigmaA; vec3 sigmaS; vec3 scatter; };

// per-block coefficients from the surface colour (linear) and texture alpha: one block of stained glass passes about
// its saturated tint, clear glass a faint green; scattering media also get a scattering coefficient and albedo
Medium mediumOf(int kind, vec3 tintLin, float a) {
	vec3 tn = tintLin / max(max(tintLin.r, max(tintLin.g, tintLin.b)), 1e-3);
	float density = kind == ID_GLASS ? clamp(a * 1.4, 0.0, 0.9) : kind == ID_ICE ? 0.45 : kind == ID_SLIME ? 0.75 : 0.9;
	vec3 T1 = mix(vec3(1.0), tn * 0.95, density);
	if (kind == ID_GLASS && a < 0.05) T1 = vec3(0.965, 0.985, 0.975);
	float s = kind == ID_ICE ? 0.12 : kind == ID_SLIME ? 0.6 : kind == ID_HONEY ? 0.5 : 0.0; // ice: clear with a glow, not frosted
	return Medium(-log(max(T1, vec3(0.002))), vec3(s), sqrt(tn));
}

float hgPhase4pi(float c, float g) { float g2 = g * g; return (1.0 - g2) / pow(1.0 + g2 - 2.0 * g * c, 1.5); } // isotropic = 1

// light scattered toward the camera along a segment o + d * [0, L] inside the medium
vec3 mediumInscatter(vec3 o, vec3 d, float L, Medium md, vec3 sigmaT, float skyLight) {
	if (md.sigmaS.x <= 0.0 || SSS <= 0.0 || L <= 0.0) return vec3(0.0);
	vec3 m = o + d * (L * 0.5);
	vec3 l = lightDir();
	vec3 sun = vec3(0.0);
	if (dot(lightColor(), vec3(1.0)) > 1e-3) {
		// where the sunlight reaching m entered the medium, and what it crossed before
		ElHit x = el_traceSurface(m, l, 8.0, EL_MASK_TRANSLUCENT);
		float ls = x.hit ? x.t : 0.5;
		vec3 vis = el_traceVisibility(m, l, ls, MASK_SOLID) ? sunTransmission(m + l * (ls + 0.004), l, false) : vec3(0.0);
		sun = lightColor() * vis * exp(-sigmaT * ls) * hgPhase4pi(dot(d, l), 0.55);
	}
	vec3 amb = skyAmbient(vec3(0.0, 1.0, 0.0)) * skyLight * skyLight;
	// block light (the light list's sources of the cell, unshadowed, as hitBlockLight): without it slime and honey next to
	// a lamp scattered nothing at night and showed only the dark floor through two blocks of absorption
	// shortcut: no lightmap gate, a lamp behind a wall still lights the jelly; gate it if that shows up
	vec3 blk = vec3(0.0);
	uvec2 c = el_lightCell(m);
	uint cnt = c.y - c.x, nl = min(cnt, 8u);
	for (uint i = 0u; i < nl; i++) {
		ElLight L = el_light(el_lightIndex(c.x + uint(float(i) * float(cnt) / float(nl))));
		blk += L.color * lightFalloff(max(distance(L.position, m), 0.05), L.range);
	}
	blk *= float(cnt) / float(max(nl, 1u)) * BLOCKLIGHT_STRENGTH * 1.25; // (a quarter of a surface's: x5 made the blocks glow opaque)
	vec3 src = (sun + amb * 0.25 + blk) * md.sigmaS * md.scatter;
	return src * (1.0 - exp(-sigmaT * L)) / max(sigmaT, vec3(1e-4)) * SSS;
}

// radiance leaving a hit toward the ray: the lit screen pixel when the hit is what the screen shows there (with its GI
// and reflections), else shaded from its own sun ray and lightmap
vec3 seenHit(ElHit h, vec3 d, float skyLight) {
	if (!h.hit) return skyRadiance(d) * smoothstep(0.05, 0.6, skyLight);
	vec3 view = el_worldToView(h.position);
	vec4 clip = el_projection * vec4(view, 1.0);
	if (clip.w > 0.0) {
		vec2 suv = clip.xy / clip.w * 0.5 + 0.5;
		if (all(greaterThan(suv, vec2(0.0))) && all(lessThan(suv, vec2(1.0)))) {
			ivec2 q = ivec2(suv * el_screenSize);
			float vd = -view.z;
			if (abs(el_linearDepth(texelFetch(el_depthOpaque, q, 0).r) - vd) < 0.03 * vd + 0.08) return texelFetch(lit, q, 0).rgb;
		}
	}
	return shadeHit(h, d, true, true);
}

// a ray from o under the surface of water w down to the bottom: absorbed and in-scattered
vec3 inWater(vec3 o, vec3 dw, ElHit w, float skyLight) {
	ElHit b = el_traceClosest(o, dw, 48.0, MASK_SOLID);
	vec3 T = exp(-vec3(0.38, 0.10, 0.06) * (b.hit ? b.t : 48.0) * 1.2);
	vec3 water = el_srgbToLinear(w.color.rgb) * 0.8 * (0.4 + 0.6 * daylight);
	vec3 insc = water * (lightColor() * max(lightDir().y, 0.0) * 0.06 + skyAmbient(vec3(0.0, 1.0, 0.0)) * 0.35) * skyLight + water * 0.004;
	vec3 below = b.hit ? seenHit(b, dw, skyLight) : vec3(0.0);
	return below * T + insc * (1.0 - T);
}

// a water surface hit from above through a glass floor: refract in, absorb down to the bottom, reflect the sky
vec3 throughWater(ElHit w, vec3 d, float skyLight) {
	vec3 nw = w.normal;
	vec3 dw = refract(d, nw, 1.0 / 1.33);
	if (dw == vec3(0.0)) dw = reflect(d, nw);
	float F = 0.02 + 0.98 * pow(1.0 - abs(dot(d, nw)), 5.0);
	return mix(inWater(w.position - nw * 0.01, dw, w, skyLight), skyRadiance(reflect(d, nw)) * smoothstep(0.05, 0.6, skyLight), F);
}

// what the camera sees through a thick block entered at surf (n faces the camera) along the view direction v
vec3 thickTransmission(vec3 surf, vec3 n, vec3 v, int kind, vec3 tintLin, float a, float skyLight) {
	float ior = mediumIor(kind);
	Medium md = mediumOf(kind, tintLin, a);
	vec3 sigmaT = md.sigmaA + md.sigmaS;
	vec3 d = refract(v, n, 1.0 / ior);
	vec3 o = surf - n * 0.004;
	vec3 thr = vec3(1.0), insc = vec3(0.0);
	bool left = false;
	bool jelly = kind == ID_SLIME || kind == ID_HONEY;
	for (int i = 0; i < 3 + (jelly ? 6 : 0); i++) { // up to two total internal reflections (jelly: plus its inner cubes)
		ElHit e = el_traceSurface(o, d, 16.0, EL_MASK_TRANSLUCENT);
		ElHit s = el_traceClosest(o, d, e.t, MASK_SOLID);
		if (s.hit) { // the medium ends at a solid block (glass against stone): seen through the medium
			insc += thr * mediumInscatter(o, d, s.t, md, sigmaT, skyLight);
			return thr * exp(-sigmaT * s.t) * seenHit(s, d, skyLight) + insc;
		}
		insc += thr * mediumInscatter(o, d, e.t, md, sigmaT, skyLight);
		thr *= exp(-sigmaT * e.t);
		if (!e.hit) break;
		// Slime and honey hold an inner cube of the same jelly: its faces (off the block grid) have the same medium on both
		// sides, so the ray goes straight on (refracting there trapped it by total internal reflection: black cores at
		// night), but their texels still show, lit like the frame texels plus the lamps nearby: the vanilla inner cube.
		vec3 an = abs(e.normal);
		vec3 wp = e.position + fract(el_cameraPosition); // (fractional camera position: same grid, no precision loss far out)
		float ax = an.x > an.y && an.x > an.z ? wp.x : an.y > an.z ? wp.y : wp.z;
		if (jelly && e.blockId == kind && abs(ax - round(ax)) > 0.02) {
			vec4 ia = el_hitAlbedo(e);
			vec3 ic = el_srgbToLinear(ia.rgb * e.color.rgb);
			insc += thr * ia.a * (frameLight(ic, e.normal, skyLight) + ic * hitBlockLight(e));
			thr *= 1.0 - ia.a;
			o = e.position + d * 0.004;
			continue;
		}
		// back face: the opaque frame texels of a glass block are seen from inside, the rest lets the ray out
		vec4 ea = el_hitAlbedo(e);
		if (kind == ID_GLASS && ea.a > 0.9) return thr * frameLight(el_srgbToLinear(ea.rgb * e.color.rgb), -e.normal, skyLight) + insc;
		vec3 d2 = refract(d, e.normal, ior);
		// the bottom face of a block standing in water: Minecraft draws the water below 1/9 block lower, and that air
		// gap made the face a total-reflection mirror (a band of sky and treetops at the foot of a glass wall in a pool).
		// The block rests on the water: the ray goes on into it
		if (e.normal.y > 0.5) {
			ElHit w = el_traceClosest(e.position - e.normal * 0.004, vec3(0.0, -1.0, 0.0), 0.2, EL_MASK_TRANSLUCENT);
			if (w.hit && !w.entity && w.blockId == ID_WATER) {
				vec3 dw = refract(d, e.normal, ior / 1.33);
				// shortcut: past glass-water's critical angle (62 degrees) the ray goes on unbent instead of reflecting
				if (dw == vec3(0.0)) dw = d;
				return thr * inWater(w.position - vec3(0.0, 0.01, 0.0), dw, w, skyLight) + insc;
			}
		}
		if (d2 == vec3(0.0)) { d = reflect(d, e.normal); o = e.position + e.normal * 0.004; continue; }
		o = e.position - e.normal * 0.004;
		d = d2;
		// the exit interface reflects its share too (the entry's is the resolve pass's Fresnel mix): 0.96 x 0.96 at
		// normal incidence through clear glass, not 0.96
		float f0 = pow((ior - 1.0) / (ior + 1.0), 2.0);
		thr *= 1.0 - (f0 + (1.0 - f0) * pow(1.0 - abs(dot(d2, e.normal)), 5.0));
		left = true;
		break;
	}
	if (!left) return insc; // trapped or deeper than 16 blocks: only the scattered light comes out
	// outside again: the scene, water below a glass floor, another (stained) block or pane on the way
	for (int i = 0; i < 2; i++) {
		ElHit h = el_traceClosest(o, d, 256.0, MASK_SOLID | EL_MASK_TRANSLUCENT);
		if (h.hit && !h.entity && h.blockId == ID_WATER) return thr * throughWater(h, d, skyLight) + insc;
		if (i == 0 && h.hit && !h.entity && (isGlassId(h.blockId) || h.blockId == ID_ICE || h.blockId == ID_SLIME || h.blockId == ID_HONEY)) {
			vec4 ha = el_hitAlbedo(h);
			vec3 c = el_srgbToLinear(ha.rgb * h.color.rgb);
			if (isGlassId(h.blockId) && ha.a > 0.9) return thr * frameLight(c, h.normal, skyLight) + insc; // its frame
			thr *= mix(vec3(1.0), c / max(max(c.r, max(c.g, c.b)), 1e-3) * 0.92, clamp(ha.a * 1.4, 0.0, 1.0));
			o = h.position + d * 0.004;
			continue;
		}
		return thr * seenHit(h, d, skyLight) + insc;
	}
	return thr * seenHit(el_traceClosest(o, d, 256.0, MASK_SOLID), d, skyLight) + insc;
}
#endif
