# Benchmark and visual reference

The acceptance criterion is: **looks the same as Iris on OpenGL, with more FPS**, for *Complementary Unbound r5.9.3 +
Euphoria Patches 1.10.5* with the user's settings file. This page explains how to render the same scenes with the real
Iris + Sodium on OpenGL (the reference) and with Emrion Shaders on Vulkan, how to compare them, and how to benchmark.
It also lists the baseline numbers.

## Inputs shared by both runs

| Input | Where it comes from |
|---|---|
| Pack | `-PshaderPack=<dir>`, default `testpacks/unbound-euphoria` (git-ignored). Symlinked as `shaderpacks/<name>` in the run dir. |
| Settings | `-PshaderSettings=<file>`, default `testpacks/ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5.txt`. Copied to `shaderpacks/<name>.txt`, where `<name>` is the file name without `.txt` (the same name Iris uses in a launcher instance). |
| Video options | Merged into `options.txt` at every start (`sharedOptions` in `mod/build.gradle`): the reference options (render distance 16, simulation 12, FPS cap 260 = unlimited, vsync off, GUI scale 3, FOV 70, mipmaps 4, biome blend 3, entity shadows, AO), plus sound off, FOV effects off and `pauseOnLostFocus:false`, so a stray click does not change the image. |
| Window | `--width 1920 --height 1080` launch args (the screenshots are 1920×1080 on Wayland at scale 1). |
| World | `-Demrion.autotest.world=scenes`: the reference run creates it on first use (seed **2026**, default world type, peaceful) in `mod/run-iris/saves/scenes` and keeps reusing it. `runClient -Pcompare` deletes its own `mod/run/saves/scenes` and copies that world over it before every start (the source is only read; `session.lock` is skipped), so both runs render the same blocks. `-PsceneWorld=<dir>` picks another source, for example another checkout's `mod/run-iris/saves/scenes`. |
| Scenes | `mod/emrion-scenes.txt` (see below). |
| Resource packs | None by default. `-Demrion.autotest.resourcepacks=/abs/a.zip;/abs/b.zip` (same value for both runs) symlinks them into `resourcepacks/` of the run dir and enables exactly them in `options.txt`, in `runClient` and `runIrisReference` alike. |

Iris only (written by `runIrisReference`): `config/iris.properties` with `shaderPack=<name>`, `enableShaders=true`,
`maxShadowRenderDistance=24`, `colorSpace=SRGB` (the user's values), plus `config/sodium-options.json` copied from the
instance next to `-PirisModsDir` when it exists.

Not reproduced in the dev runs unless passed as above: the user's resource packs (Prime's HD 256x, Fresh Animations and others); never: the other
mods of the instance (Sodium Extra, ImmediatelyFast, Lithium, EntityCulling, Continuity, and so on). Both dev runs use
vanilla textures, so the comparison stays fair. The FPS numbers are not the numbers of the user's full instance.

**Why the world is copied and not regenerated:** the same seed does *not* give the same world. Terrain is identical, but
trees and other features differ between two freshly created worlds (MC places features across chunk borders in whatever
order its worldgen threads finish). Two `runIrisReference` runs, each on a fresh world, differed by MAE 40.7 / SSIM 0.47 in
`forest` and 6.6 / 0.82 in `overview` (other trees), against 2.3 / 0.91 and 2.0 / 0.93 on the same world.

## Commands

Set `JAVA_HOME=/usr/lib/jvm/java-27-temurin` first. In a checkout without `testpacks/`, add
`-PshaderPack=testpacks/unbound-euphoria "-PshaderSettings=testpacks/ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5.txt"`.

