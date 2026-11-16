package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.emrion.shaders.mod.rt.RtEntities;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Why: RT entities need each staged draw's render type (texture, solid/cutout); only this group knows the pairing. */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public class RenderTypeGroupRtMixin {
	@ModifyReturnValue(method = "getOrAddDraw", at = @At("RETURN"))
	private StagedVertexBuffer.Draw emrion$rtEntityDraw(StagedVertexBuffer.Draw draw, @Local PreparedRenderType type) {
		RtEntities.onDraw(draw, type);
		return draw;
	}
}
