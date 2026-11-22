# Emberlight on Sodium

**Decision (2026-11-21): Sodium is required.** `fabric.mod.json` depends on Sodium `>=0.9.3-beta.5` (and breaks newer
betas), terrain always goes through Sodium's chunk renderer (as under Iris), and the vanilla chunk path was deleted (removed: the SectionCompiler/Indigo/fluid meshing hooks, EMRION_BLOCK, the vanilla RT and shadow section
paths, ForcedSections, HorizonCull, the converter's terrain_mdi/terrain_ubo variants and its `sodium` request flag).
`runClient` always loads the Sodium jar from `-PirisModsDir`; `-Psodium` is gone.
The earlier decision (2026-11-13: both chunk paths supported) is history; the sections below that compare or mention the
vanilla path describe the state before the deletion.
Sodium is Polyform-Shield-1.0.0: we may depend on it and mixin into it, but we must not bundle or copy its code. Decompiled
classes were read only as a spec (paths below are Sodium class names, not copies). The build compiles against Sodium from
Modrinth's maven (`compileOnly`, `sodium_version` in gradle.properties). The jar is never bundled. Everything that needs Sodium's types lives
in `compat/sodium` and `mixin/sodium` (`emrionshaders.sodium.mixins.json`, skipped only next to Iris in the dev reference run).

§1–§5 are the spike (2026-11-13). §6 is step 1, §7 steps 2–4, §8 the comparison of the two paths, §9 the vanilla-path question, §10 Sodium versions.

Versions: Sodium `0.9.3-beta.1+mc26.3`, Iris `1.11.7+mc26.3` (a launcher instance), MC 26.3 dev env, RTX 4070 Ti.

## 1. Does Sodium run on MC's Vulkan backend? Yes

`./gradlew :mod:runClient -Psodium` (new, `mod/build.gradle`) loads a local Sodium jar through `-Dfabric.addMods` from the
`mods/` folder (the same folder `runIrisReference` uses, `-PirisModsDir`). It also copies that instance's `sodium-options.json`. The jar is never copied
into the repo. The autotest now logs `all sections built N ms after the teleport/remesh, Sodium draw backend X`.
`-Demrion.autotest.remesh=true` drops all meshes once (`LevelExtractor.allChanged`, like F3+A) and times the rebuild.

- Sodium picks `DrawBackend.VK_MULTIDRAW` (`VK_EXT_multi_draw`; `VK_INDIRECT` is the fallback). No errors, no mixin
  conflicts with our mod.
- The overview renders correctly without our pack (screenshot in a scratch folder, `sod-bench/smoke-sodium-keep/emrion-overview.png`).
- Sodium on 26.3 has no GL code left in the chunk path. It records into renderpearl `RenderPass`es with renderpearl
  `RenderPipeline`s (`ShaderChunkRenderer.createShader`: location `sodium:pipeline/{solid,cutout,translucent}_terrain`).

### Performance without a pack (benchmark a)

Overview, 1920×1080, RD 16, 30 s, runs interleaved V S V S V S, GPU_BUSY held. Our mod is loaded in both runs but inactive (`-Demrion.pack=`).
These runs are CPU-bound (thousands of FPS), and the harness stops recording at 65 536 frames, so Sodium's runs cover only 19–24 s.

| | run 1 | run 2 | run 3 | median frame time (ms) | full remesh (ms)\* |
|---|---|---|---|---|---|
| vanilla Vulkan | 2397 FPS | 1552 FPS | 2346 FPS | 0.379 / 0.413 / 0.365 | 189 / 251 / 298 |
| Sodium Vulkan | 3526 FPS | 2742 FPS | 2739 FPS | 0.269 / 0.269 / 0.293 | 147 / 147 / 104 |

Sodium takes about 30 % less CPU per frame and rebuilds faster. \*The remesh time is the first tick on which
`hasRenderedAllSections()` is true again. Sodium overwrites that method with "build queue empty", which is not the same test,
so treat the remesh column as rough. Logs (scratch folder): `sod-bench/{v1,v2,v3,s1,s2,s3}/latest.log`.

## 2. Our mod + Sodium + the target pack: what breaks

