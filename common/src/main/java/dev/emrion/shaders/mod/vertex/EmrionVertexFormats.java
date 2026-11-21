package dev.emrion.shaders.mod.vertex;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.emrion.shaders.mod.EmrionShadersClient;
import net.minecraft.client.Minecraft;

/**
 * Extended vertex formats used while a pack is active; element names are the canonical inputs of VertexLayouts (Sodium's
 * a_* names for SODIUM_TERRAIN). EMRION_ENTITY is used only in world and hand passes.
 */
public final class EmrionVertexFormats {
	private EmrionVertexFormats() {}

	/** Sodium's compact 20 B terrain vertex plus the extras packs read (at_midBlock as RGBA8_SINT); 40 B. */
	public static final VertexFormat SODIUM_TERRAIN = VertexFormat.builder(0)
			.addAttribute("a_Position", GpuFormat.RG32_UINT)
			.addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("a_TexCoord", GpuFormat.RG16_UINT)
			.addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT)
			.addAttribute("mc_Entity", GpuFormat.RG16_SINT)
			.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
			.addAttribute("at_tangent", GpuFormat.RGBA8_SNORM)
			.addAttribute("mc_midTexCoord", GpuFormat.RG16_UNORM)
			.addAttribute("at_midBlock", GpuFormat.RGBA8_SINT)
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

	/** World text as Iris's GLYPH: same elements as ENTITY, so it uses the entity vertex variant. */
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

	/** Extended entity format (ENTITY + derived tangent/midTexCoord + iris_Entity). */
	public static VertexFormat entity() { return ENTITY; }

	/** True while the extended formats are in use (pack active); switching triggers a chunk rebuild. */
	public static boolean active() { return active; }

	/** ENTITY draws use EMRION_ENTITY while active. */
	public static boolean entitiesActive() { return active; }

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
			dev.emrion.shaders.mod.platform.Platform.get().onClientTagsLoaded(() -> {
				BlockIdTable t = ids;
				if (t.hasTags()) Minecraft.getInstance().execute(() -> setIds(BlockIdTable.build(t.source)));
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
