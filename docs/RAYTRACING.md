# Emberlight — Optional hardware ray tracing (M7 design)

Status: RT-1 (infrastructure) implemented and measured (2026-11-13, §9); RT-2 (variants, patch table, UI,
toggle) and RT-3 (RT shadows, Hybrid and Full) (§10); RT-4 reflections (§11); refraction and water
caustics (§12); RT AO (§13); entities in the TLAS (§14); block-light shadows (§15); global illumination (§16); RT style RTX (§18). All merged into
master. This document answers *how* M7 plugs hardware ray tracing (RT) into
the existing renderer without touching the Iris-equivalent path. Read [ARCHITECTURE.md](ARCHITECTURE.md) (§4.1 transpiler,
§5 runtime, §6 mixins) and [FORMAT.md](FORMAT.md) first. Pack code is only referenced by file:line (paths relative to
`testpacks/unbound-euphoria/shaders/`). It is never copied.

## 0. Ground rules

1. **RT is off by default.** With RT off, the converter is asked for exactly the request it gets today. The cache key, SPIR-V,
   manifest, pipelines and frame plan are byte-identical to the current build. No RT object (acceleration structure, scratch
   buffer, descriptor, query pool, extra pass) is created.
2. **RT is a different conversion output plus one runtime object.** Turning RT on reconverts the pack with RT patch points
   applied. The first time takes about 4 s; after that the cache hits. The runtime is then swapped through the existing
   hot-reload path, and the swap also creates `RtScene` (BLAS/TLAS). Turning RT off swaps back and destroys `RtScene`.
3. **Each effect can be switched on its own** (shadows, reflections, later AO). A pack whose patch points do not match keeps
   RT unavailable for that effect, and the UI says why.
4. **Device-level setup is the only thing that is always on** (when supported). A Vulkan device cannot gain extensions after
   creation, so the RT extensions, the `bufferDeviceAddress` feature, VMA's device-address flag and two extra usage bits on
   MC's terrain vertex heaps are enabled at boot whenever the GPU supports them. None of these allocate memory or change
   rendering. Step 1 must prove that FPS does not change. Kill switch: `-Demrion.rt.device=false` (or `rt.device=false` in the
   config) restores today's device exactly.

## 1. Platform facts (checked)

| Question | Answer |
|---|---|
| Does MC 26.3 enable the RT extensions? | **No.** `VulkanFeatureSets` (`REQUIRED_FEATURESET`, `optionalFeatureSets()`) has nothing RT-related, and `bufferDeviceAddress` is not requested. The instance is created with `apiVersion 1.2` (`VulkanInstance:47`), so SPIR-V 1.4/1.5 and BDA are core. No instance extension is needed. |
| Hook for device extensions and features | The existing `VulkanFeatureSetsMixin` pattern is enough. A `FeatureSet(name, extensions, features)` can carry **device extensions** as well as features, and MC enables an optional set all-or-nothing when supported (`VulkanBackend:161-170`, extensions go into `ppEnabledExtensionNames` in `createDevice:400`). `VulkanPNextStruct` is a public record, so new feature structs work: `new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class)`, `...RayQueryFeaturesKHR.class`. `bufferDeviceAddress` goes into MC's existing `VK12_FEATURES_STRUCT`. |
| VMA | `VulkanBackend.createVma` sets **no flags**. Without `VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT`, VMA does not add `VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT`, so `vkGetBufferDeviceAddress` on MC's buffers would be invalid. A new mixin sets the flag (only when the RT set was enabled). lwjgl-vma 3.4.3 has the constant. |
| lwjgl-vulkan 3.4.3 bindings | Present in MC's jar: `KHRAccelerationStructure`, `KHRRayQuery`, `KHRDeferredHostOperations`, `VkAccelerationStructureInstanceKHR`, `VkPhysicalDeviceAccelerationStructureFeaturesKHR`, `VkPhysicalDeviceRayQueryFeaturesKHR`. No new dependency. |
| shaderc / transpiler | **`GL_EXT_ray_query` requires `#version 460`.** Under `#version 450`, glslang fails with "'rayQueryEXT' : undeclared identifier". With `#version 460`, our settings (Vulkan 1.2, SPIR-V 1.5, `-O`) compile with MC's **lwjgl-shaderc 3.4.3** (tested from Java), and the result passes `spirv-val --target-env vulkan1.2` for a fragment and a compute stage. Both include a candidate loop that alpha-tests against a sampler, with vertex fetch via `GL_EXT_buffer_reference`. Emitted capabilities: `RayQueryKHR`, `PhysicalStorageBufferAddresses`, and `Int64` if `uint64_t` is used. So the transpiler emits `#version 460` **only for RT-patched stages**, and we use `GL_EXT_buffer_reference_uvec2` to avoid needing `shaderInt64`. |
| Reflection | lwjgl-spvc 3.4.3 reports the TLAS under `SPVC_RESOURCE_TYPE_ACCELERATION_STRUCTURE` with the right set and binding (tested). `SpirvReflection` needs one more `addDescriptors(..., "acceleration_structure")` line. |
| Required device extensions | `VK_KHR_acceleration_structure`, `VK_KHR_deferred_host_operations` (a dependency of the former, never used: all builds happen on the device), `VK_KHR_ray_query`. Features: `accelerationStructure`, `rayQuery`, `bufferDeviceAddress`. The RTX 4070 Ti / NVIDIA 615.78 has all of them. |

## 2. Acceleration structures (`common/.../rt/`)

New package `dev.emrion.shaders.mod.rt`:

| Class | Role |
|---|---|
| `RtCapability` | Static: was the "Emberlight RT" feature set enabled at device creation (set from a `VulkanBackend.createDevice` HEAD inject that checks `featureSet.contains(EMRION_RT)`), plus the `VkPhysicalDeviceAccelerationStructurePropertiesKHR` limits (scratch alignment). Loads the KHR function pointers through `VkDevice` capabilities (LWJGL does this automatically). |
| `RtScene` | Exists only while an RT runtime is active. Owns the per-section BLAS map, the TLAS, the instance and section-table buffers, and the scratch buffer. Created in `PackRuntime` when `manifest.rt` is non-empty, destroyed in `PackRuntime.destroyLater()`. |
| `AccelPool` | Sub-allocates BLAS storage from 64 MiB `VkBuffer`s (usage `ACCELERATION_STRUCTURE_STORAGE_BIT_KHR`, 256 B alignment) with a VMA virtual block (`vmaCreateVirtualBlock`, available in lwjgl-vma). This avoids roughly 20 000 separate VMA allocations. |
| `SectionTracker` | Mixin-fed queue of "section X, layer L got a new mesh / lost its mesh". |
| `RtDebugView` | A compute pass that traces the TLAS from the camera and writes hit normal, instance id or alpha-tested albedo to an image shown through `DebugPost`. This is the step 1 test tool. |

### 2.1 Geometry source: MC's section vertex heaps

- Terrain meshes live in `SectionRenderDispatcher.chunkUberBuffers`: per `ChunkSectionLayer` (SOLID, CUTOUT, TRANSLUCENT), one
  `UberGpuBuffer` of 128 MiB vertex heaps (`SectionRenderDispatcher:75`) and 32 MiB index heaps. Heaps are created lazily by
  `UberGpuBufferHeap.<init>` → `gpuDevice.createBuffer(name, usage | 8 | 16, size)` (`UberGpuBuffer:190`) and closed when they
  become empty. Allocations never move. A re-upload frees the old slice and allocates a new one.
- Vertices are `EMRION_BLOCK` (56 B, `EmrionVertexFormats.TERRAIN`): Position `RGB32_FLOAT` at 0, which is
  **section-relative** (`SectionCompiler:100`), Color 12, UV0 16, UV2 24, Normal 28, mc_Entity 32, mc_midTexCoord 36,
  at_tangent 44, at_midBlock 48. The uber buffer is aligned to lcm(28, 56) = 56 (`SectionRenderDispatcherMixin`), so a slice
  offset is always a whole vertex.
- Solid and cutout draws use MC's shared sequential quad index buffer. Translucent draws have a per-section sorted index
  buffer that is re-uploaded on camera moves (index-only, `vertexBuffer == null`). **The BLAS ignores both** and uses our own
  `RtQuadIndices` buffer: u32 indices `0,1,2, 2,3,0` + 4k, the same winding as MC's quads, grown on demand. Translucent
  re-sorts therefore never trigger a rebuild.
- One BLAS per (section, layer) with one geometry: `vertexFormat = R32G32B32_SFLOAT`, `vertexStride = 56`,
  `vertexData = heapAddress + slice.getOffsetFromHeap()`, `maxVertex = quads*4-1`, `indexType = UINT32`,
  `primitiveCount = quads*2`, where `quads = draw.indexCount()/6` (`SectionMesh.SectionDraw`).

### 2.2 Buffer usage flags on MC's heaps

- New custom usage bit `Formats.USAGE_RT_INPUT` (like the existing `USAGE_STORAGE` for textures; the frontend does not
  validate bits). `UberGpuBufferRtMixin` adds it in `UberGpuBufferHeap.<init>` (`@ModifyArg` on `createBuffer`) **only for
  vertex heaps** (`usage & 32`) and only when `RtCapability.enabled`. `VulkanConstMixin` gets a second inject on
  `bufferUsageToVk` that maps the bit to `SHADER_DEVICE_ADDRESS_BIT | ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR`.
  `SHADER_DEVICE_ADDRESS` also lets hit shading read UVs and colours from the same heap.
- **Why boot-time, not toggle-time:** heaps are created on demand and live as long as the `SectionRenderDispatcher`. If the
  flags followed the RT toggle, RT-on would find old heaps without them and would have to recreate MC's uber buffers. With the
  flags present from the start (when capable), a toggle only needs our own objects. On NVIDIA the flags do not change the
  buffer's memory requirements, and VMA's BDA flag only adds `VkMemoryAllocateFlagsInfo` to block allocations. Step 1 measures
  this and keeps the kill switch.
- Index heaps are untouched (we never read MC indices). Entity and per-frame buffers are untouched in v1.

### 2.3 Build and rebuild

- **Hook point.** `RenderSection.vertexBufferUploadCallback(mesh, layer)` (`SectionRenderDispatcher:360`) runs right after the
  staging copy into the uber heap. A HEAD inject records `(sectionNode, layer, mesh)` in `SectionTracker`.
  `releaseSectionMesh` and `removeAllocation` record removals. At **`SectionRenderDispatcher.uploadTerrainBuffersToGpu` TAIL**
  (after the `StagingBuffer.Uploader` has closed and submitted its copies), `RtScene` resolves each slice
  (`vertexBuffer.getAllocation(mesh)` → heap `VkBuffer` + offset), records the BLAS builds into a raw command buffer and
  submits it with `execute(cb)`. Because our builds are submitted on the same queue after MC's copies, and before any later
  upload could reuse a freed slice, the data a BLAS reads is always the data that was just uploaded. Uploads that happen
  without a pack (`EmrionVertexFormats.active() == false`) or without an `RtScene` are ignored.
- **Always rebuild, never refit:** a re-meshed section changes its vertex count and topology. A section BLAS is small (median
  of roughly 1–3 k triangles, about 10–50 µs on the GPU). Flags: `PREFER_FAST_TRACE | ALLOW_COMPACTION`. SOLID geometry gets
  `VK_GEOMETRY_OPAQUE_BIT_KHR`, so traversal skips the candidate loop. CUTOUT and TRANSLUCENT geometry does not.
- **Budget.** At most N triangles per frame (default 2 M, about 1 ms) and at most 400 BLAS per frame (`-Demrion.rt.blasPerFrame`;
  each costs ~25 µs of CPU for sizes, allocation and creation, so a world join or RT switch-on with 2000+ sections took one
  60 ms frame). The rest waits for the next upload hook or the next
  `levelStart`. A section without a BLAS yet is simply missing from the TLAS for a frame or two (no shadow there), which is
  invisible during normal play and acceptable during the initial load.
- **Scratch:** one 64 MiB scratch buffer (aligned to `minAccelerationStructureScratchOffsetAlignment`) used as a ring inside a
  batch, with a barrier (`AS_BUILD write → AS_BUILD read/write`) when it wraps. Builds go out as batches of
  `vkCmdBuildAccelerationStructuresKHR` with many infos.
- **Compaction (done 2026-11-19):** after each build batch its compacted sizes go into a query pool
  `ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR` (reset and written in the build's own command buffer), one region of 2048
  queries per frame ring slot. When the slot comes round again (`buildTlas`), every BLAS that is still the same build
  (`Entry.buildId`) and shrinks by 10 % or more is copied (`vkCmdCopyAccelerationStructureKHR`, `COMPACT`) into a buffer of
  that size before the TLAS build; the original goes through `destroyLater`. Results not yet available are skipped (the
  BLAS stays uncompacted). Test world, RT distance 16: 521 → 135 MiB (77 → 20 B per triangle). `-Demrion.rt.compact=false`
  turns it off.
- **RT on with a world already loaded:** call `levelExtractor.allChanged()` (the same full rebuild a pack activation already
  does, `EmrionVertexFormats.rebuildChunks`). This is simpler than walking MC's allocation maps and costs about 1–2 s of chunk
  rebuilding, which only happens when the user switches RT on.

### 2.4 Memory budget at RD 16 (estimate; measured numbers in §9.2)

- RD 16 → 33² = 1089 columns × 24 sections = 26 k sections. About 30–50 % are non-empty: the surface plus cave walls
  (underground stone is face-culled).
- Estimate: **5–10 M quads = 10–20 M triangles** in the overworld (more in dense forests: leaves draw all faces). At 224 B per
  quad, today's `EMRION_BLOCK` vertex data is 1.1–2.2 GB. Step 1 cross-checks the estimate with the uber-buffer heap count.
- BLAS on NVIDIA: about 60–90 B/triangle uncompacted, about 30–45 B compacted, so **≈ 0.6–1.8 GB uncompacted and 0.3–0.9 GB
  compacted**. Plus scratch 64 MiB, the instance buffer (≤ 30 k instances × 64 B = 2 MB), the section table (≤ 30 k × 16 B)
  and the TLAS (a few MB).
- Headroom: the measured peak with 256x+PBR is already 11.96 GB of 12 GB (a known risk; M5b is working on it). RT
  reflections pay for themselves when they let the converter drop WSR's `bufferObject.0` (**810 MB**, read only by
  `getFaceData`, `lib/voxelization/reflectionVoxelData.glsl:47`) plus `wsr_img`/`wsr_lod_img` (≈ 34 MB) (§4.2). Until then,
  RT shadows add memory, and `RtScene` logs its total in the F7 overlay. An RT-distance option (default = render distance)
  limits the BLAS set if needed.

### 2.5 Entities

- **Implemented** (see "RT entities" below). The design changed from "usage flags on MC's per-frame
  buffers" to a CPU copy: the entity draws are captured from their CPU slices before `StagedVertexBuffer` uploads them, so MC's
  buffers stay untouched. One BLAS per draw (render type + texture), refit or rebuilt per frame, instance mask 0x04. Effects opt
  in per effect (`<effect>:entities`); RT shadows Full mode then has entity shadows.

### 2.6 TLAS per frame

- Built at `FrameExecutor.levelStart`, in the raw command buffer it already records, before the shadow pass and the gbuffers.
  It is rebuilt every frame (`PREFER_FAST_BUILD | ALLOW_UPDATE`): about 20 k instances build in roughly 0.1–0.3 ms. While the
  instance set is the same as at the last build (same BLAS, masks, flags and order; a hash taken while the instances are
  written) the TLAS is refitted instead (`MODE_UPDATE`): camera movement moves every instance by the same offset, so the
  tree stays as good as a build; only moving entities loosen it, hence a full build at least every 30 frames
  (`-Demrion.rt.tlasRefits`, 0 = always build). 0.16 → 0.04 ms GPU in the test scenes. One TLAS object of fixed capacity (grown and re-created only when the instance
  count exceeds it) keeps the set-1 descriptor **static**: it is written once into every pre-built flip-state descriptor set.
- **Camera-relative transforms.** For each (section, layer) BLAS, the instance transform is a pure translation
  `sectionOrigin - cameraPosition`, computed in **double** on the CPU and then stored as float. TLAS space therefore equals the
  pack's `playerPos` space (`playerPos = world - cameraPosition`), so a pack ray starts at `playerPos` with no conversion and no
  precision loss far from the origin.
- `instanceCustomIndex` (24 bit) = slot in the `EmrionRtSections` SSBO (`uvec2 vertexAddr; uint layer; uint format`, format 1 = Sodium path, §17).
  `mask`: 0x01 = SOLID/CUTOUT, 0x02 = TRANSLUCENT (water, glass, ice).
  `flags`: `TRIANGLE_FACING_CULL_DISABLE` (MC quads are one-sided, but shadow rays must hit back faces, like the pack's shadow
  pass, which renders without culling).
- Instances: every loaded section with a BLAS within the RT distance, not frustum-culled (shadow and reflection rays leave the
  view frustum).

### 2.7 Queue and synchronisation

- **Graphics queue only.** All work is recorded in raw command buffers (`VulkanCommandEncoder.allocateAndBeginTransientCommandBuffer()`
  → `execute(cb)`), like every other raw pass. MC's frame model (timeline semaphore, two submits in flight, `queueForDestroy`)
  applies unchanged. Freed BLAS go through `VulkanContext.destroyLater`.
- Barriers (sync2, which MC requires): after the BLAS batch, `ACCELERATION_STRUCTURE_BUILD → ACCELERATION_STRUCTURE_BUILD`
  (AS write → AS read) before the TLAS build. After the TLAS build, `AS_BUILD → FRAGMENT_SHADER | COMPUTE_SHADER`
  (`ACCELERATION_STRUCTURE_READ`). Before the next frame's TLAS rebuild, `FRAGMENT | COMPUTE → AS_BUILD` (WAR). MC's own
  ALL_COMMANDS barriers at pass ends already cover most of this, but the raw passes must not rely on them (§5.8 rule).
- **Async compute: not in v1.** The work is small (TLAS ≈ 0.2 ms, BLAS budget ≤ 1 ms, mostly during chunk loading), and a
  compute queue would need cross-queue ownership transfers of MC's uber heaps, which would be fragile. Revisit only
  if the F7 profiler shows BLAS builds in the frame-time critical path.

## 3. How RT plugs into the pack

### 3.1 Inline ray queries (a) vs. a separate RT pass (b)

| | (a) Ray query inline in pack stages | (b) Emrion pass writes a buffer that patched pack code samples |
|---|---|---|
| Shadows | **Required.** Complementary computes sun lighting **forward in the gbuffers** (`lib/lighting/mainLighting.glsl:349-395`, included by gbuffers_terrain/entities/water/…). A screen-space visibility pass would need this frame's depth before the gbuffers run. Previous-frame reprojection breaks at disocclusions and on translucents. | Not viable without a depth pre-pass. |
| Reflections | Natural. The pack traces in `composite` (fullscreen, `program/composite.glsl:169`) and `gbuffers_water` (`reflections.glsl:250`) per pixel and shades the hit with its own code. | Possible for composite, but the hit shading must then be re-implemented outside the pack, and water would need a second path. |
| AO / GI | Possible, but a noisy result needs a denoiser and history buffers. | **Better:** an Emrion compute pass after `deferred` depth, with its own temporal accumulation, writing `emrion_rtao`. The patched `GetAmbientOcclusion` (`program/deferred1.glsl:95`) then just samples it. |
| Robustness | Patches touch only the variant's SPIR-V. The frame plan is unchanged and so are the descriptor flips. Risk: a pack refactor that moves the function. | Needs new planner commands, new targets and a barrier plan. More moving parts, but pack-independent output. |

**Decision:** (a) for shadows and reflections, (b) for AO/GI (step 5). In both cases the pack's code decides how the result
is used (bias, colour, fog, TAA), which keeps the look close to Iris apart from what RT is meant to improve.

### 3.2 RT variants as separate conversion outputs

- `Converter.Request` gets `Set<String> rt` (`shadows`, `reflections`, `ao`). `CacheKey.compute` appends `rt=<sorted list>` to
  `env` **only when the set is non-empty**. So the RT-off key equals today's key and the cache directory is shared with the
  current build. An empty set takes no RT code path at all in the converter.
- The manifest gets an optional `rt` object (FORMAT.md addition): `{ "effects": ["shadows"], "unavailable": {"reflections":
  "patch 'voxelRayTrace' not found"}, "tlasBinding": 60, "sectionsBinding": 61 }`. It also gets a new resource kind
  `acceleration_structure` (builtin `emrion_tlas`) and the storage buffer `emrion_rt_sections`. `DescriptorPool` and
  `Descriptors` learn `VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR` (`VkWriteDescriptorSetAccelerationStructureKHR`).
- `report.json` lists per effect: applied (with the stages it touched) or unavailable (with the reason).

### 3.3 Patch points

- **Where:** after preprocessing, in the transpiler, on its function-level structure (function bodies are already token
  ranges, ARCHITECTURE §4.1). After preprocessing, option-dependent `#if` blocks are resolved, so a match means "this function
  is live in this stage with these settings". New classes in the converter:
  - `converter/rt/RtPatchTable`: loads `converter/src/main/resources/rt/<table>.json`.
  - `converter/glsl/RtPatcher`: matches and replaces function bodies, injects our helper library, and marks the stage as
    "needs `#version 460` + extensions".
- **Patch table format** (our own file, LGPL, no pack code):
  ```json
  { "id": "complementary-r5",
    "detect": ["lib/lighting/shadowSampling.glsl", "lib/materials/materialMethods/worldSpaceRef.glsl"],
    "effects": {
      "shadows": { "requires": ["gbuffers_terrain"], "patches": [
        { "function": "vec3 SampleTAAFilteredShadow(vec3,float,int,bool,float,float)", "body": "rt/complementary/shadow_taa.glsl" },
        { "function": "vec3 SampleFilteredShadow(vec3,float,float,float)",             "body": "rt/complementary/shadow_filtered.glsl" },
        { "function": "vec3 SampleBasicFilteredShadow(vec3,float)",                    "body": "rt/complementary/shadow_basic.glsl" } ],
        "needs": ["GetShadowPos", "shadowModelViewInverse", "shadowProjectionInverse", "shadowMapBias", "SampleShadow"],
        "skipIds": "rt/complementary/shadow_skip_ids.glsl" },
      "reflections": { "requires": ["composite"], "patches": [
        { "function": "vec4 voxelRayTrace(vec3,vec3,vec3,float,float,float,out float)", "body": "rt/complementary/wsr_trace.glsl" } ],
        "needs": ["getShadedReflection", "playerToSceneVoxel", "CheckInsideSceneVoxelVolume", "DoFog"] } } }
  ```
  - A function matches by **name + parameter types** (qualifiers included). `needs` lists pack symbols the replacement body
    calls, which must exist in the same stage.
  - An effect is **available** only if every `requires` stage contains at least one patched function and every patched
    function in every stage satisfies its `needs`. Otherwise the whole effect is dropped for the pack. The converter output is
    then identical to the effect being off, and the manifest's `unavailable` gives the reason, which the UI shows. There is
    never a half-applied effect.
  - Replacement bodies only call Emrion helpers (`rt/emrion_rt.glsl`: `emrionTraceVisibility`, `emrionHitFetch`,
    `emrionAlphaTest`, …) and the listed pack symbols. Helpers declare the set-1 bindings of the TLAS and section table. The
    transpiler gives them their manifest bindings like any other resource.
- **Generality:** the table is chosen by `detect` (files present). Complementary Reimagined/Unbound r5.x and Euphoria share
  the same functions, so one table covers the target pack and its relatives. Other packs get "RT unavailable: no patch table"
  until someone writes one.

### 3.4 Option interplay (Complementary / Euphoria, reference settings: SHADOW_QUALITY=3, shadowDistance=256, TAA, WORLD_SPACE_REFLECTIONS=1, COLORED_LIGHTING=512, ENTITY_SHADOW default 1)

