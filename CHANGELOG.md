# Changelog

## 0.1.0-alpha.1

First public alpha of **Emberlight** (developed as "Emrion Shaders"): a Fabric and NeoForge mod for Minecraft Java **26.3** that runs
OptiFine/Iris shaderpacks on Minecraft's **Vulkan** renderer. Expect bugs and keep backups of your worlds.

### Requirements

- Minecraft 26.3, Fabric Loader 0.20.1 or newer, Fabric API.
- Graphics API set to Vulkan (*Options → Video Settings → Graphics API → Prefer Vulkan*). On OpenGL the mod stays inactive
  and says so.
- Sodium **0.9.3** exactly (required). Other Sodium versions are refused at startup on purpose, because the internals
  Emberlight hooks change between versions.
- Ray tracing: a GPU with `VK_KHR_ray_query`. DLSS: an NVIDIA RTX GPU (frame generation: RTX 40 or newer); NVIDIA's model
  files are downloaded once on request (see below).

### Features

**Iris-compatible pack runtime**
- OptiFine/Iris pack format: shaders.properties, block/item/entity.properties, dimension folders, includes, options,
  profiles. Iris settings files (`<pack>.txt`) are read and written 1:1 and can be shared with Iris.
- All program stages: gbuffers, shadow, deferred, composite, final, compute, geometry and tessellation shaders; custom
  images, SSBOs, custom textures, noisetex, custom uniforms and all 149 Iris built-in uniforms.
- labPBR for blocks, entities, items, block entities and particles.
- The pack's option screens, hot reload (R), shaders on/off (K), profiler overlay (F7), wireframe and debug mode, as in Iris.
- Packs are converted once (pure-Java converter, own GLSL transpiler, SPIR-V) and cached; all pipelines are built at load,
  so there is no shader stutter.
- Errors never crash the game: a failing shader is reported in chat with file and line, a failing frame falls back to
  vanilla rendering.

**Performance**
- Complementary Unbound r5.9.3 + Euphoria Patches at 1080p, render distance 16, RTX 4070 Ti: 132.9 FPS against 75.3 FPS for
  Iris + Sodium on OpenGL (1.76×), with an image within Iris's own run-to-run noise.
- Shadow culling that skips casters whose shadow can't land on anything visible (*Performance…* page).

**Sodium**
- Terrain is meshed and drawn by Sodium 0.9.3, as under Iris.

**Hardware ray tracing (off by default)**
- For Complementary Reimagined / Unbound r5 (including Euphoria Patches), traced with Vulkan ray queries on the RT cores.
- Two styles: *RTX* (default) lets RT light the scene: coloured light through stained glass, block lights with soft traced
  shadows, bounced sun and block light, reflections on polished and metal blocks, stronger light shafts. *Faithful* only
  makes the pack's own effects more accurate.
- Effects, each with its own switch on the **Emberlight RT…** page: shadows (Hybrid / Full, entities cast shadows),
  reflections, refraction and water caustics, ambient occlusion, block-light shadows, global illumination (Overworld).
- With RT off the pack renders exactly as without RT support.

**Upscaling**
- AMD FSR 3.1 (any GPU) and NVIDIA DLSS Super Resolution, presets from Native AA / DLAA to Ultra Performance, switchable
  live on the **Upscaling…** page. They replace the pack's TAA. FSR Quality at 1440p: +34 % FPS.

**Frame generation**
- AMD FSR 3 Frame Generation (any GPU) and NVIDIA DLSS Frame Generation (RTX 40 or newer), with or without upscaling, on the
  **Frame Generation…** page. Pauses itself when the rendered rate already reaches the monitor's refresh rate. The F7
  overlay shows both rates.

**DLSS installation**
- The DLSS bridge library ships in the jar (under its own terms, `LICENSE-DLSS.txt`, not the LGPL). Selecting DLSS the
  first time offers to download NVIDIA's DLSS and DLSS-FG model files (~67 MB) from NVIDIA's GitHub; by downloading you
  accept NVIDIA's RTX SDK licence. Restart the game once. Linux and Windows. `mod/native/build-dlss.sh` remains as the
  manual route.

**Iris API compatibility**
- When Iris is not installed, Emberlight provides Iris's public API (`net.irisshaders.iris.api.v0`, LGPL), so mods such as
  Entity Model Features and Entity Texture Features see when a pack is active and when the shadow pass runs. The `iris` mod
  id is not claimed on purpose.

### Pack compatibility

Tested in game against Iris 1.11.7 + Sodium 0.9.3 on OpenGL (8 fixed scenes, animation time pinned; MAE on 0–255, Iris
against itself scores 0.2–0.9). ✅ matches Iris, 🟡 works with visible differences, ❌ broken.

| Pack | Status | MAE / SSIM vs Iris | FPS, Iris → Emberlight | Notes |
|---|---|---|---|---|
| Complementary Unbound r5.9.3 + Euphoria Patches 1.10.5 | ✅ | 0.29–1.00 / ≥ 0.983 | 75.3 → 132.9 | Reference pack. RT supported |
| Complementary Reimagined r5.9.3 | ✅ | 0.12–1.77 / 0.951–0.999 | 272 → 428* | RT supported |
| BSL v10.1.8 | ✅ | 0.13–1.79 / 0.965–0.999 | 162 → 372* | Distant short grass slightly darker |
| Solas V3.9 | ✅ | 0.31–1.90 / 0.954–0.997 | 157 → 275* | Distant short grass slightly darker |
| Sildur's Vibrant 2.02 Extreme | ✅ | 0.03–2.24 / 0.948–0.999 | 163 → 393* | Distant short grass slightly darker |
| Photon v1.4 | 🟡 | 0.63–9.99 / 0.785–0.989 | 246 → 332* | Cloud amount differs |
| MakeUp UltraFast 9.6 | 🟡 | 0.50–9.77 / 0.821–0.989 | 436 → 556* | Some sunlit surfaces about 10 % darker |
| Bliss v2.1.2 (Chocapic13 edit) | 🟡 | 0.43–2.48 / 0.969–0.996 | – | Sunlit scenes 2–7 % warmer |
| Shrimple | ❌ | – | – | Broken |
| Insanity | ❌ | – | – | Broken |

\* Single preliminary runs, to be re-measured.

### Known issues

- Shrimple and Insanity are broken.
- Distant Horizons is not supported yet. Voxy is not possible (OpenGL only).
- Reflections on water have no motion vectors, so they can smear with FSR/DLSS or frame generation while the camera moves;
  the same goes for moving mobs and the hand.
- Ray tracing needs about 0.85 GB of extra VRAM and is tuned for Complementary r5 only.
- Distant short grass is slightly darker than under Iris. Photon's clouds and MakeUp's sunlit surfaces differ (see table).
- 256× resource packs with PBR need a lot of VRAM, as with Iris (`-Demberlight.pbr=false` turns PBR off).
- A compute pass named like a fullscreen pass (`composite.csh` next to `composite.fsh`) is not supported yet.
- O is also vanilla 26.3's *Friends* key: both fire, rebind one of them.
- Tested on NVIDIA (RTX 4070 Ti) on Linux and Windows 11.

### Upgrading from Emrion Shaders dev builds

Remove the old `emrionshaders-*.jar`. Mod settings start fresh in `config/emberlight.properties` (pack settings are
untouched), packs are reconverted once into `emberlight/cache/` (the old `emrionshaders/` folder can be deleted), key
bindings fall back to their defaults, and `-Demrion.*` system properties keep working next to `-Demberlight.*`.
