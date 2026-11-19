// Ray-traced lighting shared by the passes with "raytracing": true.
#ifndef RTX_RT
#define RTX_RT 1
#include "/lib/common.glsl"
#include "/lib/materials.glsl"
#include "/lib/water.glsl"

const uint MASK_SOLID = EL_MASK_OPAQUE | EL_MASK_ENTITIES;

// Caustics: how much the waves focus the light that crossed the water surface at s after depth blocks of water
// (refraction, about a quarter of the slope bends the ray). Physically the focal length of these waves is a few dozen
// blocks; CAUSTIC_FOCUS shortens it so a 1-4 block pool already shows the bright web. Fades out with depth.
const float CAUSTIC_FOCUS = 3.0;
// viewDist: the lines are finer than a pixel far away (sparkling noise), so they fade into flat light there
// shafts: the underwater god rays keep the undivided factor (their brightness is the showpiece, not light on a floor)
float refractionCaustic(vec3 s, float depth, float viewDist, bool shafts) {
	float f = causticFactor(s.xz + el_cameraPosition.xz, el_time, min(depth, 10.0) * CAUSTIC_FOCUS, 0.25);
	if (shafts) f *= causticMean(min(depth, 10.0) * CAUSTIC_FOCUS, 0.25);
	f = mix(1.0, f, exp(-depth * 0.06) * (1.0 - smoothstep(32.0, 112.0, viewDist)));
	return mix(1.0, f, min(CAUSTICS, 1.0)) * (1.0 + 0.25 * max(CAUSTICS - 1.0, 0.0) * (f - 1.0));
}

// Sunlight the water below reflects onto p (walls and overhangs beside water): only a few percent of it at noon, a
// third and more at sunset, rippled by the same waves. 0 where no sunlit water lies along the mirrored sun direction.
float reflectedCaustic(vec3 p, vec3 n, vec3 l, float skyLight) {
	vec3 d = vec3(l.x, -l.y, l.z);
	float nd = dot(n, d);
	float fres = 0.02 + 0.98 * pow(1.0 - clamp(l.y, 0.0, 1.0), 5.0);
	if (l.y < 0.02 || skyLight < 0.3 || fres * nd * CAUSTICS < 0.012) return 0.0; // floors face away: never traced
	ElHit h = el_traceClosest(p, d, 32.0, EL_MASK_TRANSLUCENT);
	if (!h.hit || h.blockId != ID_WATER || h.normal.y < 0.5) return 0.0;
	if (!el_traceVisibility(p, d, h.t, MASK_SOLID) || !el_traceVisibility(h.position + vec3(0.0, 0.02, 0.0), l, 512.0, MASK_SOLID)) return 0.0;
	float f = mix(1.0, causticFactor(h.position.xz + el_cameraPosition.xz, el_time, min(h.t, 5.0), 2.0), 1.0 - smoothstep(32.0, 112.0, length(p)));
	return fres * nd * f * CAUSTICS;
}

