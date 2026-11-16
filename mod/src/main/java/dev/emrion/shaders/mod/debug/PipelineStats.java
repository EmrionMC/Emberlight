package dev.emrion.shaders.mod.debug;

import static org.lwjgl.vulkan.KHRPipelineExecutableProperties.*;

import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import dev.emrion.shaders.mod.EmrionShadersClient;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR;
import org.lwjgl.vulkan.VkPipelineExecutableInfoKHR;
import org.lwjgl.vulkan.VkPipelineExecutablePropertiesKHR;
import org.lwjgl.vulkan.VkPipelineExecutableStatisticKHR;
import org.lwjgl.vulkan.VkPipelineInfoKHR;

/** Dev only ({@code -Demrion.debug.pipelineStats=true}): logs the driver's per-stage statistics (registers, spills) of each pack pipeline. */
public final class PipelineStats {
	public static final boolean ENABLED = Boolean.getBoolean("emrion.debug.pipelineStats");
	public static final FeatureSet FEATURE_SET = new FeatureSet("Emberlight pipelineExecutableInfo", Set.of(VK_KHR_PIPELINE_EXECUTABLE_PROPERTIES_EXTENSION_NAME),
			Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR.class), "pipelineExecutableInfo")));
	/** The device has the extension enabled (VulkanBackendRtMixin). */
	public static volatile boolean active;

	private PipelineStats() {}

	public static int flags() {
		return active ? VK_PIPELINE_CREATE_CAPTURE_STATISTICS_BIT_KHR : 0;
	}

	public static void log(VkDevice dev, long pipeline, String label) {
		if (!active) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkPipelineInfoKHR.calloc(stack).sType$Default().pipeline(pipeline);
			var n = stack.mallocInt(1);
			vkGetPipelineExecutablePropertiesKHR(dev, info, n, null);
			var props = VkPipelineExecutablePropertiesKHR.calloc(n.get(0), stack);
			for (int i = 0; i < props.capacity(); i++) props.get(i).sType$Default();
			vkGetPipelineExecutablePropertiesKHR(dev, info, n, props);
			for (int e = 0; e < props.capacity(); e++) {
				var ei = VkPipelineExecutableInfoKHR.calloc(stack).sType$Default().pipeline(pipeline).executableIndex(e);
				var m = stack.mallocInt(1);
				vkGetPipelineExecutableStatisticsKHR(dev, ei, m, null);
				var stats = VkPipelineExecutableStatisticKHR.calloc(m.get(0), stack);
				for (int i = 0; i < stats.capacity(); i++) stats.get(i).sType$Default();
				vkGetPipelineExecutableStatisticsKHR(dev, ei, m, stats);
				var sb = new StringBuilder();
				for (var s : stats) {
					sb.append(' ').append(s.nameString()).append('=');
					switch (s.format()) {
						case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_BOOL32_KHR -> sb.append(s.value().b32());
						case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_INT64_KHR -> sb.append(s.value().i64());
						case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_UINT64_KHR -> sb.append(s.value().u64());
						default -> sb.append(s.value().f64());
					}
				}
				EmrionShadersClient.LOG.info("Pipeline stats {} [{}]{}", label, props.get(e).nameString(), sb);
			}
		}
	}
}
