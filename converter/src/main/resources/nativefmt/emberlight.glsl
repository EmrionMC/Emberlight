// Emberlight header library, native format 1 (docs/NATIVE_FORMAT.md §7.4). Compiled into every stage of a native pack
// after the generated prelude, which declares the frame block, the pack's images/textures/buffers with their bindings,
// the built-in images (el_depth, el_noise, ...) and the per-draw vertex primitives (emr_*) of the geometry variant.
// Unused functions and declarations cost nothing: they are dropped before SPIR-V.
#ifndef EL_HEADER_INCLUDED
#define EL_HEADER_INCLUDED 1
#define EL_HEADER_VERSION 4 // 2: el_hitSpecular, el_hitNormalMap, el_hitMaterial; 3: el_*Grad samplers, el_traceSurface; 4: el_lightFromPrev

// ---- frame block (§8): every member below is always present; the list is the format-1 contract ----------------------
// The compiler declares them as members of one std140 block (set 1, binding 0) at the offsets of the manifest's layout.
/* BEGIN FRAME
uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferPreviousModelView;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferPreviousProjection;
uniform mat4 shadowModelView;
uniform mat4 shadowModelViewInverse;
uniform mat4 shadowProjection;
uniform mat4 shadowProjectionInverse;
uniform vec3 cameraPosition;
uniform vec3 previousCameraPosition;
uniform vec3 cameraPositionFract;
uniform vec3 previousCameraPositionFract;
uniform ivec3 cameraPositionInt;
uniform ivec3 previousCameraPositionInt;
uniform vec3 eyePosition;
uniform vec3 relativeEyePosition;
uniform vec3 playerLookVector;
uniform vec3 sunPosition;
uniform vec3 moonPosition;
uniform vec3 shadowLightPosition;
uniform vec3 upPosition;
uniform vec3 skyColor;
uniform vec3 fogColor;
uniform vec3 heldBlockLightColor;
uniform vec3 heldBlockLightColor2;
uniform ivec2 eyeBrightness;
uniform ivec2 eyeBrightnessSmooth;
uniform float near;
uniform float far;
uniform float viewWidth;
uniform float viewHeight;
uniform float aspectRatio;
uniform float frameTime;
uniform float frameTimeCounter;
uniform float sunAngle;
uniform float shadowAngle;
uniform float rainStrength;
uniform float wetness;
uniform float thunderStrength;
uniform float nightVision;
uniform float blindness;
uniform float darknessFactor;
uniform float darknessLightFactor;
uniform float screenBrightness;
uniform float eyeAltitude;
uniform float cloudHeight;
uniform float temperature;
uniform float rainfall;
uniform float ambientLight;
uniform int frameCounter;
uniform int worldTime;
uniform int worldDay;
uniform int moonPhase;
uniform int isEyeInWater;
uniform int heldItemId;
uniform int heldItemId2;
uniform int heldBlockLightValue;
uniform int heldBlockLightValue2;
uniform int biome;
uniform int bedrockLevel;
uniform int heightLimit;
uniform int seaLevel;
uniform bool hasSkylight;
uniform bool hasCeiling;
uniform bool is_sneaking;
uniform bool is_sprinting;
uniform bool is_on_ground;
uniform bool isSpectator;
uniform bool firstPersonCamera;
uniform bool hideGUI;
END FRAME */