// Visibility of the sun/moon from p, tinted by stained glass and water on the way (coloured light through windows).
// caustic: the refraction caustic of the first water surface crossed (1 = none)
// likelyLit (volumetrics: the previous march step was lit): first one ray against solid and translucent geometry
// together, which answers the common case "nothing at all in the way" alone (the same alpha test, so the result of the
// two rays below, up to the 32-candidate cap of el_traceVisibility). Not worth it for surfaces: with last frame's visibility as the guess, rt_direct got slower in
// the forest (dappled light guesses wrong) and gained nothing elsewhere; the translucent ray is cheap there.
vec3 sunTransmissionC(vec3 p, vec3 dir, bool withPlayer, out float caustic, bool likelyLit) {
	caustic = 1.0;
	uint mask = withPlayer ? MASK_SOLID | EL_MASK_PLAYER : MASK_SOLID;
	if (likelyLit && el_traceVisibility(p, dir, 512.0, mask | EL_MASK_TRANSLUCENT)) return vec3(1.0);
	if (!el_traceVisibility(p, dir, 512.0, mask)) return vec3(0.0);
	vec3 t = vec3(1.0); // (no separate "any translucent?" ray first: the first closest hit answers that too)
	vec3 o = p;
	for (int i = 0; i < 3; i++) {
		ElHit h = el_traceClosest(o, dir, 512.0, EL_MASK_TRANSLUCENT);
		if (!h.hit) break;
		vec4 a = el_hitAlbedo(h);
		vec3 c = el_srgbToLinear(a.rgb * h.color.rgb);
		if (h.blockId == ID_WATER) {
			t *= vec3(0.55, 0.85, 0.95);
			if (CAUSTICS > 0.0 && caustic == 1.0 && abs(h.normal.y) > 0.5) caustic = refractionCaustic(h.position, distance(h.position, p), length(p), false);
		}
		else if (isGlassId(h.blockId)) {
			// saturated tint, strength by the texel's opacity; clear glass barely dims
			vec3 tint = c / max(max(c.r, max(c.g, c.b)), 1e-3);
			t *= mix(vec3(1.0), tint * 0.92, clamp(a.a * 1.4, 0.0, 1.0));
		} else t *= mix(vec3(1.0), c, a.a);
		o = h.position + dir * 0.002;
	}
	return t;
}
vec3 sunTransmissionC(vec3 p, vec3 dir, bool withPlayer, out float caustic) { return sunTransmissionC(p, dir, withPlayer, caustic, false); }
vec3 sunTransmission(vec3 p, vec3 dir, bool withPlayer) { float c; return sunTransmissionC(p, dir, withPlayer, c, false); }

// Sunlight that reaches the shaded side of a leaf through the foliage in front of it: every leaf face on the way
// passes 50 % (a leaf scatters, it does not block), anything else blocks. Four faces: about two blocks of leaves
// (six cost 13 % FPS in a backlit forest).
float foliageTransmission(vec3 p, vec3 dir) {
	float t = 1.0;
	for (int i = 0; i < 4; i++) {
		ElHit h = el_traceClosest(p, dir, 256.0, MASK_SOLID | EL_MASK_PLAYER);
		if (!h.hit) return t;
		if (h.blockId != 10) return 0.0;
		t *= 0.5;
		p = h.position + dir * 0.01;
	}
	return 0.0;
}

// Material of a traced hit: the LabPBR resource pack's _s texel there (el_hitSpecular), as the gbuffer does, else the
// built-in table. With the maps, blocks that glow without being a light source (Emberlight PBR's neon terracotta) light
// their surroundings through the GI rays that hit them and glow in reflections.
Mat hitMaterial(ElHit h, vec3 albedoSrgb) {
#if PBR_SOURCE == PBR_SOURCE_AUTO
	vec4 s = el_hitSpecular(h);
	if (s != vec4(0.0)) return Mat(s.r, s.g * 255.0, el_material(s).emission, h.blockId == 10 ? KIND_FOLIAGE : KIND_DEFAULT);
#endif
	return tableMaterial(h.blockId, albedoSrgb);
}

// glow of a hit as the camera would see it (lighting.frag.glsl's scale); rt_gi raises it: as GI the emitters light more
#ifndef HIT_EMISSION_SCALE
#define HIT_EMISSION_SCALE 2.0
#endif
vec3 hitEmission(ElHit h, vec3 albedoSrgb, vec3 albedoLin) {
	return emissiveColor(albedoLin) * hitMaterial(h, albedoSrgb).emission * EMISSIVE_STRENGTH * HIT_EMISSION_SCALE * rtxBoost();
}

// Is a hit emitter one of the RT light list's sources (sampled directly by blockLights(), so its glow must not count
// again)? Only if a listed source sits in the hit's own block: testing for any source in the grid cell switched off the
// glow of neon terracotta (no light source) wherever a torch was in range. Outside the grid nothing is listed.
bool emitterListed(ElHit h) {
	vec3 b = h.position - h.normal * 0.05; // inside the hit block
	uvec2 c = el_lightCell(b);
	for (uint k = c.x; k < c.y; k++)
		if (all(lessThan(abs(el_light(el_lightIndex(k)).position - b), vec3(0.52)))) return true;
	return false;
}

