package dev.emrion.shaders.mod.compat;

import dev.emrion.shaders.mod.EmrionShadersClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Pre-launch: when Iris is NOT installed, puts our copy of the Iris API (net.irisshaders.iris.api.v0, LGPL-3.0-only,
 * nested as {@code emrion-irisapi.jar}) on the game class path, so mods that look for the API class see ours
 * ({@link EmrionIrisApi}). With Iris installed nothing is added and Iris's own API is the only one.
 * We never claim the "iris" mod id: mods that see it mixin into and call Iris internals (README, Iris API).
 */
public final class IrisApiShim implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		FabricLoader loader = FabricLoader.getInstance();
		if (loader.isModLoaded("iris")) {
			EmrionShadersClient.LOG.info("Iris is installed: not providing the Iris API");
			return;
		}
		try {
			Path src = loader.getModContainer(EmrionShadersClient.MOD_ID).orElseThrow().findPath("emrion-irisapi.jar").orElseThrow();
			// the class loader needs a real file; copy only when it differs (another running instance may hold it open)
			Path dst = loader.getGameDir().resolve("emrionshaders").resolve("emrion-irisapi.jar");
			Files.createDirectories(dst.getParent());
			if (!Files.exists(dst) || Files.mismatch(src, dst) != -1) Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
			// shortcut: loader-internal API (stable since loader 0.12); replace if Fabric ever offers a public one
			net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().addToClassPath(dst);
			EmrionShadersClient.LOG.info("Providing the Iris API (v0) for other mods, backed by Emrion Shaders");
		} catch (Exception e) {
			EmrionShadersClient.LOG.warn("Could not provide the Iris API; mods asking for it will see no shaders", e);
		}
	}
}
