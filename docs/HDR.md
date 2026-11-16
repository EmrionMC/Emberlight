# HDR output

Status: implemented (`feat/hdr`, 2026-11-16), default **Off**. Off is MC's path: the swapchain is created with MC's
format and colour space, no extra pass, no extra copy, MC's blit reads its own main target. The converter request does
not change (HDR adds no converter effect), so `TargetPackRtTest.rtOffOutputMatchesGolden` is untouched.

## 1. Approach: expand the final SDR image (option a)

Two ways were considered:

* **(b) scene-referred output from the pack:** patch Complementary's tonemap (composite) so it writes linear radiance, then
  apply a PQ tonemap with the peak brightness in our own final pass. Not done. Everything after the tonemap is SDR:
  Complementary's later passes (bloom composite, lens flare, `final`'s sharpening and colour grading) assume 0-1 input;
  MC's main target, which `final` writes and the GUI is drawn into, is RGBA8; the upscalers (FSR/DLSS/XeSS/RR, all in LDR
  mode, docs/UPSCALING.md §1) and frame generation (RGBA8 HUD-less copy and interpolation slots, docs/FRAMEGEN.md §7) take
  that SDR image. (b) would need a float main target in MC, HDR modes for three upscalers and both frame generators, and
  pack-specific patches of four passes, for a feature nobody on the team can look at on a real HDR display. Revisit when
  someone with an HDR monitor can judge it.
* **(a) inverse tonemap of the final image (chosen):** the image that would go to an SDR swapchain is expanded right before
  the swapchain blit. Works for every pack and without a pack; upscaling and frame generation run unchanged before it.

The encode (`hdr_encode.comp`), per pixel of MC's final image (sRGB-encoded):

1. `linear = sRGB EOTF(c)`.
2. **GUI pixels** (the frame with GUI differs from the HUD-less copy taken before the GUI, the rule frame generation's GUI
   composition uses): `nits = linear · paperWhite`. Menus without a world (no HUD-less copy) are all GUI. So the HUD,
   text and menus are shown at paper white, never expanded.
3. **Scene pixels:** luminance `L` (BT.709). Below the knee `L ≤ 0.5`: `nits = linear · paperWhite` (plain SDR at paper
   white). Above: the luminance is stretched with `L·pw + (peak − pw)·x²`, `x = (L − 0.5)/0.5`, which joins the linear part
   with the same slope and reaches `peak` at SDR white (sun, sky highlights, light sources). Colour ratios are kept;
   channels are clamped at the peak.
4. Output: **HDR10** (`VK_COLOR_SPACE_HDR10_ST2084_EXT`, A2B10G10R10 or A2R10G10B10): BT.709 → BT.2020, PQ. **scRGB**
   (`VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT`, R16G16B16A16_SFLOAT; only if the surface has no HDR10): linear BT.709,
   1.0 = 80 nits. Written to an RGBA16F image that MC's own `vkCmdBlitImage` then copies to the swapchain image (format
   conversion by the blit, same Y flip).

"Fake HDR": no detail is recovered that the pack's tonemap clipped. It does give real highlight brightness with a
correct paper white, which is what most "auto HDR" features do.

## 2. Swapchain

* `HdrInstanceMixin`: enables the instance extension `VK_EXT_swapchain_colorspace` when the loader has it (surfaces
  report HDR colour spaces only with it). It only adds enum values; MC still picks `B8G8R8A8/R8G8B8A8 + SRGB_NONLINEAR`.
* Optional device feature set "Emberlight HDR metadata" (`VK_EXT_hdr_metadata`).
* `HdrSurfaceMixin` wraps `vkCreateSwapchainKHR` in `VulkanGpuSurface.configure`: with HDR on, picks HDR10 (preferred) or
  scRGB from the surface formats whose format supports `BLIT_DST`, and after creation sets `vkSetHdrMetadataEXT`
  (BT.709 primaries, D65, max luminance and MaxCLL = peak, MaxFALL = paper white). A failed HDR creation turns HDR off for
  the session's next configures (MC's retry then creates an SDR swapchain) and shows the reason on the page.
