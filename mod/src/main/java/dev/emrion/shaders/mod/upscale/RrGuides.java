package dev.emrion.shaders.mod.upscale;

import static dev.emrion.shaders.mod.vk.Barriers.*;
import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.rt.RtScene;
import dev.emrion.shaders.mod.vk.ComputePipelines;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.ShaderModules;
import dev.emrion.shaders.mod.vk.VulkanContext;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

/** DLSS Ray Reconstruction's guide buffers and its sanitised colour input (rr_guides.comp, docs/UPSCALING.md §11) at render size. */
final class RrGuides {
	private final VulkanContext ctx;
	private final int width, height;
	private final long setLayout, layout, pipeline;
	final RawImage diffuse, specular, normals, color, bias, particles;
	private final Matrix4f inv = new Matrix4f();

	RrGuides(VulkanContext ctx, int w, int h) {
		this.ctx = ctx;
		width = w;
		height = h;
		int cs = VK_SHADER_STAGE_COMPUTE_BIT;
		var b = new Descriptors.LayoutBuilder().add(0, KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, cs)
				.add(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, cs);
		for (int i = 2; i <= 6; i++) b.add(i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, cs);
		b.add(11, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, cs).add(12, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, cs).add(13, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, cs)
				.add(14, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, cs).add(15, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, cs);
		for (int i = 7; i <= 10; i++) b.add(i, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, cs);
		try { // a failure (VRAM full) frees what was made so far: destroyLater skips the fields still unset
			setLayout = b.build(ctx, true);
			layout = Descriptors.pipelineLayout(ctx, 80, setLayout);
			long module = ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/rr_guides.comp.spv"));
			try {
				pipeline = ComputePipelines.create(ctx, module, layout, ctx.pipelineCache().handle);
			} finally {
				ShaderModules.destroy(ctx, module);
			}
			int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
			diffuse = new RawImage(ctx, VK_FORMAT_R16G16B16A16_SFLOAT, w, h, 1, 1, usage, "rr diffuse albedo");
			specular = new RawImage(ctx, VK_FORMAT_R16G16B16A16_SFLOAT, w, h, 1, 1, usage, "rr specular albedo");
			normals = new RawImage(ctx, VK_FORMAT_R16G16B16A16_SFLOAT, w, h, 1, 1, usage, "rr normals roughness");
			color = new RawImage(ctx, VK_FORMAT_R16G16B16A16_SFLOAT, w, h, 1, 1, usage, "rr colour");
			bias = new RawImage(ctx, VK_FORMAT_R16G16B16A16_SFLOAT, w, h, 1, 1, usage, "rr bias mask");
			particles = new RawImage(ctx, VK_FORMAT_R16G16B16A16_SFLOAT, w, h, 1, 1, usage, "rr particle mask");
		} catch (RuntimeException e) {
			destroyLater();
			throw e;
		}
	}

	/** Records the pass; false (nothing recorded) while there is no TLAS yet. */
	boolean dispatch(VkCommandBuffer cb, long colorView, long depthView, long compositionView, long reactiveView, Upscaler.RrInputs in) {
		RtScene scene = RtScene.get();
		if (scene == null || scene.tlasHandle() == 0) return false;
		long nearest = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)).vkSampler();
		in.projection().mul(in.modelView(), inv).invert();
		float pixelAngle = 2f * (float) Math.atan(1.0 / in.projection().m11()) / height;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var writes = VkWriteDescriptorSet.calloc(16, stack);
			boolean nativeGuides = in.specularView() != 0;
			var as = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(scene.tlasHandle()));
			writes.get(0).sType$Default().pNext(as.address()).dstBinding(0).descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
			writes.get(1).sType$Default().dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
					.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(scene.sectionTable().handle).offset(0).range(VK_WHOLE_SIZE));
			boolean packViews = in.pack() || nativeGuides;
			long[] sampled = {in.atlasView(), depthView, colorView, packViews ? in.normalView() : depthView, packViews ? in.materialView() : depthView};
			// the atlas: MC's mipmapped sampler, the plain one clamps to mip 0 and would ignore the shader's footprint LOD
			long mipped = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true)).vkSampler();
			for (int i = 0; i < 5; i++) Upscaler.image(writes.get(2 + i), stack, 2 + i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, i == 0 ? mipped : nearest, sampled[i]);
			long[] out = {diffuse.view, specular.view, normals.view, color.view};
			for (int i = 0; i < 4; i++) Upscaler.image(writes.get(7 + i), stack, 7 + i, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, out[i]);
			// without masks the bindings still need a view; params.w bit 2 tells the shader not to read it
			boolean masks = compositionView != 0 && reactiveView != 0;
			Upscaler.image(writes.get(11), stack, 11, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nearest, masks ? compositionView : depthView);
			Upscaler.image(writes.get(12), stack, 12, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nearest, masks ? reactiveView : depthView);
			Upscaler.image(writes.get(13), stack, 13, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, bias.view);
			Upscaler.image(writes.get(14), stack, 14, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, particles.view);
			Upscaler.image(writes.get(15), stack, 15, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nearest, nativeGuides ? in.specularView() : depthView);
			vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, writes);
			var pc = stack.malloc(80);
			inv.get(0, pc);
			pc.putFloat(64, width).putFloat(68, height).putFloat(72, pixelAngle).putFloat(76, (in.pack() ? 1 : 0) | (masks ? 2 : 0) | (nativeGuides ? 4 : 0));
			vkCmdPushConstants(cb, layout, VK_SHADER_STAGE_ALL, 0, pc);
			vkCmdDispatch(cb, (width + 7) / 8, (height + 7) / 8, 1);
		}
		memory(cb, COMPUTE, STORAGE_WRITE, COMPUTE, SAMPLED_READ | STORAGE_READ);
		Profiler.mark(cb, "RR guides");
		return true;
	}

	void destroyLater() {
		for (RawImage i : new RawImage[] {diffuse, specular, normals, color, bias, particles}) if (i != null) i.destroyLater();
		long pipeline = this.pipeline, layout = this.layout, setLayout = this.setLayout; // 0 (a no-op) when construction stopped early
		ctx.destroyLater(() -> {
			vkDestroyPipeline(ctx.vk, pipeline, null);
			vkDestroyPipelineLayout(ctx.vk, layout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, setLayout, null);
		});
	}
}
