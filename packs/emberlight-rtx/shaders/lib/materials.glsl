// Material model. A material is LabPBR-shaped (smoothness, f0/metal id, emission) so a resource pack's _s map and the
// built-in table (block ids from emberlight.json "materials") end up in the same gMat encoding.
#ifndef RTX_MATERIALS
#define RTX_MATERIALS 1

const int KIND_DEFAULT = 0;
const int KIND_FOLIAGE = 10; // thin plants without a LabPBR SSS value (built-in subsurface amount FOLIAGE_SSS)
// LabPBR subsurface scattering (the _s blue channel, 65-255) rides in the kind byte: 64-127 thin plants (light passes
// through), 128-255 everything else (snow, slime, honey...: wrap lighting only)
const int KIND_SSS_THIN = 64, KIND_SSS = 128;
const float FOLIAGE_SSS = 0.75;

int sssKind(float sss, bool thin) {
	if (sss <= 0.0) return thin ? KIND_FOLIAGE : KIND_DEFAULT;
	return thin ? KIND_SSS_THIN + int(round(clamp(sss, 0.0, 1.0) * 63.0)) : KIND_SSS + int(round(clamp(sss, 0.0, 1.0) * 127.0));
}
bool matThin(int kind) { return kind == KIND_FOLIAGE || (kind >= KIND_SSS_THIN && kind < KIND_SSS); }
float matSss(int kind) {
	return kind == KIND_FOLIAGE ? FOLIAGE_SSS : kind >= KIND_SSS ? float(kind - KIND_SSS) / 127.0
		: kind >= KIND_SSS_THIN ? float(kind - KIND_SSS_THIN) / 63.0 : 0.0;
}

const int ID_WATER = 1, ID_GLASS = 2, ID_ICE = 3, ID_SLIME = 4, ID_PORTAL = 5, ID_PANE = 7, ID_HONEY = 8;
bool isGlassId(int id) { return id == ID_GLASS || id == ID_PANE; }

struct Mat { float smoothness; float f0; float emission; int kind; }; // f0 in LabPBR units: 0-229 dielectric, 230+ metal

// table entry by block id; albedo (sRGB) decides which texels of a partial emitter (torch, lantern) glow
Mat tableMaterial(int id, vec3 albedo) {
	Mat m = Mat(0.0, 10.0, 0.0, KIND_DEFAULT);
	if (id < 0) return m;
	if (id == 10) m.kind = KIND_FOLIAGE;
	else if (id == 20) m.smoothness = 0.62;
	else if (id == 21) m.smoothness = 0.80;
	else if (id == 22) m.smoothness = 0.70;
	else if (id == 23) m.smoothness = 0.88;
	else if (id == 24) m.smoothness = 0.90;
	else if (id == 25) { m.smoothness = 0.85; m.f0 = 20.0; }
	else if (id == 26) m.smoothness = 0.2;
	else if (id == 40) { m.smoothness = 0.78; m.f0 = 230.0; }
	else if (id == 41) { m.smoothness = 0.90; m.f0 = 231.0; }
	else if (id == 42) { m.smoothness = 0.75; m.f0 = 234.0; }
	else if (id == 43) { m.smoothness = 0.80; m.f0 = 255.0; }
	else if (id == ID_ICE) { m.smoothness = 0.95; m.kind = sssKind(0.5, false); } // packed and blue ice (opaque)
	else if (id == 11) { m.smoothness = 0.3; m.kind = sssKind(0.7, false); } // snow
	else if (id == 44) { m.smoothness = 0.55; m.f0 = 230.0; } // chainmail
	else if (id == 45) { m.smoothness = 0.62; m.f0 = 12.0; } // wet skin, shells, slime
	else if (id == 50) m.smoothness = 0.32; // varnished wood: armour stands, item frames, boats, chests
	else if (id >= 60 && id <= 67) {
		float bright = max(albedo.r, max(albedo.g, albedo.b));
		float mask = (id == 62 || id == 63 || id == 64) ? 0.35 + 0.65 * smoothstep(0.2, 0.8, bright) : smoothstep(0.55, 0.85, bright);
		float k = id == 64 ? 1.0 : id == 66 || id == 67 ? 0.45 : id == 65 ? 0.7 : 0.8;
		m.emission = mask * k;
		if (id == 63) m.smoothness = 0.6;
	}
	return m;
}

