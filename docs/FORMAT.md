# Emrion Pack Format (EPF) — version 1

The converter writes a converted shaderpack as a directory. The mod reads it. This document is the **only contract**
between `converter/` and `mod/`. The Java records that mirror it live in `converter/src/main/java/dev/emrion/shaders/format/`
and are used by both sides (Gson, field names = JSON keys). A change here bumps `formatVersion`.
The JSON examples below show intent. Where an example and `Manifest.java` disagree in shape (for example, `size` is split into
`relativeSize`/`absoluteSize`), the **Java records win**, and this document is fixed in the same commit.

## 1. Directory layout and cache key

```
<cacheRoot>/<cacheKey>/
  manifest.json            global data + per-dimension plans (§3)
  report.json              diagnostics (§9)
  spv/<dim>/<program>.<stage>[.<variant>].spv
  textures/<name>.png      custom textures copied from the pack (noisetex, texture.* entries)
  src/<dim>/<program>.<stage>[.<variant>].glsl   transpiled GLSL (debug aid, always written)
```

- `cacheRoot` = `.minecraft/emrionshaders/cache` (mod) or the CLI's `<out-dir>`.
- `cacheKey = hex(sha256(converterVersion ‖ formatVersion ‖ sha256(pack bytes, sorted by path) ‖ normalised settings ‖ environment))`, first 24 hex chars.
  "environment" covers the inputs that change preprocessing: MC version, GPU vendor/renderer macros, OS macro, plus the
  requested dimension filter when one is given (CLI `--dimension`), because it changes which plans the manifest holds.
  "normalised settings" are the settings file's `key=value` lines, trimmed and sorted by key (blank and `#` lines dropped).
- A directory is valid only if `manifest.json` exists and its `cacheKey`/`formatVersion` match. The converter writes into
  `<key>.tmp/` and renames, so a crash never leaves a half-valid cache.

## 2. Conventions

- All paths in the manifest are relative to the cache directory and use `/`.
- Dimension keys are the pack folder names: `world0`, `world-1`, `world1`, or custom ones from `dimension.properties`.
- Enum values are lower_snake_case strings.
- Texture formats use Iris/GL names (`RGBA16F`, `R11F_G11F_B10F`, `RGBA8_SNORM`, `R16UI`, …), after **promotion**: RGB-only
  formats become RGBA (`RGB8→RGBA8`, `RGB16F→RGBA16F`, `RGB8_SNORM→RGBA8_SNORM`, …). The original format is kept in `declaredFormat`.
- Depth convention: everything is GL-style depth in [0,1] with near = 0 (see ARCHITECTURE §4.1 step 8). Depth images are `D32F`.

## 3. manifest.json — top level

```jsonc
{
  "formatVersion": 1,
  "converterVersion": "0.1.0",
  "cacheKey": "3fa2…",
  "pack": { "name": "ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5", "sha256": "…" },
  "environment": { "mcVersion": "26.3", "macros": { "MC_VERSION": "260300", "IS_IRIS": "", … } },
  "options": { … §4 … },
  "properties": { … §5 … },               // pack-wide pipeline flags from shaders.properties
  "uniforms": { … §6 … },                 // one std140 layout shared by every dimension and program
  "idMaps": { … §8 … },
  "textures": [ … §7.3 … ],               // custom textures (files in textures/)
  "dimensionMap": [ { "match": "minecraft:overworld", "folder": "world0" }, … ],  // exact ids win; then globs ("*:*") in list order
  "dimensions": { "world0": { … §7 … }, "world-1": { … }, "world1": { … } }
}
```

## 4. options (for the GUI and settings round-trip)

