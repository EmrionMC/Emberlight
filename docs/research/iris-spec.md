# Iris shaderpack semantics: implementation spec

This document describes Iris's shaderpack behaviour so we can rebuild it on the MC 26.3 Vulkan renderer.
It is **derived from reading Iris** at `reference/iris`, commit `bff1e69` (2026-09-15, built for MC 26.x). It contains no Iris code.

**Path abbreviations**
- `I/` = `reference/iris/common/src/main/java/net/irisshaders/iris/`
- `K/` = `reference/iris/common/src/main/java/kroppeb/stareval/`

**Test pack:** Complementary Reimagined r5.9.3, at `testpacks/reimagined/shaders`. Notes about this pack are marked **[CR]**.

---

## 0. Pipeline at a glance (per frame)

| # | Step | Programs / action | Iris ref |
|---|------|-------------------|----------|
| 0 | Pipeline creation (once per pack load or dimension change) | Runs `setup.csh … setup99.csh` once with dispatch(1,1), after a full clear of all colortex | `I/pipeline/IrisRenderingPipeline.java:483-514` |
| 1 | `beginLevelRendering` | (a) Block-ID map built (first frame only). (b) Custom images with `clear=true` are cleared. (c) Shadow depth is cleared, then **`shadow.csh`, `shadow_a.csh…`** run with dispatch(shadowRes, shadowRes), then shadowcolor clears. (d) Frame counter and timer advance, `FrameUpdateNotifier` fires, custom uniforms update. (e) On a resize, render targets are resized and the setup computes run again. (f) colortex clear passes run. (g) **begin, begin1…begin99** | `IrisRenderingPipeline.java:875-1025` |
| 2 | Sky-disc / horizon (`onBeginClear`, phase `SKY`) | Iris draws a horizon box with the fog colour if `sky=true` and the dimension has an overworld skybox or skylight | `:1299-1318` |
| 3 | `renderShadows` (before MC `addMainPass`) | Shadow pass: gbuffers-style `shadow*` programs, then **shadowcomp…shadowcomp99**. Then **prepare…prepare99** | `:1028-1035`, `I/shadows/ShadowRenderer.java:384-640`, `I/mixin/MixinLevelRenderer.java:197-203` |
| 4 | MC main pass, opaque | `gbuffers_*`: sky, terrain (OPAQUE group), entities, block entities, opaque particles, … | `MixinLevelRenderer.java:211-268` |
| 5 | Before `renderTranslucentFeatures` | `beginHand()` copies depth into **depthtex2** and samples the centre depth. The **solid hand** is drawn (`HAND_SOLID`). `beginTranslucents()` copies depth into **depthtex1**. Then **deferred…deferred99** | `MixinLevelRenderer.java:276-283`, `IrisRenderingPipeline.java:1051-1086` |
| 6 | MC main pass, translucent | `gbuffers_water`, `*_translucent`, translucent particles, weather, clouds, … | — |
| 7 | End of `renderLevel` | Translucent hand (`HAND_TRANSLUCENT`). Then **composite…composite99**. Then **final** (`final.csh` computes first). Then "swap" copies alt→main for flipped, non-cleared buffers. Mipmap state is reset | `MixinLevelRenderer.java:168-185`, `IrisRenderingPipeline.java:1088-1094`, `I/pipeline/FinalPassRenderer.java:198-310` |
| 8 | `finalizeGameRendering` | Colour-space conversion (if not `supportsColorCorrection`) | `:1096-1099` |

Note that **depthtex1 contains the solid hand** (it is copied after `renderSolid`), while depthtex2 does not.

---

## 1. Program set and pass order

### 1.1 Program groups and file names

Source files are looked up in a dimension folder, or in the pack root if there is no dimension override. The lookup is in `I/shaderpack/programs/ProgramSet.java:61-95`.

Each program is made of `.vsh`, `.gsh` and `.fsh` files. `.tcs` and `.tes` are only read if the pack declares the `TESSELLATION_SHADERS` feature flag (`ProgramSet.java:59, 107-125`).

**A program is valid only if both `.vsh` and `.fsh` exist** (`I/shaderpack/programs/ProgramSource.java:81-91`). The exception: if only a `.fsh` exists, Iris synthesises a legacy `#version 120` vertex shader that uses `ftransform()` and passes through `irs_texCoords[3]` and `irs_Color` (`ProgramSet.java:127-144`).

| Group (`ProgramGroup`) | Base name | Count | Compute files | Ref |
|---|---|---|---|---|
| Setup | `setup` | `setup.csh`, `setup1.csh` … `setup99.csh` (compute only, **no `_a` letters**) | — | `I/shaderpack/loading/ProgramArrayId.java:4`, `ProgramSet.java:62,177-188` |
| Begin | `begin` | `begin`, `begin1` … `begin99` | `beginN.csh`, `beginN_a.csh` … `_z` | `ProgramArrayId.java:5` |
| Shadow | `shadow` | see §1.2 | `shadow.csh`, `shadow_a.csh` … (run in step 1c) | `ProgramSet.java:61` |
| ShadowComposite | `shadowcomp` | `shadowcomp` … `shadowcomp99` | `shadowcompN[_x].csh` | `ProgramArrayId.java:6` |
| Prepare | `prepare` | 0–99 | yes | `:7` |
| Gbuffers | `gbuffers_*` | see §1.2 | — | `I/shaderpack/loading/ProgramId.java` |
| Deferred | `deferred` | 0–99 | yes | `ProgramArrayId.java:8` |
| Composite | `composite` | 0–99 | yes | `:9` |
| Final | `final` | 1 | `final.csh`, `final_a.csh` … | `ProgramSet.java:92` |
| DH | `dh_terrain`, `dh_water`→`dh_terrain`, `dh_generic`→`dh_terrain`, `dh_shadow` | — | — | `ProgramId.java:54-57` (Distant Horizons only) |

Rules for the numbered arrays and computes:

- **Index 0 has no number suffix.** For index i > 0 the name is `<base><i>`. Gaps are allowed: all 100 slots are probed (`ProgramSet.java:163-175`).
- **Compute names.** Computes for pass i are `<base><i>.csh` (slot 0), then `<base><i>_a.csh` … `_z.csh`. The letter sequence **stops at the first missing letter** (`ProgramSet.java:190-211`).
- **Compute-only passes.** A pass index that has computes but no valid vsh/fsh pair becomes a compute-only pass. **It does not flip any buffers** (`I/pipeline/CompositeRenderer.java:132-141`).
- **Compute order.** Within a pass, computes run *before* the fullscreen draw. They dispatch with the screen size, followed by a memory barrier (image, texture-fetch and SSBO bits) (`CompositeRenderer.java:276-292`).

### 1.2 Gbuffers / shadow programs and the fallback chain

The chain is defined by the enum in `I/shaderpack/loading/ProgramId.java:13-59`. Resolution walks `fallback` until a program is valid (`I/shaderpack/programs/ProgramFallbackResolver.java:28-45`). If nothing resolves, Iris uses a built-in fallback shader (`IrisRenderingPipeline.java:669-672, 703-712`).

| Program | Fallback | Default blend override |
|---|---|---|
| `gbuffers_basic` | — (Iris fallback shader) | |
| `gbuffers_line` | basic | |
| `gbuffers_textured` | basic | |
| `gbuffers_textured_lit` | textured | |
| `gbuffers_skybasic` | basic | |
| `gbuffers_skytextured` | textured | |
| `gbuffers_clouds` | textured | |
| `gbuffers_terrain` | textured_lit | |
| `gbuffers_terrain_solid` | terrain | |
| `gbuffers_terrain_cutout` | terrain | |
| `gbuffers_damagedblock` | terrain | |
| `gbuffers_block` | terrain | |
| `gbuffers_block_translucent` | block | |
| `gbuffers_beaconbeam` | textured | |
| `gbuffers_item` | textured_lit | (not referenced by any `ShaderKey`) |
| `gbuffers_entities` | textured_lit | |
| `gbuffers_entities_translucent` | entities | |
| `gbuffers_lightning` | entities | |
| `gbuffers_particles` | textured_lit | |
| `gbuffers_particles_translucent` | particles | |
| `gbuffers_entities_glowing` | entities | (not referenced by any `ShaderKey`) |
| `gbuffers_armor_glint` | textured | |
| `gbuffers_spidereyes` | textured | `SRC_ALPHA ONE ZERO ONE` |
| `gbuffers_hand` | textured_lit | |
| `gbuffers_weather` | textured_lit | |
| `gbuffers_water` | terrain | |
| `gbuffers_hand_water` | hand | |
| `shadow` | — | OFF |
| `shadow_solid`, `shadow_cutout`, `shadow_water`, `shadow_entities`, `shadow_block` | shadow | OFF |
| `shadow_lightning` | shadow_entities | OFF |

`final` has no fallback. Without it, colortex0 (read side) is copied to the MC main colour target (`FinalPassRenderer.java:262-279`).

**Name lookup uses the resolved file.** `alphaTest.`, `blend.`, `scale.` and `flip.` overrides are looked up under the *resolved* source name (`I/shaderpack/properties/ProgramDirectives.java:78-87`). So `alphaTest.gbuffers_terrain` applies to terrain_cutout when terrain_cutout falls back to terrain.

### 1.3 MC geometry → program ("ShaderKey")

Mapping from MC `RenderPipelines.*` to `ShaderKey` is in `I/pipeline/IrisPipelines.java:25-134`. The `ShaderKey` defines the program, fallback alpha test, vertex format, fog mode and lighting (`I/pipeline/programs/ShaderKey.java:9-91`). Alpha constants are in `I/gl/blending/AlphaTests.java`: NON_ZERO=`>0.0001`, ONE_TENTH=`>0.1`, HALF=`>0.5`, VERTEX_ALPHA (special case), OFF.

