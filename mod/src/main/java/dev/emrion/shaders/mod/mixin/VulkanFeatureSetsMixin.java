package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Why: packs store to images and SSBOs from fragment and vertex stages and write images declared without a format
 * qualifier, and gbuffers blend per draw buffer and may have geometry/tessellation stages; wireframe needs fillModeNonSolid; MC enables none of these features; sparse atlases (opt-in, SparseAtlas) need the sparse features; optional RT needs the ray-query extensions (RtCapability). Each is its own optional set, so a device lacking one still starts.
 */
@Mixin(VulkanFeatureSets.class)
public class VulkanFeatureSetsMixin {
	@Inject(method = "optionalFeatureSets", at = @At("RETURN"))
	private static void emrion$packFeatures(CallbackInfoReturnable<Set<FeatureSet>> cir) {
		for (String f : new String[] {"fragmentStoresAndAtomics", "vertexPipelineStoresAndAtomics", "shaderStorageImageWriteWithoutFormat",
				"shaderStorageImageReadWithoutFormat", "geometryShader", "tessellationShader", "independentBlend", "fillModeNonSolid", "pipelineStatisticsQuery", "sparseBinding", "sparseResidencyImage2D", "sparseResidencyAliased"}) {
			if (f.startsWith("sparse") && !dev.emrion.shaders.mod.vk.SparseAtlas.FEATURES.contains(f)) continue;
			cir.getReturnValue().add(new FeatureSet("Emberlight " + f, Set.of(), Set.of(new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, f))));
		}
		if (dev.emrion.shaders.mod.debug.PipelineStats.ENABLED) cir.getReturnValue().add(dev.emrion.shaders.mod.debug.PipelineStats.FEATURE_SET);
		cir.getReturnValue().add(dev.emrion.shaders.mod.hdr.Hdr.FEATURE_SET); // VK_EXT_hdr_metadata for HDR output, where the driver has it
		cir.getReturnValue().add(dev.emrion.shaders.mod.framegen.Reflex.FEATURE_SET); // NVIDIA Reflex (VK_NV_low_latency2), where the driver has it
		if (dev.emrion.shaders.mod.rt.RtCapability.REQUESTED) {
			cir.getReturnValue().add(dev.emrion.shaders.mod.rt.RtCapability.FEATURE_SET);
			// VK_EXT_opacity_micromap, where the driver has it (-Demrion.rt.ommDevice=false: a device without it, for tests)
			if (!"false".equals(System.getProperty("emrion.rt.ommDevice"))) cir.getReturnValue().add(dev.emrion.shaders.mod.rt.RtOmm.FEATURE_SET);
			cir.getReturnValue().add(dev.emrion.shaders.mod.rt.RtRaygen.FEATURE_SET); // RT pipelines, for native "raygen" passes
		}
		// NGX's device extensions, only when the DLSS files are installed
		var dlss = dev.emrion.shaders.mod.upscale.Dlss.deviceExtensions();
		if (!dlss.isEmpty()) cir.getReturnValue().add(new FeatureSet(dev.emrion.shaders.mod.upscale.Dlss.FEATURE_SET, dlss,
				Set.of(new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress"))));
		cir.getReturnValue().addAll(dev.emrion.shaders.mod.upscale.Xess.featureSets()); // Intel XeSS (Windows), only when its runtime is installed
	}
}