// ---- block lights (RT light list) -------------------------------------------------------------------------------------

// Diffuse lighting from the block light sources: ReSTIR DI in lib/restir.glsl (lightFalloff: above, hitBlockLight). A source is a box of half-size radius
// around its position that emits from its face turned most toward p, sampled at a random point of that face: a sea lantern flush with the floor still lights the floor (from its centre it sat below
// the floor plane), and the result is a soft area light instead of per-texel hit-or-miss along the wall plane.
vec3 lightFacePoint(vec3 c, float ext, vec3 p, vec3 u) {
	// one of the (up to three) faces turned toward p, chosen by its projected area; a face buried in a wall is then
	// rejected by the shadow ray, so the lit region fades smoothly instead of switching at the 45-degree diagonals
	vec3 d = p - c;
	vec3 a = abs(d) + 1e-4;
	float t = u.z * (a.x + a.y + a.z);
	vec3 f = t < a.x ? vec3(sign(d.x), 0.0, 0.0) : t < a.x + a.y ? vec3(0.0, sign(d.y), 0.0) : vec3(0.0, 0.0, sign(d.z));
	vec3 e1 = abs(f.x) > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
	vec3 e2 = cross(f, e1);
	// full blocks (radius 0.45) emit from their real faces, 0.5 out, and the point sits just outside the face: at
	// 0.9 x 0.45 it was inside the block, so the shadow ray hit the emitter's own face and glowstone, sea lanterns and
	// froglights never lit anything directly
	float h = ext > 0.4 ? 0.5 : ext;
	return c + f * (h + 0.02) + (e1 * (u.x * 2.0 - 1.0) + e2 * (u.y * 2.0 - 1.0)) * 0.95 * h;
}

float lightFalloff(float d, float range) {
	// Softer than inverse square (a block light is a lamp in a room, and the vanilla lightmap fades over 15 blocks):
	// 1/(1.5 + d^2) made a hot spot at the torch and a black room three blocks away. Equal at 1 block, ~2.5x at 6.
	float w = clamp(1.0 - pow(d / max(range, 1.0), 4.0), 0.0, 1.0);
	return w * w * 0.5 / (1.0 + 0.35 * d + 0.12 * d * d);
}

// Block light a traced hit receives: the light list's sources of its cell at the scale blockLights() lights the screen
// with. The vanilla lightmap estimate was far darker than that wherever several lamps light a room (it keeps only the
// brightest source, and its curve fades to nothing at level 7): a mirror floor showed the walls it saw last frame on
// screen lit and everything else black. Right next to an emitter the lightmap's curve, brighter there than one face's
// falloff, stays the floor (the light list alone darkened a lava cave's bounce light by a tenth). At most 16 sources,
// evenly spread over the cell's list and scaled up to its length. The spread starts at a per-hit, per-frame offset: with
// a fixed one each light-grid cell summed its own subset, and a room with more lamps than that showed the cell edges as
// hard steps in the light its reflections and bounces received (patches that came and went with the view).
// hitBlockLightTraced (GI, glossy reflections): shadowed by one ray to one source, drawn by its share of the sum
// (unbiased; noise only where sources differ in colour or visibility, which the history averages). The unshadowed sum,
// gated by the scalar lightmap, let any torch on this side of a wall open the gate for every lamp behind it (a torch-lit
// room took the colour of froglights next door). Perfect mirrors and the water/glass reflections (no history: the one-ray
// estimate sparkled there) test the visibility of the four strongest sources with one ray each to the centre of their
// face toward the hit; every other source takes the result of the nearest tested one (the lamps of the room next door
// share their wall; by direction instead, a lamp behind the wall in line with this room's torch counted as lit).
// Exact with up to four sources, deterministic, four rays per mirror hit.
// rt_direct's mirrors pool those tests over a 2x2 pixel quad (BL_QUAD): a source whose light index modulo 4 is the
// quad slot of a member whose hit lies close by is tested by that member only, so the quad covers a dozen sources and
// each pixel takes a source's visibility from the member that tested it (plus its own strongest source; a pixel alone
// tests the four strongest as before). With four tests per pixel alone the tested set switched from pixel to pixel and
// the untested lamps inherited the wrong answer (a room's torches shadowed with the ledge lamps behind them): black
// jagged strips along the ledges.
bool hitBlockLightTraced = false;
const uint BL_NONE = 0x3fffu;
// tested sources: per word two 14-bit light indices (BL_NONE: none) and their visibility in bits 28-29; bit 30: valid
uint blT[2] = uint[2](0u, 0u);
#ifdef BL_QUAD
int blPhase = 0;   // 0: test and sum, 1: only test (into blT), 2: sum with the quad's tests
int blQuad = -1;   // this pixel's quad slot (-1: no pooling)
uint blNear = 0u;  // bit s: the member in slot s has its hit close to this one
vec4 blSum;        // phase 1's unshadowed sum and nearLv
vec3 blNearCol;
shared uvec2 blqT[64]; // every invocation's blT (8x8 workgroup, slot = x & 1 | (y & 1) << 1)
#else
const int blPhase = 0;
const int blQuad = -1;
const uint blNear = 0u;
#endif

