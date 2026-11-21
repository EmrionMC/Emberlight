# Frame generation (M9): Off / AMD FSR 3 Frame Generation / NVIDIA DLSS Frame Generation

Status: design first, then implementation notes and results. Priority on conflicts: optics, then FPS, then everything
else. **Off is exactly today's path**: no extra pass, no extra present, no thread; the converter request is unchanged
(frame generation adds no converter effect, so the golden test `TargetPackRtTest.rtOffOutputMatchesGolden` is untouched).

## 1. What frame generation does here

For every rendered frame N the mod shows two images: an interpolated frame N−½ (between N−1 and N), then N itself, spaced
half a frame apart. Displayed frames per second ≈ 2 × rendered frames per second; the real frame N reaches the screen about
half a frame later than without frame generation (§6).

```
 render thread, frame N                                                   presenter thread
 ... pack passes → [upscale] → final ─▶ HUD-less copy ─▶ GUI ─▶ FG dispatch ─▶ blit to swapchain image A ─▶ submit
                                                                                       │ (timeline value N)
                                         present(A) hook: hand {A, interp slot, N} ───┼──▶ wait GPU ≥ N
                                                                                       │    acquire B, copy interp → B
                                                                                       │    wait until t_prev + Δ, present B
                                                                                       │    wait until t_B + Δ,  present A
```

## 2. DLSS Frame Generation on Linux: available natively

Checked 2026-11-13 against the DLSS SDK **v310.9.16** (github.com/NVIDIA/DLSS) and NVIDIA's "DLSS-FG Programming Guide"
in that repository (version 310.7.0 of the guide):

* The SDK ships `lib/Linux_x86_64/rel/libnvidia-ngx-dlssg.so.310.9.16` and the Vulkan helpers
  `nvsdk_ngx_helpers_dlssg_vk.h` (`NGX_VK_CREATE_DLSSG`, `NGX_VK_EVALUATE_DLSSG`). The guide lists Linux (glibc ≥ 2.11,
  `libnvsdk_ngx.a`) and Vulkan ≥ 1.1 as supported and says frame generation through NGX "does not handle timing or
  presentation; this is the responsibility of your application" (guide §7). So no Streamline (Windows only) is needed: the
  application calls evaluate once per rendered frame, gets the interpolated image, and presents and paces both frames
  itself, exactly the job our presenter (§4) does for FSR.
* A probe against the installed driver (615.78.08, RTX 4070 Ti), `NVSDK_NGX_VULKAN_GetFeatureRequirements` for
  `NVSDK_NGX_Feature_FrameGeneration`: **FeatureSupported = 0 (supported)**, minimum architecture 0x190 (Ada, RTX 40), no OS
  minimum. Required device extensions are the same as DLSS SR's (`VK_NVX_binary_import`, `VK_NVX_image_view_handle`,
  `VK_KHR_buffer_device_address`, `VK_KHR_push_descriptor`), so the M8 "Emberlight DLSS" feature set already covers it.
* NVIDIA Reflex (the latency reduction DLSS FG is paired with on Windows) is not part of NGX; on Linux it would be
  `VK_NV_low_latency2`, which MC's swapchain does not use. We do without (§6).
* Licence: as for M8 (docs/UPSCALING.md §6): the shim is bundled, `libnvidia-ngx-dlssg.so.*` is downloaded together
  with the DLSS model on request (or copied by `mod/native/build-dlss.sh`).

Unavailable cases show `DLSS FG unavailable: <reason>` on the page and the mode falls back to Off: no NVIDIA GPU, no shim,
the dlssg library missing, pre-Ada GPU (`FrameGeneration_Available = 0`), NGX init or feature creation failing.

## 3. FSR 3 Frame Generation (AMD FidelityFX SDK v1.1.4, MIT)

