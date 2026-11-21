package dev.emrion.shaders.mod;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.emrion.shaders.mod.autotest.AutoTest;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.photo.PhotoMode;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.emrion.shaders.mod.pack.PackScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import dev.emrion.shaders.mod.vk.VulkanContext;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.emrion.shaders.mod.platform.Platform;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The loader-independent part of the client entrypoint; the loader modules (mod/ for Fabric, neoforge/) wire it to their events. */
public final class EmrionShadersClient {
	public static final String MOD_ID = "emberlight";
	public static final Logger LOG = LoggerFactory.getLogger("Emberlight");

	/** {@code config/emberlight.properties} (mod settings only; pack settings live in shaderpacks/<pack>.txt). */
	public static java.nio.file.Path configFile() {
		return dev.emrion.shaders.mod.platform.Platform.get().configDir().resolve("emberlight.properties");
	}

	private static boolean configWarned;

	/** Reads a properties file; missing = empty, malformed (e.g. a bad {@code \\u} escape) = the keys before the bad line, warned once. */
	public static java.util.Properties readProperties(java.nio.file.Path f) {
		var p = new java.util.Properties();
		try (var r = java.nio.file.Files.newBufferedReader(f)) {
			p.load(r);
		} catch (java.nio.file.NoSuchFileException ignored) {
			// no config yet: defaults
		} catch (java.io.IOException | IllegalArgumentException e) {
			if (!configWarned) LOG.warn("Could not read {}, using defaults", f, e);
			configWarned = true;
		}
		return p;
	}

	/**
	 * The code reads the old {@code -Demrion.*} names; every {@code -Demberlight.*} property is copied to its {@code emrion.*}
	 * name (the new name wins). Called at pre-launch, before anything reads them.
	 */
	public static void aliasProperties() {
		for (String k : System.getProperties().stringPropertyNames())
			if (k.startsWith("emberlight.")) System.setProperty("emrion." + k.substring("emberlight.".length()), System.getProperty(k));
	}

	/** True once the client started on the Vulkan backend. Every hook checks this and stays inert otherwise. */
	private static boolean active;

	public static boolean active() {
		return active;
	}