Run `ps1` (pack + `-Psodium`): **the game crashes on the first world frame.**

```
WARN  Pipeline sodium:pipeline/solid_terrain draws in a gbuffers phase but has no pack pipeline; it renders vanilla
IllegalStateException: Render pass color attachment count must match pipeline color target state count.
  at FrontendRenderPass.setPipeline <- DefaultChunkRenderer.render <- SodiumWorldRenderer.drawChunkLayer
  <- SodiumChunkSection.renderGroup <- LevelRenderer.emrion$terrainStage <- LevelRenderer.executeSolid
```

How Sodium plugs in: `LevelRendererMixin` (Sodium) wraps `LevelRenderer.prepareChunkRenders` in `render` and returns a
`SodiumChunkSection extends ChunkSectionsToRender`. Its `renderGroup(group, pass, sampler, …)` draws Sodium's regions into
**the RenderPass MC hands it**. `invalidateCompiledGeometry` is cancelled and installs an `IgnoringSectionRenderDispatcher`
and an `IgnoringViewArea`, so vanilla meshes nothing. As a result:

| Our feature / mixin | With Sodium |
|---|---|
| Opaque pass replacement (`LevelRendererMixin.emrion$opaquePass`), stage wraps (`emrion$terrainStage`), split main pass, NEAREST sampler | **Still works.** Sodium draws inside our pass, and our sampler is passed through `renderGroup`. |
| Pipeline swap (`RenderSystemMixin` → `GeometryHooks.swap`) | **Misses** `sodium:pipeline/*`: Sodium's single-RT pipeline in our multi-RT gbuffers pass → the crash above. |
| `SectionCompilerMixin`, `ExtendedBufferBuilder` (EMRION_BLOCK), `FluidRendererMixin`, `IndigoBlockRendererMixin` (AO), `LevelRendererVertexMixin`, `SectionRenderDispatcherMixin` | Apply, but **silently no-op**: Sodium meshes with its own `ChunkBuilderMeshingTask`/`BlockRenderer`/`FluidRenderer` into its own 20 B format. "Vertex formats extended, chunks rebuild" is logged but changes nothing. |
| Shadow pass (`ShadowPass.collectSections/prepareTerrain`: `viewArea()` + vanilla `prepareChunkRenders`) | `IgnoringViewArea` holds no meshes → **no terrain and no block-entity shadows** (not reached because of the crash; follows from the code). |
| Forced section compilation (m7/rt2) | Targets vanilla's dispatcher → no-op. |
| RT BLAS (`UberGpuBufferRtMixin`, `RenderSectionRtMixin`, `SectionRenderDispatcherRtMixin`) | Vanilla heaps stay empty → **empty BLAS**. |
| Entities, particles, text (our `ExtendedBufferBuilder` for EMRION_ENTITY) | Not verified because of the crash. Risk: Sodium's `VertexBufferWriter` fast paths (`CubeMixin`, `BufferBuilderMixin` intrinsics) write Sodium-serialised vertices into any `BufferBuilder`, and with no serializer registered for our formats they may fail or fall back. Iris registers its own (`ModelToEntityVertexSerializer` and friends). |

No mixin of ours failed to apply. All targets still exist.

## 3. How Iris integrates with Sodium (1.11.7 jar, decompiled; LGPL), and our equivalent

Iris on 26.3 does **not** replace Sodium's renderer. It uses the same pipeline-swap idea we use for vanilla:

