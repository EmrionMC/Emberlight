# Emrion Pack Format (EPF) — version 2

The converter writes a converted shaderpack as a directory. The mod reads it. This document is the **only contract**
between `converter/` and `mod/`. Two compilers write it: the Iris/OptiFine converter (`converter/…/glsl`, `plan/`) and the
native-format compiler (`converter/…/nativefmt/`, NATIVE_FORMAT.md §15), which compiles `emberlight.json` packs directly.
Where the two differ in naming, this document says so; the runtime treats both the same. The Java records that mirror it live in `converter/src/main/java/dev/emrion/shaders/format/`
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

- `cacheRoot` = `.minecraft/emberlight/cache` (mod) or the CLI's `<out-dir>`.
- `cacheKey = hex(sha256(converterVersion ‖ formatVersion ‖ sha256(pack bytes, sorted by path) ‖ normalised settings ‖ environment))`, first 24 hex chars.
  "environment" covers the inputs that change preprocessing: MC version, GPU vendor/renderer macros, OS macro, plus the
  requested dimension filter when one is given (CLI `--dimension`), because it changes which plans the manifest holds.
  "normalised settings" are the settings file's `key=value` lines, trimmed and sorted by key (blank and `#` lines dropped).
- A directory is valid only if `manifest.json` exists and its `cacheKey`/`formatVersion` match. The converter writes into
  `<key>.tmp/` and renames, so a crash never leaves a half-valid cache.

## 2. Conventions

- All paths in the manifest are relative to the cache directory and use `/`.
- Dimension keys are the pack folder names: `world0`, `world-1`, `world1`, or custom ones from `dimension.properties`.
  Native packs: `default` plus the name of each dimension override; `dimensionMap` routes to them the same way.
- Enum values are lower_snake_case strings.
- Texture formats use Iris/GL names (`RGBA16F`, `R11F_G11F_B10F`, `RGBA8_SNORM`, `R16UI`, …), after **promotion**: RGB-only
  formats become RGBA (`RGB8→RGBA8`, `RGB16F→RGBA16F`, `RGB8_SNORM→RGBA8_SNORM`, …). The original format is kept in `declaredFormat`.
- Depth convention: everything is GL-style depth in [0,1] with near = 0 (see ARCHITECTURE §4.1 step 8). Depth images are `D32F`.

## 3. manifest.json — top level

```jsonc
{
  "formatVersion": 2,
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
  "dimensions": { "world0": { … §7 … }, "world-1": { … }, "world1": { … } },
  "rt": { … §10 … }                       // only in RT variants (the request had RT effects)
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
  "flipStates": [ { "id": 0, "alt": [] }, { "id": 1, "alt": [5], "shadowAlt": [0] }, … ],  // which colortex (shadowcolor) read their alt image
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
    "tessControl": null,   // .tcs, same shape as vertex (one module per vertex variant)
    "tessEval": null,      // .tes, same shape as vertex
    "geometry": null,      // .gsh, same shape as vertex: { "variants": { "terrain_mdi": "spv/world0/gbuffers_terrain.gsh.terrain_mdi.spv", … } }
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
  "localSize": null, "workGroups": null, "workGroupsRender": null,      // compute: [x,y,z] (from the SPIR-V) or [sx,sy] scale
  "mipmapBefore": ["colortex0"],       // composite-like: generate mips for these before the pass (shadowcomp: shadowcolorN)
  "viewportScale": 1.0,
  "viewportOffset": null,              // scale.<program> offset [x, y] (fraction of the target size), null = 0 0
  "renderStageOrdinal": null
}
```

