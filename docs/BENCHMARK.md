# Benchmark and visual reference

The acceptance criterion is: **looks the same as Iris on OpenGL, with more FPS**, for *Complementary Unbound r5.9.3 +
Euphoria Patches 1.10.5* with the user's settings file. This page explains how to render the same scenes with the real
Iris + Sodium on OpenGL (the reference) and with Emberlight on Vulkan, how to compare them, and how to benchmark.
It also lists the baseline numbers.

## Inputs shared by both runs

| Input | Where it comes from |
|---|---|
| Pack | `-PshaderPack=<dir>`, default `testpacks/unbound-euphoria` (git-ignored). Symlinked as `shaderpacks/<name>` in the run dir. |
| Settings | `-PshaderSettings=` (empty) runs the pack with its defaults and removes a stale `<pack>.txt`; a zip pack (`-PshaderPack=<file>.zip`) is linked under its own file name. `-PshaderSettings=<file>`, default `testpacks/ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5.txt`. Copied to `shaderpacks/<name>.txt`, where `<name>` is the file name without `.txt` (the same name Iris uses in a launcher instance). |
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

**Fast mode (default since 2026-11-14, `-Demrion.autotest.fast=false` for the old fixed waits):** instead of the 8 s chunk
wait and the 5 s / 200-frame settle, a scene is shot as soon as every chunk within render distance - 1 is on the client, all
sections are built, and the image has converged: a 1/8-size readback per tick, compared with one at least 250 ms and 24 frames
older, has a mean absolute RGB difference below `-Demrion.autotest.converge.threshold` (0.4) five ticks in a row. The old
waits stay as the upper bound. A script step `settle [MAX_S]` does the same between script steps (`-Demrion.autotest.fastwait=true`
also ends `wait N` early). `-Demrion.autotest.configs=off:rtset enabled=false|gi:rtset enabled=true,gi=medium` renders every
scene once per configuration in one game start (`<tag>-<scene>-<config>.png`); `-Demrion.autotest.bench.seconds=S` shortens
every bench. Each bench also logs the spread of 0.5 s windows (`Benchmark spread: ... avg +- X at 95%`), and the run ends with
`Autotest timing:`, the harness time per phase. `runClient` shares one VkPipelineCache across all checkouts
(`~/.cache/emberlight-dev`, `-PpipelineCacheDir=` for the run dir's own), sets `inactivityFpsLimit:minimized` (the default
`afk` caps an unattended game at 30 FPS after one minute), and syncs the comparison world with rsync instead of a full copy.

Measured (5 scenes x 3 RT configurations + 5 benches, 2026-11-14): old harness 3 starts of ~95 s plus a 134 s bench run
(~425 s), fast harness one start of 94 s. Screenshots match the old ones within run-to-run noise (MAE 0.3-0.7 for off and RT
shadows); RT GI in overview/forest differed by MAE 1.1-1.3 (floor 0.7-1.6): its slow accumulation can pass the threshold early,
so use a lower threshold or a longer `settle` for GI comparisons.

**Packs with temporal effects** (TAA history, bloom fed into TAA, auto exposure, time-smoothed uniforms) do not converge to
the same image in two renderers that run at different frame rates: the convergence check can pass at different points of a
slow drift. For those, `-Demrion.autotest.settle.exactFrames=300` shoots exactly 300 rendered frames after the chunks are ready and rain and thunder
have finished fading (game ticks, so a fast renderer would otherwise shoot mid-fade),
`-Demrion.autotest.frameTime=0.016666668` pins `frameTime` (and with it every smoothed uniform and custom `smooth()`) in both
runs, and `-Demrion.autotest.shots=3` adds `<scene>-s2`/`-s3` shots `shotGap` (8) frames apart; `compare_scenes.py` compares
their per-pixel median. Shrimple, two run pairs: each renderer repeats itself within MAE 0.6-0.8 (overview) and ~3
(entities, waving leaves); without the pinning the same pack drifted by 5-10 % between runs.

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

## Debug snapshots, the test server and replay

### Taking a snapshot (in game)

Press **F9** (rebindable: Controls > Emberlight > Debug Snapshot; vanilla 26.3 leaves F9 free) or type
`/emberlight snapshot`. Emberlight writes `<gameDir>/emberlight/snapshots/<yyyy-MM-dd_HH-mm-ss>/` with
`screenshot.png` (what is on screen, HUD included unless F1 hid it) and `snapshot.json`. It copies a one-line summary and
the folder path to the clipboard and puts "Snapshot saved (F9)" in chat (click the folder name to open it). It costs
nothing until it is used.

`snapshot.json`:

| Section | Fields |
|---|---|
| `player` | `x y z` (exact doubles), `yaw pitch bodyYaw eyeY`, `gameMode`, `flying`, `underwater`, `eyeBlockLight eyeSkyLight`, `selectedSlot`, `mainHand offHand hotbar[9]` (as `id[components] count`, the form `/item replace` takes), `effects[]` (`id amplifier duration`, -1 = infinite), `target` (block under the crosshair: `pos face state`) |
| `world` | `name` (save folder, or the server entry's name), `levelName`, `seed` (singleplayer), `server` (address, multiplayer), `dimension`, `biome` |
| `time` | `dayTime` (default clock, day count included), `gameTime`, `moonPhase` |
| `weather` | `state` (clear/rain/thunder), `rain thunder` (levels 0-1) |
| `options` | `camera` (FIRST_PERSON, THIRD_PERSON_BACK/FRONT), `fov fovEffectScale bobView gamma renderDistance effectiveRenderDistance guiScale hudHidden`, `windowWidth/Height framebufferWidth/Height fullscreen` |
| `pack` | `name enabled running`, `settings` (the `shaderpacks/<pack>.txt` values in effect) |
| `emberlight` | the whole `config/emberlight.properties` with the live RT, upscaling and frame generation values on top (`rt.*`, `upscale.*`, `framegen.*`, `pack`, `perf.*`, `profiler.*`, ...) |
| `system` | `gpu vendor driver backend`, `fps` (rendered) and `presentedFps` (with frame generation), `os java`, `mods` (id: version), `lastHitches` (the last 20 lines of `logs/emberlight-hitches.log`) |

### The test world and the test server

The test world is seed 2026 (the harness seed, normal terrain, a river at 468 64 468) with test areas built by
`mod/tools/testworld/build.sh` (commands from `build_commands.py`, the optional torch hall from a `torch_hall.schem` you supply). Signs mark each
area; `/function emberlight:<spot>` teleports there (`/function emberlight:help` lists them); `README.txt` in the world folder lists them too.

| Spot | Teleport | What |
|---|---|---|
| `spawn` | 580.5 65 590.5 | the test pad, sign board |
| `hall` | 332.5 -39 324.5 180 20 | a torch hall (optional schematic), underground, as in the harness hall scene |
| `shaft` | 402.5 -38.4 405.3 180 52 | white concrete shaft with glowstone and a fence post, open to the sky (face-direction shadow bug) |
| `overhang` | 512.5 66 528.5 180 10 | white and red concrete platform on pillars in sunlight |
| `river` | 552.5 68 540.5 180 25 | stepped riverbed (1-5 deep), NoAI squids, glow squid and cod, gold block 5 deep at 552 59 528, plank platform, stained glass, boats on water and land |
| `clock` | 599.5 66 520.5 180 15 | observer clock driving a crafter and a lamp, torches and wall torches |
| `armour` / `armour_front` | 620.5 66 520.5 180 5 / 620.5 66 502.5 0 5 | enchanted armour and sword on an armour stand behind a stone brick wall |
| `lava` | 640.5 69 523.5 180 35 | lava pit: a pool of sources and lava flowing down from the rim |
| `portals` | 622.5 67 572.5 180 10 | Nether portal, End portal (full frame), End gateway |
| `nether` | Nether 0.5 64 -10.5 0 20 | closed netherrack room with lava, glowstone, shroomlight, lanterns (as the harness Nether scene) |
| `end` | End 100.5 49 0.5 90 0 | obsidian platform facing the main island (entering the End starts the dragon fight) |

Without `/function`: `/execute in minecraft:the_nether run tp @s 0.5 64 -10.5 0 20` and so on.

It is hosted by a local test server: Fabric 26.3 (Fabric API, WorldEdit 7.4.6), reachable on **127.0.0.1:25570 only**
(offline mode, so it must never be reachable from outside; whitelist your own account and the offline test account, both
op). If the server runs on another machine, forward the port with an SSH tunnel. The server is not part of the repo.

Suggested server settings: low priority (`nice 10`), `-Xms1G -Xmx2G`, pause ticking after 60 s without players. Builds
made on that server are what the harness tests, so it always sees the current state of that world.

### Harness on the server

`-Pserver=127.0.0.1:25570` makes `runClient` join the server as an offline test player (`-PtestUsername`, default `EmbTest`)
instead of opening a singleplayer world. Commands go through chat as that op player; `@a` in scene and script commands
becomes `@s`, and the harness never kills entities there (it does in singleplayer). At the end it sets
`advance_time`/`advance_weather` back to true and the player to creative. Scene commands that `fill` or `summon` change
the shared world: use them only in empty areas. `join` reconnects. Do not run a test while another game client is running on the same GPU.

```sh
./gradlew :mod:runClient -Pcompare -Pserver=127.0.0.1:25570 "-Demrion.autotest.pos=512.5 66 528.5 180 10" -Demrion.autotest.quit=true
```

### Replay: the `snapshot` step

`snapshot FILE` (a script step) recreates a snapshot: `execute in <dimension> run tp` to the exact pose (pinned every
tick, so no gravity or drift), game mode, day time (moon phase included), weather and the exact rain/thunder levels
(held on the client), effects, hotbar, offhand and selected slot, camera mode, FOV, FOV effects, bobbing, gamma, render distance, GUI scale,
HUD hidden, window size; it writes the snapshot's Emberlight config (`rt.*`, upscaler, frame generation, ...) and pack
settings and reloads the pack (the snapshot's pack if this run has it, else the current one with those settings), waits
for the swap and for the image to settle, and shoots `<tag>-snapshot-<folder>.png`.

Where: `-Demrion.autotest.world`/`-Pserver` win. Without them the snapshot picks: a snapshot taken on a local server
(127.x/localhost, i.e. the test server) joins that server; otherwise the singleplayer save of the same
name in `mod/run/saves`, or a new world from the snapshot's seed (same terrain, but trees and builds differ, see Inputs).
From another server or world everything but the terrain is restored. A snapshot step adds `--username` with the test player name.

```sh
./gradlew :mod:runClient -Pcompare "-Demrion.autotest.script=snapshot /path/to/snapshots/2026-11-15_12-00-00/snapshot.json" -Demrion.autotest.quit=true
```

Check (2026-11-15, Complementary Unbound + Euphoria, RT off, frameTimeCounter 100, 1920x1080): F9 in one run, the
`snapshot` step in a fresh run, `screenshot.png` against the replay shot:

| Where | MAE | SSIM |
|---|---|---|
| singleplayer `scenes`, river at 64.5 70 110.5 | 0.45 | 0.996 |
| (same run, scene shot vs snapshot shot: the noise floor) | 0.43 | 0.996 |
| test server, `overhang` | 0.34 | 0.997 |
| test server, `river` (the NoAI squid and cod still bob) | 0.59 | 0.991 |

Not reproduced: entity and particle state, animation phase (pin it with `-Demrion.autotest.frameTimeCounter`), the
nausea/portal skew (`nausea` step), the view-bob phase (`bob` step), and startup-only config keys.

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
5. Do the same in the Vulkan build with Emberlight (Options > Video > Graphics API: Prefer Vulkan) and the same pack.

The full instance has more mods and a 256x resource pack, so its numbers differ from the dev runs. Compare like with like.

## Results: Iris + Sodium vs Emrion (M5, 2026-11-13)

Hardware: AMD Ryzen 7 5800X3D, NVIDIA RTX 4070 Ti (driver 615.78), Linux 7.2 (Arch), Wayland. Iris 1.11.7,
Sodium 0.9.3-beta.1, MC 26.3 dev environment, 1920×1080, render distance 16, scene `overview`, 30 s, the user's settings.
Each side was run 3 times, interleaved (Iris, Emrion, Iris, …), same world and settings.

**Measure only on an otherwise idle GPU.** A second game instance (even one still loading)
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
| `-Demrion.debug.terrainMarks=true` | split each geometry phase per terrain pipeline (GPU timestamp + pipeline statistics: primitives, vertex and fragment invocations, logged every 600 frames as `Terrain stats`) |
| `-Demrion.autotest.profile=true` | log the profile averaged over the benchmark window (`Benchmark profile:`) |
| `-Demrion.debug.pipelineStats=true` | enable `VK_KHR_pipeline_executable_properties` and log the driver's statistics of every pack gbuffers pipeline as it is built (`Pipeline stats <program>[<variant>] <vanilla pipeline> [FS] Register Count=… Local Memory Size=…`; on NVIDIA the local memory value carries a 2^36 flag, the rest is the spill size in bytes) |
| `-Demrion.perf.deferClears=false`, `-Demrion.perf.computeClears=false` | A/B switches for the clear optimisations |
| `-Demrion.perf.sparseAtlases=true` | sparse atlases (M5b): −6.7 GB at 256x, ≈ −10 % FPS there on NVIDIA |
| `-Demrion.debug.vram=true` | name every VMA allocation; the autotest `mem` step writes `emrion-vma-N.json` (`mod/tools/vram_report.py`) |

## M5b: VRAM and performance without visual loss (2026-11-13, `m5b/p2-vram`)

The brief: more FPS and less VRAM with *no* visual change at all. Hardware and settings as in M5. The clean conditions here:
other desktop apps were closed, so other processes used about 1.05 GB of VRAM (the desktop compositor and shell). The KDE file
indexer `baloo_file` ran in the background (95 % CPU, niced). Runs with frame spikes over 50 ms were discarded and repeated.

### Tools

- `-Demrion.debug.vram=true` names every VMA allocation by its owner. MC's textures and buffers get their label through
  mixins on `VulkanGpuTexture`/`VulkanGpuBuffer.Direct`, and our raw images and buffers get theirs too.
- The autotest step `mem` logs VMA's per-heap numbers plus the driver's own per-heap usage and budget
  (`VkPhysicalDeviceMemoryBudgetPropertiesEXT`, a physical-device query; MC creates VMA without
  `VMA_ALLOCATOR_CREATE_EXT_MEMORY_BUDGET_BIT`, so VMA's "usage" is only its own block total). With the switch it also writes
  VMA's detailed JSON map to `emrion-vma-N.json`.
- `python3 -I mod/tools/vram_report.py emrion-vma-N.json [--all]` sums that map by owner.
- `mod/emrion-bench-scenes.txt`: scene `hand_overview` is the overview view, standing on a barrier block in creative with a
  diamond sword. Run it with `-Demrion.autotest.hud=true`, so the hand and hotbar keep the items atlas and its PBR maps in use, as in normal play.

### VRAM breakdown

Overview scene, MiB, from the VMA map. "256x" = Prime's HD Textures [256x] via `-Demrion.autotest.resourcepacks=…`, measured
after the items atlas came into use. The default build has the same numbers as master; the last column is the opt-in sparse
atlases (below).

| Owner | vanilla | 256x (master = default) | 256x, `-Demrion.perf.sparseAtlases=true` |
|---|---|---|---|
| MC block atlas (16384² with 4 mips at 256x) | 21 | 1365 | 999 |
| PBR block atlas, normals / specular | 1×1 defaults | 1365 / 1365 | 556 / 549 |
| MC items atlas (32768×16384 at 256x) | 2 | 2048 | 1249 |
| PBR items atlas, normals / specular (47 / 54 maps) | — | 2048 / 2048 | 193 / 204 |
| other MC atlases (shulker 512, particles, celestials, gui, chest 128 each, …) | 11 | 1106 | ≈ 520 |
| ours: SSBO `bufferObject0` (pack-declared 810 549 248 B) | 773 | 773 | 773 |
| ours: `floodfill_img` + `floodfill_img_copy` (512×256×512 RGBA16F) | 1024 | 1024 | 1024 |
| ours: voxel_img 128, wsr_img 32, leaves_img 2, puddle, wsr_lod | 162 | 162 | 162 |
| ours: colortex main / alt, depth copies, shadow maps | 128 / 86 / 17 / 64 | same | same |
| MC chunk geometry (`UberBuffer` solid/cutout/translucent) | 544 | 416 | 416 |
| MC render targets (main colour/depth, outline, hud depth) | 34 | 34 | 34 |
| MC other textures (per-sprite animation-frame textures, panorama, fonts) | 31 | 308 | 308 |
| VMA free space inside its 32–256 MiB blocks | 59 | 1571 | 1878 |
| **VMA device memory** | **2961** | **9705** | **9174** |
| **placed in system RAM** (VMA fallback when device allocation fails) | 0 | **6240** (blocks_s, SSBO, items_n, items_s) | 6 |
| driver-reported usage, device heap / host heap | 3036 / 142 | 9781 (budget 9869) / 6568 | 9249 / 334 |

**Why nvidia-smi showed ≈ 11.95 GB at 256x:** the card was full. Before other desktop apps were closed, other processes held 1.98 GB
(one chat app alone 756 MiB, the desktop shell and compositor about 350 MiB, …; `nvidia-smi` and `--query-compute-apps`). The game held
9.8 GB, which is the driver's whole budget for the process (9869 MiB). Everything allocated after that point, 6.2 GB
(the specular block atlas, the pack's SSBO and both items PBR atlases), went to system RAM. VMA placed it there when
`vkAllocateMemory` on the device heap failed (MC asks for `AUTO_PREFER_DEVICE`, not device-only).
It is not driver caching and not leaked staging: MC's transient/staging memory is about 100 MiB. VMA block granularity
does contribute: after the 256x resource reload, 256 MiB blocks stay alive for a few small textures (fonts, sprite animation
frames) left between freed per-sprite scratch textures, which pins 1.57 GB of free space.
**Tip:** closing GPU-accelerated desktop apps (or turning off their hardware acceleration) can free several hundred MiB of VRAM.

### FPS (overview, 30 s, 1080p, RD 16)

| Build | runs (avg FPS) | mean | 1% low | note |
|---|---|---|---|---|
| Iris 1.11.7 + Sodium 0.9.3 | 78.5 / 78.6 / 78.3 | **78.5** | 70.8 | 12.74 ms per frame |
| Emrion master `55a0fbd` | 135.9 / 137.3 / 137.7 (later: 138.5 / 138.2) | **137.0** | 110.6 | 7.30 ms per frame |
| Emrion M5b final (default), interleaved with master: 135.2 / 134.2 / 137.1 (mean 135.5) | 137.9 / 139.1 / 138.0 | **138.3** | 111.3 | median 7.17–7.21 ms (master 7.27–7.29) |
| 256x, master | 80.1 / 80.6 / 79.5 | 80.1 | 65.9 | |
| 256x, sparse atlases (opt-in) | 78.2 / 76.6 / 69.1 | 74.6 | 29.5 (33 ms bursts) | |
| 256x, VMA 32 MiB blocks (rejected) | 60.4 / 60.2 / 60.0 | 60.2 | 52.0 | |

Emrion master is 1.74× Iris under these clean conditions. Profile of master (GPU-bound, GPU 7.16 ms per frame):
- render-thread CPU per frame: uniforms 0.03, raw recording 0.18, shadow pass 1.46, MC's rest 0.67 ms, and 4.92 ms blocked on the GPU
- GPU, biggest passes: opaque 2.44, translucent 1.05, composite1 0.88, shadowcomp 0.61, shadow_opaque 0.47, deferred1 0.31,
  deferred voxel clears 0.31 ms; composite2 + composite3 together 0.04 ms

### What changed

| Change | Effect | Image |
|---|---|---|
| VRAM audit tooling (above) | — | no change: naming only with the switch |
| **Sparse atlases**, opt-in `-Demrion.perf.sparseAtlases=true` (`SparseAtlas`) | 256x: −6.7 GB in total demand, nothing in system RAM (9174 MiB device vs 9705 device + 6240 host). Costs ≈ 10 % FPS at 256x, so it is off by default | byte-identical (below) |

Sparse atlases: every atlas of 4096² texels or more (MC's own and the PBR copies) is created as a sparse, aliased image. A
64 KiB tile (128×128 RGBA8) gets its own memory only where a sprite rectangle touches it at that mip level (the rectangle
includes the sprite's padding, plus one texel). Every other tile is bound to a single shared page. Why every texel keeps its
value:
- MC writes an atlas only by blitting sprites into their rectangles, or uniformly: the PBR default clear covers all mips and
  also fills the shared page with the default value.
- The atlas render passes use LOAD/STORE, so the shared page only ever receives the value it already holds.
- In a plain base atlas, the texels outside all sprites were never written either.

The 256x dumps of the block atlas, its normals and its specular atlas (all 4 mips, `-Demrion.debug.pbrDump`) are
**byte-identical** with the switch on and off. The particle atlas differed only inside the animated `minecraft:vibration`
sprite, because the two dumps caught different animation frames. Per atlas (sprites cover 71 % of the block atlas; normal
and specular maps cover 19 % each; on the items atlas, 57 % and 7 %): items_n 2048 → 193 MiB, items_s 2048 → 204, items
2048 → 1249, blocks_n 1360 → 556, blocks_s 1360 → 549, blocks 1360 → 999, shulker 512 → 232.

**Why it is opt-in:** on NVIDIA 615.78, merely enabling the `sparseBinding` device feature costs about 10 % GPU time at 256x,
with no sparse image at all (overview with the atlases plain: 79.1 FPS without the feature, 71.4 with `sparseBinding`, 71.7 with
`sparseResidencyImage2D` added). At vanilla there is no cost (138.1 / 138.6 FPS with sparse on). Keeping things in VRAM
does not win that back: in the hand scene, master spills 4.9 GB (the SSBO and the items PBR atlases, which are sampled only
by the held item) and runs at 67.5 / 61.3 FPS; sparse keeps everything in VRAM and runs at 55.2 / 60.7. With the switch off,
the features are not requested at all.

### Proof that the image is unchanged

Pinned comparison (`frameTimeCounter=100`, 8 scenes) of the final M5b build against the pinned Iris references, and against
master `55a0fbd` rendered in the same session:

| Scene | final vs Iris (MAE / SSIM) | master vs Iris | final vs master | master vs master (run-to-run floor) |
|---|---|---|---|---|
| overview | 0.48 / 0.995 | 0.48 / 0.995 | 0.40 / 0.996 | 0.43 / 0.996 |
| forest | 0.67 / 0.993 | 0.70 / 0.992 | 0.47 / 0.995 | 0.62 / 0.992 |
| water | 0.72 / 0.986 | 0.74 / 0.986 | 0.51 / 0.992 | 0.54 / 0.991 |
| cave | 0.28 / 0.998 | 0.28 / 0.998 | 0.23 / 0.998 | 0.27 / 0.998 |
| sunset | 0.61 / 0.994 | 0.59 / 0.994 | 0.25 / 0.998 | 0.35 / 0.996 |
| night | 0.52 / 0.989 | 0.51 / 0.989 | 0.11 / 0.999 | 0.20 / 0.998 |
| rain | 0.97 / 0.979 | 0.94 / 0.980 | 0.86 / 0.981 | 0.80 / 0.983 |
| entities | 1.01 / 0.989 | 0.97 / 0.989 | 0.32 / 0.997 | 0.84 / 0.989 |

Final against master is inside master's own run-to-run floor in every scene (rain: 0.86 vs 0.80, within noise). Neither
build is bit-identical to itself between runs: TAA and frame-time-dependent `smooth()` uniforms vary. Nothing on the default
path touches pixels: the audit hooks only name allocations, and the sparse code returns before doing anything when the
switch is off. The opt-in sparse path is proven bit-exact by the atlas dumps above. Against Iris, the numbers match the M6
final table (MAE 0.28–1.01).

### Tried and rejected

- **VMA blocks of 32 MiB** (instead of VMA's 256 MiB): 1.3 GB less slack at 256x (1878 → 551 MiB), but 60.2 instead of
  80.1 FPS. The device heap stays at its budget either way. With 32 MiB blocks, the allocation that no longer fits is MC's
  translucent chunk `UberBuffer` (read every frame, now over PCIe) instead of the pack's SSBO. Which allocation spills
  matters more than how much is free. At vanilla it was neutral to −2 %. Reverted.
- **Memory priority + pageable device-local memory** (`VK_EXT_memory_priority`, `VK_EXT_pageable_device_local_memory`,
  VMA's priority flag; priorities by label: items/other PBR atlases 0.0, UberBuffers and MC's transient memory 1.0, the
  rest 0.5). It works mechanically: VMA keeps all 14.4 GB "on the device", and the driver keeps 10.6 GB resident and pages
  5.6 GB out. But at 256x in the hand scene it ties with master (steady-state median frame time 13.96 / 13.52 ms vs 12.92 /
  14.63 ms). Not kept.
- **Skipping composite2/composite3:** they are exact identity copies here. `vec4(texelFetch(colortex0, ivec2(gl_FragCoord.xy), 0).rgb, 1.0)`
  goes into R11G11B10F, which has no alpha. composite2 writes main → alt and composite3 writes alt → main, and no later pass
  of the frame reads the alt side (flip states 5–8); the frame-start clear resets it. So the skip would be bit-exact. But
  together they cost 0.04 ms (0.5 %), below the run-to-run noise of ±0.05 ms, so a SPIR-V/GLSL pattern matcher is not worth it.
- **Aliasing transient targets:** colortex alt sides are 86 MiB at 1080p, under 1 % of the 256x demand, and their contents
  cross the frame boundary through the end-of-frame copies.
- **SSBO, floodfill, voxel images:** these are the pack's declared sizes (Iris allocates the same). The SSBO is indexed by
  voxel face of the world-space reflections and written by the GPU wherever there are faces, and the floodfill volumes are
  fully read and written every frame, so no region can be left unbacked. `leaves_img` (2 MiB) is unused but too small to matter.
- **Smaller formats:** none provably holds the data with these settings; not attempted.
- **Lossless compression:** the driver does not expose `VK_EXT_image_compression_control`. That extension only controls
  fixed-rate compression (lossy, mainly ARM), and NVIDIA already applies its own transparent lossless framebuffer
  compression, which saves bandwidth, not memory. `VK_EXT_memory_decompression` (GDeflate) decompresses during copies, so
  resident size stays the same. BC/ASTC is lossy and forbidden.
- **Caching:**
  - Pack load is already cached. Cold: conversion 3.4 s, raw pipelines 4.5 s. Warm (converter cache + `VkPipelineCache`
    on disk): 0.2 s + 0.2 s, and the gbuffers pipelines build in the background during world load.
  - Uniform upload is 0.03 ms CPU and descriptor sets are pre-built per flip state. The frame is GPU-bound with ≈ 5 ms of
    CPU slack, so CPU-side caching cannot raise FPS.
- **Async compute for shadowcomp:** it reads this frame's voxelisation from shadow_opaque, and every later pass reads its
  floodfill result. The only possible overlap is shadow_translucent + sky (0.19 ms), and it would need queue-ownership
  transfers and timeline semaphores in MC's single-queue submit. Not worth it.
- **Fewer MC render-pass splits:** MC's work between our passes totals ≈ 0.05 ms.
- **Compact chunk vertex format** (Sodium-style 16-bit positions/UVs): not bit-identical to our current float data (position
  quantum 1/2048 block, UV quantum 1/65536). It could move us closer to Iris+Sodium, but the brief forbids any change of
  our image. Upper bound: shadow_opaque, mostly vertex work, is 0.47 ms; the opaque pass (2.44 ms) is fragment-heavy; the
  UberBuffers (416–544 MiB) would shrink by ≈ 40 %.
- **Early-Z / depth pre-pass:** the terrain shaders discard (alpha test), and a pre-pass doubles the vertex work. Not exact in general.

Observed but not caused by us: in 256x runs, master included, the benchmark often ends in 180–200 frames pinned at exactly
33.3 ms. This looks like presentation or compositor throttling. Medians are used where it matters.

## M7 RT shadows (2026-11-13, `m7/rt2-shadows`)

Same hardware and route; 25 s per run with `-Demrion.autotest.profile=true`; "RT off" includes the forced shadow sections
(master since 2026-11-13). RT on: `-Demrion.rt.enabled=true -Demrion.rt.shadows=hybrid|full`.

| Scene | master before forced sections | RT off | RT Hybrid | RT Full |
|---|---|---|---|---|
| overview | 133.4 / 136.7 | 95.9 / 94.5 | 88.2 | 89.3 |
| sunset | 168.6 | 145.2 | 129.9 | 129.0 |
| forest | 218.2 | 188.5 | 171.5 | 170.5 |

RT GPU cost per frame: TLAS build 0.15–0.16 ms, plus the ray queries inside `gbuffers opaque` (+0.2 to +0.7 ms). The
forced shadow sections cost `shadow_opaque` 0.47 → 3.25 ms in the overview (sections behind the camera and in the voxel
safe zone now have meshes, as under Iris/Sodium). RT comparison images and VRAM numbers: RAYTRACING.md §10.
## Shadow coverage without the FPS loss (2026-11-13, `perf/sh1-shadow`)

`ForcedSections` (92643fd) made the shadow map cover what Iris covers, at a cost of 135 → 97 FPS in `overview`. Hardware and
settings as in M5b; 30 s, interleaved runs; `-Demrion.debug.shadowStats=true` logs what the shadow pass draws (sections and
triangles by safe zone / band, sky-dark / lit, below / above the camera) and its CPU split every 5 s.

### What the 3.3 ms were

`overview` after the fix: 7.9k sections, 9.4 M opaque triangles. 0.4 M in the 64-block safe zone; in the band to 256 blocks
4.3 M in sky-dark sections (sky light 0 in the section and its 6 neighbours), 3.1 M lit below the camera, 1.6 M lit above.
A/B (shadow_opaque GPU ms / FPS): baseline 3.34 / 98; 1×1 scissor (vertex work only) 2.85, so the pass is vertex-bound, about
0.28 ms per M triangles even outside the voxel volume, i.e. vertex fetch of the 56-byte vertices; no band sections at all
0.28 / 151; no dark band sections 1.96 / 115; no sections outside the voxel volume (±256 / ±128 blocks) 2.93 / 102. The CPU
part (3.2 ms) was 1.5 ms for the culling loop and 1.7 ms for MC's `prepareChunkRenders` over the shadow set; the frame is
GPU-bound (5.3 ms per frame blocked on the GPU), so the CPU side does not limit FPS.

### Changes

| Change | Triangles (overview) | shadow_opaque | FPS (3 runs) |
|---|---|---|---|
| master `45f62fc` (ForcedSections) | 9.4 M | 3.36 ms | 97.3 / 97.1 / 97.3 |
| **Reachable sections only:** a section casts only if MC's `SectionOcclusionGraph` holds a node for it. That node map is the frustum-free flood fill from the camera through the sections' visibility sets (the frustum is applied afterwards), the same set Sodium meshes with its wide search, so fully enclosed caves never cast, exactly as under Iris. Switch: `-Demrion.perf.shadowReachable=false` | 5.0 M | 1.94 ms | 115.1 / 115.6 / 116.2 |
| **Receiver-driven culling** (`ReceiverGrid`, option *Shadow culling*): a light-space grid of 8-block cells holds the depth of the receiver farthest from the light; receivers are all sections in the camera frustum that the flood fill reaches, *air included*, because volumetric light and clouds sample the shadow map along every view ray, plus the camera itself. A caster section (box + 1.125 margin) is kept if it lies in the safe zone or is closer to the light than a receiver in any cell it covers (footprint padded by 8 blocks for filtering, normal offsets and waving, depth by 4 blocks) | 1.76 M | 0.78 ms | **131.3 / 131.6 / 131.5** (interleaved with *Off*: 114.0 / 114.2 / 112.2); 1% low 104–106 |

*Outside voxel volume* mode (receiver culling only beyond ±256 / ±128 blocks, and the voxel volume itself counts as a receiver
for the WSR hit points) is safe for the voxelizer by construction but gains nothing in `overview` (116.3 FPS): almost nothing
the flood fill reaches lies that far below the camera.

Option: shader options → *Performance...* → *Shadow culling: Off / Outside voxel volume / Full* (tooltip), stored as
`perf.shadowReceivers=off|outside|all` in `config/emberlight.properties`, default `all`; `-Demrion.perf.shadowReceivers`
overrides it. It takes effect on the next frame, no reconversion.

### Image

Pinned (`frameTimeCounter=100`), MAE mean against the pinned Iris references (`ref-pinned`, back scenes re-shot from the
same world) and against master rendered in the same session:

| Scene | master vs Iris | reachable vs Iris | Full vs Iris | Full vs master |
|---|---|---|---|---|
| overview | 0.48 | 0.46 | 0.43 | 0.43 |
| forest | 0.74 | 0.73 | 0.72 | 0.49 |
| water | 0.72 | 0.76 | 0.76 | 0.50 |
| cave | 0.25 | 0.28 | 0.28 | 0.28 |
| sunset | 0.57 | 0.59 | 0.60 | 0.34 |
| night | 0.49 | 0.50 | 0.52 | 0.21 |
| rain | 0.93 | 1.00 | 0.95 | 0.90 |
| entities | 0.61 | 0.86 | 1.03 | 0.77 |
| sunset_back | 0.52 | 0.48 | 0.52 | 0.29 |
| forest_back | 0.39 | 0.39 | 0.42 | 0.31 |

Everything is within master's run-to-run floor (M5b table: water 0.54, cave 0.27, rain 0.80, entities 0.84). The diff panels
show only the chicken's wings and particles (entities), kelp shimmer (water) and torch flicker (cave).
Limits: the reachable filter follows MC's graph, whose "smart cull" ray test beyond 60 blocks is a little stricter than
Sodium's search. *Full* is not provably lossless: a hidden caster outside the 64-block safe zone is also missing from the
voxelization (world-space reflections and coloured light can show blocks that are not on screen). No scene showed it; use
*Outside voxel volume* or *Off* to rule it out.

### Investigated, not changed

- **"Sky-dark sections never matter"** (skip sections without sky light): false for this pack. `mainLighting.glsl` uses the
  shadow map alone within the shadow distance and mixes in the sky-light factor only beyond it, so a receiver with sky light 0
  still gets sun wherever the map says so. The voxel volume (512×256×512, not just the 64-block safe zone) also needs those
  sections for colored light. The reachable filter removes most of them anyway (5270 → 178 sections).
- **Vertex-shader variant without voxel writes outside the volume:** vertex fetch dominates (0.28 ms per M triangles with no
  writes), and outside the volume only ≈ 0.1 ms is left after the receiver grid. The Sodium switch (36-byte vertices) is the lever.
- **Caching the culled list across frames:** the shadow CPU is now ≈ 1.8 ms on a GPU-bound frame, so it would not show as FPS.

## Robustness tests (M6)

`-Demrion.autotest.script="step;step;..."` runs after the scene shots (full list in `AutoTest`'s Javadoc): `wait S`,
`size WxH`, `fullscreen on|off`, `gui N`, `cmd COMMAND`, `shot NAME`, `mem` (FPS and VMA budget per heap), `pack off|on`,
`reload`, `leave`, `join`, `hud`, `f3 on|off`, `screen pause|inventory|chat|packs|none`, `screen rt|upscale|framegen|perf|dlss|options[:ID]` (an Emberlight page, for GUI screenshots), `use down|up`, `swing` (main-hand attack swing), `press KEY`,
`repeat N { ... }`, `turnshot DEG FRAMES NAME [DX DZ]` (frame-exact turn/move, shoots the last moving frame; frameTimeCounter restarts at 100, so a `turnshot 0 …` at the end pose gives a matching still reference for upscaler motion tests; `fgturn` does the same turn and saves the frame generator's interpolated frame in front of the last one, to compare with a `turnshot 0 …` at the half-step pose and time offset `-0.008333`). Commands run as the server, so target players with `@a`/`@p`, not `@s`.

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
Steam has since moved the library to `~/.local/share/Steam/steamrt64/libVkLayer_khronos_validation.so`; a manifest still
pointing at `ubuntu12_64` fails silently (one "cannot open shared object file" line, then no validation at all).
`-PrenderDebugLabels=1` starts the game with `--renderDebugLabels`, so MC names its buffers and images: a leak report then
says e.g. `VkBuffer 0x…[Shared index buffer]` (a Sodium buffer), while our own `RawBuffer`s stay unnamed.

## M8-S1: Sodium step-1 gate (2026-11-13, `sodium/s1-terrain`)

Overview, 1080p, RD 16, 30 s, pinned `frameTimeCounter=100`, interleaved runs. "Vanilla" is master `c2a3393` (same code
as this branch without `-Psodium`, checked with one extra run: 96.2 FPS). "Sodium" is this branch with `-Psodium`.

| | run 1 | run 2 | run 3 | 1% low |
|---|---|---|---|---|
| vanilla chunk path | 97.9 | 98.1 | 98.1 (and 98.0) | 72–79 |
| Sodium chunk path | 140.4 | 142.9 | 142.6 | 124–126 |

**This is not a like-for-like comparison.** GPU profile (`-Demrion.autotest.profile=true`, ms/frame):

| pass | vanilla | Sodium |
|---|---|---|
| shadow_opaque | 3.35 | 0.22 |
| shadow_translucent | 0.20 | 0.05 |
| shadow pass CPU recording | 3.15 | 0.08 |
| gbuffers opaque | 2.38 | 2.74 |
| gbuffers translucent | 1.03 | 0.85 |

- Almost all of the FPS gap is the shadow pass. On Sodium it still draws only the camera's render lists (step-1 interim, see
  SODIUM.md §6), so it covers far less terrain than the vanilla path's full shadow-frustum section set (ForcedSections).
- The main view is the comparable part: opaque **+0.36 ms**, translucent −0.18 ms, net **+0.18 ms slower on Sodium**. The
  estimate in SODIUM.md §5 was −0.2…−0.6 ms. Possible causes, not yet measured: per-vertex decoding in the pack's large
  vertex shader, Sodium's smaller per-region/per-facing draws, and the missing mc_Entity ids changing which foliage paths run.
- **Vanilla baseline is 98 FPS today, not the 137 recorded for M5–M7.** The vanilla shadow_opaque pass now costs 3.35 ms GPU
  (M7 records 0.47 ms) plus 3.15 ms CPU. This is a regression on master, independent of Sodium, and probably comes from the
  full shadow-frustum coverage (ForcedSections).
- Gate: numerically ≥ 137 (143), but the real decision waits for step 3. Once Sodium's shadow pass covers the same sections,
  it can be compared fairly.

## M8-S2: Sodium path vs vanilla path (2026-11-13, `sodium/s1-terrain`, steps 1–4)

Same code; "Sodium" = `-Psodium` (Sodium 0.9.3-beta.1 meshes and draws terrain, our extended vertex, our shadow lists),
"vanilla" = without it. 1080p, RD 16, pinned `frameTimeCounter=100`, 30 s, runs interleaved V S V S V S under one exclusive
GPU hold (no other game running).

| scene | vanilla FPS (runs) | Sodium FPS (runs) | Δ | 1% low vanilla / Sodium |
|---|---|---|---|---|
| overview | 129.6 / 129.7 / 126.8 (128.7) | 119.0 / 118.2 / 116.8 (118.0) | −8.3 % | 101–103 / 94–103 |
| sunset | 162.0 / 160.0 / 161.3 (161.1) | 152.8 / 154.7 / 151.4 (153.0) | −5.0 % | 128–133 / 130–136 |
| forest | 194.2 / 195.1 / 197.9 (195.7) | 191.7 / 194.7 / 193.1 (193.2) | −1.3 % | 149–156 / 157–160 |

GPU profile, overview (`-Demrion.autotest.profile=true`, ms/frame, one 25 s run each):

| | vanilla | Sodium | vanilla RD 32 | Sodium RD 32 | vanilla 960×540 | Sodium 960×540 |
|---|---|---|---|---|---|---|
| FPS | 128.2 | 116.5 | 90.5 | 88.7 | 203.0 | 187.7 |
| GPU frame | 7.67 | 8.49 | 10.72 | 10.93 | 4.83 | 5.22 |
| gbuffers opaque | 2.48 | 2.95 | 4.51 | 4.59 | 1.54 | 1.80 |
| gbuffers translucent | 1.07 | 1.20 | 1.47 | 1.42 | 0.56 | 0.56 |
| shadow_opaque | 0.78 | 0.91 | 0.99 | 1.39 | 0.78 | 0.94 |
| shadow_translucent | 0.15 | 0.34 | 0.14 | 0.14 | 0.13 | 0.18 |
| CPU: shadow pass recording | 1.74 | 0.81 | 2.37 | 1.38 | 1.63 | 0.81 |
| CPU: MC (rest) | 0.89 | 0.66 | 2.43 | 1.96 | 0.77 | 0.51 |

- Every scene stays **GPU-bound** on this machine, even at RD 32, so Sodium's CPU savings (shadow recording −0.9…−1.0 ms, MC
  rest −0.2…−0.5 ms per frame) do not show as FPS. They show as better 1% lows in sunset and forest.
- The Sodium path costs **+0.3…+0.8 ms GPU** per frame:
  - Opaque: +0.47 ms at RD 16. Of that, +0.26 ms remains at a quarter of the pixels, so about half scales with pixels and half does not. At RD 32 it is equal.
  - Shadow: +0.13…+0.40 ms. Sodium's built set is Iris's set and larger than MC's occlusion graph (1560 against 951
    sections after culling), which is the price of Iris-exact shadow coverage.
- Ruled out (measured, reverted): draw order (sorting sections near to far inside a region: 2.87 → 2.86 ms), block-face
  culling (off: 2.84 ms), the per-vertex section-time fetch (9a1c34d, opaque 2.95 ms with it skipped), and descriptor churn
  (MC pushes descriptors only when they change). Sodium draws fewer opaque triangles (648 k after face culling against 955 k).
- Chunk rebuild after a full remesh: vanilla 666–830 ms, Sodium 1232–1702 ms. Sodium's "all built" test is its build queue
  being empty, not MC's `hasRenderedAllSections`, and our extended encoder (normal/tangent per quad) runs on its workers, so
  the column is rough (SODIUM.md §1).
- Sodium 0.9.3-beta.5 (`sodium/beta5`): overview 118.1 FPS, opaque 2.92 ms, the same as beta.1.
- Device-local shared quad index buffer (Sodium makes it host-visible, `USAGE_MAP_WRITE`): uploaded once into a device-local
  buffer, overview 116.7 FPS, opaque 2.91 ms. No effect, reverted. The section-time buffer is no longer read with fade-in off.
- **Goal not reached:** Sodium ≥ vanilla on GPU-bound scenes. Still unexplained: the non-pixel part of the opaque gap.
  Host-visible buffers are ruled out (above). Untested: Sodium's shared quad index buffer is one large INT buffer, where MC
  uses SHORT/INT per section.

## Raster path at 1440p (2026-11-14, `perf/raster`)

Goal: a faster non-RT path with no visible change. 2560×1440, RD 16, pinned `frameTimeCounter=100`, the user's pack and settings,
Sodium 0.9.3-beta.5. Each launch runs a warm-up scene first, then benchmarks 5 scenes for 20–30 s each
(`-Demrion.autotest.benchmark.perScene=true`, one CSV per scene).
"Before" is master `f94b71c` (3 runs; a cold first run without warm-up was dropped). "After" is this branch (vanilla: 2 runs, a third had a
425 ms stall in the first scene; Sodium: 3 runs).

| Scene | vanilla before | vanilla after | Δ | Sodium before | Sodium after | Δ |
|---|---|---|---|---|---|---|
| overview | 96.4 | 98.8 | +2.5 % | 86.3 | 88.5 | +2.5 % |
| forest | 147.8 | 152.2 | +3.0 % | 139.7 | 144.7 | +3.6 % |
| water | 83.1 | 84.8 | +2.0 % | 79.7 | 81.9 | +2.8 % |
| cave | 232.1 | 242.8 | +4.6 % | 194.7 | 218.7 | **+12.3 %** |
| sunset | 114.5 | 118.0 | +3.1 % | 106.8 | 111.4 | +4.3 % |

Same build with the switches off (`-Demrion.perf.flipSwap=false -Demrion.perf.identitySkip=false`; Sodium:
`-Demrion.perf.sodiumVisibleReceivers=false`), 2 runs each, gives the same picture: vanilla overview 96.3 → 98.8, cave 226.4 → 242.8;
Sodium cave 200.9 → 218.7, sunset 109.8 → 111.4.

### What changed

| Change | GPU (overview, 1440p) | Switch |
|---|---|---|
| **End-of-frame copies become image swaps** (`FlipSwap`). Iris copies every flipped colortex alt → main at the end of the frame. When the first access to the alt side in every frame is a full overwrite (a fullscreen pass without blending, viewport scale/offset or discard in its SPIR-V, or a clear), swapping the two images leaves main with exactly the copied content; only the dead alt side differs. Targets with mips or storage bindings are never swapped. A second set of descriptor sets covers the swapped assignment. Unbound: colortex1, 2, 4, 7 (colortex5 has mips and keeps its copy). | copies 0.18 → 0.02 ms | `emrion.perf.flipSwap` |
| **Identity copy pairs are skipped** (`IdentityCopies`). composite2 and composite3 only do `vec4(texelFetch(colortex0, ivec2(gl_FragCoord.xy), 0).rgb, 1.0)` into R11G11B10F (main → alt → main). They are recognised in the SPIR-V. When alt is overwritten before its next read (here: the frame-start clear), both are skipped. | 0.08 → 0 ms | `emrion.perf.identitySkip` |
| **Sodium shadow receivers: visible sections only.** The receiver grid used every built section in the camera frustum, including terrain hidden behind walls. Now it uses air in the frustum (volumetrics sample the shadow map there) plus the sections Sodium's render lists draw this frame. This matches the vanilla path's reachable-only receivers. | cave: shadow_opaque 0.49 → 0.21 ms; overview 0.99 → 0.84 ms | `emrion.perf.sodiumVisibleReceivers` |

Image check (MAE mean / SSIM, after against before): vanilla overview 0.42 / 0.996, forest 0.52 / 0.994, water 0.63 / 0.988,
cave 0.21 / 0.998, sunset 0.33 / 0.996. Sodium: 0.34 / 0.997, 0.62 / 0.992, 0.62 / 0.989, 0.26 / 0.998, 0.33 / 0.996. Two runs
of the same build differ by the same amount (vanilla 0.22–0.64, Sodium 0.23–0.63). The diff panels show only falling leaf particles
and water. RT-off converter output is unchanged (no converter change; `TargetPackRtTest` passes).

### Profile at 1440p (vanilla, overview, before)

GPU 10.4 ms per frame: opaque 3.23, composite1 1.58, translucent 1.53, shadow_opaque 0.80, shadowcomp 0.62, deferred1 0.62,
composite5 0.34, deferred voxel clears 0.29, composite6 0.17, composite 0.16. Everything this mod adds around the pack's shaders
is small: frame-start clears 0.05, mipmaps (colortex0 twice, 3 and 5) 0.13, depth copies 0.05, end-of-frame copies 0.18,
composite2+3 0.09. The rest is the pack's own shader work.

### Investigated, not changed

- **Sodium: 16-bit quad indices** instead of the shared INT buffer (own SHORT buffer for regions up to 16 384 quads): opaque
  3.91 vs 3.89 ms, no gain. Index values (and so vertex reuse) are the same, only the fetch width changes.
- **Sodium: indirect backend** (`vkCmdDrawIndexedIndirect` instead of `VK_EXT_multi_draw`): 3.87 vs 3.89 ms, no gain.
- **Sodium: block-face culling off** (fewer, larger draws; rasterizer back-face culling makes it pixel-identical): 1440p
  opaque 3.94 vs 3.89 ms, forest 2.47 vs 2.31 ms. Slower: the extra vertex work outweighs the draw count.
- **Sodium vertex decode:** `terrain_sodium` vs `terrain_mdi` vertex SPIR-V: 1526 vs 1415 instructions; the difference is the
  bit unpacking (about 30 ALU ops plus the section-time fetch that is already skipped without fade-in), against a shader of about
  1400. Not the cause of the opaque gap. The remaining Sodium opaque gap (+0.65 ms at 1440p overview, +0.25 ms in the cave)
  stays open. (Found and fixed: draw order across regions, see "Sodium opaque gap" below.)
- **Mipmaps** (colortex0/3/5): the pack requests them (`mipmap.*`) and samples the levels. 0.13 ms in total, needed.
- **Clears:** 0.05 ms in total with targets in VRAM. The 14 ms seen once came from targets spilled to system RAM (M5b). The
  alt sides of colortex0/3 are fully overwritten before they are read, but both targets have mip chains and the clear zeroes all
  levels, so dropping it is not provably invisible.
- **Barriers, layouts, load ops:** raw passes already use one precise sync2 barrier per pass, every image stays in GENERAL, and
  LOAD vs DONT_CARE makes no difference on a desktop GPU (no tile memory).
- **Shadow map resolution:** 2048, as the pack's `SHADOW_QUALITY=3` + `SHADOW_SMOOTHING=4` select.
- **Deferred voxel clears (0.29 ms):** the pack declares the images with clear; whether a later pass rewrites every texel is not
  provable from the shaders.
- **CPU:** the frame is GPU-bound in every scene (4–9 ms per frame blocked on the GPU), so render-thread work does not limit FPS
  here. Sodium's slower full remesh (SODIUM.md §1) was not changed.

## Sodium opaque gap: draw order across regions (2026-11-15, `perf/sodium-opaque`)

2560×1440, RD 16, pinned `frameTimeCounter=100`, the target pack (Unbound r5.9.3 + Euphoria Patches 1.10.5) with the
settings of the reference test instance, its `emberlight.properties` with upscaling and frame generation off,
Sodium 0.9.3-beta.5 with that instance's `sodium-options.json`. Scenes overview, forest, cave, 20 s each, 3 interleaved runs
(vanilla, Sodium before, Sodium after) per RT mode, median. "Before" is the same build with `-Demrion.perf.sodiumBands=false`.

**Where the time went.** `-Demrion.debug.terrainMarks=true` (new) splits every geometry phase per terrain pipeline with a GPU
timestamp and a pipeline-statistics query (primitives in, vertex invocations, primitives after clipping, fragment
invocations). Per frame, main view, solid terrain:

| scene | vanilla: prims after clip / FS invocations / ms | Sodium before | Sodium after |
|---|---|---|---|
| cave | 2.8 k / 3.76 M / 0.77 | 2.4 k / 4.66 M / 0.95 | 2.4 k / 3.97 M / 0.81 |
| overview | 385 k / 3.24 M | 328 k / 3.48 M | 329 k / 3.37 M |

Sodium already draws fewer triangles (block-face culling), so neither vertex work nor draw count is the gap. It is
**overdraw**: in the cave Sodium runs the fragment shader 24 % more often than vanilla (1.26 per pixel against 1.02).
Vanilla's multi-draw-indirect list is sorted near to far across all sections. Sodium draws region by region (8×4×8
sections). It sorts regions by distance and keeps the sections inside a region in octree order. So the far cave sections of
a near region are shaded before the box walls in the next region hide them. With this pack's expensive terrain fragment
shader, and with RT ray queries in it, that costs real time. The earlier "+0.65 ms at the overview" no longer reproduces on
`test/all-current` (vanilla opaque rose to ≈3.95 ms; both paths are equal there now). What is left is the order effect.

**Fix** (`DefaultChunkRendererMixin`, `VKMultiDrawBatchMixin`): for the opaque passes, each region's command list is
filled in distance bands (Chebyshev section distance 0, 1, 2–3, 4–7, 8–15, 16+; sorted near to far inside a band). The
regions are then drawn once per band, nearest band first, each draw covering that band's sub-range of the region's
commands. The commands are the same (same face culling, same shared index buffer), only their order changes. Translucent
passes and the shadow pass are unchanged. Finer far bands (10 half-octave bands) measured no faster. The indirect backend
keeps Sodium's order.

| RT off | vanilla FPS / GPU / opaque | Sodium before | Sodium after |
|---|---|---|---|
| overview | 65.3 / 11.90 / 3.98 | 81.0 / 11.74 / 3.99 | 81.8 / 11.52 / 3.92 |
| forest | 118.3 / 7.44 / 2.45 | 120.0 / 7.25 / 2.34 | 120.0 / 7.17 / 2.20 |
| cave | 163.4 / 4.44 / 0.79 | 177.3 / 4.85 / 1.02 | 180.0 / 4.64 / 0.84 |

| RT RTX | vanilla FPS / GPU / opaque | Sodium before | Sodium after |
|---|---|---|---|
| overview | 41.0 / 24.32 / 9.43 | 40.8 / 24.43 / 9.51 | 41.2 / 24.23 / 9.37 |
| forest | 52.2 / 18.99 / 6.95 | 53.3 / 18.70 / 6.82 | 54.0 / 18.45 / 6.62 |
| cave | 59.7 / 16.17 / 4.43 | 59.7 / 15.89 / 5.03 | 60.0 / 15.83 / 4.47 |

(ms per frame. RTX = the instance's RT settings: style rtx, full shadows, GI high, reflections, refraction, AO, caustics.)
The RT-off forest and cave FPS sit on 120 and 180 in nearly every Sodium run, so the test display seems to cap them there.
Compare GPU ms there. Vanilla FPS varies between runs (overview 60–77) at the same GPU time.

- Opaque pass on Sodium: cave −0.18 ms (RT off) and −0.56 ms (RTX), forest −0.14 / −0.20 ms, overview −0.07 / −0.14 ms.
  Sodium's opaque pass is now at or below vanilla's in all six cases (cave RT off +0.05 ms).
- What remains in the Sodium cave GPU frame is the shadow pass (Iris's larger section set, known, M8-S2).
- Image: before vs after (RT off, three run pairs) MAE 0.14–0.46, SSIM ≥ 0.9946. Two runs of the same build differ by
  0.25–0.55. RTX: 0.26–0.61 against 0.04–0.60 between same-build runs (temporal GI noise on the leaves, plus a falling-leaf particle).
  No converter change (the RT-off golden `TargetPackRtTest` is unaffected).
- Ruled out with the statistics: draw count and multi-draw batching, vertex decode (Sodium's vertex invocations are lower),
  per-draw descriptor or push-constant updates, and state changes between regions (the cave draws 2.4 k triangles and still
  costs more).

### Follow-up: shadow pass order and section set (2026-11-15)

**Order.** `SodiumShadow` now adds the opaque shadow casters near to far from the light (centre depth along the shadow
view axis), in 64-block depth bands. It draws the regions once per band, nearest band first, and the regions in order of
their nearest caster. Same commands, same section set and cutoffs. Translucent shadow draws keep their order.
`-Demrion.perf.sodiumShadowBands=false` restores region order. Pipeline statistics, solid shadow casters per frame:
fragment invocations overview 12.0 M → 3.85 M, cave 7.57 M → 3.51 M, forest 5.05 M → 3.60 M (triangles unchanged).
Cutout casters barely change (8.6 M → 8.2 M), probably because alpha-test discard limits early depth rejection.

Same setup as above, Sodium only, 3 interleaved runs, median, ms per frame:

| | shadow_opaque before → after | GPU frame before → after |
|---|---|---|
| RT off overview | 0.88 → 0.75 | 11.51 → 11.59 |
| RT off forest | 0.73 → 0.70 | 7.07 → 7.16 |
| RT off cave | 0.30 → 0.24 | 4.68 → 4.60 |
| RTX overview | 0.83 → 0.68 | 24.08 → 23.79 |
| RTX forest | 0.70 → 0.67 | 18.48 → 18.57 |
| RTX cave | 0.29 → 0.22 | 15.73 → 15.76 |

The shadow pass is 0.03–0.15 ms faster in every case. The frame totals move by −0.29 to +0.09 ms, which is within run-to-run
noise. Render-thread shadow recording: 0.62 → 0.68 ms (the sort; the frame is GPU-bound). Image, before vs after: RT off MAE
0.23–0.43, RTX 0.26–0.56, against 0.23–0.49 and 0.26–0.61 between two runs of the same build.

**FPS cap.** The gamescope test display caps FPS at about 120 and 180. Sodium's RT-off forest and cave runs sit on those
values, so the comparisons here use GPU timestamps (frame and per-pass ms), not FPS.

**Section set: not trimmed.** Our shadow caster set is already smaller than Iris's. Iris draws every renderable section in
the shadow frustum. We keep only casters whose shadow can fall on a receiver. The cave's 391 k solid casters (vanilla path:
68 k) come from the visible box receivers. Dropping all air receivers as an experiment leaves the cave set unchanged and saves
0.1 ms in the overview. That experiment also drops visible air that volumetric light samples, so it is not a valid change.
Trimming further would need one of these, and neither is provably Iris-equal:
- Vanilla's frustum-free reachability. Sodium's equivalent is per-section search state that its async culler rewrites while
  we would read it. Its `isBoxVisible` also treats empty sections as always visible, so air visibility is not tracked.
- Dropping enclosed casters. They are never the nearest occluder from the light, but they are needed for the voxelisation
  in the safe zone (coloured light, WSR).


## Iris vs Emberlight vs Emberlight + Sodium at 1440p (2026-11-15, `test/all-current` 0fd022f)

2560×1440, RD 16, pinned `frameTimeCounter=100`, the target pack (Unbound r5.9.3 + Euphoria Patches 1.10.5) with the
settings of the reference test instance and its `sodium-options.json`. Emberlight uses that instance's
`emberlight.properties` with upscaling and frame generation off. RTX = its RT settings (style rtx, full shadows, GI high,
reflections, refraction, AO, caustics). Iris 1.11.7 + Sodium 0.9.3-beta.1 (Iris needs beta.1); Emberlight + Sodium uses
beta.5. A warm-up scene first, then 20 s per scene. 3 rounds, interleaved (Iris, vanilla, Sodium, vanilla RTX, Sodium RTX),
median. Spread between runs ≤ 1 % except vanilla cave/night (≈ 3 %).

The test display no longer caps FPS: it now runs gamescope with `-r 1000 -o 1000 --immediate-flips`. The 120/180 caps
in the sections above came from its default nested refresh. The Iris run now logs GPU ms too (`GlGpuTimer`: GL timestamp
queries around `GameRenderer.render`, the same span as the Vulkan profiler's frame total, with `-Demrion.autotest.profile=true`).
`runIrisReference` takes `-PwinWidth/-PwinHeight` like `runClient`.

| Scene | Iris FPS / 1% low / GPU ms | Emberlight | Emberlight + Sodium | Emberlight RTX | Emberlight + Sodium RTX |
|---|---|---|---|---|---|
| overview | 61.4 / 58.6 / 16.20 | **97.2** / 80.7 / 10.23 | 89.9 / 81.1 / 11.07 | 43.5 / 39.1 / 22.91 | 42.9 / 39.9 / 23.26 |
| forest | 110.2 / 100.0 / 9.01 | **150.3** / 123.9 / 6.60 | 147.9 / 127.8 / 6.71 | 53.0 / 47.6 / 18.79 | 55.8 / 52.2 / 17.87 |
| cave | 138.1 / 126.6 / 7.17 | **248.5** / 194.2 / 3.97 | 223.9 / 173.9 / 4.41 | 65.3 / 58.3 / 15.26 | 65.8 / 60.8 / 15.15 |
| water | 65.6 / 62.5 / 15.17 | **83.2** / 72.1 / 11.96 | 82.1 / 73.6 / 12.14 | 34.2 / 31.1 / 29.20 | 36.1 / 34.2 / 27.66 |
| night | 168.0 / 154.7 / 5.87 | 219.7 / 175.5 / 4.50 | **227.9** / 185.8 / 4.34 | 170.7 / 141.2 / 5.80 | 179.8 / 156.5 / 5.50 |

- Emberlight (RT off) is 1.27× (water) to 1.80× (cave) Iris's FPS, and 1.58× in the overview. Every frame is GPU-bound on
  both renderers, so the GPU ms tell the same story: 16.2 → 10.2 ms in the overview.
- Sodium vs vanilla path, RT off: equal within 1–3 % in forest, water and night. It is slower in overview (−7.5 %, +0.84 ms GPU)
  and cave (−10 %, +0.44 ms), the shadow-pass section set (M8-S2). Sodium has the better 1% lows in 4 of 5 scenes.
- With RTX the Sodium path is equal or faster: forest +5 %, water +6 %, night +5 %, cave and overview within 1.5 %.
- Image check (run 1, RT off, against Iris): vanilla MAE 0.26–0.61 / SSIM ≥ 0.988, Sodium 0.24–0.70 / ≥ 0.991.

Scripts: `fpsbench/run.sh`, `batch.sh`, `summ.py` in a scratch folder (not in the repo).

## Shader compile stutter (2026-11-16, `feat/precompile`)

Target pack (Complementary Unbound r5.9.3 + EP 1.10.5, user settings), RT on (`rt.style=rtx`), Sodium 0.9.3-beta.5, 2560×1440,
`-PpipelineCacheDir` pointing at a run-private directory. One script per run (about 3 min after the world join): place a
plains village, summon ~30 kinds of mobs and entities (charged creeper, armour stand with enchanted armour, end crystal,
displays, lightning), place block entities, end portal/gateway, beacon, nether portal, water/lava, a dozen particle types,
open the inventory, thunder, night, under water, fly 20 s over new terrain, then the Nether (fly 8 s) and the End.
"cold" = empty VkPipelineCache directory *and* empty NVIDIA shader disk cache (`__GL_SHADER_DISK_CACHE_PATH`); "warm" =
both from the previous run; "vk only" = VkPipelineCache kept, NVIDIA disk cache emptied. Counted from
`logs/emberlight-hitches.log` (frames > 25 ms and well above the median); "compile" = a hitch whose top entries include
`pipeline compile` or `late pipeline`.

| Run | compile hitches (all at the world join, before the pack renders) | gbuffers pipelines built in a frame | pack renders after join | first Nether / End switch | gameplay hitches (village to Nether) |
|---|---|---|---|---|---|
| before, cold | 2: 39, 499 ms | 0 | 20.2 s (12.0 s conversion, 11.3 s pipelines) | 11.9 s / 21.7 s of vanilla | 14 (837 ms total, worst 102) |
| before, warm | 2: 152, 150 ms | 0 | 3.7 s | 0.20 s / 0.23 s | 15 (647 ms, worst 102) |
| after, cold | 4: 43, 145, 115, 126 ms | 0 | 18.5 s (conversion cached, 15.1 s pipelines) | **0.15 s / 0.28 s** | 17 (755 ms, worst 102) |
| after, warm | 3: 34, 145, 117 ms | 0 | 3.8 s | 0.16 s / 0.20 s | 16 (660 ms, worst 92) |
| after, vk only (2 runs) | 3 / 2 | 0 | 4.5 s (world0 pipelines 1.4-1.6 s) | 0.41 s / 0.36 s | 25 / 18 (mostly gpu-wait and acquire) |

- No pipeline is compiled in a frame during play: every gbuffers variant of every known vanilla and Sodium world pipeline
  (303 here) is built at pack load, so new blocks, entities and render types never compile. The only `pipeline compile`
  hitches are MC's own vanilla compiles at the world join (6-7 pipelines, 140-370 ms), the same before and after.
- The real cost was the first trip to another dimension with a cold cache: 12-22 s of vanilla rendering while that
  dimension's pipelines were built. They are now built in the background after the first activation (2 threads, 23 s +
  33 s cold, 0.4 s warm) and the switch takes 0.15-0.28 s. No extra compile hitches; the gameplay hitch counts stay within
  the run-to-run spread (all GPU-bound `gpu-wait`/`acquire` frames at a 27 ms median).
- The VkPipelineCache is hit: with the NVIDIA disk cache emptied the startup dimension builds in 1.4-1.6 s instead of 15 s.
  But the saved file did not grow when the other dimensions were compiled in a session (55,569,556 bytes before and after
  two vk-only sessions), and their background builds stayed cold (22-36 s). In normal use the NVIDIA disk cache covers
  them; why the driver does not write them into the application cache is open.
- Reproduce: `runClient -Pcompare -Psodium -PpipelineCacheDir=<dir>` with the steps above as `-Demrion.autotest.script`
  (`cmd place structure minecraft:village_plains ...`, `cmd execute at @p run summon ...`, `move 0 0 -25 20`, ...).

## No vanilla frames on a dimension switch (2026-11-17, `fix/dim-switch`)

When a portal comes before the background precompile of that dimension is done (cold caches, right after the
join), the pack used to render vanilla until the dimension's pipelines were built. Now MC's level loading screen stays
open until the pack renders (`LevelLoadingScreenMixin`, at most 30 s), with the loading panel on it. Only the screen
waits: the player-loaded packet goes to the server on vanilla timing (on a server the client would otherwise stay
"not loaded" after the server's own 3 s timeout).
Recorded with the new `frames SECONDS NAME` step (every presented frame, quarter size, GUI included); 2560×1440, RT
`rtx`, Sodium. Cold = empty VkPipelineCache and NVIDIA disk cache, portal 1-2 s after the pack renders; warm = Nether
portal, Nether portal back, `execute in minecraft:the_end run tp`, `execute in minecraft:overworld run tp`.

| Run | before | after |
|---|---|---|
| Complementary U + EP, cold, Overworld → Nether | 7.7 s of vanilla Nether | 7.9 s portal screen with "Preparing shaders… x/323", then shaded |
| Complementary, warm (Nether / back / End / back) | 0.45 / 0.27 s, no vanilla frames | 0.44 / 0.24 / 0.33 / 0.26 s, no vanilla frames |
| Eclipse (Merlin), cold / warm | — | 1.1 s / 0.7-0.8 s, no vanilla frames |
| packs/template, cold / warm | — | 0.06 s / 0.17-0.26 s, no vanilla frames |
| LAN test server, Complementary cold / warm, `execute in` | — | 5.9 s held / 0.19-0.20 s, no vanilla frames, no kick or timeout |

The times are "pack renders N ms after the switch" from the log (the level renders behind the screen, so the hold does
not lengthen the build). The first world join waits the same way behind its loading screen (pack renders 15-16 s after
the join cold, 0.8-0.9 s warm; not recorded frame by frame).

## RT stutter after teleports and dimension switches (2026-11-16, `fix/tlas-stutter`)

Same setup as above (target pack, RT `rtx` with GI high, Sodium, 2560×1440, warm caches). One script per run, every phase
recorded with the new `trace start` / `trace stop NAME` steps (every frame time -> `emrion-trace-NAME.csv`, VSync and cap
unchanged): world join and settle at 600 110 600, a 10 s idle bench, `/tp` 2000 blocks into fresh terrain, 15 s of flight
at 40 blocks/s, the Nether (settle, then 8 s moving), the End, back to the overworld, another idle bench. Spectator mode.

**Cause.** Not the acceleration structures: the TLAS build is 0.1-1 ms and BLAS builds are 1-4 ms per frame there. The
hitch log (which now splits `RT lights` from `TLAS`) put 60-150 ms per frame into `RtLights.rebuild`, the block-light lists,
which run whenever a section with lights recompiles or the camera crosses an 8-block cell. Next to a Nether lava sea the
grid holds ~16 k sources and ~1 M (cell, source) entries; most cells exceed 512 sources, and that "rare" path sorted boxed
`Integer` lists with a comparator (80 % of the time, JFR). While the Nether loaded this ran every frame.

**Changes.** (1) The overfull cells are sorted as primitive `long` keys (weight bits, pair index) in reused arrays: the
same order, unit-tested against the old comparator; 100-150 ms -> 60-80 ms. (2) A rebuild that took more than 4 ms makes
the next one run on a worker into a second set of lists, swapped in when done (the frame keeps the last complete lists).
Normal scenes (a rebuild of ~1600 lights: 2.3 ms) stay synchronous, so a placed torch still lights in the same frame.
(3) A recompile with the same lights no longer triggers a rebuild. (4) At most 400 BLAS per frame (each ~25 µs CPU): the
world join had one 62 ms frame of 2253 BLAS.

| Phase | before: frames > 50 ms / > 100 ms, max, avg FPS | after, run 1 | after, run 2 |
|---|---|---|---|
| world join + settle | 4 / 0, 67 ms, 49.8 | 3 / 0, 89 ms, 50.8 | 4 / 0, 92 ms, 50.5 |
| `/tp` 2000 blocks | 3 / 0, 86 ms, 48.7 | 1 / 0, 51 ms, 51.0 | 4 / 0, 76 ms, 47.9 |
| flight 40 blocks/s | 3 / 0, 95 ms, 35.7 | 5 / 0, 62 ms, 35.4 | 3 / 0, 76 ms, 36.0 |
| **Nether** | **398 / 30, 157 ms, 18.2** | 4 / 1, 124 ms, 73.5 | 4 / 0, 92 ms, 73.2 |
| End | 5 / 2, 166 ms, 94.5 | 2 / 0, 78 ms, 97.5 | 1 / 0, 97 ms, 98.9 |
| back to the overworld | 3 / 0, 68 ms, 43.7 | 3 / 0, 72 ms, 41.8 | 3 / 0, 77 ms, 46.8 |
| idle bench (2 × 10 s) | 35.5 / 35.2 FPS | 36.6 / 35.4 | 36.0 / 35.3 |

- `RT lights` in a hitch line: up to 147 ms before, at most 1.3 ms after. The remaining > 50 ms frames are the dimension
  switch itself (`targets`: the pack's render targets for the new dimension, 20-35 ms, one frame), GC pauses and GPU-bound
  `gpu-wait`/`present` frames, the same in every run.
- Image: the settled shots after the teleport, in the Nether and in the End match the old ones within run-to-run noise
  (MAE 0.5-1.1 against before, 0.4-0.9 between two runs after). Shots right after the teleport differ only by when the
  chunks arrived.
- Missing geometry: the RT log line now reports `longest wait N frames` (Sodium path: from a section's upload to its BLAS).
  It was 0 in every 5 s window of all runs, also with `-Demrion.rt.blasPerFrame=1000000`: a section is in the TLAS in the
  frame its mesh is uploaded. What is missing right after a teleport is missing for the raster path too (chunks not yet
  generated or meshed).
- The 400 BLAS cap did not bind in these runs after the join (at most ~300 per frame while loading the Nether); with it
  off the numbers were the same (Nether 6 / 0, max 97 ms).
- Files: `run.sh`, `sum.py` and all outputs in a local test folder (not in the repo).

## Async compute and variable rate shading: tried, dropped (2026-11-16, `perf/async-vrs`)

Goal: more FPS with RT on, kept only if the image is not worse. Setup: target pack, the user's RT settings (`rtx`, full
shadows with 4 rays, GI high, block light 2 rays, reflections, AO), Sodium, 2560×1440, pinned `frameTimeCounter=100`,
20 s per scene, no upscaling unless noted. Two baseline runs give the noise: FPS ±3 %, image floor (MAE between the two)
overview 0.38, forest 0.57, cave 0.23, water 0.65, night 0.20.

| Scene | baseline FPS (2 runs) | GPU: gbuffers opaque / translucent ms | prepare (GI + ReSTIR) ms |
|---|---|---|---|
| overview | 36.5 / 36.9 | 13.16 / 2.64 | 4.74 |
| forest | 53.0 / 51.2 | 6.49 / 0.07 | 6.22 |
| cave | 68.6 / 70.0 | 1.82 / 0.01 | 7.89 |
| water | 32.2 / 33.3 | 12.12 / 4.92 | 6.89 |
| night | 159 / 169 | 0.01 / 0.01 | 1.04 |

**Async compute: not built.** The 4070 Ti has a compute-only family (8 queues) and 16 graphics queues; a second queue of
the graphics family would even avoid ownership transfers (all our images are in GENERAL, EXCLUSIVE). But the frame has
almost nothing to overlap. The only big compute work is `prepare_a..i` (RTX GI and ReSTIR block light, 4.7-7.9 ms). It
reads no colortex, depth or shadow map, only its own images, the atlases and the TLAS, so in principle it could run
beside the shadow pass. However its primary rays use mask 0x05, i.e. the entities, and the entity BLAS/TLAS is built
from the shadow pass's entity capture (`RtScene.refreshEntities`, after `shadow_translucent`). Everything after it needs
the GI result (`gbuffers opaque` applies it in `DoLighting`), the AO trace needs depth, the composites are fragment work
on the same images. The window left is `shadowcomp` (0.72 ms, itself compute) plus 0.13 ms of translucent shadow
geometry: < 3 % even with perfect overlap, for a split of MC's single per-frame submit into cross-queue timeline waits.
Moving the entity capture before the shadow pass, or a second TLAS without entities for `prepare`, would change what GI
sees (an image change) and would still only open the 0.83 ms shadow-raster window (≈ 1 ms ceiling, ~3 %).

**Variable rate shading (VK_KHR_fragment_shading_rate): prototyped, dropped.** Supported here with 16×16 attachment
texels. Prototype: a compute pass after `final` writes an R8_UINT rate image (1×1/2×1/1×2/2×2 per tile) from the contrast
of the finished image; the world's `opaque` (and optionally `translucent`) gbuffers passes begin with it as their
fragment-shading-rate attachment (mixin on `VulkanCommandEncoder.createRenderPass`, all non-shadow gbuffers pipelines
built with `RENDERING_FRAGMENT_SHADING_RATE_ATTACHMENT_BIT` and combiner KEEP/REPLACE). The rate pass costs 0.04 ms;
building the pipelines with the flag but no attachment cost nothing measurable. Variants (thresholds on sRGB luma 0-255,
mean / max of neighbouring pixel pairs per tile):

| Variant | overview | forest | cave | water | Image (MAE vs baseline; floor above) |
|---|---|---|---|---|---|
| uniform 2×2 on all terrain (upper bound) | 44.4 FPS (+21 %) | 67.5 (+30 %) | 75.8 (+9 %) | 42.9 (+31 %) | 2.81 / 2.75 / 0.83 / 3.11: blocky leaves, textures at half resolution |
| adaptive, mean 2 / max 12, opaque + translucent | 38.7 (+5.5 %) | 55.2 (+6 %) | 69.9 (+1 %) | 34.0 (+4 %) | 0.62 / 0.73 / 0.52 / 1.18: sand grain blurred, 16 px tile pattern on water |
| same, pairs across 2×2 borders (no lock-in) | 38.6 (+5 %) | 53.7 (+3 %) | 70.2 (+1 %) | 33.0 (+1 %) | 0.52 / 0.69 / 0.44 / 1.08: seagrass under water loses detail, tile pattern on water |
| adaptive, mean 1 / max 6, opaque only | 37.2 (+1 %) | 54.0 (+4 %, noise) | 70.0 (0) | 31.7 (-3 %, noise) | 0.39 / 0.62 / 0.31 / 0.66: at the floor; gbuffers opaque 13.16 → 13.03 ms |

- Coarse shading saves less than its pixel share: uniform 2×2 cut `gbuffers opaque` only 13.2 → 9.9 ms, so about two
  thirds of that pass is not fragment-shading bound.
- Low contrast in the final image does not mean coarse shading is invisible here: the water and the shadows/GI are
  dithered per pixel and TAA resolves the noise; shaded per 2×2 block the dither turns into a visible checker inside
  the tile. And with pairs inside the 2×2 blocks as the metric, a coarse tile measures its own blur and stays coarse
  (the sand); pairs across block borders fix that but lower the gain.
- Under DLSS Quality (1706×960) the variant "pairs across borders" saved 0.3 ms in `gbuffers opaque` (overview) and
  1.3 ms opaque + translucent in water; DLSS hides the tile pattern but the underwater seagrass is washed out (water MAE
  1.07). The DLSS FPS runs were too noisy to quote (cave 107 vs 123 FPS for a 0.3 ms pass difference).
- Not tested: motion (the rates come from last frame without reprojection).

So: the settings that gain ≥ 3 % change the image visibly, the settings that keep it identical gain nothing. The
prototype is kept as `vrs-prototype.patch` next to the run scripts and outputs in
a local test folder (not in the repo).