| Area | Iris (class) | Sodium side | Ours (to build) |
|---|---|---|---|
| Program swap | `IrisPipelines`: any pipeline whose namespace contains `sodium` maps to `SODIUM_TERRAIN_{SOLID,CUTOUT,TRANSLUCENT}` (or `SHADOW_SODIUM_*` during the shadow pass). `ExtendedShader` uses `ShaderChunkRenderer.BIND_GROUP` uniforms. `MixinShaderChunkRenderer` keeps the pipeline's vertex format at `COMPACT` so the lookup works. `SodiumTransformer` patches the GLSL. | `ShaderChunkRenderer.createShader`: bind groups `u_BlockTex`, `u_Globals` (UBO: projection, modelView, fog, atlas inverse size, …), `u_SectionTimeInfo` (texel buffer), plus `LIGHT_GROUP` `u_LightTex` (**a second bind group**). Push constants 20 B per region (camera-relative region offset xyz, time, region id). | Map `sodium:pipeline/*` in `GbufferMapping` to `gbuffers_terrain_{solid,cutout}`/`gbuffers_water`. Add a converter variant `terrain_sodium` that decodes the compact attributes, gets `gl_Vertex` from the region push constant and the ModelView from `u_Globals`, and moves `EmrionDraw` behind Sodium's 20 B push range. `Set0Patcher` learns Sodium's names and its 2 bind groups (our set 1 = EmrionUniforms must not collide). |
| Vertex format + ids | `MixinChunkMeshFormats` `@Overwrite getCurrent()` → `XHFPModelVertexType`: compact 20 B + optional `mc_Entity` (4), `iris_Normal` (4, normal + tangent), `mc_midTexCoord` (4), `at_midBlock` (4, RGBA8_SNORM + emission). `FormatAnalyzer` includes only what the pack uses. separateAo: color alpha = AO instead of baked in. `MixinChunkVertex` adds fields to `ChunkVertexEncoder.Vertex`. `MixinChunkMeshBuildTask` sets block/fluid id per block (`beginBlock`), `MixinAbstractBlockRenderContext` handles model-part overrides. | `ChunkMeshFormats.getCurrent()`, `ChunkVertexType`/`ChunkVertexEncoder`, `ChunkBuilderMeshingTask.execute`, `BlockRenderer`, `DefaultFluidRenderer`. | Same approach with our `BlockIdTable`/`QuadMath` logic: an Emrion `ChunkVertexType`. The vanilla EMRION_BLOCK mixins can then go. AO and quad splitting come from Sodium as in the reference, so `IndigoBlockRendererMixin` becomes unnecessary. |
| Shadow pass | `MixinRenderSectionManagerShadow`: separate `SortedRenderLists`/`SectionTree`/task lists swapped in during the shadow pass; `finalizeRenderLists` → `renderOutOfGraph` (`FallbackVisibleChunkCollector`) with the shadow viewport; async cull, chunk updates and translucent sorting skipped while in shadow. `MixinRenderRegion` (per-region shadow render list), `MixinUniformData` (second UBO), `MixinDefaultChunkRenderer` (separate `shouldDraw`, **block-face culling off in shadow**). `MixinRenderSectionManager`: fog occlusion off while a pack is active. | `RenderSectionManager`, `RenderRegion`, `UniformBufferManager`, `DefaultChunkRenderer`. | Port this design, with our `ShadowCuller` as the shadow `Viewport`. Replace `prepareTerrain`/`collectSections`. Shadow block entities come from Sodium's per-section block-entity lists. |
| Complete meshing | none | Sodium meshes the sections that the **WIDE** occlusion BFS reaches (`TaskCollectingTree.visit`, not frustum-tested, within render distance). Fully occluded sections (enclosed caves) stay unmeshed until they become reachable. `FallbackVisibleChunkCollector` only lists meshed sections. | Iris therefore casts shadows only from meshed sections. Matching the reference needs **no** forced meshing. Our forced compilation becomes an optional extra: schedule tasks for shadow-visible pending sections. |
| RT BLAS source | none | `RenderRegion.DeviceResources.getGeometryBuffer()`: per region (8×4×8 sections) slices of shared arena buffers (`ArenaAggregator`, `createBuffer("Arena buffer", usage 120, …)`). Per-section ranges in `SectionRenderDataStorage`/`SectionRenderDataUnsafe` (base vertex, per-facing counts). Quads use a shared index buffer. Positions are 20-bit fixed point packed in two uints (`CompactChunkVertex`). **Arenas defragment**, so data moves (`PendingBufferCopyCommand`). | Mixin on the arena `createBuffer` for AS-input and device-address usage. BLAS per region or section from the storage ranges. Rebuild on defrag copies. Either decode positions in a compute pre-pass, or give our extended format a BLAS-friendly position (for example SNORM16×4). |
| Sampler | `MixinDefaultChunkRenderer.iris$forceNearest`: NEAREST + mips while a pack is in use | `ShaderChunkRenderer.begin(…, GpuSampler, …)` | Already done: we pass NEAREST through `renderGroup`. |

