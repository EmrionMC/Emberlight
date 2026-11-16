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

/** Device-level RT setup: an optional feature set MC enables all-or-nothing at boot; {@link #enabled} records the outcome. */
public final class RtCapability {
	private RtCapability() {}

	/** Kill switch ({@code rt.device=false}): the device is created exactly as without RT. Read once, before the device exists. */
	public static final boolean REQUESTED = !"false".equals(System.getProperty("emrion.rt.device", configDevice()));

	private static String configDevice() {
		return dev.emrion.shaders.mod.EmrionShadersClient.readProperties(dev.emrion.shaders.mod.EmrionShadersClient.configFile()).getProperty("rt.device", "true").trim();
	}
	/** Debug view ({@code -Demrion.rt.debug=true}). */
	public static final boolean DEBUG = Boolean.getBoolean("emrion.rt.debug");

	public static final FeatureSet FEATURE_SET = new FeatureSet("Emberlight RT",
			Set.of(KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME, KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
					KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME),
			Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class), "accelerationStructure"),
					new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class), "rayQuery"),
					new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress")));

	/** Set once at device creation (VulkanBackendRtMixin), before VMA and any buffer exist. */
	public static volatile boolean enabled;
}
