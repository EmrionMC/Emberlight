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
	public static final String MOD_ID = "emrionshaders";
	public static final Logger LOG = LoggerFactory.getLogger("Emrion Shaders");

	/** True once the client started on the Vulkan backend. Every hook checks this and stays inert otherwise. */
	private static boolean active;

	public static boolean active() {
		return active;
	}

	private static void vulkanNotice(net.minecraft.client.Minecraft mc) {
		net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.gui.toastManager(),
				net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PACK_LOAD_FAILURE, Component.literal("Emrion Shaders needs Vulkan"),
				Component.literal("Options > Video > Graphics API: Prefer Vulkan, then restart"));
	}

	@Override
	public void onInitializeClient() {
		AutoTest.registerIfEnabled();
		var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		KeyMapping packKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.emrionshaders.packs", InputConstants.KEY_O, category));
		// R: free in vanilla 26.3 (Iris uses it for the same purpose)
		KeyMapping reloadKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.emrionshaders.reload", InputConstants.KEY_R, category));
		// K: Iris's toggle key, free in vanilla 26.3
		KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.emrionshaders.toggle", InputConstants.KEY_K, category));
		KeyMapping profilerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.emrionshaders.profiler", InputConstants.KEY_F7, category));
		// unbound like Iris's; works only in debug mode and singleplayer
		KeyMapping wireframeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.emrionshaders.wireframe", InputConstants.UNKNOWN.getValue(), category));
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
			while (profilerKey.consumeClick()) if (active) Profiler.visible = !Profiler.visible;
			while (reloadKey.consumeClick()) if (active && PackManager.selected() != null) {
				PackManager.reload();
				mc.gui.chatListener().handleSystemMessage(Component.literal("Emrion Shaders: reloading " + PackManager.selected() + "..."), false);
			}
			if (active) PackManager.flushChat(mc);
			// in game the conversion progress goes to the action bar (the pack screen has its own bar)
			if (active && PackManager.progress >= 0 && mc.gui.screen() == null)
				mc.gui.hud.setOverlayMessage(Component.literal(PackManager.status + " " + (int) (PackManager.progress * 100) + "%"), false);
		});
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
			if (!"Vulkan".equals(backend)) {
				LOG.warn("Emrion Shaders needs the Vulkan backend but the game runs on {}. The mod stays inert. "
						+ "Set Options > Video > Graphics API to 'Prefer Vulkan' (or launch with --graphicsBackend vulkan) and restart.", backend);
				// once per start; not when Iris is installed (then OpenGL is the user's choice for Iris)
				if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) vulkanNotice(client);
				return;
			}
			active = true;
			ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
				boolean buildsDone = PackManager.shutdown();
				dev.emrion.shaders.mod.runtime.HorizonBox.close();
				dev.emrion.shaders.mod.rt.RtDebugView.shutdown();
				dev.emrion.shaders.mod.rt.RtScene.shutdown();
				dev.emrion.shaders.mod.pbr.PbrTextures.closeDefaults();
				VulkanContext.onShutdown(buildsDone);
			});
			VulkanContext.get().pipelineCache(); // render thread: background pipeline builds only read these
			PackManager.init();
			if (DebugVertex.ENABLED) DebugVertex.init();
			LOG.info("Emrion Shaders active on Vulkan ({})", RenderSystem.getDevice().getDeviceInfo().name());
		});
	}
}
