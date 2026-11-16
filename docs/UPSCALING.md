# Upscaling (M8): Off / AMD FSR / NVIDIA DLSS / Intel XeSS

Status: design (M8 step 1), then implementation notes and results as they land. Priority on conflicts: optics, then FPS,
then everything else. **Off is exactly today's path**: no extra pass, no jitter, the converter request and its output are
byte-identical to master (golden test `TargetPackRtTest.rtOffOutputMatchesGolden`).

## 1. The cut

```
 internal resolution (render size)                                      | window size (display size)
 shadow → prepare → gbuffers (jittered) → deferred → composite* → last  | UPSCALE → final → MC main target → GUI
                                                       composite ──────▶| (FSR 3.1 compute or DLSS SR)
```

* Every pack pass runs at the **render size**: `PackRuntime` is created with the render size instead of the window size, so
  every screen-relative target (`relativeSize`, colortex, depthtex, RT AO, compute `workGroupsRender`) follows. The geometry
  passes need nothing: renderpearl sets the viewport from the attachment size (`VulkanRenderPass` constructor). The shadow map
  keeps its absolute size. `viewWidth`/`viewHeight`/`aspectRatio`/`iris_ScreenSize` report the render size.
* The upscaler runs right before `final`. Its colour input is the target `final` samples that the last command before
  `final` wrote (Complementary: `colortex3`, written by composite6/7). Its output is a raw window-size RGBA16F image.
* `final` runs at the window size with its own descriptor set: identical to the flip state's set except that the upscaled
  target's binding points at the upscaler output, and with its own copy of the frame uniform block where `viewWidth`,
  `viewHeight` and `iris_ScreenSize` are the window size (Complementary's final sharpening and chromatic aberration step in
  window pixels, as without upscaling). Other targets `final` reads stay at render size (sampled through normalised
  coordinates they still line up; a `texelFetch` on them would not, Complementary only does that behind options that are off
  in the target settings: `ENTITIES_ARE_LIGHT`, nether heat distortion, letterboxing).
* A pack without a `final` program: the blit to the main target reads the upscaler output instead of colortex0.
* MC's main target and the GUI stay at window size; nothing after `final` changes.

Why before `final` and not after it: `final` is the pack's display-space pass (sharpening, chromatic aberration, film grain,
text overlays); running it at window size keeps those effects pixel-exact. The colour at that point is already tonemapped
(Complementary tonemaps in composite), so both upscalers run in their LDR mode (FSR: no `HDR_COLOR_INPUT`; DLSS: no
`IsHDR`). Pre-tonemap colour would need pack-specific patches of the tonemap pass and its later LDR passes (bloom
composite, lens flare); not worth it while the LDR path looks right.

## 2. Inputs