**Geometry slots.** The runtime maps every vanilla pipeline to a geometry slot named like the Iris program that draws it
(`gbuffers_terrain_solid`, `gbuffers_hand`, `shadow_water`, …; the table is the mod's `GbufferMapping`) and draws it with
the program whose key or `fallbackFor` names the first slot of the slot's fallback chain that some program serves; no
such program = not drawn. The shadow pass runs with the program that serves the `shadow` slot. Program keys themselves are
free-form for the runtime (profiler labels, file names).

**Program keys** of converted packs are the Iris program names (`gbuffers_terrain`, `composite3`, `deferred1_b`, `setup2`).
Native packs use their pass names for fullscreen, compute and final passes, and `<pass>.<class>` for the one program per
geometry class a geometry pass draws (`gbuffer.terrain_solid`, `fallbackFor: ["gbuffers_terrain_solid"]`, one slot each);
`el_none.shadow` is a program that draws nothing, emitted only so the shadow pass runs when no pass draws the native
`shadow` class itself. A `.csh` is a
`compute` program named like its file; the slot-0 compute of a pass that also has a fullscreen program (`composite.csh`
next to `composite.fsh`) is keyed `<pass>_csh` (`composite_csh`). Frame commands run a pass's computes (`<pass>_csh` or
`<pass>`, then `_a` … `_z`) before its `fullscreen` command, in the pass's flip state; only the fullscreen program flips.

**Vertex variants.** A gbuffers/shadow vertex stage is compiled once per vertex layout it can be drawn with. The layout keys,
with their attributes in canonical location order, are:

| Key | MC formats | Attributes present |
|---|---|---|
| `terrain_mdi` | `EMRION_BLOCK` + instanced `ChunkPosition`,`ChunkVisibility` | Position, Color, UV0, UV2, Normal, mc_Entity, mc_midTexCoord, at_tangent, at_midBlock + ChunkPosition, ChunkVisibility |
| `terrain_ubo` | `EMRION_BLOCK` (ChunkSection UBO) | same, without the two instanced ones |
| `entity` | `EMRION_ENTITY` (`ENTITY` + derived mc_midTexCoord, at_tangent + iris_Entity) | Position, Color, UV0, UV1, UV2, Normal, mc_midTexCoord, at_tangent, iris_Entity |
| `particle` | `PARTICLE` | Position, UV0, Color, UV2 |
| `block` | `BLOCK` outside chunk meshes (block items, moving blocks, `CRUMBLING`, `BEACON_BEAM_*`; DynamicTransforms) | Position, Color, UV0, UV2 |
| `position_color` | `POSITION_COLOR` | Position, Color |
| `position_tex` | `POSITION_TEX` | Position, UV0 |
| `position_tex_color` | `POSITION_TEX_COLOR` | Position, UV0, Color |
| `position` | `POSITION` | Position |
| `position_color_tex_lightmap` | `POSITION_COLOR_TEX_LIGHTMAP` | Position, Color, UV0, UV2 |
| `lines` | `POSITION_COLOR_NORMAL_LINE_WIDTH` | Position, Color, Normal, LineWidth |

