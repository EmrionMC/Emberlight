package dev.emrion.shaders.mod.neoforge;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.gui.F3Info;
import dev.emrion.shaders.mod.gui.LoadingHud;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge client entrypoint: wires {@link EmrionShadersClient} to NeoForge's events (the Fabric module does the same with Fabric API). */
@Mod(value = EmrionShadersClient.MOD_ID, dist = Dist.CLIENT)
public final class EmberlightNeoForge {
	public EmberlightNeoForge(IEventBus modBus) {
		List<KeyMapping> keys = new ArrayList<>();
		EmrionShadersClient.init(k -> {
			keys.add(k);
			return k;
		});
		modBus.addListener(RegisterKeyMappingsEvent.class, e -> keys.forEach(e::register));
		modBus.addListener(RegisterGuiLayersEvent.class,
				e -> e.registerAboveAll(Identifier.fromNamespaceAndPath(EmrionShadersClient.MOD_ID, "loading"), LoadingHud::extractRenderState));
		modBus.addListener(RegisterDebugEntriesEvent.class, e -> {
			e.register(F3Info.ID, new F3Info());
			e.includeInProfile(F3Info.ID, DebugScreenProfile.DEFAULT, DebugScreenEntryStatus.IN_OVERLAY);
		});
		NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, e -> e.getDispatcher().register(EmrionShadersClient.command()));
		NeoForge.EVENT_BUS.addListener(ClientStartedEvent.class, e -> {
			if (EmrionShadersClient.clientStarted(e.getClient())) NeoForge.EVENT_BUS.addListener(ClientStoppingEvent.class, x -> EmrionShadersClient.clientStopping());
		});
	}
}