| Option / feature | Where | RT handling |
|---|---|---|
| `SHADOW_QUALITY` 0 / 1 / ≥2+TAA | `GetShadow` picks `SampleBasicFilteredShadow` / `SampleFilteredShadow` / `SampleTAAFilteredShadow` (`shadowSampling.glsl:132-138`) | All three are patched. Ray count = `shadowSamples` (2/4/8/16 for 2–5), capped by the "RT shadow rays" option (default 1 with TAA, since the pack's TAA accumulates). `SHADOW_QUALITY=-1` → the effect is unavailable (the pack has no shadows). |
| `shadowDistance`, `shadowLength` gate | `mainLighting.glsl:266-268` (inside the big lighting function, not a patch point) | Unchanged in v1: RT shadows cover the same distance as the map. A later option could raise `shadowDistance` in the RT variant only, but that also changes the map extent, so it is not in v1. |
| Translucent coloured shadows (`shadowcolor0`, `shadowtex1` vs `shadowtex0`) | `SampleShadow` (`shadowSampling.glsl:11-27`) | Kept from the shadow map (see hybrid, §4.1). |
| Water caustics | Written into `shadowcolor0` by `program/shadow.glsl:127-156` | Kept from the shadow map (same mechanism). |
| `ENTITY_SHADOW` | Entities drawn into the shadow map | Hybrid: from the map (plus RT entities if `shadows:entities`). Full: from the entity BLAS with `shadows:entities` (default on), none without. |
| Foliage that does not cast shadows | `program/shadow.glsl:62` discards mats 10003, 10005, … in the shadow pass | `shadow_skip_ids.glsl`: candidate hits whose `mc_Entity` is in that list are not confirmed (the id is read from the hit vertex). |
| Waving foliage | Shadow VS moves vertices (`shadow.glsl:300`) | BLAS is static, so RT shadows of leaves do not sway. Accepted (sub-block motion). |
| Volumetric light / light shafts | `volumetricLight.glsl:225` samples the map | Unchanged: the shadow map stays. |
| Cloud shadows | `GetCloudShadow` | Unchanged (not shadow-map based). |
| Coloured light (floodfill), WSR voxel data | Shadow **vertex** stage stores voxels and face data (`vertexPipelineStoresAndAtomics`) | The shadow pass keeps running. RT must never remove it while any of these consumers is live. |
| `WORLD_SPACE_REFLECTIONS=-1` / `WORLD_SPACE_REF_MODE=1` | No `voxelRayTrace` live | Reflection effect unavailable ("enable World-Space Reflections in the pack"). This is correct: there is nothing to replace. |

## 4. Per-effect plan

### 4.1 RT shadows (sun/moon)

- **Patched body** (all three sampling functions call one helper):
  `emrionRtShadow(shadowPos, offset, samples, leaves, colorMult, colorPow)`:
  1. Invert the pack's mapping exactly: undo `*0.5+0.5`, undo `z*0.2`, and undo the distortion
     (`r' = r/(r·b + 1-b)` ⇒ `r = r'(1-b)/(1 - r'·b)` with `b = shadowMapBias`). Then `shadowProjectionInverse` and
     `shadowModelViewInverse` give the **biased** player-space position. All of the pack's bias logic (normal bias,
     subsurface, the cave leak fix, `mainLighting.glsl:286-345`) stays in effect without having to patch the giant lighting
     function.
  2. Ray direction: the light vector (`shadowModelViewInverse` z axis, which follows `sunPathRotation`), jittered inside a cone.
     The cone half-angle is derived from the pack's `offset` (softness grows with `SHADOW_SMOOTHING` and rain as in
     `GetShadow:104-118`) times a tunable factor. The jitter uses the pack's own `InterleavedGradientNoiseForShadows()`, which
     rotates with `frameCounter` under TAA, so the pack's TAA does the temporal accumulation as it already does for the map's
     penumbra. No denoiser of our own in v1.
  3. `rayQueryInitializeEXT(tlas, TerminateOnFirstHit, mask 0x01, origin, tMin 0.02, dir, tMax = 512)`. Candidate loop for
     non-opaque geometry: fetch the hit quad's UVs from the vertex heap (`instanceCustomIndex` → section table →
     `vertexAddr`, primitive → quad = prim>>1, triangle (0,1,2) or (2,3,0)), interpolate with the barycentrics, take
     `textureLod(atlas, uv, 0).a` against the 0.1 cutoff (`GetShadow` uses mip 0 like the pack's shadow pass), skip the
     `skipIds`, and confirm otherwise.
- **Modes** (option "RT Shadows: Off / Hybrid / Full"):
  - **Hybrid (default):** `result = rtVisibility * SampleShadow(shadowPos, colorMult, colorPow)` with the pack's single-tap
    `SampleShadow`. The map adds entity shadows, translucent colour and caustics. RT adds exact, aliasing-free, non-blurred
    terrain occlusion (contact shadows, thin geometry, no acne or peter-panning in RT-only areas). Remaining map artefacts can
    still darken. That is the honest trade-off of hybrid.
  - **Full:** `result = rtVisibility * translucentTint`, where the tint comes from the map only where its opaque depth says lit
    (`shadowtex1` test), i.e. colour and caustics stay but map occlusion is ignored. No entity shadows until step 5.
- **Shadow pass when RT shadows are on:** v1 keeps it **exactly as is**. It feeds volumetric light, translucent colour,
  caustics, entity shadows (hybrid), floodfill voxels and WSR face data. Skipping its depth rendering would only be possible if
  all of these were RT too, so there is no perf win to promise for v1. Later option, only once VL is RT or off: run the
  terrain shadow draws with a `rasterizerDiscardEnable` pipeline variant (vertex-stage voxelisation still runs, no
  fragments). That is purely an optimisation.
- **Cost estimate:** 1 ray per lit fragment within `shadowDistance`, with gbuffers overdraw. On a 4070 Ti at 1080p, about
  0.5–1.5 ms. The map's 2×4 PCF taps are replaced (Full) or kept as 1 tap (Hybrid), which saves some fragment time back.
  Measured in step 3.

### 4.2 RT reflections (replacing WSR's voxel trace)

- **Patch:** `voxelRayTrace(playerPos, voxelPos, rayDir, RVdotU, RVdotS, dither, out traceLength)`
  (`worldSpaceRef.glsl:230`). It is called from `getWSR` (`:311`), which `GetReflection` uses in `composite` and
  `gbuffers_water`. Everything around it stays the pack's: SSR/WSR blending (`WORLD_SPACE_REF_MODE`), roughness, fresnel,
  sky fallback, fog.
- **v1 body: RT traversal, pack shading.** Trace mask 0x01 (alpha-tested like shadows, cutoff `0.0041` as
  `getShadedReflection:103`). On a hit: `traceLength = t`, `intersection = playerPos + (t-0.001)·rayDir`, `normal = geometric
  triangle normal` (axis-aligned for full blocks), and the hit block's voxel position is `floor(intersection - 0.01·normal)`
  in scene-voxel space via `playerToSceneVoxel`. Then call the pack's own `getShadedReflection(voxelPos, playerPos,
  intersection, rayDir, normal, mat, dither)` and apply the same fade and `DoFog` code as the original. `mat` comes from
  `wsr_img` at that voxel, as before. Hits outside the voxel volume (`CheckInsideSceneVoxelVolume` false) return the
  original's miss value (`vec4(0)`, `traceLength = 999999`), so the pack falls back to SSR/sky exactly as today.
  - What improves: exact intersections with slabs, stairs, fences, plants and leaves (the voxel trace sees whole cubes), no
    LOD-grid misses, and correct occlusion.
  - What stays: shading quality and range (= voxel volume), since lighting, AO and coloured light still come from the voxel
    data. Hit shading uses RT shadows when that effect is also on, because `getShadedReflection` calls `GetShadow`, which is
    patched.
- **v2 (step 4b): shading from the hit vertex, WSR memory freed.** A second patch makes `getFaceData(ivec3, vec3)` return
  `faceData` synthesised from the hit vertex instead of `blockDataSSBO`: `glColor` = vertex Color.rgb, `lightmap` = UV2/256
  (the same encoding the voxeliser stores), `textureBounds` = sprite bounds from `mc_midTexCoord` and the UV extent of the
  quad. Also patch `storeFaceData` (`reflectionVoxelData.glsl:55`) to a no-op. Then nothing references `bufferObject.0`, so
  the converter allocates it only if it is referenced (this has to be verified or implemented in `IrisDimensionPlanner`
  resource planning). **That frees 810 MB**, plus `wsr_img`/`wsr_lod_img` once `voxelRayTrace` no longer reads them. It also
  makes hits outside the voxel volume possible: shade them with the vertex data, `GetShadow` (RT), and ambient from the
  lightmap without floodfill.
- **Material data available at a hit:** albedo from the block atlas (`textureAtlas` builtin, already bound) at the
  interpolated UV0; labPBR normal/specular from `PbrAtlases` (same UVs, so the step 4b option binds the block PBR atlas
  views); lightmap from UV2; the face normal from the triangle; the block id from mc_Entity (emission, foliage); sky, fog and
  sun/moon colours from `EmrionUniforms` as the pack's code already reads them.

### 4.3 RT AO / GI (step 5, outline only)

- `EmrionRtAoPass` (compute, after `deferred` depth is final): 1–2 cosine-weighted short rays (≤ 4 blocks) per pixel from the
  reconstructed position and the normal in colortex, plus temporal accumulation with our own history image (reprojection
  through `gbufferPreviousModelView`/`Projection`) and a small spatial blur.
- The patched `GetAmbientOcclusion(sampler2D,float,float,float,float,float,float,vec3)` (`deferred1.glsl:95`) returns
  `texelFetch(emrion_rtao, ivec2(gl_FragCoord.xy), 0).r`, raised to the pack's `SSAO_IM` strength so the pack's AO slider
  still works. GI (one bounce, lighting the hit with lightmap + sun visibility) builds on the same pass. It needs its own
  design pass once AO works.

## 5. UI, toggle, persistence

- **Config:** `config/emberlight.properties` (already used by `PackManager.CONFIG`), never the Iris settings `.txt`, which
  stays 1:1 with Iris. Keys: `rt.enabled=false` (master), `rt.shadows=hybrid|full|off`, `rt.reflections=true|false`,
  `rt.ao=false`, `rt.shadowRays=1`, `rt.distance=0` (0 = render distance), `rt.device=true`.
- **Options screen:** `ShaderOptionsScreen` gets an extra top-level page **"Emberlight RT"**, injected by us and not part of the
  pack's screen tree. It shows the master switch, the per-effect cycles, and a status line per effect: "unsupported GPU"
  (`!RtCapability.enabled`), "unavailable: <manifest reason>", "active", or "BLAS: 0.6 GB, 14 210 sections". Apply writes
  only our config and starts the reconvert.
- **Key:** `key.emberlight.rt` (default unbound; F7 = profiler, R = reload, O = packs) toggles `rt.enabled`. It is
  registered in `EmrionShadersClient` like the others. It shows a toast ("RT on: shadows, reflections").
- **Switching:** `PackManager` builds the `Converter.Request` with `rt = enabled ? effects : {}`, so off is the exact current
  request. It then follows the existing reload path (async convert, cache hit after the first time; pipelines built in the
  background; atomic swap at a frame boundary). The RT runtime creates `RtScene` and triggers the chunk rebuild (§2.3).
  Swapping to non-RT destroys `RtScene` (all BLAS, the TLAS, scratch and pools through `destroyLater`). RT-off therefore
  allocates nothing.

## 6. Validation

- **RT-off bit-identity (blocking for every M7 merge):**
  1. Converter: `TargetPackConversionTest` with `rt = {}` must produce the same cache key and byte-identical SPIR-V and
     manifest as master. Add a golden-hash test over the cache directory.
  2. In game: the autotest scenes (`mod/emrion-*-scenes.txt`, pinned `frameTimeCounter`) on master vs. the M7 build with RT
     off. Screenshot diff **MAE = 0** (exact, not "within noise"), and the same with `-Demrion.rt.device=false`.
  3. Benchmark (BENCHMARK.md route, 3 runs): RT-capable device setup (extensions, VMA BDA flag, heap usage bits) vs.
     `rt.device=false`. Avg FPS and 1 % lows must be within the run-to-run noise. If not, the device setup becomes opt-in
     (`rt.device=false` default plus a restart notice).
  4. Validation layer (Steam's layer, as in M6) clean in both modes, including toggling RT 5× (no leak: VRAM back to baseline
     after RT off, as in the M5 hot-reload test).
- **RT-on:** the same scenes, compared side by side with RT off and Iris (qualitative: contact shadows under leaves and fences,
  no acne, slab and stair reflections). Plus F7 numbers: TLAS build ms, BLAS ms per frame, ray pass cost (gbuffers delta),
  `RtScene` memory. A stress scene: flying fast through new chunks (BLAS budget, no hitches above 2 ms) and breaking or
  placing blocks (a section rebuilds within 1 frame, with no stale shadow).
- **Debug view (step 1):** `-Demrion.rt.debug=normals|instances|albedo` replaces the final image with `RtDebugView`. Every
  block visible in the raster image must be in the same place in the traced image (overlay toggle), including cutout holes.

## 7. Risks

| Risk | Mitigation |
|---|---|
| VRAM: 0.3–1.8 GB BLAS on top of 11.96 GB peak (256x + PBR) | Compaction (step 5), RT distance option, F7 memory readout. Step 4b frees 850 MB of WSR data. Document "RT + 256x PBR may exceed 12 GB" until measured. |
| Usage bits / VMA BDA flag on MC allocations change something (memory type, perf) | Measured in step 1 against `rt.device=false`. If not neutral, make the device setup opt-in with a restart. |
| MC's uber-buffer behaviour (heap close/re-create, slice reuse) races our BLAS build | Build at `uploadTerrainBuffersToGpu` TAIL in submission order. Sections are removed from the TLAS before their slice can be reused. The debug view plus a block break/place stress test covers it. |
| `#version 460` changes semantics of pack code in patched stages | Only patched stages, only in the RT variant. 460 only adds a few built-ins and keywords. The full-pack compile test plus RT-on screenshots catch problems. |
| A patch matches but behaves differently after a pack update | The table is versioned per pack family (`detect` + signatures + `needs`). An unknown version gives "unavailable", not wrong output. Golden RT-on screenshots per pack version. |
| Driver issues (ray queries in fragment shaders with discard and early-Z on NVIDIA; long candidate loops in leaves) | Leaves are CUTOUT → candidate loop. Cap the loop iterations, use `TerminateOnFirstHit` for shadows. If a fragment-stage ray query misbehaves, switch shadows to Full mode first; there is no fallback to (b) for shadows (§3.1). |
| Translucent water in BLAS doubles as an occluder | Mask 0x02 is never traced for shadows or v1 reflections. Translucent colour comes from the map. |
| Initial world load: thousands of BLAS builds | Per-frame triangle budget. Sections without BLAS are simply absent for a few frames. |

## 8. Milestone steps

| Step | Content | Done when | Effort |
|---|---|---|---|
| **RT-1 Infrastructure** | "Emberlight RT" `FeatureSet` in `VulkanFeatureSetsMixin` (3 extensions, 3 features). `RtCapability` (+ `createDevice` HEAD inject). `VulkanBackendVmaMixin` (BDA flag). `UberGpuBufferRtMixin` + `VulkanConstMixin.bufferUsageToVk`. `rt/` package: `SectionTracker` (mixins on `RenderSection.vertexBufferUploadCallback`, `releaseSectionMesh`, `uploadTerrainBuffersToGpu` TAIL), `AccelPool`, `RtScene` (BLAS per section/layer, TLAS per frame, camera-relative), `RtDebugView` + `-Demrion.rt.debug`, F7 counters. Built only with `-Demrion.rt.debug` in this step (no pack changes). | Debug view matches the raster image (incl. cutout holes and section updates after break/place) at RD 16. Validation layer clean. RT-off screenshots MAE 0 vs. master. FPS with RT device setup within noise vs. `rt.device=false`. Real triangle count, BLAS memory and build times logged into this doc. | 5–7 days |
| **RT-2 Variants, patch table, UI** | `Converter.Request.rt`, `CacheKey`, manifest `rt` + `acceleration_structure` resource, `SpirvReflection` AS type, transpiler `#version 460` + extensions for patched stages, `RtPatchTable` + `RtPatcher` + `rt/emrion_rt.glsl` helpers. Mod: config keys, "Emberlight RT" page, key, reconvert + swap creating/destroying `RtScene`, `Descriptors` AS writes. | With `rt={shadows}` the target pack converts (253+ stages, `spirv-val` clean), and the report lists the patched stages. A deliberately broken table yields "unavailable" plus byte-identical off output. Toggling in game swaps runtimes 5× without VRAM growth. RT-off golden-hash test passes. | 4–5 days |
| **RT-3 Shadows** | Complementary shadow patches (3 functions), inverse shadow mapping, cone jitter, alpha test, skip ids, Hybrid/Full modes. | Comparison scenes (overview, forest, cave, sunset, entities, water) look right in both modes (contact shadows, coloured glass and caustics kept in Hybrid). Perf delta measured and documented in BENCHMARK.md. No stale shadows after block edits. | 4–6 days |
| **RT-4 Reflections** | (a) `voxelRayTrace` patch with pack shading. (b) `getFaceData`/`storeFaceData` patches, vertex-based face data, unreferenced-resource elimination for `bufferObject.0`/`wsr_img`, hits outside the voxel volume. | (a) Water and polished-block reflections show exact slab/stair/plant geometry with otherwise unchanged look. (b) VRAM drops by about 850 MB with RT reflections on, and reflections beyond the voxel volume work. | (a) 3–4 days, (b) 4–5 days |
| **RT-5 Polish + AO** | BLAS compaction, entity BLAS per frame (Full shadows with entities, entity reflections), `EmrionRtAoPass` + `GetAmbientOcclusion` patch, then a GI design. | Compaction halves BLAS memory. Entities cast RT shadows. RTAO replaces SSAO with no halos at depth edges, temporally stable. | 1–2 weeks |

Order rationale: RT-1 can be built and verified without touching the converter or the pack (debug view only). RT-2 can be
verified without any visible RT effect (patch plumbing, toggling, bit-identity). Each later step adds one effect behind its
own switch.

## 9. RT-1 results (2026-11-13)

### 9.1 What is built

| Piece | Where |
|---|---|
| "Emberlight RT" `FeatureSet` (3 extensions, `accelerationStructure`, `rayQuery`, `bufferDeviceAddress`), requested unless `-Demrion.rt.device=false` | `rt/RtCapability`, `VulkanFeatureSetsMixin` |
| `RtCapability.enabled` from a `createDevice` HEAD inject; VMA `BUFFER_DEVICE_ADDRESS` flag only then | `VulkanBackendRtMixin` |
| `Formats.USAGE_RT_INPUT` (1 << 12) on vertex heaps only, mapped to `SHADER_DEVICE_ADDRESS | AS_BUILD_INPUT_READ_ONLY` | `UberGpuBufferRtMixin`, `VulkanConstMixin.bufferUsageToVk` |
| Section events: `vertexBufferUploadCallback` HEAD (add), `releaseSectionMesh` HEAD (remove, any thread, queued), `uploadTerrainBuffersToGpu` TAIL (apply + build), `dispose` HEAD (drop all) | `RenderSectionRtMixin`, `SectionRenderDispatcherRtMixin` |
| BLAS per (section mesh, layer), budgeted batches, 64 MiB scratch ring; TLAS per frame in `FrameExecutor.levelStart`'s raw command buffer, camera-relative, plus the `EmrionRtSections` table (uvec2 vertex address, layer) | `rt/RtScene` |
| Debug view `-Demrion.rt.debug=true`: compute ray query from the camera (face normals faded by distance, cutout alpha test against the block atlas), copied over the final image | `rt/RtDebugView`, `glsl/rt_debug.comp` |
| Geometry check: raster | traced | raster with traced edges | `mod/tools/rt_overlay.py <raster-dir> <traced-dir> <out>` |
| F7 / `-Demrion.debug.timings`: "RT: BLAS builds", "RT: TLAS build", "RT: debug view"; with the debug switch a 5 s log line with counts, memory and build times | `Profiler` marks, `RtScene.log` |

Deviations from §2: no `AccelPool` (each BLAS is its own VMA buffer; VMA already sub-allocates them from its blocks, and 4.5 k
buffers caused no measurable cost), no separate `SectionTracker` class (the queue lives in `RtScene`), BLAS built with
`PREFER_FAST_TRACE` only (`ALLOW_COMPACTION` comes with compaction in RT-5), instance and section-table data are rewritten
per frame into a 3-slot host-visible ring (custom index = slot), and the config key `rt.device` is not read yet (only the
system property). An instance is only added while its mesh is the section's current mesh, so a half-uploaded re-mesh never
doubles geometry. The debug view has one mode (normals), not normals/instances/albedo.

**Finding for RT-3:** MC 26.3 compiles only sections that the frustum + occlusion graph has reached
(`LevelExtractor`: `sectionUpdateRenderStates` from `visibleSections()`). The TLAS therefore holds what was ever visible:
sections behind the camera at join, unseen caves and dirty sections outside the view keep no or stale meshes. For shadows
and reflections from off-screen geometry, RT-2/3 must schedule compiles for all sections within the RT distance (a mixin on
that loop), which also costs chunk-meshing time and vertex-heap memory.

### 9.2 Measured (RTX 4070 Ti, NVIDIA 615.78, 1080p, RD 16, scene world seed 2026)

| | Overview at load | Overview after a full turn (4 yaws, up, down) | Forest |
|---|---|---|---|
| BLAS (section × layer) | 1 623 | 4 450 | 1 223 |
| Triangles | 1.22 M | 5.04 M | 1.62 M |
| BLAS memory (uncompacted) | 84 MiB | 346 MiB | 110 MiB |
| Bytes per triangle | 72 | 71 | 71 |

- TLAS: 1.4 MiB at 8 192-instance capacity (0.7 MiB at 4 096), build **0.13 ms** GPU per frame (0.12–0.21). Scratch 64 MiB,
  TLAS scratch and instance/table rings ≈ 1 MiB. Total at the full turn ≈ 410 MiB.
- BLAS builds: ≈ 16 µs GPU per section BLAS on average (80 ms for 5 020 builds incl. re-meshes); worst single frame during
  the initial load 1.9 ms with the default budget of 2 M triangles per frame (`-Demrion.rt.blasBudget`), 1.35 ms at 100 k.
  MC's own upload rate spreads the load over many frames, so the budget rarely binds.
- The §2.4 estimate (10–20 M triangles, 0.6–1.8 GB) assumed every non-empty section is meshed. With MC's visibility-driven
  meshing the real set is 1–5 M triangles; forcing all sections (finding above) would move it towards the estimate.
- Debug view (one ray per pixel, alpha-tested candidates): 0.2–1.1 ms.

### 9.3 Done criteria

- **Geometry:** the traced image matches the raster image at RD 16 in overview, forest and cave, including cutout holes
  (leaves, glass, torches, fence). Block break/place test in the cave (hole in the wall, leaves/glass/stone/fence placed,
  six break/restore cycles, restore): every state matches the raster run, no stale geometry
  (`rt_overlay.py` panels).