```jsonc
"options": {
  "settingsFile": "ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5.txt",
  "values": { "SHADOW_QUALITY": "3", "TAA_DEFINE": "1", "POM": "true", … },   // resolved: every option, defaults included
  "declared": [
    { "name": "SHADOW_QUALITY", "kind": "define_value" | "define_bool" | "const",
      "type": "int" | "float" | "bool" | "string", "default": "2", "allowed": ["-1","0","1","2","3","4","5"],
      "file": "shaders/lib/common.glsl", "line": 120, "comment": "…" }
  ],
  "unknownSettings": ["AAA_THIS_IS_A_EUPHORIA_PATCHES_SETTINGS_FILE"],  // keys from the .txt that match no option (kept on save)
  "profiles": { "POTATO": { … }, … },                                  // profile.<name> resolved to value maps
  "screens": { "": ["<profile>", "[LIGHTING]", …], "LIGHTING": […] },   // screen.* raw lists
  "sliders": ["SHADOW_QUALITY", …], "columns": { "": 2, … }
}
```

The mod writes changed settings back in the Iris `.txt` format through `IrisSettings` (converter package `options`), so the file stays usable with Iris.

## 5. properties (pack-wide flags)

Typed copy of the shaders.properties keys that affect runtime behaviour (after preprocessing):
`sun, moon, stars?, sky?, clouds ("off"|"fast"|"fancy"), underwaterOverlay, vignette, oldLighting, oldHandLight, separateAo,
supportsColorCorrection, beaconBeamDepth, rainDepth, particlesOrdering ("before"|"mixed"|"after"), shadowCulling
("default"|"reversed"|"off"), shadowTerrain, shadowTranslucent, shadowEntities, shadowPlayer, shadowBlockEntities,
shadowLightBlockEntities, voxelizeLightBlocks, frustumCulling, …`. Unknown or unsupported keys are listed in the report.
Defaults follow iris-spec §4.

## 6. uniforms — the shared `EmrionUniforms` block

```jsonc
"uniforms": {
  "blockName": "EmrionUniforms", "size": 1536,
  "members": [
    { "name": "gbufferModelView", "type": "mat4", "offset": 0,   "source": "builtin" },
    { "name": "cameraPositionInt", "type": "ivec3", "offset": 64, "source": "builtin" },
    { "name": "worldAgeSmooth", "type": "float", "offset": 1200, "source": "custom" },
    { "name": "shadowLightPosition", "type": "vec3", "offset": 1216, "source": "builtin" },
    { "name": "foo", "type": "float", "arraySize": 4, "arrayStride": 16, "offset": … }
  ],
  "custom": [   // evaluated in this order every frame, after built-ins; variables (not in block) included
    { "name": "framemod2", "type": "float", "uniform": true, "expr": { …AST… } },
    { "name": "worldDayTime", "type": "float", "uniform": false, "expr": { … } }
  ],
  "phase": {    // EmrionPhase block (set 1 binding 1), written per render phase
    "blockName": "EmrionPhase", "size": 144,
    "members": [ { "name": "emr_ProjMat", "type": "mat4", "offset": 0 }, { "name": "emr_ProjMatInverse", "type": "mat4", "offset": 64 },
                 { "name": "renderStage", "type": "int", "offset": 128 } ]
  },
  "pushConstants": {  // EmrionDraw, per draw
    "size": 40,
    "members": [ { "name": "entityColor", "type": "vec4", "offset": 0 }, { "name": "entityId", "type": "int", "offset": 16 },
                 { "name": "blockEntityId", "type": "int", "offset": 20 }, { "name": "currentRenderedItemId", "type": "int", "offset": 24 },
                 { "name": "emr_alphaTestRef", "type": "float", "offset": 28 }, { "name": "atlasSize", "type": "ivec2", "offset": 32 } ]
  }
}
```

- std140 rules; `bool` is stored as a 4-byte int. Members sit in **one block for every program**, so the CPU writes it once per frame.
- `source: builtin` names follow Iris naming (iris-spec §5). The mod's `UniformSystem` has one provider per built-in name.
  A built-in the mod does not know is an error at load time (it is reported, the value stays 0).
