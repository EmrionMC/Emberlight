package dev.emrion.shaders.mod.autotest;

import dev.emrion.shaders.mod.EmrionShadersClient;
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
 * emrion.autotest.seed=2026
 * emrion.autotest.pos=x,y,z,yaw,pitch[;x,y,z,yaw,pitch...]   one screenshot per position
 * emrion.autotest.time=6000         emrion.autotest.wait=8 (seconds, plus until chunk builds are done)
 * emrion.autotest.reload=N          after joining, reload the resource packs N times and log each duration
 * emrion.autotest.resourcepacks=/abs/pack.zip[;...]   (Gradle runClient only) symlink + enable these resource packs
 * emrion.autotest.tag=name          screenshots: run/screenshots/emrion-&lt;tag&gt;[-i].png
 * emrion.autotest.commands=cmd1;cmd2   extra commands (no slash) run at every position, before the teleport
 * emrion.autotest.scenes=file      scene list instead of pos/time/commands (format: mod/emrion-scenes.txt);
 *                                  screenshots: run/screenshots/&lt;tag&gt;-&lt;scene&gt;.png
 * emrion.autotest.only=a,b         only these scenes of the list (e.g. the benchmark scene)
 * emrion.autotest.settle=5         seconds to wait after chunks are built (temporal effects), and at least
 * emrion.autotest.settle.frames=200 frames
 * emrion.autotest.benchmark.seconds=N   frame-time capture after the screenshots (at the last position)
 * emrion.autotest.quit=true         leave the world (saving it) and exit
 * emrion.autotest.hud=true          keep the HUD (and with it the first-person hand) visible
 * </pre>
 * With only {@code quit=true} the game exits as soon as the title screen is up (smoke test).
 */
public final class AutoTest {
	private static final String P = "emrion.autotest.";
	private static final int TPS = 20;

	private enum State { WAIT_TITLE, WAIT_WORLD, RELOAD, SETUP, WAIT_CHUNKS, SETTLE, SHOT, BENCHMARK, LEAVE, QUIT, DONE }

	private final String world = System.getProperty(P + "world");
	private final long seed = Long.getLong(P + "seed", 2026L);
	private final int time = Integer.getInteger(P + "time", 6000);
	private final int waitTicks = Integer.getInteger(P + "wait", 8) * TPS;
	private final String tag = System.getProperty(P + "tag", "shot");
	private final int benchmarkSeconds = Integer.getInteger(P + "benchmark.seconds", 0);
	private final boolean quit = Boolean.getBoolean(P + "quit");
	private final int settleTicks = Integer.getInteger(P + "settle", 5) * TPS;
	private final int settleFrames = Integer.getInteger(P + "settle.frames", 200);
	private final List<Scene> scenes = new ArrayList<>();

	/** {@code name == null}: legacy pos mode (emrion-&lt;tag&gt;[-i].png). {@code pos == null}: wherever the player is. */
	record Scene(String name, String pos, int time, String weather, List<String> commands) {}
	private int reloads = Integer.getInteger(P + "reload", 0);
	private @Nullable CompletableFuture<Void> reload;
	private long reloadStart;

	private State state = State.WAIT_TITLE;
	private int ticks;
	private int shot;
	private boolean vsync;
	private int fpsLimit;
	private long settleStartFrame;

