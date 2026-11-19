# Emberlight native shaderpack format — format 1

Status: **alpha** (format 1, first implementation 2026-11). This is the public spec for people who write shaderpacks
for Emberlight directly, without going through Iris/OptiFine conventions. Iris/OptiFine packs keep working through the
converter, unchanged; this format sits next to it.

Goals:

* **Explicit.** One manifest (`emberlight.json`) lists every image, buffer, texture, pass, option and setting. Nothing
  is inferred from file names or magic comments.
* **Vulkan-native features first-class.** GLSL 460, compute passes, storage images and buffers, ray queries against
  Emberlight's scene TLAS, the RT light list, LabPBR maps, upscaling and frame generation contracts.
* **Same runtime.** A native pack is compiled to the same internal manifest (EPF, [FORMAT.md](FORMAT.md)) as a
  converted Iris pack, so every runtime feature (geometry hooks, Sodium path, RT scene, DLSS/FSR/XeSS, frame generation,
  HDR, photo mode, profiler) works for both without a second code path (§15).
* **Good errors.** Every manifest error names `emberlight.json:<line>`; every shader error names `<file>:<line>` in the
  pack, not in generated code.

Inspiration (ideas only, no code): Iris/OptiFine (frame phases, geometry classes, option GUI, LabPBR), Canvas
(pipeline JSON with named images and passes), Veil (named framebuffers), Bedrock Deferred/RTX (material maps, emissive
and metal-rough conventions), Godot/Bevy render graphs (passes declare what they write; the graph orders work between
fixed engine nodes).

## 1. A complete minimal pack

```
my-pack/
  emberlight.json
  shaders/
    gbuffer.vert.glsl
    gbuffer.frag.glsl
    final.frag.glsl
```

```jsonc
// emberlight.json  (JSON; // and /* */ comments are allowed)
{
  "format": 1,
  "id": "my-pack",
  "name": "My Pack",
  "version": "1.0.0",
  "images": {
    "albedo": { "format": "RGBA8" }
  },
  "passes": [
    { "name": "terrain", "kind": "geometry", "classes": ["terrain_solid", "terrain_cutout", "entities"],
      "vertex": "shaders/gbuffer.vert.glsl", "fragment": "shaders/gbuffer.frag.glsl", "writes": ["albedo"] },
    { "name": "output", "kind": "final", "fragment": "shaders/final.frag.glsl" }
  ]
}
```

```glsl
// shaders/gbuffer.vert.glsl
out vec2 uv;
out vec4 tint;
void main() {
    uv = el_vertexUV();
    tint = el_vertexColor();
    gl_Position = el_project(el_vertexViewPosition());
}
```

```glsl
// shaders/gbuffer.frag.glsl
in vec2 uv;
in vec4 tint;
layout(location = EL_OUT_albedo) out vec4 outAlbedo;
void main() {
    vec4 c = el_albedo(uv) * tint;
    if (c.a < el_alphaCutoff()) discard;
    outAlbedo = c;
}
```

```glsl
// shaders/final.frag.glsl
in vec2 el_uv;
layout(location = 0) out vec4 outColor;
void main() { outColor = texture(albedo, el_uv); }
```

A working, commented example with deferred lighting, a ray-query shadow pass and an option page is in
[`packs/template/`](../packs/template).

## 2. Pack layout

```
<pack>/                      folder or .zip (the root may sit one folder deep inside the zip)
  emberlight.json            required: the manifest (§3). Its presence makes the pack a native pack.
  shaders/**                 GLSL sources (any names and subfolders, see the reserved names below)
  textures/**                custom textures (§6.3), PNG (+ optional .mcmeta) or raw
  lang/<code>.lang           translations (§10.5), e.g. lang/en_us.lang, lang/de_de.lang
  pack.png                   optional icon (shown in the pack list in a later version)
```

File names under `shaders/` are free (Iris names such as `composite.fsh` or `shaders.properties` mean nothing in a
native pack; the reserved-path rule of the first 1.x builds is gone). Paths are case-sensitive, use `/`, and must not
contain `..`.

## 3. emberlight.json — top level

| Key | Type | Required | Meaning |
|---|---|---|---|
| `format` | int | yes | Format version of this file. This document describes `1`. |
| `id` | string | yes | `[a-z0-9_-]{1,64}`. Stable identifier (settings, caches). |
| `name` | string | yes | Display name. |
| `version` | string | yes | Pack version, free text (semver recommended). |
| `authors`, `description`, `license`, `website` | string / string[] | no | Shown in the pack list. |
| `requires` | string[] | no | Device features the pack needs: `ray_query` (§7.6). A pack that requires a missing feature does not load and says why. |
| `settings` | object | no | Engine settings (§11). |
| `images` | object | no | Named render images (§6.1). |
| `buffers` | object | no | Named storage buffers (§6.2). |
| `textures` | object | no | Named textures from pack files (§6.3). |
| `shadow` | object | no | Shadow map (§6.4). Without it, no shadow map is rendered. |
| `passes` | array | yes | The render graph (§4, §5). |
| `uniforms` | object | no | Custom uniforms computed per frame (§9). |
| `options` | array | no | User options (§10). |
| `pages` | array | no | Option pages (§10.2). |
| `profiles` | object | no | Option presets (§10.3). |
| `materials` | object | no | Block / item / entity id maps (§12). |
| `dimensions` | object | no | Per-dimension overrides (§13). |

Unknown top-level keys are an error (a typo should not silently do nothing). Keys starting with `x-` are ignored, for
tools and comments.

## 4. The frame

Minecraft decides **when** its geometry is drawn; a pack decides **how** it is drawn and what runs between the draws.
A frame is a fixed sequence of **phases**. Geometry passes attach to the phase of their geometry class; fullscreen and
compute passes name the phase they run in. Within a phase, passes run in the order of the `passes` array.

| # | Phase | Pass kinds | What happens |
|---|---|---|---|
| 0 | `setup` | compute | Once after load, resize and option change (allocation-time work: LUTs, noise). |
| 1 | `begin` | fullscreen, compute | Start of the frame, before any geometry. |
| 2 | `shadow` | geometry (`shadow*` classes) | Shadow map rendering (only with a `shadow` section). |
| 3 | `shadowcomp` | fullscreen, compute | After the shadow map, before the camera view. |
| 4 | `prepare` | fullscreen, compute | Before opaque geometry (depth is still clear). |
| 5 | `opaque` | geometry (sky, terrain solid/cutout, entities, block entities, particles, …, then `hand`) | The opaque gbuffer; the opaque hand is drawn last, after `el_depthNoHand` is taken. |
| 6 | `deferred` | fullscreen, compute | After opaque geometry, before translucents: deferred lighting goes here. |
| 7 | `translucent` | geometry (`*_translucent`, `water`, `hand_translucent`, `weather`, …) | Forward-rendered translucents and the translucent hand. |
| 8 | `composite` | fullscreen, compute | Post-processing. |
| — | *upscale* | (Emberlight) | DLSS / FSR / XeSS, when enabled (§14). |
| 9 | `final` | final | Exactly one pass; writes the display image. |
| — | *frame generation, HDR output, GUI* | (Emberlight) | §14. |

Ordering rule: phase first, then array order. Two passes that write the same image run in that order; a pass that reads
an image sees everything written to it earlier in the frame (§6.1 "Reads, writes and history").

## 5. Passes

Common keys:

| Key | Type | Meaning |
|---|---|---|
| `name` | string | `[a-z][a-z0-9_]{0,47}`, unique. Used in error messages, `EL_PASS_<name>` macro, profiler. |
| `kind` | `geometry` \| `fullscreen` \| `compute` \| `final` | |
| `phase` | string | Fullscreen/compute only (§4). Geometry passes take it from their classes; `final` is `final`. |
| `vertex`, `fragment`, `geometryShader`, `compute` | path | Stage sources (§7). `vertex` is optional for fullscreen/final passes (Emberlight's fullscreen triangle, which passes `el_uv`). |
| `writes` | string[] | Images written as colour attachments, in output-location order (geometry, fullscreen, final: not allowed for final). Compute: the images/buffers it stores to (documentation + validation). |
| `defines` | object | Extra `#define NAME value` for this pass's stages. |
| `enabled` | string | Condition over options (§10.4). A disabled pass is removed from the frame. |
| `raytracing` | bool | The pass uses ray queries (§7.6). Requires `"requires": ["ray_query"]`. |

### 5.1 Geometry passes

| Key | Meaning |
|---|---|
| `classes` | Geometry classes this pass draws (table below). One pass may draw many classes; one class may be drawn by at most one pass (per dimension). |
| `blend` | Default: Minecraft's own blending for each class (off for opaque, alpha for translucent, additive for glint). `off`, `alpha`, `additive`, `premultiplied`, `{ "src": "...", "dst": "...", "srcAlpha": "...", "dstAlpha": "..." }` with factors `zero one src_color one_minus_src_color dst_color one_minus_dst_color src_alpha one_minus_src_alpha dst_alpha one_minus_dst_alpha`; or an object `{ "<image>": <blend>, "default": <blend> }` per written image. |
| `alphaCutoff` | float, or `false`. Default: `0.1` for cutout-like classes, off otherwise. Read in shaders with `el_alphaCutoff()`; Emberlight does not discard for you. |

Geometry classes, their phase and their fallback (a class without a pass is drawn by its fallback's pass; a chain that
ends without a pass is **not drawn**):

| Class | Phase | Fallback | What it is |
|---|---|---|---|
| `basic` | opaque | — | Untextured lines and coloured shapes (selection outline, debug, leash). |
| `lines` | opaque | `basic` | Line primitives (block outline). |
| `textured` | opaque | `basic` | Textured, unlit (vignette-free GUI-in-world, item frames' maps …). |
| `textured_lit` | opaque | `textured` | Textured + lightmap catch-all. |
| `sky` | opaque | `basic` | Sky dome, horizon, stars. |
| `sky_textured` | opaque | `textured` | Sun, moon, end sky. |
| `clouds` | opaque | `textured` | Vanilla clouds (`settings.clouds` decides whether they exist). |
| `terrain_solid` | opaque | `textured_lit` | Opaque chunk geometry. |
| `terrain_cutout` | opaque | `terrain_solid` | Alpha-tested chunk geometry (leaves, grass, glass panes' frames). |
| `damage` | opaque | `terrain_solid` | Block-breaking overlay. |
| `block_entities` | opaque | `terrain_solid` | Chests, signs, banners, beds … |
| `block_entities_translucent` | translucent | `block_entities` | Translucent block entities. |
| `beacon` | opaque | `textured` | Beacon beam. |
| `entities` | opaque | `textured_lit` | Mobs, players, armour stands, items on the ground. |
| `entities_translucent` | translucent | `entities` | Translucent entity layers (slime outer, …). |
| `entities_glowing` | opaque | `entities` | Entities with the glowing effect. |
| `lightning` | opaque | `entities` | Lightning bolts. |
| `glint` | opaque | `textured` | Enchantment glint (blended additively by default). |
| `emissive_eyes` | opaque | `textured` | Spider/enderman/phantom eyes. |
| `particles` | opaque | `textured_lit` | Opaque particles. |
| `particles_translucent` | translucent | `particles` | Translucent particles. |
| `weather` | translucent | `textured_lit` | Rain and snow. |
| `terrain_translucent` | translucent | `terrain_solid` | Water, stained glass, ice, slime, honey. |
| `hand` | opaque | `textured_lit` | First-person hand and held items (opaque part), drawn before `deferred`: a pass that writes an image the deferred phase overwrites loses it. |
| `hand_translucent` | translucent | `hand` | Translucent held items. |
| `shadow` | shadow | — | Everything in the shadow map not matched below. |
| `shadow_solid`, `shadow_cutout`, `shadow_translucent` | shadow | `shadow` | Terrain layers into the shadow map. |
| `shadow_entities`, `shadow_block_entities`, `shadow_lightning` | shadow | `shadow` (`shadow_lightning` → `shadow_entities`) | Entities into the shadow map. |

Translucent classes are drawn after the `deferred` phase, sorted back to front by Minecraft. Hand classes are drawn last
with their own projection (`EL_CLASS_HAND` is defined; use `el_project`, which uses the right matrix).

### 5.2 Fullscreen and final passes

| Key | Meaning |
|---|---|
| `scale` | Viewport scale (0 < s ≤ 1), default 1: renders into the top-left `s × size` of the written images (half-res effects). |
| `mipmaps` | Images whose mip chain is generated right before this pass (the image needs `"mipmaps": true`). |

The `final` pass writes the display image at location 0 (`vec4`, display-referred, sRGB-encoded 0–1 in SDR; §14.3 for
HDR). It runs at **output resolution** even when upscaling (§14.1).

### 5.3 Compute passes

| Key | Meaning |
|---|---|
| `workgroups` | `{ "size": [x, y, z] }` — the local size, emitted as `layout(local_size_x = …) in;` (do not declare it yourself) — plus either `"count": [x, y, z]` (fixed dispatch) or `"scale": [sx, sy]` (dispatch covers `scale × render size` pixels: `ceil(w·sx / x) × ceil(h·sy / y) × 1`). Default `{ "size": [8, 8, 1], "scale": [1, 1] }`. |

Compute passes write through storage images (`<image>_img`, §7.3) and buffers; they have no colour attachments.

`"raygen": true` (compute passes with `"raytracing": true`, experimental): on NVIDIA GPUs with RT pipelines
(VK_KHR_ray_tracing_pipeline, setting `rt.raygen`, default on) the pass is compiled as a ray generation shader and run as
a raygen-only RT pipeline instead of a compute dispatch: the same code, one invocation per `workgroups` invocation, the
traces stay ray queries. The Emberlight RTX GI pass took up to 0.2 ms (30 %) less GPU time that way; a light pass like
its ReSTIR candidates got slower, so measure. Elsewhere it runs as compute.
Write the pass so it works both ways: use `EL_GLOBAL_ID` instead of `gl_GlobalInvocationID` (defined in every compute
pass), and no `shared` memory, barriers, workgroup or subgroup built-ins (`EL_STAGE_RAYGEN` is defined in the raygen
build).

## 6. Resources

### 6.1 Images

```jsonc
"images": {
  "gAlbedo":   { "format": "RGBA8" },
  "gNormal":   { "format": "RGBA16F", "clear": [0, 0, 0, 0] },
  "lit":       { "format": "RGBA16F", "mipmaps": true },
  "taa":       { "format": "RGBA16F", "history": true },
  "bloomHalf": { "format": "R11F_G11F_B10F", "scale": 0.5 },
  "voxels":    { "format": "R32UI", "size": [256, 128, 256], "storage": true }
}
```

| Key | Default | Meaning |
|---|---|---|
| `format` | — | `R8 RG8 RGBA8 R8_SNORM RG8_SNORM RGBA8_SNORM R16 RG16 RGBA16 R16_SNORM RG16_SNORM RGBA16_SNORM R16F RG16F RGBA16F R32F RG32F RGBA32F R8I RG8I RGBA8I R8UI RG8UI RGBA8UI R16I RG16I RGBA16I R16UI RG16UI RGBA16UI R32I RG32I RGBA32I R32UI RG32UI RGBA32UI RGB10_A2 R11F_G11F_B10F`. Three-channel formats other than these two do not exist on most GPUs; use RGBA. |
| `scale` | `1.0` | Size relative to the render resolution: a number or `[sx, sy]`. |
| `size` | — | Absolute `[w, h]` or `[w, h, d]` (3D). Excludes `scale`, `mipmaps` and `history`. |
| `clear` | `[0,0,0,0]` | Clear value at the start of every frame; `false` = never cleared. |
| `history` | `false` | Keep the contents across frames (implies `clear: false`). |
| `mipmaps` | `false` | Allocate a full mip chain; a pass lists it in `mipmaps` to regenerate it. |
| `storage` | `false` | Usable as a storage image in compute passes (`<name>_img`). Screen-sized images are storage-capable anyway; `size` images must set it. |

Limits in format 1: at most **16 screen-sized images** (`scale`) and 8 absolute-size images per pack. Every pass kind
can sample all of them (the first 1.x builds limited geometry passes to the first 12).

Built-in images every pass may sample (read-only):

| Name | Type | Content |
|---|---|---|
| `el_depth` | `sampler2D` | Depth of everything drawn so far (opaque + translucent + hand once drawn). |
| `el_depthOpaque` | `sampler2D` | Depth before translucents. |
| `el_depthNoHand` | `sampler2D` | Depth before the hand. |
| `el_shadowDepth`, `el_shadowDepthOpaque` | `sampler2D` | Shadow map depth (all / without translucents). Only with `shadow`. |
| `el_shadowColor0`, `el_shadowColor1` | `sampler2D` | Shadow colour images declared in `shadow.images` (§6.4). |
| `el_noise` | `sampler2D` | 256² RGBA8 white noise, repeat (or the pack's `textures.noise`). |

Depth convention: depth images hold window depth in `[0, 1]` with **near = 0, far = 1** for the projection in the frame
block (`el_projection`), not reversed-Z. `el_screenToView` / `el_screenToWorld` (§7.4) undo it.

**Reads, writes and history.** A pass samples any declared image by its name (`uniform` declarations are generated, §7).
What it sees:

* an image written earlier in this frame: the latest written value;
* an image not yet written this frame: its clear value, or, for `history`/`clear: false` images, the value from the end
  of the previous frame;
* an image the **same** fullscreen/final pass also writes: the value before the pass (Emberlight ping-pongs between two
  physical images; reading and writing one image in one pass is how you do TAA, accumulation and blur chains). Do not
  rely on which physical image that is.
* Geometry passes must not sample an image they write (the result is undefined; not checked in format 1). Compute passes that both sample and store one image see
  their own stores only through the storage view (no ordering between invocations — it is plain Vulkan).

### 6.2 Buffers

```jsonc
"buffers": { "histogram": { "bytes": 1024 }, "probes": { "bytesPerPixel": 16 } }
```

`bytes` (fixed) or `bytesPerPixel` (× render resolution). Zero-initialised once; never cleared afterwards. Declared in GLSL as
`layout(std430, binding = EL_BUFFER_<name>) buffer <Block> { … };` (the macro is defined for every stage). Total ≤ 1 GiB.

### 6.3 Textures

```jsonc
"textures": {
  "blueNoise": { "file": "textures/bluenoise.png", "filter": "nearest", "wrap": "repeat" },
  "lut":       { "file": "textures/lut.bin", "raw": { "format": "RGBA16F", "size": [32, 32, 32], "pixel": "RGBA HALF_FLOAT" } },
  "atlas":     { "resource": "minecraft:textures/atlas/blocks.png" }
}
```

PNG files (filter/wrap default `linear`/`repeat`; a `<file>.mcmeta` with `texture.blur`/`texture.clamp` also works),
raw texel files (`pixel` = GL pixel format + type, e.g. `RGB FLOAT`), or Minecraft resources resolved at runtime.
Sampled in every stage as `uniform sampler2D <name>` (or `sampler3D` for 3D raw textures, generated). The name
`noise` replaces `el_noise`.

### 6.4 Shadow map

```jsonc
"shadow": {
  "resolution": 2048, "distance": 160, "sunPathRotation": -30.0, "renderDistanceMultiplier": 1.0,
  "entityDistanceMultiplier": 1.0, "hardwareFiltering": true,
  "images": { "shadowTint": { "format": "RGBA8", "clear": [1, 1, 1, 1] } }   // at most 2: el_shadowColor0/1
}
```

The shadow camera follows the sun/moon (`el_shadowView`, `el_shadowProjection`). Shadow geometry passes write
`shadow.images` in their `writes`. Ray-traced shadows (§7.6) need no shadow map at all.

## 7. Shaders

### 7.1 Stage files and what Emberlight prepends

Stage files are GLSL **460** bodies without a `#version` line. Before compiling a stage, Emberlight prepends:

```glsl
#version 460
#define EL_STAGE_VERTEX 1             // | EL_STAGE_FRAGMENT | EL_STAGE_GEOMETRY | EL_STAGE_COMPUTE
#define EL_FORMAT 1
#define EL_PASS_<name> 1
#define EL_KIND_GEOMETRY 1            // | EL_KIND_FULLSCREEN | EL_KIND_COMPUTE | EL_KIND_FINAL
#define EL_PHASE_<PHASE> 1
#define EL_DIMENSION_<NAME> 1         // §13: EL_DIMENSION_DEFAULT or the override's name in upper case
#define EL_CLASS_<CLASS> 1            // geometry passes: the class being compiled (one variant per class)
#define EL_ALPHA_CUTOFF 0.1           // geometry passes (§5.1)
#define EL_UPSCALING 1                // only while DLSS/FSR/XeSS is active (§14.1)
#define EL_RR_ACTIVE 1                // only while DLSS Ray Reconstruction runs on the pack's guides (§14.4)
#define EL_RAYTRACING 1               // only in passes with "raytracing": true (also EL_LIGHTS)
#define EL_SHADOW_MAP 1               // only with a "shadow" section
// every option value (§10), the dimension override's and the pass's "defines"
// EL_OUT_<image> = output location, EL_BUFFER_<name> = buffer binding
// the frame block (§8), image / texture / built-in image declarations with their bindings (§7.3)
#include "/emberlight.glsl"           // the header library (§7.4)
```

The stage is compiled as written (GLSL 460, Vulkan rules, `GL_GOOGLE_cpp_style_line_directive`), with two rewrites that
keep every line where it is: varyings get their `layout(location)` (§7.2), and a pack's own `uniform <type> <name>;` of a
name the prelude already declares (a custom uniform, a frame member, an image) is dropped, so declaring them is allowed
but not needed. Nothing else in the pack's source is changed.

`#include "/path"` is relative to `shaders/` (so `#include "/lib/common.glsl"` is `shaders/lib/common.glsl`);
`#include "file.glsl"` is relative to the including file. Includes are expanded textually with `#line` tracking, so
errors point at the included file. Use `#ifndef` include guards.

Your own `#define`s are plain preprocessor macros; only the manifest's `options` are options (no source scanning).

### 7.2 Varyings and outputs

Declare varyings with `out`/`in` and the same name in consecutive stages; locations are assigned for you by name, one
table per pass, so the order of the declarations does not matter (explicit `layout(location)` on varyings is allowed
but not needed). Assignment works on global declarations with one variable per line (`flat out vec3 normal;`,
`out vec2 uv[4];`); interface blocks and `out vec2 a, b;` need an explicit `layout(location = …)`. Fragment outputs use
`layout(location = EL_OUT_<image>) out <type> <anything>;` where the location is the image's index in `writes`. The
output type follows the image format (`vec4`, `uvec4` for `*UI`, `ivec4` for `*I`).

### 7.3 Resource declarations (generated)

For every stage Emberlight declares:

* each image as `uniform sampler2D|usampler2D|isampler2D|sampler3D <name>;` (by format and dimensionality, with its
  set-1 binding),
* in compute stages, each storage-capable image as `layout(<format>) uniform image2D|uimage2D|image3D … <name>_img;`,
* each texture as `uniform sampler2D|sampler3D <name>;`,
* `EL_OUT_<image>` and `EL_BUFFER_<name>` macros. `EL_BUFFER_<name>` expands to `<binding>, set = 1`, for use inside
  `layout(std430, binding = EL_BUFFER_<name>)` only.

Image, texture and buffer names are global identifiers in every shader of the pack: do not reuse them for variables.
Unused declarations cost nothing (they are stripped before SPIR-V).

### 7.4 The header library `emberlight.glsl`

Always included. Everything in it is part of the format-1 contract (names, types, meaning). Members are `el_` names;
the frame data also keeps the long-standing Iris names (`gbufferModelView`, `cameraPosition`, `frameTimeCounter`,
`rainStrength`, …) because pack authors already know them — both refer to the same block member.

Frame data (one std140 block, §8), selection:

| Name | Type | Meaning |
|---|---|---|
| `el_view`, `el_viewInverse` | mat4 | World (camera-relative, "player space") → view. |
| `el_projection`, `el_projectionInverse` | mat4 | View → clip, including upscaler jitter. |
| `el_prevView`, `el_prevProjection` | mat4 | Last frame's. |
| `el_shadowView`, `el_shadowProjection` (+ `Inverse`) | mat4 | Shadow camera. |
| `el_cameraPosition`, `el_prevCameraPosition` | vec3 | World position of the camera (float). |
| `el_cameraBlock`, `el_cameraFract` | ivec3 / vec3 | Exact split of the camera position for large coordinates. |
| `el_eyePosition` | vec3 | Player eye in world space (≠ camera in third person). |
| `el_screenSize`, `el_texelSize` | vec2 | Render resolution in pixels and its reciprocal. |
| `el_frame`, `el_time`, `el_frameTime` | int / float / float | Frame counter, seconds since start (wraps after 3600 s), last frame duration. |
| `el_worldTime`, `el_worldDay`, `el_moonPhase` | int | Day time in ticks (0–23999), day counter, moon phase. |
| `el_sunDirection`, `el_moonDirection`, `el_lightDirection` | vec3 | Unit vectors in **world** space (the light is the sun by day, the moon by night). |
| `el_sunAngle` | float | 0–1 over a day (0.25 = noon). |
| `el_rain`, `el_thunder`, `el_wetness` | float | Smoothed rain and thunder strength, wetness. |
| `el_skyColor`, `el_fogColor` | vec3 | Vanilla sky and fog colour. |
| `el_eyeInWater` | int | 0 air, 1 water, 2 lava, 3 powder snow. |
| `el_eyeBrightness` | vec2 | Smoothed block / sky light at the eye, 0–1. |
| `el_nightVision`, `el_blindness`, `el_darkness` | float | Effects. |
| `el_near`, `el_far` | float | Clip planes (far = render distance in blocks). |
| `el_hasSkylight`, `el_hasCeiling` | bool | Dimension properties. |
| `el_heldLight`, `el_heldLight2` | int | Light level of the item in the main / off hand. |
| `el_heldLightColor`, `el_heldLightColor2` | vec3 | Its colour. |

The complete member list is in §8.

Helper functions:

| Function | Meaning |
|---|---|
| `vec3 el_screenToView(vec2 uv, float depth)` | Reconstruct a view-space position from a depth sample. |
| `vec3 el_viewToWorld(vec3 view)` / `vec3 el_worldToView(vec3 world)` | Camera-relative world ↔ view. |
| `vec3 el_screenToWorld(vec2 uv, float depth)` | Both in one. |
| `vec2 el_worldToPrevScreen(vec3 world)` | Reprojection into last frame's UV (for history). |
| `vec4 el_project(vec3 view)` | View → clip with the right projection (hand classes included). |
| `float el_linearDepth(float depth)` | Window depth → view distance. |
| `bool el_isSky(float depth)` | `depth >= 1.0`. |
| `vec3 el_srgbToLinear(vec3)`, `vec3 el_linearToSrgb(vec3)`, `float el_luminance(vec3)` | Colour helpers. |
| `vec4 el_blueNoise(ivec2 pixel)` | Per-frame shifted noise from `el_noise`. |
| `vec2 el_encodeNormal(vec3)`, `vec3 el_decodeNormal(vec2)` | Octahedral normal packing (RG16 / RG16F friendly). |

Geometry-pass vertex inputs and per-draw data (vertex stage of geometry passes):

| Function / name | Meaning |
|---|---|
| `vec3 el_vertexPosition()` | Model-space position (chunk / entity local). |
| `vec3 el_vertexViewPosition()` | View-space position (model-view applied). |
| `vec3 el_vertexWorldPosition()` | Camera-relative world position. |
| `vec4 el_vertexColor()` | Vertex colour (biome tint; with `settings.separateAo` the AO is in `a`). |
| `vec2 el_vertexUV()` | Atlas UV. |
| `vec2 el_vertexLight()` | Lightmap (block, sky) in 0–1. |
| `vec3 el_vertexNormal()` / `vec3 el_vertexViewNormal()` | Normal in model / view space. |
| `vec4 el_vertexTangent()` | Tangent, `w` = bitangent sign (LabPBR normal maps). |
| `int el_blockId()` | Material id from `materials.blocks` (§12), terrain only; `-1` otherwise. |
| `vec2 el_midUV()` | Centre of the polygon's uv in the atlas: the average of its vertices' uv (Iris's `mc_midTexCoord`). That is the sprite centre only for faces that cover their whole sprite; on a slab side or a stair step it lies off it, so do not derive sprite bounds from it alone (`emberlight-rtx`'s POM places the sprite from the block position instead). `vec2(0)` for geometry without polygon data. |
| `vec3 el_midBlock()` | Offset from the vertex to the block centre, in blocks. |
| `float el_vertexEmission()` | Block light emission of the block (0–15). |
| `int el_entityId()`, `int el_blockEntityId()`, `int el_itemId()` | Ids from §12 for the current draw (`-1` = none). |
| `vec4 el_entityColor()` | Hurt / creeper flash overlay colour. |

Fragment stage of geometry passes:

| Function | Meaning |
|---|---|
| `vec4 el_albedo(vec2 uv)` | Base colour texture of the draw (block atlas, entity texture, …). |
| `vec4 el_normalMap(vec2 uv)` | LabPBR `_n` texture: `xy` normal, `z` AO, `w` height. Neutral `(0.5, 0.5, 1, 1)` without a resource pack. |
| `vec4 el_specularMap(vec2 uv)` | LabPBR `_s` texture: `r` smoothness, `g` F0 / metal id, `b` porosity / SSS, `a` emission. Zero without a resource pack. |
| `vec4 el_albedoGrad(vec2 uv, vec2 dx, vec2 dy)`, `el_normalMapGrad`, `el_specularMapGrad` | The same three textures with explicit uv gradients (`textureGrad`): for uv computed in a loop or wrapped inside a sprite, as in parallax occlusion mapping, where implicit derivatives are undefined or jump. Since `EL_HEADER_VERSION` 3. |
| `vec2 el_lightmapUV(vec2 light)` / `vec3 el_vanillaLight(vec2 light)` | Vanilla lightmap lookup (for packs that want it). |
| `float el_alphaCutoff()` | The pass's `alphaCutoff` (0 if off). |
| `el_material(vec4 specular)` | Decodes LabPBR into `ElMaterial { float roughness; vec3 f0; bool metal; float emission; float porosity; float sss; }`. |

### 7.5 Fullscreen passes

`el_uv` (vec2, 0–1) is available as an input in fragment stages of fullscreen and final passes without declaring it
(an `in vec2 el_uv;` declaration is accepted).
It follows the GL convention like everything else in the format: `(0, 0)` is the bottom-left corner, `el_uv ==
gl_FragCoord.xy / el_screenSize`, and `texelFetch(image, ivec2(gl_FragCoord.xy), 0)` reads the pixel under the fragment.
A pass with its own `vertex` stage must write `out vec2 el_uv;` itself if its fragment stage reads it.

### 7.6 Ray queries

Passes with `"raytracing": true` get `GL_EXT_ray_query` and the scene:

| Name | Meaning |
|---|---|
| `el_tlas` | `accelerationStructureEXT`: every terrain section (BLAS per section and layer) and, when the pack sets `settings.rayTracedEntities`, entities. Rebuilt every frame. |
| instance space | **Camera-relative world** (player space): the ray origin of a pixel is `el_screenToWorld(uv, depth)`, no conversion. |
| masks | `EL_MASK_OPAQUE` (0x01, solid + cutout terrain), `EL_MASK_TRANSLUCENT` (0x02, water, glass), `EL_MASK_ENTITIES` (0x04), `EL_MASK_PLAYER` (0x08, the local player, also in first person), `EL_MASK_ALL`. |
| `bool el_traceVisibility(vec3 origin, vec3 dir, float tMax, uint mask)` | Any-hit visibility with alpha-tested cutouts (leaves, grass). 1 query, up to 32 candidate tests. |
| `ElHit el_traceClosest(vec3 origin, vec3 dir, float tMax, uint mask)` | Closest hit: `ElHit { bool hit; float t; vec3 position; vec3 normal; vec2 uv; int blockId; vec4 color; vec2 light; bool entity; bool entityAtlas; }`, alpha-tested; `normal` is the geometric normal facing the ray, `color` the interpolated vertex colour, `light` the lightmap (0–1). `uv` addresses the RT entity atlas when `entityAtlas`, else the block atlas (`el_atlas`): terrain, and entities drawn from the block atlas (dropped and falling blocks, `entity` true) keep block-atlas uv and their LabPBR maps (`el_hitAlbedo(hit)` and `el_hitSpecular(hit)` sample the right one). |
| `ElHit el_traceSurface(vec3 origin, vec3 dir, float tMax, uint mask)` | `el_traceClosest` without the alpha test: every triangle counts, so the clear centre of a glass block (alpha 0) is still its face. For the thickness of translucent blocks and refraction through them; with `EL_MASK_OPAQUE` cutout leaves and grass count as solid. Since `EL_HEADER_VERSION` 3. |
| `el_atlas`, `vec4 el_hitAlbedo(ElHit)` | Base colour at a hit. |
| `vec4 el_hitSpecular(ElHit)`, `vec4 el_hitNormalMap(ElHit)`, `ElMaterial el_hitMaterial(ElHit)` | LabPBR maps at a hit: the `_s` / `_n` texel of the active resource pack at `hit.uv`, read from the block atlas's PBR atlases (same layout as `el_atlas`; the maps the gbuffer reads with `el_specularMap` / `el_normalMap`), nearest, mip 0. `el_hitMaterial` decodes the specular texel like `el_material`. Entities, sprites without maps and worlds without a LabPBR pack read the LabPBR defaults: specular `vec4(0)` (test `!= vec4(0.0)` to fall back to your own table), normal `(0.5, 0.5, 1, 1)`. Lets blocks that glow in the resource pack but are no light source (lightEmission 0, so not in the RT light list) light their surroundings through GI rays that hit them, and shows their material in reflections. Since `EL_HEADER_VERSION` 2. |
| low level | `rayQueryEXT` with `el_tlas` directly; per-instance geometry through `el_hitVertices(customIndex)` and the vertex accessors documented in `emberlight.glsl` (positions, UVs, colours, light, normals, block ids). |

RT light list (passes with `"raytracing": true`; `EL_LIGHTS` is defined): every block light source near the camera
(experimental in format 1): `el_lightCount()`, `el_light(i)` → `ElLight { vec3 position; vec3 color; float range;
float radius; }` in player space, plus a spatial grid for ReSTIR-style candidate sampling: `uvec2 r = el_lightCell(p)`
is the index range `[r.x, r.y)` and `el_lightIndex(k)` the light of entry k. The grid covers 128 blocks around the
camera (16³ cells of 8 blocks) and is updated every frame (§16). `int el_lightFromPrev(int i)` maps last frame's light index to this
frame's (-1 = that source is gone), so reservoirs that store a light index survive list rebuilds (temporal reuse); since
`EL_HEADER_VERSION` 4.

### 7.7 Restrictions

Ray queries are for fullscreen, final and compute passes in format 1 (not geometry passes). `gl_FragDepth` writes are
allowed in geometry passes only (they break early-Z: §16). Tessellation stages are not part of format 1.

## 8. The frame block

All frame data lives in **one std140 uniform block** (set 1, binding 0), written once per frame; per-draw data
(entity ids, entity colour, alpha cutoff) is a push-constant block, per-phase data (projection, render stage) a second
small block. Shaders never declare these blocks: they use the names from §7.4, which `emberlight.glsl` declares.

Layout rule (stable for a given format version): the block holds **every** frame member of the header, always, in
this order: 16-byte-aligned members (matrices, `vec4`, arrays) sorted by name; then each `vec3` sorted by name, each
followed by the next 4-byte scalar (sorted by name) that fills its tail; then 8-byte members (`vec2`, `ivec2`) sorted
by name; then the remaining scalars. Pack custom uniforms (§9) are merged into the same sorting, so they can shift
offsets after them — tools that need offsets read them from the compiled manifest (`uniforms.members` with
`offset`). `bool` is a 4-byte int. The member list (name, type) is the format-1 contract; adding a member is a minor
version (§17).

Format-1 members (the `el_` names of §7.4 are macros over these; `emberlight.glsl` is the reference):

| Type | Members |
|---|---|
| `mat4` | `gbufferModelView`, `gbufferModelViewInverse`, `gbufferPreviousModelView`, `gbufferProjection`, `gbufferProjectionInverse`, `gbufferPreviousProjection`, `shadowModelView`, `shadowModelViewInverse`, `shadowProjection`, `shadowProjectionInverse` |
| `vec3` | `cameraPosition`, `previousCameraPosition`, `cameraPositionFract`, `previousCameraPositionFract`, `eyePosition`, `relativeEyePosition`, `playerLookVector`, `sunPosition`, `moonPosition`, `shadowLightPosition`, `upPosition`, `skyColor`, `fogColor`, `heldBlockLightColor`, `heldBlockLightColor2` |
| `ivec3` | `cameraPositionInt`, `previousCameraPositionInt` |
| `ivec2` | `eyeBrightness`, `eyeBrightnessSmooth` |
| `float` | `near`, `far`, `viewWidth`, `viewHeight`, `aspectRatio`, `frameTime`, `frameTimeCounter`, `sunAngle`, `shadowAngle`, `rainStrength`, `wetness`, `thunderStrength`, `nightVision`, `blindness`, `darknessFactor`, `darknessLightFactor`, `screenBrightness`, `eyeAltitude`, `cloudHeight`, `temperature`, `rainfall`, `ambientLight` |
| `int` | `frameCounter`, `worldTime`, `worldDay`, `moonPhase`, `isEyeInWater`, `heldItemId`, `heldItemId2`, `heldBlockLightValue`, `heldBlockLightValue2`, `biome`, `bedrockLevel`, `heightLimit`, `seaLevel` |
| `bool` | `hasSkylight`, `hasCeiling`, `is_sneaking`, `is_sprinting`, `is_on_ground`, `isSpectator`, `firstPersonCamera`, `hideGUI` |


## 9. Custom uniforms

```jsonc
"uniforms": {
  "sunVisibility": { "type": "float", "expr": "clamp(dot(sunPosition, upPosition) * 0.01 + 0.5, 0.0, 1.0)" },
  "smoothRain":    { "type": "float", "expr": "smooth(rainStrength, 5.0, 2.0)" },
  "dayPhase":      { "type": "int", "expr": "worldTime / 6000", "variable": true }
}
```

Evaluated on the CPU every frame in declaration order, after the built-ins. Expressions use the custom-uniform language
Iris packs use (Emberlight's shared evaluator): arithmetic, comparisons, `&& || !`, `vec2/3/4(…)`, swizzles, GLSL math
functions (`sin cos tan asin acos atan pow exp exp2 log log2 sqrt abs sign floor ceil frac fmod min max clamp mix`; not
`step`, `smoothstep`, `fract`, `length` or `dot`: a uniform using them is not created, so shaders then fail with an
undeclared identifier),
`if(cond, a, b)`, `between`, `equals` and `smooth(value, up, down)`. Inputs: the frame members (by their Iris names),
int/float/enum option values (substituted as constants), earlier custom uniforms. `variable: true` = helper only, not
visible to shaders. Types: `bool int float vec2 vec3 vec4`. Custom uniforms are members of the frame block, so a shader
reads them by name; a declaration such as `uniform float sunVisibility;` is accepted (and dropped) for readability.

## 10. Options

### 10.1 Declaring options

```jsonc
"options": [
  { "name": "SHADOW_RAYS", "type": "int", "default": 1, "min": 1, "max": 8, "step": 1 },
  { "name": "EXPOSURE", "type": "float", "default": 1.0, "min": 0.25, "max": 4.0, "step": 0.25, "slider": true },
  { "name": "RT_SHADOWS", "type": "bool", "default": true },
  { "name": "TONEMAP", "type": "enum", "default": "aces", "values": ["aces", "reinhard", "none"] },
  { "name": "SUN_TINT", "type": "color", "default": [1.0, 0.95, 0.85] }
]
```

| Type | GLSL | Notes |
|---|---|---|
| `bool` | `#define NAME 1` when on, undefined when off: test with `#ifdef NAME` (`#if NAME` works too) | |
| `int` | `#define NAME <int>` | `min`, `max`, `step` (default 1) or an explicit `values` list. |
| `float` | `#define NAME <float>` | `min`, `max`, `step` or `values`. At most 256 values. |
| `enum` | `#define NAME <index>` and `#define NAME_<VALUE> <index>` for every value | Compare with `#if TONEMAP == TONEMAP_ACES`. Value names `[a-z0-9_]`. |
| `color` | `#define NAME vec3(r, g, b)` | Format 1 GUI: three sliders `NAME_R`, `NAME_G`, `NAME_B` (0–1, step 0.05, plus the default). |

Common keys: `slider` (bool, show a slider instead of a value button), `label`, `comment` (English text; translations
§10.5 override them), `restart` (bool, reserved: accepted, no effect in format 1).
Names are `[A-Z][A-Z0-9_]*`, unique, and must not start with `EL_`.

Option values are compile-time constants: changing an option recompiles the pack in the background (the old version
renders meanwhile; §15). For values you want to tweak without recompiling, use custom uniforms (§9) driven by
options.

### 10.2 Pages

```jsonc
"pages": [
  { "name": "main", "columns": 2, "items": ["<profile>", "RT_SHADOWS", "SHADOW_RAYS", "<empty>", "[lighting]"] },
  { "name": "lighting", "items": ["EXPOSURE", "SUN_TINT", "TONEMAP"] }
]
```

The first page is the root of the options screen. Items: an option name, `[page]` (a button opening another page),
`<profile>` (the profile selector), `<empty>` (a gap). Options on no page are still saved and settable by profiles
but not shown (a warning). Without `pages`, every option is listed on one page in declaration order.

### 10.3 Profiles

```jsonc
"profiles": {
  "low":    { "SHADOW_RAYS": 1, "RT_SHADOWS": false },
  "high":   { "SHADOW_RAYS": 4 },
  "ultra":  { "inherit": "high", "SHADOW_RAYS": 8 }
}
```

A profile sets some options; the rest keep their defaults. The GUI shows the profile whose values all match (or
"Custom").

### 10.4 Conditions

`enabled` on passes (and on `options` entries, to grey them out — later version) is a boolean expression over options:
`RT_SHADOWS && SHADOW_RAYS > 1 && TONEMAP != TONEMAP_NONE`, with `!`, `&&`, `||`, comparisons, parentheses and integer
literals over bool, int and enum options (float and colour options are an error: conditions are evaluated as integers).
A bool option is true when on. Unknown names are an error. Two engine names may be tested as well: `EL_UPSCALING` and
`EL_RR_ACTIVE` (1 while the macro of the same name is defined, §14.1 / §14.4), e.g. `"enabled": "!EL_RR_ACTIVE"` on a
denoiser pass that Ray Reconstruction replaces.

### 10.5 Localisation and the settings file

`lang/<code>.lang` (UTF-8, `key=value`): `option.NAME`, `option.NAME.comment`, `value.NAME.<value>` (enum and
explicit values), `page.<page>`, `profile.<name>`, `pack.name`, `pack.description`. The game language is used, with
`en_us` and then the manifest's `label`/`comment` as fallbacks.

The user's choices are stored next to the pack as `<pack folder or zip name>.txt` (`NAME=value` lines, only non-default
values; colours as `NAME_R` …), the same file format Iris uses, so the settings survive pack updates and can be shared.

## 11. Engine settings

```jsonc
"settings": {
  "clouds": "fancy",              // fancy | fast | off: whether vanilla clouds are drawn (class "clouds")
  "sun": true, "moon": true,      // vanilla sun/moon quads (class "sky_textured")
  "stars": true,                  // vanilla stars (class "sky")
  "separateAo": true,             // vertex AO in el_vertexColor().a instead of multiplied into rgb (default true)
  "vanillaDirectionalShade": false, // MC's per-face brightness baked into colours (default false)
  "ambientOcclusion": 1.0,        // strength of MC's baked vertex AO, 0-1
  "rayTracedEntities": false,     // add entities to el_tlas (§7.6), costs a BLAS rebuild per entity per frame
  "underwaterOverlay": false, "vignette": false,  // vanilla overlays drawn over the pack's image
  "rayReconstruction": false      // the pack writes DLSS Ray Reconstruction's guides (§14.4); off: RR falls back to DLSS SR
}
```

The hand is always drawn with its own projection and a compressed depth range (window depth below 0.56), as in Iris.

## 12. Materials (ids)

```jsonc
"materials": {
  "blocks":   { "1": ["minecraft:water", "minecraft:flowing_water"],
                "10": ["#minecraft:leaves", "minecraft:vine"],
                "20": ["minecraft:torch", "minecraft:lantern:hanging=false", "minecraft:glowstone"] },
  "items":    { "20": ["minecraft:torch", "minecraft:lantern"] },
  "entities": { "100": ["minecraft:zombie", "minecraft:husk"] }
}
```

Ids are `1–65535`. Matchers: `namespace:block`, block states `namespace:block:prop=value[:prop=value]`, tags
`#namespace:tag`. First match wins in key order. Unmatched: `-1`. Available as
`el_blockId()` (terrain vertices and ray hits), `el_entityId()`, `el_blockEntityId()`, `el_itemId()` and per material
in the TLAS (`ElHit.blockId`).

## 13. Dimensions

```jsonc
"dimensions": {
  "nether": { "match": ["minecraft:the_nether"], "defines": { "FOG_DENSITY": "0.04" }, "disable": ["sky_pass"] },
  "end":    { "match": ["minecraft:the_end"],    "defines": { "END_SKY": "1" } }
}
```

Every dimension uses the same graph; an override adds `defines`, defines `EL_DIMENSION_<NAME>` (upper case) instead of
`EL_DIMENSION_DEFAULT`, and removes the passes in `disable`. `match` takes dimension ids and globs (`modid:*`). The first
matching override wins; unmatched dimensions (overworld, modded ones) use the default.

## 14. Upscaling, frame generation, HDR

### 14.1 Upscaling (DLSS / FSR / XeSS)

The user enables upscaling in Emberlight's settings; the pack does nothing to opt in, but it must keep the contract:

* **Cut point.** All phases up to `composite` run at the render resolution; the upscaler runs between the last
  `composite` pass and `final`. Its colour input is the image that `final` samples and that the last pass before it
  wrote. Make that image the finished, tonemapped (SDR, display-referred) frame — the upscaler runs in LDR mode.
  `final` runs at output resolution; `el_screenSize` there is the output size.
* **Depth.** `el_depth` must stay the scene depth (Emberlight reads it). Write depth only through geometry passes.
* **Jitter.** Emberlight jitters `el_projection` (Halton 2,3) while upscaling; `el_prevProjection` is last frame's
  jittered matrix. Do not add your own jitter; `EL_UPSCALING` is defined so a pack can **skip its own TAA** (the
  upscaler is the temporal filter).
* **Motion vectors.** Emberlight computes them itself (camera motion from depth + matrices, plus per-vertex object
  motion for entities, block entities and the hand). Vertex animation done in a pack's vertex shader (waving leaves,
  water waves) is not in them; keep such motion small or mask it. Mirrors: a compute pass may write the reserved
  render-size `R32F` storage image `el_motionDistance_img` (list `el_motionDistance` in its `writes`; it does not count
  against the image limits): per pixel the distance along the view ray of what the pixel shows, i.e. for a mirror the
  surface's distance plus the reflection's path length (where the reflected image sits), 0 for "the depth's". The
  motion vectors then follow the reflected image, not the mirror (DLSS smears a mirror's image otherwise). Write every
  pixel every frame. Not used under Ray Reconstruction.
* **Ray Reconstruction** (DLSS RR) is opt-in and needs guide images from the pack, §14.4.

### 14.2 Frame generation

DLSS-FG and FSR-FG work on the final image + depth + motion vectors and need nothing from the pack. The GUI is never
interpolated.

### 14.3 HDR

With HDR output enabled, `final`'s output is still SDR-encoded; Emberlight expands it. Writing scene-linear HDR in
`final` (`settings.hdrOutput`) is planned for 1.x.

### 14.4 Ray Reconstruction (DLSS RR)

DLSS Ray Reconstruction replaces DLSS SR **and the pack's own denoisers**: it takes the noisy ray-traced frame plus guide
images and denoises and upscales in one step ([UPSCALING.md §11](UPSCALING.md)). It runs at the same cut as the upscaler
(§14.1): its colour input is the image `final` samples, in LDR. A pack opts in with `"settings": { "rayReconstruction":
true }` (it then needs `"requires": ["ray_query"]`). While the user has RR on (Upscaling page; DLSS, an RTX GPU; Emberlight's
own RT switch does not matter for a native pack, it traces its own rays), the pack is compiled with:

* `EL_RR_ACTIVE` defined in every stage and true in `enabled` conditions (§10.4). The pack skips its spatial and temporal
  denoisers for the signals RR denoises and feeds their raw per-frame samples into its lighting instead. RR wants
  per-pixel, uncorrelated noise: a signal traced below render resolution and upsampled (half-res GI) has blotchy,
  spatially correlated noise that RR keeps as texture, so such a signal should keep its own temporal accumulation and drop
  only the spatial filters ([UPSCALING.md §11.5](UPSCALING.md)). A short history also helps signals that pass through
  non-linear maths (§11.4: with none, raw samples through `sqrt` lighting came out darker).
* Three reserved images, render size, `RGBA16F`, sampled as `el_rrDiffuse`, `el_rrSpecular`, `el_rrNormal` and stored
  through `el_rrDiffuse_img`, `el_rrSpecular_img`, `el_rrNormal_img` (compute passes). They do not count against the
  image limits and exist only while RR runs. A compute pass that lists any of them in `writes` is part of the frame only
  while RR runs (no `enabled` needed); `settings.rayReconstruction` requires that every one of them is written by some
  pass. Emberlight never clears them: write every pixel, every frame, after the colour input is final (usually a compute
  pass in `composite` after the last pass that writes the image `final` samples).

| Image | Content |
|---|---|
| `el_rrDiffuse` | rgb: diffuse albedo, **in the encoding of the colour input** (sRGB-encoded when the frame is, as it must be for the LDR cut), so colour ÷ albedo is still the lighting. Metals: 0. Where the pack has nothing to demodulate (sky, water and glass showing refraction, particles, the hand), the colour input itself: RR then keeps those pixels as they are. |
| `el_rrSpecular` | rgb: pre-integrated specular reflectance (the environment BRDF: F0·scale + bias for the roughness and N·V, e.g. Karis's analytic fit or NVIDIA's `EnvBRDFApprox2`), same encoding as above; 0 where the diffuse guide is the colour. |
| `el_rrNormal` | rgb: unit shading normal in **camera-relative world space** (the space of `el_screenToWorld`), a: perceptual roughness (1 − LabPBR smoothness), 0–1. Sky: `vec4(0)`. |

Depth, motion vectors, jitter and the matrices come from Emberlight as for SR. Not provided in format 1: specular hit
distance and specular motion vectors (RR estimates reflection motion from normals, roughness and depth), the particle and
transparency masks. NaN or Inf in a guide is replaced (albedo by the colour, normal by "none"), and NaN in the colour by 0.

When RR is selected but the pack does not set `rayReconstruction`, the pack is compiled as usual (its denoisers run)
and DLSS SR runs; the Upscaling page says why. When RR cannot start (old driver, no model), Emberlight falls back to
DLSS SR and recompiles the pack without `EL_RR_ACTIVE`.

```jsonc
"settings": { "rayReconstruction": true },
"passes": [
  // ... the pack's ray tracing; its denoisers carry "enabled": "!EL_RR_ACTIVE" or test the macro in their shaders ...
  { "name": "rr_guides", "kind": "compute", "phase": "composite", "compute": "shaders/rr_guides.comp.glsl",
    "writes": ["el_rrDiffuse", "el_rrSpecular", "el_rrNormal"] }
]
```

`packs/emberlight-rtx` is the reference: `rr_guides.comp.glsl`, `denoise.frag.glsl` (sun and reflections raw, no
gathers; the half-res GI keeps its temporal history) and the à-trous passes switched off.

## 15. How a native pack runs — compiled directly to the internal manifest

A native pack is compiled to the same internal, versioned manifest (EPF, [FORMAT.md](FORMAT.md)) and SPIR-V cache as a
converted Iris pack; the runtime (`PackRuntime`, `FrameExecutor`, `GbufferPipelines`, RT, upscaling, frame
generation) does not know which kind it runs. The native compiler (`converter/…/nativefmt/`: `NativeManifest` parses,
`NativeCompiler` drives, `NativeGlsl` assembles stages, `NativePlanner` plans the frame, `NativeOptions` handles options)
does not go through the Iris converter: no Iris-layout files are generated, no `gbuffers_*` program files, no
`shaders.properties`, no option scanning of `#define` lines and no rewriting of Iris uniforms or varyings. It shares
only the pieces that are not Iris-specific: the std140 layout builder, the custom-uniform expression parser and
evaluator, shaderc, SPIR-V reflection and the cache directory.

| Native | Internal (EPF) |
|---|---|
| `images` (screen-sized) | `targets` `colortex<N>` in declaration order (N = 4…15, then 0…3); `scale` → `relativeSize`, `clear` / `history` → `clear` + `clearColor`, `mipmaps` → `mipLevels 0`. Ping-pong (§6.1) uses the EPF flip mechanism, only for a fullscreen pass that samples an image it writes (known from the SPIR-V); such images get `hasAlt`, and a `history` image that ends a frame on its alt side is copied back (`copy`). |
| `images` (absolute `size`) | `targets` of kind `image` (2D/3D), named like the image. |
| `buffers` | `targets` of kind `buffer`, named like the buffer. |
| `textures` | `textures` (custom textures, copied or converted into the cache). |
| built-in images | `depthtex0/1/2` (1 and 2 only when sampled), `shadowtex0/1`, `shadowcolor0/1`, `noisetex`. |
| fullscreen / compute passes | programs named like the pass, `kind` `composite` / `compute`, `group` = phase; run in the fixed phase order of §4 in array order. |
| `setup` | `once` commands. |
| geometry classes | one program `<pass>.<class>` per class whose fallback chain (§5.1) reaches a pass, `fallbackFor` = the runtime's geometry slot of the class; the vertex stage is compiled once per Minecraft vertex layout the slot is drawn with. A class whose chain reaches no pass gets no program and is not drawn. |
| `final` | the program of the `final` command. |
| `writes` | `outputs` / fullscreen `writes`. |
| `blend`, `alphaCutoff` | `blend` (default `vanilla` for geometry, `off` for fullscreen); `alphaTest` is always off — the cutoff is `EL_ALPHA_CUTOFF` in the shader. |
| `workgroups` | `localSize`, `workGroups` / `workGroupsRender`. |
| `enabled`, `dimensions.disable` | programs left out of the plan (conditions are evaluated with the current option values). |
| `dimensions` | dimension plans `default` and one per override + `dimensionMap`. |
| options, pages, profiles | `options` (`declared`, `screens`, `profiles`, `sliders`, `columns`) for the existing option screen; values become `#define`s in the prelude, so changing an option recompiles the pack (cache key = pack + settings). |
| `uniforms` | `uniforms.custom` (the shared expression evaluator), members of the frame block. |
| `materials` | `idMaps`. |
| `settings.rayReconstruction` + RR on | `EL_RR_ACTIVE`; targets `el_rrDiffuse`/`el_rrSpecular`/`el_rrNormal` (kind `image`, render size, RGBA16F); `rt` effect `rr` (or `unavailable` `rr` for a pack without it, which keeps DLSS SR); the runtime's RR guide pass copies the three images into DLSS-D's guides. |
| `requires: ray_query` / `raytracing` | `rt` section with effect `native` (`native:entities` with `settings.rayTracedEntities`): the mod builds the TLAS; `acceleration_structure` + section table + light list resources. |
| `settings` | `properties` (clouds, sun, moon, stars, separateAo, oldLighting, …). |

Per stage, the compiler writes the full GLSL it compiled to the cache's `src/<dimension>/` folder (debugging aid) and
the SPIR-V to `spv/`; set-1 bindings, set-0 slots and the storage writes each program uses come from SPIR-V reflection.
Errors in the manifest are reported against `emberlight.json` before anything is compiled; shader errors point at the
pack's own files through `#line "file"` directives (included files too).

## 16. Performance guidelines

* Geometry passes run once per draw call of their classes: keep the gbuffer small (3–4 images, 8–16 bit) and do
  lighting in `deferred`.
* Prefer `R11F_G11F_B10F` over `RGBA16F` for HDR colour without alpha; pack normals into two channels
  (`el_encodeNormal`).
* Half- and quarter-resolution images (`scale`) for blur, bloom, volumetrics, SSR and RT effects; upsample with depth
  awareness.
* Compute over fullscreen when a pass needs shared memory or scatter writes; fullscreen when it is a pure per-pixel
  function (early-out on sky, no dispatch-size tail).
* Ray queries: one shadow ray per pixel with `el_traceVisibility` costs ~0.3–0.6 ms at 1440p on an RTX 30/40-class GPU;
  trace at half resolution and filter for soft effects; use `EL_MASK_OPAQUE` unless you need translucent hits;
  `rayTracedEntities` costs BLAS rebuilds every frame.
* `discard` and `gl_FragDepth` in opaque geometry disable early depth testing for that pass.
* `history` images cost memory but no bandwidth when unused; `clear` costs a clear per frame.
* Options that change resources (resolutions, counts) recompile; mark them `restart` only when they do something a
  recompile cannot.
* The profiler (F7) times every pass; native passes show up under their own names (§15), e.g. `lighting`.

## 17. Compatibility and versioning

* `format` is the major version of the manifest + header contract. Emberlight loads every format it knows; a pack
  with a newer `format` is refused with "needs a newer Emberlight". Format 1 packs keep working in all later Emberlight
  versions (a new format adds a translation step, it does not drop the old one).
* Within a format, Emberlight only **adds**: new manifest keys (optional, with defaults that keep old behaviour), new
  header functions and frame members (prefix `el_`, so they cannot clash with pack names that follow the rules), new
  geometry classes (with a fallback to an existing class, so old packs draw them as before). Pack code may test
  `EL_HEADER_VERSION` (an integer, starts at 1, bumped on every addition) to use newer additions when present.
* Names starting with `el_`/`EL_`/`emr`/`iris_` are reserved for Emberlight in pack GLSL; unknown manifest keys are
  errors, so a pack written for a newer 1.x that uses a new key fails clearly on an older Emberlight.
* Behaviour that is not specified here (which physical image a ping-pong pass writes, descriptor binding numbers,
  pipeline-cache contents) can change at any time.
* Minecraft updates: geometry classes are Emberlight's abstraction; when Minecraft adds or splits render types,
  Emberlight maps them to existing classes or adds a class with a fallback.

## 18. Alpha status (format 1 implementation, 2026-11)

Implemented: manifest parsing and validation with line numbers, direct compilation to the internal manifest, every pass kind and
phase, all geometry classes with the native fallback chains, images (screen-sized, history, mipmaps, absolute/3D),
buffers, textures, options (all types; colour as three sliders), pages, profiles, localisation, custom uniforms,
materials, dimension overrides, `EL_UPSCALING`, DLSS Ray Reconstruction guides (§14.4), ray queries with the header helpers, hot reload of the pack folder.
Tested in game: the template pack (deferred lighting, ray-traced shadows, translucents, options).

Not yet: RR's specular hit distance and HDR colour input (§14.4), `hdrOutput`, option `restart` and
`enabled` greying, pack icon, more than 2 shadow colour images, tessellation, ray queries in
geometry passes. Which blocks are RT light sources comes from Emberlight's built-in emissive table in format 1 (emission from a
resource pack's `_s` maps reaches ray hits through `el_hitSpecular`, but does not add sources to the light list).

## 19. Hot reload and errors

* **Hot reload.** While a native pack from a folder is active, Emberlight watches the folder. Saving any file recompiles
  the pack in the background (~0.2–2 s for typical packs); the old version keeps rendering until the new one is ready,
  and a failing version never replaces a working one. The reload key (R) and the options screen's Apply do the same. Zip packs are not watched.
* **Errors.** Manifest errors are listed before compilation, each as `emberlight.json:<line>: <message>`, e.g.

  ```
  emberlight.json:41: pass "lighting" writes unknown image "gAlbdo" (images: gAlbedo, gNormal, lit)
  emberlight.json:12: option "SHADOW_RAYS": default 0 is below min 1
  shaders/lighting.frag.glsl:57 [lighting.fragment]: 'el_traceVisiblity' : no matching overloaded function found
  ```

  Errors go to the log and the loading HUD; with Emberlight's debug option on, the first 20 are also printed in chat.
  A pack with manifest errors does not load (the previous pack keeps rendering); a pass whose shaders fail to compile is
  left out and the rest of the pack still runs.
* **Warnings** (pack loads): option on no page; a settings-file value that is not a valid option value; shaderc's
  warnings, with the pack file and line.