**Tessellation and geometry stages** (gbuffers and shadow programs only; version 2). `.gsh` is always read, `.tcs`/`.tes`
only when the pack lists `TESSELLATION_SHADERS` in `iris.features.required/optional` (Iris ProgramSet). They are compiled
once per vertex variant like the vertex stage, because their matrices (`gl_ModelViewMatrix`, `gl_TextureMatrix[0]`,
`chunkOffset`) depend on the variant (terrain reads TerrainUniform, everything else DynamicTransforms). Varying locations
are assigned per program by name across VS → TCS → TES → GS → FS (per-vertex arrays count their element type), and the
GL→Vulkan depth fix sits in the last stage before the rasterizer (`EmitVertex` in the GS, else the end of the TES, else the
VS). With tessellation the mod draws MC's triangle lists as 3-vertex patches (Iris: `GL_TRIANGLES` → `GL_PATCHES` with GL's
default patch size 3); other topologies, a lone `.tcs` or `.tes` (Vulkan needs both) and a geometry shader whose input
primitive does not match the topology draw nothing, as GL would. Composite-like programs ignore these stages (warning).

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
`0 DynamicTransforms, 1 TerrainUniform, 2 ChunkSection, 3 Sampler0, 4 Sampler1, 5 Sampler2, 6 CloudInfo, 7 CloudFaces,
8 EmrionNormals, 9 EmrionSpecular`.
In every gbuffers and shadow program the pack samplers `normals` and `specular` are `EmrionNormals`/`EmrionSpecular`: the
labPBR maps of the texture the draw binds as `Sampler0` (Iris's PBRTextureManager). Vanilla pipelines never push them, so
the mod pushes them as extra set-0 samplers after the vanilla bindings, and again whenever `Sampler0` changes.
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
  { "binding": 50, "kind": "sampled_image", "builtin": "block_atlas" | "white" | "white_depth" }
]
```

- One binding per distinct (image, view kind, sampler state). A pack sampler name resolves **per program group** to a
  binding (for example `gaux4` → custom texture `cloud-water` in gbuffers/shadow, but → `colortex7` in composites).
- `sampled_image` entries for colortex use the read side per flip state, so the mod builds one descriptor set per `flipStates` entry.
  shadowcolor works the same through `shadowAlt` (absent = none): only shadowcomp passes flip shadowcolor (Iris
  ShadowCompositeRenderer: a pass reads its snapshot, writes the other side, then flips what it drew; `flip.shadowcomp_pre`
  and `flip.shadowcompN.<buf>` apply). The shadow pass always writes main, and every other pass reads the final state, which
  is static (no end-of-frame copy). A shadowcolor that is ever written on its alt side has `hasAlt`.
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
`"resource": "minecraft:textures/atlas/blocks.png"` for MC resources the mod resolves at runtime. Raw textures (iris-spec §2.5,
`<file> TEXTURE_1D|2D|3D|RECTANGLE <format> <w> [h] [d] <pixel format> <pixel type>`) add `"rawPixel": "RGB HALF_FLOAT"`; the
converter rewrites the file as tightly packed texels of the promoted `format` (RGB data gets alpha 1), `size` is `[w(, h(, d))]`,
and the default sampling is Iris's raw default linear/clamp (an `.mcmeta` overrides it). The mod uploads it into a 1D, 2D or 3D image.

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
            "hardwareFiltering": [true, true], "culling": "reversed", "voxelDistance": 64.0 }
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

## 10. rt (RT variants only, RAYTRACING.md §3.2)

Present only when the converter request carried RT effects; the RT-off output has no `rt` key and is byte-identical to a
conversion without RT support. The cache key adds `rt=<sorted request>` only then.

```jsonc
"rt": {
  "effects": ["shadows", "shadows:full"],                       // applied request entries (effect[:option])
  "unavailable": { "reflections": "table complementary-r5 has no reflections patches" },
  "patched": { "shadows": ["world0/gbuffers_terrain.fsh", "world0/composite.fsh", …] },
  "anyHitIds": [10003, 10005]                                   // block ids an active skip list decides per id (shadows' foliage
                                                                // skip): the mod's opacity micromaps leave their cutout hits to the shaders
}
```

Resources added to set 1 of the patched programs (bindings assigned like any other resource):

| kind | builtin | What the mod binds |
|---|---|---|
| `acceleration_structure` | `emrion_tlas` | `RtScene`'s TLAS (fixed for the scene's lifetime, rebuilt every frame) |
| `storage_buffer` | `emrion_rt_sections` | `RtScene`'s section table (`uvec4`: vertex heap address lo/hi, layer, pad), indexed by `instanceCustomIndex` |
| `sampled_image` | `emrion_rtao` | The RT AO pass's output (R32F, screen size, 1 = unoccluded), written before the first pass that samples it (effect `ao`) |
| `sampled_image` | `emrion_rt_entity_atlas` | `RtEntities`' 2048² copy of the entity textures traced this frame (helper sampler `emrRtEntityAtlas`, nearest/clamp); white when no effect traces entities |
| `sampled_image` | `block_atlas` | the block atlas (alpha test of cutout hits) |
| `sampled_image` | `block_atlas_normals`, `block_atlas_specular` | the block atlas's LabPBR `_n` / `_s` atlases (same layout as `block_atlas`; native helpers `emrRtNormalAtlas` / `emrRtSpecularAtlas` behind `el_hitNormalMap` / `el_hitSpecular`); 1×1 LabPBR defaults (flat normal, specular 0) without a LabPBR resource pack |

Patched stages are emitted as `#version 460` with `GL_EXT_ray_query`, `GL_EXT_buffer_reference` and
`GL_EXT_buffer_reference_uvec2`; every other stage stays `#version 450`.
