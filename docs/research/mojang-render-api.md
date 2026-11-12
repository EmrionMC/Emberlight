# Mojang render API (MC 26.3, "renderpearl") — implementation guide for Emrion Shaders

All refs are `file:line` relative to `reference/mc-src/`. Abbreviations:
`rp/` = `com/mojang/renderpearl/`, `vk/` = `rp/backend/vulkan/`, `cr/` = `net/minecraft/client/renderer/`, `b3d/` = `com/mojang/blaze3d/`.
Vanilla GLSL lives in the client jar under `assets/minecraft/shaders/{core,include,post}` (not extracted).

---

## TL;DR for the architecture

1. Everything goes through `RenderSystem.getDevice()` (a `FrontendGpuDevice`) → one global `CommandEncoder` → one Vulkan primary-CB stream on the **graphics queue**, **one submit per frame**, **2 frames in flight**.
2. All Vulkan images live permanently in `VK_IMAGE_LAYOUT_GENERAL`; after *every* copy/clear/render pass a **global ALL_COMMANDS→ALL_COMMANDS memory barrier** is emitted. No layout tracking → raw-Vulkan interop is trivial, but nothing overlaps.
3. Pipelines = GLSL 330 → **shaderc** (LWJGL) → SPIR-V (Vulkan 1.2 env) → SPIRV-Cross (spvc) reflection → bindings remapped by **name** into **one push-descriptor set (set 0)** + optional ≤128 B push constants. VS+FS only.
4. Precompiled SPIR-V can be injected by intercepting `GlslCompiler.compileToSpv` (returns `new SPIRVModule(spv, type)`) — the rest of the pipeline builder (reflection, attribute/binding mapping) keeps working.
5. Hard limits: 8 color targets, depth attachment must be `D32_FLOAT`, no storage images/SSBO/compute/geometry/3D/array textures, no compare samplers, no border address mode, one blend function shared by all blended targets, viewport always = full attachment, **reverse-Z** depth (clear 0, `GREATER_OR_EQUAL`).
6. Terrain vertex format is only `Position, Color, UV0, UV2` (28 B) — **no normal, no mc_Entity, no midTexCoord, no tangent**.
7. Choke point for pipeline swapping: `RenderSystem.getCompiledPipeline[Nullable](RenderPipeline)` (`b3d/systems/RenderSystem.java:105-127`). Choke point for frame structure: `LevelRenderer.render` / `addMainPass` lambda (`cr/LevelRenderer.java:186-327, 384-468`).

---

## 1. Layering, entry points, backend selection

| Layer | Package | Role |
|---|---|---|
| **api** | `rp/api/**` | Public interfaces/records: `GpuDevice`, `CommandEncoder`, `RenderPass`, `RenderPassDescriptor`, `GpuTexture(View)`, `GpuSampler`, `GpuBuffer(Slice)`, `TransientMemory`, `RenderPipeline`(+Builder/Snippet), `BindGroupLayout`, `ColorTargetState`, `DepthStencilState`, `VertexFormat`, `GpuFormat`, `GpuQueryPool`, `DeviceInfo/Limits/Features`. |
| **frontend** | `rp/frontend/**` | Validation + shader compilation. `FrontendGpuDevice` (implements `GpuDevice`, wraps a `GpuDeviceBackend`), `FrontendCommandEncoder`, `FrontendRenderPass`, `FrontendRenderPipeline` (= `CompiledRenderPipeline`), `shaders/{GlslCompiler,SPIRVModule,PipelineBuilder,SpvUtil}`. |
| **backend/api** | `rp/backend/api/**` | SPI implemented per backend: `GpuDeviceBackend`, `CommandEncoderBackend`, `RenderPassBackend`, `BackendRenderPipeline(.CreateInfo)`, `SpvModule`. |
| **backend/vulkan, backend/opengl** | `vk/**`, `rp/backend/opengl/**` | Implementations. GL consumes the same SPIR-V by cross-compiling to GLSL 330 with spvc (`rp/backend/opengl/GlPipelineRecompiler.java:51-69`). |

Note: textures, views, samplers and buffers are **not wrapped** by the frontend — `FrontendGpuDevice.createTexture` returns the backend object directly (`rp/frontend/FrontendGpuDevice.java:108,154,172`). So on Vulkan a `GpuTexture` *is* a `VulkanGpuTexture`, a `GpuTextureView` *is* a `VulkanGpuTextureView`, etc. Only encoder/render pass/pipelines are wrapped.

Entry points:
- `RenderSystem.getDevice()` / `tryGetDevice()` — `b3d/systems/RenderSystem.java:346-355`. Set once by `initRenderer(device)` (`:223`).
- `GpuDevice` (`rp/api/device/GpuDevice.java:27-61`): `createCommandEncoder()` (returns the single shared encoder, `FrontendGpuDevice.java:63`), `createTexture(label, usage, format, w, h, depthOrLayers, mips)`, `createTextureView(tex[, baseMip, mips])`, `createSampler(...)`, `createBuffer(label, usage, size|data)`, `compilePipeline(RenderPipeline, ShaderSource, Executor)`, `createTimestampQueryPool(n)`, `getDeviceInfo()`.
- `RenderSystem.getCompiledPipeline(RenderPipeline)` (`RenderSystem.java:120`), `bindDefaultUniforms(pass)` binds `Projection`, `Fog`, `Globals`, `Lighting` UBOs (`:370-390`), `getDynamicUniforms()` (`:362`), `getSamplerCache()` (`:87`), `setProjectionMatrix(slice, type)` (`:270`), `getSequentialBuffer(QUADS)` (`:303`, auto quad index buffer).

