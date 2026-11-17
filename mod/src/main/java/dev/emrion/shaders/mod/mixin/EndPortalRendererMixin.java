package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.emrion.shaders.mod.uniforms.IrisTimer;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Why: with a pack, Iris draws end portals and gateways as entity_solid with the end_portal texture and full entity vertices
 * (MixinTheEndPortalRenderer/MixinTheEndGatewayRenderer). Packs build their portal effect on that: Complementary samples tex
 * as the starfield and keys on blockEntityId, which only the entity format carries per vertex. The vanilla end_portal
 * pipeline gives position-only vertices with end_sky as Sampler0, so the effect came out flat (water-like) or white.
 */
@Mixin(AbstractEndPortalRenderer.class)
public class EndPortalRendererMixin {
	@Shadow @Final private static Map<Direction, List<Vector3fc>> FACES;

	@WrapOperation(method = "submitCube(Ljava/util/Collection;Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Ljava/util/function/Consumer;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V"))
	private static void emrion$irisPortal(SubmitNodeCollector collector, PoseStack poseStack, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer,
			Operation<Void> original, @Local(argsOnly = true) Collection<Direction> faces) {
		if (!EmrionVertexFormats.active() || type != RenderTypes.endPortal() && type != RenderTypes.endGateway()) {
			original.call(collector, poseStack, type, renderer);
			return;
		}
		// shortcut: Iris scrolls by its own frameTimeCounter; ours is not reachable here, so only the phase differs (pinned in autotests)
		float t = (IrisTimer.FIXED >= 0 ? IrisTimer.FIXED : IrisTimer.wallSeconds()) * 0.01f % 1f;
		original.call(collector, poseStack, RenderTypes.entitySolid(AbstractEndPortalRenderer.END_PORTAL_LOCATION),
				(SubmitNodeCollector.CustomGeometryRenderer) (pose, consumer) -> {
					for (Direction d : faces) {
						List<Vector3fc> v = FACES.get(d);
						for (int i = 0; i < 4; i++) {
							Vector3fc p = v.get(i);
							float u = i >= 2 ? 0.2f : 0f, w = i == 1 || i == 2 ? 0.2f : 0f; // Iris's corner UVs
							consumer.addVertex(pose, p.x(), p.y(), p.z()).setColor(0.075f, 0.15f, 0.2f, 1f).setUv(u + t, w + t)
									.setOverlay(OverlayTexture.NO_OVERLAY).setLight(15728880).setNormal(pose, d.getStepX(), d.getStepY(), d.getStepZ());
						}
					}
				});
	}
}