- Custom uniform expressions (§6.1) are serialised ASTs that the shared `expr` package evaluates.
- Only uniforms that are **live in at least one program** (after SPIR-V optimisation) are included.

### 6.1 Expression AST

```jsonc
{ "op": "call", "fn": "smooth", "stateId": 3, "id": "4", "args": [ target, up?, down? ], "type": "float" }  // id dropped from args
{ "op": "call", "fn": "clamp", "args": [ … ], "type": "float" }  // Iris function name; overload = (fn, arg types, type)
{ "op": "var", "name": "frameCounter", "type": "int" }         // built-in or custom uniform/variable
{ "op": "const", "name": "BIOME_PALE_GARDEN", "type": "int" }  // biome id, resolved by the mod from the registry
{ "op": "lit", "value": 2.0, "type": "float" }                 // also CAT_* / PPT_* (fixed values)
{ "op": "cast", "arg": { … }, "type": "float" }                // int→float (implicit or toFloat) / float→int (toInt)
{ "op": "bin", "operator": "&&", "lhs": { … }, "rhs": { … }, "type": "bool" }
{ "op": "un", "operator": "!", "arg": { … }, "type": "bool" }
{ "op": "vec", "size": 2, "args": [ … ], "type": "vec2" }      // also "__ivecN" / "__bvecN" (Iris array vectors)
{ "op": "member", "arg": { … }, "field": "y", "type": "float" } // field as written (x/r/s/0 …); matrix columns too
```

Every node is fully typed; implicit conversions are explicit `cast` nodes, so the evaluator never guesses an overload.
Types: `bool int float vec2-4 ivec2-4 mat3 mat4 __ivec2-4 __bvec2-4`. Stateful functions (`smooth`) carry a stable
`"stateId"` assigned by the converter, so the mod keeps per-call history. Iris quirks are reproduced on purpose and noted
in the `expr` package and its tests (e.g. `&&`/`||` have equal precedence, the `smooth` id is ignored, n-ary `min`/`max`
only look at their first two arguments). The shared evaluator is `expr.CustomUniformRuntime`.

## 7. Dimension plan

```jsonc
"world0": {
  "programs": { "<name>": { … §7.1 … } },
  "resources": [ … §7.2 … ],     // set 1 layout (shared by all programs of this dimension)
  "targets": [ … §7.3 … ],       // physical images to allocate
  "flipStates": [ { "id": 0, "alt": [] }, { "id": 1, "alt": [5] }, … ],  // which colortex read their alt image
  "frame": [ … §7.4 … ],         // ordered per-frame command list
  "once": [ … ],                 // commands run once after load/resize (setup*, full clears)
  "shadow": { … §7.5 … }
}
```

### 7.1 Programs

```jsonc
"gbuffers_terrain": {
  "kind": "gbuffers" | "shadow" | "composite" | "compute",
  "group": "gbuffers" | "shadow" | "begin" | "prepare" | "deferred" | "composite" | "final" | "setup" | "shadowcomp",
  "fallbackFor": ["gbuffers_terrain_solid", "gbuffers_terrain_cutout"],   // Iris names this program serves
  "stages": {
    "vertex":   { "variants": { "terrain_mdi": "spv/world0/gbuffers_terrain.vsh.terrain_mdi.spv", "entity": "…" } },
    "geometry": null,
    "fragment": { "spv": "spv/world0/gbuffers_terrain.fsh.spv" },
    "compute":  null
  },
  "outputs": [ { "location": 0, "target": "colortex0" }, { "location": 1, "target": "colortex6" }, { "location": 2, "target": "colortex4" } ],
  "blend": { "default": "vanilla" | "off" | { "src": "src_alpha", "dst": "one_minus_src_alpha", "srcA": "one", "dstA": "one_minus_src_alpha" },
             "perTarget": { "colortex6": "off", "colortex4": "off" } },
  "alphaTest": { "func": "greater", "ref": 0.1 } | null,   // null = Iris default for this program
  "set0": [ { "name": "TerrainUniform", "type": "uniform_buffer" }, { "name": "Sampler0", "type": "combined_image_sampler" } ],
  "set1": [ 0, 1, 7, 12, 13 ],          // set-1 bindings the program actually uses (for barriers)
  "writesStorage": [ 40 ],             // set-1 bindings written via imageStore/SSBO (for barriers)
  "usesPushConstants": true,
  "localSize": null, "workGroups": null, "workGroupsRender": null,      // compute: [x,y,z] or [sx,sy] scale
  "mipmapBefore": ["colortex0"],       // composite-like: generate mips for these before the pass
  "viewportScale": 1.0,
  "renderStageOrdinal": null
}
```