Backend selection (`net/minecraft/client/Minecraft.java:463-525`):
- `PreferredGraphicsApi { DEFAULT, OPENGL, VULKAN }` (`net/minecraft/client/PreferredGraphicsApi.java:13-40`, extracted). `getBackendsToTry()`: `VULKAN → [vulkan, gl]`, otherwise `[gl, vulkan]` (`:36-40`). **DEFAULT tries OpenGL first.**
- Option `options.graphicsApi` (`net/minecraft/client/Options.java:137-158`, accessor `preferredGraphicsBackend()` `:1031`), restart required. Launch arg `gameConfig.game.forcedGraphicsApi` overrides (`Minecraft.java:467`).
- Crash guard: unclean last start → VULKAN reset to DEFAULT, DEFAULT forced to OPENGL (`Minecraft.java:470-479`).
- Loop tries each backend: `loadLibrary()` → `createDevice(GpuDebugOptions)`, `BackendCreationException` → next (`:488-509`).
- Vulkan requirements (`vk/VulkanFeatureSets.java:27-40`): instance API 1.2 (`vk/VulkanInstance.java:47`), device exts `VK_KHR_dynamic_rendering, VK_KHR_push_descriptor, VK_KHR_synchronization2, VK_EXT_vertex_attribute_divisor, VK_KHR_swapchain`; features multiDrawIndirect, drawIndirectFirstInstance, samplerAnisotropy, shaderDrawParameters, timelineSemaphore, hostQueryReset, synchronization2, dynamicRendering, vertexAttributeInstanceRateDivisor. Optional: calibrated timestamps, fillModeNonSolid, NV/AMD checkpoints, EXT_multi_draw (`:41-75`). Device creation in `vk/VulkanBackend.java:140-203`.

**Runtime detection**: `RenderSystem.getDevice().getDeviceInfo().backendName()` is `"Vulkan"` (`vk/VulkanDevice.java:94`) or `"OpenGL"` (`rp/backend/opengl/GlHeuristics.java:95`). Stronger: `((FrontendCommandEncoder) device.createCommandEncoder()).backend() instanceof VulkanCommandEncoder` (`rp/frontend/FrontendCommandEncoder.java:43-46`, `@Internal` but public). Recommendation: Emrion only activates on Vulkan; optionally force `PreferredGraphicsApi.VULKAN` via a mixin into `Options` defaults or tell the user.

---

## 2. Shaders → SPIR-V → pipelines

### 2.1 Compile path
`PipelineCache.get` / `ShaderManager` → `GpuDevice.compilePipeline` → `PipelineBuilder.compilePipeline` (`rp/frontend/shaders/PipelineBuilder.java:346-399`, runs on the given executor) →
1. `ShaderSource.getShader(id, type)` returns **GLSL text** (`rp/api/pipeline/ShaderSource.java:12`); includes via `getInclude(id)` (shaderc include callback, `#include <ns:path.glsl>`).
2. `GlslCompiler.compileToSpv(name, source, type, defines, shaderSource)` (`rp/frontend/shaders/GlslCompiler.java:99-146`): **LWJGL `Shaderc`** (not glslang directly). Options (`:77-97`): target env `(0, 4202496)` = Vulkan 1.2 (→ SPIR-V ≤1.5), `auto_bind_uniforms=true`, `preserve_bindings=false`, debug info, **optimization level 0**. Macros: `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` (Vulkan), `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE`, `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE`, plus pipeline `ShaderDefines`. Entry point always `"main"`. Returns `new SPIRVModule(copy, type)` (`:137`).
3. Reflection via **SPIRV-Cross C API (LWJGL `Spvc`)** in `SPIRVModule.doReflection` (`rp/frontend/shaders/SPIRVModule.java:68-123`): stage inputs/outputs, descriptors of spvc types {1 UBO, 2 SSBO, 6 storage image, 7 sampled image, 10 sep. image, 11 sep. sampler} (`SpvUtil.java:14`), push constants. Location/Binding/Set are patched **in the SPIR-V binary** via `spvc_compiler_get_binary_offset_for_decoration` (`SPIRVModule.java:144`, `SpvUtil.java:38-44`) → the module **must already contain** `Location`, `Binding`, `DescriptorSet` decorations (otherwise `ShaderCompileException`).
4. `generateBackendCreateInfo` (`PipelineBuilder.java:66-344`):
   - Vertex inputs matched **by name** to `VertexFormatElement.name` (`:100-122`); every VS input must have an element (`:131-136`), base type must match (float for UNORM/SNORM/FLOAT, uint/int for UINT/SINT, `:137-154`), no `Component` decoration (`:125`).
   - VS outputs ↔ FS inputs matched **by location**, base type/vector size/`flat` must match (`:171-241`). 64-bit and struct varyings rejected (`:427-441`).
   - Descriptors: each name must exist in the pipeline's `BindGroupLayout`s (`:260-263`); binding index = first-seen order across stages, **set forced to 0** (`:265-281`). Type check: UBO ↔ `UNIFORM_BUFFER`, sampled image ↔ `COMBINED_IMAGE_SAMPLER|TEXEL_BUFFER` (`:285-310`). Dims: 2D, Cube, Rect → sampler; Buffer → texel buffer; **1D and 3D throw** (`:298-300`). SSBO/storage-image resource types never match → error.
   - ≤1 push-constant block, size ≤ `pipeline.pushConstantSize()` (`:312-324`).
5. `VulkanDevice.compilePipeline` → `VulkanRenderPipeline.compile` **synchronously** (`vk/VulkanDevice.java:250-253`, `vk/VulkanRenderPipeline.java:83-299`).

### 2.2 GLSL conventions (vanilla `core/terrain.vsh/.fsh`)
- `#version 330` + `#extension GL_ARB_separate_shader_objects : require`.
- Explicit `layout(location=N)` on all VS outputs/FS inputs (matching is by location).
- VS attribute names = vertex element names (`Position`, `Color`, `UV0`, `UV1`, `UV2`, `Normal`, `ChunkPosition`, `ChunkVisibility`, `LineWidth`, `UV3`).
- UBOs: `layout(std140) uniform BlockName { ... };` (anonymous instance); bound by **block name** (`Globals`, `Projection`, `Fog`, `Lighting`, `DynamicTransforms`, `TerrainUniform`, `ChunkSection`, …). Samplers: `uniform sampler2D Sampler0;` bound by variable name. No `binding=` qualifiers needed.
- Std140 helper: `b3d/buffers/Std140Builder.java`, `Std140SizeCalculator.java`.

