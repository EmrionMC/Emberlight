# NeoForge support

Emberlight builds for Fabric and NeoForge (Minecraft 26.3) from one repository, laid out like VoxelMap
(https://github.com/Updated-VoxelMap/VoxelMap): shared code plus one module per loader. Sodium is required on both
loaders; Sodium's Fabric and NeoForge builds contain the same classes, so our Sodium mixins are shared too.

**Status:** first dev-client runs on 2026-11-21 work: Vulkan, Complementary r5.10 + Euphoria converts, RT, DLSS SR and
frame generation through five test scenes, no errors. Better Block Entities on NeoForge is not tested yet.

## Layout

| Module | Gradle path | Build plugin | Contents |
|---|---|---|---|
| `converter/` | `:converter` | plain Java | pack converter, no Minecraft (unchanged) |
| `common/` | `:common` | Fabric Loom (only for the vanilla Minecraft jar) | renderer, RT, upscalers, frame generation, pack runtime, GUI, all mixins and mixin configs, assets, natives, GLSL → SPIR-V tasks, the Iris API copy (`src/irisapi`), all mod tests |
| `mod/` | `:mod` | Fabric Loom | Fabric module: `EmberlightFabric` (entrypoint), `FabricPlatform`, `IrisApiShim` (pre-launch), `fabric.mod.json`, dev runs (`runClient`, `runIrisReference`, autotests, `-P` flags), scene lists, `tools/`, `native/` (DLSS shim sources) |
| `neoforge/` | `:neoforge` | ModDevGradle 2.0.148, NeoForge 26.3.0.115 | `EmberlightNeoForge` (`@Mod`), `NeoForgePlatform`, `META-INF/neoforge.mods.toml`, a dev `runClient`, `tools/MixinAudit.java` |

The Fabric module keeps the old directory and Gradle path (`mod/`, `:mod`) so `gamequeue.sh`, the run dirs
(`mod/run`, `mod/run-iris`), scene lists and every documented `:mod:...` command keep working. Renaming it to `fabric/`
can be done later, once no branch from before the split is still open.

### How the shared code reaches each loader

- `common/` must not use Fabric API or loader classes. What it needs from the loader goes through
  `dev.emrion.shaders.mod.platform.Platform` (game/config dir, `isModLoaded`, mod versions for snapshots, end-of-tick and
  client-tag events). Each loader module provides one implementation via `META-INF/services`; `ServiceLoader` finds it,
  which also works during mixin bootstrap (`SodiumMixinPlugin` asks `isModLoaded("iris")`).
- `EmrionShadersClient` holds the loader-neutral entry logic: `init(registerKey)` (autotest, key mappings, tick handler),
  `command()` (the `/emberlight` command, generic over the command source), `clientStarted(mc)` / `clientStopping()`.
  `LoadingHud.extractRenderState` is the HUD layer. The loader entrypoints only wire these to their events.
- Fabric: `mod/` compiles against `common`'s classes and packs them (`jar { from common.sourceSets.main.output }`);
  `loom.mods` groups both source sets into one mod for dev runs. The Fabric jar is the same as before the split plus
  `EmberlightFabric`, `FabricPlatform`, `Platform` and the service file.
- NeoForge: `neoforge/` compiles `common/src/main/java` again, against NeoForge's patched Minecraft, so a NeoForge API or
  access difference fails the build (this found the `VulkanRenderPipeline` constructor change below). Resources come
  from `common` (minus the Fabric-only `emberlight-irisapi.jar`); the converter is nested with `jarJar`.
- Access: `common/emberlight.accesswidener` (compile only, not in any jar) opens `RenderType.create`, which Fabric API's
  transitive access wideners open at runtime on Fabric and NeoForge's own access transformers open on NeoForge.

### Loader mapping

| What | Fabric (`EmberlightFabric`, `FabricPlatform`) | NeoForge (`EmberlightNeoForge`, `NeoForgePlatform`) |
|---|---|---|
| entrypoint | `ClientModInitializer` | `@Mod(value = "emberlight", dist = Dist.CLIENT)` constructor |
| `-Demberlight.*` → `emrion.*` | pre-launch (`IrisApiShim`) | `NeoForgePlatform` constructor (first `Platform` use, during mixin bootstrap) |
| key mappings | `KeyMappingHelper.registerKeyMapping` | collected, registered in `RegisterKeyMappingsEvent` (category via vanilla `KeyMapping.Category.register`, deprecated on NeoForge but working) |
| client command | `ClientCommandRegistrationCallback` | `RegisterClientCommandsEvent` |
| HUD | `HudElementRegistry.addLast` | `RegisterGuiLayersEvent.registerAboveAll` |
| client start / stop | `ClientLifecycleEvents.CLIENT_STARTED/STOPPING` | `ClientStartedEvent` / `ClientStoppingEvent` |
| end of tick | `ClientTickEvents.END_CLIENT_TICK` | `ClientTickEvent.Post` |
| client tags | `CommonLifecycleEvents.TAGS_LOADED` (client) | `TagsUpdatedEvent.ClientPacketReceived` |
| mod list | `FabricLoader` | `FMLLoader.getCurrent().getLoadingModList()` (complete during mixin bootstrap) |
| Sodium pin | `depends >=0.9.3-beta.5`, `breaks >0.9.3-beta.5` | `versionRange = "[0.9.3-beta.5,0.9.3-beta.6)"` (matches `0.9.3-beta.5+mc26.3` only) |
| Sodium options | `custom."sodium:options"` | `[mods."sodium:options"]` in the mod entry (where Sodium's `ForgeMixinOverrides` reads it) |
| mixin configs | `fabric.mod.json` `mixins` | `[[mixins]]` (same four configs) |
| Iris API copy for other mods | `IrisApiShim` puts `emberlight-irisapi.jar` on the class path | **not provided** (see below) |

## NeoForge differences found so far

Found by compiling against NeoForge and by `neoforge/tools/MixinAudit.java`, which compares all 193 injection points of
our mixins (Minecraft, Sodium, BBE) between vanilla/Fabric jars and NeoForge's:

- `VulkanRenderPipeline`: NeoForge adds stencil variants (constructor takes `withStencilPipeline`,
  `withDepthStencilPipeline` first; `VulkanRenderPass.setPipeline` binds them and returns early when the pass has a
  stencil attachment). `GeometryPipelines.wrap` looks the constructor up once (vanilla, else NeoForge's with 0 for both
  stencil pipelines), and `VulkanRenderPassMixin` injects at every `RETURN` of `setPipeline`. Limit: our wrapped
  pipelines have no stencil variant, so a pass with a stencil attachment would bind none. NeoForge only adds stencil to
  render targets when a mod asks for it.
- `VulkanPipelineCacheMixin`: NeoForge creates four pipelines per vanilla pipeline (two stencil variants); the
  `@ModifyArg` applies to all four calls, so all use our persistent `VkPipelineCache`. Pipeline compile time on NeoForge
  is higher than on Fabric (NeoForge's own cost).
- `TextureAtlasMixin.cycleAnimationFrames` `TAIL`: NeoForge's version has one return instead of an early return, so our
  hook also runs while the atlas has no texture yet. `PbrAtlases.onAtlasTick` is harmless then.
- Sodium and BBE: every target class and injection point matches between their Fabric and NeoForge builds.

Re-run the audit after any NeoForge, Sodium or BBE update (usage in the file header). It does not compare
`CONSTANT`/`LOAD`/`STORE` points or `@Local` captures; those in NeoForge-patched classes (`Gui`, `GameRenderer`,
`LevelRenderer`, `Minecraft`, `SubmitNodeCollection`, `VulkanGpuTexture`) were checked by hand against NeoForge's patches.

## Build and run

```sh
./gradlew build                      # everything: converter + common tests, Fabric and NeoForge jars
./gradlew :mod:build                 # Fabric only: mod/build/libs/emberlight-<version>.jar
./gradlew :neoforge:build            # NeoForge only: neoforge/build/libs/emberlight-neoforge-<version>.jar
./gradlew :common:test               # the mod's unit tests (moved from :mod:test)
```

Fabric dev runs are unchanged (`./gradlew :mod:runClient -PirisModsDir=... -Pserver=... -Pbbe -Pcompare`, gamequeue.sh).

NeoForge dev run (untested):

```sh
./gradlew :neoforge:runClient -PneoforgeMods=<dir> [-Pserver=host:port] [-Pbbe] [-Demberlight.upscale.dlssDir=<dir>] ...
```

- `<dir>` (default `neoforge/run-mods/`, untracked) must contain exactly one `sodium-neoforge-*.jar`, plus
  `bbe-neoforge-*.jar` for `-Pbbe`; they are copied into `neoforge/run/mods/`. Download only from Modrinth, e.g.
  `sodium-neoforge-0.9.3-beta.5+mc26.3.jar` and `bbe-neoforge-1.4.1+mc26.3.jar`.
- Run dir: `neoforge/run/`. `--graphicsBackend vulkan` is passed (`-PgraphicsBackend=opengl` to check the inert path).
- `-Pserver` sets `emrion.autotest.server` and `--username EmbTest` (`-PtestUsername`), as on Fabric.
- Every `-Demberlight.*` / `-Demrion.*` goes to the game as `emrion.*`; `emrion.autotest.scenes` resolves against `mod/`.
- Not ported from the Fabric run: `-Pcompare` (pack/settings linking, comparison world, fixed options),
  `-PirisModsDir`, `-PwinWidth/-PwinHeight`, the shared pipeline cache dir, resource-pack linking, `runIrisReference`.
  Put packs into `neoforge/run/shaderpacks/` and pick them in game, or pass `-Demrion.pack=<name>`.

## Untested / stubbed

- Nothing has run on NeoForge: mod loading, mixin application, Vulkan activation, pack loading, RT, DLSS/FSR/XeSS,
  frame generation, Reflex, HDR, photo mode, snapshots, autotests.
- Iris API copy: not provided on NeoForge. Mods that look for `net.irisshaders.iris.api` without Iris installed see no
  shaders. Fabric's way (adding a jar to the class path at pre-launch) has no NeoForge equivalent that would not clash
  with a real Iris; it needs its own design (e.g. a separate library jar) if any NeoForge mod needs it.
- Iris on NeoForge: `emrionshaders.iris.mixins.json` targets the same Iris class as on Fabric; not checked against
  Iris's NeoForge build.
- Converter as a `jarJar` library (no `neoforge.mods.toml`): expected to load as a plain library; untested.
- Key category: registered with vanilla's deprecated `KeyMapping.Category.register`; NeoForge's controls screen with it
  is untested.
- `pack.mcmeta`: none (as on Fabric); NeoForge should generate the mod resource pack metadata itself.
- Stencil passes (see above).

## In-game test plan (NeoForge)

One game at a time, through the test queue: `gamequeue.sh` only knows `:mod:runClient` and needs a NeoForge mode
(run `:neoforge:runClient` with the same display, server and screenshot handling) before these runs.

1. Build: `./gradlew :neoforge:build`. Put `sodium-neoforge-0.9.3-beta.5+mc26.3.jar` (and later
   `bbe-neoforge-1.4.1+mc26.3.jar`) into `neoforge/run-mods/`. Link `packs/emberlight-rtx` into `neoforge/run/shaderpacks/`
   and copy `mod/run/config/sodium-options.json` / `options.txt` settings if wanted.
2. Start: `./gradlew :neoforge:runClient -Pserver=<test server>:25570 -Demrion.pack="Emberlight RTX (Preview)"
   -Demrion.upscale.dlssDir=<dir with the DLSS runtime> -Demrion.autotest.script="wait 20;screenshot nf-boot;quit"`.
   Check in `neoforge/run/logs/latest.log`: no mixin errors (`Mixin apply failed`, `InvalidInjectionException`), no
   `Sodium` option override warning for emberlight, `Emberlight active on Vulkan`, pack loaded, `Providing the Iris API`
   absent (expected on NeoForge).
3. Same with RT on and DLSS (SR, then frame generation + Reflex): compare `nf-*` screenshots with a Fabric run of the
   same script and position (Fabric via gamequeue). Watch FPS against Fabric; expect a slower first pipeline build
   (four pipelines per vanilla pipeline on NeoForge).
4. Keys: open Controls, find the Emberlight category, press O (pack screen), K (toggle), R (reload), F9 (snapshot:
   check `snapshot.json` lists NeoForge mods). `/emberlight snapshot test` and `/emberlight photo`.
5. `-Pbbe`: storage room scene with Better Block Entities (chests baked by BBE, side-face light).
6. Complementary r5.10 + Euphoria via the pack screen: converts with `unavailable {}`, RT effects as on Fabric.
7. Quit cleanly (no Vulkan validation leak beyond the two known ones), restart: warm pipeline cache.
8. OpenGL: `-PgraphicsBackend=opengl` shows the "needs Vulkan" toast and stays inert.