* Source: `GPUOpen-LibrariesAndSDKs/FidelityFX-SDK` tag **v1.1.4** (the same release as M8's upscaler):
  `sdk/include/FidelityFX/gpu/frameinterpolation/` and `opticalflow/` headers and the 18 Vulkan GLSL pass files from
  `sdk/src/backends/vk/shaders/{frameinterpolation,opticalflow}/`, vendored unmodified under `common/src/main/glsl/fidelityfx/`
  (AMD's licence file is already there). The build compiles them with `glslc` (task `compileFsr3Fg`) in one permutation:
  render-size (low-res) motion vectors, unjittered motion vectors, forward depth, no FP16, SDR input.
* Host side: Java ports of `ffx_opticalflow.cpp` (`framegen/OpticalFlow.java`) and `ffx_frameinterpolation.cpp`
  (`framegen/FrameInterpolation.java`) in the "interpolation only" configuration of `ffx_fsr3.cpp`: our own prepare pass
  (reconstruct-and-dilate from depth + motion vectors) instead of the upscaler's shared resources, so FG works with
  FSR, DLSS or no upscaling alike. Passes, resources, clears, constants, SPD setup and the optical-flow pyramid follow the
  C++ one to one; bindings are resolved by name through SPIR-V reflection like `Fsr3Upscaler`. No native code; runs on every
  Vulkan GPU (needs `shaderStorageImageReadWithoutFormat`, which the mod already enables when the GPU has it).
* AMD's swapchain proxy (`FrameInterpolationSwapchainVK`) is **not** used: it replaces the application's swapchain, and MC
  owns its swapchain. We take its pacing algorithm instead (§4).

## 4. Presenting two images per rendered frame

MC's frame end (`Minecraft.renderFrame`): `acquireNextTexture()` at frame start, `blitFromTexture(mainTarget)` (records the
blit and signals `presentSemaphores[i]`), `encoder.submit()`, `present()` (`vkQueuePresentKHR` on the graphics queue).
Mixins on `VulkanGpuSurface` (all no-ops while Off):

* `blitFromTexture` HEAD: record this frame's frame-generation dispatch (needs the GUI image, so it runs after the GUI);
  TAIL: add a signal of our timeline semaphore (value = frame number) to the frame's submission.
* `present` HEAD (cancellable): instead of `vkQueuePresentKHR`, hand {swapchain, image index, MC's present semaphore,
  interpolated slot, timeline value} to the **presenter thread** and return. Results the presenter got back
  (`VK_ERROR_OUT_OF_DATE_KHR`, `VK_SUBOPTIMAL_KHR`) are applied to the surface's own flags at the next present, the way MC's
  `present()` does, so MC reconfigures as usual.
* `configure` / `close` HEAD: drain the presenter first (it must not touch a swapchain MC destroys).
* Queue and swapchain external synchronisation: `vkQueuePresentKHR` and `vkAcquireNextImageKHR` run under one lock (and
  `vkQueueSubmit2KHR`, `vkQueueWaitIdle` too when the presenter has to share MC's queue). While FG runs, MC's acquire polls with timeout 0
  (releasing the lock between tries) so it can never block while holding the lock the presenter needs.

The presenter thread (per frame N, in order):
1. Wait on the host until the timeline semaphore reaches N (the GPU finished frame N including the interpolation).
2. Pacing (AMD's algorithm from `FrameInterpolationSwapchainVK.cpp`): moving average and standard deviation over the last
   10 intervals between these completion times; Δ = max(0, avg/2 − 0.1·stddev − 0.1 ms); a gap > 100 ms resets the average.
3. Acquire a swapchain image B (own acquire semaphore), submit a blit interpolated slot → B (Y flip and RGBA/BGRA swizzle
   like MC's blit, waits on the acquire semaphore and the timeline value), wait until last present + Δ, present B.
4. Wait until present(B) + Δ, present A (waits on MC's present semaphore).
Frames without an interpolated image (menus, pack off, first frame after a reset) present A at once.

Back-pressure: interpolated images are a ring of 4; the render thread's present hook for frame N waits until the presenter
has submitted the copy of frame N−3, so frame N+1's interpolation can never overwrite a slot that is still to be copied. MC
already keeps at most two frames in flight, so this wait is normally free. Submitted is not finished: the copy runs on the
presenter's queue, so before frame N+1's dispatch writes the slot the render thread also waits (host, ≤ 1 s) on a second
timeline the copy signals (value = frame number; a skipped copy signals it with an empty submit). On timeout that frame gets
no interpolated image. A presenter that does not drain within 3 s (or exit within 3 s of stop) is abandoned: frame
generation stays off until restart and its Vulkan objects are leaked instead of freed while possibly in use. While FG runs
the swapchain has the surface's minimum plus 4 images (§9). The presenter submits and presents on its own queue in MC's graphics family when the GPU offers one
(NVIDIA: 16 queues), so a blocking present never stalls MC's submits; otherwise it shares MC's queue under the lock. Pacing
waits are skipped while the presenter is behind (the GPU already finished the next queued frame), see §9.

**VSync (FIFO)**: the timed waits still apply (they make an uneven 2:1 cadence even below the refresh rate); when the
doubled rate exceeds the refresh rate, presents block in the presentation engine and the back-pressure throttles rendering to
refresh/2, which is the expected FG-with-VSync behaviour (e.g. 144 Hz → 72 rendered, 144 shown). **VRR / G-Sync**: FIFO with
VRR presents at our timed points, so Δ-pacing gives even intervals inside the VRR range. **Immediate / Mailbox** (VSync off):
pacing is only by Δ; tearing is possible as without FG.

## 5. HUD / GUI

Interpolating MC's GUI (hotbar, chat, crosshair, F3, open screens) would smear text. Both FSR FG and DLSS FG have a
"HUD-less" mode: they get the final image *with* GUI and the same image *without* GUI; they interpolate the HUD-less pair
and put back the GUI of the current frame where the two differ (FSR: `ffx_frameinterpolation_inpainting.h`, a pixel that
differs between HUD-less and back buffer takes the back buffer's colour; DLSS-G: `pHudless` + `pBackbuffer`).

This is exact for MC because nothing but the GUI touches the main target after our HUD-less copy: the copy is taken in
`GameRenderer.render` right after the level, entity outlines and vanilla post effects (`fogRenderer.endFrame()`), just
before the GUI is drawn. Re-rendering the GUI per interpolated frame would be the only "more correct" option (moving
translucent HUD over moving scene), but MC's GUI pipeline is not re-entrant and the HUD only changes at tick rate; showing
frame N's GUI on frame N−½ costs at most a half-frame GUI delay. Open screens (pause menu, inventory) differ from the
HUD-less image almost everywhere, so the interpolated frame is then (almost) a copy of frame N — no smearing.

## 6. Latency and pacing

FG cannot show frame N before it is rendered, and it must show N−½ first: frame N is held back by Δ ≈ half a rendered frame
plus the interpolation cost (FSR FG ≈ 1–2 ms at 1440p, measured below). At 80 rendered FPS that is ≈ +7 ms. Recommended
use: ≥ 60 rendered FPS (interpolation errors grow with motion per frame). There is no extra cap option: MC's own
"Max Framerate" limits rendered frames, so a limit of half the refresh rate gives a steady 2× cadence at refresh; with
VSync the presentation engine caps automatically (§4).

## 7. Inputs

| Input | Source |
|---|---|
| HUD-less colour | copy of MC's main target (RGBA8, window size) before the GUI |
| back buffer | MC's main target after the GUI |
| depth | `depthtex0` (render size, forward GL window depth = D3D depth, as in M8) |
| motion vectors | M8's `upscale_motion.comp` (camera motion, render size, UV offsets current→previous); with upscaling off the same pass runs at native size just for FG |
| jitter | M8's Halton jitter while an upscaler runs, else 0 |
| reset | M8's reset rule (first frame, camera jump > 16 blocks, runtime rebuild) plus mode changes |
| camera | near/far/vertical FOV from the unjittered projection, frame time |

Moving entities and animated foliage have no motion vectors (Iris packs write none, M8 §11). FSR FG's optical flow
(computed from the HUD-less colour) covers part of that; DLSS-G uses its own optical flow hardware.

## 8. Config and UI

`config/emberlight.properties`: `framegen.mode` = off | fsr | dlss; `-Demberlight.framegen.mode=` overrides. Page "Frame
Generation..." next to "Upscaling..." (cycle button Off / AMD FSR 3 Frame Generation / NVIDIA DLSS Frame Generation; DLSS
shows `unavailable: <reason>`). Switching is live (no reload: FG adds no converter effect); the presenter is drained, the
generator replaced and the history reset. Autotest script step `framegen off|fsr|dlss [pause|nopause]` (the second word sets the auto pause, §11); `fgshots N` saves the next N
presented frames (interpolated and real, in display order) as PNGs.

## 9. Implementation notes

* `framegen/FrameGen` (render thread), `Presenter` (thread + pacing), `FsrFrameGen` = `OpticalFlow` + `FrameInterpolation`
  on `FfxPasses` (AMD's passes, reflection-bound push descriptors), `DlssFrameGen` (NGX through
  `mod/native/emrion_ngx_dlssg.cpp`), `FrameCapture` (autotest `fgshots`). The camera motion-vector pass moved from
  `Upscaler` into `upscale/MotionVectors` so frame generation can run it without an upscaler.
* Mixins: `FrameGenSurfaceMixin` (blit HEAD/TAIL, present, configure/close drain, acquire polling, +2 swapchain images while
  FG runs), `FrameGenQueueMixin`/`FrameGenQueueIdleMixin` (queue lock, only without our own queue),
  `FrameGenQueueFamilyMixin` (one extra queue in MC's graphics family when the family has one; MC never uses it, so the Off
  path renders exactly as before), `GameRendererMixin` (HUD-less copy before the GUI).
* Swapchain images: MC asks for max(3, the surface's minImageCount). While FG runs we ask for minImageCount + 4 (capped
  at maxImageCount). Vulkan only guarantees an acquire while at most images − minImageCount are held, and with FG up to 4 are
  held at the next acquire: MC's frames handed to the presenter but not yet presented (the back-pressure allows 4, and the
  presenter releases it only after presenting the interpolated image) or 3 of them plus the presenter's interpolated image.
  With 3 images the presenter waited for an image only its own next present could free (first test: "GPU timeout attempting
  to acquire next frame"). The later "+2" (5 images on Wayland, minImageCount 2) still left one too few: when the presenter
  fell 3 frames behind (pack swaps, uneven frame times) its acquire waited its 1 s timeout and the render thread with it
  (≈ 1000 ms `fg-wait` hitches, 2026-11-14). Switching FG on/off marks the swapchain suboptimal so MC recreates it.
* Pacing ratchet: the first version paced even when it was behind; its waits throttled rendering through the back-pressure,
  which lengthened the measured interval and with it the waits (22 rendered FPS instead of 110). The presenter now skips the
  waits whenever the GPU already finished the next queued frame.
* DLSS-G's own GUI handling (HUD-less + back buffer, no separate UI layer) blends GUI that changes between two frames (F3
  numbers showed both values). After evaluate we run `fg_ui_compose.comp`, the same rule FSR's inpainting pass uses: where
  the back buffer differs from the HUD-less copy, take the current frame's pixel. `-Demberlight.framegen.dlssUi=blend`
  keeps NVIDIA's behaviour. DLSS-G motion vectors are handed in render pixels (`mvecScale` = render size, verified by the
  midpoint test below; `-Demberlight.framegen.dlssMvScale=uv` passes UV units instead).

## 10. Results (2026-11-13, RTX 4070 Ti, driver 615.78.08, 2560×1440 window, native Wayland, VSync off, uncapped, target pack and settings, RT off, scene `overview`, HUD shown, 10 s per row)

| Upscaler | Frame generation | Rendered FPS | Presented FPS | Presented interval mean / p99 (ms) |
|---|---|---|---|---|
| Off | Off | 86.3 | 86.3 | 11.6 / 12.6 |
| Off | FSR 3 FG | 79.5 | **159.1** | 6.28 / 7.22 |
| Off | DLSS FG | 75.4 | **150.8** | 6.63 / 7.48 |
| FSR Quality | Off | 133.1 | 133.1 | 7.5 / 8.8 |
| FSR Quality | FSR 3 FG | 104.5 | **208.9** | 4.79 / 5.47 |
| FSR Quality | DLSS FG | 100.9 | **201.7** | 4.96 / 5.47 |
| DLSS Quality | Off | 126.0 | 126.0 | 7.9 / 8.6 |
| DLSS Quality | DLSS FG | 95.2 | **190.3** | 5.25 / 5.93 |

FG costs ≈ 1.0 ms (FSR) and 1.5 ms (DLSS) of GPU time per rendered frame at 1440p, so the shown rate is 1.6–1.85× the
non-FG rate. Pacing: interpolated and real presents alternate evenly (mean interval of even vs odd presents differs by
0.13–0.16 ms, stdev 0.2–0.4 ms, p99 ≤ 1.2 ms above the mean). 1080p window: Off 130.3 rendered → FSR FG 110.8 rendered /
221.7 shown.

Interpolation check (`fgshots` during a scripted constant turn, `mod/run/screenshots`): the horizontal image shift real(N−1)
→ interpolated(N−½) is 0.49–0.55 of real(N−1) → real(N) for both (FSR 0.51–0.54, DLSS 0.49 over 5 pairs each), i.e. the
generated frame sits at the temporal midpoint and the motion-vector scale and sign are right. HUD: hotbar and F3 text in
the interpolated frame match the real frame (F3 first line MAE 0.9 FSR); DLSS-G blended changing F3 numbers until the
GUI composition pass (§9) was added (that fix is not yet re-measured).

Not yet measured: VSync on (FIFO) — the first attempt, before the 1 s presenter acquire timeout, skipped 160 interpolated
frames and paced unevenly (presented ≈ 88/s at 120 Hz with ≈ 49 rendered); the re-run was cancelled for GPU time. Latency
(no tool here). XWayland was not used.

## 11. Fixes after the first user test (`fix/user-round1`, 2026-11-14)

* **DLSS FG flickered massively: every interpolated frame was black.** The GUI composition pass (§9) read MC's back buffer
  as a storage image, which it is not created for; the reads returned 0, so every pixel "differed" from the HUD-less copy
  and was overwritten with black. It reads it as a sampled image now (`fg_ui_compose.comp`). Checked with `fgshots`: the
  interpolated frame of a still scene now matches the real one (MAE 0.2–0.3 of 255, was 123), HUD text included.
* **FPS display**: MC's counter (and our corner overlay) counted rendered frames only, so FG "made no difference". The
  corner overlay now shows `120 FPS (60 rendered)` while the presenter runs, the F7 overlay a `frame generation: N FPS shown,
  M rendered` line (presents counted per second on the presenter thread). MC's own counter is left as the rendered rate:
  the autotest benchmarks and other mods read it.
* **Auto pause**: when the rendered rate reaches the display's refresh rate (monitor of the window, `VideoMode.getRefreshRate`)
  the extra frames cannot be shown, so FG only costs. FG then interpolates nothing and presents the real frames (presenter
  and swapchain stay, no restart); it resumes below 90 % of the refresh rate. The overlay says `frame generation paused`.
  `-Demberlight.framegen.autoBypass=false` turns this off (benchmarks).
  Pause and resume are logged (`Frame generation paused: 177 rendered FPS reach the 144 Hz refresh rate ...`), and the
  autotest `bench` line counts the interpolated presents and says when FG was paused: the Windows test (docs/WINDOWS-TEST-FINDINGS.md
  R6) read "FG interpolates nothing with present mode 0" because its uncapped benches ran above the monitor's refresh and
  were paused, silently. `fgshots` does not count a history reset's output (FSR returns the current frame unchanged
  then, e.g. the first frame after a pause or a mode switch) as an interpolated frame.
* **Cost per rendered frame** (2560×1386, DLSS Quality, RT off, overview, 6 s each): FG off 100.2 FPS (9.98 ms); FSR FG
  85.3 rendered / 170.5 shown (11.72 ms, +1.74 ms); DLSS FG 79.8 / 159.7 (12.52 ms, +2.54 ms). The pack passes' GPU time
  stays (10.37 vs 9.87 ms incl. the HUD-less copy); the rest is the interpolation itself, recorded after the level and so
  outside the profiler's pass list. No extra stall was found: the user's 190 → 140 FPS in the Nether (+1.9 ms) is this
  cost, which is why the auto pause exists.
* The presenter logs the swapchain's present mode when FG starts (`present mode 0 (immediate)` on the dev machine with
  VSync off; MAILBOX/FIFO with VSync). With FIFO at refresh R the screen gets at most R frames (§4).


## 12. The Nether re-measured (`fix/small-items`, 2026-11-15)

The user once saw 140 FPS with FG against 190 without in the Nether. Re-measured after the swapchain image count fix, the
presenter priority and the auto pause: fortress over the lava sea (`endnether/bench.txt` `fort`), the user's config (Sodium,
RTX with GI High, block light 2 rays, DLSS Quality), 2560×1440 in the gamescope test display (120 Hz), exclusive GPU, VSync
off, 10–12 s per row, two interleaved rounds (FG off: rendered FPS of round 1, round 2; FG: rendered / presented per round):

