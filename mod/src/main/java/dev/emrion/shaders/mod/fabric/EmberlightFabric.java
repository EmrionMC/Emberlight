package dev.emrion.shaders.mod.fabric;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.gui.F3Info;
import dev.emrion.shaders.mod.gui.LoadingHud;
import dev.emrion.shaders.mod.mixin.DebugScreenEntriesAccessor;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;

/** Fabric client entrypoint: wires {@link EmrionShadersClient} to Fabric API. */
public final class EmberlightFabric implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EmrionShadersClient.init(KeyMappingHelper::registerKeyMapping);
		registerF3();
		ClientCommandRegistrationCallback.EVENT.register((d, ctx) -> d.register(EmrionShadersClient.command()));
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(EmrionShadersClient.MOD_ID, "loading"), LoadingHud::extractRenderState);
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			if (EmrionShadersClient.clientStarted(client)) ClientLifecycleEvents.CLIENT_STOPPING.register(c -> EmrionShadersClient.clientStopping());
		});
	}

	/** Runs before MC creates its debug entry list (loads the F3 profile), like NeoForge's RegisterDebugEntriesEvent. */
	private static void registerF3() {
		DebugScreenEntriesAccessor.emrion$register(F3Info.ID, new F3Info());
		Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> profiles = new HashMap<>(DebugScreenEntries.PROFILES);
		Map<Identifier, DebugScreenEntryStatus> def = new HashMap<>(profiles.get(DebugScreenProfile.DEFAULT));
		def.put(F3Info.ID, DebugScreenEntryStatus.IN_OVERLAY);
		profiles.put(DebugScreenProfile.DEFAULT, Map.copyOf(def));
		DebugScreenEntriesAccessor.emrion$setProfiles(Map.copyOf(profiles));
	}
}