Mipmaps: neither Sodium 0.9.3 nor Iris 1.11.7 changes mipmap generation (Sodium has no mipmap code; Iris's
`mixins.iris.bettermipmaps.json` is empty). Sodium marks every terrain material as mipped. So the darker distant short grass
under Iris is **probably not** a mipmap difference. Candidates are Sodium's AO/shading of cross models and quad splitting, which
we get automatically once Sodium meshes.

## 4. Sodium's public API on 26.3 and the internals we need

`net.caffeinemc.mods.sodium.api.*` contains:
- `vertex.*`: `VertexBufferWriter`, `VertexSerializerRegistry` (register serializers into our EMRION_ENTITY/glyph formats,
  like Iris), `VertexFormatRegistry`, attribute helpers;
- `config.*`: `ConfigEntryPoint` (`sodium:config_api_user` entrypoint) for adding our settings page to Sodium's video
  settings, as Iris does;
- `texture.SpriteUtil` (mark sprites active; matters because `animate_only_visible_textures` is on);
- `blockentity.*` (render predicates), `math`, `memory`, `util`.

There is **no chunk-rendering API**. Like Iris, we would need mixins into internals: `ChunkMeshFormats`,
`ChunkVertexEncoder.Vertex`, `ChunkBuilderMeshingTask`, `BlockRenderer`/`AbstractBlockRenderContext`, `DefaultFluidRenderer`,
`ShaderChunkRenderer`, `DefaultChunkRenderer`, `RenderSectionManager`, `RenderRegion`, `RenderRegionManager`,
`UniformBufferManager`, `SodiumWorldRenderer`, `ArenaAggregator` (RT). That is about 15 mixins, the same shape as Iris's
`mixins.iris.compat.sodium.json` (21 entries). Each Sodium beta can move these, so pin the version range in `fabric.mod.json`.

## 5. Effort and risks

### Full-pack performance (benchmark b): estimated, not measured

Step 1 cannot be hacked together inside the spike. It needs a new converter vertex variant, a second bind group in our
set layout and push-constant relocation (see §3, about 2–3 days). So (b) is an estimate:

- Today: 136.5 FPS (run p1 on this build; master reference 137), 7.3 ms/frame, **GPU-bound** with about 5 ms CPU slack.
  Opaque pass 2.44 ms GPU, shadow 0.47 ms GPU + 1.46 ms CPU.
- Sodium changes only the vertex side of terrain: block-face culling drops whole back-facing facing slices per section in the main
  view (not in shadow, where Iris disables it), vertices are 36 B (or less) instead of 56 B, and there are fewer draw calls.
  Fragment work and overdraw stay the same, because back faces were already culled by the rasterizer.
- Expected: opaque −0.2…−0.6 ms, shadow GPU −0.05 ms (vertex size only), shadow CPU replaced by Sodium's async lists (no FPS
  effect while GPU-bound, may help 1 % lows). Net **≈ 137 → 140–150 FPS**. A loss is unlikely but not excluded: fog occlusion is
  off with a pack, as in Iris, and Sodium issues more, smaller draws (per region and facing) than our MDI path.
- CPU and meshing (measured without a pack): about −30 % CPU frame time, faster full remesh.

The step-1 gate below makes this measurable before anything is deleted.

### Plan (days)

