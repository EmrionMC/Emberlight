# Emrion Shaders — Optional hardware ray tracing (M7 design)

Status: RT-1 (infrastructure) implemented and measured on `m7/rt1-infra` (2026-11-13, §9); RT-2 (variants, patch table, UI,
toggle) and RT-3 (RT shadows, Hybrid and Full) implemented and measured on `m7/rt2-shadows` (2026-11-13, §10); RT-4/RT-5 are design. This document answers *how* M7 plugs hardware ray tracing (RT) into
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

## 2. Acceleration structures (`mod/.../rt/`)

New package `dev.emrion.shaders.mod.rt`:

| Class | Role |
|---|---|
| `RtCapability` | Static: was the "Emrion RT" feature set enabled at device creation (set from a `VulkanBackend.createDevice` HEAD inject that checks `featureSet.contains(EMRION_RT)`), plus the `VkPhysicalDeviceAccelerationStructurePropertiesKHR` limits (scratch alignment). Loads the KHR function pointers through `VkDevice` capabilities (LWJGL does this automatically). |
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
- **Budget.** At most N triangles per frame (default 2 M, about 1 ms). The rest waits for the next upload hook or the next
  `levelStart`. A section without a BLAS yet is simply missing from the TLAS for a frame or two (no shadow there), which is
  invisible during normal play and acceptable during the initial load.
- **Scratch:** one 64 MiB scratch buffer (aligned to `minAccelerationStructureScratchOffsetAlignment`) used as a ring inside a
  batch, with a barrier (`AS_BUILD write → AS_BUILD read/write`) when it wraps. Builds go out as batches of
  `vkCmdBuildAccelerationStructuresKHR` with many infos.
- **Compaction (step 5):** a query pool `ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR` is read two frames later (like
  `GpuQueryPool`), then `vkCmdCopyAccelerationStructureKHR(COMPACT)` into `AccelPool`, and the uncompacted BLAS is released
  through `VulkanContext.destroyLater`. On NVIDIA, compaction typically saves about 50 % for static triangle geometry.
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
- Headroom: the measured peak with 256x+PBR is already 11.96 GB of 12 GB (PROGRESS risks; M5b is working on it). RT
  reflections pay for themselves when they let the converter drop WSR's `bufferObject.0` (**810 MB**, read only by
  `getFaceData`, `lib/voxelization/reflectionVoxelData.glsl:47`) plus `wsr_img`/`wsr_lod_img` (≈ 34 MB) (§4.2). Until then,
  RT shadows add memory, and `RtScene` logs its total in the F7 overlay. An RT-distance option (default = render distance)
  limits the BLAS set if needed.

### 2.5 Entities

- **v1: not in the TLAS.** Entity vertices are per-frame `StagedVertexBuffer` data in `EMRION_ENTITY`. Tracing them needs a
  per-frame BLAS (`PREFER_FAST_BUILD`, one BLAS per frame over all world entity draws) and the usage flags on MC's per-frame
  buffers. That is step 5. Until then, entity shadows come from the shadow map in "hybrid" shadow mode (§4.1), so nothing is
  lost by default.

### 2.6 TLAS per frame

- Built at `FrameExecutor.levelStart`, in the raw command buffer it already records, before the shadow pass and the gbuffers.
  It is a full rebuild every frame (`PREFER_FAST_BUILD`): the instance set changes with uploads and camera movement, and about
  20 k instances build in roughly 0.1–0.3 ms. One TLAS object of fixed capacity (grown and re-created only when the instance
  count exceeds it) keeps the set-1 descriptor **static**: it is written once into every pre-built flip-state descriptor set.
- **Camera-relative transforms.** For each (section, layer) BLAS, the instance transform is a pure translation
  `sectionOrigin - cameraPosition`, computed in **double** on the CPU and then stored as float. TLAS space therefore equals the
  pack's `playerPos` space (`playerPos = world - cameraPosition`), so a pack ray starts at `playerPos` with no conversion and no
  precision loss far from the origin.
- `instanceCustomIndex` (24 bit) = slot in the `EmrionRtSections` SSBO (`uvec2 vertexAddr; uint layer; uint pad`).
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

### 3.4 Option interplay (Complementary / Euphoria, user settings: SHADOW_QUALITY=3, shadowDistance=256, TAA, WORLD_SPACE_REFLECTIONS=1, COLORED_LIGHTING=512, ENTITY_SHADOW default 1)

