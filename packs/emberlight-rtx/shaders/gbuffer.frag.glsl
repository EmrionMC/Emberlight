#include "/lib/common.glsl"
#include "/lib/materials.glsl"

in vec2 uv;
in vec2 light;
in vec4 tint;
in vec3 normal;
in vec3 world;
flat in int blockId;
flat in float emission;
flat in vec2 midUV;

layout(location = EL_OUT_gAlbedo) out vec4 outAlbedo;
layout(location = EL_OUT_gNormal) out vec4 outNormal;
layout(location = EL_OUT_gMat) out vec4 outMat;

#if POM != POM_OFF && defined(EL_CLASS_TERRAIN_SOLID) && PBR_SOURCE == PBR_SOURCE_AUTO
#define RTX_POM 1
#if POM == POM_LOW
const int POM_STEPS = 16; const float POM_DIST = 16.0;
#elif POM == POM_HIGH
const int POM_STEPS = 64; const float POM_DIST = 32.0;
#else
const int POM_STEPS = 128; const float POM_DIST = 48.0;
#endif
const float POM_DEPTH = 0.25; // LabPBR: height 0 lies a quarter block below the face

// Parallax occlusion mapping from the LabPBR height (_n alpha): march the view ray down through the height field in
// sprite uv, wrapping inside the sprite so it continues into the neighbouring block's copy of the texture. The surface
// gradients of u and v come from the screen derivatives (no vertex tangent needed); one block face is one sprite in
// vanilla models (16 texels per block, also for slabs and HD textures), which gives the sprite's extent, and model uvs
// follow the block coordinates, which places it: the sprite is the uv range over this block. (Not around midUV: that is
// the polygon's uv centre, which on a slab side or a stair step lies off the sprite centre, so the wrap reached into
// the neighbouring sprite of the atlas.)
// The depth buffer keeps the flat face (writing gl_FragDepth would cost early-Z for all terrain): shading, normals and
// materials follow the displaced texel, ray-traced shadows start from the face.
vec2 parallaxUV(vec3 n, vec2 dx, vec2 dy, vec3 dp1, vec3 dp2) {
	float dist = length(world);
	float fade = 1.0 - smoothstep(0.6 * POM_DIST, POM_DIST, dist);
	if (fade <= 0.0 || midUV == vec2(0.0)) return uv;
	vec3 c2 = cross(dp2, n), c1 = cross(n, dp1);
	float det = dot(dp1, c2);
	if (abs(det) < 1e-14) return uv;
	vec3 gu = (c2 * dx.x + c1 * dy.x) / det, gv = (c2 * dx.y + c1 * dy.y) / det; // d(uv)/d(world) along the face
	vec2 size = vec2(length(gu), length(gv));
	vec3 f = fract(world + el_cameraPosition - n * 0.001); // position in the block (behind the face)
	vec2 c0 = uv - vec2(dot(gu, f), dot(gv, f)); // uv at the block's (0, 0, 0) corner and across at (1, 1, 1)
	vec2 lo = min(c0, c0 + vec2(gu.x + gu.y + gu.z, gv.x + gv.y + gv.z));
	vec3 V = -world / dist;
	float vn = max(dot(V, n), 0.12); // grazing: capped, the offset would run across whole blocks
	vec3 off = -(V - n * dot(V, n)) / vn * POM_DEPTH * fade; // lateral move of the view ray at full depth
	vec2 duv = vec2(dot(gu, off), dot(gv, off));
	int steps = max(int(float(POM_STEPS) * mix(1.0, 0.35, vn) * mix(0.4, 1.0, fade)), 6);
	float stepH = 1.0 / float(steps);
	float layer = 1.0, h = el_normalMapGrad(uv, dx, dy).a;
	vec2 cur = uv;
	for (int i = 0; i < POM_STEPS && i < steps && h < layer; i++) {
		layer -= stepH;
		cur = uv + duv * (1.0 - layer);
		h = el_normalMapGrad(lo + mod(cur - lo, size), dx, dy).a;
	}
	// the height map is pixel art (piecewise constant): the crossing is usually a texel's vertical wall, which
	// interpolating heights misses (stair-stepped edges up close); bisect between the last two layers instead
	if (h >= layer && layer < 1.0) {
		float lo0 = layer, hi0 = layer + stepH; // ray depth range: hit at lo0, above the surface at hi0
		for (int i = 0; i < 6; i++) {
			float mid = 0.5 * (lo0 + hi0);
			vec2 m = uv + duv * (1.0 - mid);
			if (el_normalMapGrad(lo + mod(m - lo, size), dx, dy).a >= mid) lo0 = mid; else hi0 = mid;
		}
		cur = uv + duv * (1.0 - lo0);
	}
	return lo + mod(cur - lo, size);
}
#endif

