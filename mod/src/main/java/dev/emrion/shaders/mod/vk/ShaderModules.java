package dev.emrion.shaders.mod.vk;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

public final class ShaderModules {
	private ShaderModules() {}

	/** {@code spv} must be a direct buffer of SPIR-V words (position..limit). */
	public static long create(VulkanContext ctx, ByteBuffer spv) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer p = stack.mallocLong(1);
			VulkanContext.check(VK10.vkCreateShaderModule(ctx.vk, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spv), null, p), "vkCreateShaderModule");
			return p.get(0);
		}
	}

	public static long create(VulkanContext ctx, byte[] spv) {
		ByteBuffer buf = MemoryUtil.memAlloc(spv.length).put(spv).flip();
		try {
			return create(ctx, buf);
		} finally {
			MemoryUtil.memFree(buf);
		}
	}

	/** Loads a SPIR-V file from the mod jar, e.g. {@code /assets/emberlight/spv/debug_post.frag.spv}. */
	public static byte[] resource(String path) {
		try (InputStream in = ShaderModules.class.getResourceAsStream(path)) {
			if (in == null) throw new IllegalArgumentException("missing resource " + path);
			return in.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static void destroy(VulkanContext ctx, long module) {
		VK10.vkDestroyShaderModule(ctx.vk, module, null);
	}
}
