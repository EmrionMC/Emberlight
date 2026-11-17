# Emberlight — Architecture

Status: M0 (design). Research notes this design relies on are in `docs/research/`:
`mojang-render-api.md` (MC 26.3 "renderpearl"), `iris-spec.md` (Iris semantics) and
`complementary-features.md` (what the target pack uses with the user's settings).
The new on-disk format is specified in [FORMAT.md](FORMAT.md).

## 1. Goals and target

- **Acceptance target:** *Complementary Unbound r5.9.3 + Euphoria Patches 1.10.5*, with the reference Iris settings file
  `ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5.txt`. It must look like Iris on
  OpenGL with the same settings. Plain Reimagined/Unbound share the same code (they differ only in `SHADER_STYLE`), so they come for free.
- Priority on conflicts: correct look, then FPS, then everything else.
- The FPS goal is to beat Iris + Sodium on OpenGL with the same pack and settings.

## 2. Platform decision

| Item | Choice | Why |
|---|---|---|
| Minecraft | **26.3** (stable, 2026-10) | Newest *stable* release that has the Vulkan backend (`com.mojang.renderpearl.backend.vulkan`, opt-in via *Graphics API: Prefer Vulkan*). 26.4 is still at snapshot-8. The user's instance is 26.3. |
| Fabric Loader | 0.20.1 | latest from meta.fabricmc.net |
| Fabric API | 0.166.2+26.3 | latest for 26.3 on Modrinth |
| Loom | 1.19-SNAPSHOT, plugin `net.fabricmc.fabric-loom` | Fabric example mod for 26.3. MC 26.x ships unobfuscated, so there are **no mappings** and we use `implementation` instead of `modImplementation`. |
| Java | release 25 (MC requirement), built with JDK 27 | |
| LWJGL | 3.4.3 (MC's) | MC already ships `lwjgl-shaderc` and `lwjgl-spvc`, so the converter's SPIR-V toolchain exists at runtime with no extra natives. |

Porting to 26.4 later should be cheap. The renderpearl API is the same family, and every MC-facing piece sits behind the mixin layer (§6).

## 3. Big picture

```
            ┌──────────────────────────── converter/ (pure Java, no MC) ─────────────────────────────┐
 pack.zip ─▶│ PackSource → OptionDiscovery + IrisSettings → ShadersProperties → ProgramSet          │
 settings ─▶│   → per program: Preprocessor → DirectiveParser → Transpiler (→ Vulkan GLSL 450)      │
            │   → ShadercCompiler (SPIR-V, -O) → Reflection (spvc) → FramePlanner (flips/clears)     │
            │   → ManifestWriter  ==>  cache/<key>/manifest.json + *.spv + report.json              │
            └───────────────────────────────────────────────────────────────────────────────────────┘
                                             │  (FORMAT.md contract; Java records in dev.emrion.shaders.format)
            ┌──────────────────────────────── mod/ (Fabric, MC 26.3, Vulkan only) ────────────────────┐
            │ PackManager (select, convert async, cache) → PackRuntime                                │
            │   Targets (colortex/depthtex/shadow*, custom textures, images)                          │
            │   PipelineFactory (raw VkPipelines, wrapped as MC CompiledRenderPipeline for geometry)  │
            │   UniformSystem (built-ins + custom uniforms → std140 ring buffer)                      │
            │   FrameExecutor (runs the manifest's frame plan: shadow → gbuffers → deferred → …)     │
            │   GeometryHooks (mixins: pipeline swap, pass split, shadow submit, depth copies, hand)  │
            │   Profiler (GPU timestamps per pass, overlay)                                           │
            └─────────────────────────────────────────────────────────────────────────────────────────┘
```

The converter does **all** pack interpretation offline, including option resolution, preprocessing, transpiling, SPIR-V,
reflection, the uniform layout and the static flip/clear schedule. The mod only *executes* the frame plan. This keeps the
render-thread work small and makes nearly all logic unit-testable without Minecraft.

## 4. converter/

Packages (`dev.emrion.shaders.converter.*`):

| Package | Responsibility |
|---|---|
| `pack` | `PackSource`: zip or folder, case-sensitive paths rooted at `shaders/`, dimension folder lookup (`world0`, `world-1`, `world1`, plus `dimension.properties`). `ProgramSet`: which program files exist per dimension, with gbuffers fallback chains (iris-spec §1). |
| `options` | `OptionDiscovery` (scans *all* sources for option lines: `#define X v //[..]`, `//#define X`, `const T x = v; //[..]`). `IrisSettings` reads and writes the Iris `.txt` format 1:1 (iris-spec §7). `Profiles`. `OptionValues`: the resolved values. `OptionApplier` rewrites option lines in source text exactly the way Iris does. |
| `properties` | `ShadersProperties`: the file is run through the preprocessor first (it uses `#ifdef`), then parsed into typed keys (iris-spec §4). `IdMaps`: block, item and entity `.properties`. |
| `expr` | Custom-uniform expression language: parser, type checker, AST serialisation. The **evaluator also lives here** and the mod reuses it (pure Java). |
| `glsl` | `Preprocessor`: a full C-style preprocessor (includes with absolute `/lib/...` and relative paths, function-like macros, `defined`, `#if` arithmetic, `__LINE__`, `#line` map back to the original file and line). It has a mode that keeps comments, because Iris reads `const`, DRAWBUFFERS and RENDERTARGETS after preprocessing *with* comments. `DirectiveParser`. `Lexer`. `Transpiler` (§4.1). |
| `spirv` | `ShadercCompiler` (lwjgl-shaderc, target Vulkan 1.2 / SPIR-V 1.5, optimisation `performance`, which runs spirv-opt `-O` inside shaderc). `Reflection` (lwjgl-spvc). A test helper validates with `spirv-val` and `glslangValidator` when they are on PATH. |
| `plan` | `FramePlanner`: the static flip schedule, read and write targets per pass, clear passes, depth copies, mipmap generation, shadow activation (iris-spec §1.6, §2.2). |
| `format` | Manifest records plus Gson I/O. **This package is the converter↔mod contract** (FORMAT.md). |
| `report` | `Diagnostic` (severity, file, line, message) and `ConversionReport`. Errors are collected per shader and the run never aborts. |
| `cli` | `ConverterCli`: `java -jar converter.jar <pack.zip|dir> <settings.txt> <out-dir> [--dimension world0]`. |

Entry point: `Converter.convert(ConvertRequest) -> ConversionResult`, with a `ProgressListener(stage, done, total)` for the in-game progress bar.

### 4.1 Transpiler

The input is fully preprocessed GLSL with only `#version` and `#extension` left. The transpiler works on a token stream and a
**light structural parse**: global-scope declarations, function bodies as token ranges, and the brace and paren structure.
It is not a full GLSL AST. That is enough for every rewrite Iris does (iris-spec §8). Iris's own transformer library,
glsl-transformer, is **AGPL-3.0**, so we write our own.

Steps, in order:
1. **Version normalisation.** Output is `#version 450`. GLSL ≥ 330 keywords that are identifiers in old sources (`sample`,
   `texture` used as a variable, `smooth`, `patch`, `buffer`, `shared`, `filter`, `common`, `partition`, `active`, …) are renamed.
2. **Built-in function renames**: `texture2D`, `texture2DLod`, `shadow2D`, `texelFetch2D` and the rest (iris-spec §8.2).
   `shadow2D(s, c)` becomes `vec4(texture(s, c))` on a `sampler2DShadow`, which binds a compare sampler (§5.4).
3. **Stage I/O.** `attribute` and `varying` become `in` and `out`. Every inter-stage variable gets an explicit `location`,
   assigned **per program by name** across VS→GS→FS (Iris links by name; Vulkan matches by location). An FS `in` with no
   producer becomes a zero-initialised global. Type mismatches are patched the way Iris does (iris-spec §8.6).
4. **Fragment outputs.** `gl_FragColor` and `gl_FragData[i]` become `layout(location=k) out vec4`, where `k` is the position of
   `i` in DRAWBUFFERS or RENDERTARGETS. Writes to buffers that are not listed become dead locals. Modern `out` declarations
   keep their names and get locations from RENDERTARGETS order. The alpha test for gbuffers is appended to `main` and compares
   against `EmrionDraw.alphaTestRef` (push constant).
5. **Vertex inputs** (gbuffers and shadow). Legacy built-ins and `mc_*`/`at_*` attributes map to canonical input names with
   fixed locations (FORMAT.md §5). Inputs that the variant's vertex layout lacks become constants with Iris's defaults. This
   is why gbuffers programs are compiled **per vertex-layout variant**.
6. **Matrices and compat built-ins** (`gl_ModelViewMatrix`, `gl_ProjectionMatrix`, `gl_NormalMatrix`, `gl_TextureMatrix[n]`,
   `ftransform`, `gl_Fog`, …) are rewritten to expressions over the uniform blocks in §5.2. For gbuffers this follows Iris's
   `VanillaTransformer` table. For composite-type programs it follows the `CompositeTransformer` table.
7. **Uniforms.** Every non-opaque `uniform` becomes a member of the shared `EmrionUniforms` std140 block (anonymous instance,
   so the code is unchanged). Uniforms we never provide become `const` with their initialiser or zero, which matches GL's
   default. Opaque uniforms (`sampler*`, `image*`) get set/binding from the global resource table (§5.3). SSBO
   `buffer` blocks get set/binding too.
8. **Depth convention.** At the end of every vertex-type stage that writes `gl_Position`, the transpiler appends
   `gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;`. Pack matrices stay in **GL convention** (NDC z in [-1,1]), and
   our depth buffers store exactly GL's window depth with test `LEQUAL` and clear 1.0. So every depth read and reconstruction
   in the pack stays bit-compatible with Iris. MC's reverse-Z matrices are converted when we fill the uniforms (§5.2).
   Y needs no change: renderpearl renders unflipped and the swapchain blit flips, so `gl_FragCoord.xy/size == texcoord` as on GL.
9. **Cleanup.** Unused functions are dropped (shaderc and spirv-opt do the rest).

Every rewrite gets unit tests (input GLSL → expected GLSL). Every generated stage is validated by shaderc, and by `spirv-val` when it is available.

## 5. Runtime rendering model (mod/)

### 5.1 Two pipeline kinds, one resource model

Renderpearl's frontend cannot do compute, storage images, SSBOs, 3D textures, compare samplers, per-target blend or geometry
shaders, and it puts a global ALL_COMMANDS barrier after every operation (mojang-render-api §2–§4). But
`VulkanRenderPipeline` has a **public constructor that takes raw handles**, and `FrontendRenderPipeline` is a public record
that implements `CompiledRenderPipeline`. So:

- **Geometry pipelines** (gbuffers_*, shadow*) are raw `VkPipeline`s that *we* create from our SPIR-V: any stages (VS/GS/FS),
  storage-image descriptors, per-target blend, our own pipeline layout. They are wrapped as `VulkanRenderPipeline` and
  `FrontendRenderPipeline`, so MC's own draw code (terrain, entities, particles, sky, …) draws with them unchanged
  through `RenderSystem.getCompiledPipeline` (swap point, §6).
- **Fullscreen and compute passes** (setup, begin, prepare, deferred, composite, final, shadowcomp, and every `.csh`) do not go
  through renderpearl at all. The `FrameExecutor` records them into its own command buffers
  (`VulkanCommandEncoder.allocateAndBeginTransientCommandBuffer()` → `execute(cb)`) with **precise barriers**: only the
  read-after-write hazards the frame plan knows about, never ALL_COMMANDS. Consecutive composites run in one command buffer.

### 5.2 Uniform data

| Block | Where | Contents | Update |
|---|---|---|---|
| `EmrionUniforms` | set 1, binding 0, `UNIFORM_BUFFER_DYNAMIC` | Every built-in and custom uniform the pack uses, at the converter's fixed std140 layout (in the manifest). Matrices are GL-convention (`gbufferProjection` = `C·P_mc`, where `C` maps reverse-Z [0,1] to GL [-1,1]: `z' = w − 2z`). | once per frame, ring buffer (3 slots × frames in flight) |
| `EmrionPhase` | set 1, binding 1, `UNIFORM_BUFFER_DYNAMIC` | The "current" `gl_ProjectionMatrix` and its inverse per render phase (world, hand, shadow) plus `renderStage`. | a few times per frame, same ring |
| set 0 (MC) | MC push descriptors | Per-draw MC data: `DynamicTransforms` (ModelViewMat, ModelOffset, ColorModulator, TextureMat), chunk section data for terrain, `Sampler0` (albedo or atlas), `Sampler1` (overlay), `Sampler2` (lightmap), cloud buffers. | per draw, by MC |
| set 0 (ours) | push descriptors after MC's bindings | Samplers the pack reads but the vanilla pipeline does not push: white/overlay/lightmap stand-ins, and `EmrionNormals`/`EmrionSpecular`, the labPBR maps of the current `Sampler0` (`PbrTextures`: atlas → per-atlas PBR atlases, SimpleTexture → `_n`/`_s` loaded at the next frame start, as Iris's PBRTextureManager). | at our pipeline bind and whenever `Sampler0` changes |
| `EmrionDraw` | push constants (≤128 B) | `entityId`, `blockEntityId`, `currentRenderedItemId`, `entityColor`, `alphaTestRef`, `iris_NormalMat` (when not derivable) | per draw from hooks |

Set-0 bindings in our SPIR-V are **patched at load time** to the binding order of the vanilla pipeline we replace (its
**backend** `VulkanRenderPipeline.uniforms()` list, which equals the frontend's `uniformIndices()`: only the uniforms the
vanilla shaders really use, in first-use order. The frontend `uniforms()` list includes unused declarations and gives the wrong
order (found through a crash). That is exactly the set MC pushes for that draw, so **a pack stage may only use set-0
resources the vanilla pipeline also uses**. Example: terrain on the multidraw-indirect path pushes `Globals, Projection,
TerrainUniform, Fog, Sampler2, Sampler0` and has no `DynamicTransforms` and no `ChunkSection`.
Our pipeline layout uses a push-constant range with stage `VK_SHADER_STAGE_ALL`, because MC pushes with all stage bits.
A wrapped `VulkanRenderPipeline` destroys its set-0 layout, pipeline layout and modules itself, so the mod owns set-1 layouts separately. Global MC state (fog, camera, projection) is never read
from set 0. It always comes from `EmrionUniforms` or `EmrionPhase`, so a vanilla pipeline missing e.g. `Projection` cannot break us.

### 5.3 Resources (set 1)

All pack resources sit in **one descriptor set layout shared by every pack pipeline**: colortex0-15 (and above, if used),
depthtex0-2, shadowtex0/1, shadowcolor0/1, noisetex, custom textures, storage images (`colorimg`/`shadowcolorimg`/custom
images), SSBOs, and the two dynamic UBOs. Binding numbers are fixed by the converter (manifest `resources`). Because Iris's
flip schedule is **static**, the mod pre-builds one descriptor set **per distinct flip state** at pack load. Binding one is
a single `vkCmdBindDescriptorSets` per pass, so there are no per-draw descriptor updates. For geometry pipelines we bind set 1
in a mixin at `VulkanRenderPass.setPipeline` TAIL when the pipeline is ours.

### 5.4 Render targets

- colortexN: MC `GpuTexture`s (so geometry passes can attach them) with format per pack directive. RGB-only formats are
  promoted to RGBA. The usage includes STORAGE through a custom usage bit plus a mixin on `VulkanConst.textureUsageToVk`.
  An alt copy exists only if the plan flips the buffer.
- Depth: our own D32_FLOAT, forward-Z (§4.1 step 8). `depthtex0` is the live buffer. `depthtex1` and `depthtex2` are copies
  made at Iris's points (iris-spec §2.3). Gbuffers that sample depthtex0 get the previous copy, to avoid a feedback loop.
- Shadow: `shadowtex0/1` D32_FLOAT at `shadowMapResolution`, `shadowcolor0/1`. Compare samplers are raw `VkSampler`s.
- 3D and array textures and images are raw VMA images (never seen by MC).
- All MC images live in `GENERAL` layout. Our raw images do too, which keeps interop with MC's model and avoids transitions.

### 5.5 Frame flow (per frame)

```
beginLevelRendering: update uniforms → clears → setup (once) / begin* computes
shadow pass:  MC terrain via prepareChunkRenders(shadowView) + entities → shadowtex/shadowcolor   [geometry pipelines]
              shadowcomp*                                                                       [raw]
prepare*                                                                                        [raw]
main opaque:  sky, terrain solid/cutout, entities, block entities, opaque particles → colortex MRT [geometry]
              (solid hand) → copy depthtex1, (hand) copy depthtex2 at Iris's points
deferred*                                                                                       [raw]
main translucent: water/translucent terrain, translucent entities and particles, clouds, weather [geometry]
hand translucent
composite* → final → mainRenderTarget (RGBA8)                                                   [raw]
end-of-frame alt→main copies for flipped, non-cleared buffers (as planned)
```

The exact order of clouds, weather and particles is fixed in M3 against MC 26.3's `addMainPass`.

### 5.6 Vertex data

Vanilla terrain format `BLOCK` (Position, Color, UV0, UV2; 28 B) lacks everything Complementary needs (normal, `mc_Entity`,
`mc_midTexCoord`, `at_tangent`, `at_midBlock`). While a pack is active, the terrain pipelines use an **extended
format** `EMRION_BLOCK` (FORMAT.md §5). Section meshing emits it through a mixin at the `BufferBuilder` creation in
`SectionCompiler` plus a quad-completion hook that computes normal, tangent and midTexCoord per quad (Iris does the same).
Block IDs come from `block.properties`, resolved to a `BlockState → id` table at pack load. Activating or deactivating a pack
triggers a chunk rebuild.

### 5.7 Pipeline compilation (no stutter)

At pack load, after conversion, in this order: create shader modules → create all `VkPipeline`s **in parallel on
background threads** with a shared `VkPipelineCache` that is persisted to `emberlight/pipeline-<driver cache UUID>.bin`. Then
swap atomically on the render thread. Nothing is compiled lazily. A pipeline missing at draw time is a bug and is logged once;
the draw falls back to the pack's `gbuffers_basic` variant.

Every gbuffers variant a known vanilla or Sodium world pipeline can need (303 for Complementary Unbound + EP) is built in
that step, so a new block, entity or render type never compiles in a frame; only pipelines other mods create later go
through `GbufferPipelines.buildLate` (logged as "built in a frame"). The loading panel shows "Preparing shaders… x/y".
In dev runs the file is shared in `~/.cache/emberlight-dev`. MC's own pipelines also go
through it (`VulkanPipelineCacheMixin`; MC passes no cache). After a dimension's pack pipelines are active, the
other dimensions of the pack are built once in the background (`PackManager.PRECOMPILER`, 2 low-priority threads,
stopped by a new selection or shutdown) and dropped, so a cold cache has them before the first portal.
If a portal or world join comes before that (`PackManager.switching`: the dimension has a pack folder but no runtime),
MC's level loading screen stays open until the pack renders, at most 30 s, with the loading panel on it
(`LevelLoadingScreenMixin`), so no vanilla frames show. Only the screen waits: the player-loaded signal to the server
keeps vanilla timing. A failure or a pack without that dimension releases it at once.
Measured 2026-11-16 (BENCHMARK.md, "Shader compile stutter"): cold 11-22 s per dimension, warm 0.2-0.6 s.

### 5.8 Performance principles

- No `vkQueueWaitIdle` or `vkDeviceWaitIdle` in the frame. Query readback lags ≥2 frames (`GpuQueryPool`).
- One descriptor bind per pass, dynamic-offset UBOs from a ring buffer, push constants for per-draw data.
- Raw passes use precise barriers and get merged into as few command buffers as possible.
- Shaders are spirv-opt `-O`'d offline. Dead programs (`program.X.enabled=false`, unused outputs) are skipped by the planner.
- The shadow pass culls against the light frustum and respects `shadowDistance` / `maxShadowRenderDistance`.
- MC's multithreaded chunk building stays untouched apart from the extra attribute work per quad.

## 6. Mixins (narrow, one reason each)

Planned set; each mixin class carries a comment explaining why it is needed.

| Target | Kind | Why |
|---|---|---|
| `RenderSystem.getCompiledPipelineNullable` | HEAD, cancellable | The single choke point every draw uses to pick its pipeline. While a pack phase is active, return our geometry pipeline. |
| `LevelRenderer.render` HEAD / after `FrameGraphBuilder.execute` | inject | Iris's beginLevelRendering (uniforms, plan up to the first world phase) and the end of the plan (composites, final). |
| `LevelRenderer.lambda$addMainPass$0` | wrap `createRenderPass`, `executeClassicTransparency` | MC's "Main" pass becomes our opaque gbuffers pass; at the translucent call the opaque pass is closed, the solid hand drawn, the plan run to the translucent phase (depthtex1 copy, deferred), translucents drawn in a second pass, then the translucent hand (`GeometryHooks.splitMainPass`). |
| `LevelRenderer.executeSolid/executeClassicTransparency` | wrap draw groups | Iris renderStage per group (terrain 8/17, entities 11, particles 19, weather 21, border 22); vanilla clouds skipped when `clouds=off`. |
| `SkyRenderer` | wrap `createRenderPass`, HEAD of each sky part | Sky phase into our pass; renderStage SKY/SUNSET/SUN/MOON/STARS/VOID; `sun`/`moon`/`stars=false`; sunPathRotation tilt. |
| `GameRenderer.renderItemInHand` (invoked from the level) | invoker + wraps | The hand is drawn inside the level (Iris order) with the HAND projection and its own FeatureRenderDispatcher; MC's own hand call in `render3dHud` is skipped while a pack renders. |
| `LevelExtractor.applyFrustum` TAIL | inject | While a pack renders, drop visible sections outside Sodium's cylindrical render distance (`HorizonCull`), so the pack's border fog meets the same terrain edge as under Iris. |
| `GameRenderer.useImprovedTransparency` | HEAD | Force the classic transparency path while a pack is active (OIT changes the pass structure). |
| `VulkanRenderPass.setPipeline` TAIL | inject | Bind descriptor set 1 and push the extra set-0 samplers for our pipelines. |
| `FrontendRenderPass.setUniform` HEAD | inject | `Sampler0` changed: atlasSize and the per-draw PBR maps follow it. |
| `FrontendRenderPass.setPipeline` HEAD | inject, field swap | Only when the gbuffers programs together write more colortex than `maxColorAttachments` (Bliss: 9 > 8): `GbufferPipelines` splits the programs into attachment groups (first fit, most outputs first) and builds each pipeline against its group; setting a pipeline of another group ends the dynamic rendering, puts a colour/depth memory barrier, resumes with that group's attachments (LOAD, same depth) and swaps the pass's attachment list. With one group nothing happens (a static boolean check). |
| `TextureAtlas.upload` RETURN / `cycleAnimationFrames` TAIL / `createTexture` TAIL | inject | PBR atlases mirror each atlas's layout and animation; a reload drops the per-texture PBR cache. |
| `PipelineCache.get` (`compilePipeline` executor) | modify arg | With a pack selected, MC's blocking pipeline compiles run on our own thread instead of the chunk worker pool: after a resource reload every chunk worker can wait on the render thread, so a compile queued behind them never finishes. `PackManager.prepare` also never blocks: it starts the missing vanilla compiles and builds the pack pipelines a few frames later (the old pack keeps rendering). |
| `VulkanRenderPipeline.compile` (`vkCreateGraphicsPipelines`) | modify arg | MC's pipelines use our persisted VkPipelineCache instead of none. |
| `LevelLoadingScreen.tick` (`isLevelReady`), `extractRenderState` TAIL | wrap, inject | The screen stays open while the pack builds the new dimension's pipelines (no vanilla frames), with the loading panel on it (§5.7). |
| `VulkanConst.textureUsageToVk` | return | Add the STORAGE usage bit for our custom usage flag. |
| `VulkanFeatureSets.optionalFeatureSets` | return | Enable `fragmentStoresAndAtomics`, `vertexPipelineStoresAndAtomics`, `geometryShader`, `shaderStorageImageWriteWithoutFormat` (only those the pack needs). |
| `SectionCompiler` / `BufferBuilder` | wrap | Extended terrain vertex format plus per-quad attributes. |
| `FrontendGpuDevice.backend` | accessor | Get the `VulkanDevice` for raw calls. |
| `VulkanCommandEncoder.submit` (`awaitSubmitCompletion`), `VulkanGpuSurface` acquire/present | wrap | Profiler only: render-thread time blocked on the GPU (GPU- vs CPU-bound), shown by the F7 overlay (BENCHMARK.md). |
| `LevelRenderer.render` before `addMainPass` | inject | Iris's renderShadows point: `ShadowPass` swaps `visibleSections` for the shadow-culled sections (Iris SAFE_ZONE/advanced culling, `ShadowCuller`), lets MC prepare the chunk draws with the shadow model-view (MDI or not, `LevelRendererAccessor`), re-extracts and submits entities into its own SubmitNodeStorage/FeatureRenderDispatcher, then draws shadow_opaque/entities/translucent with the SHADOW pipelines (no backface culling) into shadowcolor0/1 + shadowtex0. |
| `EntityRenderDispatcher.submit` → `submitShadow` | wrap with condition | Vanilla blob shadows off while the plan draws a shadow map (Iris). |
| `SubmitNodeCollection.submitModel/submitCustomGeometry`, `PreparedRenderType.draw`, `RenderType` accessor | modify arg / wrap | Block entities get render-type copies (separate batches); drawing one switches to gbuffers_block/block_translucent and renderStage BLOCK_ENTITIES (Iris). |

## 7. Threading and lifecycle

- Conversion runs on a background executor with progress shown in the pack screen. Cache hits skip it.
- Pack activation: convert (or load the cache) → create GPU resources and pipelines in the background → swap on the render
  thread at a frame boundary → rebuild chunks. Deactivation or failure: tear down, back to vanilla (M6 fallback).
- Hot reload (R): the same path with a new cache key.

## 8. Testing strategy

- converter: JUnit with many small GLSL in→out golden tests, plus an integration test that converts the real target pack from
  `testpacks/` (skipped when absent) and asserts **every stage compiles** (M1 metric) and the report has 0 errors.
- mod: the frame planner and uniform layout are tested in converter. In-game checks use a visual checklist in PROGRESS.md
  with Iris comparison screenshots, plus the benchmark (M5).

## 9. Licensing

The project is LGPL-3.0. Iris (LGPL-3.0) is read as a specification only. No code is copied; if that ever changes, the
file gets an attribution header. glsl-transformer (AGPL-3.0) is not used. Shaderpacks are never committed (`testpacks/` is git-ignored).
