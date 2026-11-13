package dev.emrion.shaders.mod.rt;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import java.util.Set;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRDeferredHostOperations;
import org.lwjgl.vulkan.KHRRayQuery;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;

/**
 * Device-level RT setup (RAYTRACING.md §0.4): the "Emrion RT" optional feature set, requested at boot unless
 * {@code -Demrion.rt.device=false}. MC enables it all-or-nothing when the GPU supports it; {@link #enabled} records the outcome.
 */
public final class RtCapability {
	private RtCapability() {}

	/**
	 * Kill switch ({@code -Demrion.rt.device=false} or {@code rt.device=false} in config/emrionshaders.properties): false restores
	 * today's device exactly (no extensions, no BDA flag, no extra usage bits). Read once, before the device exists.
	 */
	public static final boolean REQUESTED = !"false".equals(System.getProperty("emrion.rt.device", configDevice()));

	private static String configDevice() {
		var p = new java.util.Properties();
		try (var r = java.nio.file.Files.newBufferedReader(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("emrionshaders.properties"))) {
			p.load(r);
		} catch (java.io.IOException ignored) {
			// no config yet
		}
		return p.getProperty("rt.device", "true").trim();
	}
	/** RT-1 debug view ({@code -Demrion.rt.debug=true}): the only thing that creates an {@link RtScene} in this step. */
	public static final boolean DEBUG = Boolean.getBoolean("emrion.rt.debug");

	public static final FeatureSet FEATURE_SET = new FeatureSet("Emrion RT",
			Set.of(KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME, KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
					KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME),
			Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class), "accelerationStructure"),
					new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class), "rayQuery"),
					new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress")));

	/** Set once at device creation (VulkanBackendRtMixin), before VMA and any buffer exist. */
	public static volatile boolean enabled;
}
