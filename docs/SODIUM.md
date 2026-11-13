# Emrion Shaders on Sodium (spike, 2026-11-13)

Decision being prepared: make Sodium a hard dependency and render terrain through Sodium's chunk renderer instead of
vanilla's, because Iris (the visual reference) always runs on Sodium. This page holds the spike's evidence and a step plan.
Sodium is Polyform-Shield-1.0.0: we may depend on it and mixin into it, but we must not bundle or copy its code. Decompiled
classes were read only as a spec (paths below are Sodium class names, not copies).

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

## 2. Our mod + Sodium + the user's pack: what breaks

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

- Today: 136.5 FPS (run p1 on this branch; master reference 137), 7.3 ms/frame, **GPU-bound** with about 5 ms CPU slack.
  Opaque pass 2.44 ms GPU, shadow 0.47 ms GPU + 1.46 ms CPU.
- Sodium changes only the vertex side of terrain: block-face culling drops whole back-facing facing slices per section in the main
  view (not in shadow, where Iris disables it), vertices are 36 B (or less) instead of 56 B, and there are fewer draw calls.
  Fragment work and overdraw stay the same, because back faces were already culled by the rasterizer.
- Expected: opaque −0.2…−0.6 ms, shadow GPU −0.05 ms (vertex size only), shadow CPU replaced by Sodium's async lists (no FPS
  effect while GPU-bound, may help 1 % lows). Net **≈ 137 → 140–150 FPS**. A loss is unlikely but not excluded: fog occlusion is
  off with a pack, as in Iris, and Sodium issues more, smaller draws (per region and facing) than our MDI path.
- CPU and meshing (measured without a pack): about −30 % CPU frame time, faster full remesh.

The step-1 gate below makes this measurable before anything is deleted.

### Plan (agent-days)

| Step | Content | Verifiable by | Effort |
|---|---|---|---|
| **1** | Sodium as a dev runtime mod (done: `-Psodium`). `terrain_sodium` converter variant on the **stock compact format** (mc_Entity, midTexCoord, tangent, midBlock get the existing defaults). Map `sodium:pipeline/*` to gbuffers_terrain/water. Teach `Set0Patcher` Sodium's two bind groups and offset the push constants. | Pack loads with Sodium. Composites work and terrain is drawn by Sodium through our gbuffers pipelines. Overview MAE vs Iris close to today's, block-ID effects excepted. **FPS ≥ 137 in 3 interleaved runs** (go/no-go). | 2–3 |
| 2 | Emrion `ChunkVertexType` (ids, midTexCoord, tangent/normal, midBlock + emission, separateAo) via `ChunkMeshFormats`/`Vertex`/meshing-task mixins. | All 6 scenes, MAE vs Iris ≤ today's. | 2–3 |
| 3 | Shadow pass on Sodium render lists (Iris design + our `ShadowCuller`), shadow block entities from Sodium. | forest/sunset shadows, shadow-pass timings. | 2–3 |
| 4 | Entities, particles and text: `VertexSerializerRegistry` serializers into our formats; check the entities and hand scenes. | entities scene MAE. | 1 |
| 5 | RT BLAS from Sodium arenas (usage flags, ranges, defrag, position decode). | RT shadows scene, VRAM report. | 3–4 |
| 6 | Optional: mesh shadow-only sections (the forced-meshing equivalent). | cave/sunset coverage. | 1–2 |
| 7 | Sodium becomes `depends` in `fabric.mod.json`. Delete the vanilla chunk path (SectionCompiler/BufferBuilder terrain, Indigo AO, fluid, RT vanilla-heap mixins). Re-baseline BENCHMARK.md. | build, full scene compare. | 1–2 |

Total **≈ 12–18 agent-days**. Steps 1–3 (about a week) give a full visual switch. RT (step 5) can follow later.

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

## Recommendation

Go, behind the step-1 gate. Sodium runs on Vulkan, is about 30 % lighter on the CPU, keeps our pass structure (it draws into
our RenderPass through `renderGroup`), and makes the reference look (AO, quad splitting, face culling, meshing set) automatic
instead of re-implemented. Our pipeline-swap model carries over, as Iris 1.11.7 shows. The cost is a Sodium-specific vertex
variant plus about 15 internal mixins. Keep the vanilla path until step 3 passes, then delete it.
