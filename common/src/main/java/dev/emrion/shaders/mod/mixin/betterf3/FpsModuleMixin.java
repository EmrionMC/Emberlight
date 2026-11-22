package dev.emrion.shaders.mod.mixin.betterf3;

import dev.emrion.shaders.mod.framegen.Presenter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Why: BetterF3's FPS module replaces F3's FPS line; the same presented/generated text as DebugEntryFpsMixin (only when BetterF3 is installed). */
@Pseudo
@Mixin(targets = "me.cominixo.betterf3.modules.FpsModule", remap = false)
public class FpsModuleMixin {
	@ModifyArg(method = "update", require = 0, at = @At(value = "INVOKE",
			target = "Lme/cominixo/betterf3/utils/Utils;styledText(Ljava/lang/Object;Lnet/minecraft/network/chat/TextColor;)Lnet/minecraft/network/chat/MutableComponent;"))
	private Object emrion$generated(Object text) {
		return text instanceof String s ? Presenter.withGenerated(s) : text;
	}
}
