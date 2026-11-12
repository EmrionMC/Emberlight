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

Iris only (written by `runIrisReference`): `config/iris.properties` with `shaderPack=<name>`, `enableShaders=true`,
`maxShadowRenderDistance=24`, `colorSpace=SRGB` (the user's values), plus `config/sodium-options.json` copied from the
instance next to `-PirisModsDir` when it exists.

Not reproduced in the dev runs: the user's resource packs (Prime's HD 256x, Fresh Animations and others) and the other
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

Emrion against Iris, measured 2026-11-13 on `m4/f1-fixes` after the shadow pass merge (same canonical world, Iris
reference re-shot in the worktree after the scene-command fix):

| Scene | MAE mean | SSIM | Iris noise floor (MAE / SSIM) |
|---|---|---|---|
| overview | 3.02 | 0.922 | 2.03 / 0.933 |
| forest | 5.59 | 0.820 | 2.34 / 0.907 |
| water | 4.10 | 0.888 | 4.62 / 0.894 |
| cave | 1.43 | 0.963 | 0.50 / 0.997 |
| sunset | 1.69 | 0.955 | 0.77 / 0.977 |
| night | 0.50 | 0.989 | 0.20 / 0.998 |
| rain | 3.47 | 0.919 | 2.96 / 0.930 |
| entities | 5.35 | 0.838 | 3.33 / 0.883 |

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

## Baseline (Iris + Sodium on OpenGL)

Hardware: AMD Ryzen 7 5800X3D, NVIDIA RTX 4070 Ti (driver 615.78), Linux 7.2 (Arch), Wayland. Iris 1.11.7,
Sodium 0.9.3-beta.1, MC 26.3 dev environment, 1920×1080, render distance 16, scene `overview`, 30 s.

| Metric | Iris 1.11.7 + Sodium 0.9.3 (OpenGL) |
|---|---|
| Frames in 30 s | 2212 |
| Average FPS | **73.8** (13.55 ms) |
| Median frame time | 13.22 ms (≈ 76 FPS) |
| 99th percentile frame time | 17.90 ms (≈ 56 FPS) |
| 1% low (avg FPS of the slowest 1 % of frames) | **19.0** FPS (pulled down by a few stalls, max 210.65 ms) |
| CSV | `mod/run-iris/emrion-benchmark-iris.csv` (git-ignored, `frame,ms`) |

The frame times are stable at about 13 ms (GPU-bound with this pack). The 1% low comes from a few long stalls (GC or chunk
uploads in the first seconds), so report the 99th percentile too. This is one run. Repeat it 2–3 times before you draw
conclusions from differences under 5 %.