| MC pipeline(s) | Main pass program (alpha) | Shadow pass program |
|---|---|---|
| SOLID_BLOCK, SOLID_TERRAIN | terrain_solid (off) | shadow_cutout |
| CUTOUT_BLOCK, CUTOUT_TERRAIN | terrain_cutout (>0.5) | shadow_cutout |
| TRANSLUCENT_TERRAIN | **water** (>0.0001) | shadow_water |
| TRANSLUCENT_BLOCK (moving blocks) | block (>0.1) | shadow_water |
| ENTITY_CUTOUT*, ITEM_CUTOUT, ARMOR_*CUTOUT* | entities (>0.1). In hand: hand / hand_water. In BLOCK_ENTITIES phase: block | shadow_entities |
| ENTITY_SOLID* | entities (off). Or hand / block | shadow_entities |
| ENTITY_TRANSLUCENT*, ITEM_TRANSLUCENT, ARMOR_TRANSLUCENT, BREEZE_WIND, BANNER_PATTERN, ENTITY_SHADOW | entities_translucent. Or hand_water, or block_translucent for block entities | shadow_entities |
| EYES | spidereyes (>0.0001). ENTITY_TRANSLUCENT_EMISSIVE: spidereyes (>0.1) | shadow_entities |
| END_PORTAL, END_GATEWAY | block | shadow_block |
| ENERGY_SWIRL, END_CRYSTAL_BEAM, ENTITY_CUTOUT_Z_OFFSET | entities | shadow_entities |
| LIGHTNING, DRAGON_RAYS* | lightning (off) | shadow_lightning |
| BEACON_BEAM_* | beaconbeam (off) | shadow_entities |
| TEXT*, TEXT_INTENSITY* | entities_translucent. Or block_translucent (BE phase), or hand / hand_water | shadow_entities |
| TEXT_BACKGROUND* | entities_translucent | shadow_entities |
| LINES, LINES_TRANSLUCENT, SECONDARY_BLOCK_OUTLINE | line (off) | shadow |
| STARS, SKY | skybasic (off) | — |
| SUNRISE_SUNSET | skybasic (>0.0001) | — |
| CELESTIAL, END_SKY | skytextured (off) | — |
| OPAQUE_PARTICLE / TRANSLUCENT_PARTICLE | particles / particles_translucent (>0.1) | shadow |
| WEATHER_* | weather (>0.1) | shadow |
| CLOUDS, FLAT_CLOUDS | clouds (>0.1) | — |
| CRUMBLING | damagedblock (>0.1) | shadow (tex) |
| GLINT | armor_glint (>0.0001) | shadow_entities |
| WORLD_BORDER | textured | — |
| WATER_MASK | basic | shadow |
| LEASH | basic | shadow |

**Hand.** "Hand" is decided by `HandRenderer.isActive()`. Opaque hand items use `gbuffers_hand`. Translucent hand items use `gbuffers_hand_water` (`IrisPipelines.java:186-222`).

**Hand projection.** The hand projection is MC's perspective projection with near = 0.05 and the HUD FOV, with z scaled by 0.125 (`I/pathways/HandRenderer.java:43,60-63`). The GLSL define `MC_HAND_DEPTH` = `0.125`.

**Block entities.** "Block entity" means the current phase is `BLOCK_ENTITIES`.

### 1.4 WorldRenderingPhase (`renderStage` uniform, `MC_RENDER_STAGE_*` defines)

Ordinals are taken from `I/pipeline/WorldRenderingPhase.java:7-30`. They are exposed as `#define MC_RENDER_STAGE_<NAME> <ordinal>` (`I/gl/shader/StandardMacros.java:347-353`).

| Ordinal | Phase | Ordinal | Phase |
|---|---|---|---|
| 0 | NONE | 12 | BLOCK_ENTITIES |
| 1 | SKY | 13 | DESTROY |
| 2 | SUNSET | 14 | OUTLINE |
| 3 | CUSTOM_SKY | 15 | DEBUG |
| 4 | SUN | 16 | HAND_SOLID |
| 5 | MOON | 17 | TERRAIN_TRANSLUCENT |
| 6 | STARS | 18 | TRIPWIRE |
| 7 | VOID | 19 | PARTICLES |
| 8 | TERRAIN_SOLID | 20 | CLOUDS |
| 9 | TERRAIN_CUTOUT_MIPPED | 21 | RAIN_SNOW |
| 10 | TERRAIN_CUTOUT | 22 | WORLD_BORDER |
| 11 | ENTITIES | 23 | HAND_TRANSLUCENT |

Notes:
- In 26.x, terrain is drawn per `ChunkSectionLayerGroup`. **OPAQUE → TERRAIN_SOLID** (this includes cutout terrain); TRANSLUCENT → TERRAIN_TRANSLUCENT (`WorldRenderingPhase.java:32-40`, `MixinLevelRenderer.java:236-242`).
- In the shadow pass the order is TERRAIN_SOLID (opaque group) → TERRAIN_CUTOUT (external callbacks only) → ENTITIES → TERRAIN_TRANSLUCENT (`ShadowRenderer.java:508-601`).
- Phases are set at: sky (`I/mixin/MixinSkyRenderer.java:29-68`), particles (`I/mixin/MixinParticleEngine.java:28`), entities / outline / block entities (`I/layer/GbufferPrograms.java:29-68`), hand (`HandRenderer.java:106,143`).

### 1.5 Dimension folders

Logic is in `I/shaderpack/ShaderPack.java:125-155, 395-418, 556-586`.

1. **dimension.properties exists and is non-empty.** Each `dimension.<folder>=<id> <id> …` maps namespaced dimension IDs to `<folder>`. The token `*` maps the wildcard `*:*`. The file is read as ISO-8859-1 and run through the properties preprocessor with environment defines (no option values).
2. **Otherwise** the legacy folders are used: `world0` → overworld and `*:*`, `world-1` → `minecraft:the_nether`, `world1` → `minecraft:the_end`.
3. A folder only counts if it contains at least one known program file name.
4. **Base program set** = folder mapped to `*:*`, or the pack root if there is none (`ShaderPack.java:322`).
5. **Per-dimension set.** The mapped folder's set **completely replaces** the base set; nothing is merged (`:573-580`). A dimension with no mapping uses the base set.
6. The pipeline is rebuilt per dimension.

**[CR]** `dimension.world0=*`, `dimension.world-1=minecraft:the_nether minecraft:nether`, `dimension.world1=minecraft:the_end minecraft:end`. All programs live in `world0/`, `world-1/` and `world1/`, which `#include "/program/*.glsl"`.

### 1.6 Shadow pass activation

Shadow render targets, and therefore the whole shadow pass and shadowcomp, exist only in two cases (`IrisRenderingPipeline.java:317-328, 451-477`):

- `shadow.enabled=true` is set in shaders.properties, or
- any begin, prepare, deferred, composite or final program has an **active** shadow sampler: `shadowtex0/1`, `shadow`, `watershadow`, `shadowcolor[0-7]` or `shadowcolorimg*` (`I/samplers/IrisSamplers.java:117-136`).
  Gbuffers programs that MC geometry uses count too: `ShaderMap` builds all `ShaderKey`s in enum order, the gbuffers keys
  before the `SHADOW_*` keys, and `addGbufferOrShadowSamplers` asks for the shadow targets (`IrisRenderingPipeline.java:767-773`).
  MakeUp UltraFast samples `shadowtex0` only in `gbuffers_terrain` and friends.

`shadow.enabled=false` creates the targets but skips rendering.

**The existence of `shadow.vsh` alone does not enable shadows.**

**[CR]** Shadows are disabled via `program.world0/shadow.enabled=false` when `SHADOW_QUALITY == -1`.

---

## 2. Render targets

### 2.1 colortex (colour buffers)

- **Count.** Up to **32** (`colortex0` … `colortex31`; `I/gl/IrisLimits.java` `MAX_COLOR_BUFFERS = 32`). This is also defined as `#define MAX_COLOR_BUFFERS 32`.
- **Legacy aliases** (index 0–7): `gcolor`, `gdepth`, `gnormal`, `composite`, `gaux1`, `gaux2`, `gaux3`, `gaux4` (`I/shaderpack/properties/PackRenderTargetDirectives.java:21-30`).
- **Lazy creation.** A target is allocated only when something references it: a draw buffer of any program, an active sampler `colortexN` or its alias, or an image `colorimgN` (`I/targets/RenderTargets.java:118-137`, `IrisSamplers.java:86-105`).
- **Two textures each.** Every target has `main` and `alt` textures with identical format and size (`I/targets/RenderTarget.java:44-50`).
- **Filtering.** LINEAR, or NEAREST for integer formats, with CLAMP_TO_EDGE. Mipmapped samplers are used only after `MipmapEnabled` is triggered (`RenderTarget.java:64-67, 132-143`).

| Setting | Default | Directive | Ref |
|---|---|---|---|
| Format | `RGBA` (= RGBA8) | `const int colortexNFormat = <FMT>;` (or `gcolorFormat`, …) | `PackRenderTargetDirectives.java:104-113, 132-136` |
| Clear each frame | **true** | `const bool colortexNClear = false;` | `:116-117` |
| Clear colour | buffer 0: **fog colour, alpha 1**. Buffer 1: **(1,1,1,1)**. Others: **(0,0,0,0)** | `const vec4 colortexNClearColor = vec4(r,g,b,a);` (also the initial colour when Clear=false) | `I/targets/ClearPassCreator.java:31-43` |
| Size | screen size | `size.buffer.colortexN = W H` in shaders.properties (§4) | `I/shaderpack/properties/PackDirectives.java:325-349` |
| Mipmap | off | `const bool colortexNMipmapEnabled = true;` (per program, see §3) | `ProgramDirectives.java:96-121` |

**Accepted format names** (`I/gl/texture/InternalTextureFormat.java:16-88`, case-insensitive):
- RGBA, R8, RG8, RGB8, RGBA8
- `*_SNORM` 8/16
- R16, RG16, RGB16, RGBA16
- R16F … RGBA32F
- R8I … RGBA32UI
- RGBA2, RGBA4, R3_G3_B2, RGB5_A1, RGB565, RGB10_A2, RGB10_A2UI, R11F_G11F_B10F, RGB9_E5

Integer formats get NEAREST filtering.

**Not implemented in this Iris:** the legacy `/* GAUX4FORMAT: */` directive and the "declare `gdepth` → upgrade to RGBA32F" rule. Both are wired to no-op stubs in `I/shaderpack/parsing/DispatchingDirectiveHolder.java:257-287`.

**Clear behaviour** (`ClearPassCreator.java:20-78`, `IrisRenderingPipeline.java:973-994`):
- Every frame, for each *existing* target with `clear=true`, both main and alt are cleared to the clear colour.
- On a "full clear" (first frame and after a resize; `RenderTargets.java:66,216`), **every** existing target is cleared regardless of the flag.
- The fog colour used is `CapturedRenderingState.getFogColor()` with a = 1.
- Clears happen at the start of the frame, **before** the begin passes.

**[CR] formats** (`lib/pipelineSettings.glsl`, inside a `/* … */` block):

| Buffer | Format | Buffer | Format |
|---|---|---|---|
| 0 | R11F_G11F_B10F | 6 | RGB8 |
| 1 | RGB8_SNORM | 7 | RGBA16F |
| 2 | RGB16F | 8 | RGBA16F |
| 3 | RGBA8 | 12 | RGBA8 |
| 4 | RGBA8_SNORM | 13 | R8 |
| 5 | RGBA8 | 18 | R8 |
| | | 19 | RGBA8 |