// linear f0 colour from the LabPBR value (metal ids 230-237: the hardcoded metals of the LabPBR 1.3 table; 255: albedo).
// A hardcoded metal takes the texture as a tint (geometric mean of the table value and the albedo): a gold block keeps
// the light and dark of its texels and reads as gold, not as a uniformly tinted mirror that looks like dark glass.
vec3 f0Color(float f0, vec3 albedoLin) {
	int i = int(f0 + 0.5);
	if (i < 230) return vec3(max(f0 / 255.0, 0.02));
	if (i > 237) return max(albedoLin, vec3(0.04)); // a black mirror still reflects like glossy black paint
	vec3 base = i == 230 ? vec3(0.56, 0.57, 0.58) : i == 231 ? vec3(1.00, 0.78, 0.34) : i == 232 ? vec3(0.91, 0.92, 0.92)
		: i == 233 ? vec3(0.55, 0.56, 0.55) : i == 234 ? vec3(0.95, 0.64, 0.54) : i == 235 ? vec3(0.63, 0.63, 0.63)
		: i == 236 ? vec3(0.68, 0.64, 0.58) : vec3(0.95, 0.93, 0.88);
	return min(sqrt(base * albedoLin) * 1.15, vec3(1.0)); // geometric mean: gold stays gold, its texels stay visible
}
bool isMetal(float f0) { return f0 >= 229.5; }

// f0 of a material. Perfect albedo-as-F0 mirrors (smoothness 1, id 255: Emberlight PBR's concrete) are bright mirrors
// tinted by the albedo's hue: as dark as the paint, a black concrete road showed no reflection at all and a red wall
// was a dim one; now black is a dark-glass mirror and red a red one.
// A perfect silver mirror (id 237, smoothness 1: Emberlight PBR's iron block) is a neutral bathroom mirror: no colour
// cast and none of the block's texture in the reflection.
vec3 matF0(Mat m, vec3 albedoLin) {
	if (m.smoothness >= 0.99 && abs(m.f0 - 237.0) < 0.5) return vec3(0.95);
	if (m.smoothness < 0.99 || m.f0 < 237.5) return f0Color(m.f0, albedoLin);
	float mx = max(albedoLin.r, max(albedoLin.g, albedoLin.b));
	return mix(vec3(1.0), albedoLin / max(mx, 1e-4), 0.8) * (0.35 + 0.6 * sqrt(mx));
}

// colour of a glowing surface: the albedo with its saturation raised, so neon reads as its colour instead of washing
// out to white under bloom and the tonemapper. Dark albedos (terracotta: Emberlight PBR's neon) glow at a lamp's
// brightness and more saturated: a muddy red block is a bright red tube. Bright lamps (max channel >= 0.8) as before.
vec3 emissiveColor(vec3 albedoLin) {
	float mx = max(max(albedoLin.r, max(albedoLin.g, albedoLin.b)), 1e-4);
	float lamp = smoothstep(0.3, 0.8, mx);
	return pow(albedoLin / mx, vec3(mix(3.0, 2.0, lamp))) * max(mx, 0.8);
}

float roughnessOf(float smoothness) { float r = 1.0 - smoothness; return r * r; }

vec4 encodeMat(Mat m) { return vec4(m.smoothness, m.f0 / 255.0, m.emission, float(m.kind) / 255.0); }
Mat decodeMat(vec4 e) { return Mat(e.r, e.g * 255.0, e.b, int(e.a * 255.0 + 0.5)); }

// roughest surface that still traces a reflection ray
#if REFLECTIONS == REFLECTIONS_LOW
const float REFL_MAX_ROUGH = 0.12;
#elif REFLECTIONS == REFLECTIONS_HIGH
const float REFL_MAX_ROUGH = 0.35; // GGX alpha 0.35: wider lobes cost more than they show (the ambient estimate takes over)
#elif REFLECTIONS == REFLECTIONS_INSANE
const float REFL_MAX_ROUGH = 0.85;
#else
const float REFL_MAX_ROUGH = -1.0;
#endif

// gNormal (RGBA16): xy shading normal (octahedral), z block + sky light (8 bits each), w geometric normal (2x8 bit octahedral)
float pack88(vec2 v) { vec2 q = floor(clamp(v, 0.0, 1.0) * 255.0 + 0.5); return (q.x * 256.0 + q.y) / 65535.0; }
vec2 unpack88(float p) { float v = floor(p * 65535.0 + 0.5); return vec2(floor(v / 256.0), mod(v, 256.0)) / 255.0; }
vec4 encodeGNormal(vec3 n, vec3 geo, vec2 light) { return vec4(el_encodeNormal(n), pack88(light), pack88(el_encodeNormal(geo))); }
struct GNormal { vec3 n; vec3 geo; vec2 light; };
GNormal decodeGNormal(vec4 g) { return GNormal(el_decodeNormal(g.xy), el_decodeNormal(unpack88(g.w)), unpack88(g.z)); }

// normal for specular rays: mostly the geometric normal. Every texel of a LabPBR normal map tilts the reflection its own
// way, and on a polished floor or an iron wall at 35 % that cut the reflected image into a texel mosaic; 12 % keeps a
// hint of relief in the reflection and the sun highlight.
vec3 specularNormal(vec3 n, vec3 geo) { return normalize(mix(geo, n, 0.12)); }

#endif
