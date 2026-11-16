package dev.emrion.shaders.mod.framegen;

import dev.emrion.shaders.mod.vk.RawImage;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VkCommandBuffer;

/** One frame-generation backend (FSR FG or DLSS FG) at one (render size, display size) pair. */
interface Generator {
	/** This frame's inputs; depth and motion vectors are at render size and readable by compute. */
	record Inputs(int renderW, int renderH, int displayW, int displayH, long depthImage, long depthView, int depthFormat, long motionImage,
			long motionView, float jitterX, float jitterY, Matrix4fc projection, Matrix4fc clipToPrevClip, float deltaMs, boolean reset) {}

	/**
	 * Records the interpolated frame between the previous call's frame and this one into {@code output} (display size,
	 * RGBA8), composing the GUI from {@code backbuffer} (the main target with GUI) over the interpolated {@code hudless} pair.
	 */
	void dispatch(VkCommandBuffer cb, Inputs in, RawImage hudless, long backbufferImage, long backbufferView, RawImage output);

	void destroyLater();
}