| | FG off | FSR FG | DLSS FG |
|---|---|---|---|
| RTX, auto pause off | 112, 105 | 98 / 196, 97 / 195 | 88 / 175, 90 / 179 |
| RTX, auto pause on | 108, 108 | 118 / 118, 119 / 119 (paused) | 119 / 119, 120 / 120 (paused) |
| RT off, auto pause on | 166, 165 | 186 / 186, 193 / 193 (paused) | 191 / 191, 193 / 193 (paused) |

* Running FG costs 0.9 ms (FSR) and 1.9 ms (DLSS) per rendered frame here (1920×1080: 0.1 / 0.6 ms) and doubles the shown
  frames, so it no longer loses presented frames. The user's 190 → 140 was the rendered rate above the refresh rate, which is
  what the auto pause now covers: with it on, every FG row above refresh runs paused.
* Paused FG renders **faster** than FG off (+10 % with RTX, +15 % with RT off) and with far better 1 % lows (FG off 52–95,
  paused 110–177): paused, our presenter thread presents the real frames on its own queue, while with FG off MC presents on
  the render thread and blocks there. So nothing in the paused path caps the rate (the 119 FPS RTX rows are the scene's own
  rate with the presenter, just at the 120 Hz threshold).
* Limit: the pause test is "rendered rate ≥ refresh" while FG runs. On a display faster than the FG-on rate but slower than
  the FG-off rate (e.g. 144 Hz with 117 FPS with DLSS FG and 150 without), FG keeps running and shows 144 mostly generated
  frames instead of 150 real ones. Not seen at the user's 120 Hz.

