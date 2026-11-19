package dev.emrion.shaders.mod.runtime;

import static dev.emrion.shaders.mod.vk.Barriers.*;
import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.rt.RtScene;
import dev.emrion.shaders.mod.vk.ComputePipelines;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.ShaderModules;
import dev.emrion.shaders.mod.vk.UboRing;
import dev.emrion.shaders.mod.vk.VulkanContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

/**
 * RT ambient occlusion: before the first pass that samples {@code emrion_rtao}, traces short AO rays from depthtex0,
 * accumulates them with reprojection and filters them edge-aware.
 */
final class RtAoPass {
	static final String BUILTIN = "emrion_rtao";
	/** Rays per pixel and ray length in blocks (-Demrion.rt.aoRays / aoRadius, for tuning). */
	private static final int RAYS = Integer.getInteger("emrion.rt.aoRays", 2);
	// 1 block: contact AO like the pack's SSAO; 2 blocks darkened whole tunnels
	private static final float RADIUS = Float.parseFloat(System.getProperty("emrion.rt.aoRadius", "1.0"));

	private final VulkanContext ctx;
	private final int width, height;
	private long setLayout, layout, tracePipeline, filterPipeline;
	private final RawImage[] history = new RawImage[2];
	// 160 bytes of parameters: more than the 128 bytes of push constants Vulkan guarantees, so a UBO slot per frame in flight
	private static final int PARAMS = 160, SLOT = 256;
	private RawBuffer params;
	final PackRuntime.Img output = new PackRuntime.Img("emrion rtao", null);
	private final Matrix4f prevViewProj = new Matrix4f(), viewProj = new Matrix4f(), inv = new Matrix4f();
	private Vec3 prevCam = Vec3.ZERO;
	private int frame;

	RtAoPass(VulkanContext ctx, int width, int height) {
		this.ctx = ctx;
		this.width = width;
		this.height = height;
		try {
			init();
		} catch (RuntimeException | Error failed) { // e.g. out of GPU memory half-way: release what was created
			destroyLater();
			throw failed;
		}
	}

	private void init() {
		int cs = VK_SHADER_STAGE_COMPUTE_BIT;
		setLayout = new Descriptors.LayoutBuilder()
				.add(0, KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, cs)
				.add(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, cs)
				.add(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, cs)
				.add(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, cs)
				.add(4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, cs)
				.add(5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, cs)
				.add(6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, cs)
				.add(7, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, cs).build(ctx, true);
		layout = Descriptors.pipelineLayout(ctx, 0, setLayout);
		params = new RawBuffer(ctx, (long) SLOT * UboRing.REGIONS, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true, "rt ao params");
		long trace = module("rt_ao.comp"), filter = 0;
		try {
			filter = module("rt_ao_filter.comp");
			tracePipeline = ComputePipelines.create(ctx, trace, layout, ctx.pipelineCache().handle);
			filterPipeline = ComputePipelines.create(ctx, filter, layout, ctx.pipelineCache().handle);
		} finally {
			ShaderModules.destroy(ctx, trace);
			ShaderModules.destroy(ctx, filter);
		}
		int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
		for (int i = 0; i < 2; i++) history[i] = new RawImage(ctx, VK_FORMAT_R16G16B16A16_SFLOAT, width, height, 1, 1, usage, "rt ao history " + i);
		RawImage out = new RawImage(ctx, VK_FORMAT_R32_SFLOAT, width, height, 1, 1, usage, "rt ao");
		output.raw = out;
		output.image = out.image;
		output.sampled = output.attach = out.view;
		output.width = width;
		output.height = height;
	}

	private long module(String name) {
		return ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/" + name + ".spv"));
	}

	/**
	 * Records both passes into {@code cb}; the caller's pass then reads {@link #output} (declared to {@code hazards}). {@code raw}
	 * (DLSS Ray Reconstruction): this frame's rays only, no history, no filter.
	 */
	void dispatch(VkCommandBuffer cb, Hazards hazards, PackRuntime.Img depth, long atlasView, Matrix4fc projection, Matrix4fc modelView, boolean raw) {
		RtScene scene = RtScene.get();
		if (scene == null) return;
		hazards.read(depth, COMPUTE, SAMPLED_READ);
		hazards.write(output, COMPUTE, STORAGE_WRITE);
		hazards.flush(cb);

		Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
		viewProj.set(projection).mul(modelView);
		viewProj.invert(inv);
		RawImage in = history[frame & 1 ^ 1], out = history[frame & 1];
		long nearest = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)).vkSampler();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long offset = (long) Math.floorMod(frame, UboRing.REGIONS) * SLOT;
			var pc = MemoryUtil.memByteBuffer(params.mapped + offset, PARAMS);
			inv.get(0, pc);
			prevViewProj.get(64, pc);
			pc.putFloat(128, (float) (cam.x - prevCam.x)).putFloat(132, (float) (cam.y - prevCam.y)).putFloat(136, (float) (cam.z - prevCam.z))
					.putFloat(140, frame).putFloat(144, width).putFloat(148, height).putFloat(152, RADIUS).putFloat(156, raw ? -RAYS : RAYS);

			vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, tracePipeline);
			push(cb, stack, scene, nearest, atlasView, depth.sampled, in.view, out.view, offset);
			vkCmdDispatch(cb, (width + 7) / 8, (height + 7) / 8, 1);
			Profiler.mark(cb, "RT: AO trace");
			memory(cb, COMPUTE, STORAGE_WRITE, COMPUTE, SAMPLED_READ);

			vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, filterPipeline);
			push(cb, stack, scene, nearest, atlasView, depth.sampled, out.view, in.view, offset);
			vkCmdDispatch(cb, (width + 7) / 8, (height + 7) / 8, 1);
			Profiler.mark(cb, "RT: AO filter");
		}
		prevViewProj.set(viewProj);
		prevCam = cam;
		frame++;
	}

	private void push(VkCommandBuffer cb, MemoryStack stack, RtScene scene, long sampler, long atlasView, long depthView, long historyIn, long historyOut, long offset) {
		var writes = VkWriteDescriptorSet.calloc(8, stack);
		var as = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(scene.tlasHandle()));
		writes.get(0).sType$Default().pNext(as.address()).dstBinding(0).descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
		writes.get(1).sType$Default().dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
				.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(scene.sectionTable().handle).offset(0).range(VK_WHOLE_SIZE));
		image(writes.get(2), stack, 2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, sampler, atlasView);
		image(writes.get(3), stack, 3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, sampler, depthView);
		image(writes.get(4), stack, 4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, sampler, historyIn);
		image(writes.get(5), stack, 5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, historyOut);
		image(writes.get(6), stack, 6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, output.sampled);
		writes.get(7).sType$Default().dstBinding(7).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
				.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(params.handle).offset(offset).range(PARAMS));
		KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, writes);
	}

	private static void image(VkWriteDescriptorSet w, MemoryStack stack, int binding, int type, long sampler, long view) {
		w.sType$Default().dstBinding(binding).descriptorType(type).descriptorCount(1)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
	}

	/** Also after a failed construction: destroys what was created. */
	void destroyLater() {
		for (RawImage h : history) if (h != null) h.destroyLater();
		if (output.raw != null) output.raw.destroyLater();
		if (params != null) params.destroyLater();
		ctx.destroyLater(() -> {
			vkDestroyPipeline(ctx.vk, tracePipeline, null);
			vkDestroyPipeline(ctx.vk, filterPipeline, null);
			vkDestroyPipelineLayout(ctx.vk, layout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, setLayout, null);
		});
	}
}
