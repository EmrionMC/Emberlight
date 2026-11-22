package dev.emrion.shaders.mod.hdr;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.vk.ComputePipelines;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.RawCommands;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.util.Properties;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTHdrMetadata;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkHdrMetadataEXT;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * HDR output (docs/HDR.md): an HDR10 (or scRGB) swapchain when the surface offers one and the user turned HDR on, and an
 * encode pass that expands the final SDR image before MC's swapchain blit. Off (the default) or no HDR colour space: every
 * entry point returns at once and the swapchain is created exactly as MC creates it.
 */
public final class Hdr {
	private Hdr() {}

	static final int CS_HDR10 = 1000104008, CS_SCRGB = 1000104002; // VK_COLOR_SPACE_HDR10_ST2084_EXT, _EXTENDED_SRGB_LINEAR_EXT
	public static final FeatureSet FEATURE_SET = new FeatureSet("Emberlight HDR metadata", Set.of("VK_EXT_hdr_metadata"), Set.of());

	public static volatile boolean enabled;
	public static volatile int peakNits = 1000, paperWhiteNits = 200;
	/** MC enabled VK_EXT_hdr_metadata on the device (VulkanBackendRtMixin). */
	public static volatile boolean metadataSupported;
	/** -Demrion.hdr.probe=true: logs encoded sample pixels every 300 frames (the swapchain image itself cannot be read). */
	private static final boolean PROBE = Boolean.getBoolean("emrion.hdr.probe");

	/** Why the display cannot do HDR (null = it offers an HDR colour space); checked at every swapchain creation. */
	private static volatile @Nullable String unsupported = "not checked yet";
	/** Colour space of the current swapchain (0 = SDR) and the one the pending creation asked for. */
	private static volatile int colorSpace, pending;
	private static boolean failed, reconfigure;

	private static @Nullable RawImage out, hudless;
	private static boolean hudlessTaken;
	private static final RawImage[] slots = new RawImage[4];
	private static long setLayout, layout, pipeline;
	private static @Nullable RawBuffer probe;
	private static long probeFrame;

	public static void load(Properties p) {
		enabled = Boolean.parseBoolean(System.getProperty("emrion.hdr", p.getProperty("hdr.enabled", "false")).trim());
		peakNits = Math.clamp(intProp(p, "peak", 1000), 100, 10000);
		paperWhiteNits = Math.clamp(intProp(p, "paperWhite", 200), 80, 1000);
		if (enabled) settingsChanged(); // MC created its first swapchain before the config was read
	}

