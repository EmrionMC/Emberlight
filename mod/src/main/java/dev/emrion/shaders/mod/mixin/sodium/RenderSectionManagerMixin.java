package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Why: packs draw their own fog, so Sodium's fog occlusion would drop sections they still show (Iris turns it off too). */
@Mixin(RenderSectionManager.class)
public class RenderSectionManagerMixin {
	@Redirect(method = "getSearchDistance", at = @At(value = "FIELD", target = "Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;useFogOcclusion:Z"))
	private boolean emrion$noFogOcclusion(SodiumOptions.PerformanceSettings settings) {
		return !EmrionVertexFormats.active() && settings.useFogOcclusion;
	}
}
