package dev.emrion.shaders.mod.fabric;

import dev.emrion.shaders.mod.platform.Platform;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.Minecraft;

public final class FabricPlatform implements Platform {
	@Override public Path gameDir() { return FabricLoader.getInstance().getGameDir(); }

	@Override public Path configDir() { return FabricLoader.getInstance().getConfigDir(); }

	@Override public boolean isModLoaded(String id) { return FabricLoader.getInstance().isModLoaded(id); }

	@Override
	public Map<String, String> modVersions() {
		return FabricLoader.getInstance().getAllMods().stream().map(m -> m.getMetadata())
				.collect(Collectors.toMap(ModMetadata::getId, m -> m.getVersion().getFriendlyString(), (a, b) -> a));
	}

	@Override public void onEndClientTick(Consumer<Minecraft> listener) { ClientTickEvents.END_CLIENT_TICK.register(listener::accept); }

	@Override
	public void onClientTagsLoaded(Runnable listener) {
		CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> { if (client) listener.run(); });
	}
}