	private static int intProp(Properties p, String key, int def) {
		try {
			return Integer.parseInt(System.getProperty("emrion.hdr." + key, p.getProperty("hdr." + key, "" + def)).trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	public static void store(Properties p) {
		p.setProperty("hdr.enabled", "" + enabled);
		p.setProperty("hdr.peak", "" + peakNits);
		p.setProperty("hdr.paperWhite", "" + paperWhiteNits);
	}

	public static @Nullable String unsupported() {
		return unsupported;
	}

	/** True while the swapchain is HDR: every image MC or the frame-generation presenter blits to it must be encoded. */
	public static boolean active() {
		return colorSpace != 0;
	}

	public static String status() {
		return colorSpace == CS_HDR10 ? "HDR10" : colorSpace == CS_SCRGB ? "scRGB" : "Off";
	}

	/** Settings changed: the next frame recreates the swapchain (colour space, metadata). */
	public static void settingsChanged() {
		failed = false;
		reconfigure = true;
	}

	/** blitFromTexture HEAD: true once after a settings change, then MC rebuilds the swapchain. */
	public static boolean takeReconfigure() {
		boolean r = reconfigure;
		reconfigure = false;
		return r;
	}

	// ---- swapchain (VulkanGpuSurface.configure) --------------------------------------------------------------------------

	/** Before vkCreateSwapchainKHR: picks an HDR format/colour space the surface offers, when HDR is on. */
	public static void chooseSwapchain(VkDevice dev, VkSwapchainCreateInfoKHR info) {
		pending = 0;
		var pd = dev.getPhysicalDevice();
		int format = 0, cs = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var n = stack.mallocInt(1);
			KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(pd, info.surface(), n, null);
			var formats = VkSurfaceFormatKHR.malloc(n.get(0), stack);
			KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(pd, info.surface(), n, formats);
			var props = VkFormatProperties.malloc(stack);
			for (var f : formats) {
				int rank = f.colorSpace() == CS_HDR10 && (f.format() == VK_FORMAT_A2B10G10R10_UNORM_PACK32 || f.format() == VK_FORMAT_A2R10G10B10_UNORM_PACK32) ? 2
						: f.colorSpace() == CS_SCRGB && f.format() == VK_FORMAT_R16G16B16A16_SFLOAT ? 1 : 0;
				if (rank == 0 || rank <= (cs == CS_HDR10 ? 2 : cs == CS_SCRGB ? 1 : 0)) continue;
				vkGetPhysicalDeviceFormatProperties(pd, f.format(), props);
				if ((props.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_DST_BIT) == 0) continue; // MC's blit writes the swapchain
				format = f.format();
				cs = f.colorSpace();
			}
		}
		unsupported = cs != 0 ? null : "the display (or compositor) offers no HDR colour space";
		if (!enabled || failed || cs == 0) return;
		info.imageFormat(format).imageColorSpace(cs);
		pending = cs;
	}

	/** After vkCreateSwapchainKHR. */
	public static void swapchainCreated(VkDevice dev, int result, long swapchain, int format) {
		colorSpace = 0;
		if (pending == 0) return;
		if (result != VK_SUCCESS) {
			failed = true; // MC retries the configure; that one is SDR
			unsupported = "creating the HDR swapchain failed (" + result + ")";
			EmrionShadersClient.LOG.warn("HDR: {}; using SDR", unsupported);
			return;
		}
		colorSpace = pending;
		EmrionShadersClient.LOG.info("HDR swapchain: format {}, colour space {} ({}), paper white {} nits, peak {} nits, metadata {}", format, colorSpace,
				status(), paperWhiteNits, peakNits, metadataSupported);
		if (!metadataSupported) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// mastering display = what the content is: BT.709 primaries, D65, up to the peak setting
			var m = VkHdrMetadataEXT.calloc(stack).sType$Default().maxLuminance(peakNits).minLuminance(0f).maxContentLightLevel(peakNits)
					.maxFrameAverageLightLevel(paperWhiteNits);
			m.displayPrimaryRed().set(0.640f, 0.330f);
			m.displayPrimaryGreen().set(0.300f, 0.600f);
			m.displayPrimaryBlue().set(0.150f, 0.060f);
			m.whitePoint().set(0.3127f, 0.3290f);
			EXTHdrMetadata.vkSetHdrMetadataEXT(dev, stack.longs(swapchain), VkHdrMetadataEXT.calloc(1, stack).put(0, m));
		}
	}

	// ---- per frame ---------------------------------------------------------------------------------------------------

	/** GameRenderer.render before the GUI: copy MC's main colour (HUD-less), the GUI mask of this frame. */
	public static void beforeGui(long mainColorImage, int w, int h) {
		if (!active()) return;
		VulkanContext ctx = VulkanContext.get();
		try {
			hudless = sized(ctx, hudless, w, h, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, "hdr hud-less colour");
		} catch (RuntimeException e) {
			fallBack(e);
			return;
		}
		var cb = RawCommands.begin(ctx);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var region = VkImageCopy.calloc(1, stack);
			region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.extent().set(w, h, 1);
			vkCmdCopyImage(cb, mainColorImage, VK_IMAGE_LAYOUT_GENERAL, hudless.image, VK_IMAGE_LAYOUT_GENERAL, region);
		}
		RawCommands.submit(ctx, cb);
		hudlessTaken = true;
	}

	/** blitFromTexture HEAD: encodes MC's final image; returns the image MC's blit should read instead (0 = MC's own). */
	public static long beforeBlit(long backbufferView, int w, int h) {
		boolean mask = hudlessTaken && hudless != null && hudless.width == w && hudless.height == h;
		hudlessTaken = false;
		if (!active()) {
			releaseImages(); // HDR switched off: its full-size images are not needed until it comes back
			return 0;
		}
		VulkanContext ctx = VulkanContext.get();
		try {
			out = sized(ctx, out, w, h, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, "hdr output");
		} catch (RuntimeException e) {
			fallBack(e);
			return 0;
		}
		var cb = RawCommands.begin(ctx);
		encode(ctx, cb, backbufferView, backbufferView, mask ? hudless.view : 0, out);
		if (PROBE) probe(ctx, cb, out);
		RawCommands.submit(ctx, cb);
		return out.image;
	}

	/**
	 * Frame generation: encodes interpolated slot {@code slot} (GUI mask: the current frame's back buffer against its HUD-less
	 * copy, the pixels the GUI composition took from the back buffer). The presenter blits the result like the SDR slot.
	 */
	public static RawImage encodeInterpolated(VulkanContext ctx, VkCommandBuffer cb, int slot, RawImage interp, long backbufferView, RawImage fgHudless) {
		slots[slot] = sized(ctx, slots[slot], interp.width, interp.height, VK_FORMAT_R16G16B16A16_SFLOAT,
				VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, "hdr interpolated frame " + slot);
		encode(ctx, cb, interp.view, backbufferView, fgHudless.view, slots[slot]);
		return slots[slot];
	}

	/** FrameGen.destroy: the presenter is drained; {@code free} = its copies are done (else the slots are leaked like FG's). */
	public static void destroyInterpolated(boolean free) {
		for (int i = 0; i < slots.length; i++) {
			if (slots[i] != null && free) slots[i].destroyLater();
			slots[i] = null;
		}
	}

	private static void releaseImages() {
		if (out != null) out.destroyLater();
		if (hudless != null) hudless.destroyLater();
		out = hudless = null;
	}

