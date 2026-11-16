package dev.emrion.shaders.mod.uniforms;

import dev.emrion.shaders.format.Manifest;

import java.nio.ByteBuffer;

/** Fills the std140 uniform blocks. Matrices are in GL convention; render thread only, no per-frame allocation. */
public interface UniformSystem {

	/** Iris render phases that change the EmrionPhase block. renderStage ordinals are set per draw group via {@link #setRenderStage}. */
	enum Phase { WORLD, HAND, SHADOW, FULLSCREEN }

	/** Called once at the start of each frame (before the shadow pass): advance time, smooth values, custom uniforms. */
	void beginFrame(float partialTick);

	/** Writes the whole EmrionUniforms block (layout.size() bytes) at dst.position(). */
	void writeFrameBlock(ByteBuffer dst);

	/** Writes the EmrionPhase block (layout.phase().size() bytes) for the given phase and the current renderStage. */
	void writePhaseBlock(ByteBuffer dst, Phase phase);

	void setRenderStage(int irisOrdinal);

	/** Shadow camera (GL convention), valid after {@link #beginFrame}; used by the executor to cull and draw the shadow pass. */
	org.joml.Matrix4fc shadowModelView();

	org.joml.Matrix4fc shadowProjection();

	/** Player camera rotation and projection (gbufferModelView / gbufferProjection, GL convention), for shadow culling. */
	org.joml.Matrix4fc gbufferModelView();

	org.joml.Matrix4fc gbufferProjection();

	/** Sky/fog colour for colortex0's default clear (Iris: fog colour, alpha 1). */
	org.joml.Vector4fc clearFogColor();
}