```sh
# 1. Reference: Iris + Sodium on OpenGL -> mod/run-iris/screenshots/iris-<scene>.png
./gradlew :mod:runIrisReference -Demrion.autotest.world=scenes -Demrion.autotest.scenes=emrion-scenes.txt -Demrion.autotest.quit=true

# 2. Ours: Emrion on Vulkan, same pack/settings/options/window -> mod/run/screenshots/emrion-<scene>.png
./gradlew :mod:runClient -Pcompare -Demrion.autotest.world=scenes -Demrion.autotest.scenes=emrion-scenes.txt -Demrion.autotest.quit=true

# 3. Side by side + metrics -> mod/run-compare/<scene>.png and report.csv
./gradlew :mod:compareScenes        # or: python3 -I mod/tools/compare_scenes.py <iris-dir> <emrion-dir> <out-dir>

# Benchmark (Iris writes mod/run-iris/emrion-benchmark-iris.csv, ours mod/run/emrion-benchmark-emrion.csv)
./gradlew :mod:runIrisReference -Demrion.autotest.world=scenes -Demrion.autotest.scenes=emrion-scenes.txt \
    -Demrion.autotest.only=overview -Demrion.autotest.benchmark.seconds=30 -Demrion.autotest.quit=true
./gradlew :mod:runClient -Pcompare -Demrion.autotest.world=scenes -Demrion.autotest.scenes=emrion-scenes.txt \
    -Demrion.autotest.only=overview -Demrion.autotest.benchmark.seconds=30 -Demrion.autotest.quit=true
```

- `runIrisReference` runs in `mod/run-iris/` (git-ignored), with `--graphicsBackend opengl`. Iris and Sodium are loaded
  through `-Dfabric.addMods` from `-PirisModsDir=<dir>` (for example a launcher instance's `mods/` folder; default `mod/run-iris/mods/`; the task takes
  `iris-fabric-*.jar` and `sodium-fabric-*.jar` from there through the `irisReference` configuration). The jars are
  never copied into the repo. MC 26.x is unobfuscated, so the release jars run in the Loom dev environment as they are.
  Our own mod is loaded too, but on OpenGL it stays inert except for the autotest harness (`AutoTest.registerIfEnabled()`
  runs before the backend check).
- `runClient -Pcompare` copies the reference world (see Inputs; run the reference first), links the same pack and settings
  into `mod/run/shaderpacks/`, merges the same options, sets the same window size, and passes `-Demrion.pack=<name>` and `-Demrion.autotest.tag=emrion`. Without `-Pcompare`,
  `runClient` behaves as before.
- `-Demrion.autotest.only=a,b` renders a subset of the scenes. `-Demrion.autotest.settle=<s>` and `settle.frames=<n>`
  change the settle time (defaults: 5 s and at least 200 frames after the chunks are built).
- `-Demrion.autotest.frameTimeCounter=<s>` pins `frameTimeCounter` in both runs (ours in `IrisTimer`, Iris's through the
  optional `IrisTimerMixin`), so waving leaves, water waves, clouds and rain stand still at the same phase. Pass the same value
  to both runs (we use 100). This takes the animation noise out of the comparison: the Iris-vs-Iris floor drops from
  MAE 2–5 to 0.2–0.9 (table below), so a remaining difference is a rendering difference.

## Scene list (`mod/emrion-scenes.txt`)

Format: `name | x y z yaw pitch | time | weather | commands`. For each scene the harness sets spectator mode, turns off
`advance_time` and `advance_weather`, sets time and weather, teleports, waits at least 8 s until all chunk sections are built,
runs the commands and waits for the sections again (a `summon` or `setblock` in a chunk that is not loaded yet fails without
an error, which left the entities scene empty before 2026-11-13), removes every entity without the tag `emrion` (worldgen animals wander differently in every run),
waits 5 s and 200 frames so TAA and other temporal effects settle, hides HUD/F3/screens, and takes the screenshot.

| Scene | Position (x y z yaw pitch) | Time | Weather | What it covers |
|---|---|---|---|---|
| overview | 90.5 105 -190.5 180 25 | 6000 | clear | Daylight overview of coast, ocean, forest; volumetric clouds, fog, distant water |
| forest | 4.5 119 10.5 45 5 | 6000 | clear | Forest close-up: leaves, waving foliage, sun shadows on the ground |
| water | 64.5 68 -260.5 180 15 | 6000 | clear | Ocean surface: screen-space reflections, refraction, water normals |
| cave | 0.5 -37 -5.5 0 15 | 6000 | clear | Closed deepslate room at y −40..−32 with 4 torches, a lantern and 3 ores, built by `fill`/`setblock` |
| sunset | 40.5 105 -59.5 90 5 | 12500 | clear | Sun at the horizon, sunset sky and cloud colours, long shadows |
| night | 40.5 105 -59.5 90 -70 | 18000 | clear | Moon and stars, night sky |
| rain | 64.5 70 110.5 0 25 | 6000 | rain | River in rain: rain fog, wet look, rain particles |
| entities | 110.5 69 -199.5 180 15 | 6000 | clear | Cow, sheep, pig, villager, horse and chicken (NoAI, tagged), entity shadows |