| Input | Source |
|---|---|
| colour | the cut target above (render size, LDR) |
| depth | `depthtex0` (render size, D32, forward-Z GL window depth = D3D depth for the same near/far, so FSR's non-inverted, finite depth path applies directly) |
| motion vectors | our own compute pass `upscale_motion.comp`: per pixel, depth → world position (current unjittered inverse view-projection, camera-relative) → previous unjittered view-projection shifted by the camera delta → `prevUV − currUV`, RG16F at render size. The hand (depth < 0.56, Iris's hand depth range) gets zero there. Then moving entities, block entities, item entities, name tags and the first-person hand are drawn over it with their own vectors (§2.1). |
| reactive / transparency-and-composition masks | Complementary only: the `upscale` patch writes them into the pack's TAA history target colortex2 in place of its TAA (§2.2). FSR gets r as `reactive_mask` and g (a swizzled view) as `transparency_and_composition_mask`; DLSS gets r as `InBiasCurrentColorMask`. Other packs: no masks. |
| jitter | Halton(2,3) sequence, phase count `8·(display/render)²` (FSR's rule, DLSS uses the same), applied as an NDC translation to **every** pack projection: the per-phase `gl_ProjectionMatrix` (world and hand) and `gbufferProjection`/`Inverse`; `gbufferPreviousProjection` is last frame's jittered matrix. Shadow projections are not jittered. The deferred reconstruction (`gbufferProjectionInverse · depth`) therefore matches the rasterised depth exactly. |
| exposure | 1.0 (LDR input; FSR's internal luma tracking still runs) |
| reset | first frame of a runtime (resize, mode or preset change, pack reload), dimension change, camera jump > 16 blocks in one frame |
| sharpness | user setting 0–1: FSR's RCAS pass (0 = RCAS off). DLSS output is used as is (NGX's sharpness parameter is deprecated and ignored by the current models). |

### 2.1 Object motion vectors

Iris packs write no velocity, and MC rebuilds entity vertices every frame (already posed, camera-relative), so there is no
per-draw previous transform to hand to a shader. We match vertices by identity instead (`upscale/ObjectMotion`):

* **Key.** Every feature submit gets a key when it is created: its owner (entity network id, stored on the render state at
  `EntityRenderer.createRenderState`; block entity position; hand pass) plus its ordinal among that owner's submits. The submit
  records already carry the Iris ids this way (`FeatureSubmitIdsMixin`).
* **Slot.** While the world and hand feature frames build vertices, each submit gets a per-frame slot; `ExtendedBufferBuilder`
  writes slot + 1 into the unused w of `iris_Entity` (0 when nothing is tracked, as before). At
  `StagedVertexBuffer.uploadDrawsToBuffers` the positions of tracked ENTITY/GLYPH quads are read back with their slot and the
  vertex's ordinal within it.
* **Match.** Vertex i of key K this frame is vertex i of K last frame if K had the same vertex count (an armour layer appearing
  or a model swap simply falls back to camera motion for one frame). Quads where no vertex moved more than 1e-4 blocks
  (camera-relative; the hand is not shifted by the camera move) are dropped: the camera vectors are already right for them.
* **Draw.** The CPU transforms the moving quads to (current clip, previous clip), unjittered, with this and last frame's
  view-projection (hand: hand projection × view rotation, since `renderItemInHand` bakes the inverse view rotation into the vertices). `object_motion.vert/.frag` rasterise them into the motion
  image right after the camera pass, with the frame's jitter added to `gl_Position` so they cover the same pixels as the
  gbuffers. No depth attachment: the fragment shader compares its depth with `depthtex0` (within 2 % of the distance, so the
  pack's own TAA jitter with frame generation alone still matches) and discards hidden or alpha-cut pixels; hand quads do the
  same against the hand depth (NDC z × 1/8), so the held item's hidden back and side faces do not overwrite its front.
* Off is untouched: nothing is captured, keyed or drawn unless an upscaler or frame generation runs (`ObjectMotion.enabled`),
  and the converter output does not change (the masks below ride on the existing `upscale` effect).

Not covered: vertex-waved foliage and water (the pack moves them in its vertex shader; they get mask values instead),
particles and rain (mask), falling blocks and pistons (block format), and anything drawn outside the feature renderers.

### 2.2 Masks

Complementary's TAA already knows which pixels it should not accumulate (material ids in colortex6.g: 241 water, 253 leaves,
254 entities/particles/hand, 100–199 entities). With upscaling on, the `upscale` patch replaces `DoTAA` by
`rt/complementary/upscale_masks.glsl`, which writes into colortex2 (its history target, otherwise unused once TAA is gone):

| Pixels | reactive (r) | transparency/composition (g) |
|---|---|---|
| translucent in front (depthtex0 < depthtex1): water | rain alpha × 0.8 | 0.75 |
| other translucents (glass, ice, particles that write depth) | rain alpha × 0.8 | 0.45 |
| leaves (waving) | rain alpha × 0.8 | 0.35 |
| entities, particles (254) | rain alpha × 0.8 | 0.25 |
| hand (depth < 0.56) | 0 | 0 |

Rain is Complementary's improved rain (colortex12 alpha, read through a sampler the patch declares). In FSR 3.1 the reactive
mask feeds the shading-change term (shorter history, wider clamp) and the composition mask the final reactiveness (fewer
locks); DLSS only has the bias mask, so it gets the reactive channel. The DLSS shim gained `emrion_ngx_evaluate_mask`; an older
shim keeps working without the mask (rebuild with `mod/native/build-dlss.sh` to get it).

## 3. The pack's own TAA and jitter

Complementary (`TAA_DEFINE=1` in the target settings) jitters `gl_Position` in gbuffers (`TAAJitter`, 8 positions, scaled
by `TAA_JITTER`, 1/8 px by default), resolves in composite6 (`DoTAA`, neighbourhood clamp, history in colortex2) and runs
FXAA in composite7 (`FXAA311`). Upscaling replaces all three, through the RT patch machinery (`converter/.../rt/complementary-r5.json`,
effect `upscale`, bare patches, no RT library):

| Function | Patch | Why |
|---|---|---|
| `vec2 TAAJitter(vec2,float)` | `return coord;` | our jitter goes through the projection matrices (known exactly to the upscaler, longer sequence) |
| `void DoTAA(inout vec3,inout vec3,float)` | writes the masks of §2.2 instead | a second temporal filter in front of FSR/DLSS doubles the ghosting and blurs the jitter the upscaler needs |
| `void FXAA311(inout vec3)` | empty | FXAA before a temporal upscaler only removes detail |

`TAA` stays defined, so the pack keeps its frame-animated noise (dithered shadows, clouds, SSAO, reflections), which the
upscaler now accumulates. The effect name `upscale` rides in the converter's effect set (and cache key) like the RT effects;
the mod adds it while a mode other than Off is selected, so Off keeps the empty set. Packs without a matching table keep
their TAA: the upscaler still works but stacks on the pack's TAA (status line says so).

Because the patch changes the conversion, switching Off ↔ FSR/DLSS goes through the pack reload path (reconvert or cache
hit, swap between frames, no restart). FSR ↔ DLSS, preset and sharpness changes only rebuild the runtime (a resize).

## 4. RT interaction

RT passes run at render size like every pack pass. RT AO (`RtAoPass`) and RT GI temporal passes reproject with
`gbufferProjection`/`gbufferPreviousProjection` (or the view-projection built from them): both carry this and last frame's
jitter, so reprojection lands on the jittered history pixel, which is correct; their accumulation now also averages over the
jitter, which the upscaler sees as slightly softer AO/GI (by < 1 render pixel).

## 5. FSR (AMD FidelityFX FSR 3.1 upscaler, MIT)

* Source: FidelityFX SDK **v1.1.4** (`GPUOpen-LibrariesAndSDKs/FidelityFX-SDK`, the last release with the Vulkan backend
  and GLSL shaders): `sdk/include/FidelityFX/gpu` (`ffx_core`, `fsr3upscaler/`, `spd/`, `fsr1/` for RCAS) and the eight
  `sdk/src/backends/vk/shaders/fsr3upscaler/*_pass.glsl`, vendored unmodified under `mod/src/main/glsl/fidelityfx/` with
  AMD's licence file. The build compiles them with `glslc` (like `mod/src/main/glsl`) to `assets/emrionshaders/spv/fsr3/`
  with one fixed permutation: LDR, render-size motion vectors, unjittered motion vectors, forward depth, Lanczos LUT in
  reproject, no FP16, plus the sharpening variant of accumulate. No `-O`: spirv-opt strips the resource names the host
  binds by; the driver optimises the SPIR-V anyway.
* Host side: `upscale/Fsr3Upscaler.java` is a Java port of the dispatch logic of `ffx_fsr3upscaler.cpp` (constants,
  resource ping-pong, clears, SPD setup, RCAS constants, Halton jitter, quality ratios). Resources are raw VMA images,
  descriptors are pushed per pass (bindings found by SPIR-V reflection by name, as the SDK's backend does), one full
  compute→compute barrier between passes. Runs on every Vulkan GPU, no native code.
* Presets: Native AA 1.0, Quality 1.5, Balanced 1.7, Performance 2.0, Ultra Performance 3.0 (FSR's ratios).

## 6. DLSS (NVIDIA DLSS Super Resolution through NGX)

* NGX core is part of the proprietary driver (`libnvidia-ngx.so.1`). The DLSS model (`libnvidia-ngx-dlss.so.<ver>`) comes
  with the DLSS SDK (`NVIDIA/DLSS` on GitHub, `lib/Linux_x86_64/rel/`). The SDK's documented API is reached through its
  static library `libnvsdk_ngx.a`, which Java cannot load, so a 100-line C shim (`mod/native/emrion_ngx.c`, ours) links it
  and exposes a flat C API that `upscale/DlssUpscaler.java` calls through Java FFM (Panama). LWJGL 3.4.3 has no NGX
  bindings.
* **Licence (2026-11-14): the shim ships, the models are downloaded.** The RTX SDK licence allows redistribution "as
  incorporated in object code format into a software application" (§1c) on terms "at least as protective" (§2c), and
  forbids subjecting the SDK to an open-source licence (§4e). So the shim *source* is MIT (`mod/native/LICENSE`, no
  NVIDIA source), while the compiled shim, which statically contains `libnvsdk_ngx.a`, is under `LICENSE-DLSS.txt`
  (NVIDIA's restrictions restated, explicitly outside the LGPL). It is bundled in the jar as
  `natives/linux-x86_64/libemrion_ngx.so` (built with `build-dlss.sh --shim <file>`; reproducible with the same
  toolchain) and `natives/windows-x86_64/emrion_ngx.dll` (not built yet: DLSS stays unavailable on Windows until it is
  added). `DlssFiles` extracts it into `<gameDir>/emberlight/dlss/` at start when missing or different; when that
  fails (an older shim still loaded by another running game) DLSS stays unavailable instead of loading the other shim.
* The models (`libnvidia-ngx-dlss(g).so.310.9.16`, `nvngx_dlss(g).dll`, ~67 MB) are never bundled. On the first DLSS or
  DLSS FG selection a consent screen offers to download them, with NVIDIA's `LICENSE.txt`, from
  `raw.githubusercontent.com/NVIDIA/DLSS/v310.9.16/` (plain git blobs, not LFS), checked against SHA-256 hashes pinned in
  `DlssFiles` (temp file, then atomic move). One restart follows (device extensions, below). Attribution
  ("NVIDIA, NVIDIA RTX and DLSS are trademarks of NVIDIA Corporation") is on the Upscaling, Frame Generation and consent
  screens and in `fabric.mod.json`'s description (Mod Menu's about box). `build-dlss.sh` / `.ps1` remain the manual
  route. The mod looks in `<gameDir>/emberlight/dlss/` (or `-Demrion.upscale.dlssDir=`). NGX searches that directory for
  the model (`FeatureCommonInfo.PathListInfo`) and writes its logs to `logs/` there with `-Demrion.upscale.dlssLog=true`.
  Every NGX library there (`libnvidia-ngx-*` / `nvngx_*`) is hashed against the pinned SHA-256 at start, before NGX can
  load it (a modpack could ship a modified one): an unknown or modified file keeps DLSS off with the file's name in the
  reason, and the consent screen offers the download again. No hash cache: the three models hash in ~0.1 s, and a cache
  in the game directory (size + mtime) could be shipped by the same modpack.
* Windows (untested so far): `powershell -ExecutionPolicy Bypass -File mod/native/build-dlss.ps1 <gameDir>` does the
  same with the Visual Studio Build Tools (C++ workload) and git: it fetches the SDK's `nvsdk_ngx_d.lib` and
  `nvngx_dlss.dll`/`nvngx_dlssg.dll`, plus the Vulkan headers from KhronosGroup, and installs `emrion_ngx.dll` next to them.
* Device: an optional "Emberlight DLSS" feature set adds the device extensions NGX requests
  (`NVSDK_NGX_VULKAN_RequiredExtensions`: `VK_NVX_binary_import`, `VK_NVX_image_view_handle`, `VK_KHR_push_descriptor`),
  only when the shim and model are present at start. NGX also lists `VK_EXT_buffer_device_address`; it must not be enabled together
  with core `bufferDeviceAddress` (which the RT set enables), so the DLSS set requests the core feature instead (NGX accepts
  it). Downloading the DLSS models therefore needs one game restart; after that, switching is live.
* NGX is initialised with a project id (`NVSDK_NGX_VULKAN_Init_with_ProjectID`, engine type CUSTOM) at the first DLSS use,
  on a helper thread with a 64 MiB stack: loading the model on the render thread (1 MiB JVM stack) crashed the JVM.
  Evaluation runs on the render thread into our command buffer. All NGX features are released and NGX is shut down at
  client stop, before MC destroys the device (it crashed at exit otherwise), also when NGX came up but DLSS SR then
  reported itself unsupported. The caller waits for the helper thread even when interrupted (its arena holds the call's
  arguments).
* Unavailable (non-NVIDIA GPU, no shim or model, NGX init or feature creation fails): the UI shows
  `DLSS unavailable: <reason>` and the mode falls back to Off.
* Presets map to `NVSDK_NGX_PerfQuality_Value_*` (MaxQuality, Balanced, MaxPerf, UltraPerformance, DLAA); the render size is
  the same ratio as FSR's (DLSS accepts any render size in the preset's range), so both modes render the same pixel count.
* Inputs: colour (LDR, no `IsHDR`), depth, our motion vectors with `MVLowRes` and `InMVScale` = render size (they are UV
  offsets), jitter in render pixels (same sign as FSR's, verified: flipping it drops forest SSIM from 0.973 to 0.873),
  reset, frame time.

## 7. Config and UI

`config/emberlight.properties`: `upscale.mode` = off | fsr | dlss | xess, `upscale.preset` = native | quality | balanced |
performance | ultra, `upscale.sharpness` = 0.0–1.0. System properties override (`-Demrion.upscale.mode=fsr`,
`-Demrion.upscale.preset=`, `-Demrion.upscale.sharpness=`). Page "Upscaling..." under "Performance..." in the shader options screen.
The autotest script step `upscale off|fsr|dlss|xess[:preset]` drives the same apply path (used for the switch test below).

## 8. Frame generation later (M9)

The upscaler already produces what FSR 3 frame generation needs: render-size depth, dilated motion vectors (FSR keeps them
as shared resources), the jitter and the camera data. M9 adds a swapchain proxy that interpolates between the HUD-less
upscaled frames (the image `final` writes, before MC draws the GUI) and composites the GUI on top.

## 9. Results (2026-11-13, RTX 4070 Ti, driver 615.78.08, 2560×1440, target pack and settings, RT off)

### FPS (scene `overview`, 30 s, `-Demrion.autotest.benchmark.seconds=30`, one run each)

| Mode | Render size | Avg FPS | 1% low | Upscaler GPU cost |
|---|---|---|---|---|
| Off (native, pack TAA) | 2560×1440 | 95.8 | 75.9 | — |
| FSR Native AA | 2560×1440 | 89.5 | 73.3 | ≈ 0.7 ms |
| FSR Quality | 1706×960 | 128.2 | 101.8 | 0.60 ms (accumulate 0.27, RCAS 0.06, rest ≤ 0.06 each; motion vectors 0.02) |
| FSR Balanced | 1505×847 | 141.9 | 118.3 | |
| FSR Performance | 1280×720 | 156.7 | 129.5 | |
| FSR Ultra Performance | 853×480 | 190.4 | 157.1 | |
| DLSS DLAA | 2560×1440 | 87.1 | 69.4 | |
| DLSS Quality | 1706×960 | 122.3 | 100.7 | |
| DLSS Balanced | 1505×847 | 133.7 | 110.8 | |
| DLSS Performance | 1280×720 | 138.8 | 112.8 | 1.21 ms |
| DLSS Ultra Performance | 853×480 | 170.7 | 134.0 | |

FSR Quality: +34 % over native, DLSS Quality +28 %. DLSS 310.9 (transformer model, NGX's default preset) costs about
twice FSR's GPU time at 1440p, so at equal render size it is a few FPS behind FSR; it is the sharper and more stable of
the two (below).

### Image quality versus native (2560×1440, still camera after settling; `mod/tools/compare_scenes.py`, MAE per channel on 0–255, SSIM of luma)

| Scene | FSR Quality MAE / SSIM | DLSS Quality MAE / SSIM |
|---|---|---|
| overview | 3.09 / 0.953 | 1.97 / 0.962 |
| forest | 2.43 / 0.967 | 1.77 / 0.973 |
| water | 2.31 / 0.964 | — |
| cave | 0.67 / 0.992 | — |
| sunset | 1.69 / 0.973 | — |
| night | 0.65 / 0.993 | — |
| rain | 1.68 / 0.960 | — |
| entities | 2.42 / 0.966 | — |

(The DLSS all-scene run was interrupted by a GPU hand-over to the user; forest and overview are from a separate run.)
The reference is our native path with the pack's TAA, so part of the difference is the pack's TAA versus the upscaler's.
Visually (side-by-side crops): foliage edges and block textures stay crisp at Quality with both; FSR is slightly softer on
distant leaves, DLSS closest to native; clouds are indistinguishable. Under water (refracted kelp) FSR is visibly softer:
translucent water carries no reactive/transparency mask, so FSR keeps more history there.

Jitter sign check (forest / overview SSIM against native): FSR 0.967 / 0.953 with our sign, 0.919 / 0.867 flipped; DLSS
0.973 / 0.962, flipped 0.873 / 0.855. So both take the jitter with the same sign as the projection offset.

Motion: a scripted strafe-and-turn through the forest (24 ticks), shot right after it, shows no visible trails on leaves or
trunks for Off, FSR Quality, DLSS Quality or FSR Performance. The harness cannot capture mid-motion, so fast-motion
ghosting (and moving mobs, which get no motion vectors) still needs a look in play.

Switching in one session (`upscale` script step): FSR → DLSS (runtime rebuild only), DLSS → Off (reload, cache hit 44 ms,
no chunk rebuild), Off → FSR Performance (reload) — all without restart and without errors. DLSS init failures fall back to
rendering without upscaler (tested before the arena fix: "DLSS unavailable: NGX init failed", pack kept running).

Off: the converter request is the empty set as before (golden test `rtOffOutputMatchesGolden` green), no jitter, no extra
pass; the only runtime difference is the window-size check against `displayWidth` (equal to the render size when off).

## 10. Without a shader pack (`feat/vanilla-upscale`, 2026-11-16)

Upscaling and frame generation also run when no pack is selected (or the pack is toggled off). `upscale/VanillaUpscale`,
all no-ops unless this frame has no pack and an upscaler or FG is on:

* **Cut:** before the hand. In `renderLevel`, right after `projection.mul(bob)`, the level projection gets the Halton jitter
  (an NDC translation on the left, so bob, hurt tilt and nausea stay unjittered). At `LevelRenderer.render` HEAD the textures
  of a render-size `TextureTarget` are swapped into MC's main target (and the entity outline target resized to match), so
  sky, terrain, entities, particles, weather and clouds render at render size with MC's own pipelines. Before `render3dHud`
  the level is upscaled, the textures are swapped back and the upscaler output is blitted into the display-size main target.
  Hand, screen effects, entity outline blit, post effects (spider/creeper) and GUI then draw at display size as without
  upscaling. A frame that throws mid-level is swapped back at the next `GameRenderer.render` HEAD.
* **Depth:** MC's level depth is reverse Z. `vanilla_depth.comp` writes forward GL window depth (1 − d) into a buffer that
  is copied into a D32 image, so the camera motion pass, FSR, DLSS and both frame generators take it unchanged. The
  "view" handed to them is P⁻¹ · (unjittered level projection) · view rotation, so bob and nausea count as camera motion.
* **Frame generation:** gets the same inputs (with upscaling off: native-size depth, its own motion pass). After the hand,
  pixels where the display depth is nearer than the level get hand depth and zero motion, so FG does not drag the hand.
* **Sodium (0.9.3-beta.5, no Iris):** works without extra hooks. Sodium takes its terrain projection from the matrix MC
  passes to `ProjectionMatrixBuffer.getBuffer` in `renderLevel` (its `GameRendererMixin`), which is the projection the jitter
  already changed in place, and it draws into MC's main target, so its terrain is jittered and at render size. Checked by
  screenshot: DLSS/FSR Quality give anti-aliased terrain edges, FG DLSS/FSR interpolated frames of a turn and of a sideways
  move are clean, and the motion field of the move follows terrain depth.
* **Pack on/off:** while a pack renders, `jitter()` is not called, so `beginLevel` frees the render-size target and the
  upscaler (otherwise they stayed allocated next to the pack's own upscaler). Pack on → off three times with DLSS + FG DLSS:
  no errors, the vanilla upscaler is created again after each off.
* **Screenshots:** F2 saves MC's main target after the frame, i.e. the upscaled display-size image with the GUI at display
  size, as without upscaling (2560×1440 checked).
* **Not covered:** no reactive masks (water, glass, particles accumulate like opaque surfaces) and no object motion vectors
  (moving mobs rely on the upscaler's disocclusion handling). Panorama/screenshot-cubemap mode renders natively.
  A reactive mask is not cheap here: it needs the colour before translucents, but MC draws opaque and translucent geometry
  (terrain, Sodium's too, particles, clouds, weather) in one render pass (`addMainPass` → `executeSolid` +
  `executeClassicTransparency`), so a copy would mean splitting MC's main pass. Water and glass in a DLSS + FG turn looked
  clean without it.

Results (RTX 4070 Ti, 2560×1440 in the gamescope test display, `overview`, 8 s each, exclusive GPU):

| Mode | Rendered FPS | Presented |
|---|---|---|
| Off | 1398 / 1440 | — |
| FSR Quality | 769 | — |
| DLSS Quality | 613 | — |
| DLSS Performance | 576 | — |
| FG FSR, no upscaling | 332 | 664 |
| FG DLSS + DLSS Quality | 233 | 467 |

Vanilla here is CPU-bound far above any refresh rate, so the upscaler's fixed cost (≈ 0.6 ms FSR, ≈ 0.9 ms DLSS) and FG's
cost only lower the FPS; the feature pays off on GPUs or resolutions where vanilla itself is GPU-bound, or for DLSS/FSR's
anti-aliasing. Image (still camera after settling, vs native): forest MAE 2.1 / 1.9 (FSR / DLSS Quality), overview
3.6 / 3.2; hand, chat and hotbar are pixel-sharp at display size; glowing outlines, bob, nausea, spyglass, F1, resize and
fullscreen checked by screenshot; FG interpolated frames of a scripted turn look right.

## 11. DLSS Ray Reconstruction (`feat/ray-reconstruction`, 2026-11-16)

DLSS Ray Reconstruction (RR, NGX feature `NVSDK_NGX_Feature_RayReconstruction`, "DLSS-D", model
`libnvidia-ngx-dlssd.so.310.9.16` / `nvngx_dlssd.dll`) is DLSS SR with a denoiser trained into it: it takes the *noisy*
ray-traced image plus guide buffers and denoises and upscales in one step. Option "Ray Reconstruction" on the Upscaling
page (`upscale.rr`, default off).

### 11.1 Design (decided before coding)

**Where RR runs.** NVIDIA wants RR "before tone mapping, as close to the start of post-processing as possible", on the
noisy lighting. In Complementary there is no such point: RT shadows, RT block light and RT GI are applied *forward* in
the gbuffers (`DoLighting`, see RAYTRACING.md §3.1/§16/§18), so the noisy terms are multiplied with the albedo, fogged,
reflected and bloomed inside the pack's own passes long before anything we control. Moving the lighting out of the
pack would mean rewriting the pack. RR therefore runs at the **same cut as DLSS SR** (§1): the pack's composite output
(colortex3, LDR, after tonemap and bloom), right before `final`, render size → display size. Consequences, accepted:
* LDR, no `IsHDR` (as SR). RR documents no exposure input; the tonemapped image is a fixed curve of the radiance, so
  colour/albedo still behaves like lighting (both are display-encoded, and a power-law encoding keeps products as
  products).
* Fog, the pack's water/sky, bloom and the pack's frame-animated dither (SSAO, clouds, volumetrics) arrive already
  composited; RR sees them as part of the signal (no "colour before fog/transparency" guides).

**What it replaces** (only while RR runs; FSR, DLSS SR, other GPUs and RT off keep everything as before):

| Denoiser | With RR | How |
|---|---|---|
| RT GI temporal (`gi_temporal.csh`, RTX: `prepare_b`) | **shortened** to 4 frames (from 24) | request `gi:frames=4`; see §11.4 why not 1 |
| RT GI young-history boost (`gi_trace.csh`) | off | `gi:boost=1` (with the short history most texels would count as "young" and get 2× rays) |
| RT GI à-trous (`gi_atrous_c/d/e`) | off | new option `gi:filter=0`: the passes copy the temporal result through |
| RTX block light: ReSTIR denoiser (`bl_restir_c` temporal, `bl_restir_d` à-trous) | temporal shortened to 4 frames (from 8), à-trous off | `blocklight:frames=4`, new option `blocklight:filter=0` |
| RTX block light: ReSTIR resampling itself (`bl_restir_a/b`, temporal + spatial reservoir reuse) | **kept** | it is a sampler, not a filter: NVIDIA's guide recommends ReSTIR as RR input |
| RT AO (`RtAoPass`: 32-frame history + edge-aware filter) | off | negative ray count in the push constants: no history, no filter |
| RT sun shadows | nothing to turn off | they had no own filter: their jittered rays were already averaged by the upscaler (the "shadow TAA") |

These options ride in the converter request (and so in the cache key), like `upscale` itself: switching RR on or off
reconverts the pack (cache hit after the first time), without restart. Faithful and RTX style both work (the options
exist in both GI variants; block light only has a denoiser in RTX). **RT off**: RR is not selectable (it would have no
ray-traced noise to remove and no TLAS for its albedo guide); the tile says why and DLSS SR runs.

**Guide buffers** (new compute pass `rr_guides.comp`, `upscale/RrGuides.java`, render size, after the motion vectors):

| RR input | Source |
|---|---|
| colour | the cut target (as SR), copied by the guide pass with NaN/Inf set to 0: one NaN pixel from the raw RT terms became a black 16–32 px tile in RR's output |
| depth | `depthtex0`, hardware depth (`InUseHWDepth`) |
| motion vectors | ours (§2, §2.1), as SR |
| diffuse albedo | **terrain**: the view ray to the depth-buffer surface is traced against the TLAS (solid + cutout, alpha-tested); if its hit is that surface (±1 % + 3 cm), the block-atlas texel × vertex tint, mip chosen from the pixel footprint (texels per block measured from the quad's UVs, so HD packs work). **Everything else** (sky, hand, entities, water, glass, waved foliage, lava, particles): the input colour itself, so RR sees "lighting 1" there and keeps the detail instead of treating it as noise. NVIDIA suggests grey 0.5 for the sky; the colour keeps stars and clouds |
| specular albedo | NVIDIA's `EnvBRDFApprox2` (from the RR guide's appendix) with F0 0.04, the roughness below and N·V; 0 for the sky |
| normals + roughness | packed in one RGBA16F (`Roughness_Mode_Packed`): Complementary's colortex4.xyz (world normal with normal maps, written by deferred1 and gbuffers_water) and 1 − colortex6.r (its smoothness) when the `upscale` patch ran; otherwise normals from depth and roughness 1. **Water** (composition mask 0.75): the flat surface from depth and roughness 1, so RR does not treat the refracted seabed as a mirror (with the wave normal and roughness 0 it drew a fine crosshatch over it; NVIDIA's guide recommends constant guides for noisy refraction) |
| matrices | world (camera-relative) → view = `gbufferModelView`; view → clip = the unjittered projection converted to NGX's D3D clip (y flipped for our GL row order, z to [0, 1]). The model reads them even without a specular hit distance: passing none crashed it |
| bias mask | Complementary's reactive mask (§2.2), as SR (copied by the guide pass) |
| particle mask (`pInIsParticleMask`) | Complementary's composition mask = 0.25 (particles, and entities which ride along; their albedo is their colour, so RR does not denoise them anyway): without it RR smeared the redstone-torch particles into a red haze |
| jitter, reset, frame time | as SR |

Not provided (optional in RR): specular hit distance / specular motion vectors (RR estimates reflection motion from
normals, roughness and depth), transparency layer, colour-before-transparency, DOF guide. Jitter: RR asks for ≥ 32
phases; our sequence has `8·(display/render)²` (18 at Quality), as for SR.

**Frame generation and Reflex**: RR writes the same display-size output image SR writes (`Upscaler.output`), so DLSS FG,
FSR FG and the HUD-less capture consume it unchanged; Reflex markers are unaffected. Nothing in `framegen/` changed.

**Fallback**: RR needs DLSS available, RT on, the dlssd model and NGX's `SuperSamplingDenoising.Available`. If feature
creation fails (old shim, old driver, GPU without support), the reason is recorded, DLSS SR runs, and the pack is
reconverted without the denoiser bypass (`PackManager.resizeTimed`). The Upscaling page shows the reason under the tile.

### 11.2 Native shim

`emrion_ngx.c` gained `emrion_ngx_rr_available`, `emrion_ngx_rr_create` (`NGX_VULKAN_CREATE_DLSSD_EXT1`, DL-unified
denoise mode, packed roughness, HW depth, `MVLowRes`) and `emrion_ngx_rr_evaluate` (`NGX_VULKAN_EVALUATE_DLSSD_EXT`).
Java binds them optionally: an older shim simply reports RR unavailable. Linux: `build-dlss.sh --shim
mod/src/main/resources/natives/linux-x86_64/libemrion_ngx.so` (rebuilt). Windows: the same source change applies; the
DLL must be rebuilt in the Windows VM with `build-dlss.ps1` (no source changes needed there: `emrion_ngx.c` includes
`nvsdk_ngx_helpers_dlssd_vk.h` from the same SDK v310.9.16; the link line is unchanged) and copied to
`mod/src/main/resources/natives/windows-x86_64/emrion_ngx.dll`.

### 11.3 Model download and licence

`DlssFiles` downloads `lib/Linux_x86_64/rel/libnvidia-ngx-dlssd.so.310.9.16` (48 238 232 B, SHA-256 `a5d59de1…fbfb96`)
or `lib/Windows_x86_64/rel/nvngx_dlssd.dll` (48 343 664 B, `b49435ae…46cee`) from the same tag of github.com/NVIDIA/DLSS
(plain git blob, not LFS; checked against the repository's blob hash). Users who already downloaded SR/FG see the download
tile again (only the missing file is fetched). Like the other models it is never bundled; LICENSE-DLSS.txt, NOTICE and
README name it.

### 11.4 Results (2026-11-16, RTX 4070 Ti, driver 615.78.08, 2560×1386, user config: RTX, GI high, shadows full, 4 shadow rays, AO, block light 2 rays, refraction/caustics, DLSS Quality + DLSS FG + Reflex on, Sodium beta.5, Complementary Unbound r5.9.3 + EP 1.10.5)

Images: the user's snapshots replayed on the test server (snapshot step, then two consecutive real frames with `fgshots`),
one run with SR and one with `-Demrion.upscale.rr=true`; forest (noon) and cave (night) in the scene world. Crops were
looked at one by one. "Flicker" = mean |luma difference| (0–255) between two consecutive real frames of a still camera
(boiling, shimmer); "mean" = mean luma, to see brightness shifts.

| Scene | SR flicker / mean | RR (history 1) flicker / mean | RR (history 4, default) flicker / mean |
|---|---|---|---|
| torch hall | 0.067 / 58.0 | 0.075 / 55.8 | 0.025 / 58.6 |
| chest room | 0.084 / 56.5 | 0.085 / 53.5 | 0.046 / 57.2 |
| corridor (redstone torches) | 0.061 / 33.0 | 0.025 / 30.3 | – |
| repeaters | 0.094 / 34.9 | 0.033 / 33.1 | – |
| water, river steps | 0.208 / 82.7 | 0.072 / 80.4 | – |
| water, kelp | 0.386 / 95.7 | 0.685 / 95.3 | 0.438 / 96.0 |
| water, pool with squids | 0.303 / 148.4 | 0.215 / 147.4 | – |

What it looks like:
* **Better**: RR denoises the raw RT input completely (the bypassed input is visibly grainy, see the hall's "noisy SR"
  reference) and the still image boils less than SR in every dry scene (flicker −30 to −70 %). Block textures stay as crisp
  as SR (the albedo guide carries them); the pack's dithered emissive flames and their black speckle become a soft flame.
  Motion (turn and strafe, with FG): no extra ghosting on terrain; the torch-lit hall is noticeably cleaner in motion.
* **Worse / caveats**:
  * With no history at all (history 1) the raw GI and block-light samples pass through the pack's non-linear lighting
    (`sqrt` of squared terms), so walls came out 2–4 % darker than SR and the kelp water boiled more. A 4-frame history
    (the default now) removes the darkening and halves the flicker; the remaining denoising is RR's.
  * Under water (kelp scene) RR stays slightly grainier than SR even with the flat water guides (flicker 0.44 vs 0.39);
    the river and pool scenes are calmer than SR.
  * Pixel-art flames on torches are softened (RR treats the dithered flame as noise); particles are kept sharp by the
    particle mask.
  * Placing a torch: both react in the first frame. Removing it: RR keeps a faint ghost of the torch for about 1–2
    frames, SR none.
  * One black tile in a turn (before the NaN sanitising); not seen again afterwards (only stills and two turns re-checked).
  * Whether RR drops thin animated detail under water (squid tentacles) could not be decided: the squid pose differed
    between runs.

FPS (scene world, 15 s each, exclusive GPU, Reflex on, DLSS FG on; rendered = without generated frames):

| Scene | SR rendered FPS (1 % low) | RR rendered FPS (1 % low) | SR / RR presented | RR cost |
|---|---|---|---|---|
| forest, noon | 65.0 (57.2) | 58.6 (53.7) | 130 / 117 | +1.65 ms median |
| cave, night | 82.4 (67.4) | 74.3 (66.0) | 165 / 149 | +1.41 ms median |

RR costs about 10 % (≈ 1.5 ms per frame at 1706×924 → 2560×1386) more than SR plus our denoisers, even though the
à-trous passes are skipped. (Measured with the history-1 variant; the history length does not change the work done.)

Not checked: the End snapshot (the snapshot replay hung on the dimension change, a harness problem), Faithful style with
RR (the options exist there, not looked at), FSR frame generation with RR, Windows.

## 12. Intel XeSS Super Resolution (`feat/xess`, 2026-11-16)

**Windows only, not run yet.** Intel ships XeSS for Windows only: the SDK release (`intel/xess` on GitHub, latest
**v3.0.2**, 2026-07-24) contains `libxess.dll` (DX12 + Vulkan), `libxess_dx11.dll`, `libxess_fg.dll` and `libxell.dll`, and
no `.so`; every older release (1.3 to 3.0.1) is the same. So on Linux the mode is not offered: the Upscaling page skips it
when cycling, and a config that says `xess` runs FSR (status "Windows x86_64 only (Intel ships no Linux runtime)").

* **Licence (2026-11-16):** Intel Simplified Software License (Oct 2022): use and redistribution in binary form allowed,
  unmodified, with Intel's copyright notice and the terms reproduced; no reverse engineering. Bundling would be allowed, but
  `libxess.dll` is 78 MB (60 MB compressed), 30× the whole jar today, for a Windows-only feature. So it follows DLSS's
  pattern instead: on the first XeSS selection the page offers a download (`XessDownloadScreen`) of
  `XeSS_SDK_3.0.2.zip` (76.8 MB, SHA-256 `88b8a373…71990`, pinned in `Xess.ZIP`) from
  `github.com/intel/xess/releases/download/v3.0.2/` (Intel publishes no runtime-only download). `bin/libxess.dll` and
  `LICENSE.txt` (saved as `INTEL-XESS-LICENSE.txt`) are extracted into `<gameDir>/emberlight/xess/` (or
  `-Demrion.upscale.xessDir=`), the zip is deleted, and the DLL's SHA-256 (`251659dd…d5b7`) is checked after extraction
  and again before every load. Nothing of Intel's is in the repository or the jar. One restart follows (device features).
* **No shim:** the XeSS SDK exports a plain C API, so `upscale/Xess.java` binds `xessVKCreateContext`, `xessVKInit`,
  `xessVKExecute`, `xessSetVelocityScale` and `xessDestroyContext` from `libxess.dll` directly with FFM. Struct offsets
  (`xess_vk_execute_params_t` 360 bytes, `xess_vk_image_view_info` 48, `xess_vk_init_params_t` 64; 8-byte packing, no
  `long` fields, so LLP64 = LP64) were checked against the v3.0.2 headers with `offsetof`. Create and init run on the
  64 MiB helper thread DLSS uses (model upload).
* **Device:** XeSS needs `VK_EXT_mutable_descriptor_type` + `mutableDescriptorType` and `shaderStorageImageWriteWithoutFormat`
  (the pack sets already request it); `shaderInt8` and `shaderIntegerDotProduct` speed up its DP4a path. They are three
  optional feature sets (`Xess.featureSets`), added only when `libxess.dll` is installed, so Off and Linux create the same
  device as before. Hardcoded from the developer guide rather than queried with `xessVKGetRequiredDeviceExtensions`, which
  needs the instance and physical device that MC's static feature-set list does not have.
* **Presets:** XeSS 1.3+ ratios differ (Quality 1.7, Balanced 2.0, Performance 2.3). The render size stays ours (same pixel
  count as FSR and DLSS), handed to XeSS as a dynamic input resolution under the tightest XeSS preset whose range covers it:
  Native AA → AA (1.0), Quality 1.5 → Ultra Quality, Balanced 1.7 → Quality, Performance 2.0 → Balanced, Ultra
  Performance 3.0 → Ultra Performance (unit test `XessUpscalerTest`).
* **Inputs:** colour with `XESS_INIT_FLAG_LDR_INPUT_COLOR` and exposure 1.0 (§1), depth `depthtex0` as D32 forward depth
  (no `INVERTED_DEPTH`), low-res motion vectors (our UV offsets current → previous, `xessSetVelocityScale(renderW, renderH)`
  = pixels, the convention XeSS documents), jitter in render pixels with FSR's sign (XeSS documents the same projection
  shear, `P[2][0] += 2Jx/W`, `P[2][1] -= 2Jy/H`), reset as for FSR/DLSS. No responsive mask yet (the init flag is fixed per
  context while our masks come and go per pack and per `-Demberlight.upscale.masks`). Sharpness is not used.
* **Layouts:** XeSS reads inputs in `SHADER_READ_ONLY_OPTIMAL` and writes the output in `GENERAL`; ours are all `GENERAL`,
  so colour, motion and depth (mip 0) are transitioned around `xessVKExecute`.
* **Fallback:** a failed load or context creation is recorded like DLSS's (`Xess.failCreate`) and the mode runs FSR until
  the next settings change; the no-pack path (§10) and frame generation (FSR FG; DLSS FG keeps its own rules) take XeSS
  like the other upscalers through `Upscaler.create`.

**To do on Windows (VM / test machine)**, nothing to build: `./gradlew :mod:build`, install the jar, then

1. Upscaling page → Intel XeSS → *Download Intel XeSS runtime*; check `<gameDir>/emberlight/xess/` holds `libxess.dll` and
   `INTEL-XESS-LICENSE.txt` and no zip; restart. The log must show the three "Emberlight XeSS" sets enabled (or the reason).
2. XeSS Quality with the target pack, still camera: compare against FSR and DLSS Quality (`mod/tools/compare_scenes.py`,
   forest + overview), and a turn. Then `-Demrion.upscale.jitterSign=-1`: SSIM must drop (as for DLSS, §6); if it rises,
   flip XeSS's jitter. A moving camera smearing in the wrong direction means the velocity sign is off
   (`xessSetVelocityScale(-renderW, -renderH)`).
3. Without a pack, XeSS + FG FSR, and XeSS + FG DLSS on an RTX card; preset changes and resizes (context rebuilds), pack
   reload, and quitting the game (contexts are destroyed through the destroy queue; if exit crashes, destroy them before the
   device as `Dlss.shutdown` does).
4. Enable the Vulkan validation layer once: no layout or descriptor errors from the transitions or XeSS's own passes.
5. FPS (`overview`, 30 s) for XeSS against FSR and DLSS, on an NVIDIA (DP4a) and if possible an Intel Arc (XMX) GPU.

## 13. Open issues

* No reactive / transparency mask: translucent water, glass, particles and the pack's clouds-over-terrain blend are
  accumulated like opaque surfaces (slight softness and possible trailing on animated translucents). A mask could come from
  the pack's material buffer (Complementary colortex6) or from FSR's auto-reactive pass (needs the pre-translucent colour).
* Moving entities, the item in hand when it animates, and vertex-waved foliage have no motion vectors (Iris packs do not
  write any); the upscalers' disocclusion/locking handles most of it, fast mobs may smear.
* LDR input: bloom and the pack's tonemap run before the upscaler at render size; HDR input would need pack patches.
* `final`'s `texelFetch` of other render-size targets (Complementary only behind options that are off in the target
  settings) would read the wrong texels.
* DLSS needs the user-built shim and one restart after installing it; only tested on this RTX 4070 Ti / 615.78.08.
* The DLSS all-scene image comparison and FSR/DLSS Performance image runs were cut short (GPU handed to the user).
* XeSS (§12) is untested: Windows only, the list there has to be run on Windows; no responsive mask for XeSS.
