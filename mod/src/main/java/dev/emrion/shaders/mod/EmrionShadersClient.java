package dev.emrion.shaders.mod;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.emrion.shaders.mod.autotest.AutoTest;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.vertex.DebugVertex;
import dev.emrion.shaders.mod.pack.PackScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import dev.emrion.shaders.mod.vk.VulkanContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EmrionShadersClient implements ClientModInitializer {
	public static final String MOD_ID = "emberlight";
	public static final Logger LOG = LoggerFactory.getLogger("Emberlight");

	/** {@code config/emberlight.properties} (mod settings only; pack settings live in shaderpacks/<pack>.txt). */
	public static java.nio.file.Path configFile() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("emberlight.properties");
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

	@Override
	public void onInitializeClient() {
		AutoTest.registerIfEnabled();
		var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		KeyMapping packKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.emberlight.packs", InputConstants.KEY_O, category));
		// R: free in vanilla 26.3 (Iris uses it for the same purpose)
		KeyMapping reloadKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.emberlight.reload", InputConstants.KEY_R, category));
		// K: Iris's toggle key, free in vanilla 26.3
		KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.emberlight.toggle", InputConstants.KEY_K, category));
		KeyMapping profilerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.emberlight.profiler", InputConstants.KEY_F7, category));
		// unbound like Iris's; works only in debug mode and singleplayer
		KeyMapping wireframeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.emberlight.wireframe", InputConstants.UNKNOWN.getValue(), category));
		KeyMapping rtKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.emberlight.rt", InputConstants.UNKNOWN.getValue(), category));
		// F9: unbound in vanilla 26.3 (F1-F7 and F11 are taken)
		KeyMapping snapshotKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.emberlight.snapshot", InputConstants.KEY_F9, category));
		net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((d, ctx) -> d.register(
				net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("emberlight").then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
						.literal("snapshot").executes(c -> {
							dev.emrion.shaders.mod.debug.Snapshot.pendingNote = "";
							dev.emrion.shaders.mod.debug.Snapshot.delay = 3; // ticks: the chat screen is gone from the frame by then
							return 1;
						}).then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("note",
								com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(c -> {
							dev.emrion.shaders.mod.debug.Snapshot.pendingNote = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "note");
							dev.emrion.shaders.mod.debug.Snapshot.delay = 3;
							return 1;
						})))));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
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
			while (snapshotKey.consumeClick()) dev.emrion.shaders.mod.debug.Snapshot.take(mc);
			if (dev.emrion.shaders.mod.debug.Snapshot.delay > 0 && --dev.emrion.shaders.mod.debug.Snapshot.delay == 0)
				dev.emrion.shaders.mod.debug.Snapshot.take(mc, dev.emrion.shaders.mod.debug.Snapshot.pendingNote);
			while (profilerKey.consumeClick()) if (active) Profiler.visible = !Profiler.visible;
			while (reloadKey.consumeClick()) if (active && PackManager.selected() != null) {
				PackManager.reload(); // progress in the loading panel (LoadingHud)
			}
			if (active) PackManager.flushChat(mc);
		});
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "loading"),
				new dev.emrion.shaders.mod.gui.LoadingHud());
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
			if (!"Vulkan".equals(backend)) {
				LOG.warn("Emberlight needs the Vulkan backend but the game runs on {}. The mod stays inert. "
						+ "Set Options > Video > Graphics API to 'Prefer Vulkan' (or launch with --graphicsBackend vulkan) and restart.", backend);
				// once per start; not when Iris is installed (then OpenGL is a deliberate choice for Iris)
				if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) vulkanNotice(client);
				return;
			}
			active = true;
			ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
				boolean buildsDone = PackManager.shutdown();
				dev.emrion.shaders.mod.framegen.FrameGen.shutdown(VulkanContext.get()); // presenter off before NGX and the device go
				dev.emrion.shaders.mod.framegen.Reflex.shutdown(); // after the presenter: no more markers or presents
				dev.emrion.shaders.mod.upscale.VanillaUpscale.shutdown();
				dev.emrion.shaders.mod.upscale.Dlss.shutdown(VulkanContext.get()); // before MC destroys the device
				dev.emrion.shaders.mod.runtime.HorizonBox.close();
				dev.emrion.shaders.mod.debug.HitchLog.shutdown();
				dev.emrion.shaders.mod.rt.RtDebugView.shutdown();
				dev.emrion.shaders.mod.rt.RtScene.shutdown();
				if (dev.emrion.shaders.mod.compat.SodiumTerrain.LOADED) dev.emrion.shaders.mod.compat.sodium.SodiumShadow.shutdown();
				dev.emrion.shaders.mod.pbr.PbrTextures.closeDefaults();
				VulkanContext.onShutdown(buildsDone);
			});
			VulkanContext.get().pipelineCache(); // render thread: background pipeline builds only read these
			PackManager.init();
			if (DebugVertex.ENABLED) DebugVertex.init();
			LOG.info("Emberlight active on Vulkan ({})", RenderSystem.getDevice().getDeviceInfo().name());
		});
	}
}