#define el_view gbufferModelView
#define el_viewInverse gbufferModelViewInverse
#define el_projection gbufferProjection
#define el_projectionInverse gbufferProjectionInverse
#define el_prevView gbufferPreviousModelView
#define el_prevProjection gbufferPreviousProjection
#define el_shadowView shadowModelView
#define el_shadowViewInverse shadowModelViewInverse
#define el_shadowProjection shadowProjection
#define el_shadowProjectionInverse shadowProjectionInverse
#define el_cameraPosition cameraPosition
#define el_prevCameraPosition previousCameraPosition
#define el_cameraBlock cameraPositionInt
#define el_cameraFract cameraPositionFract
#define el_eyePosition eyePosition
#define el_screenSize vec2(viewWidth, viewHeight)
#define el_texelSize (1.0 / vec2(viewWidth, viewHeight))
#define el_frame frameCounter
#define el_time frameTimeCounter
#define el_frameTime frameTime
#define el_worldTime worldTime
#define el_worldDay worldDay
#define el_moonPhase moonPhase
#define el_sunDirection normalize(mat3(gbufferModelViewInverse) * sunPosition)
#define el_moonDirection normalize(mat3(gbufferModelViewInverse) * moonPosition)
#define el_lightDirection normalize(mat3(gbufferModelViewInverse) * shadowLightPosition)
#define el_sunAngle sunAngle
#define el_rain rainStrength
#define el_thunder thunderStrength
#define el_wetness wetness
#define el_skyColor skyColor
#define el_fogColor fogColor
#define el_eyeInWater isEyeInWater
#define el_eyeBrightness (vec2(eyeBrightnessSmooth) / 240.0)
#define el_nightVision nightVision
#define el_blindness blindness
#define el_darkness darknessFactor
#define el_near near
#define el_far far
#define el_hasSkylight hasSkylight
#define el_hasCeiling hasCeiling
#define el_heldLight heldBlockLightValue
#define el_heldLight2 heldBlockLightValue2
#define el_heldLightColor heldBlockLightColor
#define el_heldLightColor2 heldBlockLightColor2

// ---- math helpers --------------------------------------------------------------------------------------------------------
// Depth is window depth in [0, 1], near = 0 (GL convention); uv (0, 0) is the bottom-left corner like gl_FragCoord.
vec3 el_screenToView(vec2 uv, float depth) {
	vec4 v = gbufferProjectionInverse * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
	return v.xyz / v.w;
}

vec3 el_viewToWorld(vec3 view) { return mat3(gbufferModelViewInverse) * view + gbufferModelViewInverse[3].xyz; }

vec3 el_worldToView(vec3 world) { return mat3(gbufferModelView) * world + gbufferModelView[3].xyz; }

vec3 el_screenToWorld(vec2 uv, float depth) { return el_viewToWorld(el_screenToView(uv, depth)); }

// Camera-relative world position of this frame -> last frame's uv (for history reprojection).
vec2 el_worldToPrevScreen(vec3 world) {
	vec3 p = world + cameraPosition - previousCameraPosition;
	vec4 clip = gbufferPreviousProjection * (gbufferPreviousModelView * vec4(p, 1.0));
	return clip.xy / clip.w * 0.5 + 0.5;
}

float el_linearDepth(float depth) {
	float z = depth * 2.0 - 1.0;
	return 2.0 * near * far / (far + near - z * (far - near));
}

bool el_isSky(float depth) { return depth >= 1.0; }

vec3 el_srgbToLinear(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c)); }

vec3 el_linearToSrgb(vec3 c) { return mix(c * 12.92, 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c)); }