Buffers 1, 2, 4, 5, 7, 18 and 19 use Clear=false (they persist across frames, e.g. TAA history). Buffers 1 and 7 use `size.buffer` = REFLECTION_RES.

### 2.2 Flip / ping-pong rules

Implementation: `I/targets/BufferFlipper.java`, `CompositeRenderer.java:113-185`, `RenderTargets.java:309-371`.

- **The flipped set F.** The pipeline holds a set F of flipped indices. It is computed **once, at load time**, by simulating every stage in order. The flip schedule is static, and F is empty at the start of every frame.
- **Composite-like passes** (begin, prepare, deferred, composite, and shadowcomp for shadowcolor):
  - **read** `colortexN` = `alt` if N ∈ F, else `main`.
  - **write** draw buffer N = `main` if N ∈ F, else `alt`.
  - After the pass, every draw buffer N toggles in F. The exception is `flip.<pass>.<buf>=false`, which suppresses the toggle. `flip.<pass>.<buf>=true` toggles a buffer even if the pass does not draw to it.
- **Stage-start flips.** `flip.<stage>_pre.<buf>=true` toggles at the start of a stage, for stages `begin_pre`, `prepare_pre`, `deferred_pre`, `composite_pre` and `shadowcomp_pre` (`IrisRenderingPipeline.java:334-358`, `CompositeRenderer.java:116-121`).
- **Gbuffers write the read side.** The draw buffer is `alt` if N ∈ F, else `main`. Samplers for colortex4+ read the same texture. Two snapshots of F are used:
  - Opaque gbuffers use **F after prepare**.
  - Translucent gbuffers use **F after deferred**. They switch at `beginTranslucents` (`IrisRenderingPipeline.java:364-365, 686-698, 1067`).
  - The draw buffer is never toggled by gbuffers.
- **Shadow-pass programs and shadow computes** see F **after begin** (`flippedBeforeShadow`, `:339, 570, 737`).
- **Setup computes** see F = ∅ (`:629-632`).
- **Final** reads with F after composite (`FinalPassRenderer.java:113-123`).
- **End of frame "swap".** For every N ∈ F_final that is not `clear=true`, the `alt` contents are **copied into `main`** (`FinalPassRenderer.java:137-160, 288-301`). The next frame therefore always starts with the newest data in `main`.
- **Images.** `colorimgN` binds the *read-side* texture, so it is affected by flips (`I/samplers/IrisImages.java:16-39`).
- **Size check.** All draw buffers of one composite pass must have the same size, otherwise loading fails (`CompositeRenderer.java:159-166`).
- **Custom textures and colortex names.** A custom texture named `colortexN` (via `texture.<stage>.colortexN`) overrides sampling of that buffer until the buffer has been flipped at least once in that stage chain (`I/gl/program/ProgramSamplers.java:239-250`).

### 2.3 Depth textures

| Name | Content | When written | Ref |
|---|---|---|---|
| `depthtex0` (`gdepthtex` in composite) | Live MC main depth buffer (the gbuffers depth attachment) | continuously | `RenderTargets.java:139-141`, `IrisSamplers.java:220-235` |
| `depthtex1` | Copy of depth **after opaque and after the solid hand**, before translucents | `beginTranslucents()` → `copyPreTranslucentDepth` | `IrisRenderingPipeline.java:1069-1071`, `RenderTargets.java:222-232` |
| `depthtex2` | Copy of depth **before the hand** (opaque only, no hand) | `beginHand()` → `copyPreHandDepth` | `:1051-1057`, `RenderTargets.java:234-244` |

- All three use NEAREST filtering.
- Gbuffers get depthtex0–2 as well. Note that sampling depthtex0 in gbuffers is a feedback loop.
- The format is MC's depth format.
- Iris never allocates its own main depth; it reuses MC's (`FinalPassRenderer.java:202-215`).

### 2.4 Shadow targets

Directives: `I/shaderpack/properties/PackShadowDirectives.java`. Targets: `I/shadows/ShadowRenderTargets.java:44-72`.

**Depth**
- `shadowtex0`: depth of everything. `shadowtex1`: copied after entities and block entities, **before translucent terrain** (`ShadowRenderer.java:588`).
- Format DEPTH32, size `shadowMapResolution`² (default **1024**). Mip levels are allocated if mipmaps are requested.
- Aliases: `shadow` = shadowtex0. `watershadow` = shadowtex0, and if `watershadow` is declared, `shadow` becomes shadowtex1 (`IrisSamplers.java:143-153`).
- Hardware compare sampler: `shadowHardwareFiltering[0|1]`. With the `SEPARATE_HARDWARE_SAMPLERS` feature flag, the compare samplers are exposed separately as `shadowtex0HW` and `shadowtex1HW`.

**Colour**
- `shadowcolor0` … `shadowcolor7` (or only 0–1 unless the feature flag `HIGHER_SHADOWCOLOR` is requested; `ShadowRenderTargets.java:46`). `shadowcolor` = shadowcolor0.
- Defaults: format RGBA8, **clear=true**, **clear colour (1,1,1,1)**, linear filtering, no mipmap (`PackShadowDirectives.java:399-405`).
- Directives: `shadowcolorNFormat`, `shadowcolorNClear`, `shadowcolorNClearColor`, `shadowcolorNMipmap` (also spelled `shadowColorNMipmap`), `generateShadowColorMipmap`, `shadowcolorNNearest` (also `shadowColorNNearest`, `shadowColorNMinMagNearest`).

**Other depth directives**
- `generateShadowMipmap`, `shadowtexMipmap` (= shadowtex0Mipmap), `shadowtex0Mipmap`, `shadowtex1Mipmap`.
- `shadowtexNearest` (= 0), `shadowtex0Nearest`, `shadowtex1Nearest`, `shadow0MinMagNearest`, `shadow1MinMagNearest`.

**Shadow draw buffers**
- Shadow programs write **main** (`stageWritesToAlt = ∅`).
- Draw buffers come from DRAWBUFFERS/RENDERTARGETS. **The default is `{0,1}`**, not `{0}` (`I/pipeline/programs/ShaderCreator.java:331`).

**Shadow pass order** (`ShadowRenderer.java:384-640`):
1. Depth clear and shadow computes, at frame begin.
2. Backface culling is disabled.
3. Opaque terrain → entities → block entities → copy into shadowtex1 → translucent terrain.
4. Mipmaps are generated.
5. shadowcomp* runs.

**Shadow matrices** (`I/shadows/ShadowMatrices.java:18-106`, `ShadowRenderer.java:185-198`):
- **Projection.** Orthographic, half-plane `shadowDistance` (default **160**), near = `shadowNearPlane` (default **−100.05**), far = `shadowFarPlane` (default **156**). A value of −1 means ∓DH render distance × 16.
- If `shadowMapFov` is set, a perspective projection is used instead.
- **Modelview.** Rotate X +90°, rotate Z by −360·skyAngle, rotate X by `sunPathRotation`.
  - skyAngle = shadowAngle + 0.75 if shadowAngle < 0.25, else shadowAngle − 0.25.
  - Then translate by `(cam mod interval) − interval/2` per axis. `shadowIntervalSize` defaults to **2**. Java `%` keeps the sign.
- **Shadow render distance** = `shadowDistance × shadowDistanceRenderMul / 16` chunks. A negative mul means the user's shadow-distance setting is used. `entityShadowDistanceMul` defaults to 1 (`IrisRenderingPipeline.java:286-296`).

### 2.5 noisetex

- Defaults to a generated noise texture of `noiseTextureResolution` (default **256**; `PackDirectives.java:59`).
- `texture.noise = <path>` in shaders.properties replaces it with a PNG (`I/shaderpack/properties/ShaderProperties.java:156-158`, `ShaderPack.java:328-336`).
- The sampler is LINEAR_REPEAT (`IrisSamplers.java:113-115`).

**[CR]** `noiseTextureResolution = 128`.

### 2.6 Other built-in samplers

| Sampler name(s) | Content | Available in |
|---|---|---|
| `tex`, `texture`, `gtexture`, `u_MainSampler` | Albedo (unit 0) | gbuffers, shadow |
| `lightmap` | MC lightmap (unit 2) | gbuffers, shadow |
| `iris_overlay` | Overlay (unit 1) | gbuffers, shadow |
| `normals`, `specular` | PBR atlas maps (`_n`, `_s`) | gbuffers, shadow |
| `colortex4`+ | Render targets (**colortex0–3 are not samplable in gbuffers/shadow**; `IrisSamplers.java:53-55`) | gbuffers, shadow |
| `depthtex0-2`, `noisetex`, `shadow*`, custom textures, custom image samplers | as described above | gbuffers, shadow |
| `colortex0-31` and aliases, `gdepthtex`, `depthtex0-2`, `noisetex`, `shadow*` | as described above | composite-like passes |
| `iris_centerDepthSmooth` | Backing for `centerDepthSmooth`, see §8 | composite, final |
| `dhDepthTex`/`dhDepthTex0`, `dhDepthTex1` | Distant Horizons depth | all |

In composite-like passes, colortex0 is the "default" sampler.

Refs: `IrisSamplers.java:193-235`, `IrisRenderingPipeline.java:745-775`, `CompositeRenderer.java:388-410`.

---

## 3. Source-level directives

### 3.1 Preprocessing order

1. **Include graph.** `#include "/abs"` or `"rel"` is resolved; the graph is built from every candidate start file (`I/shaderpack/include/ShaderPackSourceNames.java`, `I/shaderpack/include/IncludeGraph.java`).
2. **Option discovery and application.** This works on the *line level* of each file (§3.3) (`I/shaderpack/option/ShaderPackOptions.java:24-52`).
3. **Programs disabled** by the profile (`!program.x`) or by `program.<name>.enabled` return null, and are treated as missing (`ShaderPack.java:257-295`).
4. **JCPP preprocessing** (`I/shaderpack/preprocessor/JcppProcessor.java:12-71`):
   - Environment defines are added as macros. They are not written into the source.
   - Comments are **kept** (`KEEPCOMMENTS`).
   - `#version` and `#extension` are protected by marker substitution and hoisted to the top.
   - NUL characters are stripped.
   - After this step, the text contains no other preprocessor directives.
5. **Directive parsing** (DRAWBUFFERS / RENDERTARGETS / `const`) runs on the **preprocessed fragment source**. So `#if`/`#ifdef` decide which `const` lines count, and directives placed inside `/* … */` comments still count. **[CR]** relies on this: `pipelineSettings.glsl` lines 1–… sit inside a `/* */` block.
6. **GLSL transformation** (§8).