| Option / feature | Where | RT handling |
|---|---|---|
| `SHADOW_QUALITY` 0 / 1 / ≥2+TAA | `GetShadow` picks `SampleBasicFilteredShadow` / `SampleFilteredShadow` / `SampleTAAFilteredShadow` (`shadowSampling.glsl:132-138`) | All three are patched. Ray count = `shadowSamples` (2/4/8/16 for 2–5), capped by the "RT shadow rays" option (default 1 with TAA, since the pack's TAA accumulates). `SHADOW_QUALITY=-1` → the effect is unavailable (the pack has no shadows). |
| `shadowDistance`, `shadowLength` gate | `mainLighting.glsl:266-268` (inside the big lighting function, not a patch point) | Unchanged in v1: RT shadows cover the same distance as the map. A later option could raise `shadowDistance` in the RT variant only, but that also changes the map extent, so it is not in v1. |
| Translucent coloured shadows (`shadowcolor0`, `shadowtex1` vs `shadowtex0`) | `SampleShadow` (`shadowSampling.glsl:11-27`) | Kept from the shadow map (see hybrid, §4.1). |
| Water caustics | Written into `shadowcolor0` by `program/shadow.glsl:127-156` | Kept from the shadow map (same mechanism). |
| `ENTITY_SHADOW` | Entities drawn into the shadow map | Hybrid: from the map. Full: none until step 5 (entity BLAS). |
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

- **Config:** `config/emrionshaders.properties` (already used by `PackManager.CONFIG`), never the Iris settings `.txt`, which
  stays 1:1 with Iris. Keys: `rt.enabled=false` (master), `rt.shadows=hybrid|full|off`, `rt.reflections=true|false`,
  `rt.ao=false`, `rt.shadowRays=1`, `rt.distance=0` (0 = render distance), `rt.device=true`.
- **Options screen:** `ShaderOptionsScreen` gets an extra top-level page **"Emrion RT"**, injected by us and not part of the
  pack's screen tree. It shows the master switch, the per-effect cycles, and a status line per effect: "unsupported GPU"
  (`!RtCapability.enabled`), "unavailable: <manifest reason>", "active", or "BLAS: 0.6 GB, 14 210 sections". Apply writes
  only our config and starts the reconvert.
- **Key:** `key.emrionshaders.rt` (default unbound; F7 = profiler, R = reload, O = packs) toggles `rt.enabled`. It is
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
| **RT-1 Infrastructure** | "Emrion RT" `FeatureSet` in `VulkanFeatureSetsMixin` (3 extensions, 3 features). `RtCapability` (+ `createDevice` HEAD inject). `VulkanBackendVmaMixin` (BDA flag). `UberGpuBufferRtMixin` + `VulkanConstMixin.bufferUsageToVk`. `rt/` package: `SectionTracker` (mixins on `RenderSection.vertexBufferUploadCallback`, `releaseSectionMesh`, `uploadTerrainBuffersToGpu` TAIL), `AccelPool`, `RtScene` (BLAS per section/layer, TLAS per frame, camera-relative), `RtDebugView` + `-Demrion.rt.debug`, F7 counters. Built only with `-Demrion.rt.debug` in this step (no pack changes). | Debug view matches the raster image (incl. cutout holes and section updates after break/place) at RD 16. Validation layer clean. RT-off screenshots MAE 0 vs. master. FPS with RT device setup within noise vs. `rt.device=false`. Real triangle count, BLAS memory and build times logged into this doc. | 5–7 days |
| **RT-2 Variants, patch table, UI** | `Converter.Request.rt`, `CacheKey`, manifest `rt` + `acceleration_structure` resource, `SpirvReflection` AS type, transpiler `#version 460` + extensions for patched stages, `RtPatchTable` + `RtPatcher` + `rt/emrion_rt.glsl` helpers. Mod: config keys, "Emrion RT" page, key, reconvert + swap creating/destroying `RtScene`, `Descriptors` AS writes. | With `rt={shadows}` the target pack converts (253+ stages, `spirv-val` clean), and the report lists the patched stages. A deliberately broken table yields "unavailable" plus byte-identical off output. Toggling in game swaps runtimes 5× without VRAM growth. RT-off golden-hash test passes. | 4–5 days |
| **RT-3 Shadows** | Complementary shadow patches (3 functions), inverse shadow mapping, cone jitter, alpha test, skip ids, Hybrid/Full modes. | Comparison scenes (overview, forest, cave, sunset, entities, water) look right in both modes (contact shadows, coloured glass and caustics kept in Hybrid). Perf delta measured and documented in BENCHMARK.md. No stale shadows after block edits. | 4–6 days |
| **RT-4 Reflections** | (a) `voxelRayTrace` patch with pack shading. (b) `getFaceData`/`storeFaceData` patches, vertex-based face data, unreferenced-resource elimination for `bufferObject.0`/`wsr_img`, hits outside the voxel volume. | (a) Water and polished-block reflections show exact slab/stair/plant geometry with otherwise unchanged look. (b) VRAM drops by about 850 MB with RT reflections on, and reflections beyond the voxel volume work. | (a) 3–4 days, (b) 4–5 days |
| **RT-5 Polish + AO** | BLAS compaction, entity BLAS per frame (Full shadows with entities, entity reflections), `EmrionRtAoPass` + `GetAmbientOcclusion` patch, then a GI design. | Compaction halves BLAS memory. Entities cast RT shadows. RTAO replaces SSAO with no halos at depth edges, temporally stable. | 1–2 weeks |

Order rationale: RT-1 can be built and verified without touching the converter or the pack (debug view only). RT-2 can be
verified without any visible RT effect (patch plumbing, toggling, bit-identity). Each later step adds one effect behind its
own switch.

## 9. RT-1 results (2026-11-13, `m7/rt1-infra`)

### 9.1 What is built

| Piece | Where |
|---|---|
| "Emrion RT" `FeatureSet` (3 extensions, `accelerationStructure`, `rayQuery`, `bufferDeviceAddress`), requested unless `-Demrion.rt.device=false` | `rt/RtCapability`, `VulkanFeatureSetsMixin` |
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

## 10. RT-2 and RT-3 results (2026-11-13, `m7/rt2-shadows`)

### 10.1 What is built

| Piece | Where |
|---|---|
| `Converter.Request.rt` (`Set<String>`: `shadows`, `shadows:full`, `shadows:rays=N`). The cache key gets `rt=<sorted set>` plus a fingerprint of the RT tables and bodies **only when the set is non-empty**, so editing an RT body invalidates RT caches without a `VERSION` bump that would change the RT-off key | `Converter`, `CacheKey`, `RtPatchTable.fingerprint` |
| Patch table (our JSON, no pack code): detect files, per effect `requires`, `options` (emitted as `const emrRt_<effect>_<option>`), helper library, built-in uniforms declared only if missing, `needs`, foliage skip ids (only when the dimension's shadow pass discards them), patches by signature | `converter/src/main/resources/rt/complementary-r5.json` |
| `RtPatcher`: parses the preprocessed stages, matches `returnType name(paramTypes)`, checks `needs` (declared before the first patched function), replaces bodies and inserts the helpers on one line so `#line`/LineMap stay valid. All or nothing per effect: a dimension with no match stays untouched; a partial match or a missing symbol drops the effect (the pack object is returned unchanged, the reason goes to `manifest.rt.unavailable`) | `converter/glsl/RtPatcher` |
| Helpers: `emrRtVisibility` (ray query, TerminateOnFirstHit, mask 0x01, cutout candidates alpha-tested at mip 0 against the block atlas, vertex UVs fetched through `GL_EXT_buffer_reference_uvec2` from MC's heaps, max 32 candidates); `emrRtShadowOrigin` (exact inverse of `GetShadowPos`, so all of the pack's biases stay); cone around the shadow light from `shadowModelViewInverse`, jittered with the pack's `InterleavedGradientNoiseForShadows` (TAA accumulates) | `rt/emrion_rt.glsl`, `rt/complementary/shadows.glsl` |
| Transpiler: `#version 460` + `GL_EXT_ray_query` + buffer-reference extensions only for stages with the helper type; `accelerationStructureEXT` is an opaque uniform; buffer-reference blocks pass through verbatim. Planner: `emrRtTlas` → `acceleration_structure/emrion_tlas`, `EmrRtSections` → `storage_buffer/emrion_rt_sections`, `emrRtAtlas` → block atlas. `SpirvReflection` reports AS bindings | `GlslTranspiler`, `IrisDimensionPlanner`, FORMAT.md §10 |
| Config `config/emrionshaders.properties`: `rt.enabled`, `rt.shadows` (hybrid/full/off), `rt.shadowRays`, `rt.distance`, `rt.device` (now read at boot too); `-Demrion.rt.<key>` overrides | `rt/RtConfig`, `RtCapability` |
| "Emrion RT..." button on the root of the shader options screen → our own page (master switch, shadow mode, rays, RT distance, status "unsupported GPU" / "unavailable: reason" / "active (hybrid)", BLAS summary). Key `key.emrionshaders.rt` (unbound) toggles `rt.enabled` with an action-bar message | `gui/RtOptionsScreen`, `EmrionShadersClient` |
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
- RT off: the cache directory is byte-identical to master's for the user's pack (`diff -r` of both converters' output, and
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
  145.2, forest 218.2 → 188.5 (master vs. this branch); see PROGRESS (sh1 follows up).

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
- RT-5: BLAS compaction (about −50 %), entity BLAS per frame (Full mode then gets entity shadows), RTAO.
- Waving leaves cast static RT shadows (the BLAS uses the unanimated vertices).
- The RT distance only limits instances and forced compiles; BLAS outside it are still built when MC meshes them.