float el_luminance(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

vec2 el_encodeNormal(vec3 n) {
	n /= abs(n.x) + abs(n.y) + abs(n.z);
	vec2 p = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
	return p * 0.5 + 0.5;
}

vec3 el_decodeNormal(vec2 e) {
	e = e * 2.0 - 1.0;
	vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
	float t = max(-n.z, 0.0);
	n.xy += vec2(n.x >= 0.0 ? -t : t, n.y >= 0.0 ? -t : t);
	return normalize(n);
}

// Noise that shifts every frame (good input for temporal accumulation).
vec4 el_blueNoise(ivec2 pixel) {
	ivec2 size = textureSize(el_noise, 0);
	ivec2 shift = ivec2(frameCounter * 37, frameCounter * 61);
	return texelFetch(el_noise, (pixel + shift) % size, 0);
}

// LabPBR specular data (§7.4): r smoothness, g F0 (< 230/255) or metal id, b porosity/SSS, a emission (255 = none).
struct ElMaterial { float roughness; vec3 f0; bool metal; float emission; float porosity; float sss; };

ElMaterial el_material(vec4 s) {
	ElMaterial m;
	float smoothness = s.r;
	m.roughness = (1.0 - smoothness) * (1.0 - smoothness);
	m.metal = s.g * 255.0 >= 229.5;
	m.f0 = vec3(m.metal ? 1.0 : s.g);
	m.emission = s.a * 255.0 < 254.5 ? s.a * 255.0 / 254.0 : 0.0;
	m.porosity = s.b * 255.0 < 64.5 ? s.b * 255.0 / 64.0 : 0.0;
	m.sss = s.b * 255.0 >= 64.5 ? (s.b * 255.0 - 65.0) / 190.0 : 0.0;
	return m;
}

// ---- geometry passes (§7.4) -------------------------------------------------------------------------------------------
// The prelude defines the vertex primitives of the Minecraft vertex layout this stage is compiled for (one SPIR-V module
// per layout): emr_position, emr_modelView, emr_normalMatrix, emr_color, emr_uv0, emr_light, emr_normal, emr_tangent,
// emr_blockId, emr_midUV, emr_midBlock, emr_entityIds, emr_entityColorVS. Fragment stages read the per-draw ids and the
// overlay colour through the flat varyings emr_entityInfo / emr_entityColor, which the vertex stage writes when needed.
#ifdef EL_KIND_GEOMETRY
#ifndef EL_ALPHA_CUTOFF
#define EL_ALPHA_CUTOFF 0.0
#endif
float el_alphaCutoff() { return EL_ALPHA_CUTOFF; }

#ifdef EL_STAGE_VERTEX
vec3 el_vertexPosition() { return emr_position(); }
vec3 el_vertexViewPosition() { return (emr_modelView() * vec4(emr_position(), 1.0)).xyz; }
vec3 el_vertexWorldPosition() { return el_viewToWorld(el_vertexViewPosition()); }
vec4 el_vertexColor() { return emr_color(); }
vec2 el_vertexUV() { return emr_uv0(); }
vec2 el_vertexLight() { return emr_light(); }
vec3 el_vertexNormal() { return emr_normal(); }
vec3 el_vertexViewNormal() { return normalize(emr_normalMatrix() * emr_normal()); }
vec4 el_vertexTangent() { return emr_tangent(); }
int el_blockId() { return emr_blockId(); }
vec2 el_midUV() { return emr_midUV(); }
vec3 el_midBlock() { return emr_midBlock().xyz / 64.0; }
float el_vertexEmission() { return emr_midBlock().w; }
vec4 el_project(vec3 view) { return emr_phase.emr_ProjMat * vec4(view, 1.0); }
int el_entityId() { return emr_entityIds().x; }
int el_blockEntityId() { return emr_entityIds().y; }
int el_itemId() { return emr_entityIds().z; }
vec4 el_entityColor() { return emr_entityColorVS(); }
#endif

#if defined(EL_STAGE_FRAGMENT) && defined(EL_ENTITY_VARYINGS)
int el_entityId() { return emr_entityInfo.x; }
int el_blockEntityId() { return emr_entityInfo.y; }
int el_itemId() { return emr_entityInfo.z; }
vec4 el_entityColor() { return emr_entityColor; }
#endif

#ifdef EL_STAGE_FRAGMENT
vec4 el_albedo(vec2 uv) { return texture(emr_gtexture, uv); }
vec4 el_normalMap(vec2 uv) { return texture(emr_normals, uv); }
vec4 el_specularMap(vec2 uv) { return texture(emr_specular, uv); }
// explicit gradients: for uv computed in loops or wrapped inside a sprite (parallax occlusion mapping)
vec4 el_albedoGrad(vec2 uv, vec2 dx, vec2 dy) { return textureGrad(emr_gtexture, uv, dx, dy); }
vec4 el_normalMapGrad(vec2 uv, vec2 dx, vec2 dy) { return textureGrad(emr_normals, uv, dx, dy); }
vec4 el_specularMapGrad(vec2 uv, vec2 dx, vec2 dy) { return textureGrad(emr_specular, uv, dx, dy); }
vec2 el_lightmapUV(vec2 light) { return light * (15.0 / 16.0) + 1.0 / 32.0; }
vec3 el_vanillaLight(vec2 light) { return texture(emr_lightmap, el_lightmapUV(light)).rgb; }
#endif
#endif

// ---- ray queries (§7.6) ------------------------------------------------------------------------------------------------
// The prelude declares the scene (emrRtTlas, emrRtAtlas, emrRtEntityAtlas, emrRtNormalAtlas, emrRtSpecularAtlas,
// EmrRtSections, EmrRtLights) and inserts the shared RT vertex library (rt/emrion_rt.glsl) before this header.
#ifdef EL_RAYTRACING
#define el_tlas emrRtTlas
#define el_atlas emrRtAtlas
#define EL_MASK_OPAQUE 0x01u
#define EL_MASK_TRANSLUCENT 0x02u
#define EL_MASK_ENTITIES 0x04u
#define EL_MASK_PLAYER 0x08u
#define EL_MASK_ALL 0xFFu

// Low-level access to a hit instance's vertices (see emberlight_rt.glsl for the accessors).
EmrRtVerts el_hitVertices(int customIndex) { return emrRtVerts(customIndex); }

bool el_rtAlphaPasses(rayQueryEXT rq) {
	int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
	EmrRtVerts v = emrRtVerts(ci);
	uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)));
	return emrRtHitAlpha(ci, v, tri, rayQueryGetIntersectionBarycentricsEXT(rq, false)) >= 0.1;
}