### 3.2 Comment directives: DRAWBUFFERS / RENDERTARGETS

Parsing is in `I/shaderpack/parsing/CommentDirectiveParser.java:26-60` and `ProgramDirectives.java:52-161`.

- **Fragment source only.**
- **Form:** `/* DRAWBUFFERS:0123 */` or `/* RENDERTARGETS: 0,1,12 */`. Whitespace between `/*` and the key is optional. The **last** occurrence in the file wins.
  - DRAWBUFFERS: each character is parsed with `Character.digit(c,10)`. **Only 0–9 are allowed** (no hex letters).
  - RENDERTARGETS: a comma-separated list of integers.
  - If both appear, the one that appears later in the text wins.
- **No directive** ⇒ `{0}`, and the program is marked `unknownDrawBuffers` (the shadow pass then uses `{0,1}`).
- **Output mapping.** `gl_FragData[i]` and `layout(location=i) out` write draw buffer `drawBuffers[i]`. The framebuffer attachment i is the texture of `colortex(drawBuffers[i])` (`RenderTargets.java:353-373`).

### 3.3 Option lines (#define / const)

Parsing is in `I/shaderpack/option/OptionAnnotatedSource.java:158-426`. Each line is parsed in isolation; lines that are not interesting are skipped (`:160-166`).

| Line form | Result |
|---|---|
| `#define NAME` | Boolean option, default **true**, *but only if NAME is referenced* by `#ifdef NAME` / `#ifndef NAME` somewhere in the same include-connected file graph (`:185-200`, `getOptionSet:467-482`). References inside `#if defined(NAME)` **do not count** (OptiFine parity). |
| `//#define NAME` (any number of `/`, optional spaces) | Boolean option, default **false**. Same reference rule. |
| `#define NAME // comment` | Boolean option with a comment. |
| `#define NAME value // [a b c] comment` | String option. `value` must be a number or a word. Allowed values come from the first `[…]` in the comment, split on single spaces. The default is appended if it is missing from the list. **Without `[…]` the line is not an option** (`I/shaderpack/option/StringOption.java:27-65`). |
| `//#define NAME value …` | **Rejected.** A leading comment is only allowed on booleans (`:367-372`). |
| `const int|float NAME = value; // [a b c]` | String const option. **NAME must be one of the whitelisted names** (`:57-97`): shadowMapResolution, shadowDistance, voxelDistance, shadowDistanceRenderMul, entityShadowDistanceMul, shadowIntervalSize, wetnessHalflife, drynessHalflife, eyeBrightnessHalflife, centerDepthHalflife, sunPathRotation, ambientOcclusionLevel, superSamplingLevel, noiseTextureResolution. |
| `const bool NAME = true|false;` | Boolean const option, NAME whitelisted. The whitelist adds: generateShadowMipmap, generateShadowColorMipmap, shadowHardwareFiltering[0-7], shadowtex[0/1]Mipmap, shadowtexMipmap, shadowtex[0/1]Nearest, shadowtexNearest, shadow[0/1]MinMagNearest, shadowcolor/shadowColor N Mipmap/Nearest/MinMagNearest (N 0–7). |

**Applying values** (`:487-536`):
- Boolean define → the line is commented out with `//`, or has its leading `//` stripped.
- String define → the line is replaced by `#define NAME value // OptionAnnotatedSource: Changed option`.
- Const → the first occurrence of the old value after `=` is replaced.
- Lines keep their positions, so line numbers are preserved.

**Merging across files** (`I/shaderpack/option/OptionSet.java:60-104`, `I/shaderpack/option/MergedBooleanOption.java:21-43`):
- The same name in several places merges if the default values are identical.
- If the defaults conflict, **the option is dropped** with a warning ("ambiguous").

**`#ifdef` and options.** Option *values* are applied textually before preprocessing. `#ifdef`/`#if` are then evaluated by JCPP with those values. The only special role of `#ifdef`/`#ifndef` is to *register* boolean options.

### 3.4 `const` directive parser (pack-wide and per-program settings)

**Line grammar** (`I/shaderpack/parsing/ConstDirectiveParser.java:20-122`):
`^\s*const\s+(int|float|vec2|ivec3|vec4|bool)\s+WORD\s*=\s*VALUE;`
- VALUE is the raw text up to the first `;`.
- `int` doubles as a "string" type for format names.
- Values must be literal: `vec4(1.0, 0.0, 0.0, 0.0)`, `true`/`false` only, `Float.parseFloat` (so no macros or expressions).

**Pack-wide const directives** (§2) are collected from the fragment sources of all program groups, in this order (`ProgramSet.java:213-269`): shadowcomp → begin → prepare → all `ProgramId` (gbuffers, shadow*, dh*, **final**) → deferred → composite. A **later** match overwrites an earlier one.

Full list:
- **Render targets:** `colortexN|<alias>Format`, `…Clear`, `…ClearColor`.
- **Shadow:** `shadowMapResolution` (int), `shadowMapFov`, `shadowDistance`, `shadowNearPlane`, `shadowFarPlane`, `voxelDistance`, `entityShadowDistanceMul`, `shadowDistanceRenderMul`, `shadowIntervalSize`, plus all of §2.4.
- **Misc:** `noiseTextureResolution` (int), `sunPathRotation`, `ambientOcclusionLevel` (clamped to 0..1), `wetnessHalflife` (default 600), `drynessHalflife` (default 200; **bug: writes wetnessHalflife**, `PackDirectives.java:283-284`), `eyeBrightnessHalflife` (default 10), `centerDepthHalflife` (default 1).
  (`PackDirectives.java:266-291`, `PackShadowDirectives.java:300-356`)

**Per-program (fragment only):** `const bool colortexNMipmapEnabled` / `<alias>MipmapEnabled`. Before that pass runs, Iris calls glGenerateMipmap on the read-side texture and switches its sampler to mipmapped until the end of the frame (`CompositeRenderer.java:194-215, 296-302`).

**Compute (`.csh`):**
- `const ivec3 workGroups = ivec3(x,y,z);` gives an absolute group count.
- `const vec2 workGroupsRender = vec2(sx, sy);` gives ceil(ceil(W·sx)/local.x) × ceil(ceil(H·sy)/local.y) × 1.
- The default is ceil(W/local.x) × ceil(H/local.y) × 1, where W×H is: screen (begin/prepare/deferred/composite/final), shadowMapResolution² (shadow computes), or 1×1 (setup).
- Refs: `ProgramSet.java:241-253`, `I/shaderpack/parsing/ComputeDirectiveParser.java`, `I/gl/program/ComputeProgram.java:47-96`.

### 3.5 Environment macros (always defined)

Sources: `StandardMacros.java:46-121`, `I/shaderpack/IrisDefines.java:25-40`, `ShaderPack.java:170-252`.

**Versions and platform**
- `MC_VERSION`: major·10000 + minor·100 + patch. **For 26.3 this is `260300`.**
- `MC_MIPMAP_LEVEL`, `IRIS_VERSION`, `MC_GL_VERSION`, `MC_GLSL_VERSION`.
- `MC_OS_{MAC,LINUX,WINDOWS,UNKNOWN}`, `MC_GL_VENDOR_*`, `MC_GL_RENDERER_*`.
- `MC_<every GL extension>`.

**Iris flags**
- `IS_IRIS`, `IRIS_REQUIRES_SEPARATE_ENTITY_DRAWS`, `IRIS_HAS_TRANSLUCENCY_SORTING`, `IRIS_TAG_SUPPORT 2`, `MAX_COLOR_BUFFERS 32`.

**Quality and textures**
- `MC_NORMAL_MAP`, `MC_SPECULAR_MAP`, `MC_RENDER_QUALITY 1.0`, `MC_SHADOW_QUALITY 1.0`, `MC_HAND_DEPTH 0.125`.
- Texture-format defines (labPBR).
- `MC_RENDER_STAGE_*` (§1.4).

**Distant Horizons**
- `DH_BLOCK_*` 0–15.
- `DISTANT_HORIZONS` / `DISTANT_HORIZONS_TEXTURES` (only when DH is present).

**Biome and precipitation**
- `BIOME_<PATH_UPPER> <id>` for every registered biome.
- `CAT_<CATEGORY> <i>`.
- `PPT_NONE 0`, `PPT_RAIN 1`, `PPT_SNOW 2`.

**Feature flags**
- `IRIS_FEATURE_<FLAG>` for each **optional** flag declared and supported (GLSL).
- shaders.properties additionally sees `IRIS_FEATURE_<FLAG>` for **every** usable flag (`ShaderPack.java:170-175`).
- `COLOR_SPACE_*` only if `supportsColorCorrection=true`.

---

## 4. shaders.properties

### 4.1 Loading

Loading: `ShaderProperties.java:130-151`. Preprocessor: `I/shaderpack/preprocessor/PropertiesPreprocessor.java:28-128`.

- **Encoding:** ISO-8859-1 (`ShaderPack.java:440-453`).
- **Preprocessing:** JCPP runs with these macros: boolean options that are currently **true** (defined without a value), string options = their current value, and the environment defines.
- **Line cleanup before preprocessing:**
  - Lines are trimmed and blank lines dropped.
  - Lines that start with `#` but are not a known preprocessor directive become empty (comments).
  - Every other `#` on a line is deleted.
  - Backslash line continuations are preserved.
- **Key order is preserved** (`OrderBackedProperties`).
- **Two parses.** Most keys are read from the *preprocessed* text. `screen*`, `sliders`, `profile.*` and `iris.features.*` are read from the **raw, un-preprocessed** file (`ShaderProperties.java:600-622`).
- **Booleans** accept `true`/`1` and `false`/`0` (`:661-672`).

### 4.2 Keys