| Step | Content | Verifiable by | Effort |
|---|---|---|---|
| **1** | Sodium as a dev runtime mod (done: `-Psodium`). `terrain_sodium` converter variant on the **stock compact format** (mc_Entity, midTexCoord, tangent, midBlock get the existing defaults). Map `sodium:pipeline/*` to gbuffers_terrain/water. Teach `Set0Patcher` Sodium's two bind groups and offset the push constants. | Pack loads with Sodium. Composites work and terrain is drawn by Sodium through our gbuffers pipelines. Overview MAE vs Iris close to today's, block-ID effects excepted. **FPS ≥ 137 in 3 interleaved runs** (go/no-go). | 2–3 |
| 2 | Emrion `ChunkVertexType` (ids, midTexCoord, tangent/normal, midBlock + emission, separateAo) via `ChunkMeshFormats`/`Vertex`/meshing-task mixins. | All 6 scenes, MAE vs Iris ≤ today's. | 2–3 |
| 3 | Shadow pass on Sodium render lists (Iris design + our `ShadowCuller`), shadow block entities from Sodium. | forest/sunset shadows, shadow-pass timings. | 2–3 |
| 4 | Entities, particles and text: `VertexSerializerRegistry` serializers into our formats; check the entities and hand scenes. | entities scene MAE. | 1 |
| 5 | RT BLAS from Sodium arenas (usage flags, ranges, defrag, position decode). | RT shadows scene, VRAM report. | 3–4 |
| 6 | Optional: mesh shadow-only sections (the forced-meshing equivalent). | cave/sunset coverage. | 1–2 |
| ~~7~~ | ~~Sodium becomes `depends`, delete the vanilla chunk path.~~ Dropped: both paths stay (decision above). | | |

Total **≈ 12–18 days**. Steps 1–3 (about a week) give a full visual switch. RT (step 5) can follow later.

### Risks

- **Sodium beta on Vulkan.** VK_MULTIDRAW worked in every run here. Each Sodium update can move the internals we mixin into
  (Iris has the same exposure). Pin `sodium` to `0.9.x`.
- **Bind-group and push-constant layout clash** between Sodium's pipelines and our set-1 / EmrionDraw scheme. This is the main
  technical unknown of step 1.
- **Entity fast paths** (Sodium `VertexBufferWriter`) writing into our extended buffers: unverified.
- **RT**: arena defragmentation moves geometry, and the compact positions are not an AS vertex format.
- **Translucent sorting / OIT**: Sodium sorts translucent quads itself (GFNI, per-section index buffers) and has an OIT path
  (`renderOit`). This matches Iris, but water passes need re-checking.
- **animate_only_visible_textures**: sprites seen only by the shadow pass or by pack effects may not animate (Iris has the same setting).
- Performance is estimated only (above). Step 1's gate decides.

## 6. Step 1 status

Done:

- **Converter:** new vertex variant `terrain_sodium` (`VertexLayouts`, `GlslTranspiler`). It decodes Sodium's compact format the same
  way Iris's `SodiumTransformer` does: 20-bit positions plus the region offset and section translation, UV with Sodium's shrink
  bias, light from `a_LightAndData`, and `mc_chunkFade` from `u_SectionTimeInfo`. Normal, mc_Entity, midTexCoord, tangent and
  midBlock are missing from the stock format and get the existing constant defaults. The model-view comes from the pack's
  `gbufferModelView`/`shadowModelView` member, because Sodium writes `u_Globals` only once per frame. The variant is emitted
  only when `Converter.Request.sodium` is true (CLI `--sodium`; the mod sets it when Sodium is loaded). The cache key gets
  `sodium=<SODIUM_VARIANT>` only in that case. Requests without Sodium produce byte-identical output: the RT-off golden hash
  test passes and `VERSION` is unchanged.
- **Bind groups: not a problem.** MC's Vulkan backend merges Sodium's two bind groups into one set-0 push-descriptor set,
  ordered by name, so our set 1 is untouched. `Set0Patcher` gets the names `u_Globals` and `u_SectionTimeInfo`, and
  `u_BlockTex`/`u_LightTex` serve as `Sampler0`/`Sampler2`.
- **Push constants:** Sodium pushes 20 B at offset 0. `VulkanRenderPassMixin.pushConstants` moves them to offset 48 (behind
  EmrionDraw) for our Sodium pipelines, so the fragment shaders shared with vanilla stay unchanged.
- **Pipelines at pack load:** `SodiumTerrain.pipelines()` creates Sodium's 3 terrain pipelines (solid/cutout/translucent)
  through a throwaway `DefaultChunkRenderer.compileProgram`. Sodium caches them in a static map, so its renderers later draw
  with the same objects, and ours are built with the other 300 at pack load. If that fails, `GbufferPipelines.get` builds
  them on first use and logs a WARN ("built in a frame"). The smoke run logged no such warning. The OIT pipeline set is not
  covered: MC's OIT path is not used while a pack renders.
