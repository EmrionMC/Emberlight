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
 * qualifier, and gbuffers blend per draw buffer; wireframe needs fillModeNonSolid; MC enables none of these features; sparse atlases (opt-in, SparseAtlas) need the sparse features. Each is its own optional set, so a device lacking one still starts.
 */
@Mixin(VulkanFeatureSets.class)
public class VulkanFeatureSetsMixin {
	@Inject(method = "optionalFeatureSets", at = @At("RETURN"))
	private static void emrion$packFeatures(CallbackInfoReturnable<Set<FeatureSet>> cir) {
		for (String f : new String[] {"fragmentStoresAndAtomics", "vertexPipelineStoresAndAtomics", "shaderStorageImageWriteWithoutFormat",
				"shaderStorageImageReadWithoutFormat", "geometryShader", "independentBlend", "fillModeNonSolid", "sparseBinding", "sparseResidencyImage2D", "sparseResidencyAliased"}) {
			if (f.startsWith("sparse") && !dev.emrion.shaders.mod.vk.SparseAtlas.FEATURES.contains(f)) continue;
			cir.getReturnValue().add(new FeatureSet("Emrion " + f, Set.of(), Set.of(new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, f))));
		}
	}
}