- **Validation layer** (Steam's layer, BENCHMARK.md recipe) with the debug view and the edit test: no messages, nothing
  alive at exit (`RtScene.shutdown`, `RtDebugView.shutdown` on client stop).
- **RT off = master** (pinned `frameTimeCounter=100`, 8 scenes, MAE / SSIM):

| Scene | RT off vs master | `rt.device=false` vs master | master vs master (floor) | RT off vs Iris (pinned refs) |
|---|---|---|---|---|
| overview | 0.44 / 0.996 | 0.33 / 0.997 | 0.41 / 0.996 | 0.47 / 0.996 |
| forest | 0.46 / 0.994 | 0.62 / 0.992 | 0.55 / 0.994 | 0.67 / 0.993 |
| water | 0.49 / 0.993 | 0.62 / 0.989 | 0.57 / 0.991 | 0.78 / 0.985 |
| cave | 0.24 / 0.998 | 0.27 / 0.997 | 0.27 / 0.998 | 0.28 / 0.997 |
| sunset | 0.33 / 0.996 | 0.34 / 0.996 | 0.35 / 0.996 | 0.57 / 0.995 |
| night | 0.21 / 0.998 | 0.09 / 0.999 | 0.22 / 0.998 | 0.49 / 0.989 |
| rain | 0.91 / 0.980 | 0.74 / 0.984 | 0.89 / 0.981 | 0.98 / 0.979 |
| entities | 0.51 / 0.992 | 0.52 / 0.991 | 0.82 / 0.989 | 0.72 / 0.992 |

  All inside master's own run-to-run floor (rain 0.91 vs 0.89 and overview 0.44 vs 0.41 are within the ±0.05 spread).
- **FPS** (overview, 30 s, interleaved): RT-capable device, RT off 135.8 / 136.4 / 139.0 (mean **137.1**, 1 % low 111.1 /
  56.7* / 111.9) vs `-Demrion.rt.device=false` 137.6 / 139.6 / 135.5 (mean **137.6**, 1 % low 110.7 / 114.5 / 64.7*);
  *one hitch each. The difference is inside the ±2 FPS run-to-run spread, so the boot-time device setup stays on by default.

## 10. RT-2 and RT-3 results (2026-11-13)

### 10.1 What is built

| Piece | Where |
|---|---|
| `Converter.Request.rt` (`Set<String>`: `shadows`, `shadows:full`, `shadows:rays=N`). The cache key gets `rt=<sorted set>` plus a fingerprint of the RT tables and bodies **only when the set is non-empty**, so editing an RT body invalidates RT caches without a `VERSION` bump that would change the RT-off key | `Converter`, `CacheKey`, `RtPatchTable.fingerprint` |
| Patch table (our JSON, no pack code): detect files, per effect `requires`, `options` (emitted as `const emrRt_<effect>_<option>`), helper library, built-in uniforms declared only if missing, `needs`, foliage skip ids (only when the dimension's shadow pass discards them), patches by signature | `converter/src/main/resources/rt/complementary-r5.json` |
| `RtPatcher`: parses the preprocessed stages, matches `returnType name(paramTypes)`, checks `needs` (declared before the first patched function), replaces bodies and inserts the helpers on one line so `#line`/LineMap stay valid. All or nothing per effect: a dimension with no match stays untouched; a partial match or a missing symbol drops the effect (the pack object is returned unchanged, the reason goes to `manifest.rt.unavailable`) | `converter/glsl/RtPatcher` |
| Helpers: `emrRtVisibility` (ray query, TerminateOnFirstHit, mask 0x01, cutout candidates alpha-tested at mip 0 against the block atlas, vertex UVs fetched through `GL_EXT_buffer_reference_uvec2` from MC's heaps, max 32 candidates); `emrRtShadowOrigin` (exact inverse of `GetShadowPos`, so all of the pack's biases stay); cone around the shadow light from `shadowModelViewInverse`, jittered with the pack's `InterleavedGradientNoiseForShadows` (TAA accumulates) | `rt/emrion_rt.glsl`, `rt/complementary/shadows.glsl` |
| Transpiler: `#version 460` + `GL_EXT_ray_query` + buffer-reference extensions only for stages with the helper type; `accelerationStructureEXT` is an opaque uniform; buffer-reference blocks pass through verbatim. Planner: `emrRtTlas` → `acceleration_structure/emrion_tlas`, `EmrRtSections` → `storage_buffer/emrion_rt_sections`, `emrRtAtlas` → block atlas. `SpirvReflection` reports AS bindings | `GlslTranspiler`, `IrisDimensionPlanner`, FORMAT.md §10 |
| Config `config/emberlight.properties`: `rt.enabled`, `rt.shadows` (hybrid/full/off), `rt.shadowRays`, `rt.distance`, `rt.device` (now read at boot too); `-Demrion.rt.<key>` overrides | `rt/RtConfig`, `RtCapability` |
| "Emberlight RT..." button on the root of the shader options screen → our own page (master switch, shadow mode, rays, RT distance, status "unsupported GPU" / "unavailable: reason" / "active (hybrid)", BLAS summary). Key `key.emberlight.rt` (unbound) toggles `rt.enabled` with an action-bar message | `gui/RtOptionsScreen`, `EmrionShadersClient` |
| Swap: `PackManager.applyRt` reconverts only if the request changes, then the existing reload path swaps. `install` creates `RtScene` before the runtime's descriptor sets (and rebuilds chunks once so every mesh gets a BLAS); a non-RT install, deactivate, park or failure destroys it | `PackManager`, `RtScene.activate/shutdown` |
| `RtScene`: fixed TLAS capacity 65 536 instances (11 MiB; `-Demrion.rt.maxInstances`), one section-table buffer with 3 frame regions (custom index = region · capacity + slot), so the TLAS and table handles never change and the pre-built descriptor sets stay valid. `rt.distance` limits the instances and the forced compiles | `rt/RtScene` |
| Forced compilation: the shadow pass (default path, Iris/Sodium behaviour, merged to master separately) and RT (every section within the RT distance, every 8th frame) list sections; the next extraction compiles the dirty ones like MC's visible ones | `runtime/ForcedSections`, `LevelExtractorMixin` |
| Autotest step `rt off|on|hybrid|full`; scene files `mod/emrion-rt-scenes.txt`, `mod/emrion-shadow-scenes.txt` | `AutoTest` |

Deviations from §3–§5: the manifest `rt` section has no binding numbers (the bindings are ordinary resources, FORMAT.md §10);
the effect mode is a request option (`shadows:full`), not a separate effect; Hybrid multiplies RT visibility with **one
jittered** map tap (the pack's TAA turns its edge into a soft penumbra like its own filter) instead of a fixed single tap;
leaves (`leaves == true` in `SampleTAAFilteredShadow`) start their rays one block out, so a leaf block does not shadow
itself (the pack's map lets leaves through with its leaves depth offset too).

### 10.2 Converter

- Target pack with `rt={shadows}`: 265 stages, 0 errors, all `spirv-val --target-env vulkan1.2` clean. 28 stages patched
  (world0 and world1: gbuffers_basic, line, textured, terrain, block, block_translucent, entities, entities_translucent,
  lightning, entities_glowing, hand, water, hand_water, composite); world-1 has no live patch point (no shadows) and stays
  unpatched. Unpatched stages stay `#version 450`.
- RT off: the cache directory is byte-identical to master's for the target pack (`diff -r` of both converters' output, and
  the golden hash test `TargetPackRtTest.rtOffOutputMatchesGolden`). A deliberately broken table (an unknown `needs`
  symbol, or a function the pack does not have) returns the very same pack object and an `unavailable` reason
  (`brokenTableLeavesThePackUntouched`).

### 10.3 Measured (RTX 4070 Ti, 1080p, RD 16, 25 s, pinned scene world; RT off = with forced shadow sections)

| Scene | RT off | Hybrid | Full | GPU delta (Hybrid) |
|---|---|---|---|---|
| overview | 95.2 FPS | 88.2 | 89.3 | +0.84 ms: TLAS 0.15, gbuffers opaque 2.51 → 3.11 |
| sunset | 145.2 | 129.9 | 129.0 | +0.76 ms: TLAS 0.16, gbuffers opaque 2.14 → 2.82 |
| forest | 188.5 | 171.5 | 170.5 | +0.48 ms: TLAS 0.16, gbuffers opaque 1.48 → 1.66 |

- The render thread spends +0.4 ms recording (TLAS instance loop over ~15 k entries, the RT forced-section list); the frame
  stays GPU-bound.
- With every section of RD 16 forced: 15.0 k BLAS, 11.4 M triangles, 789 MiB BLAS (72 B/triangle); `RtScene` in total
  (BLAS, TLAS, rings, 64 MiB scratch) 844 MiB.
- The forced shadow sections themselves (default path, also with RT off) cost FPS: overview 133–137 → 95, sunset 168.6 →
  145.2, forest 218.2 → 188.5 (master vs. this change); a later pass follows up (BENCHMARK.md, "Shadow coverage without the FPS loss").

### 10.4 Done criteria

- **Images** (RT off | Hybrid | Full): forest, sunset, cave and sunset_back. Ground shadows match the map's, the penumbrae are
  soft and noise-free after TAA, there is no acne and no light leak in the cave. Hybrid adds crisp leaf-on-leaf shadows
  in the canopy; Full keeps the map's translucent colour and has no entity shadows (RT-5).
- **Block edits** (Full): a 4×4×4 stone block placed in the forest casts its shadow within 4 s of the command (a section
  rebuilds in one upload), and after removing it the image matches the one from before the edit (MAE 1.1, TAA noise).
- **5 toggles**: VMA 4752 MiB with RT, 3924 MiB without, the same on every cycle. `RtScene` (844 MiB) is freed completely.
  The first RT on also meshes every section in render distance (MC chunk heaps 672 → 1568 MiB). That memory is MC's and
  stays meshed after RT off; it does not grow further.
- **Validation layer** (Steam's, BENCHMARK.md recipe; confirmed loaded via `/proc/<pid>/maps`): no messages with RT on,
  through Full/off/Hybrid swaps and at exit.

### 10.5 Open for RT-4/RT-5

- RT-4 reflections: the `voxelRayTrace` patch with pack shading, then vertex-based face data so WSR's 810 MB SSBO can go.
  A second effect in the same stage must share `emrRtSkip` and the common library (marked `shortcut:` in `RtPatcher`).
- RT-5: BLAS compaction (about −50 %), RTAO. (Entity BLAS per frame: done, see "RT entities".)
- Waving leaves cast static RT shadows (the BLAS uses the unanimated vertices).
- The RT distance only limits instances and forced compiles; BLAS outside it are still built when MC meshes them.


## 11. RT-4 results (2026-11-13; in-game part pending)

### 11.1 What is built

| Piece | Where |
|---|---|
| Effect `reflections` (request `reflections`, config `rt.reflections`, default on with RT). Patches in `composite`: `voxelRayTrace` (closest TLAS hit, mask 0x01, cutout alpha test, up to 4 retries behind hits the pack rejects), `getFaceData` (face of the current hit), `getVoxelSpaceAO` (1.0). In `shadow` (vertex): `UpdateSceneVoxelMap` emptied (bare patch, no helpers, stage stays `#version 450`) | `rt/complementary-r5.json`, `rt/complementary/refl_*.glsl`, `reflections.glsl` |
| Face data from the hit vertex: interpolated Color and UV2 (/240, block light stored like the voxeliser), sprite half size from UV0 − mc_midTexCoord; texture bounds placed so the pack's own UV formula lands on the exact hit UV. The pack's `getShadedReflection`, fade and `DoFog` shade the hit unchanged | `refl_trace.glsl`, `reflections.glsl` |
| Steps (a) and (b) together: the pack's face data covers only full cubes the voxeliser stores, so RT hits on plants, fences and most slabs need the vertex data anyway | |
| Freed resources: an effect lists `frees`; after analysis the RT variant shrinks each listed `bufferObject.N` to 16 B and `image.X` to 1×1×1 if no analysed program of any dimension still uses it (else it is kept and logged). Target pack: `bufferObject.0` (810 MB), `wsr_img`, `wsr_lod_img` freed; bindings unchanged | `RtPatcher.free`, `DefaultConverter`, `BufferDecl.used` |
| `RtPatcher`: common library once per stage before its first declaration (shared by shadows and reflections in `composite`); per-effect helpers before the effect's first patched function; per-effect skip lists (`emrRt_<effect>_skip`); patches limited to `programs` (all of a program's patches or none), with their own `needs`, or `bare` | `RtPatcher`, `RtPatchTable` |
| RT page button *RT reflections* plus status line; autotest `rt refl`, `rt hybrid+refl`, `rt full+refl`; scenes `mod/emrion-rtrefl-scenes.txt` (sky pool with non-cube objects, obsidian floor, lake) | `RtOptionsScreen`, `RtConfig`, `AutoTest` |

Deviation from §4.2: no geometric hit outside the voxel volume is rejected (the TLAS has no such edge); the pack's
fade at the voxel volume's edge applies only to the ray start. Floodfill light at hits outside the volume reads the
clamped edge of the light volume.

### 11.2 Converter

- `rt={reflections}` and `rt={reflections,shadows}`: 276 stages, 0 errors, all `spirv-val --target-env vulkan1.2` clean;
  6 stages patched for reflections (composite + shadow.vsh in world0, world1, world-1). `composite` has exactly one common
  library with both effects (`TargetPackRtTest.rtReflectionsAndShadowsShareComposite`). RT off: golden hash unchanged.

### 11.3 Open

- In game: comparison images (RT off | reflections), FPS cost, VRAM drop, validation layer, 5 toggles.
- Entity and player reflections: WSR's player reflection (`WORLD_SPACE_PLAYER_REF`) is untouched; entities are not in the
  TLAS until RT-5.
## 12. RT refraction and water caustics (2026-11-13, in-game tuning open)

Two more effects in `complementary-r5.json`, each behind its own switch (`rt.refraction`, `rt.caustics`, default off; "RT
refraction" / "RT caustics" on the Emberlight RT page).

| Effect | Patch point | What the body does |
|---|---|---|
| `refraction` | `main()` and `DoLighting` in gbuffers_water, `DoRefraction` in composite1 | Since §33: gbuffers_water bends the view ray at each water fragment (apparent-depth image, IOR 1.333; glass a slab, 1.5), traces the hit and lights it with the pack's own `DoLighting` there, then writes the water over it as one opaque colour. composite1 only moves the depth behind the surface to the traced path. Before that (described below), composite1 shaded the hit from a ratio of screen taps. Motion vectors move refracted pixels with the virtual image. Total internal reflection is left to the pack's reflection. |
| `caustics` | `vec3 SampleShadow(vec3, float, float)` (every stage that includes `shadowSampling.glsl`, except composite1) | The pack's own body; only its translucent colour tap (`find`) is replaced. A receiver traces towards the light through mask 0x02 up to the water surface (block id 32000), which gives the real depth. The caustic is `1/|det dL/dx|` of the refraction map `L(x) = x + depth · r(x)` over the pack's own wave texture (`gaux4`, its four normal octaves lifted from its gbuffers_water; when the pack's water style has none, as Reimagined's default style 1, our own crossing sine swells stand in), so it focuses with depth, moves with the waves and needs no denoiser. The water's light colour is the mean of `shadowcolor0` on a ring of 1.5 blocks, which averages out the pack's baked pattern. It is computed once per receiver, cached across the filter's taps. Glass keeps the map's colour. |

- Converter: all four effects together (`shadows`, `reflections`, `refraction`, `caustics`) convert to 276 stages with 0
  errors. Refraction patches composite1 in world0/1/-1; caustics patches the same 28 stages as shadows (they share the common
  library). `TargetPackRtTest.rtRefractionAndCausticsConvert`.
- First in-game look (scene `pool_low` in `mod/emrion-refraction-scenes.txt`, RT off vs. refraction + caustics): the caustics
  show and follow the water depth, but they were too strong (now strength 0.6, cap 3). An earlier fallback relit hidden hits
  with our own lighting. It did not match the pack's exposure and showed as bright block-shaped patches, so it was removed.
  The run with both fixes, the `water` and `pool` scenes (the first run shot them before the RT chunk rebuild had finished;
  there is now a `warm` scene first), the FPS cost and the validation layer are still **open**. The game runs were paused.
- Second in-game pass (a build with every RT effect, shore, grazing, pool, under-water and pit scenes, each effect on its own vs. RT off):
  - Refraction showed bright streaks over distant water. composite1 reads the water's SSR reflection (`colortex8`) at the
    coordinate DoRefraction returns, subtracts it, and adds the combined reflection back. The pack's offset is a few pixels;
    ours can be far, so a pixel got the reflection of another point. Fix: the body swaps the hit pixel's `colortex8` for this
    pixel's in the colour and returns `texCoord`. With WSR off, both taps read the same cleared value and cancel out.
  - Near the render distance, refracted pixels showed as bright patches. The bend is sub-pixel there, so the refracted
    colour now fades out between 0.4 and 0.6 × `far`. In the fade band the pack's depths stay, so there is no fog seam.
  - Caustics: soft, depth-dependent pattern on the sand and pit walls, from above and under water, no artefacts seen.
  - Remaining: thin light lines at some step/pit edges where the refracted hit is hidden (unrefracted fallback).
- Shimmer band along foreground outlines. Player report: standing on a white platform 20 blocks
  above shallow water, looking down, a band of smeared, warped underwater image along the platform's edge, much worse while
  walking. Cause: from above, the bent ray is steeper, so its hit projects towards the camera, i.e. under the platform on
  screen. Those lookups fail (`emrRtRfOk`: not water, nothing behind), and the 5-step bisection then squeezed every lookup
  within one offset of the outline onto the outline's last water row. That stretched one pixel row into vertical streaks as
  tall as the offset (10–20 px at 1080p), and the row changed with every TAA jitter and camera move. Fix: before the
  bisection, march along the offset up to 3 offsets (8 steps + 4 refinement steps) for the first pixel that is not
  "this surface with nothing in front" (`emrRtRfSame`, no depth window), and scale the offset by `s / 3`. The image now
  compresses at most 1.5× towards an occluder and meets the unrefracted image at the outline continuously; open water
  farther than 3 offsets from any occluder is unchanged. The old bisection still handles depth edges behind the surface.
  - Test: scenes `shim_build` / `shim` in `mod/emrion-refraction-scenes.txt` (9×9 white concrete at water + 20 over a
    1–3-deep basin with sand, stone, steps, seagrass and a shore; camera at the north edge, pitch 55). Script per RT setting
    (`rtset enabled=false`, refraction only, and hybrid shadows + reflections + refraction + caustics + AO + entity shadows
    + block light 4 + GI medium): `rtset …; cmd tp @a 262.5 161 238.2 180 55; wait 8; shot X-still; motion 0 0 -0.15 0 0;`
    4 × `wait 0.15; shot X-fN`, `motion 0 0 0.15 0 0;` 4 × `wait 0.15; shot X-bN; motion off`. Still shots with
    `-Demrion.autotest.frameTimeCounter=100`, motion shots unpinned.
  - Result: the streak band is gone in the still and every mid-motion frame, with refraction only and with every effect on.
- Traced refraction (2026-11-14). Player video: looking down at a shallow riverbed, underwater blocks
  doubled or ghosted, smeared while turning, and still warped a little at screen edges and outlines; all of it gone with RT
  refraction off. Causes: the screen lookup at the hit's projection used the refracted copy at some pixels and the
  unrefracted one at their neighbours (whole offset or none), and DLSS moved the looked-up floor with the water surface's
  motion. Fix: the hit is shaded from the TLAS (table above). Its light comes from the screen around the pixel, not at the
  hit: a ratio of sums over the straight floor's 9 taps, so the floor's texture and light cancel. Things that did not
  work: a per-pixel fit of an added water colour (contrast) drew the unrefracted step edges as outlines; our own sun and
  RT shadow ray on the hit gave sharp shadows the pack's underwater light does not have; a per-channel ratio printed the
  complement of seagrass as magenta; filtered cutout texels pulled in the clear texels' colour.
  Motion vectors (`upscale_motion.comp`): with RT refraction active, a water pixel (composition mask 0.75) moves like the
  sagittal virtual image of what it shows, on the view ray at path-in-water x eta past the surface (eta = sin r / sin i:
  1/1.333 from above, 1.333 from below), the path from the depth behind over a flat floor; glass (0.45) at the depth behind,
  but with its surface when glass is not refracted (`refraction:glass=0`, always with the RTX style: the pack blends the
  glass over the straight background, and the glass's frame doubled while strafing). Total internal reflection keeps the
  surface's motion; under water it is tested against the face normal from the depth gradient (it was world up, so a
  waterfall's side seen from inside moved with its surface while it showed the wall behind it: the wall smeared into
  streaks under DLSS while moving). Rotation is depth independent; this only matters while moving.
  - Test: `turnshot` (docs/BENCHMARK.md) over the riverbed scene (`64.5 68 -260.5`, plank platform and pink glass added),
    pitch 45 (turn 2 and 6 degrees per frame), 60 (strafe 0.06), 30 (turn + strafe); camera under water (turn, strafe).
    Error = mean |turn - still| at the same pose, DLSS Quality, 2560x1386, reference config (RTX, GI high, Sodium).
  - Result (on / off / before): pitch 45 turn 2.66 / 1.85 / 3.29, fast turn 3.11 / 2.12 / 3.40, pitch 30 turn + strafe
    3.07 / 2.14 / 3.45, pitch 60 strafe 2.92 / 2.09. No doubled blocks or edge warp; what remains is on plant outlines and
    the sharper floor texture. Under water: 0.75-2.2, same as refraction off (0.83-2.0).
  - Cost (DLSS Quality + DLSS FG, 2560x1386, pitch 45 over the riverbed): 77 vs. 87 presented FPS, about 2 ms per frame.
  - Follow-up, player video: squids and fish twice, ghost step edges. Cause: a guard meant for surfaces missing from the TLAS
    kept a pixel unrefracted when the refracted hit was farther than the straight one. That is normal wherever the bent
    ray passes a squid or step edge that the straight ray hits, so those pixels showed the unrefracted squid next to the
    traced one. Now the straight ray itself is traced to check the TLAS has the surface. The light ratio prefers taps facing
    like the hit (a ratio across an unrefracted step edge printed it faintly) and skips entity taps (glow around squids).
    Test: squids, cod, salmon (NoAI) on the riverbed, pitch 65 and 60: one copy each, no ghost edges.
  - Follow-up, player screenshots: everything under water too shallow and too clear, the riverbed warped, magma glow a block
    off its block. Test scene: a gold block 4.89 blocks under clear water in a black
    concrete pool (plus magma, sea lantern, glowstone and a NoAI squid), camera at incidence 45 and 70 degrees, against RT
    refraction off and Iris. The geometry was right: the gold block's image rows match a Snell's-law projection within 2 px
    at both angles (apparent depth 0.63x at 45, 0.36x at 70 degrees on the vertical over the block; IOR 1/1.333 from above,
    normal up). What was wrong was the shading: the ratio screen / base colour treated the pack's water as a pure light
    factor, but the pack blends an additive water colour over the floor (alpha by path length and fresnel). Over the black
    floor that ratio was huge, so blocks glowed and the floor looked clear and purple; a dark hit (squid) lost the veil;
    taps on a bright block printed a black copy of it at its unrefracted place; magma taps lit the floor around the
    magma's unrefracted place (the offset glow), while the traced magma had no glow. Fixes: the floor and the hit are each
    put under the pack's water before the ratio (`emrRtRfWaterOver`: gbuffers_water's WATER_STYLE 3 alpha, BRIGHT_CAVE_WATER
    colour, biome tint and sky light from the TLAS water face, `WATER_FOG_MULT`; light under water tinted by the water), so
    fog and tint follow the refracted path and the hit's colour; taps are weighted by base-colour similarity to the hit
    and the ring widens (4, 12, 36 px) until similar ones are found; glowing blocks (RtLights grid) are left out of the
    taps, and a glowing hit takes the pack's own colour of that point from the straight view. The wave tilt is 5x weaker
    (0.02 x the pack's refraction intensity): it bent the floor by up to half a block. Remaining: a glowing block the
    straight view cannot see stays unlit; squids still look flat (entity atlas).
  - Follow-up, reported F9 snapshot (shallow shore with sand and stone steps, 417 62.7 653 on the test
    world): half-transparent, bent ghost blocks over the floor. Replayed one switch at a time: only RT refraction off
    removes them (reflections, caustics, DLSS and FG off do not). Cause: the light factor came from taps around this
    pixel, i.e. over the unrefracted floor, whose shading (step sides, AO, caustics) printed the unrefracted steps as a
    soft second set of blocks over the traced ones; the facing preference switching tap sets added blocky patches. Fix:
    the taps sit around the hit's own straight-view projection, weighted by their floor point's distance to the hit
    (`exp(-4 d²)`, d < 1 block, 5 and 13 px rings since the next follow-up) and facing like it, so the light is the hit's own; only where the straight view does not
    show the hit (hidden step faces) the old taps around the pixel are used.
    Result (snapshot replay, DLSS Quality + FG, 2560x1386): the steps show once, at their refracted place; the river
    scene at pitch 65 lost the dark ghost outlines of the steps, squids still single. Cost at the snapshot pose: 60.2 vs.
    61.8 rendered FPS (about 0.4 ms).
  - Follow-up, reported F9 snapshots (shallow beach at 766 63 418 and a 6-deep cold ocean at 705 67 427,
    test world): "distorted arcs in the water that move with my head" and "a magnifier / fisheye look"; the report accepts
    that water magnifies, but not like this, and other RTX games do not look like a fisheye. Not a bug in the sense of a
    wrong space or origin: the refracted image is world-locked under pure rotation (yaw 6 degrees, frame warped by the
    rotation, water MAE 5.8 RT refraction vs. 6.0 refraction off at the beach, 2.5 vs. 2.8 deep), and earlier tests had
    matched Snell's law within 2 px. The cause is Snell's law per pixel itself with a 70-104 degree perspective view: the
    bend grows with the incidence angle, so the apparent floor is compressed radially around the point below the camera.
    Straight floor edges become arcs centred on the player (a floor line perpendicular to the view bows 30 / 75 px at
    50 / 90 % of the half-width in the deep pose, 10 / 21 px at the beach, by tracing both models through a flat pool), the
    screen edges are magnified more than the centre, and since the arcs are centred on the player they keep their place
    on screen while turning and reshape while moving. Fix: water traces the apparent-depth image instead, the classic
    "real depth / 1.333": the view ray's component along the surface normal is scaled by 1/eta, i.e. the world behind
    the surface is scaled by eta along the normal. That is Snell's law for near-normal views, an affine copy of the
    floor (straight lines stay straight, the magnification is the same everywhere), and it moves rigidly. Total internal
    reflection still uses Snell's law (camera under water: the pack's reflection outside the window). The wave tilt is
    unchanged. `upscale_motion.comp` puts the virtual image at path-in-water x eta (was the sagittal Snell image).
    Grain (reported by the water-aura work): over refracted water a fine 2-3 px diagonal crosshatch that the pack's TAA
    and DLSS did not remove. The light ratio's taps were 4 and 12 px apart (all on one phase of the pack's 2x2 dither
    and TAA noise) and the centre tap was taken twice, so the hit got one screen pixel's noise. Now the rings are 5, 13
    and 37 px, the centre counts once, and each tap's screen colour is the mean of its 2x2 block.
    Test: grid pool (40x40, 5 deep, white lines every 4 blocks on black concrete, scenes world, camera 3.6 above the
    water at pitch 20/40/70), both snapshots with RT refraction on/off, yaw +3/+6, a 0.4-block strafe and a
    `turnshot 1 6`, plus Iris at the same spots (the beach from a fresh seed-2026 world: the server's beach differs a little).
    Result: the grid's lines stay straight and parallel at every pitch (before: barrel-shaped, outer columns splayed),
    the image is uniformly raised like real water; Iris shows no magnification, only the pack's wobble. The arc-shaped
    light seam in the near beach water (where the hit's projection left the screen) is gone. Turning
    (`turnshot 1 6` against the still at the end pose, DLSS Quality + FG): beach 2.75 before, 1.33 after (refraction
    off 0.96); deep 1.75 / 2.11 / 2.66 (noise level). Grain: high-pass RMS over the grid at pitch 40 6.9 before, 4.7
    after (refraction off 8.7 with the pack's caustics, Iris 3.5); a faint pattern of the pack's caustics remains.
    Cost (rendered FPS, refraction on / off, 2 x 10 s each): beach 52.9 / 58.4 after vs. 54.8 / 60.2 before, deep 34.5 /
    43.1 vs. 34.3 / 44.1, i.e. unchanged within run noise (refraction itself costs 1.7 ms at the beach and 6 ms over
    the deep water, where nearly every pixel is refracted).
## 13. RT AO (RT-5 part, 2026-11-13)

### 13.1 What is built

| Piece | Where |
|---|---|
| Effect `ao` (request `ao`, config `rt.ao`, default off; "RT ambient occlusion" on the RT page). Patches `float GetAmbientOcclusion(sampler2D,float,float,float,float,float,float,vec3)` in deferred1: it keeps the pack's `z0 < 0.56` hand cutoff and returns `pow(emrion_rtao, SSAO_I * 0.004)`, so the pack's AO strength slider still applies | `rt/complementary-r5.json`, `rt/complementary/ao*.glsl` |
| Option defaults from pack options: `"strength": "@SSAO_I"` takes the value of the pack's `SSAO_I` (an explicit request option still wins); an unknown pack option makes the effect unavailable | `RtPatcher.patchEffect` |
| Builtin `emrion_rtao` (sampled_image, nearest, clamp); the planner maps the helper's `emrRtAo` sampler to it | `IrisDimensionPlanner`, FORMAT.md §10 |
| `RtAoPass`, owned by `PackRuntime` (exists only if a program samples `emrion_rtao`). Recorded in the same command buffer right before the first frame command whose set 1 reads it (deferred1), after the opaque gbuffers: (1) `rt_ao.comp`: position and geometric normal from depthtex0 (no normal maps: they point below the face), N cosine-weighted rays of length R from just above the surface, mask 0x01, cutout candidates alpha-tested at mip 0, occlusion `(t/R)²` so hits fade towards the ray end; temporal accumulation (up to 32 frames) with reprojection through last frame's view-projection and camera delta, history rejected where the stored camera distance differs (disocclusion, screen edges). (2) `rt_ao_filter.comp`: 5×5 filter weighted by distance to the centre pixel's plane (no halos across depth edges) and history length; young history uses a 2-pixel step | `runtime/RtAoPass`, `glsl/rt_ao*.comp`, `rt_ao_common.glsl` |
| Memory: two RGBA16F history images + one R32F output at screen size (≈ 40 MiB at 1080p); released with the runtime | |
| Tuning switches: `-Demrion.rt.aoRays` (default 2), `-Demrion.rt.aoRadius` (blocks, default 2.0) | |

Cutout hits closer than 0.3 blocks are ignored: the pack waves grass and leaves in its vertex shader while the BLAS holds
the static blades, so a ray leaving a rasterized blade otherwise hit its own static copy and blades turned nearly black
(first run). Solid geometry is opaque in the BLAS and still gives full contact AO.

### 13.2 Verified so far

- Converter: `TargetPackRtTest.rtAoPatchesDeferred1` (deferred1 patched, strength 100 from the reference settings, builtin
  bound); RT-off golden hash unchanged; all RT tests green after merging RT-4's shared-library change.
- In game (RT on with only `ao`, pinned scenes `mod/emrion-rtao-scenes.txt`, vs RT off): no errors; MAE aoyard 4.8,
  forest 2.9, forest_floor 2.8, overview 2.1, cave 0.7, all darkening only. The on/off ratio shows contact AO at wall
  corners, fence and log bases, under the slab and the leaf block, cave corners and the lantern base; no halos, temporally
  stable after the settle time.

### 13.3 Open

- The 0.3-block cutout fix is confirmed in game: no black grass blade tips any more (near-black pixels in `aoyard` 12 850 → 7 037).
  The aoyard on/off pair from that session is not comparable (view moved by mouse input), so redo the pinned ratio there.
- Caves with every RT effect on (all RT effects, scenes cave, cave_dark, cave_tunnel): no round blotches seen in the RT AO
  images; the comparison against all RT without AO and RT off was not run yet.
- Cost: not measured yet (`overview`, interleaved RT off / `ao`, F7 marks "RT: AO trace" and "RT: AO filter").
- Not done: GI, a mode that combines RT AO with the pack's SSAO, half-resolution tracing.

### 13.4 Cave fixes (2026-11-13)

- **Black areas and wedges in lava caves came from RT AO only** (isolated in a build with every RT effect, block light off, one effect per
  run: hybrid shadows, GI medium, reflections all match RT off). Lava is in the solid BLAS. (a) The pack waves the lava
  surface (`DoWave_Lava`, up to ±0.025 blocks) in its vertex shader, so rays from a lava pixel started below the static BLAS
  surface: black triangular wedges. (b) A lava top face 1/9 block below a ceiling above it lies inside the AO hemisphere of
  that ceiling's underside, so whole undersides went black. Debug traces showed the occluder: same section, up-facing, off
  the block grid by about 0.11. Fix: `hitDistance` skips fluid hits (`mc_Entity.y == 1` in vertex word 8) and continues the
  ray (up to 4 passes). Lava glows and does not occlude ambient light.
- **Open cave floor:** the earlier −20 % (all-ao 15:46 vs all-noao 17:12) compared two different block-light builds.
  Re-shot in one session on the same code: AO leaves the floor of cave_dark unchanged (16.5 → 16.5 in the bottom quarter).
  A 2-block ray did darken a 3-wide tunnel everywhere by about 5 % compared with the pack's SSAO, so the default ray length
  is now 1 block (`aoRadius`). Corner contact AO stays.
- Results (mean brightness; RT off / all RT without AO / all RT with AO before / after; images in a scratch folder
  `rtfix/c7_*.jpg`): wg1 99.9 / 102.5 / 82.2 / 99.2, wg3 62.7 / 62.9 / 51.7 / 60.8, cave_tunnel 25.9 / 26.4 / 25.2 / 26.0,
  cave_dark 20.6 / 21.2 / 20.6 / 21.1.
- Screen-edge fix (4fe3707) confirmed: the 4 edge columns change by the same ratio as the inner columns, with no tint.
- Cost (overview, AO only): "RT: AO trace" 0.34 ms, "RT: AO filter" 0.14 ms.

## 14. RT entities (2026-11-13)

Entities, block entities and the player in the TLAS. Effects that ask for them (`<effect>:entities`) trace them; RT shadows
Full mode gets entity shadows with it.
## 15. RT block-light shadows (2026-11-13)

Torches, lanterns, lava, glowstone and every other light block cast ray-traced shadows. Without it the pack lights blocks
with the vanilla lightmap and its colored-light floodfill (`voxel_img` → shadowcomp → `GetLightVolume`). Both flow around corners and cast no shadows.

### What is built

| Piece | Where |
|---|---|
| Capture: while a feature frame is prepared, `RenderTypeFeatureRenderer$Group.getOrAddDraw` RETURN pairs each staged draw with its `PreparedRenderType`; `StagedVertexBuffer.uploadDrawsToBuffers` HEAD copies the CPU slices of every EMRION_ENTITY quad draw of a shadow-casting render type (`entity_*`, `armor_*`, `item_*`, `banner_pattern`, enchanted ones too: MC 26 draws the glint in the same pass; not emissive layers, `entity_shadow`) plus its `Sampler0` texture. The shadow pass's entity frame (every caster in shadow range, also behind the camera, the player in first person too) wins; without one the main world frame is used. MC's buffers stay untouched (no usage bits) | `RenderTypeGroupRtMixin`, `StagedVertexBufferMixin`, `StagedDrawAccessor`, `RtEntities.begin/onUpload` (hooks in `ShadowPass.prepareEntities`, `LevelRendererVertexMixin`) |
| Next `levelStart`, before the TLAS build in the same command buffer: vertices into a host-visible ring slot (3 slots, grown by doubling); every texture used is copied once into a 2048² RGBA8 **entity atlas** by a compute blit (shelf packing; textures over 1024 px or not RGBA8 are traced opaque; a full atlas starts over once per frame) and UV0 is remapped into it on the CPU, so the hit shader needs one sampler, no descriptor array. Block-atlas draws (falling blocks, items on the block atlas) keep their UVs | `RtEntities.prepare`, `glsl/rt_entity_blit.comp` |
| One BLAS per draw (key: render type + texture + n-th occurrence). Same vertex count as last frame → **refit** in place (`MODE_UPDATE`, `ALLOW_UPDATE`), at most 8 refits before a rebuild (`-Demrion.rt.entityRefits`); otherwise rebuild (storage reused if large enough). `PREFER_FAST_TRACE` (`-Demrion.rt.entityFastBuild=true` for fast build). `entity_solid*` and untested textures get `GEOMETRY_OPAQUE`. Budget 1 M triangles per frame (`-Demrion.rt.entityBudget`); draws over it are left out for that frame. BLAS of vanished draws are freed after 3 frames | `RtEntities` |
| TLAS: entity instances after the sections, **mask 0x04**, `TRIANGLE_FACING_CULL_DISABLE`, translation = capture camera − current camera. Section table entry `z` = 8 (UV0 in the entity atlas) or 9 (UV0 in the block atlas); terrain keeps 0–2 | `RtEntities.writeInstances`, `RtScene.buildTlas` |
| Shader side (common library, for every effect): `emrRtEntityAtlas` (builtin `emrion_rt_entity_atlas`, nearest/clamp), `emrRtIsEntity(ci)`, `emrRtHitAlpha(ci, v, tri, b)` (entity atlas or block atlas by kind). EMRION_ENTITY is 56 B with UV0 at words 4–5 like EMRION_BLOCK, so `emrRtVerts`/`emrRtTri`/`emrRtBaryUv` work unchanged; word 8 is the entity's Normal, so foliage skip ids apply to terrain only. Shadows: `emrRtVisibilityMask(..., mask)`, mask `0x05` with `shadows:entities` | `rt/emrion_rt.glsl`, `rt/complementary/shadows.glsl`, `IrisDimensionPlanner`, `PackRuntime` |
| Option: request `shadows:entities` (converter option `entities`, default 0), config `rt.shadowEntities` (default **true**), button "Entities cast RT shadows" on the Emberlight RT page with the status line "Entities: active (shadows)". `RtScene.setEntities` creates the entity objects only when an applied effect has `:entities` | `RtConfig`, `RtOptionsScreen`, `PackManager.install` |
| Autotest: `rt full+ent` / `hybrid+ent`; scenes `mod/emrion-rt-entity-scenes.txt` (`ent_mobs`: cow, sheep, horse, wither skeleton, armour stand with armour and sword, chest; `ent_herd`: 120 mobs and armour stands) | `AutoTest` |

Other effects add entities the same way: an `entities` option in their table entry, `<effect>:entities` in the request, mask
`| 0x04` and `emrRtHitAlpha` in their candidate loop (it handles terrain and entity hits alike).

### Measured (RTX 4070 Ti, 1080p, RD 16, pinned scene world, Full mode; FPS was unreliable on a busy machine, the GPU profile is the figure)

| `ent_herd` (120 mobs, 11 draws, 15.6 k triangles) | Full, no entities | Full + entities |
|---|---|---|
| Entity BLAS (refits, GPU) | – | 0.07 ms |
| TLAS build | 0.16 ms | 0.19 ms |
| gbuffers opaque (shadow rays) | 2.98 ms | 4.54 ms |
| CPU: capture (in the shadow pass) + prepare | – | prepare 0.45 ms |
| Memory | – | 1.0 MiB BLAS, 16 MiB atlas, ring 3 × 1 MiB, scratch 4 MiB |

- The first version spent 70 ms per frame on the CPU: it read the UVs back from the write-combined ring to remap them.
  The remap now reads the CPU copy.
- `ent_mobs` (6 mobs, 884 triangles): 11 draws, all refitted after the first frame.

### Done criteria

- **Images** (RT off | Full | Full + entities): `ent_mobs` and `ent_herd`. The entity shadows in Full + entities match the
  shadow map's (RT off) in place and shape and are sharper. The wither skeleton's shadow keeps the gaps between its legs
  (the alpha test reads the entity atlas). Full without entities has none, as RT-3 documented.
- **RT off unchanged:** `TargetPackRtTest.rtOffOutputMatchesGolden` passes. The capture hooks return at once without an
  `RtScene` with entities (no copy, no allocation).

### Limitations

- The entity data is **one frame old**: captured during frame N's shadow pass, traced from frame N+1's `levelStart` (the TLAS is
  built before the shadow pass runs). The transform corrects for camera movement, not for entity motion (about 9 cm at
  sprint speed and 60 FPS).
- One BLAS per draw spans every entity of that render type and texture (all cows in one BLAS). Fine for traversal at these
  counts. The RT distance does not limit entities; the shadow pass's own entity culling does.
- Atlas copies are cached per texture object. A texture whose contents change in place (a `DynamicTexture` re-upload) keeps its old copy until
  the atlas starts over. Textures over 1024 px are traced opaque.
- Hybrid + entities traces entities that the map already shadows: nearly the same image at extra cost. The option matters
  for Full mode.
| Light sources: while MC compiles a section, every block with `getLightEmission() > 0` that is not buried (some neighbour is not solid-render and emits less, so the inside of a lava lake or a glowstone wall is dropped) is stored per section (position, level, "full block"). Collected only while an `RtScene` exists | `rt/RtLights`, `SectionCompilerMixin` (compile HEAD/RETURN, `getBlockState` wrap) |
| Light grid, uploaded every frame at `levelStart` (cells recomputed only when the light set changed or the camera crossed a cell; header and camera-relative light positions every frame): 16 × 16 × 16 cells of 8 blocks (128 blocks on each axis around the camera). A light enters every cell its range (level blocks) reaches, at most 31 per cell (the strongest at the cell centre win), up to 4096 lights. Written into a host ring and copied into one device buffer (`EmrRtLights`, builtin `emrion_rt_lights`, 640 KiB), so one static descriptor serves every frame. A rebuild that took more than 4 ms (a lava sea: 16 k sources, ~1 M cell entries, 60-110 ms) makes the next one run on a worker (common pool) into a second set of lists that is swapped in when done; the frame keeps uploading the last complete lists meanwhile, so lights there appear a few frames late instead of every frame stalling. One rebuild at a time and none in the frame of a swap, so every list's remap maps the list uploaded before it; the GI change box does not count down while a rebuild is in flight. A recompile with the same lights does not trigger a rebuild | `RtLights.upload` from `RtScene.buildTlas` |
| Patch: a new `after` patch kind in `RtPatcher` inserts a body after a statement inside a function (matched by tokens, exactly once, else "unavailable"). The `blocklight` effect patches the pack's `DoLighting` after `blockLighting = mix(specialLighting, blockLighting, blocklightDecider);` with `blockLighting *= emrRtBlockLight(playerPos, worldGeoNormal, lightmap.x);`. Before the held light, `XLIGHT_I` and the caustics, and covering both the floodfill and the vanilla term | `complementary-r5.json` effect `blocklight`, `rt/complementary/blocklight.glsl`, `blocklight_apply.glsl` |
| Estimator: the pack's block light keeps its colours and falloff, and RT only removes light that a ray proves blocked. `P = (lightmap.x)³` is the weight of the pack's vanilla light level, `V = Σ wᵢ vᵢ` the visible and `B = Σ wᵢ (1 − vᵢ)` the blocked weight over the sources of the pixel's cell, with `wᵢ = ((level − max(L1 distance − 1, 0)) / 15)³` (the pack's steep lightmap curve; L1 because vanilla light falls one level per axis step, so `wᵢ` is on the same scale as `P`) and `vᵢ` = the visible share of the source, from alpha-tested shadow rays (mask 0x01, cutout alpha ≥ 0.1 at mip 0; lava blocks like any solid: it is in the solid BLAS, opaque geometry) to fixed points on a disk facing the pixel (radius 0.45 for full blocks/lava, 0.15 otherwise; see Sampling). The removed share is `min(max(P − V, 0), B) / P`: a source sealed in rock (lava behind a wall, glowstone under the floor) never lit the point in the pack, so it changes nothing, and light no known source explains (entities) stays. The ray stops where it enters the source's block, so the emitter's own model never shadows itself; a source behind the surface (geometric normal) counts as blocked. Result `1 − (1 − ambient) · removed`, faded to 1 between 32 and 56 blocks from the camera (§20); cells with no source or a full cell (31 sources, beside a lava lake: the sources that light the point may be missing) keep the pack's light | `blocklight.glsl` |
| Sampling: the `rays` strongest sources at the pixel (by `wᵢ`) are traced; weaker ones, and any under 1/16 of `P`, are not traced and count as possibly visible (`removed = min(max(P − V − U, 0), B) / P`, U = untraced weight), so the choice is the same every frame. Tracing stops once `V ≥ P` (nothing can be removed). Each traced source is a disk facing the pixel with **16 fixed sample points** (12 on a ring at 0.77 radius, 4 at 0.3): the 4 ring points 90° apart first, the other 12 only where those 4 disagree (penumbra). An edge that misses all 4 cuts off at most 2 ring points (error ≤ 1/8). Lit and fully shadowed pixels cost 4 rays per source, penumbra pixels 16. Nothing varies per frame or pixel. History: the first version drew sources stochastically from the weight CDF (flicker 0.311 vs 0.142 without RT block light, room scene, static camera, unpinned time, mean absolute frame-to-frame luma difference); then one ray per source to a point jittered by interleaved gradient noise rotated by `frameCounter` (0.153): the pack's TAA did not hide it, and a player report showed a grainy, cross-hatched band along every block-light shadow edge that changed every frame. Euclidean `wᵢ` broke pillar shadows in the middle: behind an occluder the vanilla flood takes a detour, `P` drops faster than the Euclidean weight of a farther visible source, and `V ≥ P` switched the shadow off part of the way out. | |
| Options: `rays` (default 4), `ambient` (0.15, the share of block light kept in full shadow: the floodfill's bounce stand-in). Config `rt.blockLightRays` (0 = off, default off), button "RT block light: OFF / 2 / 4 / 8 rays" and status line on the Emberlight RT page. Autotest `rt …+light` | `RtConfig`, `RtOptionsScreen`, `AutoTest` |
| Scene `mod/emrion-blocklight-scenes.txt`: cave (as in the RT scenes) and `room`: a stone-brick room with two pillars and torches behind them, a hanging and a standing lantern, a wall torch, a lava pool, iron bars and a fence | |

Deviations from the brief: **no ReSTIR reservoir reuse** (temporal or spatial). The lighting runs forward in the gbuffers, so
a pixel cannot read its neighbours' reservoirs for this frame. Exact enumeration of up to `rays` sources plus TAA already gives
a clean image in the test scenes, and the estimator is bounded in [ambient, 1], so it cannot produce fireflies. Reuse would
need history storage images and an extra pass; revisit if scenes with many sources in one cell look noisy.

**Fixed samples (2026-11-13).** Isolated in a closed torch-lit room (scene `dark` in a local test
folder: one torch, two cobblestone blocks): per-pixel frame-to-frame difference maps with RT block light vs GI vs AO,
each alone and all RT on, against RT off. Only block light varied, and only along its penumbrae; GI and AO were clean, static and
moving. After the change the block-light penumbra disappears from the difference map, and the flicker metric with block light
on equals RT off (dark: 0.173 vs 0.188, room: 0.213 vs 0.211; the metric is dominated by the torch flame and TAA, so these
numbers barely separate the variants and the difference maps are the evidence). Cost, room scene, block light only, 1080p:
gbuffers opaque 0.52 ms without, 1.51 ms jittered version, 3.25 ms fixed 16-point version (unfocused
game windows need `inactivityFpsLimit:"minimized"` in `mod/run/options.txt`, else MC drops to 30 FPS after a
while and the GPU clocks down).
## 16. RT global illumination (2026-11-13)

One bounce of indirect sun and sky light, traced from the gbuffer surfaces, replaces the pack's flat ambient term. Switch:
"RT global illumination: Off / Low / Medium / High" on the Emberlight RT page (`rt.gi`), request entries `gi`, `gi:rays=N`,
`gi:res=F` (`-Demrion.rt.giDebug=1|2` adds `gi:debug`: 1 = red where last frame's GI is found, blue where the pack's ambient
stays; 2 = GI only, ×4).

### Design: everything in the conversion, no mod pass code

The pack lights forward in the gbuffers (`DoLighting`), so the indirect term must exist before the gbuffers run. GI is
therefore computed after the opaque gbuffers of frame N and read by the gbuffers of frame N+1 through reprojection (one
frame of latency, invisible for static terrain; RTX GI now runs in prepare on the same frame, see §18.1). All of it is ordinary Iris pack content that `RtPatcher` adds to the RT
variant, so the existing planner, hazard tracking and descriptor code run it unchanged:

| Piece | What |
|---|---|
| Table features (additive, any effect can use them) | `patches[].find`: replace one token sequence (exactly once) inside a matched function instead of the whole body. `computes`: Emrion compute programs added to every patched dimension (`deferred_a`…; unavailable if the pack already has one of that name). `images`: `image.<name>` directives added to the properties (`$<option>` substituted). `dimensions`: limit the effect (RTX shafts: `world0`, §24). `library: false`: patched stages get only the effect helpers (the gbuffers sample images, no ray queries, stay `#version 450`). |
| `deferred_a` trace | Per texel (half or full resolution): opaque depth → player position, geometric normal from depth (per axis the smaller neighbour step), `emrRt_gi_rays` cosine-weighted rays (PCG per frame), closest alpha-tested hit (mask 0x01, cutout at mip 0). Hit shading from MC's vertex heap: atlas albedo (mip 2) × vertex tint, squared to linear; sun = `NdotL` × RT visibility towards `shadowLightPosition`; sky = albedo × smoothstep(hit sky lightmap). A solid face hit from behind returns black (no leaks through walls), cutout faces are two-sided. Misses count as sky visibility. Outputs: A = (sun bounce rgb, sky visibility), B = (sky bounce rgb), G = (normal, distance). |
| `deferred_b` temporal | Reprojects with `gbufferPreviousModelView/Projection` and the camera delta, bilinear over last frame's history where the stored distance (±3 %) and normal (dot > 0.9) match; history length up to `emrRt_gi_frames` (24). |
| `deferred_c/d/e` spatial | Edge-aware à-trous, 5×5 B3 kernel, steps 1/2/4: normal power 32 and distance to the centre's plane; young history gets a wider tolerance. `deferred_c` also copies the temporal result and geometry into the history images. |
| gbuffers patch | `vec3 sceneLighting = lightColorM * shadowLightMult + ambientColorM * ambientMult;` → `… + emrRtGi(ambientColorM * ambientMult, lightColorM, ambientColorM, playerPos, worldGeoNormal)`. `emrRtGi` reprojects the fragment into last frame's GI (bilinear, distance/normal checked) and returns `sqrt(ambient² · (sky visibility + sky bounce) + light² · sun bounce)`: the pack squares `sceneLighting`, so its ambient lives in sqrt space, and open sky gives exactly the pack's ambient colour. Where nothing matches (disocclusion, entities, hand, water surface) the pack's own ambient stays. |
| Images | 10 × RGBA16F at the trace resolution (raw, temporal, history, filter ping-pong, geometry ×2): 41 MiB at 1080p half res. |

The pack's direct light, shadows, block light, colours and fog are untouched; only the ambient changes. Ray Reconstruction
(M8) can take the trace output directly: the raw A/B images are the noisy signal, G holds normal and distance, and motion
is camera-only (terrain), so the temporal and à-trous passes are the parts RR would replace.

## 17. RT on the Sodium path (2026-11-13)

Every RT effect now works with Sodium installed (SODIUM.md step 5). `RtConfig.SODIUM` and the "Sodium path not supported yet"
messages are gone. MC's heaps are empty under Sodium, so the BLAS source is Sodium's region arenas:

| Piece | Where |
|---|---|
| Arena buffers get `USAGE_RT_INPUT` at creation when the device is RT-capable (all of them: Sodium reuses freed buffers between geometry and index arenas) | `ArenaAggregatorRtMixin` (`getBufferOfSizeAtLeast` → `createBuffer`) |
| Sync every frame at `levelStart`, before the TLAS build (Sodium's uploads and defragmentation run earlier, in the extract phase): walk the loaded regions; per (section, pass) an upload counter (Sodium frees the old segment before it allocates the new one, so a same-size upload can get the same `BufferSegment` object back) or a different segment = rebuild, none = drop. A region pass whose storage version (bumped on upload, removal, defragmentation move, buffer resize) and buffer are unchanged keeps its entries without the per-section scan (overview, RTX: sync 0.33 → 0.15 ms CPU per frame). Regions Sodium unloaded drop their BLAS | `compat/sodium/SodiumRt.sync`, `SectionRenderDataStorageAccessor`, `SectionRenderDataStorageMixin` |
| Build: a compute pass decodes the 3×20-bit positions of `EmrionChunkVertex` (40 B) into a 45 MiB float scratch buffer (`rt_sodium_positions.comp`, the same decode as the terrain_sodium variant); the BLAS is then built from it with `RtQuadIndices`, through the same `RtScene.buildPending` (budget, scratch ring, `GEOMETRY_OPAQUE` for SOLID). The BLAS does not keep the input, so the decoded positions are temporary | `SodiumRt.build` |
| Hit shading reads the arena in place: the section table entry's address is recomputed every frame from the segment's current offset (defragmentation moves it), `w` = 1 marks the format | `SodiumRt.sync`, `RtScene.instance` |
| Shader side: every effect reads vertices through format-aware accessors in the common library (`emrRtPos/Uv/Id/Color/Light/Normal/MidUv`; `EmrRtVerts` is now a struct of pointer + format). The Sodium light bytes are raw 0–240 like UV2, the Sodium UV ignores the centroid bias bit (< 1/32768) | `rt/emrion_rt.glsl`, `reflections`, `refraction`, `caustics`, `gi_common`, `rt_ao.comp` |
| RT block light: emitters collected in Sodium's meshing task (`LevelSlice.getBlockState(III)` wrap, HEAD/RETURN of `execute`; a cancelled task changes nothing) | `ChunkBuilderMeshingTaskMixin`, `RtLights.block(…, BlockGetter)` |
| Section set: whatever Sodium has built (its occlusion flood fill, frustum or not), so no forced compiles (`wantSections` runs only on the vanilla path) | `RtScene.buildTlas` |

Verified (pinned scene world, 12 scenes = the 8 shared ones + pool, polished, room, room_side; every effect on: shadows
full/hybrid, reflections, refraction, caustics, AO, block light 4 rays, GI medium): no errors. Sodium against vanilla with the
same RT settings, MAE / SSIM: full mode 0.21–1.25 / ≥ 0.962 (largest: room_side 1.25, only the lava animation; forest,
rain, water ≈ 1.1–1.15), hybrid 0.20–1.44 / ≥ 0.964, RT off 0.22–0.88 (the same pair's run-to-run level). That RT
actually applies on Sodium: Sodium full against Sodium RT off differs by 0.18 (night) to 8.4 (forest). Overview after the full run: 12 645 BLAS,
10.0 M triangles, 691 MiB on Sodium against 15 119 / 11.4 M / 793 MiB on the vanilla path with forced sections.
Speed (overview, full shadows + reflections + AO + GI medium, 25 s, V S V S): vanilla 100.0 / 97.4 FPS, Sodium 93.8 / 94.4
FPS (−4.5 %, better 1 % lows 84–85 against 78–84). The RT cost itself is the same (TLAS 0.16 against 0.18 ms; the BLAS
builds happen at load only); the gap is the Sodium path's known opaque-pass cost (SODIUM.md §8: opaque 3.9 against
3.4 ms, shadow opaque 1.05 against 0.8 ms).
The converter output is unchanged (RT-off golden hash, all `TargetPackRtTest` cases pass): only the RT helper library changed.

Limits: the decoded positions are a copy per build (45 MiB scratch caps one batch at about 2 M triangles, the BLAS budget);
a section whose pass changed format (pack turned off: Sodium remeshes in its compact format) is never read, `SodiumRt` warns
once and builds nothing until the pack format is back.

## 18. RT style RTX (2026-11-14)

Player feedback on the RT on/off video: reflections barely differ, and lights, shadows and light through coloured glass are
missing ("a bit like Bedrock RTX"). Every RT effect so far only makes the pack's own effects more accurate. A second RT style
now lets RT change the lighting itself, while Complementary's sky, water, fog, clouds and colour grading stay.

**Switch:** "RT style: RTX | Faithful" on the Emberlight RT page, config `rt.style` (default `rtx`). Faithful is exactly the
previous conversion. RTX adds `:rtx` to the shadow, block-light and GI requests, the effects `materials` and `shafts`, and
`refraction:glass=0`; it always has RT block light (4 rays when its switch is off), GI (at least Low) and RT entity shadows.
Tuning without a rebuild: `rt.extra` (or autotest `rtset extra=…`) appends request entries, `+`-separated, e.g.
`blocklight:strength=2+gi:debug=2`.

**Mechanism:** a table effect may list `variants`; a request option that names one (`shadows:rtx`) merges it into the effect
(options, uniforms, needs and images added, helpers appended, patches and computes replaced; `RtPatchTable.Effect.with`).
Without the option nothing of it is emitted, so Faithful output is byte-identical to before (checked with `diff -r` of the
full reference request against master's converter: only `manifest.json`'s cache key differs, because the table fingerprint
changed; `TargetPackRtTest.rtxStyleConvertsAndFaithfulHasNoRtxCode`). RTX code lives in `rt/complementary/rtx/`.

| Feature | Where | What |
|---|---|---|
| Coloured sunlight through glass | `shadows` variant `rtx` (`rtx/shadows.glsl`) | Shadow rays also see translucent terrain (mask 0x02; translucent BLAS geometry is `NO_DUPLICATE_ANY_HIT`, so each face tints once) and multiply the colour of every face they pass: the texel's hue (normalised to its brightest channel, `saturation` 1.5) weighted by alpha × `glass` (1.6). Water is skipped; its colour and caustics come from the map, which is used only where no glass coloured the ray. Sun cone `sun` = 0.015 rad (penumbra hard at the contact, wider with distance; 0.03 made sun patches in caves mushy, a block of penumbra at 16 blocks), at least `minRays` = 4 stratified rays per pixel, accumulated by the pack's TAA. Entity shadows always on. |
| Block lights as real lights | `blocklight` variant `rtx` (`rtx/blocklight.glsl`), `RtLights`, `LightColors` | Builds on the pack's block light (vanilla light plus coloured floodfill, so its colours and brightness stay). Per channel, the factor is S / U × `strength` (1.8), clamped to [`ambient` 0.15, `strength`]. U is the light the known sources give without shadows or facing. S is the same with wrapped N·L, shadows (the `rays` strongest sources, 8 fixed ring points at `size` 1.6 × the source radius, 4 first and 4 more only in penumbrae; no noise) and the colour of glass it passes (hue only). Source colours come from a table by block name (dyed candles by dye), stored as RGB555 in the section light list and uploaded after the 4096 light positions, squared because the pack adds block light before a square root. Light blocks, cells without sources and the translucent programs (glass, water) keep the pack's light. |
| Block-light GI, glass in GI, more bounces | `gi` variant `rtx` (`rtx/gi_*.csh`, `rtx/gi_common.glsl`, `rtx/gi_apply.glsl`) | A third channel C (4 more RGBA16F images) carries bounced block light. GI rays pass glass, tinted (up to 4 glass hits per ray, applied only in front of the closest opaque hit). The hit is lit by one block light chosen by weight, with a shadow ray (nothing on a light block's own face, §37), and adds last frame's GI at that point (history reprojection, × `bounce` 0.85), which gives multiple bounces. Sky seen through glass goes into B. The gbuffers add C × `10 · lum(blocklightCol) · blockStrength` (4) to `blockLighting`. Same temporal and à-trous filter as Faithful GI. |
| Material table | effect `materials` (table in `rtx/materials_table.glsl`; `rtx/materials.glsl` after the specular read in `GetCustomMaterials`, `rtx/materials_integrated.glsl` before gbuffers_terrain's `DoLighting` call) | With a specular read (`RP_MODE` 2 and 3) only where the specular map is empty (no labPBR resource pack). In integrated PBR (the default) and with PBR off, only the smoothness and only where the pack's own material code left the surface with no smoothness at all, so the pack's integrated materials (its iron, gold, quartz...) stay as they are. A table by pack block id gives perceptual smoothness and F0 / metal id: iron 0.65 (labPBR 230), gold 0.75 (231), copper 0.62 (234), netherite 0.45 as a dark albedo metal (255), so metals reflect in their own colour; obsidian 0.82, ice 0.92, diamond/emerald 0.85, glazed terracotta 0.75, amethyst/lapis/redstone 0.7, quartz 0.6, polished granite and diorite 0.58 (the pack ids of polished andesite, deepslate, blackstone and tuff also hold bricks, mud and the plain stones, so they stay rough). All dielectrics use F0 0.04. The pack's own PBR reflection and highlight code does the rest, and a labPBR pack's maps always win. Concrete and smooth stone are left out (barely visible, a full-screen reflection pass in the glass room: +3 ms). |
| Light shafts | effect `shafts` (composite1) | `GetVolumetricLight` × `strength` 2.5. The pack's shafts already follow the shadow map and take the glass colour from `shadowcolor1`, and the map is exact at this range, so an RT ray per volume step (20+ per pixel) would cost several ms for no visible gain. Not RT-traced. |
| No glass refraction | request `refraction:glass=0` | RT refraction bent the view through stained glass by its normal map: jagged spikes over whatever is behind. Water still refracts. |

**Quality presets:** the existing switches. GI Low/Medium/High (half res 1 ray / half res 2 rays / full res 2 rays) and RT
block light 2/4/8 rays. "RTX balanced" = GI Low + block light 2 (the last column below).

**Scenes:** `mod/emrion-rtx-scenes.txt` (built in the sky of the scene world): `glass` / `glass_win` (white room, eight
stained-glass windows, morning sun), `dark` / `dark_back` / `dark_box` (closed deepslate room lit by lava, glowstone, lanterns,
a sea lantern, a shroomlight, a redstone torch and a glowstone in a magenta glass box; pillar and iron bars as occluders),
`hall` / `hall_floor` (metal, quartz, ice, glazed terracotta pillars on polished floors, glass wall), `street` (rainy brick
street at dusk with lantern posts and lit windows behind coloured glass). Script: `rtset enabled=true,style=faithful|rtx`, then
per scene `cmd time set …; cmd weather …; cmd tp @a …; wait 12; shot …`.

**Measured** (RTX 4070 Ti, RD 16, the reference pack and settings, all other RT effects as in the reference config: hybrid shadows,
reflections, refraction, caustics, AO, block light 4 rays, GI High; pinned `frameTimeCounter`; 12 s per bench). 2560×1440 with
FSR Quality (render ≈ 1707×960):

| Scene | RT off | Faithful | RTX | RTX vs Faithful, GPU ms by pass |
|---|---|---|---|---|
| glass | 155 FPS (GPU 6.3 ms) | 110 (9.0) | 100 (9.9) | GI computes 1.60 → 2.61 |
| glass_win | 204 (4.8) | 121 (7.9) | 110 (9.0) | GI 1.66 → 2.73 |
| dark | 172 (5.7) | 98 (10.2) | 65 (15.4) | gbuffers opaque 3.49 → 6.64 (block-light rays), composite 0.09 → 1.29 (material reflections on the polished floor), GI 1.64 → 2.64 |
| dark_back | 206 (4.7) | 107 (8.6) | 67 (14.6) | opaque 2.53 → 6.56, composite 0.08 → 1.28 |
| dark_box | 193 (5.1) | 38* (8.9) | 70 (14.2) | opaque 2.42 → 5.48 |

\*CPU-side hitch during that bench; the GPU time is the reliable figure. TLAS build 0.19 ms in every RT column. At native
2560×1440 (an earlier build with 16 penumbra points and concrete in the material table): hall 128 / 85 / 72 FPS, street
134 / – / 52; RTX with GI Low: hall 89, street 57, glass 63. GI High is the largest single cost of RTX (full-resolution trace +
3 à-trous passes with the third channel: 2.6 ms at FSR Quality); GI Low takes it to ≈ 0.7–1.3 ms. With frame generation on top,
every scene above presents over 120 FPS.

**Stability** (mean absolute luma difference between two still frames 0.1 s apart, 0–255, 1440p FSR; RT off / Faithful / RTX):
dark 0.31 / 0.23 / 0.23, dark_back 0.32 / 0.27 / 0.22, dark_box 0.32 / 0.19 / 0.23, glass 0.23 / 0.36 / 0.39, glass_win
0.29 / 0.37 / 0.36. Block light is deterministic (fixed ring points), so the dark scenes are as stable as RT off. The glass
patches add a little TAA-level variation inside the coloured light (the jittered sun rays sample the glass texture at different
points); the difference map is ×20 amplified ≤ 2 grey levels and shows no edge crawl.

**Not done / limits:** RTX materials only in labPBR mode without a labPBR pack (IPBR keeps the pack's own table); light shafts
are only strengthened, not traced; wet-surface gloss in rain is the pack's own puddle code (`RAIN_PUDDLES`), unchanged; light
colours come from a name table, not from the emission texture; translucent surfaces (glass, water) keep the pack's block light.

### 18.1 Fixes after the first player test (2026-11-14)

* **Dark band along the leading screen edge while turning** (RTX): the gbuffers read last frame's GI, so the strip that
  comes into view each frame had no history. The sky/sun part fell back to the pack's ambient, but the bounced block light
  C was simply missing, so that strip was darker for one frame, every frame, for as long as the camera turned.
  `rtx/gi_apply.glsl` now takes the nearest history on a surface facing the same way (rings of 8 taps at 2–32 texels
  around the reprojected point clamped to the screen, distance within 50 cm + 25 %); only where nothing matches does the
  pack's ambient stay. With `gi:debug=1` a 240°/s turn showed a blue (no history) strip at the leading edge before and none
  after. Faithful GI is unchanged (it adds no block light, the ambient fallback is continuous).
* **Mushy, blotchy strip along the leading edge while turning** (RTX): texels entering the screen
  start a new GI history with 1–2 rays, and the à-trous filter (wider tolerance for young history) smears that into
  blotches until about 4 frames have accumulated. `rtx/gi_trace.csh` now looks up last frame's history length at the
  reprojected point (same test as the temporal pass) and traces `gi:boost` (4) times the rays where there is none, twice
  where it is under 4 frames; `rtx/gi_temporal.csh` weights the new sample by its ray count (B.a = weight in frames).
  Only the entering strip pays the extra rays. `gi:debug=3` shows history length (green = full, red = the ring fallback
  from §18.1). Glowstone room, 4°/frame turn, mean abs RGB error against the still frame at the end pose, rightmost 4 %
  of the screen: 4.39 → 3.07 (2°/frame: 3.92 → 2.18); the centre (4.4–4.8) is unchanged, the DLSS/TAA motion level.
* **GI of this frame, before the gbuffers** (RTX): the gbuffers still read last frame's GI, so
  every strip turning into view (left, right, up, down) borrowed a neighbour's history for a frame and showed faint seams. A
  deferred correction cannot fix it: the pack mixes the ambient non-linearly (`(sun + ambient)²` under a sqrt, vanilla AO
  from `dot(sceneLighting)`), so GI has to exist before `DoLighting`. The RTX GI passes now run in `prepare_a`…`prepare_e`
  (after the shadow pass and the entity TLAS refresh, before the gbuffers) and find this frame's surfaces without depth:
  `emrGiPrimary` traces the view ray of each GI texel against the TLAS (solid + cutout terrain and entities, alpha at mip
  0, face normal from the triangle; entity hits inside the first-person player's box are skipped, the body is in the TLAS
  for its shadow but not drawn). Trace, temporal and à-trous are otherwise unchanged; `rtx/gi_apply.glsl` looks the
  fragment up with this frame's matrices, no reprojection. The ring fallback stays for what the view ray does not see as
  raster does (water surfaces, an entity over the BLAS budget, mip-dependent cutout edges). Faithful GI is unchanged
  (deferred, depth, last frame). A guard band beyond the screen edges (`gi:wide`, tried first) is no longer needed and was
  removed.
  Measured (reference config, Sodium; glowstone room with a water pool and four mobs at night, and a daylight forest edge with
  six mobs; 15-frame turns ending at the pose of a still: yaw ±4°/frame, pitch ±3°/frame; `turnshot` takes `YAW:PITCH`).
  Low-frequency error (σ 6 px) of the leading 4 % strip against the still, at 2560×1386, current build → this:
  room right 1.17 → 1.34, left 1.13 → 0.98, down 1.72 → 1.15, up 2.10 → 0.69 (centre 2.0–3.5); forest right 1.12 → 1.02,
  left 1.28 → 0.79, up 0.58 → 0.34, down 2.30 → 1.95, of which the strip without a flapping chicken is 1.00 (centre 0.9).
  Every edge is at or below the centre level, the centre being the DLSS motion level. Stills against the current build
  are at the noise floor (room 0.41 mean abs, the build against itself 0.30–0.34). Frame time (median of three 15 s runs):
  room 10.97 → 10.53 ms, forest 12.08 → 11.51 ms; the view rays cost less than the guard band they replace.
* **Enchanted armour, elytra and items** looked flat, smeared or vanished (RT on and off): MC 26 draws base and glint in
  one pass (`*_glint` pipelines) with the glint's scrolling matrix as `TextureMat`, and packs apply `gl_TextureMatrix[0]`
  to the base texture. With a pack those draws now get the identity matrix and the glint is drawn again through
  `gbuffers_armor_glint` (`pipeline/GlintPass`, as Iris splits it), depth test EQUAL. They also cast RT entity shadows now.
  Follow-up: enchanted items were invisible, because `ITEM_CUTOUT_GLINT(_SPECIAL)` are registered as
  `pipeline/item_cutout`, so the name test missed them and their base drew with the glint matrix; fused pipelines are now
  found by the `GLINT` shader define. The less-or-equal test was a forward-Z op on MC's reverse-Z depth: the glint showed
  through walls and never on its own surface. EQUAL works once the converter declares `invariant gl_Position` (the pack's
  entity and glint programs are separate modules; without it EQUAL dropped most armour glint). `GLINT_SPECIAL` items
  (own foil UVs, another vertex format) get no second glint draw.
* **Dark halo trailing moving mobs, also in reflections**: the TLAS is built at `levelStart`, before the shadow pass
  captures the entity poses of this frame, so RT entity shadows, AO, GI occlusion and RT reflections showed every entity
  where it was one frame earlier. With a shadow pass the entity BLAS and a second TLAS build (sections unchanged, ≈ 0.2 ms)
  now run right after it (`RtScene.refreshEntities`); without one (no shadow capture last frame) entities are added at
  `levelStart` as before.


## 19. RT performance pass (2026-11-14)

Less work where it can't be seen, same image near the player. Each distance is an option in `rt/complementary-r5.json`
(0 = off).

| Change | Where |
|---|---|
| Block light: the strongest sources are picked in one pass (before: one pass over the cell per traced source) | `blocklight.glsl`, `rtx/blocklight.glsl` |
| Block light: past `blocklight:refineDist` (32 blocks) only the first 4 disk points decide, no penumbra refinement | same |
| Sun shadows, RTX: past `shadows:farDist` (32 blocks) half the rays on the TAA path | `rtx/shadow_taa.glsl` |
| Sun shadows, RTX: leaf receivers trace two rays on the TAA path (`shadows:leafRays`, 0 = all); forest gbuffers opaque about −0.5 ms, stills unchanged (one ray speckled the canopy) | same |
| Sun shadows, faithful hybrid: rays only where the map tap lets light through (the result is multiplied by it anyway) | `shadows.glsl`, `shadow_basic.glsl` |
| GI and AO: half the rays past 48 blocks (`gi:farDist`, `FAR_DIST` in `rt_ao.comp`); the history absorbs the noise | `gi_trace.csh`, `rtx/gi_trace.csh`, `rt_ao.comp` |
| GI filter step 1 and the AO filter read their taps from a block in shared memory (steps 2 and 4 measured slower that way and read directly) | `gi_atrous.glsl`, `rtx/gi_atrous.glsl`, `rt_ao_filter.comp` |
| No rays in helper lanes (they only feed derivatives, and nothing after the lighting takes one) | shadows, block light |

`-Demrion.rt.tlasFastTrace=true` builds the per-frame TLAS for trace speed instead of build speed. Mixed result (overview
+2 FPS, cave and room −7), so the default stays.

Measured at 2560×1440, RD 16, every RT effect on (block light 4 rays, GI high), 10 s per view, master / perf/rt:

| View | RTX FPS | RTX GPU ms | Faithful FPS |
|---|---|---|---|
| overview | 35.9 → 46.4 | 27.7 → 21.4 | 52.2 → 62.7 |
| water | 34.7 → 44.3 | 28.7 → 22.4 | 52.8 → 55.2 |
| forest | 57.9 → 58.2 | 17.1 → 17.1 | 72.9 → 78.4 |
| cave | 57.5 → 63.0 | 17.3 → 15.8 | 97.6 → 108.2 |
| room | 48.9 → 55.3 | 20.3 → 17.9 | 72.3 → 77.8 |
| ent_herd (120 mobs) | 38.0 → 41.1 | 26.2 → 24.2 | 57.8 → 57.8 |

The biggest single drops (RTX overview): gbuffers opaque 13.3 → 8.5 ms, GI trace 2.8 → 1.65 ms. Images against master are
inside master's own run-to-run difference in every view; RTX overview differs only by zero-mean noise on terrain beyond
32 blocks.

## 20. RT block light: no pop-in at distance (2026-11-14)

Flying towards a lit building, its block lighting jumped twice in RTX style (facade luma 101 → 92 → 78 between two 1-block
steps at 60 and 52 blocks). The light grid follows the camera in whole cells, and RT block light (both styles) and RTX GI block light
were faded over the outer cell of the grid: at each cell crossing the grid edge, and with it the fade, moved 8 blocks at once.
The grid was also only 64 blocks tall.

| Change | Where |
|---|---|
| Fade by distance to the camera, smoothstep over 32–56 blocks (`emrRtLightFade`), not by the grid edge; 56 blocks always lies inside the grid | `blocklight.glsl`, `rtx/blocklight.glsl`, `rtx/gi_common.glsl` |
| Grid 16 × 16 × 16 cells (was 16 × 8 × 16), so the vertical range matches; cells copied to the ring in bulk | `RtLights` |
| RTX: past the fade the factor is `blocklight:far` (1.0 = the pack's light, as before outside the grid); cells without sources fade to it too | `rtx/blocklight.glsl`, `complementary-r5.json` |

Measured (2560×1440, scene: stone-brick building with sea lanterns behind glass and wall torches, camera moved 1 block per
shot from 150 to 11 blocks away, 139 shots; largest frame-to-frame change of the facade's mean luma): RTX 15.6 % → 2.7 %,
Faithful 1.5 %, RT off 1.9 % (the remaining steps are near the building, the same in every mode). RTX now brightens smoothly
from 75 to 101 over 32–56 blocks instead of jumping. FPS (10 s): far 59.2 → 58.8, near 71.7 → 73.9, overview 44.7 → 44.0,
inside the run-to-run spread.

## 21. RTX block light: area sampling, no stacked shadows (2026-11-14)

Two player reports, RTX style, the reference config (block light 2 rays, DLSS Quality): a white room with one glowstone and a
fence showed 6–10 parallel sharp shadows of the fence on the wall instead of one soft one, and a torch-lit floor showed
hard-edged arcs that flickered while moving.

| Cause | Change (`rtx/blocklight.glsl`) |
|---|---|
| Every pixel traced the same 8 fixed disk points (radius 0.75 × 1.6 × source radius), so each point cast its own sharp shadow: 8 stacked copies of every occluder | The disk points are rotated and placed per pixel and frame (interleaved gradient noise stepped by the golden ratio per `frameCounter`, stratified by quadrant, radius uniform over the disk area). TAA / DLSS average them into one continuous area light whose penumbra widens with distance. Still 4 rays per source, 8 only where the first 4 disagree |
| The traced set (the `rays` strongest sources) switched abruptly where two sources' weights crossed: a hard arc on the floor between torches, moving with the sub-pixel jitter | The (rays + 1)-th weight is a threshold; a traced source counts by `a = clamp((w − wK) / wK, 0, 1)` and its remaining share joins the untraced sources (mean visibility of the traced ones). A source fades out of the traced set instead of dropping out |

Faithful block light (§15) keeps its fixed points: its estimator only removes light, and its player test was about noise.

Measured with the reference config at 2560×1386 (scratch scenes `rod`: white-concrete room, glowstone, oak fence and iron
bars; `arcs`: smooth-stone floor, 16 torches 7 blocks apart, chest and wall stub; 10 still frames):

- `rod`: the stacked fence shadows are gone; one soft shadow remains.
- `arcs`: frame-to-frame floor luma change (blurred, mean) 0.116 → 0.061; pan unchanged (2.10 → 2.05).
- Hall of 96 torches: image within 0.64 grey levels mean of before.

The lava source block in a cross of flowing lava looking darker and redder than the flowing lava is the pack's texture, not
an RT bug: Iris + Sodium with the same pack and settings shows the same (centre vs arm, mean RGB: Iris 255/178/53 vs
255/185/60, ours 255/188/62 vs 254/197/81).

## 22. Sodium: rebuilt sections stay in the TLAS (2026-11-14)

Player report: with a redstone clock (crafter, observers, dust) running nearby, and while holding a light with
LambDynamicLights, the lighting of the whole area pulsed. Both rebuild chunk sections every few ticks. On the Sodium path
`SodiumRt.sync` put only already-built BLAS into the frame's instance list, so a section whose mesh had just changed was
dropped and its new BLAS, built a few lines later in the same command buffer, joined only the next frame: every rebuild
left a one-frame hole in walls, floor and ceiling. Shadow, block-light and GI rays went through it, and the GI filters spread
the leak over the room. The light list was not the cause (`RtLights.end` replaces a section's lights in one `put`), and
nothing resets the GI history.

Now a replaced section goes to the front of the build queue, and the instance list is filtered after this frame's builds,
so the new BLAS is in the same frame's TLAS. The vanilla path already built uploads before `levelStart`.

Measured (reference config, 2560×1386, DLSS Quality, closed stone-brick hall with 16 torches and an observer clock driving a
crafter and dust; mean luma of 20 consecutive frames, clock running vs. clock stopped): before day 69.17 vs 68.70, night
67.40 vs 68.03; after day 68.37 vs 68.32, night 68.34 vs 68.35. The clock no longer changes the image. A dev-run test with
LambDynamicLights (held torch, camera stepped per frame) showed no difference between torch and empty hand in either build:
the autotest moves the camera, not the player, so LDL scheduled no rebuilds; that case goes through the same path and is
covered by the same change but was not reproduced.

## 23. RTX block light: every source sampled, hidden sources out of U (2026-11-14)

Player report (long-standing, two earlier attempts could not reproduce it in self-built halls): in an underground torch hall,
RTX style, the floor showed big curved dark bands that no geometry could cast. GI off and AO off: still there; RTX block
light "off" (which in RTX still means 4 rays, `RtConfig`): weaker; Faithful: none. The reporter exported the exact hall as a
WorldEdit schematic (a two-level storage hall: 29 floor torches, under the floor a chest room with 36 lit copper bulbs and 76
redstone wall torches, froglights in the walls and the floor edge; 387 light sources in all). Recreated in the scene world by
a local helper script, not in the repo (Sponge v3 schematic → a stone shell plus run-length `fill … strict` commands, no
block updates, so redstone keeps its state) and shot with the reference pack, settings and RT config (Sodium, block light 2
rays, GI high).

### Cause

Every light cell (8³ blocks) in that hall is full: 31 sources, most of them hidden (an offline model of the light grid and
the estimator, `arcs2tools/sim.py`, gives 41 % of U fully occluded on the hall floor, 32 % from sources below the floor plane).

| Cause | Change |
|---|---|
| Only the `rays` strongest sources were traced; every other source of the cell got the **mean visibility of the traced ones**. Where a hidden source (a bulb under the floor, a froglight in a wall) became one of the two strongest, the whole untraced rest turned dark; where a visible torch did, it turned bright. The ranking changes along circles around the sources: the arcs. §21's fade-out of the threshold source only softened the edge | The untraced sources get their own visibility: RTX GI's trace pass (prepare, this frame's surfaces) repeats the gbuffers' choice of traced sources and, per GI ray, draws one of the remaining sources by its remaining weight (weighted sampling over the whole cell, the fade-out share of the traced ones included) and traces one shadow ray to a random point of its disk. The result rides in the free alpha of GI channel C, so the existing temporal accumulation and à-trous filter denoise it; the gbuffers swap it in for the old guess (`emrRtxBlRescale`, applied in GI's `sceneLighting` patch of the block-light programs) |
| U, the normaliser ("all sources unshadowed, as if facing p"), counted sources **behind the surface**: they never light it, and the pack's light does not hold them either (they sit under the floor or behind a wall). Each one darkened everything in its range: more circles, and the RTX image lost energy (mean luma of the hall 58 vs 70 with RT off) | A source behind the surface counts in U only as far as the pack's vanilla level exceeds what the sources in front explain in vanilla terms (`level − L1` from the air block in front, one level of slack). The far side of a pillar keeps its dark side (nothing in front explains the pack's light there); a floor above a lit cellar no longer darkens |
| Wrapped N.L at 0.25 with strength 1.8: floors (lit at grazing angles) dropped to a third of the pack's level a few blocks from a torch while the floor right under it got 1.8x: bright pools and a too-steep falloff | Wrap 0.5, strength 1.9 (`blocklight:wrap`, `gi:wrap`, kept equal: both passes must pick the same traced sources). The hall's mean luma now matches RT off within 1 % |

Debug options (RTX block light): `blocklight:ref=1` traces every source of the cell with all disk points (the reference),
`blocklight:debug=1` colours each pixel by its strongest traced source (brightness = traced share of the weight: the
region borders are where the old arcs were), `debug=2` is the old estimator (old U, traced mean visibility for the rest),
`debug=3` the new U with the old rest estimate. `RtConfig` passes `gi:lightRays` when block light is not 4 rays.

### Measured (reference config: Sodium, RTX, block light 2 rays, GI high; 1920×1080 dev window, FSR in the dev run)

Mean absolute luma error against the reference (0–255), the hall scenes `hall_b` / `hall_c` / `hall_d`
(scratch folder `arcs2/hall-scenes.txt`):

| | hall_b | hall_c | hall_d |
|---|---|---|---|
| before, 2 rays | 9.06 | 7.05 | 6.02 |
| before, 4 rays (the reference config's "block light off") | 6.29 | 7.47 | 6.65 |
| after | 1.54 | 1.16 | 0.82 |
| same build, old rest estimate (`debug=3`, earlier wrap) vs its reference | 9.72 | 3.16 | 4.67 |

Mean luma, before / after / RT off: hall_b 77.5 / 78.1 / 77.3, hall_c 44.6 / 51.6 / 52.0, hall_d 68.0 / 73.4 / 73.6.

Cost (hall, 3 × 15 s per build, two runs in opposite order, exclusive GPU): GPU +0.3 to +0.7 ms (prepare_a 1.9 → 2.4 ms:
the rest sampling, one shadow ray per GI ray; gbuffers opaque 2.82 → 3.0 ms: the U loop). FPS 80.8 → 79.6 in the run with
the new build first, 91.7 → 82.8 in the other (run-to-run spread between game starts is large here; the GPU profile is the
figure).

Limits: the untraced sources' visibility is one scalar for all their colours, and filtered like GI (a shadow of an untraced
source is soft, a few texels of the GI image); the two strongest sources keep their exact per-pixel shadows. The U gate
reads the vanilla lightmap, so light from entities (held lights) is not part of it. The Nether and End use it too since §24.

## 24. The End and the Nether: no washed-out End, GI everywhere (2026-11-15)

Player report: in the End with Unbound + Euphoria Patches (RTX) "everything looks terrible", super bright; without Euphoria
fine. Compared against Iris 1.11.7 (Sodium 0.9.3-beta.1, same pack and settings) on the main island, a crimson forest, a
fortress over the lava sea and a closed netherrack room with lava (scratch folder `endnether/bench.txt`, seed 2026).

**The End.** RT off and Faithful match Iris (world1 programs, End sky, beams, stars all present; mean luma 60 in all three).
RTX was the outlier (luma 84): the `shafts` effect multiplies `GetVolumetricLight` by 2.5 in every dimension, and in the End
that function is not sun shafts but the pack's sky-filling volumetric haze, which Euphoria turns into the much stronger
END_BEAMS curtains (1536-block ray, fog blend). ×2.5 washed the whole view into bright lilac. `shafts` is now
`dimensions: ["world0"]`; RTX End luma 62. The dimension mapping (Euphoria's `dimension.properties`, `world0=*` first) was
checked too: `minecraft:the_end` matches world1 exactly before any wildcard (`Dimensions.folder`). The Nether matched Iris
in every mode.

**Why GI was off there.** Only the table (`gi.dimensions = world0`); nothing in the Nether or End prevents it. The GI model
did not fit them: it treats ray misses as sky and hits as lit by their sky lightmap, but in the Nether and End the pack's
ambient lights every surface alike (`ambientMult` stays 1, the Nether has no sky light), so the overworld model would
darken everything that cannot see 64 blocks of open air.

**GI in the Nether and End.** Computes now get `const int emrRtDim` (from the dimension folder; packs only define NETHER/END
in their own program files). With `emrRtDim != 0` (`gi_common.glsl`): rays end after `gi:range` (12) blocks and a miss counts
as the pack's ambient (local occlusion instead of sky visibility), a hit reflects that ambient in full (instead of its sky
lightmap), and the Nether skips the sun shadow ray (its lightColor is 0). Sun bounce in the End, block-light bounce (lava,
glowstone, shroomlight), multiple bounces and the RTX rest-visibility for untraced block lights (§23) work as in the
overworld, so RTX block light has its full path there now. Same for Faithful GI. The overworld path is unchanged.

Result (RTX, reference config, 1920×1080 DLSS Quality): the Nether changes little and looks natural (mean abs difference
0.6–0.8 of 255, mostly animated lava; slightly darker contact corners, a little lava bounce on nearby walls), because its
fog and block light dominate the pack's small ambient. Frame time (15 s, exclusive GPU) before → after: End 4.79 → 5.56 ms
(209 → 180 FPS), crimson forest 5.63 → 7.14 (178 → 140), fortress 5.56 → 6.89 (180 → 145), lava room 4.35 → 5.56
(230 → 180). The GI computes cost 0.6 ms (End) to 1.2 ms (crimson) at GI High; GI Low roughly halves that.


## 25. RTX GI: placing or removing a light no longer ghosts (2026-11-15)

Motion check of §23 in the reported hall (`hall_d`, reference config: Sodium, RTX, block light 2 rays, GI high, DLSS Quality, FG
off): `turnshot` of 30 frames at ±1°/frame and a 2.4-block move against a settled shot of the same pose, and a torch set on
and off 4 blocks in front of the camera, shot 1/5/15/45/105 frames later against the settled result.

**Camera motion:** no visible trails or lag. Whole-image luma MAE vs settled 0.8–1.5 of 255 (mostly
torch particles and the history's last few frames).

**Light changes:** they ghosted. After removing the torch a soft bright spot stayed on the floor under it for about 40
frames (crop MAE vs settled 7.1 / 5.7 / 3.8 / 1.4 at 1 / 5 / 15 / 45 frames), and a new torch took as long to reach its
full brightness (5.3 / 3.3 / 1.3 at 5 / 15 / 45). That is GI's 24-frame history: the bounce and the visibility of the
untraced lights (channel C) still held the old light, and the history test (distance and normal) cannot see a light change.

**Fix:** `RtLights` diffs each recompiled section's light list against the previous one and keeps a world box around the
added and removed lights (their range plus 13 blocks for the bulk of one bounce) for 4 frames (the light list can change a
frame before or after the mesh). The box rides in the light buffer's former header pad; RTX GI's temporal pass caps the
history to 3 frames inside it, and the trace pass then boosts the rays for the young history as it does at disocclusions.
After: removal 3.3 / 1.3 / 0.8 / 0.4, placement 1.1 / 0.9 / 0.8 (the rest is the vanilla flame particle, which lingers in
vanilla too). Limits: one box per frame around all changes; overworld GI rays reach 64 blocks, so a far bounce of the old
light still fades over the full history.

## 26. RTX block light: no lines on the walls around a glowstone (2026-11-15)

Player report (RTX, Sodium): in a narrow white-concrete shaft with a glowstone and a fence post on the floor, a thin dark line
ran across the back and both side walls about a block above the floor, with a hard-edged brighter band below it and a
brightness step at the floor/wall junction; in a second room the shapes were straight frames that changed with the face
direction. GI off: still there. Reproduced in scratch scenes `shaft`, `shaft3` and `roomr` (`seam/scenes5.txt`).

**Cause.** RTX samples a disk of 1.6 × the source radius (§21): 0.72 blocks for a full block, so part of the disk lies
outside the block. A ray to such a point misses the block's box, and the trace still ended at the box entry `enter`,
which for a miss is beyond the source: the ray crossed the room and hit the far wall. Whether a point above the block
missed it that way flips where p crosses the plane of the block's top face (and its side faces, and the floor plane),
so the visibility had a crease there. An offline model of the shaft (`seam/sim/sim.py`) shows it on the side wall:
0.585 at 1.0 blocks, 0.550 at 1.2, 0.639 at 1.5 (a dark band just above the glowstone), monotonic after the fix
(0.582 / 0.621 / 0.679). The S/U factor (strength 1.9) made it visible.

**Fix.** A ray that misses the box ends at its sample point (`rtx/blocklight.glsl` `emrRtxLightRay`, same in GI's
untraced-light sampling, `rtx/gi_common.glsl` `emrGiRestVis`); hits still end at the box. Points inside a neighbouring
wall stay blocked by that wall, as before. Clamping the points into the block was tried and dropped: it piles samples on
the box faces and stacks sharp shadow copies (§21). Faithful (disk 0.35) and the GI bounce light (inside ±0.45) always hit
the box and are unchanged.

Not reproduced: streaks on the underside of an overhang in daylight (scene `overhang`, static, reference config): clean.

## 27. RT block light: no hard-edged band where a light cell overflows (2026-11-15)

Player report (RTX, GI high, snapshot in the torch hall at 323 -39 325): a tall dark band with a hard vertical edge on the
hall wall and a dark wedge on the floor in front of it, "hard shadows with weird shapes". Block light rays 0: gone; GI, AO,
sun shadows, entity shadows off and spectator mode: still there, so RT block light, not an entity. The hard edge was the
light grid's cell border at x = 320.

**Cause.** Each 8³ cell lists at most 31 sources; a full cell kept the ones strongest at its centre. The hall has about
100 sources in reach of a cell (a froglight column and row in a glass room behind the wall, lava and redstone torch rows
above the ceiling, copper bulbs below the floor), so the cell behind the band was filled mostly with froglights behind the wall and lost
the three torches in the hall; its neighbour kept them. The pixels on the full side normalised the pack's light by lights
they cannot see, a hard step at the border.

**Fix.** A light touching another light source (diagonals included) counts 8 levels less when a full cell picks what it
keeps (`RtLights.addToCells`): dense emitters keep a few representatives, and the torches around them get their slots back.
Cells that do not overflow are unchanged. Rebuild of 1588 lights: 2.0 → 2.3 ms.

Not fixed: with GI off, a hidden source in the traced set (lava or a froglight behind a wall, front-facing for the floor)
still drags the untraced lights' estimate down to a soft star on open floor; with GI on, its visibility sampling replaces
that estimate.

## 28. RTX sun shadows: no untinted rim around mob and player shadows under water (2026-11-15)

Reported F9 snapshots (river on the test world, RTX, GI high, refraction, DLSS Quality + DLSS FG): a dark squid-shaped blob
with a brown rim beside each squid, brown streaks around the first-person hand over the water ("a second ghost hand with
an aura"). Replayed with one switch at a time: FG off, DLSS off (pack TAA), RT refraction off, reflections off, GI off
all keep it, so none of those layers. It is the sun shadow term.

**Cause.** In Full mode the RT rays decide visibility and the shadow map only adds the water's colour and caustics, but
only where the map's opaque depth (shadowtex1) is clear; elsewhere `emrRtxShadowTerm` used plain white sunlight. Mobs and
the player are in the map too, and their map shadow is wider and offset from the traced one (filtering, bias, and the
first-person body). Where the rays saw the sun but the map had the mob, the floor under water got untinted sunlight: a
warm rim around the traced shadow, which refraction then shows bent away from the mob. Since `34359f1` (RTX style).

**Fix.** The shadow rays note the nearest water face they pass. If the map's opaque depth blocks but a ray crossed water,
the water's light comes from the map on a 1.5-block ring around the receiver (taps where the opaque depth is clear), as
the caustic tint already does. Only those pixels pay for it. Faithful style still uses plain sunlight there (its rays do
not know the water id).

Result (snapshot replay 14-42-28 / 14-43-05 / 14-43-32): the squid and cod shadows are soft and blue like the rest of the
floor, the brown bands left and right of the hand are gone. FPS before / after at the snapshot poses within noise (60 / 58, 62 / 61,
70 / 68 rendered). Not changed: the noisy, grainy look of the refracted floor (also with DLSS off, gone with refraction
off), see the refraction notes.

## 29. RT AO: redstone torches and repeaters glow again (2026-11-15)

Player report (RTX, GI high, Sodium; snapshots in the test world at 326 -39 320 and 330 -44 309): the heads of lit redstone
torches and of powered repeaters were dark red-black; RT off they glow. Faithful: the same. Ablation (one RT effect off at a
time): only `rt.ao=false` brings the glow back.

**Cause.** Since 1.21.2 the lit redstone torch (and each torch of a powered repeater or comparator) wraps its 2-pixel head
in a 3-pixel glow shell of inward-facing quads, half a pixel away. Its 1-pixel UVs are opaque, so the shell sits in the
opaque BLAS and the AO pass's near-cutout skip (§13) never sees it: every AO ray leaving the head hit the shell at once,
AO went to about 0, and the pack multiplies its final colour (emission included) by AO in `deferred1`.

**Fix.** `rt_ao.comp`: a hit on a small quad (both edges at most 0.2 blocks, from the `at_midBlock` differences of its
vertices) of the block whose cell holds the ray origin does not occlude; the ray continues, as through lava. Big faces of
the same block (a stair riser, a cauldron wall) and every other cell still occlude. Tried first and dropped: lifting the AO
by the emission the pack stores in `colortex6.a` (RGBA8, so `lmCoord.x + emission` clamps at 1 and is unusable), and
limiting the rule to blocks with light emission (repeaters and comparators emit none).

Measured (both snapshots, RTX and Faithful): heads as with RT off; everything else within the run-to-run noise (MAE 6.15
before/after against 6.13 between two runs of the same build, 2560x1386).

## 30. Redstone torch heads and repeater torches as bright as with RT off (2026-11-15)

Player report after §29 (RTX, GI high, AO, block light 2 rays, full shadows, DLSS + FG; snapshots at the wall redstone torches in
the Deep Dark corridor 334.5 -46.9 315.7 and at the repeater rows 330.6 -44 309.8): the heads were better but still dark red;
the repeaters and comparators too. Ablation, one effect off at a time, tight crops of the heads:

- **Wall redstone torches:** only `rt.ao=false` brings back the RT-off look. With the glow shell no longer occluding (§29) the
  AO rays from the head still hit the wall the torch hangs on, a quarter block away: the faces towards it went dark.
- **Repeater and comparator torches:** only `rt.blockLightRays=0` brings it back (AO barely matters since §29). With the
  reference settings' `RP_MODE=3` (labPBR, no labPBR pack) the pack gives these texels no emission at all, RT off they are bright because
  the pack's floodfill light treats lit repeaters as red light sources. RT does not know them as sources (no vanilla light
  emission), so the RTX factor S / U took that light away.

**Fix.**
- `rt_ao.comp`: a short ray back along the normal finds the block the pixel lies on; if it emits light level 6 or more
  (`at_midBlock.w` of the hit vertex), its AO is 1 and no AO rays are traced. Ambient occlusion does not dim a light. This
  also leaves torches, lanterns, glowstone, lit furnaces and the like without RT AO (RT off the pack's short SSAO barely
  touches them either); dim emitters (mushrooms, brewing stands, magma) keep it.
- RTX block light (`rtx/blocklight_apply_terrain.glsl`, gbuffers_terrain only): the red texels (r > 0.5 and 0.125 above
  green and blue) of powered repeaters and comparators (`mat` 10644) keep the pack's light like light sources. The repeater's
  base and the floor keep the RTX block light.

Tried first and dropped: lifting AO and keeping the pack's light where the pack's emission is high (`colortex6.a` ≥ 0.99, the
DoLighting `emission`): in labPBR mode these texels have no emission, so nothing changed.

Measured (both snapshots replayed, the reference config): heads as with RT off in RTX and Faithful; the difference images
before/after show only the torches, the repeater heads and the lit dust on the repeaters (plus particles); whole-frame MAE
before → after 4.5 (torches) and 4.65 (repeaters) against 4.0–4.6 run-to-run with DLSS FG. Not done: the red glow the pack
casts from lit repeaters onto the floor stays missing in RTX (same cause, a source RT does not know).

## 31. RTX: the red glow of lit repeaters on the floor; RT AO on lights checked and made cheaper (2026-11-15)

**Floor glow.** RT off, Complementary's floodfill light (`COLORED_LIGHTING`, with `DO_IPBR_LIGHTS`) treats powered repeaters
and comparators (`mat` 10644, voxel id 67, `redstoneSpecialLightColor * 0.125`) as red sources: the floor and blocks around
them take the vanilla light's brightness in their red. They emit no vanilla light, so RT did not know them, and the RTX
factor S / U (§23) scaled that red by the hall torches' visibility: the floor between the repeater rows went grey-brown.
Faithful keeps it (it only removes light a blocked known source explains).

Fix: powered repeaters and comparators (`DiodeBlock.POWERED`) are RT block-light sources of level 7 (`RtLights.level`, also
the Sodium compile hook) in redstone red, one green step below the redstone torch's (`LightColors.DIODE`), so the light upload
can give them a gain in the colour's alpha (0.55; 1 for every other source). RTX block light (`emrRtxLitColor`, in S only, not
in U) and RTX GI (glow and direct light at bounce hits) multiply by it. At gain 1 the floor came out 30 % over RT off: the
factor (strength 1.9) and GI's new red bounce both added. Levels 4 to 6 changed nothing in the hall (most likely the
hall's cells are full and the repeaters lost their slots; not traced further), so the level stays 7 and the gain sets the
strength.

Not done: the pack's other floodfill-only sources (lit redstone wire and the unlit subtract comparator, id 66, 0.05 / 0.002;
glowing ores with `GLOWING_ORE_MASTER=2`; crimson/warped stems; redstone and lapis blocks when their options are on; command,
structure blocks, spawners) stay unknown to RT. Ores in particular would add many sources in caves.

Measured (snapshot 330.6 -44 309.8 replayed, reference config, red channel 0-255, mean of each patch, RT off → RTX before → after):

| Patch | RT off | RTX before | RTX after | Faithful after |
|---|---|---|---|---|
| floor next to the repeaters | 97 | 66 | 107 | 98 |
| floor between the rows | 108 | 53 | 109 | 104 |
| floor, right row | 103 | 68 | 96 | 103 |
| floor further down | 74 | 54 | 63 | 73 |
| platform front above the rows | 42 | 23 | 37 | 40 |
| repeater bases | 125 | 61 | 134 | 125 |

**RT AO on lights (§30), checked.** A closed stone-brick test room in the sky of the test world (700..710 150..157 700..710):
glowstone in a floor corner, glowstone against a wall at head height, standing lanterns against a wall and in a corner,
hanging lanterns at the ceiling against a wall. RT off against RTX: the glowstone and the lanterns glow as RT off, keep the
pack's face shading (no flat look), and show no halo; the walls and the floor right next to them keep their AO.

**Cost of the ray back to the block.** Exclusive runs, reference config (RTX, AO, GI high, DLSS Quality + FG, 2560x1386),
redstone hall (the snapshot pose) and outdoors (`overhang`, noon), 20 s each, GPU time of the AO trace pass:

| | hall | outdoor | rendered FPS hall / outdoor |
|---|---|---|---|
| without the test (as before §30) | 0.33, 0.33 ms | 0.32, 0.35 ms | 37.1, 38.4 / 79.6, 78.6 |
| §30: ray before the AO rays | 0.49, 0.49 ms | 0.46, 0.47 ms | 41.6, 38.6 / 78.7, 78.1 |
| now: ray only where the AO rays found occlusion | 0.37, 0.38 ms | 0.35, 0.33 ms | 39.0, 40.7 / 80.3, 79.2 |

The extra ray cost 0.15 ms per frame (45 % of the AO pass, about 1 % of the outdoor frame; the FPS differences are within the
run-to-run noise). The test now runs after the AO rays and only where they found occlusion (`open < 1`), which leaves
0.0–0.05 ms. Same images as before (MAE 0.4–1.2 against the §30 order at every test pose, run-to-run noise).

## 32. RTX block light: ReSTIR over every source in reach; ores and redstone as lights (2026-11-15)

**Why.** The light grid kept at most 31 sources per 8³ cell and RTX traced the `rays` strongest per pixel; everything
else got an estimated visibility (§21, §23, §27 patched the consequences: arcs, bands, crowded-out torches). Sources only
the pack's coloured light knows (ores with `GLOWING_ORE_MASTER`, lit wire, repeaters, comparators, nether stems, command and
structure blocks, spawners, fungi, chorus) were unknown to RT, so the S / U factor took their glow away wherever a known source
was in reach (§30, §31 handled diodes only).

**Sources** (`RtLights`, `LightColors`). One uncapped list: every vanilla emitter plus the pack-only blocks, each with
the pack's colour (`GetSpecialBlocklightColor`, normalised), a range from the pack's extra-light alpha (`level = 9 + 2
log2(alpha / 0.05)`, 3..15) and a power (`0.5 sqrt(alpha / 0.05)`, ores times `GLOWING_ORE_MULT · ACT_ORE_INTENSITY`).
`RtLights.configure` reads the pack's option values at install: pack-only sources exist only with `COLORED_LIGHTING > 0`
and `DO_IPBR_LIGHTS`; ores with `GLOWING_ORE_MASTER` 2 (or 1 in Unbound); stems with `GLOWING_NETHER_TREES`; redstone and
lapis blocks with their `EMISSIVE_*` options. Buried blocks (no open neighbour) are dropped as before. Per cell an index list
(at most 512 per cell, 2^19 entries), rebuilt when the light set changes or the camera crosses a cell, uploaded only then
(positions are grid-relative). A remap table (last list index → new, by block position) keeps reservoirs valid across
rebuilds. The capped grid stays for Faithful block light and refraction (vanilla emitters + diodes, as before).
Buffer layout: `rt/complementary/bl_buffer.glsl` (the one declaration for every pass).

**ReSTIR DI** (Bitterli 2020, RTXDI as the reference design), four compute passes in `prepare_f..i` at render resolution,
before the gbuffers, on this frame's surfaces (view ray against the TLAS like RTX GI):
1. `bl_restir_a`: surface, vertex lightmap, U (all sources in reach, the §23 gate), one exact weighted draw over the whole
   cell list with a visibility test (occluded candidates keep M but no weight), temporal reuse of last frame's final
   reservoir at the reprojected pixel (M cap 4, 3 inside the light-change box of §25).
2. `bl_restir_b`: spatial reuse (2 / 4 / 6 neighbours for the Low / Medium / High setting, i.e. block light 2 / 4 / 8,
   radius 16 px, same plane, at most 1 block away), one final shadow ray to the sample's disk point (glass tints), ratio S / U.
3. `bl_restir_c`: temporal accumulation of the ratio (8 frames, 3 in the change box), history clamped to the 3x3 spread.
4. `bl_restir_d`: one 5x5 edge-aware à-trous step → `emrBlOut`; history images written.
The gbuffers (`rtx/blocklight.glsl`) only look the ratio up (own pixel if the surface matches, else a ring up to 8 px; no
match: the sources unshadowed) and apply `clamp(ratio · strength, ambient, strength)` as before. GI's untraced-light
visibility (§23 `emrGiRestVis`, `emrRtxBlRescale`) is gone; RTX GI lights its bounce hits from the same list
(`emrBlPick`: exact up to 16 sources, else 16 uniform candidates resampled by weight). `rtx/bl_common.glsl
emrBlLightAt(p, n, seed)` is the stable one-sample interface for other passes (unbiased estimate of S at p).

A pack-only source lights from the point of its block nearest the surface (`emrBlSourceAt`): an ore flush with a wall then
lights that wall, as the pack's floodfill does; from the block centre it sat behind the face and lit nothing there.

Options: `blocklight:visRay` (1), `bias` (1, ray-traced bias correction, see below; 0 = plain 1/M), `mcap` (4), `radius` (16), `reach` (1.0, blocks: farthest spatial neighbour; 0 = no limit), `frames` (8), `clamp` (2), `ref=1` (every source, 32 disk
points, no reuse), `debug=1` (ratio), `debug=2` (green: traced surface, red: fallback). Removed: `gi:lightRays`,
`gi:noblocklight`, `gi:wrap/falloff/lightSize`, `blocklight:size/glass/wrap/falloff` (constants in `bl_common.glsl`).

Boiling (2026-11-21): with `mcap` 20 and the pixel radius alone, far and grazing floors near torches showed slow blotches
("like water reflections"): 16 px there span several blocks, a neighbour's sample was weighted by its own very different
light, and the history passed it on for 20 frames. `reach` 1 + `mcap` 4: blotch std on the far farm-hall floor 2.3-4.6 -> 0.2
(reference `ref=1`: 0.15), mean unchanged.

**Measured** (reference config: Sodium, RTX, block light 2, GI high, DLSS Quality + FG, 2560×1386; snapshots replayed; mean
luma of the frame, RT off / RTX before / RTX now):

| View | RT off | before | now | now vs `ref=1` |
|---|---|---|---|---|
| torch hall 13-28-25 (the "hard shadows with odd shapes" report) | 52.6 | 64.9 | 59.6 | ref 62.3 |
| hall 14-38-35 | 28.7 | 38.9 | 39.3 | |
| Deep Dark corridor, redstone wall torches 16-32-24 | 29.5 | 24.4 | 35.9 | ref 36.1 |
| repeater rows 14-38-57 | 34.8 | 34.5 | 32.7 | |
| hall 12-19-57 | 51.0 | 62.1 | 60.3 | |
| ore cave (sky scene, 160 ore features, 2 torches) | 30.6 | 30.5 | 41.5 | ref 38.3 (before the flush-ore change) |

The estimator matches its reference within 1–3 luma. The torch hall's hard-edged band and wedge (cell borders, §27) are
gone: no cells cap the sources any more. The corridor is redder and brighter than RT off: walls facing the redstone torches
get the full `strength` 1.9 (before, the capped estimate darkened them below RT off). The ore cave is brighter than RT
off for the same reason (ore-lit walls × 1.9).

Single-source cells (9×9 sealed room, white concrete; floor crop, RT off / now): torch 130.8 / 133.9, iron ore 34.3 / 42.8,
diamond ore 44.6 / 34.4, wire 21.8 / 19.4, repeater 15.4 / 15.1, crimson stem 38.9 / 28.0, spawner 38.0 / 39.6, command
block 32.6 / 23.3. The range/power mapping from the pack's alpha is a first fit, within about ±30 %.

**Cost** (same config, exclusive GPU, 20 s each, base `2122bed` → now; FPS rendered, GPU ms of the passes):

| View | FPS | GI trace `prepare_a` | ReSTIR `prepare_f..i` | gbuffers opaque |
|---|---|---|---|---|
| torch hall | 57.2 → 49.0 | 4.65 → 6.92 | – → 2.02 | 3.86 → 2.62 |
| corridor | 47.7 → 41.8 | 2.60 → 2.70 | – → 2.40 | 6.59 → 9.40 |
| ore cave | 86.3 → 79.2 | 1.20 → 1.89 | | |

**Open (next steps).** 10–15 % slower than before. GI's bounce-hit light still scans every source of the cell for the
light block holding the hit (`emrBlPick` "inside" loop) and evaluates 16 candidates per bounce: drop the scan (mark light
blocks some cheaper way) or reuse the gbuffer ReSTIR reservoir at screen hits. The corridor's gbuffers opaque rose by 2.8 ms:
probably the lookup's fallback (no surface match → full unshadowed loop over the cell) on many pixels, check with
`blocklight:debug=2`. Calibration: the corridor and the ore cave are brighter than RT off (strength 1.9 on walls facing
the sources); pack-only range/power are a first fit (±30 % per block type). Motion and light-change ghosting were not
re-measured for the new denoiser (scripts ready: scratch folder `rs/motion.s`).

**Follow-up (2026-11-15, same change).** *Cost.* GI's bounce hit finds the light block it landed in with one probe of a
hash table by block position (`emrBlBlock`, built with the full list in `RtLights`, `RtLights.blockSlot` = `emrBlHash`)
instead of scanning the cell. The gbuffers skip the unshadowed fallback loop for fragments the view ray found a surface in
front of (overdraw: the corridor's 2.8 ms; `blocklight:debug=2` shows them blue). Same config and views, exclusive GPU, base
`2122bed` → §32 → now: torch hall 57.2 → 49.0 → 63.5 FPS (GI `prepare_a` 4.65 → 6.92 → 3.56 ms), corridor 47.7 → 41.8 →
59.4 FPS (gbuffers opaque 6.59 → 9.40 → 3.51 ms), ore cave 86.3 → 79.2 → 83.1 FPS.

*Calibration* (RT off as the target; luma and hue r/g of the lit area, 16 views). A pack-only source now lights from the
air half a block in front of its nearest point (`emrBlSourceAt(i, p, n)`), where the pack's floodfill starts, at half the
power (`POWER_AT_ORE` 0.25): the single-block cells are within 5 % of RT off (iron +14 → −4 %, crimson −18 → +3 %, command
−19 → 0 %). RTX GI's bounced block light is applied in the pack's hue domain (`gi:blockHue` 0.75: channel exponent of the
squared-colour bounce, brightness kept) at `gi:blockStrength` 2.5 (was 4.0): it alone made the halls 10–20 % brighter
and bluer than RT off. `blocklight:chroma` 0.5 lets half of the ratio's own hue through (the Deep Dark corridor was twice
as red as RT off). Mean |error| over the views: luma 15 → 8 %, hue 24 → 13 %. Still off: the stems room (+31 %: GI
bounces from the stems the pack does not have), the repeater rows (−14 %), the shaft (−53 %: sky light, not block light,
unchanged since §27). Not re-measured: motion and light-change ghosting of the denoiser (scratch folder `rs/motion2.s`).

*Stems and repeater rows (2026-11-15).* A GI bounce that lands on a block only the pack's coloured light knows no longer
adds that block's glow (`emrGiBlockLight`): the pack's floodfill already lights the room from it, so the glow counted it
twice. Redstone wire and diodes get 5x the power (`LightColors.REDSTONE_MULT`): it only shifts the ratio towards them
where other sources are in reach (a lone wire or repeater cell is unchanged), the pack sums their many weak floodfills.
Against RT off, before → now: stems room +30 → +14 %, repeater rows −14 → −8 % (hue −30 → −10 %), ore cave +11 → +6 %,
Deep Dark corridor +9 → +6 %, the other views and the single-block cells unchanged within 1–2 %; mean |error| luma
7.5 → 5.3 %, hue 12.4 → 8.0 %. Motion and light changes (scratch folder `rs/motion2.s`): a 30-frame turn or strafe ends within
MAE 0.6–1.1 of the settled image; a placed torch reaches its settled light within MAE 3 after 0.5 s and 0.8 after 2 s, a
removed one within 0.3 after 2 s.

*Review fixes (2026-11-16).* **Visibility reuse lost energy.** A reservoir whose sample was occluded keeps
its M with W = 0, and spatial reuse divided by every neighbour's M: a lit pixel next to a shadow divided its light by the
shadowed neighbours' M, and the darkened result went into next frame's history, so it compounded into a dark band up to the
16 px reuse radius along every shadow edge. `bl_restir_b` now counts a neighbour's M in Z only where the chosen sample has a
target and is visible from that neighbour (one shadow ray per used neighbour, none for the neighbour the sample came from:
RTXDI's ray-traced bias correction). Temporal reuse in `bl_restir_a` drops a history whose occluded sample is visible now
(one ray, only where the history has W = 0): a revealed light came back over ~20 frames. Sky test room (white concrete, one
torch, a 1x5 stone pillar; scratch script `pillar.s`), floor beside the pillar's shadow, luma against `ref=1` (50.3):
before −6.9 (−14 %), now −1.0; after breaking the pillar, the formerly shadowed floor against `ref=1`: 0.1 / 0.25 / 0.5 / 1 s
−16.8 / −7.7 / −3.2 / −0.7 before, −5.0 / −2.7 / −1.1 / −0.5 now. The §32 views (frame mean, bias 0 → 1, `ref=1`):
torch hall 13-28-25 64.65 → 64.84 (64.73), 14-38-35 38.50 → 38.75 (38.58), corridor 16-32-24 33.68 → 33.83 (34.11), repeater
rows 14-38-57 35.10 → 35.51 (35.61), 12-19-57 63.19 → 63.87 (63.98), ore cave 43.37 → 43.90 (44.11): +0.3 to +1.2 %, the MAE
against `ref=1` drops in five of six views (12-19-57 1.05 → 0.55); the calibration (fitted to ±5 %) stays as it is. Cost
(torch hall, reference config, exclusive GPU, 2 × 10 s each, interleaved): `prepare_g` 0.24 → 0.50 ms, `prepare_f` unchanged,
69.4 → 68.4 FPS rendered.

Also fixed: source centres were rounded to float before the grid origin was subtracted (`x + 0.5f - ox`; half a block lost
beyond |x| = 2^23); an unpowered subtract comparator matched the "comparator" entry first and lit as a powered diode; a
section that becomes all air (its last block broken) or unloads is never compiled again by vanilla or Sodium, so its lights
stayed in the list (phantom light, GI and U): `ClientChunkCacheStorageMixin` drops them (box test: the ratio view stayed lit
after the only glowstone was broken, now black); the block hash table's insertion is limited to the 64 probes
`emrBlSourceIn` makes (longest probe seen in tests at the list cap: 21; a source past it is left out with a warning, not
silently missed). Not done (below the FPS noise, or off the reference setup's path): uploading the capped grid only on rebuild (512 KiB, ~12 µs CPU per
frame), a shared-memory tile cache for `bl_restir_d` (with DLSS Ray Reconstruction the filter is off), fusing the fallback's
two source loops (fallback fragments only), caching targets for refraction hits (refraction hits only).

## 33. RT refraction lights its hits itself (2026-11-15)

Reported F9 snapshots (test world, RTX, GI high, DLSS Quality + FG): an aura beside the first-person hand over water
(16-27-51, 14-42-28), shimmer along block edges under water (16-28-52), the same shimmer seen through orange glass
(16-31-01), and earlier the half-transparent bent ghost blocks (13-27-35) and the grainy look. All of them were gone with
refraction off.

**Cause.** Until now composite1 shaded the traced hit as base colour × k, with k = the lit screen / that floor's base
colour, taken from taps around the hit's projection or the pixel. Every one of these artefacts came from those taps: the
hand, a block edge or a squid in front of the water printed its colour or its edge into k (each earlier fix moved or
weighted the taps, and a new case turned up), the ratio carried the pack's per-pixel noise (grain), and the screen lit the
straight floor, not the refracted one (ghost steps, light bands).

**Fix.** No screen colour is read for the hit at all. The refraction effect now patches gbuffers_water:
- `water.glsl`'s `lViewPosDifM` becomes the refracted path in water, so the pack's own water alpha, depth fog and
  `BRIGHT_CAVE_WATER` colour follow the traced path (this replaces `emrRtRfWaterOver`).
- Before `gl_FragData[0] = color;` the fragment's hit (traced once: `emrRtRfFragment`) is lit by the pack's `DoLighting`
  in that same stage, with the hit's base colour, vertex AO and lightmap from the TLAS, set up as gbuffers_terrain does
  (light sources' emission, plants lit through, terrain's vanilla-AO curve through two small DoLighting patches; those
  terrain statements and the light highlight are lifted from the pack's gbuffers_terrain at conversion time). The
  sun goes through the patched shadow functions (RT or map shadow, water colour, caustics); RTX block light runs for the
  hit through `rtx/blocklight_apply_hit.glsl` (marked by `centerShadowBias`, which the water program does not use
  otherwise). Since the merge with ReSTIR (§32) the passes never see a hit, so `emrRtxBlockLightHit` estimates S there
  from 4 `emrBlLightAt` samples over the same U and calibrates it like terrain (`emrRtxBlApply`: strength, chroma, far
  fade); GI (with its `gi:blockStrength` / `blockHue` glow) comes through terrain's `rtx/gi_scene_lighting.glsl`. Then terrain's light
  highlight, RT AO (4 fixed cosine rays of 1 block, as `rt_ao.comp` without its history; not on light blocks) and `DoFog`,
  as deferred1 applies them to the floor. Glass between the water and the hit is lit the same way and laid over it at its
  alpha. The water's colour goes over that at the water's own alpha, written with alpha 1.
- composite1's `DoRefraction` only moves z1 to the traced path (the pack's fog and light shafts behind the surface).
- `RtPatcher`: a `find` patch matches a function only where its anchor occurs (`void main()` also names the vertex
  stages).

Kept: the apparent-depth image, the wave tilt, the far fade (0.4–0.6 × far), the straight-ray TLAS check, motion vectors.

**Result** (snapshot replay, 2560x1386, DLSS Quality + FG): no hand aura, no shimmer at the step edges or through the glass,
the steps show once and look like refraction off (raised by the refraction), the deep kelp scene too.

**Cost** (rendered frame time, median of 2 × 8 s, refraction on / off; old = before this change):

| Pose | old on / off | new on (AO 4 rays) / off | refraction cost old → new |
|---|---|---|---|
| deep 14-41-55 (water fills the screen, kelp) | 35.9 / 29.2 ms | 47.0 / 29.4 ms | 6.7 → 17.6 ms |
| steps 13-27-35 | 19.0 / 16.6 ms | 22.4 / 15.7 ms | 2.4 → 6.7 ms |

(12 AO rays: 51.0 / 23.9 ms; none: 43.6 / 21.7 ms.) The rest is the hit's full lighting per water pixel: RTX sun rays,
block-light rays and GI lookup, the same work terrain does once per pixel.

**Cost cut** (later the same day). Measured with `-Demrion.autotest.profile=true` (GPU time of
the `gbuffers translucent` pass, which runs gbuffers_water), deep pose 14-41-55, reference config, 2 × 6 s each, one option
changed at a time (it is a compile-time constant):

| What the hit lighting does | translucent pass | vs. all of it |
|---|---|---|
| refraction off | 1.8 ms | |
| everything (before) | 17.9 ms | |
| 1 sun ray instead of 4 | 15.8 ms | −2.2 |
| no RTX block light at hits (`centerShadowBias` false) | 14.5 ms | −3.4 |
| no AO rays | 15.3 ms | −2.6 |
| no DoLighting / AO at all (only the trace, albedo, fog) | 4.2 ms | −13.7 |
| GI lookup skipped / DoFog skipped (with 1 sun ray, no BL, no AO) | | −0.8 / −0.4 |

composite1's two rays add 0.45 ms. The pass is occupancy bound: code that is present costs whether it runs or not.
Skipping the block light at runtime (a uniform-dependent branch around the same code) saved 0.1 ms of its 3.4, and the
ocean pose has no light source at all; skipping AO and sun rays at runtime did save their share (−2.4, −2.3). Tried and
dropped: sharing the AO and sun rays over the 2×2 pixel quad (each lane traces one ray, subgroup shuffles average
neighbours whose hit is close): no gain for AO, +1.5 ms for the sun (the shuffles inside DoLighting cost registers);
one DoLighting call site for the water and its hits (a loop instead of a second inlined copy): no change; finding glass
in the main ray instead of a second ray: +1 ms (the ray then walks the translucent sections, the ocean).

Kept:
- **One sun ray at hits** (`refraction:sunRays`, 0 = the shadow effect's own count): `emrRtHitRays` (common library)
  overrides the RTX shadow ray count while DoLighting lights a hit. The ray's disk point turns every frame, DLSS / TAA
  average it like the terrain's 4.
- **AO rays in one pass**: a fluid (lava) candidate is not confirmed, so the ray finds what lies behind it (rt_ao.comp
  traces a second pass from there). Same result, less code.
- The straight-ray TLAS check stops at its first hit (any hit before the raster depth is enough).
- `refraction:blockLight` (1): RTX block light at hits. 0 gives the hits the pack's floodfill light (as RTX far away) and
  saves 2 ms (deep) / 1.5 ms (steps), but the torch-lit cave pool came out clearly brighter and warmer than refraction
  off (MAE against off 12.6 vs 7.3 at 1.0), so it stays on. With ReSTIR (§32) the hit body is `emrRtxBlockLightHit`
  (4 `emrBlLightAt` samples): deep 14-41-55, mean of 2 × 6 s, same session: the build before ReSTIR 14.4 ms (`blockLight=0`
  11.5), with ReSTIR 11.3 ms (`blockLight=0` 10.6), so block light at hits now costs 0.7 ms instead of 2.9. The torch-lit
  pool (glowstone on the floor, two torches, night): refraction on vs off MAE 3.4 (before ReSTIR 3.6–4.1), mean
  colour of the pool within 2–3 levels of off in both builds.

Result (rendered frame time, mean of 2 × 6 s, refraction cost = on − off; DLSS Quality + FG, 2560x1386):

| Pose | old (screen taps) | self-lit before | now | now, `blockLight=0` |
|---|---|---|---|---|
| deep 14-41-55 | 6.7 ms | 17.6 ms | 12.6 ms (42.3 / 29.7) | 10.3 ms |
| steps 13-27-35 | 2.4 ms | 6.7 ms | 4.4 ms (21.0 / 16.6) | 2.9 ms |

Run-to-run spread about ±0.5 ms. The goal of 1.3× the old cost (≈ 9 / 3.5 ms) is not reached with block light on; the
rest is the pack's DoLighting at the hit (≈ 5 ms deep) and the trace (≈ 2.5 ms).

Images: the 8 reported snapshots against the build before the cut look the same (MAE 0.9–2.2 outside chat and HUD
differences, squid and caustic animation; crops looked at: hand areas 16-27-51 and 14-42-28, step edges 16-28-52 and
13-27-35, glass 16-31-01, kelp 14-41-55, 14-41-06, 14-43-05). Motion (`turnshot` turn and strafe, `fgturn`, beach /
deep / hand poses): turn and strafe frames against the still frame as before the cut (MAE 2.2–4.0 both builds), FG
interpolated frames the same as before (5.6 / 7.9 / 13.7); no smearing or boiling of the refracted floor seen. Night and
the torch-lit cave pool (singleplayer scene, `local3.s`): refraction on matches refraction off in brightness and colour.

**Repeater glow at hits:** the rule of `rtx/blocklight_apply_terrain.glsl` (powered repeater/comparator red texels keep
the pack's light) now sets `isLightSource` for such a hit too; nothing else in the water program's DoLighting reads it
for a non-plant. Not checked in an image (no repeater under water in the test scenes).

**Squid outline (player report, 9547046):** thin dark/light lines beside a squid's tentacles and along its head. A debug
view of the pixels where the straight-ray TLAS check failed (they keep the pack's unrefracted image, so the rasterised
squid shows there, displaced against the refracted one) showed exactly those lines; confirming entity candidates
without the alpha test in that check removed them (a wider depth window did not). `emrRtRfAny` now takes an entity's
triangles as coverage. Why the entity alpha test fails along edges the rasteriser drew is not known (entity atlas UVs
look right).

**Squid outline, again (reported F9 snapshots 2026-11-16 18-26-02, 18-27-16, 18-28-02):** broken tentacles (thin dark
slivers beside them) and a "frame seen through the body" (an L-shaped edge inside the squid's top face), same on fish.
A debug view of the straight-check failures (red) showed bands about one render pixel wide along every mob's raster
silhouette, with ticks frozen (so not a stale BLAS: the logged BLAS bounds matched the summon positions). Cause: the check
traced along the fragment's view direction `d = playerPos / |playerPos|`, which the pack computes with the TAA jitter
removed, while `depthtex1` holds what the rasteriser drew with the jittered projection (DLSS or the pack's TAA). Along a
mob's edge the unjittered ray passes beside it and the floor lies far beyond `tsMax`, so the pixel fell back to the
unrefracted image. Terrain edges rarely showed it because the floor behind a step is within the margin. This was also
the unexplained "alpha test fails along edges" above. The check now traces through the drawn point itself
(`emrRtRfBehind()`: `depthtex1` unprojected with the jittered `gbufferProjectionInverse`). Test: pool with NoAI squid,
glow squid, axolotl, cod, salmon and tropical fish, DLSS Quality, frozen and live: no lines, no inner frame.

**Cheaper without changing the image? (2026-11-16).** Deep pose 14-41-55, reference config, translucent pass
GPU time (`-Demrion.autotest.profile=true`), mean of 2 × 6 s, every variant in the same session; register counts from
`-Demrion.debug.pipelineStats=true` (BENCHMARK.md). Refraction on: 12.6 ms, off: 1.8–3.0 ms.

- The water fragment shader has **168 registers in every refraction variant** (spills 240–1152 bytes), also with the hit
  lighting removed entirely (304 bytes spilled); refraction off gives 255 registers and no spill. The NVIDIA driver caps a
  ray-query fragment shader at 168 (12 warps per SM), so less live state does not raise occupancy: the "compiled out
  saves more than skipped" effect is code size / scheduling, not occupancy, and it is not predictable (removing work made
  the pass slower twice, below).
- Cost of the parts (compiled out, image changed, diagnosis only): all hit lighting 7.8 ms (12.6 → 4.8; the rest is the
  trace), AO rays 1.7, RTX block light 0.55 (no source in reach here), GI lookup 0.9, DoFog 0.4, sun ray 0.0, sun ray
  without the map / caustic part +3 ms (slower).
- Tried, image unchanged by construction, all within ±0.4 ms and dropped: hit lighting skipped in helper lanes (also the
  whole trace in helper lanes: no gain, so quad overshading costs nothing measurable); AO rays skipped in helper lanes;
  hit lighting skipped where the water alpha is exactly 1 (no such pixels here); glass ray traced after the solid hit is
  lit (+0.35); no second normal reconstruction in the albedo (−0.1); the refracted path recomputed at the output instead
  of kept through the water's own lighting (+0.15, spill 272); `[[dont_unroll]]` on the AO and block-light loops (same
  binary). The 4 AO rays proceeded together (one query each, one step per round): +1.7 ms, spill 1152 bytes.
- Not tried: moving trace and hit lighting into a compute pass. The measured reasons it should pay (helper lanes, live
  water state, occupancy) do not apply here; the hit lighting is the same work terrain does per pixel (opaque pass 12 ms for
  the whole screen), and the pack's `DoLighting` would have to be built as a compute program with gbuffers_water's inputs.

**Open:** GI at a hidden hit uses the GI buffer at its projection (falls back to the pack's ambient when no matching
surface is there); End pool not checked (the scene's fill did not land); why entity alpha fails at edges.


## 34. Opacity micromaps for cutout terrain (2026-11-15)

Every RT ray loop alpha-tests cutout candidates itself (leaves, grass, flowers, panes, webs, vines: block atlas at mip 0,
cutoff 0.1). With `VK_EXT_opacity_micromap` (optional device set "Emberlight opacity micromaps", next to the RT set) the
traversal hardware can settle most of those hits instead.

- `RtOmm`: one micromap per block atlas layout (captured in `PbrAtlases.onAtlasUpload`), 4-state, one subdivision level for
  all (level 6 for 16 px sprites, capped by the device and 64 MiB): micromap triangles 0-2 are uniform (unknown, opaque,
  transparent), then 24 per non-opaque static sprite (the sprite mapped onto a triangle with each order of three of its
  corners). `rt_omm_data.comp` fills them from the atlas: a micro-triangle is opaque when every texel it may touch has
  alpha >= 0.1, transparent when none has, else unknown (the shaders alpha-test it as before).
- `rt_omm_index.comp` runs before each cutout section layer's BLAS build (vanilla and Sodium path) and picks one micromap
  triangle per triangle: whole-sprite quads by their corner order, partial quads uniform if their UV box agrees, everything
  else unknown. Animated sprites and the ids of an active skip list (`Manifest.Rt.anyHitIds`, the foliage the pack's shadow
  pass discards; converter 0.1.0-alpha.1.8) always stay unknown, so the shaders still decide them per id.
- Committed opaque micromap hits skip the candidate code, so the two near-hit rules that lived there were moved to the
  committed hit too: `rt_ao.comp` (cutout hits closer than 0.3) and the refraction hit AO.
- Option `rt.omm` (RT page tile "Opacity micromaps", default on, "Not supported by this GPU" without the extension);
  switching it rebuilds the terrain. `-Demrion.rt.ommDevice=false` creates the device without the extension.

Bug on the way: LWJGL does not write `usageCountsCount` when `pUsageCounts` is set on `VkMicromapBuildInfoEXT` /
`VkAccelerationStructureTrianglesOpacityMicromapEXT` (the count is shared with `ppUsageCounts`); with 0 the BLAS referenced
micromap triangles that did not exist and the GPU hung a few frames later. Both now set it explicitly.

**Image** (test world, 7 views of a built forest / props / pool area, RTX all on, DLSS Q + FG; on, off, on again): on vs
off differs no more than on vs on (MAE 0.4-4.4 vs 0.4-4.3, the pool's water noise the largest) — unchanged.

**Cost** (GPU done per frame, Reflex driver reports, 2 x 8 s each): forest interior 22.5 / 21.6 ms on vs 21.7 / 22.1 off;
canopy from above 26.5 / 26.1 on vs 25.7 / 26.3 off. No measurable gain in these scenes: the alpha tests were not where
the time goes (the pack's shading and the RTX lighting dominate). Memory: 28 MiB micromap + 4 B per cutout triangle
(about 14 MiB at render distance 16). Kept because it costs nothing measurable either and helps GPUs where any-hit
handling is expensive; `rt.omm=false` turns it off.

**Re-measured on top of the current development build (2026-11-16, after ReSTIR block light, refraction self-lighting, RR):** test
server, RTX all on (rtx style, full shadows, GI high, reflections, refraction, AO, caustics), DLSS Q + FG, Reflex on,
frameTimeCounter 100, 2560x1440, profiler on; 10 s per view, rounds on/off/off/on interleaved. New views on a floating
46x80 platform at y 150 (x 900-945, z 700-779: 88 mixed trees over short grass, torches) and a 29x39 kelp/seagrass
pool box at x 950-980 (script `omm2/build.s` in a scratch folder). GPU ms per frame (both rounds):

| View | on | off | on - off |
|---|---|---|---|
| forest (under the canopy, day) | 17.68 / 17.63 | 18.13 / 18.15 | -0.48 ms (-2.7 %) |
| forest at night (torches, ReSTIR) | 17.66 / 17.85 | 18.27 / 18.22 | -0.49 ms (-2.7 %) |
| kelp pool from above | 28.09 / 28.70 | 28.53 / 28.84 | -0.29 ms (-1.0 %) |
| canopy from above | 23.17 / 23.55 | 23.47 / 23.49 | -0.12 ms (noise) |
| water snapshot (14-41-55) | 36.02 / 36.20 | 36.32 / 36.29 | -0.20 ms (noise) |
| torch hall snapshot (no foliage) | 13.37 / 12.05 | 12.23 / 12.01 | first round was the warm-up |

Under the canopy the gain is real and repeatable (the spread between rounds is 0.05 ms): `prepare_a` (GI primary +
bounce rays) -0.25 ms, the gbuffers opaque pass (RTX shadow and block-light rays) -0.2 ms, `prepare_f` -0.09 ms. From above
and on water it is in the noise. Image: on vs off within the on vs on floor in every view (MAE forest 0.62-0.79 vs 0.62,
canopy 0.94-0.99 vs 0.92, kelp 2.3-3.2 vs 4.3, water 1.9-2.0 vs 1.9, hall 0.36 vs 0.36). The refraction hit AO ray took the
same near-hit rule in its new single-pass form (trace on behind a near committed cutout hit, at most 3 passes).

**Open:** entity BLAS (no micromaps), translucent layers (glass alpha 0 areas).

## 35. Final review fixes (2026-11-16)

- **BLAS scratch base alignment:** terrain and entity BLAS scratch addresses are aligned from the buffer's real device
  address (`minAccelerationStructureScratchOffsetAlignment`, with that much padding), not only their offsets, as the TLAS path did.
- **AO parameters in a UBO:** RT AO's 160 bytes of parameters moved from push constants (Vulkan guarantees only 128) to a
  uniform buffer with one slot per frame in flight (binding 7). With RR the AO filter copies the raw AO before loading its tile.
- **Entity atlas:** every texture the frame's entity draws use gets its rect before any draw takes its UVs; an atlas reset
  halfway used to hand rects of earlier draws to later textures. Frames without an entity capture still free old entity BLAS.
- **RR:** GI passes D and E return at once with `gi:filter=0` (C already wrote the result to F). Forest, reference config + RR:
  0.20 -> 0.02 ms; AO filter 0.05 -> 0.02 ms.
- **OMM:** the micromap data/triangle buffers and every section's OMM index buffer are freed once their build is recorded
  (build inputs only): indices 11.9 MiB -> 0 at the torch hall.
- Checked, no change: GI history with view bobbing (bobbing sits in the projection, `gbufferModelViewInverse[3]` is 0; history
  length with a fixed bob identical to without), and the 48-candidate guard in GI/sun-shadow ray loops (OMM off, forest from
  the ground, the canopy, low sun and the overview: never reached; 8 candidates are common, so the probe worked).
- Not done: skipping the terrain-only TLAS build before the shadow pass's entity refresh (0.20 ms) needs the plan to prove
  no pass reads the TLAS in between.

## 36. RTX block light: emitters set flush into a surface (2026-11-16)

**Bug.** A cave lit only by a lava pool in its floor was much darker with RTX than RT off (frame luma 20.1 vs 31.5). The
pool's top (8/9 of a block) lies just below the floor, so every lava centre sat half a block behind the floor plane: U
(§23 gate open, nothing in front explains the light) counted the whole pool, but S was ~0, since every shadow ray dipped into
the floor. Same for any full-block vanilla emitter flush with a surface: glowstone in a wall, sea lanterns or magma in a floor.
Not the cause: lava's colour/power (vanilla, power 1), cell caps (the full list has none), lava in shadow rays (lava is opaque solid-BLAS geometry, so it does block them,
but the ray ends where it enters the source's block, so a pool's own lava never shadows its light; the `id == lava` skips in
the ray loops never ran and were removed in a later review fix).

**Fix** (`rtx/bl_common.glsl emrBlSourceAt`, `emrBlOpen`, `rtx/bl_restir_a.csh`). A full vanilla emitter whose centre is
0.05-0.7 behind the receiving surface lights it like a pack-only ore does: from the air half a block in front of its block's
nearest point, with a disk of radius 0.45 (0.28 x 1.6) so it stays in front of the surface. In U such a "flush" source is gated
by the sources in front (§23 rule), not added in full: a flush emitter in the next room's floor, behind a wall, darkened the
torch hall's floor by up to 15 % (frame -3 %). It does explain the lightmap for sources further behind: the test pool's lava
falls on below the floor, and those blocks, gated in, dimmed the floor again (lava frame 29.6 -> 25.6).

**Measured** (test world, reference config: RTX, GI high, full shadows, AO, block light 4, DLSS Q; frame mean luma, RT off /
before / after with RR / after without RR; scratch folder `lavalight/`):

| View | RT off | before | after (RR) | after (no RR) |
|---|---|---|---|---|
| lava cave, video shot key 1 | 31.5 | 20.1 | 29.4 | 29.5 |
| lava cave, key 2 | 36.3 | 26.6 | 33.9 | 34.0 |
| sea lantern floor + magma (new room, x 1446-1468 y 150-162) | 27.6 | 18.1 | 27.2 | 27.4 |
| glowstone wall (new room, x 1420-1442 y 150-162) | 19.3 | 25.3 | 36.5 | 37.6 |
| torch hall 13-28-25 | 65.3 | 65.3 | 65.6 | 65.5 |
| hall 14-38-35 | 31.7 | 38.1 | 38.3 | 38.6 |
| repeater rows 14-38-57 | 43.0 | 38.4 | 39.2 | 39.6 |

The wall around the glowstone went from 32 to 50 (RT off 55). The glowstone room as a whole is too bright, already before
(+31 %): with RTX GI's block bounce off (`gi=off` or `gi:blockStrength=0`) it is 21.5 (+11 %), so the excess is GI's bounce
and glow from a 45-block emitter wall (`gi:blockStrength` 2.5 was fitted on halls with point lights); not changed here.

## 37. RTX GI: emitter walls and floors no longer over-light the room (2026-11-16)

**Bug.** The glowstone-wall room (45 glowstone blocks in a brick wall) was +96 % brighter than RT off (frame 37.1 vs
18.9); with GI's block bounce off it is +11 %. Ablations on the bounce hit (`rtx/gi_common.glsl emrGiBlockLight`), glowstone
room: the light block's own glow (`gi:glow` 2.0) and the light "arriving" at a light block's face made up 12 of the 18 luma
too many, the summed light of 45 sources at ordinary wall and floor hits most of the rest.
- *Glow at a hit on a light block:* a second estimate of direct light. RTX block light (ReSTIR, §32) lights every surface from
  every emitter already; GI hitting the emitter added it again, scaled by the solid angle the emitter covers, so it grew with
  the emitter's area and lit the side walls 10–20 blocks away (RT off: dark).
- *Light arriving at a light block's face:* since §36 a full emitter flush with a surface lights it from the air in front,
  and that includes the emitter's own face and its neighbours in the same wall or pool. Each glowstone or lava face then
  reflected roughly its own brightness again, a third copy of the emission.
- *Many co-located sources:* the bounce sums every source's unshadowed weight, but the pack's brightness follows vanilla
  light, the max over sources. 45 glowstones in one wall light a floor point 10 blocks away about like one block at 2 blocks.

**Fix.** A bounce hit on a vanilla light block (its face or a torch's model) adds nothing; the glow term and the `gi:glow`
option are gone. At other hits, full-block vanilla emitters (glowstone, lava, sea lantern, magma, lamps; the ones that
tile into walls, floors and pools) count as the strongest of them (`emrBlPick` returns their max and sum; the drawn sample is
scaled by max / sum when it is one of them, which keeps the estimator unbiased for that target). Torches, lanterns and the
pack-only sources are summed as before (`gi:blockStrength` 2.5 was fitted on that; pack-only floodfills do add up in the pack).
No new constant; `gi:blockStrength` 3 was tried (torch hall 0 %, but hall, stems, ore cave 2–4 points further off).

**Measured** (test world, reference config: RTX, GI high, block light 4, 2560x1440; the dev runs upscale with FSR 3 from 1706x960,
not DLSS; frame mean luma vs RT off, and
the mean absolute error against RT off on an 8x8-downsampled frame; scratch folder `giwall/`, run `g`):

| View | RT off | before | after | MAE before / after |
|---|---|---|---|---|
| glowstone wall (x 1420-1442) | 18.9 | 37.1 (+96 %) | 21.4 (+13 %) | 18.9 / 4.8 |
| sea lantern + magma floor (x 1446-1468) | 27.2 | 27.0 (−1 %) | 23.4 (−14 %) | 6.2 / 4.0 |
| lava cave, key 1 | 31.1 | 29.6 (−5 %) | 23.9 (−23 %) | 7.6 / 7.2 |
| lava cave, key 2 | 35.9 | 34.6 (−4 %) | 28.8 (−20 %) | 7.7 / 7.2 |
| torch hall 13-28-25 | 64.6 | 64.9 (+0 %) | 63.0 (−3 %) | 14.2 / 13.9 |
| hall 14-38-35 | 31.3 | 38.2 (+22 %) | 36.7 (+17 %) | 9.2 / 8.7 |
| repeater rows 14-38-57 | 42.5 | 39.1 (−8 %) | 36.5 (−14 %) | 7.6 / 7.8 |
| stems room (§32) | 18.4 | 23.1 (+25 %) | 21.2 (+15 %) | 4.7 / 2.9 |
| ore cave (§32) | 35.7 | 41.2 (+15 %) | 41.1 (+15 %) | 6.4 / 6.3 |

The lava cave and the sea-lantern room were right on average before only by cancelling errors: the emitter's GI copies lit
the ceiling and back walls far above RT off (lava top third 26.0 vs 18.2, now 13.4; sea 22.7 vs 14.0, now 13.3) while the floor
around the pool stays darker than RT off with or without GI (lava bottom third 31.0 RT off, 24.6 before, 23.6 after). That
floor deficit is direct light, not GI: a floor point sees a source flush in the same floor at a grazing angle (wrapped N.L
about 0.4), where the pack's light has no direction. Open: that floor deficit (frame −20 % in the lava cave); the repeater
rows lose 3 points (their full-block lamps now count as one).

*Flush floors (2026-11-16, same change).* The floor deficit around flush emitters is fixed in direct light: a floor or
ceiling receiver of a flush emitter (`emrBlSourceAt`'s shrunk disk) takes N.L at least `EMR_BL_FLUSH_NDL` 0.6
(`rtx/bl_common.glsl emrBlWeight`, so ReSTIR, the gbuffers' fallback and GI's bounce hits agree). It saw the source at a grazing
angle (wrapped N.L about 0.4), where the pack's light has no direction. Walls are left out: the glowstone wall's own surface
was already brighter than RT off (centre third 41 vs 36), and the same floor on walls took the room to +20 %. 0.6 is fitted on
three new single-block cells (9x9 white concrete, x 816-850 y 149-156 z 699-711: lava, glowstone, sea lantern flush in the floor;
camera x+0.5 153 700.6, yaw 0, pitch 45); 0.7 overshot them by 5-11 %, 0.5 left the lava cave at -18 %. Tried and dropped:
counting only touching full emitters as a cluster (a CPU flag per source): no change anywhere, the repeater view's lamps touch.

Same views, frame mean vs RT off (before = `266e072`, first §37 commit `013e83f`, now) and pixel error (8x8 mean abs, luma):

| View | RT off | before | 013e83f | now | error before / now |
|---|---|---|---|---|---|
| lava cave, key 1 | 30.8 | 29.6 (−4 %) | 23.7 (−23 %) | 26.8 (−13 %) | 7.7 / 4.2 |
| lava cave, key 2 | 35.2 | 34.6 (−2 %) | 28.0 (−20 %) | 30.4 (−14 %) | 8.3 / 5.0 |
| glowstone wall | 18.9 | 37.1 (+96 %) | 21.4 (+13 %) | 21.4 (+13 %) | 18.9 / 4.8 |
| sea lantern + magma floor | 27.2 | 27.0 (−1 %) | 23.5 (−14 %) | 26.6 (−2 %) | 6.2 / 0.9 |
| torch hall 13-28-25 | 64.6 | 64.9 (+0 %) | 63.0 (−3 %) | 63.0 (−3 %) | 14.2 / 13.9 |
| hall 14-38-35 | 31.4 | 38.2 (+22 %) | 36.7 (+17 %) | 36.8 (+17 %) | 9.3 / 8.8 |
| repeater rows 14-38-57 | 42.5 | 39.1 (−8 %) | 36.5 (−14 %) | 36.5 (−14 %) | 7.6 / 7.8 |
| stems room | 18.4 | 23.1 (+25 %) | 21.2 (+15 %) | 21.2 (+15 %) | 4.7 / 2.9 |
| ore cave | 35.7 | 41.2 (+15 %) | 41.1 (+15 %) | 41.2 (+15 %) | 6.4 / 6.3 |
| cell: torch | 71.3 | | 73.0 (+2 %) | 73.0 (+2 %) | |
| cell: flush lava | 72.1 | | 64.3 (−11 %) | 71.6 (−1 %) | |
| cell: flush glowstone | 72.9 | | 68.2 (−7 %) | 76.3 (+5 %) | |
| cell: flush sea lantern | 74.5 | | 68.5 (−8 %) | 76.9 (+3 %) | |

Open: the lava cave's walls and ceiling (top third 13.4 vs 18.1 RT off, short mostly in red; the same with GI's block bounce
off, and N.L does not move it): not flush geometry, probably the pack's extra lava light (`LAVA_ACT_ALPHA` 0.8, a strong red
floodfill) against RTX's plain vanilla lava. The repeater rows' −14 % comes from the touching full-block emitters counting as
one at GI bounce hits (−8 % before); their pixel error is unchanged (7.6 / 7.8).

*Lava (2026-11-16, same change).* After the flush fix the lava cave was still −13 % (key 1), its walls and ceiling −21 to
−27 %, losing the orange glow on the pool's far walls, the pillar fronts and the chest (surfaces facing away from the pool or
in its shadow; RT off lights them through the pack's floodfill, RTX only through GI's bounce). Two causes:
- *The cluster rule took lava's bounce away.* It is right for emitters the pack lights like vanilla (the max over sources), but
  the pack gives lava extra coloured light (`blocklightColorsACT.glsl`: `LAVA_ACT_ALPHA` 0.8, magma 0.1; glowstone, sea lantern,
  lamps and torches 0) and its floodfill sums that over every block of a pool. Sources the pack sums are left out of the cluster
  rule (`LightColors.packSums`, marked by source radius 0.44 in the full list, `RtLights`). Swept first with a temporary knob
  (lava cave key 1, top third): rule on −10 % / −21 %, half −6 % / −11 %, off −3 % / −3 %; the glowstone room needs it on
  (+13 % on, +32 % off), which the pack's alpha separates. The alpha is hard-coded like the pack-only sources' alphas next to it;
  a lift does not reach it (a global initialiser behind an `#ifdef`, outside the GI pass's stage).
- *Lava lit from its block centre*, half a block below the floor: rays from the walls to it crossed the floor's lip at shallow
  angles. It now lights from its surface, 8/9 up its block (top third −27 → −21 % with the rule still on).
The pack's colour and strength for lava need nothing: RTX multiplies the pack's own light (floodfill included) by S / U, in which
a source's colour and power cancel.

Final table, DLSS Quality with Ray Reconstruction (the local `rr/dlss` libraries via `-Demrion.upscale.dlssDir`; the runs
above were FSR 3), frame mean and top third (walls, ceiling) against RT off, pixel error (scratch folder `giwall/`, run `n`):

| View | RT off | now | top third RT off / now | error |
|---|---|---|---|---|
| lava cave, key 1 | 31.3 | 30.3 (−3 %) | 18.5 / 18.0 (−3 %) | 2.9 |
| lava cave, key 2 | 35.7 | 33.9 (−5 %) | 20.5 / 19.7 (−4 %) | 3.4 |
| glowstone wall | 19.3 | 21.8 (+13 %) | 24.9 / 24.8 | 4.8 |
| sea lantern + magma floor | 27.6 | 27.2 (−1 %) | 14.3 / 13.9 | 0.9 |
| torch hall 13-28-25 | 65.3 | 63.9 (−2 %) | 39.0 / 45.6 | 13.9 |
| hall 14-38-35 | 31.7 | 37.0 (+17 %) | 23.9 / 33.0 | 8.8 |
| repeater rows 14-38-57 | 43.0 | 36.8 (−14 %) | 34.6 / 32.2 | 8.0 |
| stems room | 18.8 | 21.8 (+16 %) | 22.3 / 25.8 | 3.0 |
| ore cave | 36.3 | 42.2 (+16 %) | 18.2 / 26.0 | 6.7 |
| cell: torch | 72.0 | 74.3 (+3 %) | | 6.8 |
| cell: flush lava | 72.7 | 75.9 (+4 %) | | 4.7 |
| cell: flush glowstone | 73.6 | 77.6 (+5 %) | | 5.3 |
| cell: flush sea lantern | 75.1 | 78.1 (+4 %) | | 5.1 |

Against the flush-floor state under DLSS + RR (same views): glowstone, sea, torch hall and the glowstone / sea cells within
0.2 points; the lava cell +3.2 (the surface lift). The hall, stems room and ore cave are as they were before §37 or better
(+22, +25, +15 %).

## 38. Snapshot fixes: caustics on walls, block-entity torch shadows, ice, portal reflections (2026-11-16)

From reported F9 snapshots of 2026-11-16, replayed with the `snapshot` step on the test world.

- **Caustics striped across riverbed walls** (18-10-20, "von der Seite komisch abgeschnitten"). The RT caustic
  (`caustics.glsl`) is the light pattern on the receiver; on a face the sun grazes it is that pattern smeared along the
  light, and the pack still lights such a face (its side shading, N.L + 0.4), so the walls of every step facing away from
  the sun azimuth showed bright diagonal stripes cut off at each block edge. The caustics effect now also patches the pack's
  `DoLighting` (`caustics_ndotl.glsl`: the receiver's geometric N.L) and fades the caustic contrast out between N.L 0.5 and
  0.15. Top faces and walls facing the sun keep it. The effect is limited to the Overworld and the End (the Nether's
  programs have `DoLighting` but no shadow functions).
- **Block entities cast no shadow from torches** (20-05-55). Chests, signs, beds and the other block entities were already in
  the TLAS (mask 0x04, with the entities, §14) and cast sun shadows with *Entity shadows*, but RTX block-light visibility rays
  (`rtx/bl_common.glsl emrBlTransmit`) traced terrain only (0x03). With *Entity shadows* on, the request now carries
  `blocklight:entities` and the rays trace 0x07 (entities alpha-tested in their own atlas). The GI's block light at a
  bounce stays terrain-only (`emrBlMask`, defined by each includer).
- **Dark triangles in sea ice** (20-13-45/53). Translucent surfaces are never GI surfaces (the GI primary traces 0x05), so an
  ice sheet pixel took the nearest GI texel facing the same way from `rtx/gi_apply.glsl`'s fallback ring, within
  0.5 + 0.25 d along the view ray: next to an iceberg that was the GI of its steps above and of the sunken ones below,
  in triangles where one ring sample matched and the next did not; it changed as soon as the camera moved. The ring now also
  requires the texel to lie in the receiver's plane (within 0.6 + 0.01 d blocks).
- **Nether portal missing from reflections** (20-31-41). Reflection rays traced solid and cutout terrain only; the portal is
  translucent and was never in the pack's reflection voxels either. `emrRtReflHit` now traces 0x03 and accepts, of the
  translucent hits, only the portal (block id 30020), which `refl_trace.glsl` shades as its own glow (the pack's
  `netherPortal.glsl` colour curve, emission set to sit a little below the portal seen directly).

Measured (test world, 2560x1386, the snapshots' config: RTX, DLSS Quality + frame generation, block light 8 rays): a closed
stone room with 190 chests and 15 torches, rendered FPS 84.3 with *Entity shadows* (torch shadows of the chests) and 84.5
without (20 s each, same run). The calibration snapshots (torch hall 13-28-25, hall 14-38-35, cold ocean 14-41-55) differ
from the unpatched build by chat overlay, torch flicker and kelp sway only (MAE 0.5-1.6).

## 39. RTX GI radiance cache (2026-11-17)

A world-space radiance cache for the RTX GI after NVIDIA's SHaRC ("Spatially Hashed Radiance Cache", RTXGI SDK). The algorithm
is reimplemented in our GLSL; no SDK code is used. Option *GI radiance cache* on the RT page (`rt.giCache`, request
`gi:cache`, RTX style with GI on only), **default on**.

**How it works** (`rtx/gi_cache.glsl`, `gi_cache_update.csh` = `prepare_j`, `gi_cache_resolve.csh` = `prepare_k`):
- A hash grid of 2^20 entries (`emrGiCk` check hashes, `emrGiCs` this frame's fixed-point sums, `emrGiCr` resolved light;
  about 68 MB). Key: voxel of `cacheVoxel` 0.5 blocks up to `cacheNear` 16 blocks from the camera, doubling twice with
  distance (at most 2 blocks, so one-block walls never share a voxel with both sides), block-aligned, plus the face direction.
  Linear probing over 8 slots; insert by `imageAtomicCompSwap`.
- Update: one short path per 8x8 render pixels (random pixel each frame). From the camera's surface two cosine bounces; each
  of the two vertices gets one sample of the light arriving there (direct sun, sky lightmap and one block-light draw, plus
  `cacheBounce` x what its own bounce brought back); the third hit takes its light from the cache. So bounces accumulate
  over frames, also off screen. Same units as the GI channels (A sun, B sky, C block light).
- Resolve: per entry, this frame's samples are blended into at most `cacheFrames` 32 frames of history; entries without a
  sample for `cacheStale` 96 frames are evicted.
- Use: `gi_trace.csh`'s bounce hit (`emrGiBounceRtx`) takes the cached light times its own albedo where the voxel has
  `cacheMin` 4 samples, instead of its shadow ray, block-light ray and last frame's screen-space GI. Not for hits closer
  than the voxel (corners would average light from behind the ray), not inside RtLights' light-change box.
- Lights placed or removed: inside the change box the trace skips the cache, and a sample from there makes the resolve
  drop the entry's history (the WIP version kept 3 frames of it, which left a fading copy of a removed torch).

**Review of the paused WIP (b694062).** Sound overall. Changed: the change-box history drop above; `cacheBounce` as its own
option (the WIP reused `gi:bounce` 0.85, see calibration); GUI tile, docs, converter version.

**Calibration.** With the cache's bounce at 0.85 the views were 1-4 points brighter than without (hall +17 -> +21 %, ore
cave +16 -> +20 %, cells +3..5 -> +6..9 %; torch hall and repeater rows closer to RT off), sum of the pixel errors 74.6 ->
78.5. Swept `cacheBounce` (frame mean vs RT off, DLSS Quality + RR, 2560x1440, scratch folder `sharc/`, runs `ab`, `d`):

| View | RT off | cache off | 0.85 | 0.5 | **0.25** | 0 |
|---|---|---|---|---|---|---|
| lava cave, key 1 | 31.3 | −4 % | −3 % | −4 % | −5 % | −5 % |
| lava cave, key 2 | 35.7 | −7 % | −7 % | −7 % | −8 % | −8 % |
| glowstone wall | 19.3 | +14 % | +13 % | +14 % | +13 % | +13 % |
| sea lantern + magma floor | 27.6 | −1 % | −1 % | −1 % | −2 % | −2 % |
| torch hall 13-28-25 | 65.3 | −2 % | +1 % | −1 % | −2 % | −3 % |
| hall 14-38-35 | 31.7 | +17 % | +21 % | +19 % | +18 % | +16 % |
| repeater rows 14-38-57 | 43.0 | −14 % | −9 % | −12 % | −14 % | −15 % |
| stems room | 18.8 | +16 % | +17 % | +16 % | +14 % | +14 % |
| ore cave | 36.3 | +16 % | +20 % | +17 % | +15 % | +13 % |
| cell: torch | 72.0 | +3 % | +6 % | +4 % | +3 % | +2 % |
| cell: flush lava | 72.9 | +4 % | +6 % | +5 % | +4 % | +3 % |
| cell: flush glowstone | 73.8 | +5 % | +9 % | +7 % | +5 % | +4 % |
| cell: flush sea lantern | 75.3 | +4 % | +7 % | +5 % | +4 % | +3 % |
| sum of pixel errors (8x8) | | 74.6 | 78.5 | 75.8 | 72.8 | 72.6 |

`cacheBounce` 0.25: every view within 1 point of the cache-off numbers, no new constant elsewhere (`gi:blockStrength`
unchanged). DLSS Quality without RR (run `h`): cache on/off within 1 point in every view, too. The Nether renders the same
with and without it.

**Noise** (static view, 8 frames 0.2 s apart, ticks frozen; mean per-pixel luma std over time, full resolution / 4x4
blocks; run `i`, DLSS Quality + RR):

| View | RT off | cache off | cache on |
|---|---|---|---|
| torch hall | 0.23 / 0.05 | 0.28 / 0.17 | 0.23 / 0.10 |
| hall | 0.22 / 0.05 | 0.19 / 0.11 | 0.15 / 0.07 |
| stems room | 0.22 / 0.05 | 0.15 / 0.09 | 0.14 / 0.08 |
| ore cave | 0.23 / 0.05 | 0.22 / 0.13 | 0.22 / 0.13 |
| cell: flush lava | 0.21 / 0.06 | 0.12 / 0.07 | 0.11 / 0.06 |

The cache takes out a third to 40 % of the low-frequency GI shimmer where bounce light matters (torch hall, hall), and
changes nothing measurable elsewhere. RR still gets per-pixel noisy GI (the primary bounce rays stay per pixel); only the
bounce hit's light is cached.

**FPS** (`gpu.sh exclusive`, 2560x1440, DLSS Quality + RR, GI high, 20 s each, two repeats, avg / 1 % low):

| View | cache off | cache on |
|---|---|---|
| hall 14-38-35 | 63.9 / 46.2, 67.6 / 51.2 | 67.0 / 51.4, 68.7 / 48.0 |
| stems room | 93.5 / 72.0, 92.3 / 68.4 | 93.8 / 78.7, 92.7 / 77.8 |
| lava cave | 54.8 / 47.4, 55.4 / 48.7 | 55.4 / 44.6, 56.1 / 48.1 |
| outdoors (580 75 590) | 78.3 / 66.1, 77.9 / 65.8 | 77.8 / 65.8, 77.5 / 65.3 |

Equal within the run-to-run spread (the hall +2-5 %): the update paths cost about what the skipped shadow and
block-light rays at cached bounce hits save.

**GI lag** (torch placed / removed in the lava cell, every frame recorded, two repeats each): time to 50 % / 90 % of the
change after the `setblock` was sent: placed 354-429 / 1463-1496 ms without, 371-425 / 1388-1499 ms with the cache; removed
145-165 / 730-797 ms without, 162-169 / 808-840 ms with. Same within the network jitter. Motion (turn 1.5 degrees per
frame for 40 frames, hall and stems room): the last frames differ by 0.55 / 0.21 mean luma, no trails either way. No
light leaking through walls seen in the crops (hall, cells, ore cave).

## Presets (`rt/RtPreset`, 2026-11-16)

The RT page has a preset row: **Low / Medium / High / Ultra**, plus *Custom*, which lights up as soon as an option no longer
matches a preset. A preset sets only the `rt.*` quality keys; `rt.enabled` and `rt.omm` stay as they are. `rt.preset` is
written next to them for information only: on load, the preset is derived from the values.

| Preset | style | shadows | GI | block light | AO | reflections | refraction | caustics | trace distance | upscaler on first start |
|---|---|---|---|---|---|---|---|---|---|---|
| Low | faithful | hybrid, 1 ray, no entities | off | off | off | on | off | off | 8 chunks | Performance |
| Medium | rtx | full, 4 rays | low | Low (2) | off | on | off | off | 12 chunks | Balanced |
| High | rtx | full, 4 rays | medium | Low (2) | on | on | off | on | 16 chunks | Quality |
| Ultra | rtx | full, 4 rays | high | Low (2) | on | on | on | on | render distance | Quality |

**First start:** when `config/emberlight.properties` has no `rt.enabled`, the GPU picks a preset (`RtPreset.TABLE`: vendor ID
plus a regex on the device name; unknown RT GPUs by VRAM, at most High; laptop parts one tier lower; under 8 GB or integrated:
Low; no ray query: RT stays off). If no upscaler was ever chosen, it also turns on DLSS (NVIDIA) or FSR (others) at the
preset's upscaling level. Then it saves everything, so this runs only once. Autotests skip it unless `-Demrion.rt.autoPreset=true`.
Script step: `rtset preset=high[,key=value...]`.

Calibration: RTX 4070 Ti, 2560×1386, DLSS Quality, frame generation off, Unbound + Euphoria with the reference settings,
15 s per bench (avg FPS / 1% low):

| Scene | Ultra | High (with refraction, r1) | Medium | Low | RT off |
|---|---|---|---|---|---|
| torch hall (snapshot 13-28-25) | 68.7 / 27.9 | 107.8 / 85.4 | 112.2 / 69.5 | 142.3 / 28.9 | 193.1 / 45.1 |
| shallow water (snapshot 14-41-55) | 27.8 / 25.7 | 30.8 / 28.1 | 62.5 / 54.1 | 122.1 / 85.8 | 117.3 / 99.8 |
| forest (scene `forest`) | 76.1 / 60.7 | 94.1 / 81.7 | 100.1 / 88.4 | 168.7 / 139.8 | 170.9 / 141.1 |

Over water, refraction is the expensive option. High in water: 30.8 FPS, without refraction 48.9, without caustics 37.1,
with GI Low 31.3; Medium with refraction 38.5 against 60.4 without. So High leaves refraction off and only Ultra has it. That
puts the 4070 Ti (High) at about 50 FPS or more in all three scenes. Ultra is for the RTX 4080/4090/5080/5090 class.