vec3 hitBlockLight(ElHit h) {
	vec3 p = h.position + h.normal * 0.05;
	uvec2 c = el_lightCell(p);
	uint cnt = c.y - c.x;
	// no block light reaches the hit (lightmap 0): the list's light is scaled by 0 below, so no sum and no ray
	if (cnt == 0u || h.light.x <= 0.0) return lightmapBlock(h.light.x);
	uint n = min(cnt, hitBlockLightTraced ? 16u : 32u); // (untraced: a lamp left out of the subset left a black patch)
	float stride = float(cnt) / float(n);
	Rng rng = Rng(rtxHash(floatBitsToUint(p.x) ^ rtxHash(floatBitsToUint(p.y) ^ rtxHash(floatBitsToUint(p.z) + uint(el_frame) * 0x9e3779b9u))));
	// (a fixed subset for the untraced estimate above 32 sources: it has no history to average a changing one)
	float first = cnt > n && hitBlockLightTraced ? rnd(rng) * stride : 0.0;
	float wsum = 0.0, wPick = 0.0;
	vec3 cPick = vec3(0.0), sum = vec3(0.0);
	ElLight pick;
	uint top[4] = uint[4](99u, 99u, 99u, 99u); // list slots of the strongest sources (untraced only), strongest first
	float topW[4] = float[4](0.0, 0.0, 0.0, 0.0);
	float nearLv = -1e9; // the source vanilla would most likely take the level from (level minus distance), for the floor's colour
	vec3 nearCol = vec3(1.0);
	uint best = 99u; // quad: the strongest source of any slot
	float bestW = 0.0;
#ifdef BL_QUAD
	if (blPhase == 2) { sum = blSum.rgb; nearLv = blSum.a; nearCol = blNearCol; } else
#endif
	for (uint i = 0u; i < n; i++) {
		int li = el_lightIndex(c.x + min(uint(float(i) * stride + first), cnt - 1u));
		ElLight L = el_light(li);
		vec3 d = L.position - p;
		float dist = max(length(d), 0.05);
		vec3 ci = L.color * lightFalloff(dist, L.range) * clamp((dot(h.normal, d) / dist + 0.3) / 1.3, 0.0, 1.0);
		sum += ci;
		float w = el_luminance(ci);
		wsum += w;
		if (L.range - dist > nearLv) { nearLv = L.range - dist; nearCol = L.color; }
		if (hitBlockLightTraced) { if (w > 0.0 && rnd(rng) * wsum < w) { pick = L; wPick = w; cPick = ci; } }
		else {
			if (w > bestW) { bestW = w; best = i; }
			if (w > topW[3] && (blQuad < 0 || (li & 3) == blQuad || ((blNear >> (li & 3)) & 1u) == 0u)) {
				uint si = i; float sw = w;
				for (int j = 0; j < 4; j++) if (sw > topW[j]) { uint ti = top[j]; float tw = topW[j]; top[j] = si; topW[j] = sw; si = ti; sw = tw; }
			}
		}
	}
	if (blQuad >= 0 && best != 99u && best != top[0] && best != top[1] && best != top[2]) top[3] = best;
#ifdef BL_QUAD
	blSum = vec4(sum, nearLv); blNearCol = nearCol;
#endif
	vec3 s = sum;
	float k = stride * BLOCKLIGHT_STRENGTH * 5.0 * smoothstep(0.0, 0.2, h.light.x);
	// (no rays where the floor below outshines even the unshadowed sum: right at an emitter)
	if (!hitBlockLightTraced && el_luminance(sum) * k <= el_luminance(lightmapBlock(h.light.x))) s = vec3(0.0); // the floor alone
	else if (!hitBlockLightTraced) {
		// tested positions as halves and visibility as bits: this branch's registers slowed all of rt_direct by 0.1 ms
		uint txy[4] = uint[4](0u, 0u, 0u, 0u), tz[2] = uint[2](0u, 0u), vis = 0u;
		if (blPhase != 2) blT = uint[2](1u << 30, 1u << 30);
		for (int j = 0; j < 4; j++) {
			vec3 tp = vec3(6e4); // (no source: never the nearest)
			uint id = BL_NONE;
			if (blPhase == 2) {
				id = (blT[j >> 1] >> (14 * (j & 1))) & BL_NONE;
				if (id != BL_NONE) tp = el_light(int(id)).position - p;
				vis |= ((blT[j >> 1] >> (28 + (j & 1))) & 1u) << j;
			} else if (top[j] != 99u) {
				id = uint(el_lightIndex(c.x + min(uint(float(top[j]) * stride + first), cnt - 1u)));
				ElLight L = el_light(int(id));
				tp = L.position - p;
				vec3 d = lightFacePoint(L.position, clamp(L.radius, 0.1, 0.5), p, vec3(0.5)) - p;
				float dist = length(d);
				if (dist <= 0.06 || el_traceVisibility(p, d / max(dist, 1e-4), dist - 0.04, MASK_SOLID)) vis |= 1u << j;
			}
			if (blPhase != 2) blT[j >> 1] |= id << (14 * (j & 1)) | ((vis >> j) & 1u) << (28 + (j & 1));
			txy[j] = packHalf2x16(tp.xy);
			tz[j >> 1] |= packHalf2x16(vec2(tp.z, 0.0)) << (16 * (j & 1));
		}
		if (blPhase == 1) return vec3(0.0);
		s = vec3(0.0);
		nearLv = -1e9; // the floor's colour from a visible source only (none: the lightmap's own)
		for (uint i = 0u; i < n; i++) {
			int li = el_lightIndex(c.x + min(uint(float(i) * stride + first), cnt - 1u));
			ElLight L = el_light(li);
			vec3 d = L.position - p;
			float dist = max(length(d), 0.05);
			// tested in the quad (its own tests are in blT too): that answer
			int v = -1;
#ifdef BL_QUAD
			if (blPhase == 2) {
				// its own tests, then those of the member in the source's slot (when close by)
				int sl = li & 3, x = sl ^ blQuad;
				uvec2 o = x != 0 && ((blNear >> sl) & 1u) != 0u ? blqT[gl_LocalInvocationIndex ^ uint((x & 1) | (x >> 1) * 8)] : uvec2(0u);
				for (int q = 0; q < 4 && v < 0; q++) {
					uint t = q == 0 ? blT[0] : q == 1 ? blT[1] : q == 2 ? o.x : o.y;
					if ((t >> 30) == 0u || li >= int(BL_NONE)) continue; // (BL_NONE: the 16384th source is never pooled)
					if ((t & BL_NONE) == uint(li)) v = int((t >> 28) & 1u);
					else if (((t >> 14) & BL_NONE) == uint(li)) v = int((t >> 29) & 1u);
				}
			}
#endif
			if (v < 0) {
				int best = 0;
				float bd = 1e30;
				for (int j = 0; j < 4; j++) {
					vec3 e = vec3(unpackHalf2x16(txy[j]), unpackHalf2x16(tz[j >> 1] >> (16 * (j & 1))).x) - d;
					float dd = dot(e, e);
					if (dd < bd) { bd = dd; best = j; }
				}
				v = int((vis >> best) & 1u);
			}
			if (v == 0) continue;
			s += L.color * lightFalloff(dist, L.range) * clamp((dot(h.normal, d) / dist + 0.3) / 1.3, 0.0, 1.0);
			if (L.range - dist > nearLv) { nearLv = L.range - dist; nearCol = L.color; }
		}
	}
	// The lightmap floor tops up the expected sum (unshadowed for the one-ray estimate), not each sample: max() per
	// sample turned every occluded or dim pick into the floor, brighter on average than the light that arrives. It takes
	// the colour of the nearest source: the torch-orange floor won the red channel next to froglights and sea lanterns.
	vec3 lm = lightmapBlock(h.light.x);
	if (nearLv > -1e8) lm = nearCol * (el_luminance(lm) / max(el_luminance(nearCol), 1e-4));
	if (hitBlockLightTraced) {
		s = vec3(0.0);
		// no ray where the floor covers even the unshadowed sum (right at an emitter): the result is the floor either way
		if (wPick > 0.0 && any(greaterThan(sum * k, lm))) {
			vec3 d = lightFacePoint(pick.position, clamp(pick.radius, 0.1, 0.5), p, vec3(rnd(rng), rnd(rng), rnd(rng))) - p;
			float dist = length(d);
			if (dist <= 0.06 || el_traceVisibility(p, d / dist, dist - 0.04, MASK_SOLID)) s = cPick * (wsum / wPick);
		} else s = sum;
	}
	return s * k + max(lm - (hitBlockLightTraced ? sum : s) * k, 0.0);
}

