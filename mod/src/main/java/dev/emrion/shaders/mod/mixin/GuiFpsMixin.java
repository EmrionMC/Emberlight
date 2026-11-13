package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.emrion.shaders.mod.debug.Profiler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: the user wants a plain FPS counter in the top-left corner at all times, including with F1 (hidden HUD), which
 * skips every HUD element. Injecting right after the HUD block of Gui.extractRenderState draws it independently of
 * hideGui, below screens/overlays. Off during autotests so comparison screenshots stay clean. The profiler overlay (F7) is
 * drawn below it the same way.
 */
@Mixin(Gui.class)
abstract class GuiFpsMixin {
	private static final boolean DISABLED = Boolean.getBoolean("emrion.hideFps")
			|| (System.getProperty("emrion.autotest.world") != null && !Boolean.getBoolean("emrion.showFps"));

	@Inject(method = "extractRenderState", at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/Gui;overlay:Lnet/minecraft/client/gui/screens/Overlay;", ordinal = 0))
	private void emrion$fps(DeltaTracker deltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded, CallbackInfo ci,
	                        @Local GuiGraphicsExtractor graphics) {
		if (!shouldRenderLevel) return;
		Minecraft mc = Minecraft.getInstance();
		if (!DISABLED) {
			String fps = mc.getFps() + " FPS";
			graphics.fill(1, 1, 3 + mc.font.width(fps), 11, 0x90505050); // F3's backdrop: readable on a bright sky
			graphics.text(mc.font, fps, 2, 2, 0xFFFFFFFF, false);
		}
		if (!Profiler.visible) return;
		int y = 12;
		for (String line : Profiler.lines()) {
			graphics.text(mc.font, line, 2, y, 0xFFFFFFFF, true);
			y += 9;
		}
	}
}
