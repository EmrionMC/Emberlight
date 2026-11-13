package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.rt.RtScene;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: feeds RtScene with "layer vertices uploaded" and "mesh released" (RAYTRACING.md §2.3, SectionTracker). */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class RenderSectionRtMixin {
	@Inject(method = "vertexBufferUploadCallback", at = @At("HEAD"))
	private void emrion$rtUploaded(CompiledSectionMesh mesh, ChunkSectionLayer layer, CallbackInfo ci) {
		RtScene.onVertexUploaded((SectionRenderDispatcher.RenderSection) (Object) this, mesh, layer);
	}

	@Inject(method = "releaseSectionMesh", at = @At("HEAD"))
	private void emrion$rtReleased(SectionMesh mesh, CallbackInfo ci) {
		RtScene.onMeshReleased(mesh);
	}
}