// Outgoing radiance of a hit towards the ray origin. sunRay: trace the hit's sun visibility (else sky light estimates it).
// emissive: add the hit's own glow (GI_SKIP_LISTED_EMITTERS: not for the sources blockLights() already samples directly)
// Light a hit receives besides the sun and its own glow, estimated from its lightmap (sky and block light).
float hitAo(ElHit h) { return mix(1.0, h.color.a, 0.6); }
// without the block light (rt_direct adds that once its quad has pooled the visibility tests)
vec3 hitAmbientSky(ElHit h) {
	vec3 amb = skyAmbient(h.normal) * h.light.y * h.light.y * hitAo(h);
#if !defined(EL_DIMENSION_DEFAULT)
	amb += skyAmbient(h.normal) * 0.35;
#endif
	return amb;
}
vec3 hitAmbient(ElHit h) { return hitAmbientSky(h) + hitBlockLight(h) * hitAo(h); }

// albOut: the colour the ambient light is multiplied by
vec3 shadeHitAmbA(ElHit h, vec3 dir, bool sunRay, bool emissive, vec3 amb, out vec3 albOut) {
	vec4 a = el_hitAlbedo(h);
	vec3 srgb = a.rgb * h.color.rgb;
	vec3 alb = el_srgbToLinear(srgb);
	vec3 n = h.normal;
	vec3 l = lightDir();
	float nl = max(dot(n, l), 0.0);
	vec3 direct = vec3(0.0);
	if (nl > 0.0) {
		vec3 vis;
#ifdef HIT_SUN_DISC
		// a random point of the sun's disc, as rt_direct.comp.glsl's shadows (with its centre only, bounce light through a
		// narrow opening cut off harder than the direct light); reflections keep the centre, mirrors have no history.
		// One point per frame for every hit, not per hit: parallel rays stay coherent in the BVH (a per-hit point cost rt_gi
		// 0.1-0.25 ms), and the GI history averages the disc over frames all the same. Not blueNoise2: IGN with another salt
		// stays tied to the bounce direction's sample
		Rng sr = Rng(rtxHash(uint(el_frame) * 0x9e3779b9u + 0x632be5abu));
		vec3 sd = coneSample(l, cos(0.0085 * SHADOW_SOFTNESS * (1.0 + 0.5 * RTX_INTENSITY)), rnd2(sr));
#else
		vec3 sd = l;
#endif
		if (sunRay) vis = sunTransmission(h.position + n * 0.01, sd, false);
		else vis = vec3(smoothstep(0.85, 0.97, h.light.y));
		direct = lightColor() * nl * vis;
	}
	Mat hm = hitMaterial(h, srgb);
	vec3 e = emissive ? emissiveColor(alb) * hm.emission * EMISSIVE_STRENGTH * HIT_EMISSION_SCALE * rtxBoost() : vec3(0.0); // (hitEmission)
#ifdef GI_SKIP_LISTED_EMITTERS
	if (e != vec3(0.0) && emitterListed(h)) e = vec3(0.0); // (only emitters pay for the lookup)
#endif
	// metals reflect their f0 colour, not the texture's: copper's texels (R/G 3.5) turned every bounce off it red
	if (isMetal(hm.f0)) alb = matF0(hm, alb);
	albOut = alb;
	return alb * (direct + amb) + e;
}
vec3 shadeHitAmb(ElHit h, vec3 dir, bool sunRay, bool emissive, vec3 amb) { vec3 a; return shadeHitAmbA(h, dir, sunRay, emissive, amb, a); }