	private AutoTest() {
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

	private static List<String> splitCommands(String s) {
		return Arrays.stream(s.split(";")).map(String::strip).map(c -> c.startsWith("/") ? c.substring(1) : c).filter(c -> !c.isEmpty()).toList();
	}

	public static void registerIfEnabled() {
		if (System.getProperty(P + "world") == null && !Boolean.getBoolean(P + "quit")) return;
		AutoTest test = new AutoTest();
		ClientTickEvents.END_CLIENT_TICK.register(test::tick);
		EmrionShadersClient.LOG.info("Autotest enabled: world={} scenes={} tag={}", test.world, test.scenes, test.tag);
	}

	private void tick(Minecraft mc) {
		ticks++;
		switch (state) {
			case WAIT_TITLE -> {
				if (mc.gui.overlay() != null || mc.gui.screen() == null || ticks < TPS) return;
				if (world == null) {
					go(State.QUIT);
				} else {
					openOrCreate(mc);
					go(State.WAIT_WORLD);
				}
			}
			case WAIT_WORLD -> {
				if (mc.level != null && mc.player != null && mc.gui.screen() == null) go(reloads > 0 ? State.RELOAD : State.SETUP);
			}
			case RELOAD -> {
				if (reload == null) {
					reloadStart = System.nanoTime();
					reload = mc.reloadResourcePacks();
				} else if (reload.isDone() && mc.gui.overlay() == null) {
					EmrionShadersClient.LOG.info("Autotest: resource reload took {} ms", (System.nanoTime() - reloadStart) / 1_000_000);
					reload = null;
					go(--reloads > 0 ? State.RELOAD : State.SETUP);
				}
			}
			case SETUP -> {
				if (!mc.gui.hud.isHidden() && !Boolean.getBoolean(P + "hud")) mc.gui.hud.toggle(); // hud=true keeps it (and the hand)
				Scene scene = scenes.get(shot);
				command(mc, "gamemode spectator @a");
				command(mc, "gamerule advance_time false");
				command(mc, "gamerule advance_weather false");
				command(mc, "time set " + scene.time());
				command(mc, "weather " + scene.weather());
				scene.commands().forEach(c -> command(mc, c));
				if (scene.pos() != null) command(mc, "tp @a " + scene.pos());
				go(State.WAIT_CHUNKS);
			}
			case WAIT_CHUNKS -> {
				boolean built = mc.levelRenderer.hasRenderedAllSections();
				if ((ticks >= waitTicks && built) || ticks >= 4 * waitTicks) {
					if (!built) EmrionShadersClient.LOG.warn("Autotest: chunk builds still pending, taking the screenshot anyway");
					// worldgen animals in the freshly loaded chunks would wander differently in every run; scenes tag their own mobs
					command(mc, "kill @e[type=!minecraft:player,tag=!emrion]");
					settleStartFrame = FrameTimeLogger.INSTANCE.frames();
					go(State.SETTLE);
				}
			}
			case SETTLE -> {
				// a stray F1/F3/Esc on the visible window must not end up in the screenshot
				if (!mc.gui.hud.isHidden() && !Boolean.getBoolean(P + "hud")) mc.gui.hud.toggle(); // hud=true keeps it (and the hand)
				mc.debugEntries.setOverlayVisible(false);
				if (mc.gui.screen() != null) mc.gui.setScreen(null);
				if (ticks >= settleTicks && FrameTimeLogger.INSTANCE.frames() - settleStartFrame >= settleFrames) go(State.SHOT);
			}
			case SHOT -> {
				Scene scene = scenes.get(shot);
				String name = scene.name() != null ? tag + "-" + scene.name() + ".png"
						: "emrion-" + tag + (scenes.size() > 1 ? "-" + shot : "") + ".png";
				var cam = mc.gameRenderer.mainCamera();
				EmrionShadersClient.LOG.info("Autotest: {} from player {} {} {} / {} {}, camera {}", name, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
						mc.player.getYRot(), mc.player.getXRot(), cam.position());
				Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
						msg -> EmrionShadersClient.LOG.info("Autotest screenshot: {}", msg.getString()));
				shot++;
				if (shot < scenes.size()) go(State.SETUP);
				else if (benchmarkSeconds > 0) go(State.BENCHMARK);
				else go(State.LEAVE);
			}
			case BENCHMARK -> {
				if (ticks == 1) { // uncapped for the measurement, restored afterwards; 1 s warm-up
					vsync = mc.options.enableVsync().get();
					fpsLimit = mc.options.framerateLimit().get();
					mc.options.enableVsync().set(false);
					mc.options.framerateLimit().set(260);
				}
				if (ticks == TPS) FrameTimeLogger.INSTANCE.start();
				if (ticks >= (benchmarkSeconds + 1) * TPS) {
					FrameTimeLogger.INSTANCE.stopAndDump(mc.gameDirectory.toPath().resolve("emrion-benchmark-" + tag + ".csv"));
					mc.options.enableVsync().set(vsync);
					mc.options.framerateLimit().set(fpsLimit);
					go(State.LEAVE);
				}
			}
			case LEAVE -> {
				if (ticks < TPS) return; // let the screenshot write finish
				if (!quit) {
					go(State.DONE);
					return;
				}
				mc.disconnectWithSavingScreen();
				go(State.QUIT);
			}
			case QUIT -> {
				EmrionShadersClient.LOG.info("Autotest finished, quitting");
				mc.stop();
				go(State.DONE);
			}
			case DONE -> {}
		}
	}

	private void go(State next) {
		state = next;
		ticks = 0;
	}

	private void openOrCreate(Minecraft mc) {
		if (mc.getLevelSource().levelExists(world)) {
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
		if (server == null) throw new IllegalStateException("autotest needs a singleplayer world");
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
	}
}
