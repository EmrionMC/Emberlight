package dev.emrion.shaders.mod.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Iris's HorizonRenderer: an inverted octagonal cone drawn with the SKY pipeline before the sky disc, so the pack's skybasic
 * also shades below the horizon (otherwise the area beyond the terrain stays a flat grey wall).
 */
public final class HorizonBox {
	private HorizonBox() {}

	private static @Nullable GpuBuffer buffer;
	private static int radius;

	/** Draws the cone into {@code pass} (our sky phase pass, so SKY becomes the pack's skybasic). */
	public static void draw(RenderPass pass) {
		Minecraft mc = Minecraft.getInstance();
		int r = Math.min(mc.options.getEffectiveRenderDistance() * 16, 256);
		if (buffer == null || r != radius) rebuild(r);
		Vector4f fog = new Vector4f(mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.fogData.color);
		fog.w = 1.0f; // Iris: alpha must be 1
		pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.SKY));
		pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(), fog));
		pass.setVertexBuffer(0, buffer.slice());
		pass.draw(10, 1, 0, 0);
	}

	public static void close() {
		if (buffer != null) buffer.close();
		buffer = null;
	}

	private static void rebuild(int r) {
		if (buffer != null) buffer.close();
		radius = r;
		try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(10 * DefaultVertexFormat.POSITION.getVertexSize())) {
			BufferBuilder b = new BufferBuilder(bytes, PrimitiveTopology.TRIANGLE_FAN, DefaultVertexFormat.POSITION);
			b.addVertex(0.0f, -16.0f, 0.0f);
			for (int i = 0; i <= 8; i++) {
				double a = -i * Math.PI / 4.0;
				b.addVertex((float) (r * Math.cos(a)), 16.0f, (float) (r * Math.sin(a)));
			}
			try (MeshData mesh = b.buildOrThrow()) {
				buffer = RenderSystem.getDevice().createBuffer(() -> "Emrion horizon", 32, mesh.vertexBuffer());
			}
		}
	}
}