vec3 shadeHit(ElHit h, vec3 dir, bool sunRay, bool emissive) { return shadeHitAmb(h, dir, sunRay, emissive, hitAmbient(h)); }

// The GI filtered last frame where a hit was on screen (giFilt, not yet written this frame): the light it receives,
// with all its bounces and the glow of neon around it, instead of the lightmap estimate. False unless last frame saw
// this very hit at that pixel (view distance in shadowAcc) or when it was off screen.
bool cachedGi(vec3 pos, out vec3 gi) {
	gi = vec3(0.0);
	// behind last frame's camera the projection mirrors onto the screen (and the distance test can match by chance)
	if ((el_prevView * vec4(pos + el_cameraPosition - el_prevCameraPosition, 1.0)).z >= 0.0) return false;
	vec2 cuv = el_worldToPrevScreen(pos);
	if (any(lessThanEqual(cuv, vec2(0.0))) || any(greaterThanEqual(cuv, vec2(1.0)))) return false;
	ivec2 cq = ivec2(cuv * el_screenSize);
	float cexp = length(pos + el_cameraPosition - el_prevCameraPosition);
	// the a-trous output (last frame's, giFilt), not the temporal accumulation: one GI path per pixel left giAcc grainy,
	// and every bounce that read it carried the grain on, which the filter then froze into blotches (a room lit through
	// a small hole). Ray Reconstruction skips the a-trous passes, so giFilt is stale then.
#ifdef EL_RR_ACTIVE
	vec4 cg = texelFetch(giAcc, cq, 0);
#else
	vec4 cg = texelFetch(giFilt, cq, 0);
#endif
	gi = cg.rgb;
	return cg.a > 0.0 && abs(texelFetch(shadowAcc, cq, 0).a - cexp) < 0.02 * cexp + 0.08;
}

// sky as seen by a ray that left the scene; skyLight (0-1) hides it in places the sky never reaches
vec3 missRadiance(vec3 dir, float skyLight) {
	return skyBase(dir) * mix(0.0, 1.0, smoothstep(0.05, 0.6, skyLight));
}

#endif
