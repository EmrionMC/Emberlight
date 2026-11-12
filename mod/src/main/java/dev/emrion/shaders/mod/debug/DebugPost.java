package dev.emrion.shaders.mod.debug;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.vk.Barriers;
import dev.emrion.shaders.mod.vk.ComputePipelines;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.GpuTimer;
import dev.emrion.shaders.mod.vk.GraphicsPipelineBuilder;
import dev.emrion.shaders.mod.vk.RawCommands;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.RawPass;
import dev.emrion.shaders.mod.vk.ShaderModules;
import dev.emrion.shaders.mod.vk.VulkanContext;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Raw-interop proof (dev only, {@code -Demrion.debug.post=true}): a compute dispatch writes a storage image, pass 1
 * copies MC's main color into an RGBA16F image, pass 2 writes it back desaturated and blue-tinted, with the compute
 * result in the bottom-left corner. All in one of our command buffers, ordered into MC's frame.
 */
public final class DebugPost {
	public static final boolean ENABLED = Boolean.getBoolean("emrion.debug.post");
	private static final int PATTERN_SIZE = 256;
	private static @Nullable DebugPost instance;

	private final VulkanContext ctx = VulkanContext.get();
	private final long gfxLayout, computeLayout, copyPipeline, tintPipeline, computePipeline;
	private final RawImage pattern;
	private @Nullable RawImage intermediate;
	private final GpuTimer timer = new GpuTimer(2);
	private long lastLog;

	private DebugPost() {
		long gfxSet = new Descriptors.LayoutBuilder()
				.add(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT)
				.add(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT).build(ctx, true);
		long computeSet = new Descriptors.LayoutBuilder().add(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT).build(ctx, true);
		gfxLayout = Descriptors.pipelineLayout(ctx, 0, gfxSet);
		computeLayout = Descriptors.pipelineLayout(ctx, 4, computeSet);
		long cache = ctx.pipelineCache().handle;
		long vs = module("fullscreen.vert"), copyFs = module("debug_copy.frag"), tintFs = module("debug_tint.frag"), cs = module("debug_pattern.comp");
		copyPipeline = new GraphicsPipelineBuilder().stage(VK_SHADER_STAGE_VERTEX_BIT, vs).stage(VK_SHADER_STAGE_FRAGMENT_BIT, copyFs)
				.layout(gfxLayout).colors(new int[] {VK_FORMAT_R16G16B16A16_SFLOAT}).build(ctx, cache);
		tintPipeline = new GraphicsPipelineBuilder().stage(VK_SHADER_STAGE_VERTEX_BIT, vs).stage(VK_SHADER_STAGE_FRAGMENT_BIT, tintFs)
				.layout(gfxLayout).colors(new int[] {VK_FORMAT_R8G8B8A8_UNORM}).build(ctx, cache);
		computePipeline = ComputePipelines.create(ctx, cs, computeLayout, cache);
		for (long m : new long[] {vs, copyFs, tintFs, cs}) ShaderModules.destroy(ctx, m); // pipelines keep what they need
		pattern = new RawImage(ctx, VK_FORMAT_R8G8B8A8_UNORM, PATTERN_SIZE, PATTERN_SIZE, 1, 1,
				VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "emrion debug pattern");
	}

	private long module(String name) {
		return ShaderModules.create(ctx, ShaderModules.resource("/assets/emrionshaders/spv/" + name + ".spv"));
	}

	/** Called at {@code GameRenderer.renderLevel} TAIL (world and hand done, GUI not yet drawn). */
	public static void onRenderLevelEnd() {
		if (instance == null) instance = new DebugPost();
		instance.run();
	}

	private void run() {
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		int w = main.width, h = main.height;
		if (intermediate == null || intermediate.width != w || intermediate.height != h) {
			if (intermediate != null) intermediate.destroyLater();
			intermediate = new RawImage(ctx, VK_FORMAT_R16G16B16A16_SFLOAT, w, h, 1, 1,
					VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "emrion debug intermediate");
		}
		long mainView = ((VulkanGpuTextureView) main.getColorTextureView()).vkImageView();
		long sampler = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)).vkSampler();

		timer.beginFrame();
		if (System.nanoTime() - lastLog > 5_000_000_000L) {
			lastLog = System.nanoTime();
			EmrionShadersClient.LOG.info("DebugPost GPU time: {} us", timer.nanosSinceFirst(1) / 1000);
		}
		timer.mark();
		VkCommandBuffer cb = RawCommands.begin(ctx);
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);
		RawPass.pushImages(cb, VK_PIPELINE_BIND_POINT_COMPUTE, computeLayout, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, pattern.view);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkCmdPushConstants(cb, computeLayout, VK_SHADER_STAGE_ALL, 0, stack.floats((System.nanoTime() % 100_000_000_000L) / 1e9F));
		}
		vkCmdDispatch(cb, PATTERN_SIZE / 8, PATTERN_SIZE / 8, 1);
		Barriers.storageToRead(cb);

		RawPass.begin(cb, w, h, intermediate.view);
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, copyPipeline);
		RawPass.pushImages(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, gfxLayout, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, sampler, mainView, pattern.view);
		vkCmdDraw(cb, 3, 1, 0, 0);
		RawPass.end(cb);
		Barriers.colorToSampled(cb);
		Barriers.sampledToColor(cb);

		RawPass.begin(cb, w, h, mainView);
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, tintPipeline);
		RawPass.pushImages(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, gfxLayout, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, sampler, intermediate.view, pattern.view);
		vkCmdDraw(cb, 3, 1, 0, 0);
		RawPass.end(cb);
		RawCommands.submit(ctx, cb);
		timer.mark();
	}
}