- **Mapping:** `GbufferMapping.get` maps `sodium:pipeline/<x>` to the vanilla entry with the same path.
- **Entities:** with Sodium loaded they stay in the vanilla formats (`EmrionVertexFormats.entitiesActive()` is false). Sodium's
  `EntityRenderer.renderCuboid` fast path pushes ENTITY quads into our EMRION_ENTITY buffer, and no serializer exists for that
  pair, so the game crashed ("Source format is missing element mc_midTexCoord"). Step 4 fixes this with serializers.
- **Shadow pass (interim):** the terrain comes from a `SodiumChunkSection` built with the shadow matrices over Sodium's
  **main-view** render lists. Terrain outside the camera frustum therefore casts no shadow, and block-face culling follows the
  camera. Step 3 replaces this.

Result: the overview with the target pack renders, and terrain is drawn by Sodium through our gbuffers pipelines. MAE against
Iris is 5.97 / SSIM 0.859 (today: 0.50 / 0.995). Visible causes are the default normal (every face lit as "up"), no
mc_Entity ids (no waving foliage or water id), and the incomplete shadow map. All of these are steps 2–3.
Gate (BENCHMARK.md M8-S1): Sodium measures 140–143 FPS against vanilla at 98 FPS, but the comparison is not like-for-like.
The gap is the step-1 shadow pass, which covers less terrain. The comparable main-view passes are +0.18 ms *slower* on
Sodium (opaque +0.36, translucent −0.18). The vanilla path itself dropped from 137 to 98 FPS on master (shadow_opaque 3.35 ms).
That is a separate regression. Decide go/no-go after step 3.

## 7. Steps 2–4 status

**Step 2, vertex format and ids.** While a pack is active, `ChunkMeshFormatsMixin` makes Sodium mesh in
`EmrionChunkVertex` (`EmrionVertexFormats.SODIUM_TERRAIN`, 40 B). It is Sodium's compact 20 B vertex plus the canonical extras,
with the same element names and shader types as our vanilla `EMRION_BLOCK`:

| offset | element | format | content |
|---|---|---|---|
| 0 | a_Position | RG32_UINT | 3×20-bit position in the section (Sodium's encoding) |
| 8 | a_Color | RGBA8_UNORM | tint; with `separateAo` alpha = AO, else AO times rgb (Iris's XHFPTerrainVertex) |
| 12 | a_TexCoord | RG16_UINT | UV with Sodium's centroid bias bit |
| 16 | a_LightAndData | RGBA8_UINT | block/sky light **0–240 without Sodium's +8 bias** (Iris), material bits, section index |
| 20 | mc_Entity | RG16_SINT | block.properties id (−1 unmapped), render type 0 block / 1 fluid |
| 24 | Normal | RGBA8_SNORM | face normal (QuadMath, as vanilla) |
| 28 | at_tangent | RGBA8_SNORM | tangent + handedness (QuadMath.quadTangent) |
| 32 | mc_midTexCoord | RG16_UNORM | quad's mid UV (finer than Iris's 1/32768) |
| 36 | at_midBlock | RGBA8_SINT | Iris's signed bytes (block centre − vertex)·64, w = light emission |

Iris packs normal and tangent into 4 B (oct24 + diamond byte, 36 B in all). We keep 8-bit SNORM normal and tangent, which match the vanilla path's
values exactly. The converter's `terrain_sodium` variant reads the extras through the canonical inputs. Only at_midBlock
(ivec4) is decoded specially. `SODIUM_VARIANT` 3. The block comes from `ChunkBuilderMeshingTaskMixin`, which wraps
`BlockRenderer.renderModel` and `FluidRenderer.render` and fills our per-thread `BlockContext` (the same one the vanilla path
uses). `BlockRendererMixin`/`DefaultFluidRendererMixin` copy it into the four `ChunkVertexEncoder.Vertex` of each quad, and
`ChunkVertexMixin` carries it through `copyVertexTo`, so translucent quads kept for sorting keep their ids. AO, quad
splitting and fluid lighting are Sodium's own, as under Iris. With `oldLighting=false`, the fluid side faces get brightness 1, as
in Iris's setBrightness. Sodium's own pipelines stay in the compact format (`ShaderChunkRendererMixin`), so they still fit after
the pack is turned off. Ours are built with `SODIUM_TERRAIN` (`GbufferPipelines.extended`). Fog occlusion is off while a
pack renders (`RenderSectionManagerMixin`, as Iris).

**Step 3, shadow pass.** `compat.sodium.SodiumShadow` builds the shadow pass's own draw lists over Sodium's region
buffers instead of swapping Sodium's lists as Iris does. Sodium's main-view render lists and per-region cached batches are
never touched. Section set: every *built* section (Sodium builds exactly what its occlusion flood fill reaches, frustum or
not, which is Iris's shadow set), tested with the same `ShadowCuller` and receiver grid (the *Shadow culling* option) as the
vanilla path. Draws use all facings (no block-face culling, as Iris in shadow), one multi-draw per region and pass with a
shared quad index buffer, or the region's index buffer for sorted translucent sections. Shadow block entities come from Sodium's
per-section culled lists plus its global ones. The voxelization (coloured light, WSR) runs in the shadow vertex
shaders and works unchanged, now with real mc_Entity ids (cave scene MAE 0.22).
Difference from the vanilla path: Sodium's built set is larger than MC's occlusion graph (overview: 1560 against 951
shadow sections after culling), because MC's graph also ray-tests beyond 60 blocks ("smart cull") and Sodium keeps sections
built once they were reached. That is the set Iris uses.

**Step 4, entities, particles, text.** The step-1 shortcut is gone: ENTITY/GLYPH draws use EMRION_ENTITY/GLYPH under Sodium
too. Sodium's fast paths (model cubes, glyphs, quad particles) push finished vertices into any `BufferBuilder` that supports
its intrinsics. `ExtendedBufferBuilderMixin` makes our builder report `canUseIntrinsics() = false`, so Sodium falls back to the
`VertexConsumer` calls, and ids, normals, tangents and mid UVs come from exactly the vanilla path's code. shortcut: Sodium logs
one WARN per class, and our extended formats get no fast path. A direct serializer (Iris's ModelToEntityVertexSerializer) is only
worth it if entity-heavy scenes show CPU cost.

**RT (step 5, done):** every RT effect works on the Sodium path, with the BLAS built from Sodium's region
arenas (RAYTRACING.md §17).

## 8. The two paths compared

Image: §7 and BENCHMARK.md (pinned MAE against Iris, Sodium equal to or closer than vanilla in 9 of 10 scenes).
Speed (BENCHMARK.md M8-S2, 3× interleaved): the Sodium path is **slower on this GPU-bound machine**:
- overview −8.3 % (118.0 against 128.7 FPS), sunset −5.0 %, forest −1.3 %, with better 1% lows in sunset and forest;
- RD 32 overview −2 % (88.7 against 90.5);
- it saves 1–1.5 ms of render-thread CPU per frame, which only shows on CPU-bound systems.

The GPU costs more in the opaque pass (+0.47 ms at RD 16, partly per pixel) and in the shadow pass (Iris's larger section
set). The decision stands (both paths supported, Iris-exact shadows on Sodium). The remaining opaque gap is open work (BENCHMARK.md M8-S2
lists what was ruled out).

## 9. Forcing the vanilla path while Sodium is installed

Evaluated, not built.
- Sodium has no option that turns its chunk renderer off. `sodium-options.json` only tunes it.
- Sodium's mixin config (`SodiumMixinPlugin` + `MixinConfig`) reads `config/sodium-mixins.properties`. There
  `mixin.core=false` (or finer keys such as `mixin.features.render.world=false`) disables whole mixin packages at startup.
  Mods can set the same keys through a `sodium:options` custom value in their `fabric.mod.json`. This is a supported
  compatibility switch, but it needs a restart, and `core` holds the `LevelRendererMixin` that installs Sodium's renderer together with
  the interfaces the other packages rely on. Disabling it leaves a half-loaded Sodium (GUI and feature mixins against a missing
  core), which is untested and fragile.
- Vetoing Sodium's mixins from our side (MixinSquared-style cancellation) works only at startup too, and couples us to Sodium's
  mixin class list.
Recommendation: to use the vanilla path, remove the Sodium jar (README). Both paths are fully supported. Nothing gets
hacked in.

## 10. Sodium versions

The Sodium integration targets **0.9.3-beta.5+mc26.3** exactly (before that, beta.1).
`fabric.mod.json` declares `recommends: sodium 0.9.3-beta.5` and `breaks: sodium <0.9.3-beta.5 || >0.9.3-beta.5`. With any
other Sodium, Fabric refuses to start and names the conflict ("Replace mod 'Emberlight' … compatible with sodium
0.9.3-beta.1", checked in game), instead of crashing in a mixin or at the first frame. Without Sodium nothing changes.

Changes between beta.1 and beta.5 that the port handles (`javap` of both jars, shader sources):

| Change in beta.5 | Port |
|---|---|
| `VertexBufferWriter` methods renamed `sodium$push`/`sodium$canUseIntrinsics`/… | `ExtendedBufferBuilderMixin` overrides `sodium$canUseIntrinsics` |
| `ModelQuadView` getters renamed `sodium$getSprite()` etc. | `DefaultFluidRendererMixin` targets `sodium$getSprite` |
| `u_Globals` gains `vec3 u_CameraOffset` (after `u_FogColor`); the region push offset is integer (`getCameraTranslation(int, int)`) | terrain_sodium declares the member and subtracts it in `emr_sodChunkOffset` (`SODIUM_VARIANT` 5); `SodiumShadow` pushes integer offsets |
| `UniformBufferManager.update(matrices, fog, CameraTransform)` | `SodiumShadow.draw` passes the camera |
| `ShaderChunkRenderer.compileProgram(pass, stage, ShaderCompilationParameters)`; the pipeline cache is cleared when the parameters change | `SodiumTerrain.pipelines()` passes the same parameters as `DefaultChunkRenderer.begin` (spherical fog option); pipelines Sodium recreates later go through `GbufferPipelines.buildLate` |

Verified on beta.5 (jar from Modrinth, sha1 33ea0f57…): all 10 pinned scenes render without errors, MAE against Iris
cave 0.27, entities 0.91, forest 0.62, forest_back 0.37, night 0.51, overview 0.46, rain 0.95, sunset 0.58, sunset_back 0.46,
water 0.56 (beta.1: 0.22–1.03, vanilla path 0.28–1.01). Overview 118.1 FPS (beta.1 118.0). Upgrading Sodium means
re-checking this table.

**Tested (BENCHMARK.md "Raster path at 1440p"):** 16-bit per-region quad indices, Sodium's indirect backend and block-face
culling off all leave the opaque pass unchanged or slower. The vertex decode is about 30 extra ALU ops.
**Opaque gap found (BENCHMARK.md "Sodium opaque gap"):** overdraw from Sodium's region-by-region draw order (cave: 24 % more
fragment shader invocations than vanilla). `DefaultChunkRendererMixin` draws the opaque passes in distance bands across regions
(only the bands some region uses, a region only in its bands; with no batch filled in bands, e.g. no pack or the OpenGL or
indirect backend, Sodium's single render runs unchanged).
Opaque cave 1.02 → 0.84 ms (RT off), 5.03 → 4.47 ms (RTX), now equal to vanilla.
The shadow pass now uses only visible sections as receivers (cave +12 % FPS on Sodium).

## Recommendation

Go, behind the step-1 gate. Sodium runs on Vulkan, is about 30 % lighter on the CPU, keeps our pass structure (it draws into
our RenderPass through `renderGroup`), and makes the reference look (AO, quad splitting, face culling, meshing set) automatic
instead of re-implemented. Our pipeline-swap model carries over, as Iris 1.11.7 shows. The cost is a Sodium-specific vertex
variant plus about 15 internal mixins. Keep the vanilla path until step 3 passes, then delete it.