| Key | Value / semantics | Default | Ref (`ShaderProperties.java`) |
|---|---|---|---|
| `texture.noise` | PNG path | generated | 156 |
| `clouds` | `off` / `fast` / `fancy` | unset | 161-168 |
| `dhClouds` | `off` / `on` | — | 170-178 |
| `shadow.culling` | `false` = distance, `true` = advanced, `reversed`/`safe_zone` | default | 180-187 |
| `oldHandLight` | bool: offhand light is merged into heldBlockLightValue | **true** | 189 |
| `dynamicHandLight` | parsed, **unused** | — | 190 |
| `oldLighting` | bool. Directional (vanilla) shading is disabled **unless** true | false | 191, `IrisRenderingPipeline.java:1167` |
| `shadowTerrain` / `shadowTranslucent` / `shadowEntities` / `shadowPlayer` / `shadowBlockEntities` / `shadowLightBlockEntities` | bool | true / true / true / **false** / true / **false** | 192-197, `PackShadowDirectives.java:87-92` |
| `underwaterOverlay`, `vignette` | bool | false, false | 198, 203 |
| `sun`, `moon`, `stars`, `sky` | bool: draw these vanilla sky parts | true | 199-202 |
| `weather` | `<bool> [<particles bool>]` | true, true | 259-267 |
| `backFace.solid` / `.cutout` / `.cutoutMipped` / `.translucent` | parsed, **unused** | — | 204-207 |
| `rain.depth` | bool: weather writes depth | false | 208 |
| `beacon.beam.depth` | parsed, **unused** | — | 210 |
| `allowConcurrentCompute` | bool: skip the barrier between computes | false | 209, `ComputeProgram.java:77-79` |
| `separateAo` | bool: AO in vertex alpha instead of multiplied into colour | false | 211 |
| `breaksAnisotropy`, `voxelizeLightBlocks` | bool | false | 212-213 |
| `separateEntityDraws` | bool (also forces particles MIXED) | false | 214-217 |
| `frustum.culling`, `occlusion.culling` | bool | true | 218, 220 |
| `endFlashShadows` | bool: the End flash becomes the shadow light | false | 219 |
| `shadow.enabled`, `dhShadow.enabled` | bool | unset | 221, 223 |
| `skipAllRendering` | bool | false | 222 |
| `particles.before.deferred`, `particles.ordering` (`BEFORE`/`MIXED`/`AFTER`) | parsed. **In this Iris the result is not consumed by any renderer.** | — | 224-235 |
| `prepareBeforeShadow` | parsed, **unused** | — | 229 |
| `supportsColorCorrection` | bool | false | 230 |
| `fallbackTex` | int: colortex index used by "default framebuffer" binds | 0 | 231 |
| `scale.<program>` | `scale [offX offY]`: viewport for composite-like passes, `origin = size·off`, `extent = size·scale` | 1 0 0 | 240-257, `CompositeRenderer.java:301-305` |
| `size.buffer.<colortexN\|alias>` | `W H`. A token containing `.` is a relative factor, otherwise absolute pixels | screen | 269-278, `I/gl/texture/TextureScaleOverride.java` |
| `alphaTest.<program>` | `off`/`false` → ALWAYS, else `<FUNC> <ref>`. FUNC ∈ NEVER, LESS, EQUAL, LEQUAL, GREATER, NOTEQUAL, GEQUAL, ALWAYS, GL_ALWAYS | per ShaderKey | 280-312, `I/gl/blending/AlphaTestFunction.java` |
| `blend.<program>` | `off`, or `srcRGB dstRGB srcA dstA` using ZERO, ONE, SRC_COLOR, ONE_MINUS_SRC_COLOR, DST_COLOR, ONE_MINUS_DST_COLOR, SRC_ALPHA, ONE_MINUS_SRC_ALPHA, DST_ALPHA, ONE_MINUS_DST_ALPHA, SRC_ALPHA_SATURATE | vanilla state (gbuffers); **disabled** (composite) | 314-372, `CompositeRenderer.java:510-518` |
| `blend.<program>.<colortexN\|alias>` | per-buffer blend (`off` or 4 factors) | — | 315-355 |
| `indirect.<computeProgram>` | `<ssboIndex> <byteOffset>`: glDispatchComputeIndirect | — | 374-382 |
| `program.<path>.enabled` | Boolean expression over option names (`&&`, `\|\|`, `!`, parentheses, `true`/`1`, `false`/`0`). `<path>` is the file path without the extension, e.g. `world0/composite7`. **A parse error evaluates to true** | — | 384, `I/shaderpack/parsing/BooleanParser.java`, `ShaderPack.java:261-265` |
| `bufferObject.<0-12>` | `<bytes> [<initial-data file>]` or `<bytesPerPixel> <relative bool> <scaleX> <scaleY>`. Size < 1 disables it. **Requires feature flag `SSBO`** | — | 386-443, `ShaderPack.java:180-218` |
| `texture.<stage>.<sampler>` | PNG path, `namespace:path` resource, `minecraft:dynamic/lightmap_1`, or raw (see below) | — | 445-487 |
| `customTexture.<name>` | Same value forms; visible in **all** stages | — | 489-512 |
| `image.<name>` | `<sampler\|none> <pixelFormat> <internalFormat> <pixelType> <clear> <relative> (<relW> <relH> \| <w> [<h> [<d>]])` → 1D/2D/3D image. Max 16. **Requires `CUSTOM_IMAGES`** | — | 514-571 |
| `flip.<pass>.<buffer>` | bool (§2.2). `<pass>` may be `<stage>_pre` | — | 573-574 |
| `variable.<type>.<name>` / `uniform.<type>.<name>` | expression (§4.3) | — | 576-594 |
| `iris.features.required` / `.optional` | space list (raw file) | — | 605-606 |
| `sliders` | space list of option names shown as sliders | — | 610 |
| `profile.<NAME>` | space list (§7.3) | — | 611 |
| `screen` | main screen element list | `*` | 616-621 |
| `screen.columns`, `screen.<NAME>.columns` | int | — | 613-617 |
| `screen.<NAME>` | sub-screen element list | — | 621 |

**Texture stages for `texture.<stage>.*`** (`I/shaderpack/texture/TextureStage.java:43-51`): `setup`, `begin`, `shadowcomp`, `prepare`, `gbuffers` (this one also covers shadow), `deferred`, `composite` (this one also covers final).

**Raw texture value form:** `<path> <TEXTURE_1D|TEXTURE_2D|TEXTURE_RECTANGLE|TEXTURE_3D> <internalFormat> <sizeX> [<sizeY> [<sizeZ>]] <pixelFormat> <pixelType>`. It has 6, 7 or 8 tokens.
- For `texture.<stage>.<name>`, a raw texture is given an internal name `customtexN`. GLSL samplers called `<name>` (with a matching type) are renamed to it (`I/pipeline/transform/transformer/TextureTransformer.java`).
- `.mcmeta` next to a PNG can set `blur`/`clamp`.
- `customTexture` raw textures default to blur and clamp (`ShaderPack.java:505-525`).

**Images in GLSL.**
- `colorimgN` gives image access to colortexN (read side).
- `shadowcolorimgN` gives image access to shadowcolorN.
- Custom images are accessed by `image.<name>`'s key, with an optional sampler alias (`IrisImages.java`, `IrisSamplers.java:241-247`).
- Images with `clear=true` are cleared at frame start (`IrisRenderingPipeline.java:258-270, 892`).

**SSBOs**
- Bound with `glBindBufferBase(GL_SHADER_STORAGE_BUFFER, index)` and zero-initialised.
- Relative buffers are reallocated on resize (`I/gl/buffer/ShaderStorageBuffer.java:40-80`).

**Feature flags** (`I/features/FeatureFlags.java:12-25`; case-insensitive; `TESSELATION_SHADERS` alias): `SEPARATE_HARDWARE_SAMPLERS`, `HIGHER_SHADOWCOLOR`, `CUSTOM_IMAGES`, `PER_BUFFER_BLENDING`, `COMPUTE_SHADERS`, `TESSELLATION_SHADERS`, `ENTITY_TRANSLUCENT`, `REVERSED_CULLING`, `BLOCK_EMISSION_ATTRIBUTE`, `CAN_DISABLE_WEATHER`, `SSBO`, `FADE_VARIABLE`, `TEXTURE_FILTERING`.
- A missing **required** flag disables shaders.
- **[CR]** `iris.features.optional = CUSTOM_IMAGES SSBO BLOCK_EMISSION_ATTRIBUTE FADE_VARIABLE ENTITY_TRANSLUCENT`.

### 4.3 Custom uniforms / variables (expression language)

Sources: `I/uniforms/custom/CustomUniforms.java`, `I/parsing/IrisFunctions.java`, `I/parsing/IrisOptions.java`, `K/`.

**Declaration.** `uniform.<type>.<name> = expr` is uploaded to programs. `variable.<type>.<name> = expr` is CPU-only.
- Types: `bool`, `int`, `float`, `vec2`, `vec3`, `vec4` (`CustomUniforms.java:298-306`).
- A duplicate name is ignored (first wins).
- Variables can reference earlier or later variables. They are topologically sorted; cycles and unresolved names break the dependent uniform (`:75-140`).

**Inputs.** Every *non-dynamic* built-in uniform (§5: Camera, Viewport, WorldTime, SystemTime, Biome, Celestial, IrisExclusive, IrisTime, Matrix, IdMap, general common, IrisInternal). `fog*`, `entityId`, `atlasSize`, `renderStage`, `blendFunc` and other *dynamic* uniforms are **not** inputs.
- Vector components are accessed with `.x .y .z .w` / `.r .g .b .a` / `.s .t .p .q`.
- Macros (option values, `BIOME_*`, …) are expanded first by the properties preprocessor.

**Operators** (`IrisOptions.java:10-50`). Lower number = tighter binding.

| Precedence | Operators |
|---|---|
| 0 | `*`, `/`, `%` |
| 1 | `+`, `-` |
| 2 | `==`, `!=`, `<`, `>`, `<=`, `>=` (also `≠`, `≤`, `≥`) |
| 3 | **`&&` and `\|\|` (same precedence)** |
| unary | `!`, `-` |

`int`/`float` promote implicitly. `%` on ints is Java remainder.

**Functions** (`IrisFunctions.java:39-72, 395-560`):
- `sin cos tan asin acos atan atan2(y,x)`
- `torad/radians todeg/degrees`
- `min max` (n-ary), `clamp(x,lo,hi)`
- `abs floor ceil round frac signum/sign sqrt inversesqrt exp exp2 exp10 log log2 log10 pow(x,y)`
- `fmod(x,y)` (floor-mod)
- `edge(e,x)` (= step)
- `mix`
- `random()` → [0,1), `random(min,max)`, `randomInt([bound] | min,max)`
- `if(c, a, [c2, b, …], else)`
- `between(x,lo,hi)`, `equals(x,y,eps)`, `in(x, v1, v2, …)`
- `vec2/vec3/vec4(...)` constructors, component-wise vector ops
- `smooth([id,] x, [fadeUp, [fadeDown]])`. The id is ignored. Defaults are 1 s. It is exponential smoothing with half-life = 0.1 · fadeTime seconds and Δt = `frameTime`. The first value is not smoothed (`I/parsing/SmoothFloat.java`).

**Update.** All custom uniforms are evaluated **once per frame** in `beginLevelRendering` (`IrisRenderingPipeline.java:941`), then pushed to each program.

