package dev.emrion.shaders.mod.autotest;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.gui.ShaderOptionsScreen;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.jspecify.annotations.Nullable;

/**
 * Dev-only, unattended visual checks driven by system properties (all optional except {@code world}):
 * <pre>
 * emrion.autotest.world=name        create (fixed seed, default world type) or load this singleplayer world
 * emrion.autotest.server=host:port  join this server instead (the local test server, docs/BENCHMARK.md); commands go through
 *                                  chat as the (op) player, @a becomes @s, and nothing is killed
 * emrion.autotest.seed=2026
 * emrion.autotest.pos=x,y,z,yaw,pitch[;x,y,z,yaw,pitch...]   one screenshot per position
 * emrion.autotest.time=6000         emrion.autotest.wait=8 (seconds, plus until chunk builds are done)
 * emrion.autotest.reload=N          after joining, reload the resource packs N times and log each duration
 * emrion.autotest.resourcepacks=/abs/pack.zip[;...]   (Gradle runClient and runIrisReference) symlink + enable these resource packs
 * emrion.autotest.tag=name          screenshots: run/screenshots/emrion-&lt;tag&gt;[-i].png
 * emrion.autotest.commands=cmd1;cmd2   extra commands (no slash) run at every position, after the teleport (once the chunks are loaded)
 * emrion.autotest.scenes=file      scene list instead of pos/time/commands (format: mod/emrion-scenes.txt);
 *                                  screenshots: run/screenshots/&lt;tag&gt;-&lt;scene&gt;.png
 * emrion.autotest.only=a,b         only these scenes of the list (e.g. the benchmark scene)
 * emrion.autotest.settle=5         seconds to wait after chunks are built (temporal effects), and at least
 * emrion.autotest.settle.frames=200 frames; with fast=true (default) only the upper bound
 * emrion.autotest.settle.exactFrames=N   scenes: shoot exactly N rendered frames after the chunks are ready and the weather has
 *                                  finished fading (it moves on game ticks), no convergence
 *                                  check and no timeout, so temporal effects (TAA, bloom feedback, auto exposure) get the same
 *                                  number of frames in every renderer; shots=3 shotGap=G adds &lt;scene&gt;-s2/-s3 G frames apart
 * emrion.autotest.settle.weatherFrames=6000   exactFrames for a scene whose weather differs from the previous scene's: packs
 *                                  smooth wetness/biome uniforms over tens of seconds of frameTime, and the frames rendered
 *                                  while chunks load differ between renderers, so a short count shoots different wetness
 * emrion.autotest.fast=true         scenes: shoot as soon as the chunks around the camera are loaded and built and the image has
 *                                  converged (Convergence), instead of the fixed 8 s chunk wait(s) and 5 s settle; false = fixed waits
 * emrion.autotest.converge.threshold=0.4   mean abs RGB difference (0-255, 1/8 resolution) that counts as converged
 * emrion.autotest.fastwait=true     script "wait N" (N &gt;= 1) also ends once settled as "settle" does (not for timed motion)
 * emrion.autotest.bench.seconds=S   overrides the window of every bench/benchv step and of benchmark.seconds
 * emrion.autotest.configs=NAME:step[;step]|NAME:...   render all scenes once per configuration (script steps, e.g.
 *                                  off:rtset enabled=false|gi:rtset enabled=true,gi=medium); shots &lt;tag&gt;-&lt;scene&gt;-&lt;NAME&gt;.png
 * emrion.autotest.benchmark.seconds=N   frame-time capture after the screenshots (at the last position)
 * emrion.autotest.benchmark.perScene=true   capture after every scene's screenshot instead (&lt;tag&gt;-&lt;scene&gt;.csv)
 * emrion.autotest.quit=true         leave the world (saving it) and exit
 * emrion.autotest.hud=true          keep the HUD (and with it the first-person hand) visible
 * emrion.autotest.optionScreens=,A   after the shots: open the shader options screen ("" = main, else screen.A) and take
 *                                  &lt;tag&gt;-options[-A].png of each
 * emrion.autotest.set=NAME=value[;NAME=value]   then apply these option values like the GUI's Apply (settings file +
 *                                  reconvert + swap), wait for the swap and take &lt;tag&gt;-set.png
 * emrion.autotest.hotreload=N      then reload the pack N times (key R), each waiting for the swap; logs GPU memory (VMA)
 * emrion.autotest.script=step;step  then run these steps (robustness tests), one after the other:
 *     wait S | size WxH | fullscreen on|off | gui N | cmd COMMAND | shot NAME (&lt;tag&gt;-NAME.png) | mem (log FPS and VMA budget)
 *     pack off|on|NAME (on/NAME wait until the pack renders) | settle [MAX_S] (chunks built + image converged, default max 30) | reload (waits for the swap) | resources (F3+T) | leave | join | hud | f3 on|off
 *     rt off|on|hybrid|full[+refl+ent+light+ao+caust+gi|gilow|gihigh] (Emberlight RT page Apply, waits for the swap) | rtset KEY=V[,KEY=V] (preset=low|medium|high|ultra first, then rt.KEY of
 *     emberlight.properties, e.g. gi=medium,ao=false; -Demrion.rt.KEY still wins; Apply, waits for the swap) | upscale off|fsr|dlss[:preset] (Upscaling page) |
 *     framegen off|fsr|dlss | hdr on|off | reflex off|on|boost|fgoff | move YAW/s DX/s DZ/s SECONDS (per-frame camera motion, continues during later steps) | vsync on|off |
 *     turnshot DEG_PER_FRAME FRAMES NAME [DX DZ [T]] (frame-exact turn and move, shot of its last frame) | fgturn (the same, captures the
 *     interpolated frame in front of the last one) | bob WALK AMOUNT|off (view bobbing on, at a fixed walk distance and bob amount) | hurt TIME DIR|off | nausea INTENSITY ANGLE|off (frozen damage tilt / nausea skew) | mats NAME (logs gbufferModelView/Projection, Iris's when it renders) |
 *     bench|benchv SECONDS NAME (rendered + presented frame times; benchv keeps VSync/cap) | trace start | trace stop NAME (every frame time in between -> emrion-trace-NAME.csv) | fgshots N NAME (the next N presented frame pairs as PNGs) | frames SECONDS NAME (every frame for SECONDS, quarter size, -> &lt;tag&gt;-NAME-NNN-MSms.png; runs on during later steps) | screen pause|inventory|chat|packs|rt|upscale|framegen|perf|dlss|options[:ID]|none | key KEY [ctrl] (into the open screen) | drop PATH[;PATH] (onto the open screen) | use down|up (hold the use key) | press KEY (key.keyboard.KEY) | mdi on|off (terrain multi-draw indirect, F3+M) | irisapi (logs what the Iris API reports) | repeat N { steps } (no nesting) |
 *     photo SCALE SAMPLES NAME [rt+up|rt|up|none] (photo mode, &lt;tag&gt;-NAME.png; which histories reset per frame, sticky) |
 *     snapshot FILE (replays a debug snapshot.json: pose, dimension, time, weather, effects, items, options, pack settings and
 *     Emberlight config, then shoots &lt;tag&gt;-snapshot-&lt;folder&gt;.png; without world/server it picks the snapshot's own)
 * </pre>
 * With only {@code quit=true} the game exits as soon as the title screen is up (smoke test).
 */
public final class AutoTest {
	private static final String P = "emrion.autotest.";
	private static final int TPS = 20;
	/** -Demrion.autotest.profile=true: the Profiler accumulates over the benchmark window and logs it (Vulkan only). */
	public static final boolean PROFILE = Boolean.getBoolean(P + "profile");

	private static final boolean FAST = !"false".equals(System.getProperty(P + "fast"));
	private static final boolean FAST_WAIT = Boolean.getBoolean(P + "fastwait");
	private static final String BENCH_SECONDS = System.getProperty(P + "bench.seconds");
	/** Fast mode: at least this long after a teleport or the scene commands (the server applies them a tick or two later). */
	private static final int MIN_TICKS = 10;

	private enum State { WAIT_TITLE, WAIT_WORLD, RELOAD, CONFIG, SETUP, WAIT_CHUNKS, SETTLE, SHOT, BENCHMARK, OPTIONS, SET, HOT_RELOAD, SCRIPT, LEAVE, QUIT, DONE }

