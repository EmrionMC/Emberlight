package dev.emrion.shaders.mod.upscale;

import static dev.emrion.shaders.mod.vk.Barriers.*;
import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.vk.ComputePipelines;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.GraphicsPipelineBuilder;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.RawPass;
import dev.emrion.shaders.mod.vk.UboRing;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.ShaderModules;
import dev.emrion.shaders.mod.vk.VulkanContext;
import dev.emrion.shaders.mod.pack.PackManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Motion vectors (RG16F, current to previous UV at render size): camera motion from depth, then moving entities and the hand on top. */
public final class MotionVectors {
	/** A camera jump larger than this (blocks per frame) resets the history (teleport, respawn). */
	private static final double RESET_DISTANCE = 16;

	private final VulkanContext ctx;
	public final int width, height;
	public final RawImage image;
	private final long setLayout, layout, pipeline, objSetLayout, objLayout, objPipeline;
	// 160 bytes of parameters: more than the 128 bytes of push constants Vulkan guarantees, so a UBO slot per frame in flight
	private static final int PARAMS = 160, SLOT = 256;
	private final RawBuffer params;
	private int paramRegion;
	private final RawBuffer[] objVertices = new RawBuffer[UboRing.REGIONS];
	private int objRegion;
	/** Vertices drawn by the last object pass (world, hand), for the debug log. */
	public int objectVertices;
	private final Matrix4f viewProj = new Matrix4f(), prevViewProj = new Matrix4f(), inv = new Matrix4f(), toPrev = new Matrix4f(),
			toPrevSky = new Matrix4f(), handViewProj = new Matrix4f();
	private Vec3 prevCam;
	private long prevNanos;
	/** Time since the previous dispatch (ms), and whether the last dispatch reset the history. */
	public float deltaMs = 16.7f;
	public boolean reset;

	public MotionVectors(VulkanContext ctx, int w, int h) {
		this.ctx = ctx;
		width = w;
		height = h;
		try { // a failure (VRAM full) frees what was made so far: destroyLater skips the fields still unset
			int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
					| VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
			image = new RawImage(ctx, VK_FORMAT_R16G16_SFLOAT, w, h, 1, 1, usage, "upscale motion vectors");
			int cs = VK_SHADER_STAGE_COMPUTE_BIT;
			setLayout = new Descriptors.LayoutBuilder().add(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, cs).add(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, cs)
					.add(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, cs).add(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, cs)
					.add(4, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, cs).build(ctx, true);
			layout = Descriptors.pipelineLayout(ctx, 0, setLayout);
			params = new RawBuffer(ctx, (long) SLOT * UboRing.REGIONS, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true, "motion vector params");
			long module = ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/upscale_motion.comp.spv"));
			pipeline = ComputePipelines.create(ctx, module, layout, ctx.pipelineCache().handle);
			ShaderModules.destroy(ctx, module);

			objSetLayout = new Descriptors.LayoutBuilder().add(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT).build(ctx, true);
			objLayout = Descriptors.pipelineLayout(ctx, 16, objSetLayout);
			long vs = ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/object_motion.vert.spv"));
			long fs = ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/object_motion.frag.spv"));
			objPipeline = new GraphicsPipelineBuilder().stage(VK_SHADER_STAGE_VERTEX_BIT, vs).stage(VK_SHADER_STAGE_FRAGMENT_BIT, fs).layout(objLayout)
					.colors(new int[] {VK_FORMAT_R16G16_SFLOAT}).vertexBinding(0, 32, 0).attribute(0, 0, VK_FORMAT_R32G32B32A32_SFLOAT, 0)
					.attribute(1, 0, VK_FORMAT_R32G32B32A32_SFLOAT, 16).build(ctx, ctx.pipelineCache().handle);
			ShaderModules.destroy(ctx, vs);
			ShaderModules.destroy(ctx, fs);
		} catch (RuntimeException | Error failed) {
			destroyLater();
			throw failed;
		}
	}