### 2.3 Binding model (Vulkan)
- One descriptor set layout per pipeline with flag `PUSH_DESCRIPTOR_BIT_KHR`, stage flags VS|FS, types UBO(6) / COMBINED_IMAGE_SAMPLER(1) / UNIFORM_TEXEL_BUFFER(4), binding = index (`vk/VulkanRenderPipeline.java:84-114`).
- Pipeline layout: that one set + one push-constant range `[0,size)` all stages (`:116-134`). Max 128 B (`rp/api/pipeline/RenderPipeline.java:411`).
- At draw: `vkCmdPushDescriptorSetKHR` with all bindings if dirty (`vk/VulkanRenderPass.java:234-303`). **All declared uniforms must be set** or it throws "Missing uniform" (`:250-264`). Image layout always GENERAL (`:269`). Texel buffers create a `VkBufferView` per push (destroyed 2 frames later, `:278-295`).
- API: `RenderPass.setUniform(name, view, sampler)`, `setUniform(name, GpuBuffer|GpuBufferSlice)`, `pushConstants(ByteBuffer)` (`rp/api/commands/RenderPass.java:34-40`). Unknown names are ignored (`rp/frontend/FrontendRenderPass.java:170`). UBO offset must be aligned to `limits().minUniformOffsetAlignment()` (`FrontendRenderPass.java:153-157`).
- `BindGroupLayout.builder().withUniform(name, UniformType).build()` (`rp/api/pipeline/BindGroupLayout.java:47-74`); `UniformType {COMBINED_IMAGE_SAMPLER, UNIFORM_BUFFER, TEXEL_BUFFER}` (`rp/api/pipeline/UniformType.java`). Vanilla layouts in `cr/BindGroupLayouts.java`. Names must be unique across a pipeline's layouts.

### 2.4 Creating a RenderPipeline (custom shaders)
```java
RenderPipeline p = RenderPipeline.builder(/*optional snippets e.g. RenderPipelines.TERRAIN_SNIPPET*/)
  .withLocation(Identifier.fromNamespaceAndPath("emrion", "gbuffers_terrain"))
  .withVertexShader(id).withFragmentShader(id)            // ShaderType only VERTEX/FRAGMENT
  .withBindGroupLayout(BindGroupLayouts.GLOBALS) ... .withBindGroupLayout(myLayout)
  .withVertexBinding(0, DefaultVertexFormat.BLOCK)        // up to 16 buffers, ≤16 attribs total
  .withPrimitiveTopology(PrimitiveTopology.QUADS)        // QUADS = triangle list + auto index buffer
  .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true, slopeBias, constBias))
  .withCull(true).withPolygonMode(PolygonMode.FILL)
  .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
  .withColorTargetState(1, ...)  // index ≤ 7
  .withPushConstantSize(64)
  .build();
```
Builder: `rp/api/pipeline/RenderPipeline.java:145-430`. Constraints: `ColorTargetState.MAX_COLOR_TARGETS = 8` (`ColorTargetState.java:22`, builder array size 8 `RenderPipeline.java:154`); **all targets that have a blend function must use the same one** (`:385-398`; targets with `Optional.empty()` = blend off are fine); ≤16 vertex attribs (`:409`); push constants ≤128 (`:411`). Missing depth state → backend still builds a variant usable in passes with depth (`vk/VulkanRenderPipeline.java:281-293`).
Vulkan state baked (`vk/VulkanRenderPipeline.java:213-276`): cull BACK, frontFace **CLOCKWISE** (1), depth bias from `DepthStencilState`, 1 sample, dynamic viewport+scissor, **dynamic rendering** with color formats from `ColorTargetState.format` and **depth format hard-coded `D32_SFLOAT` (126)** (`:262`).
Render pass ↔ pipeline must agree: same color attachment count and formats (`rp/frontend/FrontendRenderPass.java:107-125`).

Compile and use:
```java
CompiledRenderPipeline c = device.compilePipeline(p, shaderSource, Util.backgroundExecutor()).join().finishCompile();
pass.setPipeline(c);
```
or register into a `PipelineCache` (`b3d/pipeline/PipelineCache.java:16-55`).

