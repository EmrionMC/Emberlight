package dev.emrion.shaders.mod.vertex;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;

/**
 * Seam between geometry pipelines (agent g1) and vertex data (agent g2).
 * While a pack is active, terrain sections and entities are built with these formats; their element names are the
 * canonical input names of {@link dev.emrion.shaders.format.VertexLayouts#INPUT_LOCATIONS} (plus iris_Entity), and the
 * element GPU formats match the transpiler's canonical GLSL input types.
 * Stub: returns vanilla formats until agent g2 lands the extended ones.
 */
public final class EmrionVertexFormats {
	private EmrionVertexFormats() {}

	/** Extended terrain format (EMRION_BLOCK); vertex binding 0 of terrain pipelines. */
	public static VertexFormat terrain() { return DefaultVertexFormat.BLOCK; }

	/** Extended entity format (ENTITY + derived tangent/midTexCoord + iris_Entity). */
	public static VertexFormat entity() { return DefaultVertexFormat.ENTITY; }

	/** True while the extended formats are in use (pack active); switching triggers a chunk rebuild. */
	public static boolean active() { return false; }
}