The positions and commands are plain vanilla commands, so they can be typed into any 26.3 client.

`mod/emrion-hand-scenes.txt` has the first-person scene `hand` (survival, HUD on, an empty map in the main hand); its header
lists the script that turns it into a filled map and shoots `<tag>-heldmap.png`. World text (maps, signs, name tags) uses
Iris's GLYPH layout with real face normals; before that the held map was grey (map region MAE 13.2 → 1.4 against Iris).

## Comparing

`compareScenes` writes, per scene, a half-size image with three panels (Iris | Emrion | |difference| × 4) and the
`report.csv` columns `mae_r, mae_g, mae_b` (mean absolute error per channel on 0..255), `mae_mean`, and `ssim` (mean SSIM
of the luma over 8×8 windows, 1 = identical).

Two Iris runs never give identical images: the pack animates by real time (`frameTimeCounter`: waving leaves, water
waves, cloud drift, rain), and the chicken flaps its wings. The run-to-run noise of Iris against itself is the floor; Emrion
should get close to it:

Measured 2026-11-12 (two `runIrisReference` scene runs on the same world, compared with `compare_scenes.py`):

| Scene | MAE R | MAE G | MAE B | MAE mean | SSIM | Main source of the difference |
|---|---|---|---|---|---|---|
| overview | 2.11 | 2.77 | 1.21 | 2.03 | 0.933 | waving leaves, clouds, distant water |
| forest | 2.14 | 2.98 | 1.91 | 2.34 | 0.907 | waving leaves and their shadows |
| water | 4.94 | 4.41 | 4.50 | 4.62 | 0.894 | water waves |
| cave | 0.72 | 0.47 | 0.32 | 0.50 | 0.997 | torch flame flicker |
| sunset | 0.93 | 0.86 | 0.51 | 0.77 | 0.977 | clouds |
| night | 0.18 | 0.20 | 0.23 | 0.20 | 0.998 | (almost static) |
| rain | 2.86 | 2.76 | 3.26 | 2.96 | 0.930 | rain streaks, ripples |
| entities | 3.46 | 4.67 | 1.86 | 3.33 | 0.883 | leaves, chicken wings |

So for the animated scenes, an MAE of 2–5 and an SSIM around 0.9 is "identical". A real rendering difference (missing
shadows, wrong colour grading, missing reflections) shows up as a clearly higher MAE, and you can see it in the diff panel.
Always look at the diff panel, not only at the numbers.

Emrion against Iris, measured 2026-11-13 on `m5/v1-visual` with `-Demrion.autotest.frameTimeCounter=100` in both runs
(Iris reference re-shot with the same pin; the floor is two pinned Iris runs against each other):

| Scene | MAE mean | SSIM | Pinned Iris floor (MAE / SSIM) | before v1 (MAE / SSIM, pinned) |
|---|---|---|---|---|
| overview | 0.40 | 0.997 | 0.38 / 0.996 | 1.38 / 0.989 |
| forest | 0.72 | 0.992 | 0.58 / 0.992 | 2.12 / 0.978 |
| water | 0.76 | 0.985 | 0.63 / 0.989 | 1.02 / 0.985 |
| cave | 0.26 | 0.998 | 0.24 / 0.998 | 0.39 / 0.997 |
| sunset | 0.57 | 0.995 | 0.33 / 0.996 | 1.00 / 0.991 |
| night | 0.50 | 0.989 | 0.18 / 0.998 | 0.50 / 0.989 |
| rain | 0.87 | 0.981 | 0.88 / 0.980 | 1.29 / 0.977 |
| entities | 0.78 | 0.991 | 0.80 / 0.990 | 2.24 / 0.978 |

Water varies by about ±0.05 between our own runs. Night stays above the floor because Euphoria's star field moves with
`worldAgeSmooth`, a `smooth()` custom uniform that converges frame-time dependently after the time jump between scenes.
("before v1" already includes the nearest terrain sampler; unpinned, the M4 numbers were forest 7.33 / 0.76 and
entities 5.49 / 0.83.)

Final M6 numbers (2026-11-14, `m6/x1-polish`, pinned `frameTimeCounter=100`, validation layer on, against the pinned Iris
references; the layer reported nothing, also nothing alive at exit):