	/**
	 * Records the pass; {@code projection} is unjittered, GL convention. Returns {@link #reset}.
	 *
	 * @param behindView depth behind translucents (depthtex1), or depthView
	 * @param compositionView the upscaler's composition mask (water / other translucents), or 0: then translucents move with
	 *        their surface (also when RT refraction is off: then the pack shows them unbent)
	 */
	public boolean dispatch(VkCommandBuffer cb, long depthView, long behindView, long compositionView, Matrix4fc projection, Matrix4fc modelView) {
		Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
		reset = prevCam == null || cam.distanceToSqr(prevCam) > RESET_DISTANCE * RESET_DISTANCE;
		long now = System.nanoTime();
		if (prevNanos != 0) deltaMs = Math.clamp((now - prevNanos) / 1e6f, 0.1f, 1000f);
		prevNanos = now;
		viewProj.set(projection).mul(modelView);
		if (reset) prevViewProj.set(viewProj);
		viewProj.invert(inv);
		Vec3 d = prevCam == null || reset ? Vec3.ZERO : cam.subtract(prevCam);
		// previous camera-relative position = current camera-relative position + (current camera - previous camera)
		prevViewProj.translate((float) d.x, (float) d.y, (float) d.z, toPrev).mul(inv);
		prevViewProj.mul(inv, toPrevSky);
		long nearest = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)).vkSampler();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var writes = VkWriteDescriptorSet.calloc(5, stack);
			paramRegion = (paramRegion + 1) % UboRing.REGIONS;
			long offset = (long) paramRegion * SLOT;
			writes.get(4).sType$Default().dstBinding(4).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
					.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(params.handle).offset(offset).range(PARAMS));
			Upscaler.image(writes.get(0), stack, 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nearest, depthView);
			Upscaler.image(writes.get(1), stack, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, image.view);
			Upscaler.image(writes.get(2), stack, 2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nearest, behindView);
			Upscaler.image(writes.get(3), stack, 3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nearest, compositionView != 0 ? compositionView : depthView);
			vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, writes);
			var pc = MemoryUtil.memByteBuffer(params.mapped + offset, PARAMS);
			toPrev.get(0, pc);
			toPrevSky.get(64, pc);
			pc.putFloat(128, projection.m00()).putFloat(132, projection.m11()).putFloat(136, projection.m22()).putFloat(140, projection.m32());
			float eta = Minecraft.getInstance().gameRenderer.mainCamera().getFluidInCamera() == FogType.WATER ? 1.333f : 1f / 1.333f;
			var rt = PackManager.rtState();
			boolean refraction = compositionView != 0 && rt != null && rt.effects().contains("refraction");
			pc.putFloat(144, Upscaling.ndcJitterX()).putFloat(148, Upscaling.ndcJitterY()); // as the object pass: same pixels as the gbuffers
			pc.putFloat(156, !refraction ? 0f : rt.effects().contains("refraction:glass=0") ? -eta : eta);
			vkCmdDispatch(cb, (width + 7) / 8, (height + 7) / 8, 1);
		}
		long writer = COMPUTE, written = STORAGE_WRITE;
		if (ObjectMotion.enabled()) {
			// MC's renderItemInHand bakes the inverse view rotation into the hand vertices and puts the rotation in the model view
			var camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
			ObjectMotion.build(viewProj, handViewProj.set(Upscaling.unjitteredHand).mul(camera.viewRotationMatrix), reset);
			objectVertices = ObjectMotion.worldVertices + ObjectMotion.handVertices;
			if (objectVertices > 0) {
				drawObjects(cb, depthView, nearest);
				writer = COLOR_OUT;
				written = COLOR_WRITE;
			}
		}
		memory(cb, writer, written, COMPUTE, SAMPLED_READ | STORAGE_READ);
		Profiler.mark(cb, "upscale: motion vectors");
		prevViewProj.set(viewProj);
		prevCam = cam;
		return reset;
	}

	/** Rasterises the moving objects' motion over the camera motion (depth-tested against the scene in the fragment shader). */
	private void drawObjects(VkCommandBuffer cb, long depthView, long sampler) {
		int n = objectVertices;
		long bytes = (long) n * 32;
		objRegion = (objRegion + 1) % objVertices.length;
		RawBuffer buf = objVertices[objRegion];
		if (buf == null || buf.size < bytes) {
			if (buf != null) buf.destroyLater();
			buf = objVertices[objRegion] = new RawBuffer(ctx, Math.max(bytes * 3 / 2, 64 * 1024), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, true, "object motion vertices");
		}
		MemoryUtil.memFloatBuffer(buf.mapped, n * 8).put(ObjectMotion.out, 0, n * 8);
		memory(cb, COMPUTE, STORAGE_WRITE, COLOR_OUT, COLOR_READ | COLOR_WRITE); // loadOp LOAD reads the camera motion
		RawPass.begin(cb, width, height, image.view);
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, objPipeline);
		RawPass.pushImages(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, objLayout, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, sampler, depthView);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkCmdBindVertexBuffers(cb, 0, stack.longs(buf.handle), stack.longs(0));
			var pc = stack.malloc(16);
			pc.putFloat(0, Upscaling.ndcJitterX()).putFloat(4, Upscaling.ndcJitterY()).putInt(8, 0).putInt(12, 0);
			if (ObjectMotion.worldVertices > 0) {
				vkCmdPushConstants(cb, objLayout, VK_SHADER_STAGE_ALL, 0, pc);
				vkCmdDraw(cb, ObjectMotion.worldVertices, 1, 0, 0);
			}
			if (ObjectMotion.handVertices > 0) {
				pc.putInt(8, 1);
				vkCmdPushConstants(cb, objLayout, VK_SHADER_STAGE_ALL, 0, pc);
				vkCmdDraw(cb, ObjectMotion.handVertices, 1, ObjectMotion.worldVertices, 0);
			}
		}
		RawPass.end(cb);
	}

	/** The last dispatch's transform from this frame's clip space to the previous frame's (GL convention, unjittered). */
	public Matrix4fc clipToPrevClip() {
		return toPrev;
	}

	public void destroyLater() {
		if (image != null) image.destroyLater();
		if (params != null) params.destroyLater();
		for (RawBuffer b : objVertices) if (b != null) b.destroyLater();
		ctx.destroyLater(() -> {
			vkDestroyPipeline(ctx.vk, objPipeline, null);
			vkDestroyPipelineLayout(ctx.vk, objLayout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, objSetLayout, null);
			vkDestroyPipeline(ctx.vk, pipeline, null);
			vkDestroyPipelineLayout(ctx.vk, layout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, setLayout, null);
		});
	}
}
