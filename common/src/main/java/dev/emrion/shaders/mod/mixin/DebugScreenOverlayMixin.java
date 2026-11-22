package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.compat.BetterF3;
import dev.emrion.shaders.mod.gui.F3Info;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Why: BetterF3 swaps the enabled entries for its own and shows only the left/right lines it is handed (its "Misc" modules),
 * so our entry never runs. Then our lines go into the left list; a plain F3 screen already showed them through the entry.
 */
@Mixin(DebugScreenOverlay.class)
abstract class DebugScreenOverlayMixin {
	@Unique
	private static List<String> emrion$lastLines = List.of();

	@ModifyArg(method = "extractRenderState", index = 1, at = @At(value = "INVOKE", ordinal = 0,
			target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;ZI)V"))
	private List<String> emrion$f3Lines(List<String> left) {
		List<String> lines = !F3Info.displayed && Minecraft.getInstance().debugEntries.isCurrentlyEnabled(F3Info.ID) ? F3Info.lines() : List.of();
		left.addAll(lines);
		if (!lines.equals(emrion$lastLines)) BetterF3.refresh(); // also when they go away
		emrion$lastLines = lines;
		F3Info.displayed = false;
		return left;
	}
}