| Scene | MAE mean | SSIM |
|---|---|---|
| overview | 0.50 | 0.995 |
| forest | 0.51 | 0.996 |
| water | 0.74 | 0.986 |
| cave | 0.29 | 0.997 |
| sunset | 0.60 | 0.994 |
| night | 0.52 | 0.989 |
| rain | 0.85 | 0.983 |
| entities | 1.00 | 0.989 |

Entities is above the floor only through the chicken's wing flap (entity animation time is not pinned).

## Benchmark with Iris + Sodium

### In the dev environment

See the commands above. The harness turns vsync off and the FPS cap to unlimited, waits 1 s, records 30 s of CPU
frame-to-frame times at the `overview` position (render distance 16), writes a CSV (`frame,ms`) and logs
`Benchmark: N frames, avg X FPS, 1% low Y FPS` (1% low = average FPS over the slowest 1 % of frames).

### Manually in a launcher instance

1. Create a singleplayer world: seed `2026`, world type Default, game mode Creative, Peaceful, cheats on.
2. In chat: `/gamemode spectator`, `/gamerule advance_time false`, `/gamerule advance_weather false`, `/time set 6000`,
   `/weather clear`, `/tp @s 90.5 105 -190.5 180 25`. Press F1. Do not move the mouse.
3. Video settings: render distance 16, vsync off, max framerate unlimited. Window 1920×1080 (or fullscreen; note which).
4. Wait about 15 s for the chunks, then read FPS from F3 or a frame-time tool (for example MangoHud with
   `MANGOHUD_CONFIG=log_duration=30` and its avg / 1% low) for 30 s.
5. Do the same in the Vulkan build with Emrion Shaders (Options > Video > Graphics API: Prefer Vulkan) and the same pack.

The full instance has more mods and a 256x resource pack, so its numbers differ from the dev runs. Compare like with like.

## Results: Iris + Sodium vs Emrion (M5, 2026-11-13)

Hardware: AMD Ryzen 7 5800X3D, NVIDIA RTX 4070 Ti (driver 615.78), Linux 7.2 (Arch), Wayland. Iris 1.11.7,
Sodium 0.9.3-beta.1, MC 26.3 dev environment, 1920×1080, render distance 16, scene `overview`, 30 s, the user's settings.
Each side was run 3 times, interleaved (Iris, Emrion, Iris, …), same world and settings.

**Measure only on an otherwise idle GPU.** A second game instance (another agent's scene run, even one still loading)
cut Emrion from ≈ 131 to 57–98 FPS and Iris from 75 to 36–64 FPS in our first attempts. Runs that overlapped another
game JVM during the 30 s window were discarded and repeated.

| Metric (mean of 3 runs) | Iris 1.11.7 + Sodium 0.9.3 (OpenGL) | Emrion before M5 | Emrion after M5 |
|---|---|---|---|
| Average FPS | **75.3** (13.28 ms); runs 75.3 / 75.4 / 75.2 | 130.7 (7.65 ms); runs 130.0 / 131.7 / 130.3 | **132.9** (7.52 ms); runs 134.2 / 131.0 / 133.5 |
| Median frame time | 13.30 ms | 7.58 ms | 7.45 ms |
| 99th percentile | 14.26 ms | 9.28 ms | 9.03 ms |
| 1% low | 68.9 FPS | 104.4 FPS | 107.1 FPS |

"Before" is the M4 state (the same build with `-Demrion.perf.deferClears=false -Demrion.perf.computeClears=false`, which
restores the old clears). Emrion is **1.76× Iris's average FPS** (−5.8 ms per frame) and better in every metric. The
final build (with the shadow-pass CPU fix below) measured 131.9 / 133.6 / 131.8 FPS: the same, because the frame is
GPU-bound (one of those runs had a burst of 20–39 ms stalls from outside the game, 1% low 52; the other two 108 / 105).
The earlier one-run baseline (73.8 FPS, 1% low 19.0) included a few long stalls; the re-runs had none.

### Where the time goes (Emrion)

Both sides are **GPU-bound** at 1080p: Iris runs at 100 % GPU utilisation (≈ 210 W), Emrion at 99 % (≈ 245 W), and
Emrion's render thread spends 4.6–5.1 ms of each 7.5 ms frame blocked on the GPU (MC's wait for the submit two frames
back, plus acquire/present). Render-thread CPU per frame (after M5, mean of the two undisturbed final runs):