	private @Nullable String world = System.getProperty(P + "world");
	private @Nullable String server = System.getProperty(P + "server");
	private long seed = Long.getLong(P + "seed", 2026L);
	private final int time = Integer.getInteger(P + "time", 6000);
	private final int waitTicks = Integer.getInteger(P + "wait", 8) * TPS;
	private final String tag = System.getProperty(P + "tag", "shot");
	private final int benchmarkSeconds = BENCH_SECONDS != null && Integer.getInteger(P + "benchmark.seconds", 0) > 0 ? (int) Double.parseDouble(BENCH_SECONDS)
			: Integer.getInteger(P + "benchmark.seconds", 0);
	private final boolean benchPerScene = Boolean.getBoolean(P + "benchmark.perScene");
	private final boolean quit = Boolean.getBoolean(P + "quit");
	private final int settleTicks = Integer.getInteger(P + "settle", 5) * TPS;
	private final int settleFrames = Integer.getInteger(P + "settle.frames", 200);
	private final List<Scene> scenes = new ArrayList<>();
	private final @Nullable List<String> optionScreens = System.getProperty(P + "optionScreens") == null ? null
			: Arrays.asList(System.getProperty(P + "optionScreens").split(",", -1));
	private final String set = System.getProperty(P + "set", "");
	private int hotReloads = Integer.getInteger(P + "hotreload", 0);
	private int optionShot, swapsBefore = -1, swapTick;
	private final List<String> script = expand(splitCommands(System.getProperty(P + "script", "")));
	private int step;
	private double @Nullable [] motion; // script step 'motion': dx dy dz dyaw dpitch per tick
	private @Nullable String scriptPack = System.getProperty("emrion.pack");
	private final Convergence convergence = new Convergence();
	record Config(String name, List<String> steps) {}
	private final List<Config> configs = parseConfigs(System.getProperty(P + "configs", ""));
	private int config, configStep;
	private boolean benched;
	/** Where the time goes: phase -> nanoseconds, logged at the end. */
	private final java.util.Map<String, Long> timing = new java.util.LinkedHashMap<>();
	private String phase = "start (mod init to title screen)";
	private long phaseStart = System.nanoTime(), sceneStart;
	private static final java.util.concurrent.atomic.AtomicInteger pendingShots = new java.util.concurrent.atomic.AtomicInteger();

	/** {@code name == null}: legacy pos mode (emrion-&lt;tag&gt;[-i].png). {@code pos == null}: wherever the player is. */
	record Scene(String name, String pos, int time, String weather, List<String> commands) {}
	private int reloads = Integer.getInteger(P + "reload", 0);
	private @Nullable CompletableFuture<Void> reload;
	private long reloadStart;

	private State state = State.WAIT_TITLE;
	private int ticks;
	private long chunksWaitStart; // time from teleport until all sections are built (meshing cost)
	private boolean remeshDone = !Boolean.getBoolean(P + "remesh"), sawUnbuilt;
	private int shot;
	private boolean vsync;
	private int fpsLimit;
	private long settleStartFrame;
	private static final int EXACT_FRAMES = Integer.getInteger(P + "settle.exactFrames", 0);
	private static final int WEATHER_FRAMES = Integer.getInteger(P + "settle.weatherFrames", 6000);
	private static final int SHOTS = Math.max(1, Integer.getInteger(P + "shots", 1)), SHOT_GAP = Math.max(1, Integer.getInteger(P + "shotGap", 8));
	/** Exact-frame shots: the frame count to shoot at, the shot index (-1 = idle) and the base name; driven by {@link #onFrame}. */
	private static long exactAt;
	private static int exactShot = -1;
	private static String exactName;
	private boolean commandsRun;

	private static volatile float motionYaw, motionDx, motionDz;
	private static volatile long motionUntil, motionLast;
	private static volatile int turnFrames;
	private static volatile float turnDeg, turnPitch, turnDx, turnDz;
	private static volatile @org.jspecify.annotations.Nullable String turnName;
	private static volatile boolean turnFg;
	/** The "bob" step's fixed view-bob phase (walk distance) and amount; NaN = MC's own. Read by GameRendererProjectionMixin. */
	public static volatile float bobWalk = Float.NaN, bobAmount;
	/** The "hurt" and "nausea" steps' fixed hurt tilt (hurt time of 10, direction) and nausea skew (intensity, angle); NaN = MC's own. */
	public static volatile float hurtTime = Float.NaN, hurtDir, nausea = Float.NaN, nauseaAngle;

	/** The "frames" step: shoot every frame until this time (nanoTime), as framesName-NNN-MSms.png. */
	private static volatile long framesStart, framesUntil;
	private static volatile @org.jspecify.annotations.Nullable String framesName;
	private static int framesIndex;

	/** The "frames" step's downscale: 4, else the largest of 2 and 1 that divides the target (Screenshot.grab needs both sides divisible). */
	private static int framesScale(com.mojang.blaze3d.pipeline.RenderTarget t) {
		for (int f = 4; f > 1; f /= 2) if (t.width % f == 0 && t.height % f == 0) return f;
		return 1;
	}