	/** Client stopping (after FrameGen.shutdown freed the interpolated slots): before MC destroys the device. */
	public static void shutdown() {
		releaseImages();
		if (probe != null) probe.destroyLater();
		probe = null;
		if (pipeline == 0) return;
		VulkanContext ctx = VulkanContext.get();
		long p = pipeline, l = layout, s = setLayout;
		ctx.destroyLater(() -> {
			vkDestroyPipeline(ctx.vk, p, null);
			vkDestroyPipelineLayout(ctx.vk, l, null);
			vkDestroyDescriptorSetLayout(ctx.vk, s, null);
		});
		pipeline = layout = setLayout = 0;
	}

	/** Allocation failed (resize under VRAM pressure): drop to SDR at the next swapchain rebuild instead of crashing. */
	private static void fallBack(RuntimeException e) {
		EmrionShadersClient.LOG.error("HDR: {}; using SDR", e.getMessage(), e);
		failed = true;
		reconfigure = true;
	}

	private static RawImage sized(VulkanContext ctx, @Nullable RawImage img, int w, int h, int format, int usage, String label) {
		if (img != null && img.width == w && img.height == h) return img;
		var n = new RawImage(ctx, format, w, h, 1, 1, usage, label); // first: on OOM the caller's field keeps a live image
		if (img != null) img.destroyLater();
		return n;
	}

	private static void encode(VulkanContext ctx, VkCommandBuffer cb, long srcView, long guiView, long hudlessView, RawImage dst) {
		if (pipeline == 0) {
			int c = VK_SHADER_STAGE_COMPUTE_BIT;
			setLayout = new Descriptors.LayoutBuilder().add(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, c).add(1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, c)
					.add(2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, c).add(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, c).build(ctx, true);
			layout = Descriptors.pipelineLayout(ctx, 16, setLayout);
			pipeline = ComputePipelines.create(ctx, "/assets/emberlight/spv/hdr_encode.comp.spv", layout);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanCommandEncoder.memoryBarrier(cb, stack); // the source was just written (interpolation) or the target last read (blit)
			var writes = VkWriteDescriptorSet.calloc(4, stack);
			long[] views = {srcView, guiView, hudlessView != 0 ? hudlessView : guiView, dst.view};
			for (int i = 0; i < 4; i++) writes.get(i).sType$Default().dstBinding(i)
					.descriptorType(i == 3 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(1)
					.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(views[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, writes);
			var pc = stack.malloc(16);
			pc.putFloat(0, paperWhiteNits).putFloat(4, Math.max(peakNits, paperWhiteNits)).putInt(8, colorSpace == CS_HDR10 ? 1 : 0).putInt(12, hudlessView != 0 ? 1 : 0);
			vkCmdPushConstants(cb, layout, VK_SHADER_STAGE_ALL, 0, pc);
			vkCmdDispatch(cb, (dst.width + 7) / 8, (dst.height + 7) / 8, 1);
		}
	}

	/** Debug readback: 5 pixels (centre, corners inset by 1/8) of the encoded output, logged as code values and nits. */
	private static void probe(VulkanContext ctx, VkCommandBuffer cb, RawImage img) {
		if (probe == null) probe = new RawBuffer(ctx, 5 * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true, "hdr probe");
		if (++probeFrame % 300 == 3) { // written 3 frames ago: MC keeps at most two in flight
			StringBuilder s = new StringBuilder("HDR probe (" + status() + "):");
			for (int i = 0; i < 5; i++) {
				s.append(" [");
				for (int c = 0; c < 3; c++) {
					float v = Float.float16ToFloat(MemoryUtil.memGetShort(probe.mapped + i * 8 + c * 2));
					double nits = colorSpace == CS_HDR10 ? pqToNits(v) : v * 80.0;
					s.append(String.format(java.util.Locale.ROOT, "%.4f=%.0fnt%s", v, nits, c < 2 ? " " : ""));
				}
				s.append("]");
			}
			EmrionShadersClient.LOG.info(s.toString());
		}
		if (probeFrame % 300 != 0) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanCommandEncoder.memoryBarrier(cb, stack);
			var regions = VkBufferImageCopy.calloc(5, stack);
			int[][] at = {{img.width / 2, img.height / 2}, {img.width / 8, img.height / 8}, {img.width * 7 / 8, img.height / 8},
					{img.width / 8, img.height * 7 / 8}, {img.width * 7 / 8, img.height * 7 / 8}};
			for (int i = 0; i < 5; i++) {
				var r = regions.get(i).bufferOffset(i * 8L);
				r.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
				r.imageOffset().set(at[i][0], at[i][1], 0);
				r.imageExtent().set(1, 1, 1);
			}
			vkCmdCopyImageToBuffer(cb, img.image, VK_IMAGE_LAYOUT_GENERAL, probe.handle, regions);
		}
	}

	static double pqToNits(double e) {
		double m1 = 0.1593017578125, m2 = 78.84375, c1 = 0.8359375, c2 = 18.8515625, c3 = 18.6875;
		double p = Math.pow(Math.max(e, 0), 1 / m2);
		return 10000 * Math.pow(Math.max(p - c1, 0) / (c2 - c3 * p), 1 / m1);
	}
}
