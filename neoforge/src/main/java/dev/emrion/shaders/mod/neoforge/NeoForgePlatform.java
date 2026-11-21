package dev.emrion.shaders.mod.neoforge;

import dev.emrion.shaders.mod.platform.Platform;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

public final class NeoForgePlatform implements Platform {
	public NeoForgePlatform() {
		// -Demberlight.* -> emrion.* (EmrionShadersClient.aliasProperties; Fabric runs it at pre-launch). The first Platform use is
		// SodiumMixinPlugin during mixin bootstrap, before any mixin reads a property. Inlined: loading EmrionShadersClient
		// this early would load Minecraft classes before their mixins apply.
		for (String k : System.getProperties().stringPropertyNames())
			if (k.startsWith("emberlight.")) System.setProperty("emrion." + k.substring("emberlight.".length()), System.getProperty(k));
	}

	@Override public Path gameDir() { return FMLPaths.GAMEDIR.get(); }

	@Override public Path configDir() { return FMLPaths.CONFIGDIR.get(); }

	// the loading mod list: already complete when mixin plugins ask (ModList is built later)
	@Override public boolean isModLoaded(String id) { return FMLLoader.getCurrent().getLoadingModList().getModFileById(id) != null; }

	@Override
	public Map<String, String> modVersions() {
		return FMLLoader.getCurrent().getLoadingModList().getMods().stream()
				.collect(Collectors.toMap(ModInfo::getModId, m -> m.getVersion().toString(), (a, b) -> a));
	}

	@Override public void onEndClientTick(Consumer<Minecraft> listener) { NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> listener.accept(Minecraft.getInstance())); }

	@Override
	public void onClientTagsLoaded(Runnable listener) {
		NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.ClientPacketReceived.class, e -> listener.run());
	}
}
