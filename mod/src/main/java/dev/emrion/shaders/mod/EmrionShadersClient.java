package dev.emrion.shaders.mod;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.emrion.shaders.mod.autotest.AutoTest;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.pack.PackScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
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

	@Override
	public void onInitializeClient() {
		AutoTest.registerIfEnabled();
		KeyMapping packKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.emrionshaders.packs", InputConstants.KEY_O,
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"))));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (packKey.consumeClick()) if (active) mc.gui.setScreen(new PackScreen(mc.gui.screen()));
		});
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
			if (!"Vulkan".equals(backend)) {
				LOG.warn("Emrion Shaders needs the Vulkan backend but the game runs on {}. The mod stays inert. "
						+ "Set Options > Video > Graphics API to 'Prefer Vulkan' (or launch with --graphicsBackend vulkan) and restart.", backend);
				return;
			}
			active = true;
			ClientLifecycleEvents.CLIENT_STOPPING.register(c -> VulkanContext.onShutdown());
			VulkanContext.get().pipelineCache(); // render thread: background pipeline builds only read these
			PackManager.init();
			LOG.info("Emrion Shaders active on Vulkan ({})", RenderSystem.getDevice().getDeviceInfo().name());
		});
	}
}