	private static void vulkanNotice(net.minecraft.client.Minecraft mc) {
		net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.gui.toastManager(),
				net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PACK_LOAD_FAILURE, Component.literal("Emberlight needs Vulkan"),
				Component.literal("Options > Video > Graphics API: Prefer Vulkan, then restart"));
	}

	/** Client init: autotest, key mappings ({@code registerKey} registers one with the loader) and the tick handler. */
	public static void init(UnaryOperator<KeyMapping> registerKey) {
		AutoTest.registerIfEnabled();
		var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		KeyMapping packKey = registerKey.apply(new KeyMapping("key.emberlight.packs", InputConstants.KEY_O, category));
		// R: free in vanilla 26.3 (Iris uses it for the same purpose)
		KeyMapping reloadKey = registerKey.apply(new KeyMapping("key.emberlight.reload", InputConstants.KEY_R, category));
		// K: Iris's toggle key, free in vanilla 26.3
		KeyMapping toggleKey = registerKey.apply(new KeyMapping("key.emberlight.toggle", InputConstants.KEY_K, category));
		KeyMapping profilerKey = registerKey.apply(new KeyMapping("key.emberlight.profiler", InputConstants.KEY_F7, category));
		// unbound like Iris's; works only in debug mode and singleplayer
		KeyMapping wireframeKey = registerKey.apply(new KeyMapping("key.emberlight.wireframe", InputConstants.UNKNOWN.getValue(), category));
		KeyMapping rtKey = registerKey.apply(new KeyMapping("key.emberlight.rt", InputConstants.UNKNOWN.getValue(), category));
		// F9: unbound in vanilla 26.3 (F1-F7 and F11 are taken)
		KeyMapping photoKey = registerKey.apply(new KeyMapping("key.emberlight.photo", InputConstants.UNKNOWN.getValue(), category));
		KeyMapping snapshotKey = registerKey.apply(new KeyMapping("key.emberlight.snapshot", InputConstants.KEY_F9, category));
		Platform.get().onEndClientTick(mc -> {
			while (packKey.consumeClick()) mc.gui.setScreen(new PackScreen(mc.gui.screen())); // on OpenGL it explains how to get Vulkan
			while (toggleKey.consumeClick()) if (active) {
				boolean on = PackManager.selected() != null && !PackManager.enabled();
				if (PackManager.selected() != null) PackManager.setEnabled(on);
				mc.gui.hud.setOverlayMessage(Component.literal(PackManager.selected() == null ? "Shaders: no pack selected" : "Shaders: " + (on ? "on" : "off")), false);
			}
			while (wireframeKey.consumeClick()) if (active) {
				String msg;
				if (!PackManager.debugMode()) msg = "Wireframe needs debug mode (Ctrl+D in the shader pack screen)";
				else if (!mc.isLocalServer()) msg = "No cheating; wireframe only in singleplayer!"; // Iris's wording
				else if (!VulkanContext.get().fillModeNonSolid) msg = "Wireframe: not supported by this GPU (fillModeNonSolid)";
				else {
					PackManager.setWireframe(!dev.emrion.shaders.mod.pipeline.GeometryPipelines.wireframe);
					msg = "Wireframe: " + (dev.emrion.shaders.mod.pipeline.GeometryPipelines.wireframe ? "on" : "off");
				}
				mc.gui.hud.setOverlayMessage(Component.literal(msg), false);
			}
			if (dev.emrion.shaders.mod.pipeline.GeometryPipelines.wireframe && !mc.isLocalServer()) PackManager.setWireframe(false); // left singleplayer
			while (rtKey.consumeClick()) if (active) {
				String msg;
				if (!dev.emrion.shaders.mod.rt.RtCapability.enabled) msg = "RT: not supported by this GPU";
				else {
					dev.emrion.shaders.mod.rt.RtConfig.enabled = !dev.emrion.shaders.mod.rt.RtConfig.enabled;
					PackManager.applyRt();
					var req = dev.emrion.shaders.mod.rt.RtConfig.request();
					msg = !dev.emrion.shaders.mod.rt.RtConfig.enabled ? "RT off" : req.isEmpty() ? "RT on, but no effect selected"
							: "RT on: " + dev.emrion.shaders.mod.rt.RtConfig.describe();
				}
				mc.gui.hud.setOverlayMessage(Component.literal(msg), false);
			}
			while (photoKey.consumeClick()) PhotoMode.request(PhotoMode.scale, PhotoMode.samples);
			PhotoMode.tick(mc);
			while (snapshotKey.consumeClick()) dev.emrion.shaders.mod.debug.Snapshot.take(mc);
			if (dev.emrion.shaders.mod.debug.Snapshot.delay > 0 && --dev.emrion.shaders.mod.debug.Snapshot.delay == 0)
				dev.emrion.shaders.mod.debug.Snapshot.take(mc, dev.emrion.shaders.mod.debug.Snapshot.pendingNote);
			while (profilerKey.consumeClick()) if (active) Profiler.visible = !Profiler.visible;
			while (reloadKey.consumeClick()) if (active && PackManager.selected() != null) {
				PackManager.reload(); // progress in the loading panel (LoadingHud)
			}
			if (active) PackManager.flushChat(mc);
		});
	}

	/** The client command {@code /emberlight snapshot [note]}, {@code /emberlight photo [scale [samples]]}. */
	public static <S> LiteralArgumentBuilder<S> command() {
		return LiteralArgumentBuilder.<S>literal("emberlight").then(LiteralArgumentBuilder.<S>literal("snapshot").executes(c -> {
				dev.emrion.shaders.mod.debug.Snapshot.pendingNote = "";
				dev.emrion.shaders.mod.debug.Snapshot.delay = 3; // ticks: the chat screen is gone from the frame by then
				return 1;
			}).then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, String>argument("note",
					com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(c -> {
				dev.emrion.shaders.mod.debug.Snapshot.pendingNote = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "note");
				dev.emrion.shaders.mod.debug.Snapshot.delay = 3;
				return 1;
			}))).then(LiteralArgumentBuilder.<S>literal("photo").executes(c -> {
				PhotoMode.request(PhotoMode.scale, PhotoMode.samples);
				return 1;
			}).then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, Integer>argument("scale", IntegerArgumentType.integer(1, 2)).executes(c -> {
				PhotoMode.request(IntegerArgumentType.getInteger(c, "scale"), PhotoMode.samples);
				return 1;
			}).then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, Integer>argument("samples", IntegerArgumentType.integer(1, 4096)).executes(c -> {
				PhotoMode.request(IntegerArgumentType.getInteger(c, "scale"), IntegerArgumentType.getInteger(c, "samples"));
				return 1;
			}))));
	}

	/** The client finished starting: activates the mod on Vulkan. False (inert) on another backend. */
	public static boolean clientStarted(net.minecraft.client.Minecraft client) {
		String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
		if (!"Vulkan".equals(backend)) {
			LOG.warn("Emberlight needs the Vulkan backend but the game runs on {}. The mod stays inert. "
					+ "Set Options > Video > Graphics API to 'Prefer Vulkan' (or launch with --graphicsBackend vulkan) and restart.", backend);
			// once per start; not when Iris is installed (then OpenGL is a deliberate choice for Iris)
			if (!Platform.get().isModLoaded("iris")) vulkanNotice(client);
			return false;
		}
		active = true;
		VulkanContext.get().pipelineCache(); // render thread: background pipeline builds only read these
		PackManager.init();
		LOG.info("Emberlight active on Vulkan ({})", RenderSystem.getDevice().getDeviceInfo().name());
		return true;
	}

	/** The client is stopping (registered only after {@link #clientStarted} returned true). */
	public static void clientStopping() {
		boolean buildsDone = PackManager.shutdown();
		dev.emrion.shaders.mod.framegen.FrameGen.shutdown(VulkanContext.get()); // presenter off before NGX and the device go
		dev.emrion.shaders.mod.framegen.Reflex.shutdown(); // after the presenter: no more markers or presents
		dev.emrion.shaders.mod.upscale.VanillaUpscale.shutdown();
		dev.emrion.shaders.mod.hdr.Hdr.shutdown();
		dev.emrion.shaders.mod.upscale.Dlss.shutdown(VulkanContext.get()); // before MC destroys the device
		dev.emrion.shaders.mod.runtime.HorizonBox.close();
		dev.emrion.shaders.mod.debug.HitchLog.shutdown();
		dev.emrion.shaders.mod.debug.TerrainStats.shutdown();
		dev.emrion.shaders.mod.pipeline.TerrainPrepass.shutdown();
		dev.emrion.shaders.mod.rt.RtDebugView.shutdown();
		dev.emrion.shaders.mod.rt.RtScene.shutdown();
		dev.emrion.shaders.mod.compat.sodium.SodiumShadow.shutdown();
		dev.emrion.shaders.mod.pbr.PbrTextures.closeDefaults();
		VulkanContext.onShutdown(buildsDone);
	}
}