## 13. NVIDIA Reflex (`feat/reflex`, 2026-11-15)

Reflex runs through the plain Vulkan extension **VK_NV_low_latency2** (plus `VK_KHR_present_id`), called through LWJGL's
bindings (`framegen/Reflex.java`). No NVIDIA SDK, library or file is used or shipped and the NGX shim is unchanged, so the
licence situation stays as in §2 / UPSCALING.md §6: Reflex adds nothing to redistribute (Khronos-registered extension,
implemented by NVIDIA's driver).

* **Device / swapchain:** an optional feature set "Emberlight Reflex" (`VK_NV_low_latency2`, `VK_KHR_present_id`, feature
  `presentId`); MC enables it only where the driver has it. AMD, Intel and older NVIDIA drivers: the tile is hidden and
  nothing else changes (no struct is chained, no call made). With the extension every swapchain is created with
  `VkSwapchainLatencyCreateInfoNV(latencyModeEnable)`, so switching modes needs no swapchain rebuild.
* **Modes** (`reflex.mode` = off | on | boost in `config/emberlight.properties`, `-Demrion.reflex.mode=` overrides; tile
  "NVIDIA Reflex" in section "Latency" of the Frame Generation page): `vkSetLatencySleepModeNV(lowLatencyMode,
  lowLatencyBoost)`, applied at the start of the next frame and after every swapchain rebuild. With frame generation running
  Reflex is at least On (the tile shows "Required by frame generation"); Boost stays selectable.
* **Per frame** (`ReflexLoopMixin`, `ReflexSurfaceMixin`): `vkLatencySleepNV` + wait on its timeline semaphore at the top of
  MC's game loop, before `RenderSystem.pollEvents` (input) and the client tick; then markers `SIMULATION_START`,
  `INPUT_SAMPLE`; `SIMULATION_END` + `RENDERSUBMIT_START` before `GameRenderer.render()` (ticks, camera, extract done);
  `RENDERSUBMIT_END` + `PRESENT_START` / `PRESENT_END` around `GpuSurface.present()`. The frame counter is the marker
  presentID and the real present's `VkPresentIdKHR`. MC's queue submits use the spec's implicit attribution (all submits
  before a frame's present, or before its `PRESENT_END` once a queue is out-of-band, belong to that frame); MC makes exactly
  one frame submit, right before present, so that is exact. Explicit attribution (`VkLatencySubmissionPresentIdNV` on every
  submit, the first version) made driver 615.78 book each frame's GPU work one frame late: its own `gpuRenderStart/End` came
  one frame after our independently measured GPU completion, so every reported latency was one frame too high (see below). If the driver's semaphore is not signalled within 200 ms (a layer that drops the call) Reflex turns
  itself off for the session instead of stalling.
* **Frame generation:** the render thread's `PRESENT_START/END` stay around the hand-off to the presenter (as the spec asks
  of frame-generation layers); the presenter wraps each of its own `vkQueuePresentKHR` (generated and real) in
  `OUT_OF_BAND_PRESENT_START/END` with the frame's ID, so both presents count for one application frame and the driver's
  pacing is not split in two. Only the real present carries `VkPresentIdKHR`. The presenter's own queue is marked
  `vkQueueNotifyOutOfBandNV(PRESENT)`. NGX's DLSS-G evaluate has no Reflex/marker input (that lives in Streamline, which we
  do not use; `DLSSG.BackbufferFrameID` is optional and unrelated), so nothing else is wired there.
