package dev.emrion.shaders.mod.autotest;

import dev.emrion.shaders.mod.EmrionShadersClient;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Dev-only, unattended visual checks driven by system properties (all optional except {@code world}):
 * <pre>
 * emrion.autotest.world=name        create (fixed seed, default world type) or load this singleplayer world
 * emrion.autotest.seed=2026
 * emrion.autotest.pos=x,y,z,yaw,pitch[;x,y,z,yaw,pitch...]   one screenshot per position
 * emrion.autotest.time=6000         emrion.autotest.wait=8 (seconds, plus until chunk builds are done)
 * emrion.autotest.tag=name          screenshots: run/screenshots/emrion-&lt;tag&gt;[-i].png
 * emrion.autotest.benchmark.seconds=N   frame-time capture after the screenshots
 * emrion.autotest.quit=true         leave the world (saving it) and exit
 * </pre>
 * With only {@code quit=true} the game exits as soon as the title screen is up (smoke test).
 */
public final class AutoTest {
	private static final String P = "emrion.autotest.";
	private static final int TPS = 20;

	private enum State { WAIT_TITLE, WAIT_WORLD, SETUP, WAIT_CHUNKS, SHOT, BENCHMARK, LEAVE, QUIT, DONE }

	private final String world = System.getProperty(P + "world");
	private final long seed = Long.getLong(P + "seed", 2026L);
	private final int time = Integer.getInteger(P + "time", 6000);
	private final int waitTicks = Integer.getInteger(P + "wait", 8) * TPS;
	private final String tag = System.getProperty(P + "tag", "shot");
	private final int benchmarkSeconds = Integer.getInteger(P + "benchmark.seconds", 0);
	private final boolean quit = Boolean.getBoolean(P + "quit");
	private final List<String> positions = new ArrayList<>();

	private State state = State.WAIT_TITLE;
	private int ticks;
	private int shot;
	private boolean vsync;
	private int fpsLimit;

	private AutoTest() {
		String pos = System.getProperty(P + "pos");
		if (pos != null) for (String p : pos.split(";")) positions.add(p.trim().replace(',', ' '));
		if (positions.isEmpty()) positions.add(null); // one shot wherever the player spawned
	}

	public static void registerIfEnabled() {
		if (System.getProperty(P + "world") == null && !Boolean.getBoolean(P + "quit")) return;
		AutoTest test = new AutoTest();
		ClientTickEvents.END_CLIENT_TICK.register(test::tick);
		EmrionShadersClient.LOG.info("Autotest enabled: world={} positions={} tag={}", test.world, test.positions, test.tag);
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
				if (mc.level != null && mc.player != null && mc.gui.screen() == null) go(State.SETUP);
			}
			case SETUP -> {
				if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
				String pos = positions.get(shot);
				command(mc, "gamemode spectator @a");
				command(mc, "gamerule advance_time false");
				command(mc, "gamerule advance_weather false");
				command(mc, "time set " + time);
				command(mc, "weather clear");
				if (pos != null) command(mc, "tp @a " + pos);
				go(State.WAIT_CHUNKS);
			}
			case WAIT_CHUNKS -> {
				boolean built = mc.levelRenderer.hasRenderedAllSections();
				if ((ticks >= waitTicks && built) || ticks >= 4 * waitTicks) {
					if (!built) EmrionShadersClient.LOG.warn("Autotest: chunk builds still pending, taking the screenshot anyway");
					go(State.SHOT);
				}
			}
			case SHOT -> {
				String name = "emrion-" + tag + (positions.size() > 1 ? "-" + shot : "") + ".png";
				Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
						msg -> EmrionShadersClient.LOG.info("Autotest screenshot: {}", msg.getString()));
				shot++;
				if (shot < positions.size()) go(State.SETUP);
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
