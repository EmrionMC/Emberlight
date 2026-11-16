package dev.emrion.shaders.mod.rt;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.uniforms.CapturedProjection;
import dev.emrion.shaders.mod.vk.Barriers;
import dev.emrion.shaders.mod.vk.ComputePipelines;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.GraphicsPipelineBuilder;
import dev.emrion.shaders.mod.vk.RawCommands;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.RawPass;
import dev.emrion.shaders.mod.vk.ShaderModules;
import dev.emrion.shaders.mod.vk.VulkanContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

/** Debug tool ({@code -Demrion.rt.debug=true}): replaces the image with traced face normals, to check the TLAS against the raster image. */
public final class RtDebugView {
	private static @Nullable RtDebugView instance;

	private final VulkanContext ctx = VulkanContext.get();
	private final long cset, gset, computeLayout, copyLayout, computePipeline, copyPipeline;
	private @Nullable RawImage image;

	private RtDebugView() {
		cset = new Descriptors.LayoutBuilder()
				.add(0, KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, VK_SHADER_STAGE_COMPUTE_BIT)
				.add(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT)
				.add(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT)
				.add(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT).build(ctx, true);
		gset = new Descriptors.LayoutBuilder().add(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT).build(ctx, true);
		computeLayout = Descriptors.pipelineLayout(ctx, 80, cset);
		copyLayout = Descriptors.pipelineLayout(ctx, 0, gset);
		long cache = ctx.pipelineCache().handle;
		long cs = module("rt_debug.comp"), vs = module("fullscreen.vert"), fs = module("debug_copy.frag");
		computePipeline = ComputePipelines.create(ctx, cs, computeLayout, cache);
		copyPipeline = new GraphicsPipelineBuilder().stage(VK_SHADER_STAGE_VERTEX_BIT, vs).stage(VK_SHADER_STAGE_FRAGMENT_BIT, fs)
				.layout(copyLayout).colors(new int[] {VK_FORMAT_R8G8B8A8_UNORM}).build(ctx, cache);
		for (long m : new long[] {cs, vs, fs}) ShaderModules.destroy(ctx, m);
	}

	private long module(String name) {
		return ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/" + name + ".spv"));
	}

	public static void shutdown() {
		RtDebugView v = instance;
		if (v == null) return;
		instance = null;
		if (v.image != null) v.image.destroyLater();
		v.ctx.destroyLater(() -> {
			vkDestroyPipeline(v.ctx.vk, v.computePipeline, null);
			vkDestroyPipeline(v.ctx.vk, v.copyPipeline, null);
			vkDestroyPipelineLayout(v.ctx.vk, v.computeLayout, null);
			vkDestroyPipelineLayout(v.ctx.vk, v.copyLayout, null);
			vkDestroyDescriptorSetLayout(v.ctx.vk, v.cset, null);
			vkDestroyDescriptorSetLayout(v.ctx.vk, v.gset, null);
		});
	}

	/** GameRenderer.renderLevel TAIL, only with the debug switch. */
	public static void onRenderLevelEnd() {
		RtScene scene = RtScene.get();
		if (scene == null || scene.tlas() == 0) return;
		if (instance == null) instance = new RtDebugView();
		instance.run(scene);
	}

	private void run(RtScene scene) {
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		int w = main.width, h = main.height;
		if (image == null || image.width != w || image.height != h) {
			if (image != null) image.destroyLater();
			image = new RawImage(ctx, VK_FORMAT_R8G8B8A8_UNORM, w, h, 1, 1, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "rt debug view");
		}
		var cs = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		Matrix4f invViewProj = new Matrix4f(CapturedProjection.LEVEL).mul(cs.viewRotationMatrix).invert();
		long mainView = ((VulkanGpuTextureView) main.getColorTextureView()).vkImageView();
		long atlasView = ((VulkanGpuTextureView) Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView()).vkImageView();
		long nearest = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)).vkSampler();

		VkCommandBuffer cb = RawCommands.begin(ctx);
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var writes = VkWriteDescriptorSet.calloc(4, stack);
			var as = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(scene.tlas()));
			writes.get(0).sType$Default().pNext(as.address()).dstBinding(0).descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
			writes.get(1).sType$Default().dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
					.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(image.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			var table = scene.sectionTable();
			writes.get(2).sType$Default().dstBinding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
					.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(table.handle).offset(0).range(VK_WHOLE_SIZE));
			writes.get(3).sType$Default().dstBinding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
					.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(nearest).imageView(atlasView).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, computeLayout, 0, writes);
			var pc = stack.malloc(80);
			invViewProj.get(0, pc);
			pc.putFloat(64, w).putFloat(68, h).putFloat(72, 0).putFloat(76, 0);
			vkCmdPushConstants(cb, computeLayout, VK_SHADER_STAGE_ALL, 0, pc);
		}
		vkCmdDispatch(cb, (w + 7) / 8, (h + 7) / 8, 1);
		Barriers.storageToRead(cb);
		RawPass.begin(cb, w, h, mainView);
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, copyPipeline);
		RawPass.pushImages(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, copyLayout, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nearest, image.view);
		vkCmdDraw(cb, 3, 1, 0, 0);
		RawPass.end(cb);
		Profiler.mark(cb, "RT: debug view");
		RawCommands.submit(ctx, cb);
	}
}
