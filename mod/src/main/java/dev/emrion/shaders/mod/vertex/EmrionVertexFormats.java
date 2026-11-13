package dev.emrion.shaders.mod.vertex;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.emrion.shaders.mod.EmrionShadersClient;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.minecraft.client.Minecraft;

/**
 * Seam between geometry pipelines (agent g1) and vertex data (agent g2).
 * While a pack is active, terrain sections and entities are built with these formats; their element names are the
 * canonical input names of {@link dev.emrion.shaders.format.VertexLayouts#INPUT_LOCATIONS}, and the element GPU formats match
 * the transpiler's canonical GLSL input types (FORMAT.md §7.1 has the table). Both are 56 bytes.
 *
 * <p>Terrain ({@code EMRION_BLOCK}): vanilla BLOCK + Normal (face normal), mc_Entity (block.properties id, 0 = block /
 * 1 = fluid), mc_midTexCoord, at_tangent and at_midBlock. at_midBlock is RGBA16_FLOAT so the shader gets Iris's values
 * directly: xyz = signed byte of (block centre − vertex)·64, w = the block's light emission (0–15).
 *
 * <p>Entities ({@code EMRION_ENTITY}): vanilla ENTITY + mc_midTexCoord, at_tangent and iris_Entity (entityId, blockEntityId,
 * currentRenderedItemId as RGBA16_UINT, w unused). Used for ENTITY draws prepared by the world and hand feature passes only
 * (the GUI keeps vanilla ENTITY), so g1 must swap every ENTITY pipeline drawn in those passes.
 */
public final class EmrionVertexFormats {
	private EmrionVertexFormats() {}

	public static final VertexFormat TERRAIN = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV2", GpuFormat.RG16_SINT)
			.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
			.addAttribute("mc_Entity", GpuFormat.RG16_SINT)
			.addAttribute("mc_midTexCoord", GpuFormat.RG32_FLOAT)
			.addAttribute("at_tangent", GpuFormat.RGBA8_SNORM)
			.addAttribute("at_midBlock", GpuFormat.RGBA16_FLOAT)
			.build();

	public static final VertexFormat ENTITY = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV1", GpuFormat.RG16_SINT)
			.addAttribute("UV2", GpuFormat.RG16_SINT)
			.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
			.addAttribute("mc_midTexCoord", GpuFormat.RG32_FLOAT)
			.addAttribute("at_tangent", GpuFormat.RGBA8_SNORM)
			.addAttribute("iris_Entity", GpuFormat.RGBA16_UINT)
			.build();

	/**
	 * World text (POSITION_TEX_LIGHTMAP_COLOR: maps, signs, name tags) as Iris's GLYPH: the same elements as {@link #ENTITY}, so
	 * it uses the entity vertex variant; ExtendedBufferBuilder fills Normal (face normal), UV1 (no overlay) and the rest.
	 */
	public static final VertexFormat GLYPH = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV1", GpuFormat.RG16_SINT)
			.addAttribute("UV2", GpuFormat.RG16_SINT)
			.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
			.addAttribute("mc_midTexCoord", GpuFormat.RG32_FLOAT)
			.addAttribute("at_tangent", GpuFormat.RGBA8_SNORM)
			.addAttribute("iris_Entity", GpuFormat.RGBA16_UINT)
			.build();

	private static volatile boolean active;
	/** shaders.properties separateAo / oldLighting of the active pack (set before the chunk rebuild of {@link #setActive}). */
	private static volatile boolean separateAo, oldLighting = true;
	private static volatile BlockIdTable ids = BlockIdTable.EMPTY;
	private static boolean tagListener;

	/** Extended terrain format (EMRION_BLOCK); vertex binding 0 of terrain pipelines. */
	public static VertexFormat terrain() { return TERRAIN; }

	/** Extended entity format (ENTITY + derived tangent/midTexCoord + iris_Entity). */
	public static VertexFormat entity() { return ENTITY; }

	/** True while the extended formats are in use (pack active); switching triggers a chunk rebuild. */
	public static boolean active() { return active; }

	/** ENTITY draws use EMRION_ENTITY: while active, except in the terrain-only debug view (no entity pipelines swapped there). */
	public static boolean entitiesActive() { return active && (!DebugVertex.ENABLED || DebugVertex.ENTITIES); }

	/** Iris separateAo: chunk vertex colour = tint, alpha = AO (Sodium's XHFPTerrainVertex), instead of AO baked into rgb. */
	public static boolean separateAo() { return active && separateAo; }

	/** Iris oldLighting=false disables MC's directional face shading (CardinalLighting.byFace → UP). */
	public static boolean directionalShadingOff() { return active && !oldLighting; }

	/** Render thread, before {@link #setActive}: the pack's lighting directives (they change how chunks are meshed). */
	public static void setLighting(boolean separateAo, boolean oldLighting) {
		EmrionVertexFormats.separateAo = separateAo;
		EmrionVertexFormats.oldLighting = oldLighting;
	}

	/** Current id tables (never null; {@link BlockIdTable#EMPTY} when no pack is loaded). */
	public static BlockIdTable ids() { return ids; }

	/** Render thread. Switching rebuilds all chunk sections so every mesh uses one format. */
	public static void setActive(boolean on) {
		if (active == on) return;
		active = on;
		rebuildChunks();
	}

	/** Render thread: install the pack's ids (from {@link BlockIdTable#build}); rebuilds chunks if active. */
	public static void setIds(BlockIdTable table) {
		ids = table;
		if (!tagListener) {
			tagListener = true;
			// Client tags arrive with the server's tag packet, after the pack may have loaded: re-resolve #tag matchers then.
			CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
				BlockIdTable t = ids;
				if (client && t.hasTags()) Minecraft.getInstance().execute(() -> setIds(BlockIdTable.build(t.source)));
			});
		}
		if (active) rebuildChunks();
	}

	private static void rebuildChunks() {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			mc.levelExtractor.allChanged();
			EmrionShadersClient.LOG.info("Vertex formats {} ({} block states mapped), chunks rebuild", active ? "extended" : "vanilla", ids.blockCount());
		});
	}
}
