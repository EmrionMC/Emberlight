# Emrion Shaders

A Fabric client mod for Minecraft Java **26.3** that runs OptiFine/Iris shaderpacks on Minecraft's new **Vulkan** renderer.

Packs are converted once, when you select them, into a Vulkan-native format: optimised SPIR-V plus a precomputed frame
plan. The result is cached, and the mod then renders the pack through Minecraft's own Vulkan backend. Iris does the same
job on OpenGL, but Iris does not support Vulkan.

**Status:** M1–M6 done: Iris-equivalent image and 1.76× the FPS for the acceptance pack. Optional hardware ray tracing (M7) is planned. Development is tracked in [docs/PROGRESS.md](docs/PROGRESS.md).

## Results

The acceptance pack is *Complementary Unbound r5.9.3 + Euphoria Patches 1.10.5*, with a real user's settings.

**Performance** (1920×1080, render distance 16, same world, pack and settings, 3 × 30 s runs each, RTX 4070 Ti):

| | Iris 1.11.7 + Sodium 0.9.3 (OpenGL) | Emrion Shaders (Vulkan) |
|---|---|---|
| Average FPS | 75.3 | **132.9** (1.76×) |
| Median frame time | 13.30 ms | 7.45 ms |
| 1% low | 68.9 FPS | 107.1 FPS |

**Image** (difference to Iris, 8 fixed scenes, same world, animation time pinned in both renderers; Iris against
itself scores MAE 0.2–0.9):

| Scene | MAE | SSIM | Scene | MAE | SSIM |
|---|---|---|---|---|---|
| overview | 0.50 | 0.995 | night | 0.52 | 0.989 |
| forest | 0.51 | 0.996 | rain | 0.85 | 0.983 |
| water | 0.74 | 0.986 | entities | 1.00 | 0.989 |
| cave | 0.29 | 0.997 | sunset | 0.60 | 0.994 |

Night is slightly above Iris's floor (0.18) because the star field depends on time smoothing; entities because of the
chicken's wing flap. Method and scenes: [docs/BENCHMARK.md](docs/BENCHMARK.md).

These numbers come from the dev environment: vanilla textures and no other performance mods. Your own instance will differ.

## Installation

1. Minecraft **26.3** with Fabric Loader ≥ 0.20.1 and Fabric API.
2. Put `emrionshaders-<version>.jar` (from `mod/build/libs/`) into `mods/`.
3. Set **Options → Video Settings → Graphics API → Prefer Vulkan**, then restart. On OpenGL the mod stays inactive and tells you so.
4. Put shaderpacks (zip or folder) into `.minecraft/shaderpacks/` as you would for Iris. Iris settings files `<pack>.txt`
   next to them are used as they are and can be shared with Iris.
5. In game, press **O**, or use **Video Settings → Shader Packs (Emrion)…**, and select a pack. The first load converts the
   pack (a few seconds, with a progress bar); later loads come from the cache in `.minecraft/emrionshaders/cache`.

All keys are in the **Emrion Shaders** category of Options → Controls and can be rebound. Defaults follow Iris.

| Key | Action |
|---|---|
| O | Shader pack screen (Shader Options… inside it). Also bound by vanilla 26.3 to **Friends** (`key.friends`): both fire, rebind one of them if that bothers you |
| R | Reload the active pack from disk |
| K | Toggle shaders on/off (vanilla rendering while off; switching back reuses the built pipelines). Action bar shows "Shaders: on/off" |
| F7 | Profiler overlay (GPU and CPU ms per pass) |
| unbound | Wireframe (toggle; only in debug mode and in singleplayer, as Iris) |

Inside the shader pack screen, as in Iris:

| Key | Action |
|---|---|
| Tab | Open the shader options; Tab in the options applies the changes and goes back to the pack list (Shift+Tab still moves the focus) |
| F1 | Hide the screen to look at the world (F1 again shows it) |
| Ctrl+D | Debug mode on/off (asks first): every shader error in chat, profiler overlay on, wireframe key allowed. Saved in `config/emrionshaders.properties` |
| Drag & drop | Dropped pack zips and pack folders are copied into `shaderpacks/` |

Not taken over: Iris's Ctrl+G (load packs Iris does not recognise) has no equivalent, every pack is tried. K, R and F7
are free in vanilla 26.3.

The FPS counter in the top left corner is always shown. Start with `-Demrion.hideFps=true` to hide it.

## Building

```sh
export JAVA_HOME=/path/to/jdk25+   # Java 25 or newer
./gradlew build                    # mod jar in mod/build/libs, converter in converter/build/libs
./gradlew :converter:fatJar        # standalone converter: java -jar converter/build/libs/converter-*-all.jar <pack> <settings.txt|-> <out-dir>
```

## Supported features

