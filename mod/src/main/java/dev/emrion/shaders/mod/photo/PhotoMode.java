package dev.emrion.shaders.mod.photo;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.uniforms.IrisTimer;
import dev.emrion.shaders.mod.upscale.UpscaleConfig;
import dev.emrion.shaders.mod.upscale.Upscaling;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Properties;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Photo mode: freezes the client (PhotoFreezeMixin, PhotoTimeMixin, IrisTimer), renders the same view {@code samples} times
 * (the pack's TAA jitter, the upscaler's jitter and every RT seed follow frameCounter, which keeps running), optionally at
 * {@code scale}× the window size, and averages the finished frames (before the GUI) in linear light into one PNG.
 * Every frame stands alone (RT and upscaler histories reset, the upscaler at native size): with their histories kept, FSR's
 * darkened fine detail by 2-4 % and correlated frames converged much slower (measured, see README "Photo mode").
 */
public final class PhotoMode {
	private PhotoMode() {}

	public static final List<Integer> SAMPLE_CHOICES = List.of(16, 64, 256);
	/** Settings ({@code photo.samples}, {@code photo.scale} in config/emberlight.properties). */
	public static volatile int samples = 64, scale = 1;
	/** Frames rendered on the frozen pose before the first sample: TAA/denoiser history and a 2× rebuild settle. */
	private static final int WARMUP = 24;
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(1500L);
	private static final float[] TO_LINEAR = new float[256];

	static {
		for (int i = 0; i < 256; i++) {
			double c = i / 255.0;
			TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
		}
	}

	private static volatile boolean active;
	/** Bumped on every start/abort, so a readback of an aborted photo is dropped. */
	private static int generation;
	private static int shotScale, shotSamples, seen, issued, done, srcW, srcH;
	private static float @Nullable [] acc;
	private static long startNanos;
	private static boolean hidHud, hadMouse;
	/** The upscaler preset before the photo switched to Native AA (render size = display size), or null. */
	private static UpscaleConfig.@Nullable Preset savedPreset;
	private static @Nullable String name;
	/** Requested from the command/key/menu while a screen is still open; starts once it is closed. */
	private static int @Nullable [] pending;
	/** PNG writes in flight (the autotest waits for them). */
	public static final java.util.concurrent.atomic.AtomicInteger writing = new java.util.concurrent.atomic.AtomicInteger();

	public static void load(Properties p) {
		samples = Math.clamp(parse(p.getProperty("photo.samples"), 64), 1, 4096);
		scale = Math.clamp(parse(p.getProperty("photo.scale"), 1), 1, 2);
	}