### 2.5 Feeding precompiled SPIR-V
Option A (recommended, least code): mixin `GlslCompiler.compileToSpv` (`GlslCompiler.java:99`) at HEAD: if `name` is in the `emrion:` namespace, return `new SPIRVModule(buf, type)` where `buf` is a `MemoryUtil.memAlloc` copy (it is `memFree`d on close, `SPIRVModule.java:38`). Provide a `ShaderSource` whose `getShader` returns a non-null dummy string for those ids (`PipelineBuilder.java:76-80` aborts on null). Offline compiler requirements for the SPIR-V:
- Vulkan 1.2 target (SPIR-V ≤ 1.5), entry `main`, one VS + one FS.
- Every interface var has `Location`; every resource has `DescriptorSet` + `Binding` (values irrelevant, rewritten) — i.e. compile with `-fauto-bind-uniforms -fauto-map-locations` (glslc) or explicit qualifiers. Keep OpName (don't strip names): binding is **by name** (UBO block name, sampler var name, VS input name).
- No `Component` decorations, no 64-bit/struct varyings, no SSBO/storage images/3D/1D samplers.
- Bake vanilla-equivalent macros yourself (`RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` etc.).
Option B: build `BackendRenderPipeline.CreateInfo` yourself (`rp/backend/api/BackendRenderPipeline.java:20-44`), call the backend `compilePipeline`, wrap in `new FrontendRenderPipeline(name, backend, vertexFormats, uniformIndices, uniforms, colorTargets, wantsDepth, pushConstSize)` (public record, `rp/frontend/FrontendRenderPipeline.java:15-24`). Needs `FrontendGpuDevice.backend` (private → accessor). More control (skip reflection), more code.

### 2.6 Eager vs lazy compilation, pipeline cache
- Vanilla: `RenderPipelines.requiredPipelines()/optionalPipelines()` are compiled in parallel on resource reload (`cr/ShaderManager.java:76-91,98-105`) into a fresh `PipelineCache`, swapped in via `RenderSystem.setCurrentPipelineCache` (`:225`).
- Anything not precompiled: `PipelineCache.get` compiles **synchronously on the render thread** (`.join()`, `b3d/pipeline/PipelineCache.java:36`) → stutter. **Emrion must precompile all pack pipelines at pack load** (e.g. `CompletableFuture.allOf` over `device.compilePipeline(..., Util.backgroundExecutor())`, then `finishCompile()` on render thread).
- **No `VkPipelineCache`**: `vkCreateGraphicsPipelines(device, 0L, ...)` (`vk/VulkanRenderPipeline.java:279,287`). Driver-side caches still apply. To add one: `@ModifyArg` on those two calls with our own persisted `VkPipelineCache` (save with `vkGetPipelineCacheData` on shutdown).
- shaderc runs with `-O0`; optimizing offline (spirv-opt) is free perf.

---

## 3. Textures, render targets, copies, sync

**Formats** (`rp/api/GpuFormat.java:8-63`, Vulkan mapping `vk/VulkanConst.java:112-170`): R/RG/RGB/RGBA × {8,16} UNORM/SNORM, {8,16,32} UINT/SINT, `R16/RG16/RGB16/RGBA16_FLOAT`, `R32/RG32/RGB32/RGBA32_FLOAT`, `RGB10A2_UNORM`, `RGB10A2_UINT`, `RG11B10_FLOAT` (= R11G11B10F), `D32_FLOAT`, `D32_FLOAT_S8_UINT`, `D24_UNORM_S8_UINT`, `D16_UNORM`, `S8_UINT`. So RGBA16F, RGBA32F, R11G11B10F, RGB10A2, R8, RG16F, D32F all exist. Avoid `RGB*` 3-component formats as render targets (mostly unsupported as color attachments on desktop drivers; nothing checks format support). Depth *attachments* must be `D32_FLOAT` (pipeline hard-codes it).

**Creation**: `createTexture(label, usage, format, w, h, depthOrLayers, mips)`; usage bits `COPY_DST=1, COPY_SRC=2, TEXTURE_BINDING=4, RENDER_ATTACHMENT=8, CUBEMAP_COMPATIBLE=16` (`rp/api/textures/GpuTexture.java:14-18`) → VK usage (`vk/VulkanConst.java:21-45`; **no STORAGE bit**). `depthOrLayers > 1` only for cubemaps (=6); arrays/3D throw (`rp/frontend/FrontendGpuDevice.java:128-143`); image type always 2D (`vk/VulkanGpuTexture.java:42`). Mips: up to log2(max)+1; views select mip range `createTextureView(tex, baseMip, count)`; render into one mip via a single-mip view. No mip generation/blit API → generate with fullscreen passes.
Vanilla `RenderTarget`s use usage 15 (all) (`b3d/pipeline/RenderTarget.java:99,104`). `MainTarget` = `RGBA8_UNORM` + `D32_FLOAT` (`b3d/pipeline/MainTarget.java:21`).

**Samplers**: `createSampler(addrU, addrV, min, mag, maxAniso, OptionalDouble maxLod)`; `AddressMode {REPEAT, CLAMP_TO_EDGE}` only, `FilterMode {NEAREST, LINEAR}`, mipmap mode linear iff maxLod>0.25 (`vk/VulkanGpuSampler.java:47-51`). **No compare (shadow) samplers, no border/mirror.** Cache: `RenderSystem.getSamplerCache().getClampToEdge(FilterMode)`.

**Render passes** (`rp/api/commands/RenderPassDescriptor.java`):
```java
RenderPassDescriptor d = RenderPassDescriptor.builder(() -> "gbuffer")
  .withColorAttachment(view0, Optional.of(new Vector4f(0,0,0,0)))   // clear
  .withColorAttachment(view1)                                        // load
  .withUnusedColorAttachment()                                       // hole
  .withDepthAttachment(depthView, OptionalDouble.of(0.0))            // reverse-Z clear!
  .withRenderArea(new RenderPass.RenderArea(x,y,w,h))                // only sets scissor
  .build();
try (RenderPass pass = encoder.createRenderPass(d)) { ... }
```
MRT supported up to `limits().maxColorAttachments()` (checked `FrontendCommandEncoder.java:108-112`). Load op = CLEAR if clear value present else LOAD; store op always STORE; unused slot = DONT_CARE (`vk/VulkanCommandEncoder.java:275-298`). Depth same (`:306-323`). Single layer only. `vkCmdBeginRenderingKHR` (dynamic rendering, `:325`). Viewport is fixed to full attachment size, 0..1 depth (`vk/VulkanRenderPass.java:81-91`) — no sub-viewport (shadow atlases must use separate textures or emulate in VS).
Only one render pass may be open; no copies/clears inside a pass.

**Copies**: `copyTextureToTexture(src, dst, mipLevel, dstX, dstY, srcX, srcY, w, h)` = `vkCmdCopyImage`, same mip on both, aspect from src format (`vk/VulkanCommandEncoder.java:574-603`; needs COPY_SRC/COPY_DST, `rp/frontend/FrontendCommandEncoder.java:697-780`). Works for D32→D32 (depth copy). Depth→color (e.g. R32F) is not possible with copy → use a fullscreen pass (`RenderPipelines.BLIT_DEPTH`/`INTEGRATE_DEPTH` pattern: `cr/LevelRenderer.java:652-664`, `cr/GameRenderer.java:721-732`). Also `copyTextureToBuffer`, `copyBufferToTexture`, `writeToTexture`, `clearColorTexture`, `clearDepthTexture`, `clearColorAndDepthTextures` (`rp/api/commands/CommandEncoder.java:59-106`).

**Layouts/barriers**: every image is transitioned once UNDEFINED→GENERAL at creation (`vk/VulkanGpuTexture.java:62-76`) and stays GENERAL forever (attachments `imageLayout(1)` `VulkanCommandEncoder.java:280,310`; sampled `VulkanRenderPass.java:269`; copies use layout 1). After each clear/copy/write/render-pass end: `memoryBarrier` = one `VkMemoryBarrier2` ALL_COMMANDS/MEMORY_READ|WRITE → same (`vk/VulkanCommandEncoder.java:200-213`, called `:346,376,455,497,533,567,601`). The `checkpoints/` package is **GPU-crash diagnostics** (NV checkpoints / AMD buffer markers recording BEGIN/END_RENDER_PASS), not barrier tracking (`vk/VulkanCommandEncoder.java:251,342`).

**Orientation**: viewport is *not* Y-flipped (frontFace CW compensates); the swapchain blit flips Y (`vk/VulkanGpuSurface.java:340-366`). Net effect: textures are stored GL-style (row 0 = bottom), `gl_FragCoord.xy / size` == texcoord, same as on GL — pack screen-space math ports unchanged.

**Depth convention: reverse-Z, [0,1]**: projection built with near/far swapped (`cr/Projection.java:70-75`), depth test `GREATER_THAN_OR_EQUAL` (`rp/api/pipeline/DepthStencilState.java:8`), clear 0.0 (`RenderSystem.java:46`, `cr/LevelRenderer.java:255`). Packs expect GL depth (near=0, far=1). We must either pass a forward-Z projection to our G-buffer/shadow pipelines (own depth test LESS, clear 1.0 — allowed, it's per-pipeline) or rewrite depth reads (`d' = 1-d`, and ndc z = d instead of 2d-1). Recommended: keep vanilla reverse-Z for vanilla-pipeline passes, but for pack passes feed packs `gbufferProjection` in GL convention and convert sampled `depthtexN` in a generated prelude.

---

## 4. Buffers, uniforms, storage, compute

- `GpuBuffer` usage bits `MAP_READ=1, MAP_WRITE=2, HINT_CLIENT_STORAGE=4, COPY_DST=8, COPY_SRC=16, VERTEX=32, INDEX=64, UNIFORM=128, UNIFORM_TEXEL_BUFFER=256, INDIRECT_PARAMETERS=512` (`rp/api/buffers/GpuBuffer.java:13-22`) → VK (`vk/VulkanConst.java:47-79`, **no STORAGE_BUFFER**). `map(offset,len,read,write)` returns `MappedView` (AutoCloseable).
- `encoder.writeToBuffer(slice, data)` = staging + `vkCmdCopyBuffer` + barrier (`vk/VulkanCommandEncoder.java:445-457`).
- **TransientMemory** (`rp/api/buffers/TransientMemory.java`; Vulkan impl `vk/VulkanTransientMemory.java`): per-submit block allocators (512 KiB blocks, `:36`), recycled after the submit retires. `uploadGpu(data, alignment, usage)` → device-visible mapped slice valid for **this frame only**; `uploadStaging(...)`, `allocateGpuMapped(...)`, `multiUploadGpu`. Usage bits of transient blocks are fixed (`BUFFER_USAGE_BITS = 471`: copy, uniform, uniform-texel, index, vertex, indirect — `:39`). **This is the UBO path to use for per-frame/per-pass uniforms**: `GpuBufferSlice ubo = encoder.transientMemory().uploadGpu(std140Bytes, limits.minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM); pass.setUniform("EmrionUniforms", ubo);`
- Vanilla helpers: `RenderSystem.getDynamicUniforms()` (`cr/DynamicGpuData.java:62-121`: `writeTransform`, `writeTerrainTransform`, `writeChunkSections`), `cr/MappableRingBuffer.java` (N-buffered persistent UBO, `rotate()` per frame).
- Push constants: ≤128 B, `RenderPass.pushConstants(ByteBuffer)`; `vkCmdPushConstants` all stages (`vk/VulkanRenderPass.java:133-137`).
- **SSBO, storage images, image load/store, atomics, compute pipelines, dispatch: not exposed at all.** `ShaderType` has only VERTEX/FRAGMENT (`rp/api/pipeline/ShaderType.java:11-12`). Read-only buffer data in shaders: `UNIFORM_TEXEL_BUFFER` + `samplerBuffer` (`UniformType.TEXEL_BUFFER` with format).
- Timestamp queries **are** in the API: `device.createTimestampQueryPool(n)`, `encoder.writeTimestamp(pool, i)` / `pass.writeTimestamp(pool, i)`, `pool.getValue(i)` (OptionalLong, empty until ready), `deviceInfo.timestampPeriod()` (`rp/api/commands/GpuQueryPool.java`, `vk/VulkanCommandEncoder.java:656-661`). Use these for the GPU profiler — no raw Vulkan needed.

---

## 5. World render loop and hook points

### 5.1 Frame
`Minecraft.runTick` → `GameRenderer.render()` (`cr/GameRenderer.java:466-515`): clear main color/depth(0.0) → globals UBO update → `lightmap.render` → **`renderLevel()`** (`:494`) → `levelRenderer.blitEntityOutline()` → `applyPostEffects()` (vanilla PostChains on mainTarget, `:548-557`) → `clearDepthTexture(main, 0.0)` → GUI. Then `Minecraft.java:1297-1305` blits `mainRenderTarget` color to swapchain, `:1319` `encoder.submit()`, `:1322` `present()`.

`GameRenderer.renderLevel()` (`:631-672`): builds projection (bob/nausea), `RenderSystem.setProjectionMatrix(levelProjectionMatrixBuffer..., PERSPECTIVE)` (`:661`), fog buffers, `levelRenderer.render(resourcePool, renderOutline, cameraState, terrainFog, fogColor, renderSky, consistentDepthRequired)` (`:670`), `render3dHud(...)` (`:671`).
`render3dHud` (`:674-719`): hud projection (`:683`), **clear depth** (main depth or `hud3DTarget` if post effects) (`:686`), `renderItemInHand` (`:378-416`, pass "Item in hand" on `mainRenderTarget` color + that depth), screen effects pass, 3D crosshair, `integrate3DHudDepth` (`:721-732`).

`LevelRenderer.render` (`cr/LevelRenderer.java:186-326`):
1. `submitFeatures` (`:328-352`): entities, block entities, breaking overlay, transient blocks, **particles** (`levelRenderState.particlesRenderState.submit`, `:338`), block outline, gizmos → `SubmitNodeStorage`; `featureRenderDispatcher.prepareFrame` (`:205`).
2. `FrameGraphBuilder frame` (`b3d/framegraph/FrameGraphBuilder.java`: `addPass`, `importExternal`, `createInternal(name, RenderTargetDescriptor)`, `FramePass.reads/readsAndWrites/executes`), main target imported (`:208`). OIT targets only if `useImprovedTransparency` (`:216-243`).
3. Pass **"clear"** (`:249-259`): main color = fog color, depth 0.0.
4. Pass **"sky"** (`addSkyPass :364-382`) → `SkyRenderer.render(skyFog, state)` (`cr/SkyRenderer.java:132-139`, own render pass on main color+depth; pipelines SKY, CELESTIAL, STARS, SUNRISE_SUNSET, END_SKY).
5. `prepareChunkRenders(terrainMatrix, respectTranslucentOrder)` or `prepareChunkRendersIndirect` (`:265-272`) → `ChunkSectionsToRender`.
6. Pass **"main"** (`addMainPass :384-468`), lambda body `:418-467`:
   - one render pass "Main" on main color+depth (`:437-451`): `executeSolid` (`:507-517`: `renderGroup(OPAQUE = SOLID+CUTOUT)` then `featureFrame.executeSolid` = solid entities/block entities/items) → if classic transparency `executeClassicTransparency` (`:666-690`): `featureFrame.executeTranslucent` (shadows, translucent models, name tags, text, translucent custom geometry, outlines, gizmos, translucent blocks/items, breaking overlay) → `renderGroup(TRANSLUCENT)` terrain (sorted back-to-front) → `executeTranslucentAfterTerrain` (particles etc.) → clouds `cloudRenderer.render(status, pass)` → weather `weatherEffectRenderer.render` → world border.
   - if improved transparency: `executeOit` (`:543-626`, wavelet OIT into extra RGBA16F/RGBA32F targets + composite).
   - `executeOutline`, see-through, always-on-top.
7. Entity-outline post chain (`:275-280`), `frame.execute(...)` (`:283`), then `compileSections`, `uploadTerrainBuffersToGpu`, `sectionOcclusionGraph.update` (`:298-317`).

### 5.2 Terrain path and vertex format
- `ChunkSectionLayer {SOLID, CUTOUT, TRANSLUCENT}` with pipelines `SOLID_TERRAIN`, `CUTOUT_TERRAIN` (define `ALPHA_CUTOUT 0.5`), `TRANSLUCENT_TERRAIN` (+ `_MULTIDRAW` variants) (`cr/chunk/ChunkSectionLayer.java:12-57`, `cr/RenderPipelines.java:466-520`). Groups: OPAQUE=SOLID+CUTOUT, TRANSLUCENT (`cr/chunk/ChunkSectionLayerGroup.java`).
- Draw: `ChunkSectionsToRender.renderGroup(group, pass, sampler, atlas, wireframe)` (`cr/chunk/ChunkSectionsToRender.java:45-98`) binds `TerrainUniform` (modelView + atlas size), `Sampler0` (block atlas), `Sampler2` (lightmap), then per layer `setPipeline(getCompiledPipeline(layer.pipeline(multidraw)))` and `drawMultipleIndexed` with per-draw `ChunkSection` UBO (`:171-185`) or `drawIndexedIndirect` + instanced `ChunkPosition/ChunkVisibility` vertex buffer slot 1 (`:116-152`).
- **Terrain vertex format `DefaultVertexFormat.BLOCK`** (`b3d/vertex/DefaultVertexFormat.java:30-35`): `Position RGB32_FLOAT`, `Color RGBA8_UNORM` (AO baked into color), `UV0 RG32_FLOAT`, `UV2 RG16_SINT` (lightmap). 28 bytes. **No Normal.** Entities use `ENTITY` (`:40-47`): Position, Color, UV0, UV1 (overlay), UV2, Normal RGBA8_SNORM. Particles: `PARTICLE` (`:57-62`).
- Positions are camera-relative per section: `pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset` (vanilla `terrain.vsh`), `gl_Position = ProjMat * ModelViewMat * vec4(pos,1)`; `ModelViewMat` = view rotation only (`cr/LevelRenderer.java:264`).
- Section mesh built with `new BufferBuilder(buffer, QUADS, layer.vertexFormat())` (`cr/chunk/SectionCompiler.java:142`); `layer.vertexFormat()` = the pipeline's binding 0 format (`ChunkSectionLayer.java:55-57`).

### 5.3 Hook points

**(a) G-buffer pipeline swap (terrain/entities/sky/clouds/weather/particles)**
- Nearly every draw selects its pipeline via `RenderSystem.getCompiledPipeline(RenderPipeline)` (call sites: `ChunkSectionsToRender.java:125,180`, `cr/rendertype/PreparedRenderType.java:43` (all RenderType/entity/item draws), `SkyRenderer.java:166-317`, `CloudRenderer.java:235`, `WeatherEffectRenderer.java:163`, `WorldBorderRenderer.java:166,178`, `cr/feature/QuadParticleFeatureRenderer.java:89`). Mixin `RenderSystem.getCompiledPipelineNullable` (`RenderSystem.java:105`) HEAD: if Emrion's "gbuffer phase" flag is set and a mapping `RenderPipeline → CompiledRenderPipeline` exists, return it. Keyed by vanilla `RenderPipeline` identity / `getLocation()`.
- The pass must have **matching color attachments** (count + formats) to the swapped pipeline, so the surrounding render pass must also be replaced: take over the `addMainPass` lambda (`LevelRenderer.java:418-467`) — e.g. `@WrapOperation` around `createRenderPass` at `:437-445` to return our MRT pass (colortex0..N + our depth), and redirect `executeClassicTransparency` (`:450`) so we can end the solid pass, copy depth, and open a translucent MRT pass. Simplest robust approach: `@Inject(HEAD, cancellable)` into the lambda (`lambda$addMainPass$...`) and re-implement its ~30 lines calling the (accessor-invoked) `executeSolid` / `executeClassicTransparency` pieces with our passes. Force classic transparency while a pack is active (`GameRenderer.useImprovedTransparency()`, `cr/GameRenderer.java:858-860`) — OIT path is incompatible with packs.
- Sky: `addSkyPass` (`:364-382`) — redirect `SkyRenderer.render` pass creation (`SkyRenderer.java:139`) or run sky inside our gbuffer pass with swapped SKY/STARS/CELESTIAL pipelines.
- Pipelines that vanilla never precompiles must be precompiled by us (see 2.6).

**(b) Shadow pass**
- Insert a FrameGraph pass before "main" (e.g. `@Inject` before `this.addMainPass(...)` at `LevelRenderer.java:274`, using `frame.addPass("emrion_shadow")`), or simply run before `frame.execute` since all work is serial anyway.
- Geometry: `LevelRenderer.prepareChunkRenders(Matrix4fc modelView, boolean respectTranslucentOrder)` (`:785-834`) is public and reusable: it builds draws from `this.visibleSections` (`:717`) and writes a `TerrainUniform` with the given modelView. For the shadow view: temporarily replace the contents of `visibleSections` (final `ObjectArrayList`, `:136`) with sections culled against the light frustum (iterate `viewArea()` / `SectionRenderDispatcher.RenderSection`s, `:1286`), call `prepareChunkRenders(shadowModelView, false)`, restore. Set `RenderSystem.setProjectionMatrix(ortho, ProjectionType.ORTHOGRAPHIC)` (or bind our own `Projection` UBO), open a depth-only (+ optional shadowcolor MRT) pass on a `D32_FLOAT` texture (`withDepthAttachment(view, OptionalDouble.of(clear))`), `renderGroup(OPAQUE/TRANSLUCENT, ...)` with the shadow pipelines swapped in via (a). Positions stay camera-relative (`Globals.CameraBlockPos/CameraOffset`), so the shadow modelView must be built around the camera, not world origin.
- Entities in shadow: re-submit via `FeatureRenderDispatcher` with a second `SubmitNodeStorage` (as `GameRenderer.renderItemInHand` does, `:394-409`) — more work; MVP can skip.
- Note `prepareChunkRenders` also allocates from `DynamicGpuData` — fine, it's per frame.

**(c) Deferred/composite/final passes**
- After `frame.execute` in `LevelRenderer.render` (`:283-292`) or at TAIL of `GameRenderer.renderLevel` before/after `render3dHud` (`cr/GameRenderer.java:670-671`): run `deferred*` passes (between opaque and translucent → inside our replaced main-pass lambda), `composite*` after translucents, `final` writes into `mainRenderTarget().getColorTextureView()` (RGBA8_UNORM) which is what gets blitted to the swapchain (`Minecraft.java:1299-1304`). Fullscreen draw pattern: pipeline with no vertex binding, `pass.draw(3, 1, 0, 0)` (see `LevelRenderer.java:620-624`).
- Hand: currently rendered after the world into main color (`GameRenderer.java:687`); for packs redirect "Item in hand" pass (`:403-405`) to the gbuffer MRT (`gbuffers_hand`) before composites, or run composites after `render3dHud`.
- Vanilla `applyPostEffects` (`:497`) runs after; keep or disable.

**(d) Depth copies**
- `depthtex1` (no translucents): after `executeSolid` (`LevelRenderer.java:448`) end the pass, `copyTextureToTexture(ourDepth, depthTex1, 0, 0,0, 0,0, w,h)` (D32→D32), reopen the pass for translucents (LOAD). Needs the split from (a).
- `depthtex2` (no hand): copy before `renderItemInHand` (`GameRenderer.java:686-687`) — note vanilla **clears** the depth before the hand (`:686`), so copy before that clear (or render the hand into a separate depth).
- All copies are automatically fenced by the global barrier.

---

## 6. Escape hatches (raw Vulkan)

Handles (all public getters unless noted):
- Device: `VulkanDevice.vkDevice()` (`vk/VulkanDevice.java:163`), `vma()` (`:179`), `instance()` (`:159`), `graphicsQueue()/computeQueue()/transferQueue()` → `VulkanQueue(vkQueue, queueFamilyIndex)` (`:167-177`, `vk/VulkanQueue.java:20`). Getting the `VulkanDevice`: `FrontendGpuDevice.backend` is **private** (`rp/frontend/FrontendGpuDevice.java:40`) → `@Accessor`. Alternatively `((FrontendCommandEncoder) dev.createCommandEncoder()).backend()` → `VulkanCommandEncoder` (its `device` field is private too → accessor).
- Encoder: `VulkanCommandEncoder` public API: `allocateAndBeginTransientCommandBuffer()` (`vk/VulkanCommandEncoder.java:117`), **`execute(VkCommandBuffer)`** (`:182`, ends the current CB and appends ours in order to the frame's submit), `waitSemaphore/signalSemaphore(sem, value, stageMask)` (`:173,191`), `queueForDestroy(Destroyable)` (`:109`, deferred 2 submits), static `memoryBarrier(cb, stack)` (`:204`), `createFence()` (`:635`). The *current* CB (`commandBuffer()`) and `objectInitCommandBuffer()` are private/package → use `execute`.
- Textures: `VulkanGpuTexture.vkImage()` (`vk/VulkanGpuTexture.java:116`), `VulkanGpuTextureView.vkImageView()` (`vk/VulkanGpuTextureView.java:66`), `VulkanGpuSampler.vkSampler()` (`vk/VulkanGpuSampler.java:108`), `VulkanGpuBuffer.vkBuffer()` (`vk/VulkanGpuBuffer.java:28`), `VulkanRenderPipeline.pipelineLayout()/withDepthPipeline()` (`vk/VulkanRenderPipeline.java:331-341`); `VulkanQueryPool.vkQueryPool()` is `protected` (`vk/VulkanQueryPool.java:71`, accessor needed).

Recipe for compute / storage images / 3D textures:
1. Own resources via VMA (`vma()`), or for storage-capable MC textures mixin `VulkanConst.textureUsageToVk` (`vk/VulkanConst.java:21`) to OR `VK_IMAGE_USAGE_STORAGE_BIT` when a custom usage bit (e.g. `1<<8`) is passed (frontend does not validate unknown bits). Transition own images to GENERAL once; MC images already are GENERAL.
2. Record: `cb = enc.allocateAndBeginTransientCommandBuffer()`; `memoryBarrier` not needed at start (vanilla emits one after every op) but **end with `VulkanCommandEncoder.memoryBarrier(cb, stack)`**; `vkEndCommandBuffer`; `enc.execute(cb)`. Must not be inside an open MC render pass (`:183`). CB comes from the per-frame pool, reset 2 submits later — do not reuse.
3. Descriptors: `VK_KHR_push_descriptor` is enabled → `vkCmdPushDescriptorSetKHR` works for compute pipelines too; no descriptor pools needed.
4. Extra device features (fragmentStoresAndAtomics, shaderStorageImageWriteWithoutFormat, geometryShader, imageCubeArray…) are **not enabled**. Add them by mixing into `VulkanFeatureSets.optionalFeatureSets()` (`vk/VulkanFeatureSets.java:67-71`, return-value inject) with `new FeatureSet("Emrion", Set.of(), Set.of(new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "fragmentStoresAndAtomics"), ...))` (`vk/init/FeatureSet.java:18`, `vk/init/VulkanFeature.java:11`). Mixin must be applied before `Minecraft.<init>` creates the device (normal client mixin is fine).
5. Custom sampler (shadow compare / border): create own `VkSampler`; to bind it through the MC `RenderPass` you need a `VulkanGpuSampler` — mixin its constructor (`vk/VulkanGpuSampler.java:44-56`) to set `compareEnable` for a marker label/flag. sampler2DShadow itself passes reflection (2D dim).
6. Fully raw composite passes are viable: own `VkPipeline` (dynamic rendering, any formats/blend per target/viewport), sample MC textures in GENERAL layout, write MC targets — all ordered by `execute()` + barrier. This bypasses limits 3D/storage/compute/blend/viewport for post-processing.

Queue / frames-in-flight model:
- One `VulkanCommandEncoder`, `MAX_SUBMITS_IN_FLIGHT = 2` (`vk/VulkanCommandEncoder.java:52`), 2 command pools (`:61,84-86`), timeline semaphore `submitSemaphore` (`:72-81`). `submit()` (`:216-232`): end CB, signal timeline = submitIndex, `vkQueueSubmit2KHR` on **graphics queue**, then **CPU-wait for submit N-2** (5 s timeout → crash with checkpoints), reset pool, rotate destroy queue and transient memory. Exactly one `submit()` per frame (`Minecraft.java:1319`).
- Compute/transfer queues are created but unused by MC → async compute would need own submits + semaphores via `waitSemaphore/signalSemaphore`; not worth it initially.
- Avoid stalls: never `vkQueueWaitIdle`/`vkDeviceWaitIdle` per frame; never `GpuFence.awaitCompletion` on the current frame; read back timestamps/queries ≥2 frames later (`GpuQueryPool.getValue` empty until ready); destroy via `queueForDestroy`/`close()` (deferred). Every MC op already serializes the GPU (global barrier) — keep pass count low and batch draws; extra raw barriers on top cost little.

---

## 7. Risks / limits for Complementary-style packs

| Need | Status in MC 26.3 | Mitigation |
|---|---|---|
| Compute shaders / `dispatch` | Not in API (`ShaderType` VS/FS only) | Raw Vulkan via `encoder.execute(cb)`; push descriptors available. |
| Image load/store, SSBO (`imageStore`, `buffer {}`), atomics | Rejected by `PipelineBuilder` + no STORAGE usage + features off | Raw Vulkan + `textureUsageToVk` / `optionalFeatureSets` mixins; or only in raw passes. |
| 3D textures (`sampler3D`, `colorimg` 3D, LUTs) | `PipelineBuilder.java:300` throws; images always 2D; depth>1 rejected | Raw image + raw pipeline; or atlas-pack 3D LUT into 2D. |
| Array textures | `createTexture` rejects layers>1 (except cube) | Raw images. |
| 1D textures | throws (`:298`) | Use 2D Nx1. |
| 16 MRTs (`colortex0..15`) | Max 8 per pipeline (`ColorTargetState.MAX_COLOR_TARGETS`) and device `maxColorAttachments` (usually 8) | Packs write ≤8 per program in practice (DRAWBUFFERS); map per-program; 16 textures total is fine. |
| Per-buffer blend modes | All blended targets must share one `BlendFunction` (`RenderPipeline.java:394`); blend-off per target OK | Usually sufficient; otherwise raw pipeline. |
| Custom vertex attributes `mc_Entity`, `at_tangent`, `mc_midTexCoord`, `at_midBlock`, normals in terrain | `BLOCK` format has only Position/Color/UV0/UV2; VS inputs without a matching element throw (`PipelineBuilder.java:131-136`) | Extend terrain format: custom `VertexFormat` on our terrain pipelines + mixin section meshing (`SectionCompiler.java:142`, `BufferBuilder`) to emit extra attributes (≤16 attribs, 4-byte aligned). Normal/tangent can be derived in FS from `dFdx/dFdy` short-term. Large, Sodium-conflict-prone work item. |
| Geometry/tessellation shaders | Not supported; `geometryShader` feature not enabled | Rewrite packs' geometry stages (rarely needed); or raw pipeline + feature mixin. |
| Shadow samplers (`shadow2D`, hardware PCF) | No compare samplers | Manual compare in shader, or mixin `VulkanGpuSampler`. |
| Border clamp, mirrored repeat | Not available | Emulate in shader. |
| Sub-viewport rendering | Viewport = full attachment | Separate textures, or scale/offset in VS + scissor via `RenderArea`. |
| Depth formats | Pipelines hard-code `D32_SFLOAT` attachment | Use `D32_FLOAT` everywhere. |
| Depth convention | Reverse-Z (near=1, far=0), zero-to-one | Convert in generated prelude / use own projection for pack passes. |
| Mipmap generation (`colortexNMipmapEnabled`) | No blit/generate API | Downsample passes (or raw `vkCmdBlitImage`). |
| `gl_FragData`/compatibility GLSL, `texture2D`, `ftransform` | Pack sources are GLSL 120–460 for GL | Offline transpiler must emit Vulkan-GLSL with explicit locations, UBO blocks, named samplers. |
| Uniform count / loose uniforms | Only UBOs/push constants, ≤128 B push | Pack all Iris uniforms into one std140 UBO per frame (`transientMemory().uploadGpu`). |
| Pipeline compile stutter | Lazy `.join()` on render thread; no `VkPipelineCache` | Precompile at pack load in background; add persisted `VkPipelineCache` via `@ModifyArg`. |
| Translucency path | OIT ("improved transparency") changes the pass structure | Force classic path while a pack is active. |
| OpenGL backend | Same SPIR-V works via spvc→GLSL 330, but no raw hatches | Require Vulkan; disable packs on GL. |
| GPU serialization | Global barrier after every op | Minimize pass count; merge composites. |
| Mapping stability | `getCompiledPipeline` is the single choke point; vanilla may add call sites | Log unmapped pipelines during gbuffer phase. |