* **Frame cap / VSync:** with Reflex on (and FG off) MC's "Max Framerate" becomes the sleep's `minimumIntervalUs` and MC's
  own limiter (which sleeps after present, i.e. adds latency) is skipped; with FG MC's limiter stays (the interval would
  count the generated presents too). VSync is untouched.
* **Readout:** the profiler overlay (F7) shows `Reflex <mode>: sim->present` (our CPU time from the frame's simulation
  start to the return of its real `vkQueuePresentKHR`) and `sim->GPU done` (driver: `vkGetLatencyTimingsNV`
  `gpuRenderEndTimeUs - simStartTimeUs`, read 4× a second). Autotest `bench` logs both, plus `measured` (a daemon
  thread waits on MC's submit timeline semaphore for each frame's submit and takes the time from simulation start: no driver
  attribution involved, it cross-checks the driver figure) and the average time spent in the Reflex sleep;
  `reflex off|on|boost|fgoff` switches the mode (`fgoff`: FG without Reflex, for measuring only). A bench script must set
  `framegen off;upscale off` itself: both persist in the run dir's config, and FG left on from an earlier run forces Reflex
  On, which is why the first matrix's "gpu-off" logged "Reflex On".

### Measured effect (2026-11-15, RTX, driver 615.78, 1920x1080 window, overview scene)

