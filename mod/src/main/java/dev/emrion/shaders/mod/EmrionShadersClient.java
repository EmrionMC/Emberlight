package dev.emrion.shaders.mod;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EmrionShadersClient implements ClientModInitializer {
	public static final String MOD_ID = "emrionshaders";
	public static final Logger LOG = LoggerFactory.getLogger("Emrion Shaders");

	@Override
	public void onInitializeClient() {
		LOG.info("Emrion Shaders initializing");
	}
}
