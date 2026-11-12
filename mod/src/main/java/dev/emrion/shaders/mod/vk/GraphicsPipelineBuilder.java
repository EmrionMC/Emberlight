package dev.emrion.shaders.mod.vk;

import static org.lwjgl.vulkan.VK10.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTVertexAttributeDivisor;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputDivisorStateCreateInfoEXT;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDivisorDescriptionEXT;

/**
 * Raw graphics pipeline for dynamic rendering. Viewport and scissor are dynamic (as in MC). Without vertex input it is a
 * fullscreen-triangle pipeline: draw 3 vertices and derive the position from {@code gl_VertexIndex}.
 */
public final class GraphicsPipelineBuilder {
	private record Stage(int stage, long module) {}
	private record Binding(int binding, int stride, int divisor) {}
	private record Attribute(int location, int binding, int format, int offset) {}

	private final List<Stage> stages = new ArrayList<>();
	private final List<Binding> bindings = new ArrayList<>();
	private final List<Attribute> attributes = new ArrayList<>();
	private int[] colorFormats = new int[0];
	private Blend[] blends = new Blend[0];
	private int depthFormat = VK_FORMAT_UNDEFINED;
	private boolean depthTest, depthWrite;
	private int depthCompare = VK_COMPARE_OP_ALWAYS;
	private float depthBiasConstant, depthBiasSlope;
	private int cullMode = VK_CULL_MODE_NONE;
	private int frontFace = VK_FRONT_FACE_CLOCKWISE; // MC renders unflipped with CW front faces
	private int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
	private int polygonMode = VK_POLYGON_MODE_FILL;
	private long layout;

	public GraphicsPipelineBuilder stage(int vkShaderStage, long module) {
		stages.add(new Stage(vkShaderStage, module));
		return this;
	}

	public GraphicsPipelineBuilder layout(long pipelineLayout) {
		this.layout = pipelineLayout;
		return this;
	}

	/** One color attachment per format; {@code blends[i] == null} means blending off for attachment i. */
	public GraphicsPipelineBuilder colors(int[] formats, Blend... blends) {
		this.colorFormats = formats.clone();
		this.blends = java.util.Arrays.copyOf(blends, formats.length);
		return this;
	}

	public GraphicsPipelineBuilder depth(int format, boolean test, boolean write, int compareOp) {
		this.depthFormat = format;
		this.depthTest = test;
		this.depthWrite = write;
		this.depthCompare = compareOp;
		return this;
	}

	public GraphicsPipelineBuilder depthBias(float constant, float slope) {
		this.depthBiasConstant = constant;
		this.depthBiasSlope = slope;
		return this;
	}

	public GraphicsPipelineBuilder cull(int cullMode) {
		this.cullMode = cullMode;
		return this;
	}

	public GraphicsPipelineBuilder frontFace(int frontFace) {
		this.frontFace = frontFace;
		return this;
	}

	public GraphicsPipelineBuilder topology(int topology) {
		this.topology = topology;
		return this;
	}

	public GraphicsPipelineBuilder polygonMode(int polygonMode) {
		this.polygonMode = polygonMode;
		return this;
	}

	/** {@code divisor == 0}: per vertex; {@code >= 1}: per instance with that divisor. */
	public GraphicsPipelineBuilder vertexBinding(int binding, int stride, int divisor) {
		bindings.add(new Binding(binding, stride, divisor));
		return this;
	}

	public GraphicsPipelineBuilder attribute(int location, int binding, int vkFormat, int offset) {
		attributes.add(new Attribute(location, binding, vkFormat, offset));
		return this;
	}

	public long build(VulkanContext ctx, long pipelineCache) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var stageInfos = VkPipelineShaderStageCreateInfo.calloc(stages.size(), stack);
			for (Stage s : stages) stageInfos.get().sType$Default().stage(s.stage).module(s.module).pName(stack.UTF8("main"));
			stageInfos.flip();

			var bindingInfos = VkVertexInputBindingDescription.calloc(bindings.size(), stack);
			var divisors = VkVertexInputBindingDivisorDescriptionEXT.calloc(bindings.size(), stack);
			for (Binding b : bindings) {
				bindingInfos.get().binding(b.binding).stride(b.stride).inputRate(b.divisor > 0 ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX);
				if (b.divisor > 1) divisors.get().binding(b.binding).divisor(b.divisor);
			}
			bindingInfos.flip();
			divisors.flip();
			var attributeInfos = VkVertexInputAttributeDescription.calloc(attributes.size(), stack);
			for (Attribute a : attributes) attributeInfos.get().location(a.location).binding(a.binding).format(a.format).offset(a.offset);
			attributeInfos.flip();
			var vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
					.pVertexBindingDescriptions(bindingInfos).pVertexAttributeDescriptions(attributeInfos);
			if (divisors.remaining() > 0) {
				vertexInput.pNext(VkPipelineVertexInputDivisorStateCreateInfoEXT.calloc(stack).sType$Default().pVertexBindingDivisors(divisors));
			}

			var blendStates = VkPipelineColorBlendAttachmentState.calloc(colorFormats.length, stack);
			for (int i = 0; i < colorFormats.length; i++) {
				var att = blendStates.get(i).colorWriteMask(0xF);
				Blend b = blends[i];
				if (b != null) {
					att.blendEnable(true).srcColorBlendFactor(b.srcColor()).dstColorBlendFactor(b.dstColor()).colorBlendOp(b.colorOp())
							.srcAlphaBlendFactor(b.srcAlpha()).dstAlphaBlendFactor(b.dstAlpha()).alphaBlendOp(b.alphaOp());
				}
			}

			var rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack).sType$Default()
					.pColorAttachmentFormats(stack.ints(colorFormats)).depthAttachmentFormat(depthFormat);
			var info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
					.pNext(rendering)
					.pStages(stageInfos)
					.pVertexInputState(vertexInput)
					.pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(topology))
					.pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
					.pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
							.polygonMode(polygonMode).cullMode(cullMode).frontFace(frontFace).lineWidth(1.0F)
							.depthBiasEnable(depthBiasConstant != 0 || depthBiasSlope != 0)
							.depthBiasConstantFactor(depthBiasConstant).depthBiasSlopeFactor(depthBiasSlope))
					.pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
					.pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
							.depthTestEnable(depthTest).depthWriteEnable(depthWrite).depthCompareOp(depthCompare))
					.pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blendStates))
					.pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
							.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
					.layout(layout);
			LongBuffer p = stack.mallocLong(1);
			VulkanContext.check(vkCreateGraphicsPipelines(ctx.vk, pipelineCache, info, null, p), "vkCreateGraphicsPipelines");
			return p.get(0);
		}
	}
}
