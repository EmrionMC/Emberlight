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
 * emrion.autotest.settle.frames=200 frames
 * emrion.autotest.benchmark.seconds=N   frame-time capture after the screenshots (at the last position)
 * emrion.autotest.quit=true         leave the world (saving it) and exit
 * emrion.autotest.hud=true          keep the HUD (and with it the first-person hand) visible
 * emrion.autotest.optionScreens=,A   after the shots: open the shader options screen ("" = main, else screen.A) and take
 *                                  &lt;tag&gt;-options[-A].png of each
 * emrion.autotest.set=NAME=value[;NAME=value]   then apply these option values like the GUI's Apply (settings file +
 *                                  reconvert + swap), wait for the swap and take &lt;tag&gt;-set.png
 * emrion.autotest.hotreload=N      then reload the pack N times (key R), each waiting for the swap; logs GPU memory (VMA)
 * emrion.autotest.script=step;step  then run these steps (robustness tests), one after the other:
 *     wait S | size WxH | fullscreen on|off | gui N | cmd COMMAND | shot NAME (&lt;tag&gt;-NAME.png) | mem (log FPS and VMA budget)
 *     pack off|on (on waits until the pack renders) | reload (waits for the swap) | leave | join | hud | f3 on|off
 *     screen pause|inventory|chat|packs|none | key KEY [ctrl] (into the open screen) | drop PATH[;PATH] (onto the open screen) | use down|up (hold the use key) | press KEY (key.keyboard.KEY) | irisapi (logs what the Iris API reports) | repeat N { steps } (no nesting)
 * </pre>
 * With only {@code quit=true} the game exits as soon as the title screen is up (smoke test).
 */
public final class AutoTest {
	private static final String P = "emrion.autotest.";
	private static final int TPS = 20;
	/** -Demrion.autotest.profile=true: the Profiler accumulates over the benchmark window and logs it (Vulkan only). */
	private static final boolean PROFILE = Boolean.getBoolean(P + "profile");

	private enum State { WAIT_TITLE, WAIT_WORLD, RELOAD, SETUP, WAIT_CHUNKS, SETTLE, SHOT, BENCHMARK, OPTIONS, SET, HOT_RELOAD, SCRIPT, LEAVE, QUIT, DONE }

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
	private final @Nullable List<String> optionScreens = System.getProperty(P + "optionScreens") == null ? null
			: Arrays.asList(System.getProperty(P + "optionScreens").split(",", -1));
	private final String set = System.getProperty(P + "set", "");
	private int hotReloads = Integer.getInteger(P + "hotreload", 0);
	private int optionShot, swapsBefore = -1, swapTick;
	private final List<String> script = expand(splitCommands(System.getProperty(P + "script", "")));
	private int step;
	private @Nullable String scriptPack = System.getProperty("emrion.pack");

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
	private boolean commandsRun;

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
				// teleport first: the scene commands (summon, setblock) fail silently in chunks that are not loaded yet
				if (scene.pos() != null) command(mc, "tp @a " + scene.pos());
				commandsRun = scene.commands().isEmpty();
				go(State.WAIT_CHUNKS);
			}
			case WAIT_CHUNKS -> {
				boolean built = mc.levelRenderer.hasRenderedAllSections();
				if ((ticks >= waitTicks && built) || ticks >= 4 * waitTicks) {
					if (!commandsRun) { // chunks are loaded now; run the commands, then wait again for the rebuilt sections
						scenes.get(shot).commands().forEach(c -> command(mc, c));
						commandsRun = true;
						go(State.WAIT_CHUNKS);
						return;
					}
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
				else go(State.OPTIONS);
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
					if (PROFILE && EmrionShadersClient.active()) Profiler.benchmarkStart();
				}
				if (ticks >= (benchmarkSeconds + 1) * TPS) {
					FrameTimeLogger.INSTANCE.stopAndDump(mc.gameDirectory.toPath().resolve("emrion-benchmark-" + tag + ".csv"));
					if (PROFILE && EmrionShadersClient.active()) Profiler.benchmarkEnd();
					mc.options.enableVsync().set(vsync);
					mc.options.framerateLimit().set(fpsLimit);
					go(State.OPTIONS);
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
					PackManager.applyOptions(session.pending);
					return;
				}
				if (PackManager.swaps == swapsBefore) {
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
				if (ticks == 1) EmrionShadersClient.LOG.info("Autotest script [{}/{}]: {}", step + 1, script.size(), script.get(step));
				if (scriptStep(mc, script.get(step))) {
					step++;
					go(State.SCRIPT);
				} else if (ticks > 300 * TPS) throw new IllegalStateException("autotest: script step never finished: " + script.get(step));
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
				return ticks >= Double.parseDouble(arg) * TPS;
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
			case "shot" -> {
				shoot(mc, tag + "-" + arg + ".png");
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
			case "pack" -> {
				if (first) {
					if (scriptPack == null) scriptPack = PackManager.selected();
					PackManager.select(arg.equals("off") ? null : scriptPack, false);
				}
				// "on" is done once the pack renders, or when it failed (selection cleared)
				return arg.equals("off") ? !PackManager.running() || ticks > 2 : PackManager.running() || PackManager.selected() == null;
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
					default -> null;
				});
				return true;
			}
			case "press" -> { // a key as if typed, e.g. "press o" (key.keyboard.o)
				net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard." + arg));
				return true;
			}
			case "key" -> { // a key typed into the open screen, e.g. "key f1", "key d ctrl"
				String[] k = arg.split(" ");
				int code = com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard." + k[0]).getValue();
				int mods = k.length > 1 && k[1].equals("ctrl") ? com.mojang.blaze3d.platform.InputConstants.MOD_CONTROL : 0;
				if (mc.gui.screen() != null) mc.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(code, 0, mods));
				return true;
			}
			case "drop" -> { // files dropped onto the open screen
				if (mc.gui.screen() != null) mc.gui.screen().onFilesDrop(java.util.Arrays.stream(arg.split(";")).map(java.nio.file.Path::of).toList());
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
			case "use" -> {
				mc.options.keyUse.setDown(arg.equals("down"));
				return true;
			}
			default -> throw new IllegalArgumentException("autotest: unknown script step " + s);
		}
	}

	private static void shoot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
				msg -> EmrionShadersClient.LOG.info("Autotest screenshot: {}", msg.getString()));
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
			}
			return sb.toString();
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