"sim->GPU done" = simulation start to the frame's GPU work finished (driver report; the independent `measured` value agrees
within 0.5 ms in every row). "sim->present" = simulation start to the return of the frame's real `vkQueuePresentKHR` (with
FG that is the presenter thread's present, after the interpolated frame). RT scene: `rt on`, no upscaling (GPU 16.5 ms,
CPU ~7 ms). High-FPS scene: RT off, DLSS Ultra Performance (GPU ~5.2 ms, CPU ~5 ms). ms, 8-10 s benches.

| Scene | FG | Reflex | Test display (gamescope) sim->GPU / sim->present | Desktop (KWin Wayland) sim->GPU / sim->present |
|---|---|---|---|---|
| RT, 60 FPS | off | Off | 32.9 / 16.5 | 30.0 / 15.1 |
| | off | On | **25.1** / 8.8 (sleep 7.6) | **24.4** / 8.1 (sleep 8.4) |
| | off | Boost | 25.4 / 9.0 | 23.8 / 7.6 |
| | DLSS FG | Off (`fgoff`) | 36.2 / 45.8 | 36.0 / 45.3 |
| | DLSS FG | On | **26.8** / 36.5 (sleep 9.6) | **26.2** / 35.7 (sleep 9.9) |
| | DLSS FG | Boost | 26.7 / 36.3 | 25.8 / 35.1 |
| High FPS, 175-190 FPS | off | Off | 11.0 / 5.7 | 10.5 / 5.3 |
| | off | On | 10.4 / 5.2 | 10.3 / 5.1 |
| | off | Boost | 10.2 / 5.1 | 9.9 / 5.0 |
| | DLSS FG | Off (`fgoff`) | 13.8 / 17.5 | 10.5 / 5.4 * |
| | DLSS FG | On | 12.2 / 15.9 | 10.3 / 5.2 * |
| | DLSS FG | Boost | 12.2 / 15.9 | – |