**Vertex variants.** A gbuffers/shadow vertex stage is compiled once per vertex layout it can be drawn with. The layout keys,
with their attributes in canonical location order, are:

| Key | MC formats | Attributes present |
|---|---|---|
| `terrain_mdi` | `EMRION_BLOCK` + instanced `ChunkPosition`,`ChunkVisibility` | Position, Color, UV0, UV2, Normal, mc_Entity, mc_midTexCoord, at_tangent, at_midBlock + ChunkPosition, ChunkVisibility |
| `terrain_ubo` | `EMRION_BLOCK` (ChunkSection UBO) | same, without the two instanced ones |
| `entity` | `EMRION_ENTITY` (`ENTITY` + derived mc_midTexCoord, at_tangent + iris_Entity) | Position, Color, UV0, UV1, UV2, Normal, mc_midTexCoord, at_tangent, iris_Entity |
| `particle` | `PARTICLE` | Position, UV0, Color, UV2 |
| `position_color` | `POSITION_COLOR` | Position, Color |
| `position_tex` | `POSITION_TEX` | Position, UV0 |
| `position_tex_color` | `POSITION_TEX_COLOR` | Position, UV0, Color |
| `position` | `POSITION` | Position |
| `position_color_tex_lightmap` | `POSITION_COLOR_TEX_LIGHTMAP` | Position, Color, UV0, UV2 |
| `lines` | `POSITION_COLOR_NORMAL_LINE_WIDTH` | Position, Color, Normal, LineWidth |

The mod picks the variant from the vanilla pipeline's vertex format. The converter emits only the variants a program can
actually be used with (from the Iris program-to-geometry mapping, iris-spec §1.3). Attributes the variant lacks are
compiled in as Iris's defaults. The final mapping table lives in `dev.emrion.shaders.format.VertexLayouts` and is the
single source of truth for both sides.

**Canonical vertex input locations** (all variants):
`0 Position, 1 Color, 2 UV0, 3 UV1, 4 UV2, 5 Normal, 6 mc_Entity, 7 mc_midTexCoord, 8 at_tangent, 9 at_midBlock,
10 ChunkPosition, 11 ChunkVisibility, 12 LineWidth, 13 iris_Entity`.