**[CR] uses:** `frameCounter % N`, `smooth`, `if`, `in(biome, BIOME_*)`, `cameraPosition.x - previousCameraPosition.x`, `endFlashIntensity`.

---

## 5. Built-in uniforms

**Update frequencies** (`I/gl/uniform/UniformUpdateFrequency.java`, `I/gl/program/ProgramUniforms.java:200-240`):
- **ONCE**: uploaded on first use.
- **PER_TICK**: re-uploaded when `level.getGameTime()` changes.
- **PER_FRAME**: re-uploaded when `frameCounter` changes.
- **DYN** (dynamic): re-uploaded on a state-change notifier (texture bind, phase change, fog change, …), i.e. per draw.

All values are per-program uploads. "Shift" below means the 30 000-block camera recentring.

### 5.1 Camera / viewport / time

| Name | Type | Freq | Semantics | Ref |
|---|---|---|---|---|
| `near` | float | once | 0.05 | `I/uniforms/CameraUniforms.java:26` |
| `far` | float | frame | effective render distance × 16 | `:27, 36-39` |
| `cameraPosition` | vec3 | frame | camera world position + shift. Shift = −(v − v mod 30000) on X/Z when \|v\| > 30000 or a jump > 1000 | `:28, 58-130` |
| `previousCameraPosition` | vec3 | frame | previous frame's value (same shift applied) | `:30` |
| `eyeAltitude` | float | frame | cameraPosition.y | `:29` |
| `cameraPositionInt` / `cameraPositionFract` | ivec3 / vec3 | frame | floor / fract of the **unshifted** position | `:31-32` |
| `previousCameraPositionInt` / `…Fract` | ivec3 / vec3 | frame | same, previous frame | `:33-34` |
| `viewWidth`, `viewHeight` | float | frame | MC main render target size | `I/uniforms/ViewportUniforms.java:27-28` |
| `aspectRatio` | float | frame | width / height | `:29` |
| `worldTime` | int | tick | defaultClockTime % 24000. **0 in fixed-time dimensions**, except Nether/End, which keep the real value | `I/uniforms/WorldTimeUniforms.java:25, 30-44` |
| `worldDay` | int | tick | defaultClockTime / 24000 | `:26` |
| `moonPhase` | int | tick | environment attribute MOON_PHASE index | `:27` |
| `frameCounter` | int | frame | +1 per frame, mod 720720 | `I/uniforms/SystemTimeUniforms.java:28, 44-46` |
| `frameTime` | float | frame | seconds since the previous frame (ms resolution) | `:29, 60-80` |
| `frameTimeCounter` | float | frame | Σ frameTime, wraps at 3600 | `:30` |
| `currentDate` / `currentTime` | ivec3 | tick | (Y, M, D) / (h, m, s), local time | `I/uniforms/IrisTimeUniforms.java:21-22` |
| `currentYearTime` | ivec2 | tick | (seconds elapsed this year, seconds remaining) | `:23-26` |

### 5.2 Matrices

All are PER_FRAME (`I/uniforms/MatrixUniforms.java:20-52`, `I/uniforms/IrisInternalUniforms.java:57-82`).

| Name | Semantics |
|---|---|
| `gbufferModelView`, `gbufferProjection` (+ `Inverse`, + `gbufferPrevious…`) | Player view and projection captured at renderLevel start. The view has no translation (camera-relative world). `Previous` is the last frame's value |
| `shadowModelView`, `shadowProjection` (+ `Inverse`) | Computed as in §2.4 (recomputed on the CPU, not read back from the pass) |
| `dhProjection` (+ `Inverse`, `Previous`) | Distant Horizons |
| `iris_DefaultNormalMat` | mat3: transpose(inverse(gbufferModelView)) |
| `iris_DefaultProjectionMatrixInverse`, `iris_DefaultModelViewMatrixInverse`, `iris_ShadowModelViewMatrixInverse`, `iris_ShadowProjectionMatrixInverse` | internal |
| `iris_LightmapTextureMatrix` | once: scale 1/256 with offset 1/32 (`I/uniforms/builtin/BuiltinReplacementUniforms.java:10-21`) |

### 5.3 Celestial

All PER_FRAME (`I/uniforms/CelestialUniforms.java:30-180`).

| Name | Type | Semantics |
|---|---|---|
| `sunAngle` | float | (SUN_ANGLE + 90°) wrapped to [0,360), divided by 360 |
| `shadowAngle` | float | sunAngle when it is day (sunAngle < 0.5), else the moon angle, /360 |
| `sunPosition` | vec3 | gbufferModelView · RotY(−90) · RotZ(sunPathRotation) · RotX(SUN_ANGLE) · (0, 100, 0, 1) |
| `moonPosition` | vec3 | same with MOON_ANGLE. **Uses +100**, not −100 |
| `shadowLightPosition` | vec3 | sun or moon position (or End-flash position if `endFlashShadows`) |
| `upPosition` | vec3 | gbufferModelView · RotY(−90) · (0, 100, 0, 0) |
| `endFlashPosition` | vec3 | End flash direction × 100 in view space |

### 5.4 Player / world state (`CommonUniforms.generalCommonUniforms`)

`I/uniforms/CommonUniforms.java:140-186`

| Name | Type | Freq | Semantics |
|---|---|---|---|
| `hideGUI` | bool | frame | F1 |
| `isRightHanded` | bool | frame | main hand is right |
| `isEyeInWater` | int | frame | 0 = none, 1 = water, 2 = lava (not in spectator), 3 = powder snow |
| `blindness` | float | frame | 1 if infinite, else clamp(duration/20) |
| `darknessFactor` | float | frame | Darkness effect blend factor |
| `darknessLightFactor` | float | frame | lightmap darkness |
| `nightVision` | float | frame | night-vision scale, or conduit water vision |
| `is_sneaking`, `is_sprinting`, `is_hurt` (hurtTime > 0), `is_invisible`, `is_burning`, `is_on_ground` | bool | frame | player state |
| `screenBrightness` | float | frame | gamma option |
| `entityColor` | vec4 | once | 0. Overridden per vertex in the transform (§8) |
| `blockEntityId`, `currentRenderedItemId` | int | once | −1. Overridden per vertex (§6) |
| `anisotropicFiltering` | int | frame | option |
| `pi` | float | once | π |
| `playerMood`, `constantMood` | float | tick | 0..1 |
| `eyeBrightness` | ivec2 | frame | (block light × 16, sky light × 16) at the eye block |
| `eyeBrightnessSmooth` | ivec2 | frame | eyeBrightness smoothed with eyeBrightnessHalflife |
| `rainStrength` | float | tick | clamp(level rain level) |
| `wetness` | float | tick | rainStrength smoothed: up = wetnessHalflife, down = drynessHalflife |
| `skyColor` | vec3 | frame | SKY_COLOR environment attribute |
| `dhFarPlane`, `dhNearPlane`, `dhRenderDistance` | | frame | Distant Horizons |

### 5.5 Iris-exclusive

`I/uniforms/IrisExclusiveUniforms.java:38-91, 280-336`

| Name | Type | Freq |
|---|---|---|
| `currentColorSpace` | int | tick |
| `chunkFadeTimeInv` | float | frame |
| `textureFilteringMode` (0 none, 1 RGSS, 2 anisotropic) | int | frame |
| `feetInWater`, `inSwimmingAnimation`, `isRiding`, `isElytraFlying`, `vehicleInWater`, `heavyFog`, `isSpectator` | bool | tick |
| `vehicleId` | int | tick |
| `vehicleLookVector`, `relativeVehiclePosition` | vec3 | frame |
| `thunderStrength` | float | frame |
| `currentPlayerHealth`, `maxPlayerHealth`, `currentPlayerHunger`, `maxPlayerHunger` (20), `currentPlayerArmor`, `maxPlayerArmor` (50), `currentPlayerAir`, `maxPlayerAir` | float | tick |
| `endFlashIntensity`, `previousEndFlashIntensity` | float | tick |
| `firstPersonCamera` | bool | frame |
| `currentSelectedBlockId` | int | frame |
| `currentSelectedBlockPos` | vec3 | frame |
| `seaLevel` | int | frame |
| `eyePosition` | vec3 | frame |
| `relativeEyePosition` = cameraPos(unshifted) − eyePosition | vec3 | frame |
| `playerLookVector`, `playerBodyVector` | vec3 | frame |
| `cloudTime` | float | tick |
| `lightningBoltPosition` (xyz, w = 1 if present) | vec4 | tick |
| `bedrockLevel`, `heightLimit`, `logicalHeightLimit` | int | frame |
| `cloudHeight`, `ambientLight` | float | frame |
| `hasCeiling`, `hasSkylight` | bool | frame |

### 5.6 Biome

`I/uniforms/BiomeUniforms.java:28-55`

| Name | Type | Freq | Semantics |
|---|---|---|---|
| `biome` | int | tick | id from `BIOME_*` |
| `biome_category` | int | tick | `CAT_*` |
| `biome_precipitation` | int | tick | 0 none, 1 rain, 2 snow |
| `rainfall` | float | tick | biome downfall |
| `temperature` | float | tick | base temperature |

### 5.7 ID-map uniforms

`I/uniforms/IdMapUniforms.java:30-40`

| Name | Type | Freq | Semantics |
|---|---|---|---|
| `heldItemId`, `heldItemId2` | int | frame | item.properties id of the main / off hand item, keyed by `ITEM_MODEL` component or registry id; −1 if unmapped |
| `heldBlockLightValue`, `heldBlockLightValue2` | int | frame | light emission. With `oldHandLight`, the main hand takes the max of both hands |
| `heldBlockLightColor`, `heldBlockLightColor2` | vec3 | frame | light colour |

### 5.8 Dynamic (per draw) uniforms

`CommonUniforms.addDynamicUniforms:67-117`, `I/uniforms/FogUniforms.java:20-55`, `IrisInternalUniforms.java:31-55`

| Name | Type | Semantics |
|---|---|---|
| `entityId` | int | fallback for the current entity (lightning) |
| `atlasSize` | ivec2 | size of the bound atlas, or 0 |
| `gtextureId` | int | bound albedo GL id |
| `gtextureSize` | ivec2 | size of the bound albedo texture |
| `textureReloadCount` | int | |
| `blendFunc` | ivec4 | (srcRGB, dstRGB, srcA, dstA) GL enums, or 0 if blending is off |
| `renderStage` | int | §1.4 |
| `fogMode` | int | GL_LINEAR (0x2601) if density < 0, else GL_EXP2 (0x0801). **0 in composite/compute** |
| `fogShape` | int | 1 (cylindrical). **−1 in composite** |
| `fogDensity` | float | max(0, density) |
| `fogStart`, `fogEnd` | float | MC environmental fog start / end |
| `fogColor` | vec3 | captured fog colour (frame) |
| `iris_FogColor` | vec4 | internal fog colour |
| `iris_FogStart`, `iris_FogEnd`, `iris_FogDensity` | float | internal fog parameters |
| `iris_currentAlphaTest`, `alphaTestRef` | float | current alpha reference |

