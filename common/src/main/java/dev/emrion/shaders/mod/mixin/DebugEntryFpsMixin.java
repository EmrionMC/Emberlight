package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.framegen.Presenter;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Why: MC's F3 FPS counts rendered frames only; with frame generation the presented rate and the generated frames go in front. */
@Mixin(DebugEntryFps.class)
abstract class DebugEntryFpsMixin {
	@ModifyArg(method = "display", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;addPriorityLine(Ljava/lang/String;)V"))
	private String emrion$generated(String line) {
		return Presenter.withGenerated(line);
	}
}
