// RT common library, inserted once per patched stage before its first declaration. No pack symbols.
// Bindings are assigned like any other set-1 resource (emrRtTlas -> emrion_tlas, EmrRtSections -> emrion_rt_sections).

layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer EmrRtVertData { uint d[]; };
layout(std430) readonly buffer EmrRtSections { uvec4 emrRtSection[]; };
uniform accelerationStructureEXT emrRtTlas;
uniform sampler2D emrRtAtlas;
uniform sampler2D emrRtEntityAtlas;

// > 0: the sun rays RTX shadows take while refraction lights a traced hit (refraction_water_out.glsl), 0 = their own count.
int emrRtHitRays = 0;

// Section table entry: xy vertex address, z layer/kind (below), w vertex format: 0 = EMRION_BLOCK / EMRION_ENTITY (56 B =
// 14 words: position (section-relative) 0-2, Color 3, UV0 4-5, UV2 6, Normal 7, mc_Entity 8, mc_midTexCoord 9-10; the
// entity format has UV0 at 4-5 too and its Normal at 8), 1 = EmrionChunkVertex on the Sodium path (40 B = 10 words: 3x20-bit
// position 0-1, Color 2, UV0 3 (2x15 bit), light 4 (block, sky bytes), mc_Entity 5, Normal 6, mc_midTexCoord 8 (2x16 unorm)).
// Read vertices only through the accessors below. Instances are pure translations, so object-space directions are world directions.
struct EmrRtVerts { EmrRtVertData p; bool sod; };

EmrRtVerts emrRtVerts(int customIndex) {
	uvec4 s = emrRtSection[customIndex];
	return EmrRtVerts(EmrRtVertData(s.xy), s.w == 1u);
}

// Vertices of triangle prim: quad prim/2, triangles (0,1,2) and (2,3,0) like RtQuadIndices.
uvec3 emrRtTri(uint prim) {
	return (prim >> 1u) * 4u + ((prim & 1u) == 0u ? uvec3(0u, 1u, 2u) : uvec3(2u, 3u, 0u));
}

uint emrRtWord(EmrRtVerts v, uint i, uint vanilla, uint sodium) {
	return v.sod ? v.p.d[i * 10u + sodium] : v.p.d[i * 14u + vanilla];
}

vec3 emrRtPos(EmrRtVerts v, uint i) {
	if (v.sod) { // Sodium's compact position (the terrain_sodium vertex decode without the region offset)
		uvec3 hi = (uvec3(v.p.d[i * 10u]) >> uvec3(0u, 10u, 20u)) & 0x3FFu, lo = (uvec3(v.p.d[i * 10u + 1u]) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
		return vec3((hi << 10u) | lo) * (32.0 / 1048576.0) - 8.0;
	}
	return uintBitsToFloat(uvec3(v.p.d[i * 14u], v.p.d[i * 14u + 1u], v.p.d[i * 14u + 2u]));
}

vec2 emrRtUv(EmrRtVerts v, uint i) {
	if (v.sod) { uint t = v.p.d[i * 10u + 3u]; return vec2(uvec2(t, t >> 16u) & 0x7FFFu) / 32768.0; } // centroid bias bit ignored
	return uintBitsToFloat(uvec2(v.p.d[i * 14u + 4u], v.p.d[i * 14u + 5u]));
}

int emrRtId(EmrRtVerts v, uint i) {
	return int(emrRtWord(v, i, 8u, 5u) << 16u) >> 16;
}

// Vertex colour (RGBA8: tint, AO as the pack's separateAo asks).
vec4 emrRtColor(EmrRtVerts v, uint i) {
	return unpackUnorm4x8(emrRtWord(v, i, 3u, 2u));
}

// UV2 (block, sky) in 0-240, as MC's lightmap coordinates.
uvec2 emrRtLight(EmrRtVerts v, uint i) {
	uint l = emrRtWord(v, i, 6u, 4u);
	return v.sod ? uvec2(l & 0xFFu, (l >> 8u) & 0xFFu) : uvec2(l & 0xFFFFu, l >> 16u);
}

vec3 emrRtNormal(EmrRtVerts v, uint i) {
	return unpackSnorm4x8(emrRtWord(v, i, 7u, 6u)).xyz;
}

vec2 emrRtMidUv(EmrRtVerts v, uint i) {
	if (v.sod) { uint m = v.p.d[i * 10u + 8u]; return vec2(m & 0xFFFFu, m >> 16u) / 65535.0; }
	return uintBitsToFloat(uvec2(v.p.d[i * 14u + 9u], v.p.d[i * 14u + 10u]));
}

vec2 emrRtBaryUv(EmrRtVerts v, uvec3 tri, vec2 b) {
	return emrRtUv(v, tri.x) * (1.0 - b.x - b.y) + emrRtUv(v, tri.y) * b.x + emrRtUv(v, tri.z) * b.y;
}

// Instance masks: 0x01 solid + cutout terrain, 0x02 translucent terrain, 0x04 entities (RtEntities, only when an effect
// requested <effect>:entities), 0x08 the local player and what it carries in first person (not drawn: shadow rays include
// it, view-ray substitutes such as the GI primary and refraction leave it out). Section table entry z: terrain = layer (0-2); entities = 8 (UV0 already remapped into
// emrRtEntityAtlas) or 9 (UV0 in the block atlas).
bool emrRtIsEntity(int customIndex) {
	return emrRtSection[customIndex].z >= 8u;
}

// Alpha of a candidate hit at mip 0: terrain and block-atlas entity draws from the block atlas, other entities from the
// entity atlas.
float emrRtHitAlpha(int customIndex, EmrRtVerts v, uvec3 tri, vec2 b) {
	vec2 uv = emrRtBaryUv(v, tri, b);
	return emrRtSection[customIndex].z == 8u ? textureLod(emrRtEntityAtlas, uv, 0.0).a : textureLod(emrRtAtlas, uv, 0.0).a;
}
