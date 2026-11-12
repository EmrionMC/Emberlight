package dev.emrion.shaders.mod.vk;

import static org.lwjgl.vulkan.VK10.*;

/** Per-attachment blend state in raw Vulkan enums. {@code null} in a pipeline means blending off. */
public record Blend(int srcColor, int dstColor, int colorOp, int srcAlpha, int dstAlpha, int alphaOp) {
	public static final Blend ALPHA = of(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
	public static final Blend ADDITIVE = of(VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE);

	public static Blend of(int src, int dst, int srcA, int dstA) {
		return new Blend(src, dst, VK_BLEND_OP_ADD, srcA, dstA, VK_BLEND_OP_ADD);
	}
}