`iris_Entity` is a `uvec3` (entityId, blockEntityId, currentRenderedItemId; vertex format RGBA16_UINT, so −1 reads as 65535
as on Iris's USHORT attribute). Geometry programs without a geometry stage that use `entityId`/`blockEntityId`/
`currentRenderedItemId` read them from a flat varying `emr_entityInfo` that the vertex stage fills from `iris_Entity` (variants
that have it) or from the EmrionDraw push constants (all other variants), so the fragment stage stays variant-independent.

Vertex formats the mod draws terrain and entities with while a pack is active (`EmrionVertexFormats`, 56 B each):

| EMRION_BLOCK | offset | GpuFormat | | EMRION_ENTITY | offset | GpuFormat |
|---|---|---|---|---|---|---|
| Position | 0 | RGB32_FLOAT | | Position | 0 | RGB32_FLOAT |
| Color | 12 | RGBA8_UNORM | | Color | 12 | RGBA8_UNORM |
| UV0 | 16 | RG32_FLOAT | | UV0 | 16 | RG32_FLOAT |
| UV2 | 24 | RG16_SINT | | UV1 | 24 | RG16_SINT |
| Normal | 28 | RGBA8_SNORM (w pad) | | UV2 | 28 | RG16_SINT |
| mc_Entity | 32 | RG16_SINT (id, 0 block / 1 fluid) | | Normal | 32 | RGBA8_SNORM (w pad) |
| mc_midTexCoord | 36 | RG32_FLOAT | | mc_midTexCoord | 36 | RG32_FLOAT |
| at_tangent | 44 | RGBA8_SNORM | | at_tangent | 44 | RGBA8_SNORM |
| at_midBlock | 48 | RGBA16_FLOAT (xyz = Iris's byte (centre − vertex)·64, w = light emission) | | iris_Entity | 48 | RGBA16_UINT |

**set0** lists the MC per-draw resources a stage references. In the SPIR-V they carry `DescriptorSet 0` and a placeholder
`Binding` equal to the index in this canonical table:
`0 DynamicTransforms, 1 TerrainUniform, 2 ChunkSection, 3 Sampler0, 4 Sampler1, 5 Sampler2, 6 CloudInfo, 7 CloudFaces`.
At pipeline creation the mod rewrites each `Binding` word to the index of that name in the replaced vanilla pipeline's
**backend** `uniforms()` list (= frontend `uniformIndices()`; ARCHITECTURE §5.2). On this NVIDIA machine, terrain uses the
multidraw-indirect path: `terrain_mdi`, set 0 = `[Globals, Projection, TerrainUniform, Fog, Sampler2, Sampler0]`. The mod parses `OpDecorate` itself, so no spvc is needed at runtime.

### 7.2 Resources (descriptor set 1)

```jsonc
"resources": [
  { "binding": 0,  "kind": "uniform_buffer_dynamic", "name": "EmrionUniforms" },
  { "binding": 1,  "kind": "uniform_buffer_dynamic", "name": "EmrionPhase" },
  { "binding": 2,  "kind": "sampled_image", "target": "colortex0", "sampler": { "filter": "linear", "mipmap": "linear", "wrap": "clamp" } },
  { "binding": 9,  "kind": "sampled_image", "target": "shadowtex0", "sampler": { "filter": "linear", "wrap": "clamp", "compare": "lequal" } },
  { "binding": 10, "kind": "sampled_image", "target": "shadowtex0", "sampler": { "filter": "nearest", "wrap": "clamp" } },
  { "binding": 20, "kind": "sampled_image", "texture": "cloud-water", "sampler": { … } },
  { "binding": 30, "kind": "storage_image", "target": "voxel_img" },
  { "binding": 31, "kind": "sampled_image", "target": "voxel_img", "sampler": { "filter": "nearest", "wrap": "clamp" } },
  { "binding": 40, "kind": "storage_buffer", "target": "bufferObject0" },
  { "binding": 50, "kind": "sampled_image", "builtin": "normals_atlas" | "specular_atlas" | "block_atlas" }
]
```

- One binding per distinct (image, view kind, sampler state). A pack sampler name resolves **per program group** to a
  binding (for example `gaux4` → custom texture `cloud-water` in gbuffers/shadow, but → `colortex7` in composites).
- `sampled_image` entries for colortex use the read side per flip state, so the mod builds one descriptor set per `flipStates` entry.
- The same image may appear with a compare sampler and a plain sampler (shadowtex0 in Complementary).
- A `storage_image` on a colortex (`colorimgN`) binds the **read side** of the flip state, as Iris does (iris-spec §2.2 "Images").

### 7.3 Targets and textures

```jsonc
"targets": [
  { "name": "colortex0", "kind": "color", "format": "R11F_G11F_B10F", "declaredFormat": "R11F_G11F_B10F",
    "size": { "relative": [1.0, 1.0] } , "mipLevels": 0 (= full chain) | 1, "hasAlt": true,
    "clear": true, "clearColor": "fog" | [0,0,0,0] },
  { "name": "depthtex0", "kind": "depth", "format": "D32F", "size": { "relative": [1,1] } },
  { "name": "shadowtex0", "kind": "depth", "format": "D32F", "size": { "absolute": [2048, 2048] } },
  { "name": "voxel_img", "kind": "image", "format": "R16UI", "dims": 3, "size": { "absolute": [512,256,512] }, "clear": true },
  { "name": "bufferObject0", "kind": "buffer", "bytes": 810549248, "clear": false }
]
```
Custom textures (top-level `textures`): `{ "name", "file", "format", "dims", "size", "filter", "wrap", "mipmap" }`, or
`"resource": "minecraft:textures/atlas/blocks.png"` for MC resources the mod resolves at runtime.

### 7.4 Frame commands

Executed strictly in order. `flipState` refers to `flipStates[].id`.

| `cmd` | Fields | Meaning |
|---|---|---|
| `clear` | `targets: [{name, side: "main"|"alt"|"both", color}]` | clear colour or images (for `clear=true`) |
| `compute` | `program`, `flipState` | dispatch (workGroups or render-scaled) |
| `fullscreen` | `program`, `flipState`, `writes: [{location, target, side}]` | draw one triangle |
| `mipmaps` | `target`, `side` | generate the full mip chain |
| `geometry` | `phase`, `flipState`, `attachments: [{location, target, side}]` per program (in `programs`) | let MC draw a geometry phase with gbuffers pipelines. Phases: `shadow_opaque`, `shadow_entities`, `shadow_translucent`, `sky`, `opaque`, `hand_solid`, `translucent`, `hand_translucent` |
| `copy_depth` | `from`, `to` | `depthtex0 → depthtex1` and so on, plus `shadowtex0 → shadowtex1` |
| `copy` | `target`, `from: "alt"`, `to: "main"` | end-of-frame swap for flipped, non-cleared buffers |
| `final` | `program`, `flipState` | final pass into MC's main render target |

The planner reproduces Iris's order (iris-spec §0) and flip rules (iris-spec §2.2). It drops commands for disabled programs.

### 7.5 Shadow settings

```jsonc
"shadow": { "enabled": true, "resolution": 2048, "distance": 256.0, "distanceRenderMul": 1.0, "entityDistanceMul": 0.125,
            "nearPlane": -100.05, "farPlane": 156.0, "fov": null, "intervalSize": 2.0, "sunPathRotation": -40.0,
            "hardwareFiltering": [true, true], "culling": "reversed" }
```

## 8. idMaps

```jsonc
"idMaps": {
  "blocks":   [ { "id": 10007, "match": [ "minecraft:oak_leaves", "minecraft:wheat:age=7", "#minecraft:logs" ] } ],
  "items":    [ … ], "entities": [ … ],
  "layers":   { "translucent": ["minecraft:glass", …] }
}
```
Matchers are fully expanded (macros and `%tag` resolved where static). Tag references stay symbolic (`#ns:tag`) and the mod
resolves them against the registry. First match wins in file order.

## 9. report.json

```jsonc
{ "ok": true, "programs": 33, "stagesCompiled": 82, "stagesFailed": 0,
  "diagnostics": [ { "severity": "error"|"warning"|"info", "file": "shaders/program/gbuffers_terrain.glsl", "line": 418,
                     "program": "gbuffers_terrain", "stage": "fragment", "message": "…" } ] }
```
Errors are collected per stage and never abort the run. A failed stage leaves its program out of the plan, and the mod
falls back as Iris does (the program is skipped and its fallback chain is used for gbuffers).