### 5.9 Composite-only

`centerDepthSmooth`: depth at the screen centre, sampled at `beginHand` and smoothed with `centerDepthHalflife`. It is delivered by sampling the `iris_centerDepthSmooth` texture (§8).

### 5.10 Not present in this Iris

`HardcodedCustomUniforms` (`timeAngle`, `day`, `night`, `isDry`, `velocity`, `starter`, …) exists in the source but **is never registered** (`I/uniforms/HardcodedCustomUniforms.java:25`; no callers). Packs must define these via `uniform.*`. **[CR]** defines its own.
So `velocity` (the camera's per-frame movement there) is **not** an Iris uniform: a pack that declares `uniform float velocity`
without a `uniform.float.velocity` reads 0, as under Iris; the mod provides nothing for it on purpose.

---

## 6. Vertex attributes and IDs

### 6.1 Vertex formats

Iris vertex formats are defined in `I/vertices/IrisVertexFormats.java:15-82`. Attribute locations are bound in `I/gl/shader/ProgramCreator.java:19-26`: `mc_Entity`/`iris_Entity` = 11, `mc_midTexCoord` = 12, `at_tangent` = 13, `at_midBlock` = 14, `Position` = 0, `UV0` = 1.

| Format | Elements |
|---|---|
| TERRAIN | Position, Color, UV0, UV2, Normal, pad, **`mc_Entity`** (2×short), **`mc_midTexCoord`** (2×float), **`at_tangent`** (4×byte normalized), **`at_midBlock`** (4×byte) |
| ENTITY | Position, Color, UV0, UV1 (overlay), UV2, Normal, pad, **`iris_Entity`** (4×ushort: entityId, blockEntityId, itemId), `mc_midTexCoord`, `at_tangent` |
| GLYPH (text) | Position, Color, UV0, UV2, Normal, pad, `iris_Entity`, `mc_midTexCoord`, `at_tangent` |
| CLOUDS | Position, Color, Normal |

| GLSL attribute | Semantics | Ref |
|---|---|---|
| `mc_Entity` | Terrain. **.x = block.properties id** (−1 if unmapped), **.y = render-type bit** (`renderType & 1`). On the Sodium path it is packed as `((id+1)<<1) \| (rt&1)` and decoded in GLSL. The pack's float/vec declaration is re-typed to integer and converted (`CommonTransformer.patchIntegerAttribute`) | `I/vertices/sodium/terrain/XHFPTerrainVertex.java:196-198`, `I/pipeline/transform/transformer/SodiumTransformer.java:252-311`, `I/pipeline/transform/transformer/CommonTransformer.java:137-196` |
| `mc_midTexCoord` | Atlas UV of the quad centre. `gl_MultiTexCoord3` is a legacy alias | `CommonTransformer.java:116-135` |
| `at_tangent` | xyz tangent, w handedness (normalized byte) | |
| `at_midBlock` | xyz = (block centre − vertex) · 64 as signed bytes. **w = block light emission** (needs `BLOCK_EMISSION_ATTRIBUTE`) | `I/vertices/ExtendedDataHelper.java:11-21`, `XHFPTerrainVertex.java:160-163` |
| `at_velocity` | **Not supported** by this Iris (no references) | — |
| `iris_Entity` → `entityId` / `blockEntityId` / `currentRenderedItemId` | The transformer replaces those uniform names with `iris_entityInfo.x/.y/.z`, a flat varying from the per-vertex `iris_Entity` | `I/pipeline/transform/transformer/EntityPatcher.java:125-205` |

### 6.2 ID maps

Parsing: `I/shaderpack/IdMap.java:69-275`, `I/shaderpack/materialmap/BlockEntry.java:18-130`.

**Files:** block.properties, item.properties, entity.properties.
- ISO-8859-1.
- Preprocessed with option and environment macros.
- `\`-continuations are joined.
- **Ordering is significant.**

**block.properties**
- `block.<int> = <entries…>`.
- Entry forms: `name` (minecraft namespace), `ns:name`, `name:prop=val[:prop=val]`, `ns:name:prop=val…`, `%tag` / `%ns:tag[:props]` (block tag).
- **First match wins** (putIfAbsent) over states (`I/shaderpack/materialmap/BlockMaterialMapping.java:63-98`).
- `layer.<solid|cutout|cutout_mipped|translucent> = blocks` overrides the chunk render layer.
- Without a block.properties, the legacy numeric IDs are used (`I/shaderpack/materialmap/LegacyIdMap.java`).

**item.properties / entity.properties**
- `item.<int>` / `entity.<int>` = space list of `ns:name`. State filters are not allowed: an entry with `=` is skipped with a
  warning (`I/shaderpack/IdMap.java:187-192`), also in item.properties. Block items take the **block.properties** id of the
  block's default state instead (state filters apply there), with `blockEntityId = 1` (`ItemStackStateLayerMixin`). Unmapped = −1.

**Special entity names** (from the mixins):
- `minecraft:current_player` (entity id of the camera player), `minecraft:zombie_villager_converting` (a converting zombie
  villager, only if the pack maps it), `minecraft:name_tag` (name tags), `minecraft:entity_flame` (fire on burning entities),
  `minecraft:end_crystal_beam`, `minecraft:lightning_bolt` (the lightning render type; the same as the entity type's id).
- item.properties: `minecraft:player_cape` (cape layer), `minecraft:elytra_with_cape` (wings of a player with a cape),
  `trim_<material>` (armor trims).
- `minecraft:entity_shadow` is declared (`I/mixin/MixinEntityRenderDispatcher.java:40`) but **never used**: Iris only
  suppresses vanilla entity shadows when the pack renders a shadow map.

**Context IDs**
- `entityId` is set per entity draw.
- `blockEntityId` is set per block-entity draw (the block-state id of the block entity's block).
- `currentRenderedItemId` is set per item draw (item.properties of the item / its model).
- `heldItemId` is set per frame.
- Refs: `I/mixin/entity_render_context/*`.

---

## 7. Settings file `shaderpacks/<pack>.txt`

Refs: `I/Iris.java:265-352, 445-480, 516-555`, `I/shaderpack/option/values/MutableOptionValues.java:49-98`.

### 7.1 Format and encoding

- **Format:** `java.util.Properties`, **ISO-8859-1**, `key=value`, `#` comments. Iris writes it with `Properties.store`, which adds a date comment and escapes non-Latin-1 characters as `\uXXXX`.
- **Location:** next to the pack zip or folder, named `<packName>.txt`. The pack name includes `.zip` if it is a zip, e.g. `ComplementaryReimagined_r5.9.3.zip.txt`.

### 7.2 Semantics

- **Keys** are option names: `#define` names or the whitelisted const names.
- **Boolean options:** the value is exactly `true` or `false`. Anything else means default.
- **String options:** any string. It is **not validated** against the allowed list. It is written into `#define NAME value` / the const value textually.
- **Unknown keys** are ignored.
- **Only non-default values are kept.** On every load, Iris rewrites the file with only the values that differ from the defaults, and **deletes the file** if there are none.
- **Application.** Before preprocessing: booleans comment or uncomment their `#define` lines, strings rewrite the value (§3.3). The same values feed the shaders.properties preprocessor as macros: booleans as defined/undefined, strings as their value.

### 7.3 Profiles

**`profile.NAME` tokens** (`I/shaderpack/option/ProfileSet.java:50-87`):
- `OPT=val` or `OPT:val` sets a string option.
- `BOOL` sets a boolean option to true (only if BOOL is a known boolean option).
- `!BOOL` sets it to false.
- `profile.OTHER` includes another profile (recursion is an error).
- `!program.<path>` disables a program while this profile is active.

**Matching.**
- Profiles are **never stored** in the .txt file. The active profile is **detected**: the first profile, sorted by number of constraints (descending), whose every option equals the current value (`ProfileSet.java:20-35, 97-121`; `I/shaderpack/option/Profile.java:19-41`).
- No match → the profile is "Custom".
- Selecting a profile in the GUI just queues its option values (`Iris.java:520-522`).

**Disabled programs.** The `disabledPrograms` of the *detected* profile are applied, together with `program.X.enabled` expressions (`ShaderPack.java:257-265`).

**[CR]** `screen=… <profile> …`. Its profiles are defined via `profile.*` in shaders.properties.

---

## 8. GLSL transformation (glsl-transformer)

Refs: `I/pipeline/transform/TransformPatcher.java:85-215` plus `I/pipeline/transform/transformer/*`.

**Input:** fully preprocessed source with only `#version`, `#extension` and `#pragma`. Any other directive is an error (`TransformPatcher.java:74-83`).

**Reserved identifiers.** Any identifier starting with `iris_`, `irisMain` or `moj_import` is rejected (`:118-126`).

### 8.1 Profile dispatch

| Source `#version` | Path | Version written |
|---|---|---|
| `compute` | `CommonTransformer` (core) | core |
| `core` profile, or ≥150 with no profile, or lines | "core" transformers (`VanillaCoreTransformer` / `CompositeCoreTransformer` / `SodiumCoreTransformer`) + `transformFragmentCore` | max(v, 330) |
| otherwise (**e.g. `#version 120/130`, `compatibility`**) | compat transformers (`VanillaTransformer` / `CompositeTransformer` / `SodiumTransformer`) | max(v, 330) **core** |

After either path: `TextureTransformer` (custom raw texture renames), then `CompatibilityTransformer.transformEach`. After all stages: `transformGrouped` (`TransformPatcher.java:142-205`).

**[CR]** Nearly all programs use `#version 130` (two use `400/430 compatibility`). They use `texture2D`, `gl_FragData`, `ftransform`, `gl_TextureMatrix`, `attribute`/`varying`, `shadow2D` and so on, so the **compat path is the one that matters**.

### 8.2 CommonTransformer (all non-core-mode stages)

Ref: `CommonTransformer.java:233-387`.

| Legacy construct | Rewritten to |
|---|---|
| `gl_FogFragCoord` | `iris_FogFragCoord` (VS `out` = 0.0, FS `in`) |
| `gl_FrontColor` (VS) | dummy global `iris_FrontColor` |
| `gl_FragColor` | `gl_FragData[0]` |
| `gl_FragData[<const int i>]` | `layout(location=i) out vec4 iris_FragData<i>`. **Only literal indices are supported** |
| alpha test (non-core, `iris_FragData0` written, test ≠ ALWAYS) | appended at the end of `main`: `if (!(iris_FragData0.a <op> iris_currentAlphaTest)) discard;` (`I/gl/blending/AlphaTest.java:12-30`) |
| `gl_TexCoord` (FS) | `in vec4 irs_texCoords[3]` |
| `gl_Color` (FS) | `in vec4 irs_Color` |
| `attribute` | `in` |
| `varying` | `out` (VS) / `in` (FS) |
| sampler named `texture` or `gcolor` (non-call uses) | renamed `gtexture` (only one declaration is kept) |
| `gl_Fog` | `irisInt_Fog` struct {color, density, start, end, scale}. Built from `iris_Fog*` uniforms (composite) or from the `iris_Fog` UBO (vanilla) |
| `texture2D`, `texture3D` | `texture` |
| `texture2DLod`, `texture3DLod` | `textureLod` |
| `texture2DProj`, `texture3DProj` | `textureProj` |
| `texture2DGrad`, `texture2DGradARB`, `texture3DGrad` | `textureGrad` |
| `texelFetch2D`, `texelFetch3D` | `texelFetch` |
| `textureSize2D` | `textureSize` |
| `shadow2D(s,c)` / `shadow2DLod` | `vec4(texture(s,c))` / `vec4(textureLod(...))` |

### 8.3 Gbuffers / shadow, compat path (`VanillaTransformer`)

Ref: `VanillaTransformer.java:14-371`.

**Injected UBOs.** These mirror MC 26 blaze3d's std140 blocks:
- `iris_Fog {FogColor, FogEnvironmentalStart/End, FogRenderDistanceStart/End, FogSkyEnd, FogCloudsEnd}`
- `iris_DynamicTransforms {ModelViewMat, ColorModulator, ModelOffset, TextureMat}`
- `iris_Projection {iris_ProjMat}`
- `iris_Globals {CameraBlockPos, CameraOffset, ScreenSize, GlintAlpha, GameTime, MenuBlurRadius}`
- `const float mc_chunkFade = -1.0`

**Matrix and input mapping**

| Legacy | Replacement |
|---|---|
| `gl_Vertex` | `vec4(iris_Position, 1.0)` (`in vec3 iris_Position`). For clouds: generated from the `CloudFaces` buffer. For lines: see below |
| `gl_ModelViewMatrix` | `(iris_transforms.ModelViewMat * translate(iris_transforms.ModelOffset))` — **"chunk offset"**: MC draws chunk sections relative to the camera and supplies the section offset in `ModelOffset`. Lines get an extra `VIEW_SHRINK` scale (1 − 1/256) |
| `gl_ProjectionMatrix` | `iris_ProjMat` |
| `gl_ModelViewProjectionMatrix` | `(gl_ProjectionMatrix * gl_ModelViewMatrix)`, expanded as above |
| `ftransform()` | defined as `gl_ModelViewProjectionMatrix * gl_Vertex` |
| `gl_NormalMatrix` | `uniform mat3 iris_NormalMat` = transpose(inverse(MC ModelView)), set per draw |
| `gl_ModelViewMatrixInverse` / `gl_ProjectionMatrixInverse` | `iris_ModelViewMatInverse` / `iris_ProjMatInverse` (per draw; `I/pipeline/programs/ExtendedShader.java:150-195`) |
| `gl_TextureMatrix[0]` | `iris_transforms.TextureMat` |
| `gl_TextureMatrix[1]` | `iris_LightmapTextureMatrix` (the 1/256 + 1/32 matrix) |
| `gl_MultiTexCoord0` | `vec4(iris_UV0, 0, 1)`, or `(0.5, 0.5, 0, 1)` if the format has no UV |
| `gl_MultiTexCoord1` / `gl_MultiTexCoord2` | `vec4(iris_UV2, 0, 1)` (`in ivec2`, 0–240 lightmap) or `(240, 240, 0, 1)`. `gl_MultiTexCoord2` is an alias of 1 |
| `gl_MultiTexCoord3` | `mc_midTexCoord` |
| `gl_MultiTexCoord4..7` | `vec4(0, 0, 0, 1)` |
| `gl_Color` (VS) | `iris_Color * iris_transforms.ColorModulator`. Without vertex colour: `ColorModulator`. For glint, alpha is × `GlintAlpha`. For VERTEX_ALPHA keys, alpha = `ColorModulator.a` (and the alpha test compares against `iris_vertexColorAlpha`) |
| `gl_Normal` | `iris_Normal`, or `(0, 0, 1)` if the format has none or for new-style lines |
| `mc_Entity` | integer attribute and alias (§6) |
| overlay / entity | `entityColor` = `vec4(overlay.rgb, 1 − overlay.a)` from `texelFetch(iris_overlay, iris_UV1)`, passed VS→(TCS/TES/GS)→FS; `entityId`/`blockEntityId`/`currentRenderedItemId` come from `iris_Entity` (`EntityPatcher.java:40-205`) |

**Lines.** `main` is renamed to `irisMain`, which is called twice (end point, then start point using `iris_Normal` as the offset). The result is expanded into screen-space quads of width `iris_LineWidth` (`VanillaTransformer.java:190-224`).

### 8.4 Core-mode gbuffers (`VanillaCoreTransformer`)

The same UBOs are injected. The renames follow the OptiFine core names:
- `vaPosition/vaColor/vaUV0/vaUV1/vaUV2/vaNormal` → `iris_*`
- `modelViewMatrix` / `projectionMatrix` / `textureMatrix` / `normalMatrix` (+ `Inverse`)
- `chunkOffset` → `iris_transforms.ModelOffset`
- `alphaTestRef` → `iris_currentAlphaTest`

Fragment outputs named `outColor0..7` without a layout get `layout(location=N)` (`CompatibilityTransformer.java:633-700`).

### 8.5 Composite / final / deferred / begin / prepare / shadowcomp (`CompositeTransformer`)

Ref: `CompositeTransformer.java:32-85`.

| Legacy | Replacement |
|---|---|
| `gl_MultiTexCoord0` | `vec4(UV0, 0, 1)` (`in vec2 UV0`) |
| `gl_MultiTexCoord1..7` | `(0, 0, 0, 1)` |
| `gl_Vertex` | `vec4(Position, 1)` |
| `gl_Color` | white |
| `gl_Normal` | `(0, 0, 1)` |
| `gl_NormalMatrix` | identity |
| `gl_TextureMatrix[0..7]` | identity |
| `gl_ModelViewMatrix` | identity |
| `gl_ProjectionMatrix` | `mat4(2, 0, 0, 0,  0, 2, 0, 0,  0, 0, 0, 0,  −1, −1, 0, 1)` (maps the 0..1 quad to NDC) |
| `ftransform` | renamed `iris_ftransform` (HD4000 workaround) |
| `uniform float centerDepthSmooth` | removed; uses become `texture(iris_centerDepthSmooth, vec2(0.5)).r` (`I/pipeline/transform/transformer/CompositeDepthTransformer.java:15-53`) |

The alpha test is never injected here (core = true).

### 8.6 CompatibilityTransformer fixes (all programs)

Ref: `CompatibilityTransformer.java:157-349, 373-631`.

- **Const parameters.** `const` is removed from local declarations initialised from `const` function parameters (transitively).
- **Unused functions** (other than `main`) are deleted (not in debug mode).
- **Empty declarations** `;` at global scope are removed.
- **Reserved words.** Identifiers `texture` and `sample` that are not function calls are renamed `iris_renamed_*`.
- **Unsized arrays.** Unsized array specifiers on struct member types are moved to each declarator.
- **Sildur's water fix:** `fract(worldpos.y + 0.001)` → `+ 0.01`.
- **Grouped (cross-stage):**
  - An `in` in GS/FS without a matching `out` in the previous stage → the `out` is added and zero-initialised at the start of main.
  - A type mismatch between `out` and `in` is patched (scalar/vector truncation or extension).

### 8.7 Explicit-layout transform

`LayoutTransformer` assigns explicit locations to all inter-stage `in`/`out` variables. **It is disabled** (`IrisLimits.VK_CONFORMANCE = false`). Under OpenGL, Iris relies on GL's name-based linking, **implicit attribute binding by name** (`glBindAttribLocation`), and **loose `uniform` variables** set by name.

---

## 9. Consequences for a Vulkan / SPIR-V port (derived)

1. **Uniforms.** Every pack `uniform <non-opaque>` (all of §5, custom uniforms, `iris_*` loose uniforms such as `iris_NormalMat`, `iris_currentAlphaTest`, `iris_ModelViewMatInverse`) must be gathered into one or more std140 UBOs or push constants. Built-ins can be assigned from a single per-frame UBO plus a per-draw block. The `iris_DynamicTransforms`, `iris_Projection`, `iris_Globals` and `iris_Fog` blocks already match MC's blaze3d block layouts, so bind them to MC's own buffers.
2. **Inter-stage locations.** We must do our own `LayoutTransformer` equivalent: assign explicit `location`s to VS→FS varyings by name. We must also assign explicit `location` to vertex inputs per §6 (11 = mc_Entity, 12 = mc_midTexCoord, 13 = at_tangent, 14 = at_midBlock), and assign descriptor `binding`/`set` to every sampler and image by name.
3. **Fragment outputs.** `location = i` ↔ `drawBuffers[i]`. In Vulkan the render pass / dynamic-rendering attachment list is `colortex(drawBuffers[i]).(read-side or write-side per F)`. Two attachment configurations are needed per gbuffers program: F after prepare and F after deferred.
4. **depthtex0 in gbuffers is a feedback loop.** Bind a copy or the previous depth, or forbid it. depthtex1 and depthtex2 are explicit copies (layout transitions plus copy commands at `beginHand` and `beginTranslucents`).
5. **Shadow pass activation** depends on *active sampler reflection* of composite-like programs. Use SPIR-V reflection, which is equivalent to GL's active-uniform test.
6. **Formats.** RGB-only formats (RGB8, RGB16F, RGB8_SNORM, …) **[CR uses several]** are often not colour-attachment capable in Vulkan. Promote them to RGBA equivalents.
7. **No hex in DRAWBUFFERS.** 32 colortex are allowed. **[CR]** uses colortex 0–8, 12, 13, 18, 19.

---

*Unverified:* the exact relative order of clouds, weather and translucent particles inside MC 26.3's main pass. The `iris_*` attribute names for core-mode vanilla formats are bound in `ShaderCreator`/`IrisProgramResourceFactory` by element name, which was not traced line by line.
