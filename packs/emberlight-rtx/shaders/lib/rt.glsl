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
vec3 sunTransmissionC(vec3 p, vec3 dir, bool withPlayer, out float caustic) {
	caustic = 1.0;
	if (!el_traceVisibility(p, dir, 512.0, withPlayer ? MASK_SOLID | EL_MASK_PLAYER : MASK_SOLID)) return vec3(0.0);
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
vec3 sunTransmission(vec3 p, vec3 dir, bool withPlayer) { float c; return sunTransmissionC(p, dir, withPlayer, c); }

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

float lightFalloff(float d, float range) {
	// Softer than inverse square (a block light is a lamp in a room, and the vanilla lightmap fades over 15 blocks):
	// 1/(1.5 + d^2) made a hot spot at the torch and a black room three blocks away. Equal at 1 block, ~2.5x at 6.
	float w = clamp(1.0 - pow(d / max(range, 1.0), 4.0), 0.0, 1.0);
	return w * w * 0.5 / (1.0 + 0.35 * d + 0.12 * d * d);
}

// Block light a traced hit receives: the light list's sources of its cell, unshadowed (no ray per hit), at the scale
// blockLights() lights the screen with. The vanilla lightmap estimate was far darker than that wherever several lamps
// light a room (it keeps only the brightest source, and its curve fades to nothing at level 7): a mirror floor showed
// the walls it saw last frame on screen lit and everything else black. The lightmap still gates it (no block light
// behind a wall), and right next to an emitter its curve, brighter there than one face's falloff, stays the floor (the
// light list alone darkened a lava cave's bounce light by a tenth). At most 16 sources, evenly spread over the cell's
// list and scaled up to its length.
vec3 hitBlockLight(ElHit h) {
	vec3 p = h.position + h.normal * 0.05;
	uvec2 c = el_lightCell(p);
	uint cnt = c.y - c.x;
	if (cnt == 0u) return lightmapBlock(h.light.x);
	uint n = min(cnt, 16u);
	float stride = float(cnt) / float(n);
	vec3 s = vec3(0.0);
	for (uint i = 0u; i < n; i++) {
		ElLight L = el_light(el_lightIndex(c.x + uint(float(i) * stride)));
		vec3 d = L.position - p;
		float dist = max(length(d), 0.05);
		s += L.color * lightFalloff(dist, L.range) * clamp((dot(h.normal, d) / dist + 0.3) / 1.3, 0.0, 1.0);
	}
	return max(s * stride * BLOCKLIGHT_STRENGTH * 5.0 * smoothstep(0.0, 0.2, h.light.x), lightmapBlock(h.light.x));
}

// Outgoing radiance of a hit towards the ray origin. sunRay: trace the hit's sun visibility (else sky light estimates it).
// emissive: add the hit's own glow (GI_SKIP_LISTED_EMITTERS: not for the sources blockLights() already samples directly)
// Light a hit receives besides the sun and its own glow, estimated from its lightmap (sky and block light).
vec3 hitAmbient(ElHit h) {
	vec3 n = h.normal;
	float ao = mix(1.0, h.color.a, 0.6);
	vec3 amb = skyAmbient(n) * h.light.y * h.light.y * ao + hitBlockLight(h) * ao;
#if !defined(EL_DIMENSION_DEFAULT)
	amb += skyAmbient(n) * 0.35;
#endif
	return amb;
}

vec3 shadeHitAmb(ElHit h, vec3 dir, bool sunRay, bool emissive, vec3 amb) {
	vec4 a = el_hitAlbedo(h);
	vec3 srgb = a.rgb * h.color.rgb;
	vec3 alb = el_srgbToLinear(srgb);
	vec3 n = h.normal;
	vec3 l = lightDir();
	float nl = max(dot(n, l), 0.0);
	vec3 direct = vec3(0.0);
	if (nl > 0.0) {
		vec3 vis;
		if (sunRay) vis = sunTransmission(h.position + n * 0.01, l, false);
		else vis = vec3(smoothstep(0.85, 0.97, h.light.y));
		direct = lightColor() * nl * vis;
	}
	vec3 e = emissive ? hitEmission(h, srgb, alb) : vec3(0.0);
#ifdef GI_SKIP_LISTED_EMITTERS
	if (e != vec3(0.0) && emitterListed(h)) e = vec3(0.0); // (only emitters pay for the lookup)
#endif
	return alb * (direct + amb) + e;
}

vec3 shadeHit(ElHit h, vec3 dir, bool sunRay, bool emissive) { return shadeHitAmb(h, dir, sunRay, emissive, hitAmbient(h)); }

// The GI accumulated last frame where a hit was on screen (giAcc, not yet written this frame): the light it receives,
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
	vec4 cg = texelFetch(giAcc, cq, 0);
	gi = cg.rgb;
	return cg.a > 0.0 && abs(texelFetch(shadowAcc, cq, 0).a - cexp) < 0.02 * cexp + 0.08;
}

// sky as seen by a ray that left the scene; skyLight (0-1) hides it in places the sky never reaches
vec3 missRadiance(vec3 dir, float skyLight) {
	return skyBase(dir) * mix(0.0, 1.0, smoothstep(0.05, 0.6, skyLight));
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

#endif
