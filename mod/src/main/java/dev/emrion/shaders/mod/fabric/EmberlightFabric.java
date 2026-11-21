package dev.emrion.shaders.mod.fabric;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.gui.LoadingHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

/** Fabric client entrypoint: wires {@link EmrionShadersClient} to Fabric API. */
public final class EmberlightFabric implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EmrionShadersClient.init(KeyMappingHelper::registerKeyMapping);
		ClientCommandRegistrationCallback.EVENT.register((d, ctx) -> d.register(EmrionShadersClient.command()));
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(EmrionShadersClient.MOD_ID, "loading"), LoadingHud::extractRenderState);
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			if (EmrionShadersClient.clientStarted(client)) ClientLifecycleEvents.CLIENT_STOPPING.register(c -> EmrionShadersClient.clientStopping());
		});
	}
}