| Render-thread CPU | ms/frame |
|---|---|
| uniform update (frame block + phase blocks) | 0.03 |
| recording our raw passes (barriers, binds, draws) | 0.19 |
| shadow pass: culling + MC's draw preparation/recording for the shadow view + entities | 1.47 (2.09 before) |
| MC's own work (everything else: extraction, main-view chunks, entities, GUI, submit) | 0.69 |
| blocked on the GPU | 5.17 |

GPU time per pass (GPU timestamps, mean of 3 runs after M5; "MC draws" = the gbuffers phase that MC's own draw code
renders with our pipelines):

| Pass | GPU ms | Pass | GPU ms |
|---|---|---|---|
| clear (frame start: depthtex0, shadowtex0, colortex) | 0.03 | translucent (water, translucent entities/particles) | 1.09 |
| shadow_opaque (terrain + voxelisation) | 0.47 | mipmaps colortex5 | 0.03 |
| shadow_entities | 0.00 | composite | 0.09 |
| copy shadowtex1 | 0.02 | voxel clears (deferred, compute) | 0.32 |
| shadow_translucent | 0.12 | composite1 (world-space reflections) | 0.88 |
| shadowcomp (floodfill compute, 512×256×512) | 0.65 | composite2 + composite3 (identity copies) | 0.08 |
| sky | 0.07 | composite4–7, final, mipmaps | 0.48 |
| opaque (terrain, entities, block entities) | 2.52 | end-of-frame copies | 0.09 |
| copy depthtex1 + mipmaps colortex0 | 0.05 | MC between our passes, before/after the level | 0.09 |
| deferred1 | 0.33 | **total (frame start to GUI end)** | **7.36** |

Iris cannot be split per pass the same way (OpenGL timer queries would need an Iris patch). What the numbers do show:
Iris needs 13.3 ms of GPU-bound frame time for the same passes with the same shaders (the comparison scenes match within
Iris's own noise), so the difference is not in what is rendered but in how: Vulkan with precise barriers inside our
raw passes and pre-built descriptor sets versus OpenGL's implicit synchronisation and driver work, and pipelines
built at load time. The GPU draws less power under Iris at 100 % "utilisation", consistent with more idle bubbles.

### What M5 changed and what it gained

| Change | Effect (measured) |
|---|---|
| **Profiler** (`debug.Profiler`): GPU timestamps around every raw pass and MC geometry phase, plus frame start/end, so MC's own work between our passes is visible; render-thread CPU sections; time blocked on the GPU (`GpuWaitMixin`, `SurfaceWaitMixin`) | overhead below noise (130.8 FPS without, 130.6 with `-Demrion.debug.timings=true`) |
| **Overlay**, key *Profiler Overlay* (default **F7**, rebindable in Controls), drawn with the FPS counter so it works with F1; `-Demrion.debug.overlay=true` starts with it shown | — |
| **Deferred clears** (`DeferredClears`): the per-frame clear of the raw voxel images (voxel_img 128 MB, wsr_img 32 MB, …) moves from the frame start to right after the image's last use in the previous frame (after the first shader pass behind any transfers); `leaves_img`, which no active program uses, is no longer cleared at all | with `vkCmdClearColorImage` alone: none (130.2 vs 130.7 FPS); a transfer clear is waited on by the next pass behind a mipmap/copy |
| **Compute clears** for unsigned-integer raw images (`emrion_clear2d/3d.comp`): raw-pass barriers never wait on the compute stage, so the clear overlaps composite1–3 | **+2.2 FPS (+1.7 %), −0.13 ms** average and median; clear itself 0.40 → 0.32 ms |
| **Shadow pass skips empty sections**: every air section in the shadow volume went through MC's `extractSectionDrawGroups` (JFR: 2.3 GB allocated in 28 s by the shadow pass alone) | shadow-pass CPU **2.09 → 1.47 ms**, render-thread allocation 3.7 → 1.6 GB per 28 s; no FPS change (GPU-bound) |
| No string building per frame for profiler labels; `Hazards` keeps pending accesses in a flat array | no per-pass allocation in our recording code |

Visual regression (MAE / SSIM against the Iris references, after all changes): cave 1.42/0.963, sunset 1.76/0.945,
overview 2.89/0.921, rain 3.23/0.918, water 4.35/0.884, entities 5.47/0.834, forest 7.35/0.759, night 0.51/0.989, all
within run-to-run noise of the M4 values. Emrion with the clear optimisations against Emrion without them differs by
less than Iris against itself (overview 0.40/0.996, cave 0.42/0.997, night 0.23/0.998).

### Investigated, not changed

- **composite2/composite3** are identity copies of colortex0 with these settings, but they cost 0.05–0.08 ms together
  (0.8 % of the frame). Skipping them needs a SPIR-V or GLSL pattern match plus flip-state remapping; not worth the risk now.
- **`gl_NormalMatrix` inverse per vertex**: after `spirv-opt -O` each terrain/shadow vertex shader has a single
  `MatrixInverse` (mat3) per vertex, a few dozen ALU ops; the per-pixel `inverse` in `gbuffers_terrain.fsh` is the pack's
  own mat2 (derivatives), the same under GL. No SPIR-V pitfalls found in composite1 (fully inlined, no function-local arrays).
- **MC's global barriers**: the gaps between our passes and MC's work around the level add up to ≈ 0.09 ms per frame.
  Merging MC render passes could save part of that; small.
- **Terrain vertex size (56 B vs 28 B)**: not measured directly (needs a second vertex format). Upper bound: the whole
  shadow_opaque pass, which is mostly vertex work, is 0.47 ms; the opaque pass is fragment-heavy.
- **Shadow culling** follows Iris (SAFE_ZONE + caster frustum), shadow geometry costs 0.6 ms on the GPU.
- **CPU**: remaining render-thread hot spots are MC's own (`extractSectionDrawGroups` for both views, the section lookups
  of the shadow culling loop). The frame is GPU-bound with ≈ 5 ms of slack, so they do not limit FPS here.

