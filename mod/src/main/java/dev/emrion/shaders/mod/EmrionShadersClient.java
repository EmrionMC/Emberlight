package dev.emrion.shaders.mod;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.emrion.shaders.mod.autotest.AutoTest;
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
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
			if (!"Vulkan".equals(backend)) {
				LOG.warn("Emrion Shaders needs the Vulkan backend but the game runs on {}. The mod stays inert. "
						+ "Set Options > Video > Graphics API to 'Prefer Vulkan' (or launch with --graphicsBackend vulkan) and restart.", backend);
				return;
			}
			active = true;
			ClientLifecycleEvents.CLIENT_STOPPING.register(c -> VulkanContext.onShutdown());
			LOG.info("Emrion Shaders active on Vulkan ({})", RenderSystem.getDevice().getDeviceInfo().name());
		});
	}
}
