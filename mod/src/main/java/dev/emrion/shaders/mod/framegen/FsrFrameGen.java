package dev.emrion.shaders.mod.framegen;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.VulkanContext;
import org.lwjgl.vulkan.VkCommandBuffer;

/** AMD FSR 3 frame generation: optical flow + frame interpolation on our compute passes. */
final class FsrFrameGen implements Generator {
	private final FfxPasses passes;
	private final OpticalFlow of;
	private final FrameInterpolation fi;

	FsrFrameGen(VulkanContext ctx, int rw, int rh, int dw, int dh) {
		// constant blocks per frame: FI 176 B x 2 + 2 pyramid blocks, OF 32 B x 9; 256 B alignment worst case
		passes = new FfxPasses(ctx, "fsr fg", 32 * 256);
		try {
			of = new OpticalFlow(passes, dw, dh);
			fi = new FrameInterpolation(passes, rw, rh, dw, dh, VK_FORMAT_R8G8B8A8_UNORM);
		} catch (RuntimeException e) {
			passes.destroyLater(); // what the two already created
			throw e;
		}
	}

	@Override
	public void dispatch(VkCommandBuffer cb, Inputs in, RawImage hudless, long backbufferImage, long backbufferView, RawImage output) {
		var frame = new FrameInterpolation.Frame(in.depthView(), in.motionView(), in.jitterX(), in.jitterY(), in.projection(), in.deltaMs(), in.reset());
		try {
			fi.prepare(cb, frame);
			of.dispatch(cb, hudless.view, in.reset());
			fi.dispatch(cb, frame, hudless, backbufferView, output, of);
		} finally {
			passes.ubo.nextFrame();
		}
	}

	@Override
	public void destroyLater() {
		passes.destroyLater();
	}
}