### Profiling switches

| Switch | Effect |
|---|---|
| F7 (*Profiler Overlay*) | ms per pass and the CPU breakdown, averaged over 0.5 s, top left |
| `-Demrion.debug.overlay=true` | start with the overlay shown (also in autotest screenshots) |
| `-Demrion.debug.timings=true` (`.seconds=5`) | log the full profile every 5 s |
| `-Demrion.autotest.profile=true` | log the profile averaged over the benchmark window (`Benchmark profile:`) |
| `-Demrion.perf.deferClears=false`, `-Demrion.perf.computeClears=false` | A/B switches for the clear optimisations |

## Robustness tests (M6)

`-Demrion.autotest.script="step;step;..."` runs after the scene shots (full list in `AutoTest`'s Javadoc): `wait S`,
`size WxH`, `fullscreen on|off`, `gui N`, `cmd COMMAND`, `shot NAME`, `mem` (FPS and VMA budget per heap), `pack off|on`,
`reload`, `leave`, `join`, `hud`, `f3 on|off`, `screen pause|inventory|chat|none`, `use down|up`, `press KEY`,
`repeat N { ... }`. Commands run as the server, so target players with `@a`/`@p`, not `@s`.

Fault injection (dev only): `-Demrion.debug.throwIn=levelStart,shadow,phase,finish` throws once in each FrameExecutor entry
point after `-Demrion.debug.throwAfter=200` level frames; `-Demrion.debug.failAlloc=N` makes the N-th raw image allocation fail
with `VK_ERROR_OUT_OF_DEVICE_MEMORY`. `-PgraphicsBackend=opengl` starts `runClient` on OpenGL.

**Validation layers without installing anything:** Steam ships `libVkLayer_khronos_validation.so` (SDK 1.3.284) in
`~/.local/share/Steam/ubuntu12_64/`. Write a layer manifest for it into any directory (`"name": "VK_LAYER_KHRONOS_validation"`,
`"library_path"`: that file, `"api_version": "1.3.284"`) and start the game with
`VK_ADD_LAYER_PATH=<dir> VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation VK_LOADER_LAYERS_DISABLE=VK_LAYER_MND_enable_timeline_semaphore VK_LAYER_MESSAGE_ID_FILTER=0x92d66fc1`.
The implicit `MND_enable_timeline_semaphore` layer adds a struct that makes the old layer fail `vkCreateDevice`; message
0x92d66fc1 (pMultisampleState-09026) is a false positive of this layer version for our vertex-only pipelines, and MC's debug
callback turns every reported error into a failed pipeline creation. Messages appear in the log as `(Minecraft) Validation Error`.
The layer predates some structs MC and the driver use, so a few `pNext` warnings at startup are expected.