* Switching HDR or changing a value marks the swapchain suboptimal; MC recreates it at the next frame (no restart).
* `blitFromTexture` HEAD: encode MC's main target; the blit's source is swapped for the encoded image (both `GENERAL`).

## 3. Frame generation and upscaling

Upscalers: unchanged (they run before `final`, SDR). Frame generation: the interpolated slot (RGBA8, GUI already
composed) is encoded in the same command buffer right after the interpolation into a ring of 4 RGBA16F images (same index
and lifetime as the slots, freed in `FrameGen.destroy`), with the current frame's back buffer / HUD-less pair as GUI mask;
the presenter blits it like the SDR slot. Cost while HDR is on: one 1 ms-class compute pass and one RGBA8 copy per frame
(HUD-less), plus 4 × 8 bytes/pixel for the FG ring (≈ 118 MB at 2560×1440) only while HDR and FG both run. Screenshots
(F2, autotest) read MC's main target and stay SDR.

## 4. Platforms

| Where | What the surface offers (RTX 4070 Ti, driver 615.78) | Result |
|---|---|---|
| KDE Plasma 6, native Wayland (SDL3 Wayland surface) | `vulkaninfo`: HDR10_ST2084 on A2B10G10R10, scRGB on RGBA16F, also on the user's SDR monitor | HDR10 swapchain; KWin shows it as HDR when the output has HDR enabled in the display settings, else tone-maps it to SDR. The page cannot tell an SDR monitor from an HDR one there: leave HDR off on SDR monitors. |
| X11 / Xwayland on a desktop | SDR formats only | Toggle disabled: "the display (or compositor) offers no HDR colour space" |
| gamescope `--hdr-enabled` (Xwayland inside, `ENABLE_GAMESCOPE_WSI=1`) | HDR10 through the gamescope WSI layer | HDR10 swapchain, verified below |

The page's HDR section is on "Upscaling" (video settings): **HDR output** (Off/On), **Peak brightness** (400-4000 nits,
default 1000), **Paper white** (80-400 nits, default 200). Without an HDR colour space the toggle is disabled with the
reason. Config keys `hdr.enabled`, `hdr.peak`, `hdr.paperWhite`; `-Demrion.hdr=true|false`, `-Demrion.hdr.peak=`,
`-Demrion.hdr.paperWhite=` override. `-Demrion.hdr.probe=true` logs five encoded pixels (code values and nits) every 300
frames, since a PQ swapchain image cannot be screenshotted.

## 5. Verification (2026-11-16, RTX 4070 Ti, driver 615.78, target pack and settings, scene `overview`, 1920×1080)

Run in a separate nested `gamescope --hdr-enabled --hdr-debug-force-support` (Xwayland, `ENABLE_GAMESCOPE_WSI=1`), not
the shared test display:

* HDR on: gamescope WSI logs `format: VK_FORMAT_A2B10G10R10_UNORM_PACK32 - colorspace: VK_COLOR_SPACE_HDR10_ST2084_EXT`,
  ours `HDR swapchain: format 64, colour space 1000104008 (HDR10), paper white 200 nits, peak 1000 nits, metadata true`.
  The swapchain is recreated once after the config is read (MC creates its first one before), again when FSR FG is
  switched on (7 images, still HDR10) and off.
* Probe (PQ code values → nits per BT.2020 channel), settled world: shadowed terrain 14–32 nits, ocean/sky 58–161 nits,
  bright sky 204–244 nits (above the 200-nit paper white: the knee stretch), title/loading screen 0 before the world.
* FSR FG with HDR: presenter ran (944 presents, 31 interpolated (gamescope at 60 Hz paused FG most of the time), 0 skipped),
  no errors.
* HDR off: WSI logs MC's `B8G8R8A8_UNORM / SRGB_NONLINEAR`, no HDR line, no probe (the encode never runs). The
  `overview` screenshot (MC's main target) differs from the HDR-on run by MAE 0.4–0.8, the animation floor.

Not verified (no HDR monitor): how it looks. Needs a real HDR display: KDE Plasma 6 Wayland with HDR enabled for the
output, MC on native Wayland, then Upscaling → HDR output On, and peak brightness set to the monitor's peak.
