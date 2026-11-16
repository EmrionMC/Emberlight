package dev.emrion.shaders.mod.vk;

import static org.lwjgl.vulkan.VK10.*;

import dev.emrion.shaders.mod.EmrionShadersClient;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

/**
 * A VkPipelineCache persisted to {@code <gameDir>/emberlight/pipeline-<pipelineCacheUUID>.bin}. The file name is keyed by
 * the driver's cache UUID, so a driver update starts a fresh cache (the driver also validates the header itself).
 */
public final class PipelineCacheStore {
	private final VulkanContext ctx;
	private final Path file;
	public final long handle;
	private static final long MAX_LOAD = 1L << 30;

	public PipelineCacheStore(VulkanContext ctx, Path dir) {
		this.ctx = ctx;
		byte[] uuid = new byte[16];
		ctx.properties.pipelineCacheUUID().get(0, uuid);
		this.file = dir.resolve("pipeline-" + HexFormat.of().formatHex(uuid) + ".bin");
		ByteBuffer initial = null;
		try {
			if (Files.isRegularFile(file) && Files.size(file) > MAX_LOAD) {
				// it only grows (every session saves what it loaded plus its own pipelines): start over
				EmrionShadersClient.LOG.info("Pipeline cache {} is {} MiB, starting a new one", file, Files.size(file) >> 20);
			} else if (Files.isRegularFile(file)) {
				byte[] data = Files.readAllBytes(file);
				initial = MemoryUtil.memAlloc(data.length).put(data).flip();
			}
		} catch (IOException e) {
			EmrionShadersClient.LOG.warn("Could not read pipeline cache {}", file, e);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
			if (initial != null) info.pInitialData(initial);
			LongBuffer p = stack.mallocLong(1);
			VulkanContext.check(vkCreatePipelineCache(ctx.vk, info, null, p), "vkCreatePipelineCache");
			handle = p.get(0);
		} finally {
			if (initial != null) MemoryUtil.memFree(initial);
		}
		EmrionShadersClient.LOG.info("Pipeline cache {} ({})", file, initial != null ? "loaded" : "new");
	}

	/** Call on exit and after a pack load. */
	public void save() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer size = stack.mallocPointer(1);
			VulkanContext.check(vkGetPipelineCacheData(ctx.vk, handle, size, null), "vkGetPipelineCacheData");
			ByteBuffer data = MemoryUtil.memAlloc((int) size.get(0));
			try {
				VulkanContext.check(vkGetPipelineCacheData(ctx.vk, handle, size, data), "vkGetPipelineCacheData");
				byte[] bytes = new byte[(int) size.get(0)];
				data.get(0, bytes);
				Files.createDirectories(file.getParent());
				Path tmp = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp"); // a shared dir may have several writers
				Files.write(tmp, bytes);
				Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} finally {
				MemoryUtil.memFree(data);
			}
		} catch (IOException e) {
			EmrionShadersClient.LOG.warn("Could not save pipeline cache {}", file, e);
		}
	}
}