	private static int parse(@Nullable String v, int def) {
		try {
			return v == null ? def : Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/**
	 * Every frame of a photo stands alone: our RT histories (GI, block light, ReSTIR) and the upscaler's are reset each frame,
	 * so the average converges to the expectation instead of to a few correlated frames. Off = the histories keep running.
	 */
	public static volatile boolean resetRt = true, resetUpscaler = true;

	public static boolean independentRt() {
		return active && resetRt && seen > 0;
	}

	public static boolean independentUpscaler() {
		return active && resetUpscaler && seen > 0;
	}

	/** The pack's targets at 2× could not be allocated: go on at 1×. True if this failure was ours to handle. */
	public static boolean scaleFailed(RuntimeException e) {
		if (!active || shotScale == 1) return false;
		EmrionShadersClient.LOG.warn("Photo mode: 2× does not fit into GPU memory, taking the photo at 1×", e);
		Minecraft.getInstance().execute(() -> Minecraft.getInstance().showDebugChat(Component.literal("Photo: not enough GPU memory for 2×, taking it at 1×")));
		shotScale = 1;
		seen = 0;
		return true;
	}

	/** True while a photo renders: the game is frozen (read by the freeze mixins, IrisTimer and frame generation). */
	public static boolean frozen() {
		return active;
	}

	/** Takes a photo with the given settings as soon as no screen is open (the chat that ran the command closes first). */
	public static void request(int scale, int samples) {
		if (active) return;
		pending = new int[] {Math.clamp(scale, 1, 2), Math.clamp(samples, 1, 4096)};
	}

	/** End of every client tick: the pending start, the progress toast, cancelling (a screen opened, the world left). */
	public static void tick(Minecraft mc) {
		if (pending != null && !active && mc.gui.screen() == null && mc.level != null) {
			int[] p = pending;
			pending = null;
			start(mc, p[0], p[1], null);
		}
		if (!active) return;
		if (mc.level == null || mc.gui.screen() != null) {
			abort(mc, "cancelled");
			return;
		}
		if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse(); // a click grabs it again: no turning during the photo
		SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST, Component.literal("Photo mode"), Component.literal(seen <= WARMUP
				? "Preparing… (Esc cancels)" : done + " / " + shotSamples + " samples" + (shotScale > 1 ? " at " + shotScale + "×" : "")));
	}

	/** Starts a photo now; {@code fileName} null = screenshots/emberlight-photo-&lt;time&gt;.png. */
	public static void start(Minecraft mc, int scale, int samples, @Nullable String fileName) {
		if (active || mc.level == null) return;
		active = true;
		generation++;
		shotScale = scale;
		shotSamples = samples;
		seen = issued = done = 0;
		acc = null;
		name = fileName;
		startNanos = System.nanoTime();
		hidHud = !mc.gui.hud.isHidden();
		if (hidHud) mc.gui.hud.toggle();
		hadMouse = mc.mouseHandler.isMouseGrabbed();
		if (hadMouse) mc.mouseHandler.releaseMouse();
		IrisTimer.freeze(true);
		// a reset upscaler only upsamples one frame: averaging those is blurry below native, so render at native size meanwhile
		if (resetUpscaler && UpscaleConfig.effective() != UpscaleConfig.Mode.OFF
				&& UpscaleConfig.preset != UpscaleConfig.Preset.NATIVE) {
			savedPreset = UpscaleConfig.preset;
			UpscaleConfig.preset = UpscaleConfig.Preset.NATIVE;
			Upscaling.settingsChanged(); // not saved: the config file keeps the user's preset
		}
		EmrionShadersClient.LOG.info("Photo mode: {} samples at {}× ({}×{})", samples, scale, mc.getWindow().getWidth() * scale, mc.getWindow().getHeight() * scale);
	}

	private static void stop(Minecraft mc) {
		active = false;
		generation++;
		acc = null;
		IrisTimer.freeze(false);
		if (savedPreset != null) {
			UpscaleConfig.preset = savedPreset;
			savedPreset = null;
			Upscaling.settingsChanged();
		}
		if (hidHud && mc.gui.hud.isHidden()) mc.gui.hud.toggle();
		if (hadMouse && mc.gui.screen() == null) mc.mouseHandler.grabMouse();
		SystemToast.forceHide(mc.gui.toastManager(), TOAST);
	}

	private static void abort(Minecraft mc, String why) {
		stop(mc);
		EmrionShadersClient.LOG.info("Photo mode: {}", why);
		mc.gui.hud.setOverlayMessage(Component.literal("Photo " + why), false);
	}

	/** GameRenderer.render HEAD (after extract): a 2× photo renders into a main target of twice the window size. */
	public static void onRender(GameRenderer renderer) {
		if (!active || shotScale == 1) return;
		var w = renderer.gameRenderState().windowRenderState;
		w.width *= shotScale;
		w.height *= shotScale;
	}

	/** GameRenderer.render, after the level and post effects, before the GUI: queue this frame's readback as one sample. */
	public static void beforeGui(RenderTarget main) {
		if (!active) return;
		Minecraft mc = Minecraft.getInstance();
		GpuTexture tex = main.getColorTexture();
		if (tex == null || main.width != mc.getWindow().getWidth() * shotScale || main.height != mc.getWindow().getHeight() * shotScale) {
			seen = 0; // resizing (2×, or the window changed): start the warm-up again
			return;
		}
		if (++seen <= WARMUP || issued >= shotSamples) return;
		if (tex.getFormat().blockSize() != 4) {
			abort(mc, "failed: unsupported main target format " + tex.getFormat());
			return;
		}
		if (acc == null) {
			srcW = main.width;
			srcH = main.height;
			acc = new float[srcW / shotScale * (srcH / shotScale) * 3];
		} else if (main.width != srcW || main.height != srcH) {
			abort(mc, "cancelled: the window was resized");
			return;
		}
		issued++;
		int gen = generation, w = srcW, h = srcH;
		GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Emberlight photo sample", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * 4);
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(tex, buffer, 0L, () -> {
			try (GpuBufferSlice.MappedView read = buffer.map(true, false)) {
				float[] a = acc;
				if (gen == generation && a != null) {
					accumulate(read.data(), w, h, shotScale, a);
					if (++done == shotSamples) finish(mc, a, w / shotScale, h / shotScale);
				}
			} finally {
				buffer.close();
			}
		}, 0);
	}

	/** Adds one RGBA8 frame (bottom row first, as MC's Screenshot reads it) in linear light, box-filtered down by {@code s}. */
	static void accumulate(ByteBuffer src, int w, int h, int s, float[] acc) {
		int ow = w / s, oh = h / s;
		for (int y = 0; y < oh * s; y++) {
			int row = (oh - 1 - y / s) * ow * 3, in = y * w * 4;
			for (int x = 0; x < ow * s; x++) {
				int p = src.getInt(in + x * 4), o = row + x / s * 3;
				acc[o] += TO_LINEAR[p & 255];
				acc[o + 1] += TO_LINEAR[p >>> 8 & 255];
				acc[o + 2] += TO_LINEAR[p >>> 16 & 255];
			}
		}
	}

	/** Average of {@code weight} linear values, sRGB-encoded to 0..255. */
	static int encode(float sum, float weight) {
		double c = Math.clamp(sum / weight, 0.0, 1.0);
		return (int) Math.round(255.0 * (c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055));
	}

	private static void finish(Minecraft mc, float[] a, int ow, int oh) {
		float weight = shotSamples * shotScale * shotScale;
		NativeImage image = new NativeImage(ow, oh, false);
		for (int y = 0; y < oh; y++)
			for (int x = 0; x < ow; x++) {
				int o = (y * ow + x) * 3;
				image.setPixelABGR(x, y, 0xFF000000 | encode(a[o + 2], weight) << 16 | encode(a[o + 1], weight) << 8 | encode(a[o], weight));
			}
		double seconds = (System.nanoTime() - startNanos) / 1e9;
		int n = shotSamples, s = shotScale;
		String fileName = name;
		stop(mc);
		File dir = new File(mc.gameDirectory, "screenshots");
		dir.mkdirs();
		File file = new File(dir, fileName != null ? fileName : "emberlight-photo-" + Util.getFilenameFormattedDateTime() + ".png");
		writing.incrementAndGet();
		Util.ioPool().execute(() -> {
			try (image) {
				image.writeToFile(file);
				EmrionShadersClient.LOG.info("Photo mode: {} ({}×{}, {} samples at {}×) in {} s", file.getName(), ow, oh, n, s, String.format("%.1f", seconds));
				Component link = Component.literal(file.getName()).withStyle(ChatFormatting.UNDERLINE)
						.withStyle(st -> st.withClickEvent(new ClickEvent.OpenFile(file.getAbsoluteFile())));
				mc.execute(() -> mc.showDebugChat(Component.literal("Photo saved (" + n + " samples, "
						+ String.format("%.1f", seconds) + " s): ").append(link)));
			} catch (Exception e) {
				EmrionShadersClient.LOG.warn("Photo mode: could not save {}", file, e);
				mc.execute(() -> mc.showDebugChat(Component.translatable("screenshot.failure", e.getMessage())));
			} finally {
				writing.decrementAndGet();
			}
		});
	}
}