| Feature | Status |
|---|---|
| OptiFine/Iris pack format (shaders.properties, block/item/entity.properties, dimension folders, includes, options, profiles) | ✅ |
| Iris settings file (`<pack>.txt`), read and written 1:1 | ✅ |
| GLSL 120–460 compatibility → Vulkan GLSL 450 → SPIR-V (own transpiler) | ✅ |
| gbuffers_* (terrain, water, entities, block entities, hand, sky, particles, weather, lines, …) | ✅ |
| deferred*, composite*, final, compute shaders (`.csh`, work groups) | ✅ |
| Shadow pass (terrain, entities, translucent, Iris culling modes) | ✅ |
| colortex/depthtex/shadowtex/shadowcolor, flips, clears, mipmaps, size.buffer | ✅ |
| Custom images (2D/3D, image load/store), SSBOs, writes from vertex shaders | ✅ |
| Custom textures, noisetex, custom uniforms (Iris expression language incl. `smooth`) | ✅ |
| Iris built-in uniforms (149) | ✅ |
| Block, item and entity IDs (mc_Entity, iris_Entity, at_midBlock, at_tangent, mc_midTexCoord) | ✅ |
| labPBR normals/specular for blocks | ✅ |
| labPBR for entities, items, block entities, particles (per draw, as Iris) | ✅ |
| Options GUI (screens, sliders, profiles, lang files) and hot reload | ✅ |
| Iris keys: toggle (K), reload, pack screen, wireframe, debug mode, pack-screen shortcuts | ✅ |
| Iris API for other mods (`net.irisshaders.iris.api.v0`, revision 4) when Iris is not installed | ✅ (partly: see below) |
| Geometry shaders, tessellation | ❌ (not needed by the target pack) |
| Distant Horizons / Voxy / Colorwheel / Photonics hooks | ❌ |
| OpenGL backend | ❌ (Vulkan only, by design) |

## Known limitations

- Developed and tested against one pack (Complementary Unbound + Euphoria Patches). Other packs may hit unimplemented paths.
- Tested on NVIDIA (RTX 4070 Ti, Linux) only. Other GPUs and drivers are untested.
- 256× resource packs with PBR need a lot of GPU memory together with Complementary's voxel data (about 2.45 GB), as with Iris.
  `-Demrion.pbr=false` turns PBR off.
- The first item or shulker box drawn with a 256× PBR pack causes a one-time hitch of about 0.3 s while its PBR atlas uploads.
- Some hooks depend on Fabric API internals (Indigo block rendering) and may need an update when Fabric API changes.
- A compute pass named like a fullscreen pass (`composite.csh` next to `composite.fsh`) is not supported yet.

## Iris API for other mods

Mods such as Entity Model/Texture Features ask Iris's public API (`IrisApi.getInstance().isShaderPackInUse()`,
`isRenderingShadowPass()`, …) to adapt to shaders. When Iris is **not** installed, Emrion Shaders puts its own copy of
that API (Iris's LGPL-3.0 API classes, identical signatures to Iris 1.11.7 for 26.3) on the class path at pre-launch and
answers from its own state: `isShaderPackInUse` = a pack is rendering, `isRenderingShadowPass` = inside our shadow pass,
`getConfig().areShadersEnabled/setShadersEnabledAndApply` = the K toggle, `getSunPathRotation`, `openMainIrisScreenObj` =
our pack screen. With Iris installed nothing is added.

- Only mods that detect the API by **class presence** see it (EMF, ETF; Sodium Extra also needs an Iris internal class and
  keeps treating Iris as absent). Mods that check `FabricLoader.isModLoaded("iris")` (Particle Rain, Litematica, MaLiLib)
  do not, on purpose: Emrion Shaders does **not** claim the `iris` mod id, because mods that see it also mix into and call
  Iris internals (ImmediatelyFast, Euphoria Patcher, VoxelMap, …) and would crash or misbehave.
- `assignPipeline`, `assignPipelineShadow` and `registerShadowRenderCallback` are accepted and ignored;
  `createTextVertexSink` throws `UnsupportedOperationException`.

## Robustness

Errors never crash the game. A shader that fails to compile drops that program and reports pack file and line in chat; any
exception during a frame disables the pack and Minecraft keeps rendering vanilla. Resize, fullscreen, dimension changes,
pack toggling, hot reloads and a 10-minute soak run without GPU memory growth, and with no errors from the Khronos
validation layer (see docs/BENCHMARK.md for the recipe).

## How it works

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and the format spec [docs/FORMAT.md](docs/FORMAT.md). In short:

- The **converter** is pure Java with no Minecraft dependency. It does everything Iris does at load time: options,
  preprocessing, transpiling, SPIR-V (shaderc with `-O`), the uniform layout, and a static flip/clear plan. It writes this
  into a versioned cache.
- The **mod** executes that plan. Geometry passes use raw Vulkan pipelines wrapped as Minecraft pipelines, so Minecraft's own
  draw code renders the world through the pack's shaders. Fullscreen and compute passes are recorded in raw Vulkan with
  precise barriers. Everything is compiled when the pack loads, so there is no shader stutter.

## License

LGPL-3.0-only (see `LICENSE` and `COPYING`). The Iris source code was used only as a specification; no Iris code is included.
Shaderpacks are not part of this repository.