\* on the desktop FG paused itself at 189 FPS (auto-pause above the monitor's refresh; 1821 of 7734 presents interpolated),
so these rows are effectively FG off.

Why this is the most Reflex can do here: MC's `VulkanCommandEncoder.submit()` already waits for the previous frame's
submit after each submit, so at most one frame is ever queued and the latency without Reflex is about CPU + 1 queued GPU
frame + GPU (2 frames when GPU-bound). Reflex removes the queued frame: the sleep (7-10 ms in the RT scene) moves the wait
from MC's submit to before input, and sim->GPU done drops to CPU time + GPU time (7-8 + 16.5 ms), -25 % without FG and
-26 % with DLSS FG. In the high-FPS scene CPU and GPU take the same ~5 ms, nothing queues, the sleep is ~0.2 ms and the
latency is already CPU + GPU; Reflex can't go below that. Boost only raises clocks (within noise here). gamescope does not
limit it: the test display and the desktop show the same picture.

The first matrix (before the attribution fix) read "~50 ms off and on" because (a) FG was still on from an earlier run's
config, so "off" ran forced On, and (b) explicit submit attribution shifted the driver's GPU timestamps one frame (16.7 ms)
late (RT scene, FG off: 50 ms reported where 33 ms was real; with the fix driver and measured value agree).
