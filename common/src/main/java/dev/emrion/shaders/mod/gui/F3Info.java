package dev.emrion.shaders.mod.gui;

import com.mojang.blaze3d.platform.Window;
import dev.emrion.shaders.converter.nativefmt.NativeCompiler;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.framegen.FrameGen;
import dev.emrion.shaders.mod.framegen.FrameGenConfig;
import dev.emrion.shaders.mod.framegen.Reflex;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.platform.Platform;
import dev.emrion.shaders.mod.rt.RtCapability;
import dev.emrion.shaders.mod.rt.RtScene;
import dev.emrion.shaders.mod.upscale.UpscaleConfig;
import dev.emrion.shaders.mod.upscale.Upscaling;
import dev.emrion.shaders.mod.upscale.VanillaUpscale;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/** Our F3 debug screen entry (emberlight:state): what really runs, like the main screen's tiles (never "on" when it is not). */
public final class F3Info implements DebugScreenEntry {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(EmrionShadersClient.MOD_ID, "state");
	private static final String VERSION = Platform.get().modVersions().getOrDefault(EmrionShadersClient.MOD_ID, "?");
	/** Set by {@link #display}; DebugScreenOverlayMixin adds the lines itself when an F3 replacement (BetterF3) skipped us. */
	public static boolean displayed;
	/** The ray-traced effects of RtConfig.request(); the rest of a manifest's effects (upscale, prepass, materials, ...) is plumbing. */
	private static final List<String> RT_EFFECTS = List.of(NativeCompiler.RT_EFFECT, "shadows", "reflections", "gi", "ao", "blocklight", "caustics", "refraction");

	@Override
	public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
		displayed = true;
		displayer.addToGroup(ID, lines());
	}

	@Override
	public boolean isAllowed(boolean reducedDebugInfo) {
		return true; // no world information in it
	}

	public static List<String> lines() {
		List<String> out = new ArrayList<>(5);
		String status = PackManager.status;
		out.add("Emberlight " + VERSION + ": " + (status.length() > 70 ? status.substring(0, 67) + "..." : status));

		Manifest.Rt rt = PackManager.installedRt();
		List<String> fx = rt == null ? List.of() : rt.effects().stream().map(e -> e.split(":", 2)[0]).filter(RT_EFFECTS::contains).distinct().toList();
		boolean rtOn = RtCapability.enabled && !fx.isEmpty();
		if (!rtOn) out.add(RtCapability.enabled ? "RT: off" : "RT: off (not supported)");
		else if (fx.contains(NativeCompiler.RT_EFFECT)) out.add("RT: on, built into the pack");
		else out.add("RT: on, " + (rt.effects().contains("materials") /* only the RTX style requests it */ ? "RTX" : "Faithful") + " style: " + String.join(", ", fx));
		RtScene scene = RtScene.get();
		if (rtOn && scene != null) out.add(scene.f3Line());

		UpscaleConfig.Mode up = UpscaleConfig.effective();
		if (up != UpscaleConfig.Mode.OFF && (Upscaling.active() || VanillaUpscale.upscaling())) {
			Window w = Minecraft.getInstance().getWindow();
			// as PackRuntime.initUpscaler decides; the upscaler without a pack never uses RR
			boolean rr = Upscaling.active() && UpscaleConfig.rrActive() && (rt == null || !rt.unavailable().containsKey("rr"));
			int[] r = Upscaling.renderSize(w.getWidth(), w.getHeight());
			out.add("Upscaler: " + up.shortLabel + (rr ? " RR " : " ") + UpscaleConfig.preset.label + ", " + r[0] + "x" + r[1]
					+ " -> " + w.getWidth() + "x" + w.getHeight());
		} else out.add("Upscaler: off");

		FrameGenConfig.Mode fg = FrameGenConfig.effective();
		boolean fgOn = fg != FrameGenConfig.Mode.OFF && EmberScreen.frameGenFallback(FrameGenConfig.mode) == null;
		Reflex.Mode reflex = Reflex.effective();
		out.add("Frame gen: " + (!fgOn ? "off" : (fg == FrameGenConfig.Mode.DLSS ? "DLSS" : "FSR 3") + (FrameGen.bypassed() ? " (paused, at refresh rate)" : ""))
				+ (reflex != Reflex.Mode.OFF ? ", Reflex " + reflex.label : ""));
		return out;
	}
}