	/** Every frame (GameRenderer.render HEAD): the "move" step's camera motion, applied per frame so it is smooth. */
	public static void onFrame() {
		String fn = framesName;
		if (fn != null) { // the main target still holds the previous frame as presented (GUI included)
			long now = System.nanoTime();
			if (now > framesUntil) framesName = null;
			else {
				pendingShots.incrementAndGet();
				Screenshot.grab(Minecraft.getInstance().gameDirectory, String.format("%s-%03d-%05dms.png", fn, framesIndex++, (now - framesStart) / 1_000_000),
						Minecraft.getInstance().gameRenderer.mainRenderTarget(), framesScale(Minecraft.getInstance().gameRenderer.mainRenderTarget()), msg -> pendingShots.decrementAndGet());
			}
		}
		if (exactShot >= 0 && FrameTimeLogger.INSTANCE.frames() >= exactAt) { // the target still holds the previous, complete frame
			String n = exactShot == 0 ? exactName : exactName.replaceFirst("\\.png$", "-s" + (exactShot + 1) + ".png");
			shoot(Minecraft.getInstance(), n);
			exactShot = exactShot + 1 < SHOTS ? exactShot + 1 : -1;
			exactAt += SHOT_GAP;
		}
		if (turnName != null) { // "turnshot": a frame-exact turn, then the frame that arrived at the end
			var p = Minecraft.getInstance().player;
			if (turnFrames > 0 && p != null) {
				float yaw = p.getYRot() + turnDeg;
				p.setYRot(yaw);
				p.yRotO = yaw;
				float pitch = p.getXRot() + turnPitch;
				p.setXRot(pitch);
				p.xRotO = pitch;
				double x = p.getX() + turnDx, z = p.getZ() + turnDz;
				p.setPos(x, p.getY(), z);
				p.xo = p.xOld = x;
				p.zo = p.zOld = z;
				turnFrames--;
			} else if (turnFrames-- == 0) { // the pose set at a frame's start renders in the next frame
				if (turnFg) { // fgturn: this frame shows the last pose; capture it and the interpolated frame in front of it
					var dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
					dev.emrion.shaders.mod.framegen.FrameCapture.request(1, dir, turnName);
					dev.emrion.shaders.mod.upscale.MotionCapture.request(dir, turnName); // and the motion vectors it was made with
					turnName = null;
				} // MC's target lags one frame behind: shoot at the next one
			} else { // the target now holds the last turning frame
				if (p != null) EmrionShadersClient.LOG.info("Autotest: turnshot {} at {} {} {} / {} {}", turnName, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
				shoot(Minecraft.getInstance(), turnName);
				turnName = null;
			}
		}
		long now = System.nanoTime();
		if (now > motionUntil) return;
		var player = Minecraft.getInstance().player;
		long last = motionLast;
		motionLast = now;
		if (player == null || last == 0) return;
		float dt = (now - last) / 1e9f;
		float yaw = player.getYRot() + motionYaw * dt;
		player.setYRot(yaw);
		player.yRotO = yaw;
		double x = player.getX() + motionDx * dt, z = player.getZ() + motionDz * dt;
		player.setPos(x, player.getY(), z);
		player.xo = x;
		player.zo = z;
		player.xOld = x;
		player.zOld = z;
	}

	private AutoTest() {
		pickSnapshotWorld();
		String file = System.getProperty(P + "scenes");
		if (file != null) {
			try {
				List<String> only = splitCommands(System.getProperty(P + "only", "").replace(',', ';'));
				parseScenes(Files.readAllLines(Path.of(file))).stream().filter(s -> only.isEmpty() || only.contains(s.name())).forEach(scenes::add);
			} catch (IOException e) {
				throw new IllegalStateException("autotest: cannot read scene list " + file, e);
			}
			if (scenes.isEmpty()) throw new IllegalStateException("autotest: no scenes in " + file);
			return;
		}
		List<String> commands = splitCommands(System.getProperty(P + "commands", ""));
		String pos = System.getProperty(P + "pos");
		if (pos != null) for (String p : pos.split(";")) scenes.add(new Scene(null, p.trim().replace(',', ' '), time, "clear", commands));
		if (scenes.isEmpty()) scenes.add(new Scene(null, null, time, "clear", commands)); // one shot wherever the player spawned
	}

	/** Exact-frame count for the current scene; a weather change gets {@link #WEATHER_FRAMES} so the packs' wetness smoothing converges. */
	private int exactFrames() {
		String prev = shot > 0 ? scenes.get(shot - 1).weather() : "clear";
		return scenes.get(shot).weather().equals(prev) ? EXACT_FRAMES : Math.max(EXACT_FRAMES, WEATHER_FRAMES);
	}

	/** One scene per line: {@code name | x y z yaw pitch | time | clear|rain|thunder | cmd; cmd; ...}; '#' starts a comment. */
	static List<Scene> parseScenes(List<String> lines) {
		List<Scene> out = new ArrayList<>();
		for (String line : lines) {
			line = line.strip();
			if (line.isEmpty() || line.startsWith("#")) continue;
			String[] f = line.split("\\|", -1);
			if (f.length < 4) throw new IllegalArgumentException("scene needs at least name|pos|time|weather: " + line);
			out.add(new Scene(f[0].strip(), f[1].isBlank() ? null : f[1].strip().replace(',', ' '), Integer.parseInt(f[2].strip()), f[3].strip(),
					f.length > 4 ? splitCommands(f[4]) : List.of()));
		}
		return out;
	}

	/** {@code name:step;step|name:step}; a config without a name is called c1, c2, ... */
	static List<Config> parseConfigs(String s) {
		List<Config> out = new ArrayList<>();
		for (String c : s.split("\\|")) {
			if (c.isBlank()) continue;
			int i = c.indexOf(':');
			String name = i > 0 && !c.substring(0, i).contains(" ") ? c.substring(0, i).strip() : "c" + (out.size() + 1);
			out.add(new Config(name, expand(splitCommands(i > 0 && !c.substring(0, i).contains(" ") ? c.substring(i + 1) : c))));
		}
		return out;
	}

	private static List<String> splitCommands(String s) {
		return Arrays.stream(s.split(";")).map(String::strip).map(c -> c.startsWith("/") ? c.substring(1) : c).filter(c -> !c.isEmpty()).toList();
	}

	/** An autotest runs: first-start defaults (RT preset pick) stay off so runs keep the plain defaults. */
	public static boolean ACTIVE;

	public static void registerIfEnabled() {
		if (System.getProperty(P + "world") == null && System.getProperty(P + "server") == null && !Boolean.getBoolean(P + "quit")
				&& !System.getProperty(P + "script", "").contains("snapshot ")) return;
		ACTIVE = true;
		AutoTest test = new AutoTest();
		ClientTickEvents.END_CLIENT_TICK.register(test::tick);
		EmrionShadersClient.LOG.info("Autotest enabled: world={} server={} scenes={} tag={}", test.world, test.server, test.scenes, test.tag);
	}

	private void tick(Minecraft mc) {
		ticks++;
		if (motion != null && mc.player != null) { // at the end of the tick: the next frames interpolate old -> new
			var pl = mc.player;
			pl.setPos(pl.getX() + motion[0], pl.getY() + motion[1], pl.getZ() + motion[2]);
			pl.setYRot(pl.getYRot() + (float) motion[3]);
			pl.setXRot(pl.getXRot() + (float) motion[4]);
		}
		switch (state) {
			case WAIT_TITLE -> {
				if (mc.gui.overlay() != null || mc.gui.screen() == null || ticks < (FAST ? 2 : TPS)) return;
				// "afk" (the default) caps an unattended game at 30 FPS after a minute without input
				mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
				if (world == null && server == null) {
					go(State.QUIT);
				} else {
					openOrCreate(mc);
					go(State.WAIT_WORLD);
				}
			}
			case WAIT_WORLD -> {
				// with -Demrion.pack, also wait for the pack's pipelines (a cold pipeline cache under load takes > 1 min)
				boolean packReady = scriptPack == null || PackManager.running() || PackManager.selected() == null;
				if (mc.level != null && mc.player != null && mc.gui.screen() == null && packReady) go(reloads > 0 ? State.RELOAD : firstScene());
			}
			case RELOAD -> {
				if (reload == null) {
					reloadStart = System.nanoTime();
					reload = mc.reloadResourcePacks();
				} else if (reload.isDone() && mc.gui.overlay() == null) {
					EmrionShadersClient.LOG.info("Autotest: resource reload took {} ms", (System.nanoTime() - reloadStart) / 1_000_000);
					reload = null;
					go(--reloads > 0 ? State.RELOAD : firstScene());
				}
			}
			case CONFIG -> {
				Config c = configs.get(config);
				if (configStep >= c.steps().size()) {
					configStep = 0;
					shot = 0;
					go(State.SETUP);
					return;
				}
				String st = c.steps().get(configStep);
				if (ticks == 1) {
					phase = "config step " + st.split(" ", 2)[0];
					EmrionShadersClient.LOG.info("Autotest config {} [{}/{}]: {}", c.name(), configStep + 1, c.steps().size(), st);
				}
				if (scriptStep(mc, st)) {
					configStep++;
					go(State.CONFIG);
				} else if (ticks > 300 * TPS) throw new IllegalStateException("autotest: config step never finished: " + st);
			}
			case SETUP -> {
				sceneStart = System.nanoTime();
				if (!mc.gui.hud.isHidden() && !Boolean.getBoolean(P + "hud")) mc.gui.hud.toggle(); // hud=true keeps it (and the hand)
				Scene scene = scenes.get(shot);
				command(mc, "gamemode spectator @a");
				command(mc, "gamerule advance_time false");
				command(mc, "gamerule advance_weather false");
				command(mc, "time set " + scene.time());
				command(mc, "weather " + scene.weather());
				// teleport first: the scene commands (summon, setblock) fail silently in chunks that are not loaded yet
				if (scene.pos() != null) command(mc, "tp @a " + scene.pos());
				commandsRun = scene.commands().isEmpty();
				chunksWaitStart = System.nanoTime();
				sawUnbuilt = false;
				go(State.WAIT_CHUNKS);
			}
			case WAIT_CHUNKS -> {
				boolean built = mc.levelRenderer.hasRenderedAllSections();
				sawUnbuilt |= !built;
				if (built && sawUnbuilt && chunksWaitStart != 0) {
					EmrionShadersClient.LOG.info("Autotest: all sections built {} ms after the {}{}", (System.nanoTime() - chunksWaitStart) / 1_000_000,
							remeshDone ? "remesh" : "teleport", sodiumBackend());
					chunksWaitStart = 0;
				}
				boolean ready = FAST ? ticks >= MIN_TICKS && built && chunksLoaded(mc) && atScenePos(mc, scenes.get(shot)) : ticks >= waitTicks && built;
				if (ready || ticks >= 4 * waitTicks) {
					if (!commandsRun) { // chunks are loaded now; run the commands, then wait again for the rebuilt sections
						scenes.get(shot).commands().forEach(c -> command(mc, c));
						commandsRun = true;
						go(State.WAIT_CHUNKS);
						return;
					}
					if (!built) EmrionShadersClient.LOG.warn("Autotest: chunk builds still pending, taking the screenshot anyway");
					if (!remeshDone) { // -Demrion.autotest.remesh=true: drop all meshes once and time the full rebuild (F3+A)
						remeshDone = true;
						sawUnbuilt = false;
						if (mc.levelExtractor != null) mc.levelExtractor.allChanged();
						chunksWaitStart = System.nanoTime();
						go(State.WAIT_CHUNKS);
						return;
					}
					// worldgen animals in the freshly loaded chunks would wander differently in every run; scenes tag their own mobs
					if (server == null) command(mc, "kill @e[type=!minecraft:player,tag=!emrion]"); // never on the shared server
					settleStartFrame = FrameTimeLogger.INSTANCE.frames();
					convergence.reset();
					go(State.SETTLE);
				}
			}
			case SETTLE -> {
				// a stray F1/F3/Esc on the visible window must not end up in the screenshot
				if (!mc.gui.hud.isHidden() && !Boolean.getBoolean(P + "hud")) mc.gui.hud.toggle(); // hud=true keeps it (and the hand)
				mc.debugEntries.setOverlayVisible(false);
				if (mc.gui.screen() != null) mc.gui.setScreen(null);
				if (EXACT_FRAMES > 0) { // onFrame shoots; SHOT only waits for it
					// weather fades over game ticks, not frames: count from the moment rain and thunder have reached their target
					float rain = mc.level.getRainLevel(1f), thunder = mc.level.getThunderLevel(1f);
					boolean weatherSettled = (rain == 0f || rain == 1f) && (thunder == 0f || thunder == 1f);
					boolean packReady = PackManager.selected() == null || PackManager.running(); // a pack switch still building renders vanilla
					if (exactShot < 0 && exactName == null && weatherSettled && packReady) {
						exactName = shotName();
						exactAt = FrameTimeLogger.INSTANCE.frames() + exactFrames();
						exactShot = 0;
					}
					if (exactName != null && exactShot < 0) {
						exactName = null;
						go(State.SHOT);
					}
					return;
				}
				boolean timeout = ticks >= settleTicks && FrameTimeLogger.INSTANCE.frames() - settleStartFrame >= settleFrames;
				// the kill above needs a tick or two on the server; MIN_TICKS covers it
				if (FAST && ticks >= MIN_TICKS && settled(mc) || timeout) {
					if (FAST) EmrionShadersClient.LOG.info("Autotest: scene {} {} after {} frames (diff {}), {} ms after the teleport", scenes.get(shot).name(),
							timeout ? "NOT converged, settle timeout" : "converged", FrameTimeLogger.INSTANCE.frames() - settleStartFrame,
							String.format("%.3f", convergence.lastDiff), (System.nanoTime() - sceneStart) / 1_000_000);
					go(State.SHOT);
				}
			}
			case SHOT -> {
				String name = shotName();
				var cam = mc.gameRenderer.mainCamera();
				EmrionShadersClient.LOG.info("Autotest: {} from player {} {} {} / {} {}, camera {}{}", name, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
						mc.player.getYRot(), mc.player.getXRot(), cam.position(), EXACT_FRAMES > 0 ? " (" + exactFrames() + " frames after the chunks)" : "");
				if (EXACT_FRAMES == 0) shoot(mc, name);
				shot++;
				if (benchPerScene && benchmarkSeconds > 0) go(State.BENCHMARK);
				else if (shot < scenes.size()) go(State.SETUP);
				else afterScenes();
			}
			case BENCHMARK -> {
				if (ticks == 1) { // uncapped for the measurement, restored afterwards; 1 s warm-up
					vsync = mc.options.enableVsync().get();
					fpsLimit = mc.options.framerateLimit().get();
					mc.options.enableVsync().set(false);
					mc.options.framerateLimit().set(260);
				}
				if (ticks == TPS) {
					FrameTimeLogger.INSTANCE.start();
					if (PROFILE) { if (EmrionShadersClient.active()) Profiler.benchmarkStart(); else GlGpuTimer.reset(); }
				}
				if (ticks >= (benchmarkSeconds + 1) * TPS) {
					String scene = benchPerScene ? "-" + scenes.get(shot - 1).name() : "";
					if (benchPerScene) EmrionShadersClient.LOG.info("Autotest: benchmark scene {}", scenes.get(shot - 1).name());
					FrameTimeLogger.INSTANCE.stopAndDump(mc.gameDirectory.toPath().resolve("emrion-benchmark-" + tag
							+ (configs.isEmpty() ? "" : "-" + configs.get(config).name()) + scene + ".csv"));
					if (PROFILE) { if (EmrionShadersClient.active()) Profiler.benchmarkEnd(); else GlGpuTimer.log(); }
					mc.options.enableVsync().set(vsync);
					mc.options.framerateLimit().set(fpsLimit);
					if (benchPerScene && shot < scenes.size()) go(State.SETUP);
					else {
						benched |= benchPerScene;
						afterScenes();
					}
				}
			}
			case OPTIONS -> {
				if (optionScreens == null || optionShot >= optionScreens.size()) {
					if (mc.gui.screen() != null) mc.gui.setScreen(null);
					go(State.SET);
					return;
				}
				String screen = optionScreens.get(optionShot);
				if (ticks == 1) {
					var session = PackManager.optionSession();
					if (session == null) throw new IllegalStateException("autotest: no pack loaded for the options screen");
					mc.gui.setScreen(ShaderOptionsScreen.open(session, screen));
				}
				if (ticks == 2 * TPS) {
					shoot(mc, tag + "-options" + (screen.isEmpty() ? "" : "-" + screen) + ".png");
					optionShot++;
					go(State.OPTIONS);
				}
			}
			case SET -> {
				if (set.isBlank()) {
					go(State.HOT_RELOAD);
					return;
				}
				if (swapsBefore < 0) {
					var session = PackManager.optionSession();
					if (session == null) throw new IllegalStateException("autotest: no pack loaded");
					for (String kv : set.split(";")) session.pending.put(kv.substring(0, kv.indexOf('=')).strip(), kv.substring(kv.indexOf('=') + 1).strip());
					swapsBefore = PackManager.swaps;
					swapTick = -1;
					reloadStart = System.nanoTime();
					EmrionShadersClient.LOG.info("Autotest: applying {}", set);
					switch (PackManager.applyOptions(session.pending)) {
						case FAILED -> throw new IllegalStateException("autotest: could not apply " + set);
						case UNCHANGED -> swapTick = ticks; // already in effect: no reload, no swap to wait for
						case RELOADING -> {}
					}
					return;
				}
				if (swapTick < 0 && PackManager.swaps == swapsBefore) {
					if (ticks > 300 * TPS) throw new IllegalStateException("autotest: no swap after applying " + set);
					return;
				}
				if (swapTick < 0) {
					EmrionShadersClient.LOG.info("Autotest: option change applied and swapped in after {} ms", (System.nanoTime() - reloadStart) / 1_000_000);
					swapTick = ticks;
				}
				if (ticks - swapTick < settleTicks) return;
				shoot(mc, tag + "-set.png");
				swapsBefore = -1;
				go(State.HOT_RELOAD);
			}
			case HOT_RELOAD -> {
				if (swapsBefore < 0) {
					if (hotReloads <= 0) {
						go(State.SCRIPT);
						return;
					}
					EmrionShadersClient.LOG.info("Autotest: GPU memory before hot reload: {}", gpuMemory());
					swapsBefore = PackManager.swaps;
					reloadStart = System.nanoTime();
					PackManager.reload();
					return;
				}
				if (PackManager.swaps == swapsBefore) {
					if (ticks > 300 * TPS) throw new IllegalStateException("autotest: hot reload never swapped");
					return;
				}
				if (reloadStart != 0) {
					EmrionShadersClient.LOG.info("Autotest: hot reload swapped in after {} ms", (System.nanoTime() - reloadStart) / 1_000_000);
					reloadStart = 0;
					go(State.HOT_RELOAD);
				}
				if (ticks < 3 * TPS) return; // the deferred destroy queue retires the old version within a few frames
				EmrionShadersClient.LOG.info("Autotest: GPU memory after hot reload ({} left): {}", --hotReloads, gpuMemory());
				swapsBefore = -1;
			}
			case SCRIPT -> {
				if (step >= script.size()) {
					go(State.LEAVE);
					return;
				}
				if (ticks == 1) {
					phase = "script step " + script.get(step).split(" ", 2)[0];
					EmrionShadersClient.LOG.info("Autotest script [{}/{}]: {}", step + 1, script.size(), script.get(step));
				}
				if (scriptStep(mc, script.get(step))) {
					step++;
					go(State.SCRIPT);
				} else if (ticks > 300 * TPS) throw new IllegalStateException("autotest: script step never finished: " + script.get(step));
			}
			case LEAVE -> {
				if (server != null && ticks == 1) { // the user plays on this server: hand the world back as it runs normally
					command(mc, "gamerule advance_time true");
					command(mc, "gamerule advance_weather true");
					command(mc, "gamemode creative @a");
				}
				if (ticks < (server != null ? 10 : 2) || pendingShots.get() > 0 && ticks < 10 * TPS) return; // let the screenshot writes finish
				if (!quit) {
					go(State.DONE);
					logTiming();
					return;
				}
				mc.disconnectWithSavingScreen();
				go(State.QUIT);
			}
			case QUIT -> {
				go(State.DONE);
				logTiming();
				EmrionShadersClient.LOG.info("Autotest finished, quitting");
				mc.stop();
			}
			case DONE -> {}
		}
	}

	/** {@code repeat N { a; b }} → a; b repeated N times (the braces are separate ';'-steps or glued to their neighbours). */
	static List<String> expand(List<String> steps) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < steps.size(); i++) {
			String s = steps.get(i);
			if (!s.startsWith("repeat ")) {
				out.add(s);
				continue;
			}
			String[] head = s.substring(7).split("\\{", 2);
			int n = Integer.parseInt(head[0].strip());
			List<String> body = new ArrayList<>();
			if (head.length > 1 && !head[1].isBlank()) body.add(head[1].strip());
			while (++i < steps.size() && !steps.get(i).endsWith("}")) body.add(steps.get(i));
			if (i < steps.size()) {
				String last = steps.get(i).substring(0, steps.get(i).length() - 1).strip();
				if (!last.isEmpty()) body.add(last);
			}
			for (int k = 0; k < n; k++) out.addAll(body);
		}
		return out;
	}

	/** One script step; called every tick (ticks = 1 on the first) until it returns true. */
	private boolean scriptStep(Minecraft mc, String s) {
		String[] a = s.split(" ", 2);
		String arg = a.length > 1 ? a[1].strip() : "";
		boolean first = ticks == 1;
		switch (a[0]) {
			case "wait" -> {
				double sec = Double.parseDouble(arg);
				if (first) convergence.reset();
				return ticks >= sec * TPS || FAST_WAIT && sec >= 1 && ticks >= MIN_TICKS && settled(mc);
			}
			case "settle" -> { // "settle [MAX_SECONDS]": chunks around the camera loaded and built, image converged (default at most 30 s)
				if (first) convergence.reset();
				boolean done = ticks >= MIN_TICKS && settled(mc);
				if (done || ticks >= (arg.isEmpty() ? 30 : Double.parseDouble(arg)) * TPS) {
					EmrionShadersClient.LOG.info("Autotest: settle {} after {} ms (diff {})", done ? "converged" : "timed out", ticks * 50,
							String.format("%.3f", convergence.lastDiff));
					return true;
				}
				return false;
			}
			case "size" -> {
				String[] wh = arg.split("x");
				if (first) mc.getWindow().setWindowed(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
				return ticks >= TPS;
			}
			case "fullscreen" -> {
				if (first) mc.options.fullscreen().set(arg.equals("on"));
				return ticks >= 2 * TPS;
			}
			case "gui" -> {
				mc.options.guiScale().set(Integer.parseInt(arg));
				mc.resizeGui();
				return true;
			}
			case "cmd" -> {
				command(mc, arg);
				return true;
			}
			case "bob" -> { // "bob WALK AMOUNT|off": view bobbing on, at a fixed phase (the player stands still in the scenes)
				String[] m = arg.split("\\s+");
				mc.options.bobView().set(true);
				bobWalk = m[0].equals("off") ? Float.NaN : Float.parseFloat(m[0]);
				if (m.length > 1) bobAmount = Float.parseFloat(m[1]);
				return true;
			}
			case "hurt" -> { // "hurt TIME DIR|off": damage tilt frozen at hurt time TIME of 10 ticks, from yaw DIR
				String[] m = arg.split("\\s+");
				hurtTime = m[0].equals("off") ? Float.NaN : Float.parseFloat(m[0]);
				if (m.length > 1) hurtDir = Float.parseFloat(m[1]);
				return true;
			}
			case "nausea" -> { // "nausea INTENSITY ANGLE|off": nausea/portal skew frozen at INTENSITY (0..1) and spin ANGLE degrees
				String[] m = arg.split("\\s+");
				nausea = m[0].equals("off") ? Float.NaN : Float.parseFloat(m[0]);
				if (m.length > 1) nauseaAngle = Float.parseFloat(m[1]);
				return true;
			}
			case "shot" -> {
				shoot(mc, tag + "-" + arg + ".png");
				return true;
			}
			case "trace" -> { // "trace start" | "trace stop NAME": every frame time in between -> emrion-trace-NAME.csv (VSync and cap unchanged)
				if (arg.equals("start")) FrameTimeLogger.INSTANCE.start();
				else FrameTimeLogger.INSTANCE.stopAndDump(mc.gameDirectory.toPath().resolve("emrion-trace-" + arg.substring(5).strip() + ".csv"));
				return true;
			}
			case "mem" -> {
				EmrionShadersClient.LOG.info("Autotest: {} FPS, GPU memory: {}{}", mc.getFps(), gpuMemory(),
						dev.emrion.shaders.mod.debug.VramReport.driverUsage(VulkanContext.get()));
				if (dev.emrion.shaders.mod.debug.VramReport.ENABLED) {
					try {
						EmrionShadersClient.LOG.info("Autotest: VMA map {}", dev.emrion.shaders.mod.debug.VramReport.dump(VulkanContext.get(), mc.gameDirectory.toPath()));
					} catch (java.io.IOException e) {
						EmrionShadersClient.LOG.warn("VMA dump failed", e);
					}
				}
				return true;
			}
			case "pack" -> { // "pack off|on|NAME" (NAME: a pack in shaderpacks/, its settings from shaderpacks/NAME.txt)
				if (first) {
					if (scriptPack == null) scriptPack = PackManager.selected();
					if (!arg.equals("off") && !arg.equals("on")) scriptPack = arg;
					PackManager.select(arg.equals("off") ? null : scriptPack, false);
				}
				// "on" is done once the pack renders, or when it failed (selection cleared); the old pack stops at the next frame
				return arg.equals("off") ? !PackManager.running() || ticks > 2 : ticks > 2 && (PackManager.running() || PackManager.selected() == null);
			}
			case "rt" -> { // "rt off|on|hybrid|full|refl|light|hybrid+refl+light|..." (+ent: entities cast RT shadows): the RT page's Apply; waits for the swapped runtime
				if (first) {
					swapsBefore = PackManager.swaps;
					var rt = java.util.List.of(arg.split("\\+"));
					dev.emrion.shaders.mod.rt.RtConfig.enabled = !arg.equals("off");
					if (!arg.equals("off") && !arg.equals("on")) { // explicit effect list: everything else off
						dev.emrion.shaders.mod.rt.RtConfig.shadows = rt.contains("full") ? "full" : rt.contains("hybrid") ? "hybrid" : "off";
						dev.emrion.shaders.mod.rt.RtConfig.reflections = rt.contains("refl");
						dev.emrion.shaders.mod.rt.RtConfig.shadowEntities = rt.contains("ent");
						// "light": RT block light with the configured rays (at least 4)
						dev.emrion.shaders.mod.rt.RtConfig.blockLightRays = !rt.contains("light") ? 0 : Math.max(4, dev.emrion.shaders.mod.rt.RtConfig.blockLightRays);
						dev.emrion.shaders.mod.rt.RtConfig.ao = rt.contains("ao");
						dev.emrion.shaders.mod.rt.RtConfig.caustics = rt.contains("caust");
						// "gi" = medium, "gilow" / "gihigh"
						dev.emrion.shaders.mod.rt.RtConfig.gi = rt.contains("gi") ? "medium" : rt.contains("gilow") ? "low" : rt.contains("gihigh") ? "high" : "off";
					}
					if (!PackManager.applyRt()) swapsBefore = -2; // nothing to swap
				}
				return swapsBefore == -2 || PackManager.swaps != swapsBefore || PackManager.selected() == null;
			}
			case "rtset" -> {
				if (first) {
					swapsBefore = PackManager.swaps;
					var p = new java.util.Properties();
					dev.emrion.shaders.mod.rt.RtConfig.store(p);
					for (String kv : arg.split(",")) {
						String k = kv.substring(0, kv.indexOf('=')).strip(), v = kv.substring(kv.indexOf('=') + 1).strip();
						if (k.equals("preset")) dev.emrion.shaders.mod.rt.RtPreset.parse(v).applyTo(p); // "rtset preset=high,ao=false"
						else p.setProperty("rt." + k, v);
					}
					dev.emrion.shaders.mod.rt.RtConfig.load(p);
					if (!PackManager.applyRt()) swapsBefore = -2;
				}
				return swapsBefore == -2 || PackManager.swaps != swapsBefore || PackManager.selected() == null;
			}
			case "upscale" -> { // "upscale off|fsr|dlss|xess[:preset[:rr]]": the Upscaling page's Done (rr: Ray Reconstruction); waits for a swap when the conversion changes
				if (first) {
					swapsBefore = PackManager.swaps;
					String[] m = arg.split(":");
					dev.emrion.shaders.mod.upscale.UpscaleConfig.mode = dev.emrion.shaders.mod.upscale.UpscaleConfig.Mode.valueOf(m[0].toUpperCase(java.util.Locale.ROOT));
					if (m.length > 1) dev.emrion.shaders.mod.upscale.UpscaleConfig.preset = dev.emrion.shaders.mod.upscale.UpscaleConfig.Preset.valueOf(m[1].toUpperCase(java.util.Locale.ROOT));
					dev.emrion.shaders.mod.upscale.UpscaleConfig.rayReconstruction = m.length > 2 && m[2].equals("rr");
					if (!PackManager.applyUpscale()) swapsBefore = -2; // runtime rebuild only
				}
				return swapsBefore == -2 ? ticks > 2 : PackManager.swaps != swapsBefore || PackManager.selected() == null;
			}
			case "framegen" -> { // "framegen off|fsr|dlss": the Frame Generation page's Done (takes effect at the next frame)
				if (first) {
					dev.emrion.shaders.mod.framegen.FrameGenConfig.mode = dev.emrion.shaders.mod.framegen.FrameGenConfig.Mode.valueOf(arg.toUpperCase(java.util.Locale.ROOT));
					PackManager.applyFrameGen();
				}
				return ticks > 10;
			}
			case "hdr" -> { // "hdr on|off": the HDR toggle (MC recreates the swapchain after the next frame)
				if (first) {
					dev.emrion.shaders.mod.hdr.Hdr.enabled = arg.equals("on");
					dev.emrion.shaders.mod.hdr.Hdr.settingsChanged();
				}
				return ticks > 10;
			}
			case "reflex" -> { // "reflex off|on|boost": the Reflex tile on the Frame Generation page; "fgoff": off even with frame generation (measuring only)
				if (first) {
					dev.emrion.shaders.mod.framegen.Reflex.fgForces = !arg.equals("fgoff");
					dev.emrion.shaders.mod.framegen.Reflex.mode = dev.emrion.shaders.mod.framegen.Reflex.Mode.valueOf(arg.equals("fgoff") ? "OFF" : arg.toUpperCase(java.util.Locale.ROOT));
					PackManager.applyFrameGen();
				}
				return ticks > 10;
			}
			case "move" -> { // "move YAW_DEG_PER_S DX_PER_S DZ_PER_S SECONDS": smooth per-frame camera motion, runs on while the next steps do
				String[] m = arg.split("\\s+");
				motionYaw = Float.parseFloat(m[0]);
				motionDx = Float.parseFloat(m[1]);
				motionDz = Float.parseFloat(m[2]);
				motionUntil = System.nanoTime() + (long) (Float.parseFloat(m[3]) * 1e9);
				motionLast = 0;
				return true;
			}
			case "turnshot", "fgturn" -> { // "turnshot DEG_PER_FRAME FRAMES NAME [DX DZ [T]]": turn by exactly DEG (YAW or YAW:PITCH; and move by DX/DZ blocks) every frame,
				// shoot the last frame of the turn (<tag>-NAME.png); frameTimeCounter restarts at 100 (+ T seconds) so every turnshot sees the
				// same animation. "fgturn": the same turn, then the frame generator's interpolated frame in front of the last one
				// (screenshots/NAME-00-a-interp.png, at the pose and time half a step back)
				if (first) {
					String[] m = arg.split("\\s+");
					String[] deg = m[0].split(":");
					turnDeg = Float.parseFloat(deg[0]);
					turnPitch = deg.length > 1 ? Float.parseFloat(deg[1]) : 0;
					turnFrames = Integer.parseInt(m[1]);
					turnDx = m.length > 4 ? Float.parseFloat(m[3]) : 0;
					turnDz = m.length > 4 ? Float.parseFloat(m[4]) : 0;
					turnFg = a[0].equals("fgturn");
					turnName = turnFg ? m[2] : tag + "-" + m[2] + ".png";
					dev.emrion.shaders.mod.uniforms.IrisTimer.rewindTo = 100 + (m.length > 5 ? Float.parseFloat(m[5]) : 0);
				}
				return turnName == null && dev.emrion.shaders.mod.framegen.FrameCapture.done() && dev.emrion.shaders.mod.upscale.MotionCapture.done();
			}
			case "vsync" -> { // "vsync on|off": Options > VSync
				if (first) mc.options.enableVsync().set(arg.equals("on"));
				return ticks > 10;
			}
			case "bench", "benchv" -> { // "bench SECONDS NAME": rendered and presented frame times (VSync off, no cap) -> emrion-bench-NAME.csv,
				// emrion-presents-NAME.csv; "benchv" keeps the current VSync and frame cap
				String[] m = arg.split("\\s+");
				if (BENCH_SECONDS != null) m[0] = BENCH_SECONDS;
				boolean keep = a[0].equals("benchv");
				if (first && !keep) {
					vsync = mc.options.enableVsync().get();
					fpsLimit = mc.options.framerateLimit().get();
					mc.options.enableVsync().set(false);
					mc.options.framerateLimit().set(260);
				}
				if (ticks == TPS) {
					FrameTimeLogger.INSTANCE.start();
					dev.emrion.shaders.mod.framegen.Presenter.PRESENTS.start();
					dev.emrion.shaders.mod.framegen.Reflex.statsReset();
					if (PROFILE) { if (EmrionShadersClient.active()) Profiler.benchmarkStart(); else GlGpuTimer.reset(); } // per-pass GPU ms for this bench
				}
				if (ticks < (Float.parseFloat(m[0]) + 1) * TPS) return false;
				if (PROFILE) { if (EmrionShadersClient.active()) Profiler.benchmarkEnd(); else GlGpuTimer.log(); }
				FrameTimeLogger.INSTANCE.stopAndDump(mc.gameDirectory.toPath().resolve("emrion-bench-" + m[1] + ".csv"));
				var pr = dev.emrion.shaders.mod.framegen.Presenter.PRESENTS.stopAndDump(mc.gameDirectory.toPath().resolve("emrion-presents-" + m[1] + ".csv"));
				EmrionShadersClient.LOG.info("Bench {}: presents {} (avg {} per second)", m[1], pr.frames(), String.format("%.1f", pr.avgFps()));
				dev.emrion.shaders.mod.framegen.Reflex.statsLog(m[1]);
				if (!keep) {
					mc.options.enableVsync().set(vsync);
					mc.options.framerateLimit().set(fpsLimit);
				}
				return true;
			}
			case "frames" -> { // "frames SECONDS NAME": every frame for SECONDS at quarter size (less if the target is not divisible by 4); returns at once, the next steps run meanwhile
				String[] m = arg.split("\\s+");
				framesIndex = 0;
				framesStart = System.nanoTime();
				framesUntil = framesStart + (long) (Double.parseDouble(m[0]) * 1e9);
				framesName = tag + "-" + m[1];
				return true;
			}
			case "fgshots" -> { // "fgshots N NAME": the next N frames as presented (interpolated, then real) -> screenshots/NAME-NN-{a-interp,b-real}.png
				String[] m = arg.split("\\s+");
				if (first) dev.emrion.shaders.mod.framegen.FrameCapture.request(Integer.parseInt(m[0]), mc.gameDirectory.toPath().resolve("screenshots"), m[1]);
				return dev.emrion.shaders.mod.framegen.FrameCapture.done();
			}
			case "resources" -> { // F3+T
				if (first) reload = mc.reloadResourcePacks();
				return reload != null && reload.isDone();
			}
			case "upscaleopts" -> { // "upscaleopts objects=on|off masks=on|off": object motion vectors and upscaler masks, for before/after shots
				for (String kv : arg.split("\\s+")) {
					boolean v = kv.endsWith("=on");
					if (kv.startsWith("objects=")) dev.emrion.shaders.mod.upscale.ObjectMotion.on = v;
					if (kv.startsWith("masks=")) dev.emrion.shaders.mod.upscale.Upscaler.masksOn = v;
				}
				return true;
			}
			case "mvshot" -> { // "mvshot NAME": the next frame's motion vectors (and upscaler masks) -> screenshots/NAME-{motion,masks}.png
				if (first) dev.emrion.shaders.mod.upscale.MotionCapture.request(mc.gameDirectory.toPath().resolve("screenshots"), arg);
				return dev.emrion.shaders.mod.upscale.MotionCapture.done();
			}
			case "reload" -> {
				if (first) {
					swapsBefore = PackManager.swaps;
					PackManager.reload();
				}
				return PackManager.swaps != swapsBefore || PackManager.selected() == null;
			}
			case "leave" -> {
				if (first) mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("autotest")); // as the pause menu: saves, then the title screen
				return mc.level == null && mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen;
			}
			case "join" -> {
				if (first) openOrCreate(mc);
				return ticks > TPS && mc.level != null && mc.player != null && mc.gui.screen() == null;
			}
			case "hud" -> {
				mc.gui.hud.toggle();
				return true;
			}
			case "f3" -> {
				mc.debugEntries.setOverlayVisible(arg.equals("on"));
				return true;
			}
			case "screen" -> {
				mc.gui.setScreen(switch (arg) {
					case "pause" -> new net.minecraft.client.gui.screens.PauseScreen(true);
					case "inventory" -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player);
					case "chat" -> new net.minecraft.client.gui.screens.ChatScreen("", false);
					case "packs" -> new dev.emrion.shaders.mod.pack.PackScreen(null);
					case "rt" -> new dev.emrion.shaders.mod.gui.RtOptionsScreen(null);
					case "upscale" -> new dev.emrion.shaders.mod.gui.UpscaleScreen(null);
					case "framegen" -> new dev.emrion.shaders.mod.gui.FrameGenScreen(null);
					case "perf" -> new dev.emrion.shaders.mod.gui.PerformanceScreen(null);
					case "dlss" -> new dev.emrion.shaders.mod.gui.DlssDownloadScreen(null);
					default -> {
						if (!arg.startsWith("options")) yield null; // "options" = the pack's main screen, "options:ID" = screen.ID
						var session = PackManager.optionSession();
						if (session == null) throw new IllegalStateException("autotest: no pack loaded for " + s);
						yield ShaderOptionsScreen.open(session, arg.contains(":") ? arg.substring(arg.indexOf(':') + 1) : "");
					}
				});
				return true;
			}
			case "mdi" -> { // "mdi on|off": vanilla terrain multi-draw indirect (F3+M)
				mc.multiDrawIndirect = arg.equals("on");
				return ticks > 2;
			}
			case "press" -> { // a key as if typed, e.g. "press o" (key.keyboard.o)
				net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard." + arg));
				return true;
			}
			case "rawkey" -> { // "rawkey f2": press and release through KeyboardHandler, as from the keyboard (F2 screenshot, debug keys)
				int code = com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard." + arg).getValue();
				var ev = new net.minecraft.client.input.KeyEvent(code, org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyFromScancode(code, (short) 0, true), 0);
				mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, ev);
				mc.keyboardHandler.keyPress(mc.getWindow().handle(), 0, ev);
				return true;
			}
			case "key" -> { // a key typed into the open screen, e.g. "key f1", "key d ctrl"
				String[] k = arg.split(" ");
				int code = com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard." + k[0]).getValue();
				int mods = k.length > 1 && k[1].equals("ctrl") ? com.mojang.blaze3d.platform.InputConstants.MOD_CONTROL : 0;
				if (mc.gui.screen() != null) {
					// key = scancode; the screens' navigation (Tab, arrows) reads the layout keycode
					int keycode = org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyFromScancode(code, (short) 0, true);
					mc.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(code, keycode, mods));
					if (mc.gui.screen() != null && mc.gui.screen().getFocused() instanceof net.minecraft.client.gui.components.AbstractWidget w)
						EmrionShadersClient.LOG.info("Autotest: key {} -> focus '{}'", arg, w.getMessage().getString());
				}
				return true;
			}
			case "drop" -> { // files dropped onto the open screen
				if (mc.gui.screen() != null) mc.gui.screen().onFilesDrop(java.util.Arrays.stream(arg.split(";")).map(java.nio.file.Path::of).toList());
				return true;
			}
			case "mats" -> { // logs gbufferModelView/Projection: Iris's captured ones when Iris renders, else ours
				var cs = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
				Object mv = cs.viewRotationMatrix, pr = dev.emrion.shaders.mod.uniforms.CapturedProjection.LEVEL;
				try {
					Class<?> c = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
					Object i = c.getField("INSTANCE").get(null);
					mv = c.getMethod("getGbufferModelView").invoke(i);
					pr = c.getMethod("getGbufferProjection").invoke(i);
				} catch (ReflectiveOperationException ignored) { // no Iris: ours
				}
				EmrionShadersClient.LOG.info("Autotest: mats {}\ngbufferModelView=\n{}gbufferProjection=\n{}", arg, mv, pr);
				return true;
			}
			case "irisapi" -> { // test consumer: what a mod asking the Iris API sees (only present when Iris is not installed)
				try {
					var api = net.irisshaders.iris.api.v0.IrisApi.getInstance();
					EmrionShadersClient.LOG.info("Autotest: IrisApi rev {} isShaderPackInUse={} areShadersEnabled={}", api.getMinorApiRevision(),
							api.isShaderPackInUse(), api.getConfig().areShadersEnabled());
				} catch (Throwable t) {
					EmrionShadersClient.LOG.info("Autotest: IrisApi unavailable: {}", t.toString());
				}
				return true;
			}
			case "motion" -> {
				motion = arg.equals("off") ? null : Arrays.stream(arg.split("\\s+")).mapToDouble(Double::parseDouble).toArray();
				if (motion != null && motion.length != 5) throw new IllegalArgumentException("autotest: motion needs dx dy dz dyaw dpitch: " + s);
				return true;
			}
			case "swing" -> { // one attack click: the main hand swings
				net.minecraft.client.KeyMapping.click(mc.options.keyAttack.getDefaultKey()); // as a left click
				return true;
			}
			case "photo" -> { // "photo SCALE SAMPLES NAME [RESETS]": photo mode -> screenshots/<tag>-NAME.png, waits until it is written
				String[] m = arg.split("\\s+");
				if (first && m.length > 3) { // history resets: "rt", "up", "rt+up" or "none" (default both)
					dev.emrion.shaders.mod.photo.PhotoMode.resetRt = m[3].contains("rt");
					dev.emrion.shaders.mod.photo.PhotoMode.resetUpscaler = m[3].contains("up");
				}
				if (first) dev.emrion.shaders.mod.photo.PhotoMode.start(mc, Integer.parseInt(m[0]), Integer.parseInt(m[1]), tag + "-" + m[2] + ".png");
				return ticks > 1 && !dev.emrion.shaders.mod.photo.PhotoMode.frozen() && dev.emrion.shaders.mod.photo.PhotoMode.writing.get() == 0;
			}
			case "snapshot" -> {
				return snapshotStep(mc, arg, first);
			}
			case "use" -> {
				mc.options.keyUse.setDown(arg.equals("down"));
				return true;
			}
			case "fly" -> { // "fly on|off": creative flight, so a camera moved by turnshot/move after a snapshot does not fall
				mc.player.getAbilities().flying = arg.equals("on");
				mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
				return true;
			}
			default -> throw new IllegalArgumentException("autotest: unknown script step " + s);
		}
	}

	// ---- debug snapshot replay (debug/Snapshot.java writes them) ----------------------------------------------------------

	private com.google.gson.@Nullable JsonObject snap;
	private int snapPhase, snapSwaps, snapTick;
	private boolean snapNewPack, snapReload;

	private static com.google.gson.JsonObject readSnapshot(String file) {
		try {
			return com.google.gson.JsonParser.parseString(Files.readString(Path.of(file))).getAsJsonObject();
		} catch (IOException e) {
			throw new IllegalStateException("autotest: cannot read snapshot " + file, e);
		}
	}

	/** Without -Demrion.autotest.world/server: the first snapshot's own place (a local server, or its world and seed). */
	private void pickSnapshotWorld() {
		if (world != null || server != null) return;
		String step = splitCommands(System.getProperty(P + "script", "")).stream().filter(st -> st.startsWith("snapshot ")).findFirst().orElse(null);
		if (step == null) return;
		var w = readSnapshot(step.substring(9).strip()).getAsJsonObject("world");
		String addr = w.has("server") ? w.get("server").getAsString() : "";
		if (addr.startsWith("127.") || addr.startsWith("localhost")) server = addr;
		else {
			world = w.get("name").getAsString();
			if (w.has("seed")) seed = w.get("seed").getAsLong(); // a missing world is created from the same seed: same terrain, no builds
		}
	}

	/** "snapshot FILE": apply everything once, wait for the pack swap and the image to settle (pose pinned every tick), shoot. */
	private boolean snapshotStep(Minecraft mc, String file, boolean first) {
		if (first) {
			snap = readSnapshot(file);
			snapPhase = 0;
			applySnapshot(mc, snap);
		}
		pinSnapshot(mc, snap);
		switch (snapPhase) {
			case 0 -> { // the pack (re)load, if the settings, config or pack changed
				boolean done = PackManager.selected() == null || !PackManager.enabled() /* toggled off: parked, no load or swap comes */ || (snapNewPack ? ticks > 2 && PackManager.running() : !snapReload || PackManager.swaps != snapSwaps);
				if (done) {
					snapPhase = 1;
					snapTick = ticks;
					convergence.reset();
				}
			}
			case 1 -> {
				boolean done = ticks - snapTick >= MIN_TICKS && settled(mc);
				if (done || ticks - snapTick > 60 * TPS) {
					if (!done) EmrionShadersClient.LOG.warn("Autotest: snapshot did not settle, shooting anyway");
					String dir = Path.of(file).toAbsolutePath().getParent().getFileName().toString();
					var pl = mc.player;
					EmrionShadersClient.LOG.info("Autotest: snapshot {} at {} {} {} / {} {} in {}", dir, pl.getX(), pl.getY(), pl.getZ(), pl.getYRot(), pl.getXRot(),
							mc.level.dimension().identifier());
					shoot(mc, tag + "-snapshot-" + dir + ".png");
					snapPhase = 2;
				}
			}
			default -> {
				return pendingShots.get() == 0;
			}
		}
		return false;
	}

	private void applySnapshot(Minecraft mc, com.google.gson.JsonObject j) {
		var p = j.getAsJsonObject("player");
		var w = j.getAsJsonObject("world");
		var o = j.getAsJsonObject("options");
		command(mc, "gamerule advance_time false");
		command(mc, "gamerule advance_weather false");
		command(mc, "gamemode " + p.get("gameMode").getAsString() + " @a");
		command(mc, "execute in " + w.get("dimension").getAsString() + " run tp @a " + p.get("x").getAsDouble() + " " + p.get("y").getAsDouble() + " "
				+ p.get("z").getAsDouble() + " " + p.get("yaw").getAsFloat() + " " + p.get("pitch").getAsFloat());
		command(mc, "time set " + j.getAsJsonObject("time").get("dayTime").getAsLong());
		command(mc, "weather " + j.getAsJsonObject("weather").get("state").getAsString());
		command(mc, "effect clear @a");
		for (var e : p.getAsJsonArray("effects")) {
			var ef = e.getAsJsonObject();
			int d = ef.get("duration").getAsInt();
			command(mc, "effect give @a " + ef.get("id").getAsString() + " " + (d < 0 ? "infinite" : String.valueOf(Math.max(1, (d + 19) / 20))) + " "
					+ ef.get("amplifier").getAsInt() + " true");
		}
		var hotbar = p.getAsJsonArray("hotbar");
		for (int i = 0; i < hotbar.size(); i++) command(mc, "item replace entity @a hotbar." + i + " with " + hotbar.get(i).getAsString());
		command(mc, "item replace entity @a weapon.offhand with " + p.get("offHand").getAsString());
		mc.player.getInventory().setSelectedSlot(p.get("selectedSlot").getAsInt());

		mc.options.setCameraType(net.minecraft.client.CameraType.valueOf(o.get("camera").getAsString()));
		mc.options.fov().set(o.get("fov").getAsInt());
		mc.options.fovEffectScale().set(o.get("fovEffectScale").getAsDouble());
		mc.options.bobView().set(o.get("bobView").getAsBoolean());
		mc.options.gamma().set(o.get("gamma").getAsDouble());
		mc.options.renderDistance().set(o.get("renderDistance").getAsInt());
		mc.options.guiScale().set(o.get("guiScale").getAsInt());
		mc.resizeGui();
		if (mc.gui.hud.isHidden() != o.get("hudHidden").getAsBoolean()) mc.gui.hud.toggle();
		int ww = o.get("windowWidth").getAsInt(), wh = o.get("windowHeight").getAsInt();
		if (!o.get("fullscreen").getAsBoolean() && (mc.getWindow().getScreenWidth() != ww || mc.getWindow().getScreenHeight() != wh)) mc.getWindow().setWindowed(ww, wh);

		// Emberlight config: the file (startup-only keys too) and the live RT / upscaling / frame generation settings
		var cfg = new java.util.Properties();
		j.getAsJsonObject("emberlight").entrySet().forEach(e -> cfg.setProperty(e.getKey(), e.getValue().getAsString()));
		try (var wr = Files.newBufferedWriter(EmrionShadersClient.configFile())) {
			cfg.store(wr, "Emberlight (from a snapshot)");
		} catch (IOException e) {
			throw new IllegalStateException("autotest: cannot write the config", e);
		}
		dev.emrion.shaders.mod.rt.RtConfig.load(cfg);
		dev.emrion.shaders.mod.upscale.UpscaleConfig.load(cfg);
		dev.emrion.shaders.mod.framegen.FrameGenConfig.load(cfg);
		dev.emrion.shaders.mod.framegen.Reflex.load(cfg);
		dev.emrion.shaders.mod.hdr.Hdr.load(cfg);
		dev.emrion.shaders.mod.hdr.Hdr.settingsChanged();
		String recv = cfg.getProperty("perf.shadowReceivers", "all");
		if (dev.emrion.shaders.mod.runtime.ReceiverGrid.MODES.contains(recv)) dev.emrion.shaders.mod.runtime.ReceiverGrid.mode = recv;
		dev.emrion.shaders.mod.upscale.Upscaling.settingsChanged();
		PackManager.applyFrameGen();

		// the pack: the snapshot's if this run has it, else the current one with the snapshot's settings
		var pk = j.getAsJsonObject("pack");
		String want = pk.get("name").getAsString();
		snapSwaps = PackManager.swaps;
		snapNewPack = false;
		snapReload = false;
		if (want.equals("off")) {
			PackManager.select(null, false);
			return;
		}
		String pack = PackManager.packs().contains(want) ? want : PackManager.selected();
		if (pack == null) {
			EmrionShadersClient.LOG.warn("Autotest: snapshot pack {} is not installed and no pack is selected", want);
			return;
		}
		if (!pack.equals(want)) EmrionShadersClient.LOG.warn("Autotest: snapshot pack {} is not installed, using {} with its settings", want, pack);
		boolean changed;
		try {
			var settings = new java.util.LinkedHashMap<String, String>();
			pk.getAsJsonObject("settings").entrySet().forEach(e -> settings.put(e.getKey(), e.getValue().getAsString()));
			changed = dev.emrion.shaders.converter.options.IrisSettings.update(PackManager.settingsFile(pack), settings);
		} catch (IOException e) {
			throw new IllegalStateException("autotest: cannot write the pack settings", e);
		}
		if (!pack.equals(PackManager.selected())) {
			PackManager.select(pack, false);
			snapNewPack = true;
		} else snapReload = PackManager.applyRt() || changed && reloadPack();
		if (PackManager.enabled() != pk.get("enabled").getAsBoolean()) PackManager.setEnabled(pk.get("enabled").getAsBoolean());
	}

	private static boolean reloadPack() {
		PackManager.reload();
		return true;
	}

	/** Every tick until the shot: the exact pose (no gravity or drift) and rain/thunder level, as the snapshot had them. */
	private static void pinSnapshot(Minecraft mc, com.google.gson.JsonObject j) {
		var pl = mc.player;
		if (pl == null || mc.level == null || !mc.level.dimension().identifier().toString().equals(j.getAsJsonObject("world").get("dimension").getAsString())) return;
		var p = j.getAsJsonObject("player");
		double x = p.get("x").getAsDouble(), y = p.get("y").getAsDouble(), z = p.get("z").getAsDouble();
		pl.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		pl.setPos(x, y, z);
		pl.xo = pl.xOld = x;
		pl.yo = pl.yOld = y;
		pl.zo = pl.zOld = z;
		float yaw = p.get("yaw").getAsFloat(), pitch = p.get("pitch").getAsFloat();
		pl.setYRot(yaw);
		pl.yRotO = yaw;
		pl.setXRot(pitch);
		pl.xRotO = pitch;
		pl.yBodyRot = pl.yBodyRotO = p.get("bodyYaw").getAsFloat();
		pl.yHeadRot = pl.yHeadRotO = yaw;
		if (p.has("flying") && pl.getAbilities().mayfly) pl.getAbilities().flying = p.get("flying").getAsBoolean(); // stays put after the step
		var wx = j.getAsJsonObject("weather");
		mc.level.setRainLevel(wx.get("rain").getAsFloat());
		mc.level.setThunderLevel(wx.get("thunder").getAsFloat());
	}

	private String shotName() {
		Scene scene = scenes.get(shot);
		String cfg = configs.isEmpty() ? "" : "-" + configs.get(config).name();
		return scene.name() != null ? tag + "-" + scene.name() + cfg + ".png" : "emrion-" + tag + (scenes.size() > 1 ? "-" + shot : "") + cfg + ".png";
	}

	private static void shoot(Minecraft mc, String name) {
		pendingShots.incrementAndGet();
		Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, msg -> {
			pendingShots.decrementAndGet();
			EmrionShadersClient.LOG.info("Autotest screenshot: {}", msg.getString());
		});
	}

	private State firstScene() {
		return configs.isEmpty() ? State.SETUP : State.CONFIG;
	}

	/** All scenes shot (for this configuration): the benchmark at the last one, then the next configuration or the rest. */
	private void afterScenes() {
		if (benchmarkSeconds > 0 && !benched) {
			benched = true;
			go(State.BENCHMARK);
		} else if (config + 1 < configs.size()) {
			config++;
			benched = false;
			go(State.CONFIG);
		} else go(State.OPTIONS);
	}

	/** The chunks around the camera are built and the image has converged (resets the convergence while they are not). */
	private boolean settled(Minecraft mc) {
		// a dimension switch renders vanilla until the pack's pipelines for the new dimension are built: not settled yet
		// a resource reload (e.g. a pack from -Demrion.autotest.resourcepacks) rebuilds the renderer: Sodium's section
		// manager is null until it finishes
		if (mc.gui.overlay() != null || dev.emrion.shaders.mod.compat.SodiumTerrain.LOADED && !dev.emrion.shaders.mod.compat.sodium.SodiumRt.sectionsReady() || !chunksLoaded(mc) || !mc.levelRenderer.hasRenderedAllSections() || PackManager.selected() != null && !PackManager.running()) {
			convergence.reset();
			return false;
		}
		return convergence.poll(mc);
	}

	/** Every chunk within render distance - 1 (a circle, as the server sends them) is on the client. */
	private static boolean chunksLoaded(Minecraft mc) {
		if (mc.level == null || mc.player == null) return false;
		int r = mc.options.getEffectiveRenderDistance() - 1, cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
		var chunks = mc.level.getChunkSource();
		for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (dx * dx + dz * dz <= r * r && !chunks.hasChunk(cx + dx, cz + dz)) return false;
		return true;
	}

	/** The teleport has arrived (the old position's chunks would pass {@link #chunksLoaded}). */
	private static boolean atScenePos(Minecraft mc, Scene scene) {
		if (scene.pos() == null) return true;
		String[] p = scene.pos().split("\\s+");
		try {
			return mc.player.distanceToSqr(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])) < 1;
		} catch (RuntimeException e) {
			return true; // relative coordinates
		}
	}

	private void logTiming() {
		long total = timing.values().stream().mapToLong(Long::longValue).sum();
		long jvm = System.currentTimeMillis() - java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
		StringBuilder sb = new StringBuilder(String.format("Autotest timing: %.1f s since JVM start, %.1f s in the harness:", jvm / 1e3, total / 1e9));
		timing.entrySet().stream().sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
				.forEach(e -> sb.append(String.format("%n  %8.1f s  %s", e.getValue() / 1e9, e.getKey())));
		EmrionShadersClient.LOG.info(sb.toString());
	}

	/** VMA's view of device memory: bytes VMA has allocated (MC's and ours) and the driver-reported usage, per heap, in MiB. */
	private static String gpuMemory() {
		var ctx = VulkanContext.get();
		try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
			var props = org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties.malloc(stack);
			org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties(ctx.vk.getPhysicalDevice(), props);
			var budgets = org.lwjgl.util.vma.VmaBudget.calloc(org.lwjgl.vulkan.VK10.VK_MAX_MEMORY_HEAPS, stack);
			org.lwjgl.util.vma.Vma.vmaGetHeapBudgets(ctx.vma, budgets);
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < props.memoryHeapCount(); i++) {
				var b = budgets.get(i);
				sb.append(String.format("heap%d%s allocated %.1f MiB, usage %.1f MiB; ", i,
						(props.memoryHeaps(i).flags() & org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0 ? " (device)" : "",
						b.statistics().allocationBytes() / 1048576.0, b.usage() / 1048576.0));
				sb.append(String.format("(%d allocations, %d blocks); ", b.statistics().allocationCount(), b.statistics().blockCount()));
			}
			System.gc(); // leak checks compare heap after GC
			var rt = Runtime.getRuntime();
			sb.append(String.format("Java heap %.1f MiB; ", (rt.totalMemory() - rt.freeMemory()) / 1048576.0));
			try {
				for (String l : Files.readAllLines(Path.of("/proc/self/status")))
					if (l.startsWith("VmRSS:")) sb.append("RSS ").append(l.substring(6).strip()).append("; ");
			} catch (IOException ignored) {}
			return sb.toString();
		}
	}

	/** Sodium's chosen draw backend (DrawBackend.BACKEND), or "" without Sodium. */
	private static String sodiumBackend() {
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")) return "";
		try {
			return ", Sodium draw backend " + Class.forName("net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend").getField("BACKEND").get(null);
		} catch (ReflectiveOperationException e) {
			return ", Sodium draw backend unknown: " + e;
		}
	}

	private void go(State next) {
		long now = System.nanoTime();
		timing.merge(phase, now - phaseStart, Long::sum);
		if (now - phaseStart >= 100_000_000L) EmrionShadersClient.LOG.info("Autotest: {} took {} ms", phase, (now - phaseStart) / 1_000_000);
		phaseStart = now;
		phase = next.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
		state = next;
		ticks = 0;
	}

	private void openOrCreate(Minecraft mc) {
		if (server != null) {
			EmrionShadersClient.LOG.info("Autotest: joining server {} as {}", server, mc.getUser().getName());
			net.minecraft.client.gui.screens.ConnectScreen.startConnecting(mc.gui.screen(), mc, net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(server),
					new net.minecraft.client.multiplayer.ServerData("autotest", server, net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
		} else if (mc.getLevelSource().levelExists(world)) {
			EmrionShadersClient.LOG.info("Autotest: loading world '{}'", world);
			mc.createWorldOpenFlows().openWorld(world, () -> EmrionShadersClient.LOG.error("Autotest: loading '{}' was cancelled", world));
		} else {
			EmrionShadersClient.LOG.info("Autotest: creating world '{}' with seed {}", world, seed);
			var settings = new LevelSettings(world, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
					WorldDataConfiguration.DEFAULT);
			mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(seed, true, false), WorldPresets::createNormalWorldDimensions,
					mc.gui.screen());
		}
	}

	private static void command(Minecraft mc, String command) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) {
			if (mc.player == null) throw new IllegalStateException("autotest: not in a world for " + command);
			mc.player.connection.sendCommand(command.replace("@a", "@s")); // only ever the test player on the shared server
			return;
		}
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
	}
}