// true = nothing within tMax along dir (alpha-tested: leaves and grass let light through their holes).
bool el_traceVisibility(vec3 origin, vec3 dir, float tMax, uint mask) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT, mask, origin, 0.0, dir, tMax);
	// bounded cost: the 32nd alpha-tested candidate counts as a hit, so a capped ray reads as blocked, not as lit
	for (int guard = 0; guard < 32 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) == gl_RayQueryCandidateIntersectionTriangleEXT && (guard == 31 || el_rtAlphaPasses(rq)))
			rayQueryConfirmIntersectionEXT(rq);
	}
	return rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT;
}

// entityAtlas: uv addresses the RT entity atlas; false for terrain and for entities drawn from the block atlas (dropped and
// falling blocks: section kind 9), whose uv stays in the block atlas and its LabPBR maps
struct ElHit { bool hit; float t; vec3 position; vec3 normal; vec2 uv; int blockId; vec4 color; vec2 light; bool entity; bool entityAtlas; };

ElHit emr_traceClosest(vec3 origin, vec3 dir, float tMax, uint mask, bool alphaTest) {
	rayQueryEXT rq;
	rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsNoneEXT, mask, origin, 0.0, dir, tMax);
	// bounded cost: the 64th alpha-tested candidate counts as a hit, so a capped ray ends on geometry, not in the sky
	for (int guard = 0; guard < 64 && rayQueryProceedEXT(rq); guard++) {
		if (rayQueryGetIntersectionTypeEXT(rq, false) == gl_RayQueryCandidateIntersectionTriangleEXT && (!alphaTest || guard == 63 || el_rtAlphaPasses(rq)))
			rayQueryConfirmIntersectionEXT(rq);
	}
	ElHit h;
	h.hit = rayQueryGetIntersectionTypeEXT(rq, true) != gl_RayQueryCommittedIntersectionNoneEXT;
	h.t = h.hit ? rayQueryGetIntersectionTEXT(rq, true) : tMax;
	h.position = origin + dir * h.t;
	h.normal = vec3(0.0);
	h.uv = vec2(0.0);
	h.blockId = -1;
	h.color = vec4(1.0);
	h.light = vec2(0.0);
	h.entity = false;
	h.entityAtlas = false;
	if (!h.hit) return h;
	int ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true);
	EmrRtVerts v = emrRtVerts(ci);
	uvec3 tri = emrRtTri(uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true)));
	vec2 b = rayQueryGetIntersectionBarycentricsEXT(rq, true);
	vec3 p0 = emrRtPos(v, tri.x), p1 = emrRtPos(v, tri.y), p2 = emrRtPos(v, tri.z);
	vec3 n = normalize(cross(p1 - p0, p2 - p0));
	h.normal = dot(n, dir) > 0.0 ? -n : n;
	h.uv = emrRtBaryUv(v, tri, b);
	h.entity = emrRtIsEntity(ci);
	h.entityAtlas = emrRtSection[ci].z == 8u;
	h.blockId = h.entity ? -1 : emrRtId(v, tri.x);
	h.color = emrRtColor(v, tri.x) * (1.0 - b.x - b.y) + emrRtColor(v, tri.y) * b.x + emrRtColor(v, tri.z) * b.y;
	h.light = clamp((vec2(emrRtLight(v, tri.x)) * (1.0 - b.x - b.y) + vec2(emrRtLight(v, tri.y)) * b.x + vec2(emrRtLight(v, tri.z)) * b.y) / 240.0, 0.0, 1.0); // interpolated like the colour (one vertex left a seam at the quad diagonal)
	return h;
}