void main() {
	vec2 dx = dFdx(uv), dy = dFdy(uv);
	vec3 dp1 = dFdx(world), dp2 = dFdy(world);
	vec2 tuv = uv; // texture uv (moved by parallax mapping)
#ifdef RTX_POM
	{
		vec3 n0 = normalize(normal);
		if (!gl_FrontFacing) n0 = -n0;
		if (blockId != 10 && el_specularMap(uv) != vec4(0.0)) tuv = parallaxUV(n0, dx, dy, dp1, dp2);
	}
#endif
	vec4 albedo = el_albedoGrad(tuv, dx, dy) * vec4(tint.rgb, 1.0);
	if (albedo.a < el_alphaCutoff()) discard;
#if defined(EL_CLASS_ENTITIES) || defined(EL_CLASS_BLOCK_ENTITIES)
	albedo.rgb = mix(albedo.rgb, el_entityColor().rgb, el_entityColor().a); // hurt flash
#endif
	vec3 n = normalize(normal);
	if (!gl_FrontFacing) n = -n;
	vec3 geo = n;
	float pbrAo = 1.0;

	vec4 spec = el_specularMapGrad(tuv, dx, dy);
	Mat m;
#if PBR_SOURCE == PBR_SOURCE_AUTO
	if (spec != vec4(0.0)) { // a LabPBR resource pack is loaded
		ElMaterial e = el_material(spec);
		// metals are capped at 0.85 (a pure metal mirror reads as dark glass), except perfect mirrors (smoothness 1)
		m = Mat(isMetal(spec.g * 255.0) && spec.r < 0.99 ? min(spec.r, 0.85) : spec.r, spec.g * 255.0, e.emission, sssKind(e.sss, blockId == 10));
		// LabPBR normals are DirectX-style (+y = down in the texture = +v in the atlas), so they map onto a tangent frame
		// along +u / +v directly: the cotangent frame from screen-space derivatives (Schüler 2013), no vertex tangent needed
		vec4 nm = el_normalMapGrad(tuv, dx, dy);
		// 60 % strength, fading out where a texel is smaller than about a pixel (the atlas has no usable mips for normals)
		float footprint = length(fwidth(world));
		float strength = 0.6 * (1.0 - smoothstep(1.0 / 24.0, 1.0 / 6.0, footprint));
		vec2 xy = (nm.xy * 2.0 - 1.0) * strength;
		pbrAo = mix(1.0, nm.b, 0.5 * strength / 0.6);
		vec3 tn = vec3(xy, sqrt(max(1.0 - dot(xy, xy), 0.0)));
		vec2 du1 = dx, du2 = dy;
		vec3 c2 = cross(dp2, n), c1 = cross(n, dp1);
		vec3 t = c2 * du1.x + c1 * du2.x, b = c2 * du1.y + c1 * du2.y;
		// u and v are normalised separately: the atlas is not square, so their world-space rates differ
		if (dot(t, t) > 1e-20 && dot(b, b) > 1e-20) n = normalize(mat3(normalize(t), normalize(b), n) * tn);
	} else
#endif
	m = tableMaterial(blockId, albedo.rgb);
#if defined(EL_CLASS_ENTITIES) || defined(EL_CLASS_BLOCK_ENTITIES)
	// tools and items: the wooden handle of an iron, diamond or netherite sword is not part of the blade (brown and
	// saturated; gold and copper keep it: their own dark texels are brown too)
	float mx = max(albedo.r, max(albedo.g, albedo.b));
	bool blade = blockId == 40 || blockId == 43 || blockId == 25;
	if (blade && albedo.r > albedo.g && albedo.g > albedo.b && (mx - albedo.b) / max(mx, 1e-3) > 0.3 && mx < 0.55)
		m = Mat(0.3, 10.0, 0.0, KIND_DEFAULT);
#endif
	// blocks that emit light but are in no table entry (modded, new blocks): their bright texels glow. Only unmapped
	// blocks: el_vertexEmission() was seen non-zero on deepslate bricks next to light sources (2026-11-17, unexplained)
	if (emission > 0.0 && m.emission == 0.0 && blockId < 0)
		m.emission = emission * smoothstep(0.5, 0.85, max(albedo.r, max(albedo.g, albedo.b))) * 0.8;
#if defined(EL_CLASS_EMISSIVE_EYES) || defined(EL_CLASS_BEACON) || defined(EL_CLASS_LIGHTNING) || defined(EL_CLASS_ENTITIES_GLOWING)
	m.emission = 1.0;
#endif

#if defined(PUDDLES) && defined(EL_DIMENSION_DEFAULT)
	// rain: sky-exposed surfaces get wet, flat ground collects puddles that act as mirrors
	// el_wetness lingers for minutes after rain (and after a weather change): only while it actually rains, drying with it
	float wet = min(el_wetness, rainSmooth * 2.0) * smoothstep(0.88, 0.97, light.y);
	if (wet > 0.0) {
		vec2 p = (world.xz + el_cameraPosition.xz) * 0.35;
		float puddle = smoothstep(0.45, 0.62, fbm(p)) * smoothstep(0.9, 0.99, n.y);
		m.smoothness = max(m.smoothness, mix(m.smoothness + 0.35 * (1.0 - m.smoothness), 0.97, puddle) * wet + m.smoothness * (1.0 - wet));
		albedo.rgb *= 1.0 - wet * (0.25 + 0.2 * puddle);
		if (puddle * wet > 0.3) n = normalize(mix(n, geo, puddle * wet));
	}
#endif

	outAlbedo = vec4(albedo.rgb, tint.a * pbrAo); // separateAo: vertex AO in alpha (times the LabPBR AO)
	outNormal = encodeGNormal(n, geo, light);
	outMat = encodeMat(m);
}
