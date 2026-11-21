package dev.emrion.shaders.mod.platform;

import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/**
 * What the shared code needs from the mod loader. One implementation per loader module (fabric: mod/, neoforge/), found
 * with {@link ServiceLoader} (META-INF/services), so it also works before the loader calls our entrypoint (mixin plugins).
 */
public interface Platform {
	Platform INSTANCE = ServiceLoader.load(Platform.class, Platform.class.getClassLoader()).findFirst()
			.orElseThrow(() -> new IllegalStateException("No Emberlight platform implementation (META-INF/services)"));

	static Platform get() {
		return INSTANCE;
	}

	Path gameDir();

	Path configDir();

	boolean isModLoaded(String id);

	/** Mod id to version, every loaded mod (snapshots). */
	Map<String, String> modVersions();

	/** Runs every client tick, at the end of the tick. May be called at any time. */
	void onEndClientTick(Consumer<Minecraft> listener);

	/** Runs whenever the client receives tags from the server. May be called at any time. */
	void onClientTagsLoaded(Runnable listener);
}