ElHit el_traceClosest(vec3 origin, vec3 dir, float tMax, uint mask) { return emr_traceClosest(origin, dir, tMax, mask, true); }
// Closest surface without the alpha test: the clear centre of a glass block is still the block's face (refraction,
// thickness of translucent blocks). With EL_MASK_OPAQUE, cutout leaves and grass count as solid too.
ElHit el_traceSurface(vec3 origin, vec3 dir, float tMax, uint mask) { return emr_traceClosest(origin, dir, tMax, mask, false); }

// Base colour at a hit: block atlas for terrain and block-atlas entities, Emberlight's RT entity atlas for the others.
vec4 el_hitAlbedo(ElHit h) {
	return h.entityAtlas ? textureLod(emrRtEntityAtlas, h.uv, 0.0) : textureLod(emrRtAtlas, h.uv, 0.0);
}

// LabPBR maps at a hit (§7.6): the resource pack's _s / _n texel the hit sees, from the block atlas's PBR atlases (same
// layout and uv as el_atlas, the maps the gbuffer reads through el_specularMap / el_normalMap). Entities and worlds
// without a LabPBR resource pack read the LabPBR defaults: specular 0 (no material), flat normal.
vec4 el_hitSpecular(ElHit h) { return h.entityAtlas ? vec4(0.0) : textureLod(emrRtSpecularAtlas, h.uv, 0.0); }
vec4 el_hitNormalMap(ElHit h) { return h.entityAtlas ? vec4(0.5, 0.5, 1.0, 1.0) : textureLod(emrRtNormalAtlas, h.uv, 0.0); }
ElMaterial el_hitMaterial(ElHit h) { return el_material(el_hitSpecular(h)); }

// RT light list (§7.6): every block light source near the camera, player-space positions (EmrRtLights, prelude).
// range: light level reach in blocks; radius: size of the source (for soft shadows).
struct ElLight { vec3 position; vec3 color; float range; float radius; };

int el_lightCount() { return int(emrRtLightOrigin.w); }

ElLight el_light(int i) {
	vec4 l = emrBlPos[i];
	return ElLight(emrRtLightOrigin.xyz + l.xyz, emrBlCol[i].rgb * abs(emrBlCol[i].a), floor(l.w), fract(l.w));
}

// Index range [x, y) into el_lightIndex() of the sources whose range reaches the grid cell around p (player space);
// empty outside the grid.
uvec2 el_lightCell(vec3 p) {
	ivec3 c = ivec3(floor((p - emrRtLightOrigin.xyz) / float(emrRtLightDims.w)));
	if (any(lessThan(c, ivec3(0))) || any(greaterThanEqual(c, emrRtLightDims.xyz))) return uvec2(0u);
	int cell = (c.y * emrRtLightDims.z + c.z) * emrRtLightDims.x + c.x;
	return uvec2(emrBlCellStart[cell], emrBlCellStart[cell + 1]);
}

int el_lightIndex(uint i) { return int(emrBlIndex[i]); }

// Last frame's el_light index -> this frame's, -1 when that source is gone: temporal reuse of light samples (ReSTIR). The
// list keeps its order until it is rebuilt (lights added or removed, the camera crossed a cell); then the remap table maps it.
int el_lightFromPrev(int i) {
	if (i < 0 || i >= 16384) return -1;
	uint r = emrRtLightChange[1].w > 0.5 ? emrBlRemap[i] : uint(i);
	return r < uint(el_lightCount()) ? int(r) : -1;
}
#endif

#endif
