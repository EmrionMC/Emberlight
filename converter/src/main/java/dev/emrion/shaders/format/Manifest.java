package dev.emrion.shaders.format;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * Pack manifest, the converter/mod contract (docs/FORMAT.md; field name = JSON key, nullable fields are omitted).
 * Change FORMAT.md first, then bump {@link #FORMAT_VERSION}.
 */
public record Manifest(
		int formatVersion,
		String converterVersion,
		String cacheKey,
		PackInfo pack,
		Environment environment,
		Options options,
		Map<String, Object> properties,
		UniformLayout uniforms,
		IdMaps idMaps,
		List<TextureDef> textures,
		List<DimensionMapping> dimensionMap,
		Map<String, Dimension> dimensions,
		Rt rt
) {
	public static final int FORMAT_VERSION = 2;

	/** Without the optional {@code rt} section. */
	public Manifest(int formatVersion, String converterVersion, String cacheKey, PackInfo pack, Environment environment, Options options,
	                Map<String, Object> properties, UniformLayout uniforms, IdMaps idMaps, List<TextureDef> textures,
	                List<DimensionMapping> dimensionMap, Map<String, Dimension> dimensions) {
		this(formatVersion, converterVersion, cacheKey, pack, environment, options, properties, uniforms, idMaps, textures, dimensionMap,
				dimensions, null);
	}

	/**
	 * RT variant (absent = no RT requested). effects: applied effects with options ("shadows:full");
	 * unavailable: effect → reason; patched: effect → patched "dim/program.stage" list; anyHitIds: block ids (mc_Entity) whose
	 * cutout hits a patched shader decides per id (an active skip list), so the mod's opacity micromaps never make them opaque
	 * (absent in older caches = none).
	 */
	public record Rt(List<String> effects, Map<String, String> unavailable, Map<String, List<String>> patched, List<Integer> anyHitIds) {
		public Rt {
			anyHitIds = anyHitIds == null ? List.of() : anyHitIds;
		}
	}

	public record PackInfo(String name, String sha256) {}

	public record Environment(String mcVersion, Map<String, String> macros) {}

	// ---- options ----

	public record Options(
			String settingsFile,
			Map<String, String> values,
			List<OptionDecl> declared,
			List<String> unknownSettings,
			Map<String, Map<String, String>> profiles,
			Map<String, List<String>> screens,
			List<String> sliders,
			Map<String, Integer> columns
	) {}

	/** kind: define_value | define_bool | const; type: int | float | bool | string. */
	public record OptionDecl(String name, String kind, String type, String defaultValue, List<String> allowed,
	                         String file, int line, String comment) {}

	// ---- uniforms ----

	public record UniformLayout(String blockName, int size, List<UniformMember> members, List<CustomUniform> custom,
	                            Block phase, Block pushConstants) {}

	public record Block(String blockName, int size, List<UniformMember> members) {}

	/** source: builtin | custom. arraySize/arrayStride are 0 for non-arrays. */
	public record UniformMember(String name, String type, int offset, int arraySize, int arrayStride, String source) {}

	/** expr: serialised AST, kept as JSON (the expr package owns its schema). */
	public record CustomUniform(String name, String type, boolean uniform, JsonObject expr) {}

	// ---- id maps ----

	public record IdMaps(List<IdEntry> blocks, List<IdEntry> items, List<IdEntry> entities, Map<String, List<String>> layers) {}

	public record IdEntry(int id, List<String> match) {}

	// ---- dimension plan ----

	public record DimensionMapping(String match, String folder) {}

	public record Dimension(
			Map<String, Program> programs,
			List<Resource> resources,
			List<Target> targets,
			List<FlipState> flipStates,
			List<Command> frame,
			List<Command> once,
			Shadow shadow
	) {}

	/** kind: gbuffers | shadow | composite | compute; group: gbuffers | shadow | begin | prepare | deferred | composite | final | setup | shadowcomp. */
	public record Program(
			String kind,
			String group,
			List<String> fallbackFor,
			Stages stages,
			List<Output> outputs,
			Blend blend,
			AlphaTest alphaTest,
			List<Set0Ref> set0,
			List<Integer> set1,
			List<Integer> writesStorage,
			boolean usesPushConstants,
			int[] localSize,
			int[] workGroups,
			float[] workGroupsRender,
			List<String> mipmapBefore,
			float viewportScale,
			Integer renderStageOrdinal,
			float[] viewportOffset // scale.<program> offset [x, y] as a fraction of the target size; null = 0 0
	) {
		public Program(String kind, String group, List<String> fallbackFor, Stages stages, List<Output> outputs, Blend blend, AlphaTest alphaTest,
				List<Set0Ref> set0, List<Integer> set1, List<Integer> writesStorage, boolean usesPushConstants, int[] localSize, int[] workGroups,
				float[] workGroupsRender, List<String> mipmapBefore, float viewportScale, Integer renderStageOrdinal) {
			this(kind, group, fallbackFor, stages, outputs, blend, alphaTest, set0, set1, writesStorage, usesPushConstants, localSize, workGroups,
					workGroupsRender, mipmapBefore, viewportScale, renderStageOrdinal, null);
		}
	}

	/** tessControl/tessEval/geometry have one module per vertex variant, like vertex (they read variant-dependent transforms). */
	public record Stages(VertexStage vertex, VertexStage tessControl, VertexStage tessEval, VertexStage geometry, SpvRef fragment, SpvRef compute) {}

	/** variant key (see {@link VertexLayouts}) → spv path. Composite-like programs use the single key "fullscreen". */
	public record VertexStage(Map<String, String> variants) {}

	public record SpvRef(String spv) {}

	public record Output(int location, String target) {}

	/** default/perTarget values: "vanilla", "off", or a factor spec. */
	public record Blend(BlendMode defaultMode, Map<String, BlendMode> perTarget) {}

	/** mode: vanilla | off | custom (then src/dst/srcA/dstA are GL-style factor names in lower_snake_case). */
	public record BlendMode(String mode, String src, String dst, String srcA, String dstA) {}

	/** func: never | less | equal | lequal | greater | notequal | gequal | always. */
	public record AlphaTest(String func, float ref) {}

	/** name from {@link VertexLayouts#SET0_CANONICAL}; type: uniform_buffer | combined_image_sampler | texel_buffer. */
	public record Set0Ref(String name, String type) {}

	/**
	 * kind: uniform_buffer_dynamic | sampled_image | storage_image | storage_buffer | acceleration_structure.
	 * RT variants add builtins such as emrion_tlas, emrion_rt_sections, emrion_rt_lights and emrion_rtao.
	 * Exactly one of target (a {@link Target#name()}), texture (a {@link TextureDef#name()}) or builtin is set for images.
	 * builtin: block_atlas | white | white_depth. sampler null = the texture's own sampling (custom textures:
	 * TextureDef filter/wrap; atlases: MC's atlas sampler).
	 */
	public record Resource(int binding, String kind, String name, String target, String texture, String builtin, SamplerDef sampler) {}

	/** filter: nearest | linear; mipmap: none | nearest | linear; wrap: clamp | repeat; compare: null or a func name. */
	public record SamplerDef(String filter, String mipmap, String wrap, String compare) {}

	/**
	 * kind: color | depth | shadow_color | image | buffer. size: relative [w,h] scale or absolute [w,h(,d)]; per axis a
	 * non-zero absoluteSize[i] wins over relativeSize[i] (size.buffer may mix both). mipLevels: 1, or 0 = full chain.
	 * clearColor: [r,g,b,a] or null with clearFog=true; depth targets clear to 1.0. buffer: bytes (per pixel if relative).
	 */
	public record Target(String name, String kind, String format, String declaredFormat, int dims,
	                     float[] relativeSize, int[] absoluteSize, int mipLevels, boolean hasAlt,
	                     boolean clear, float[] clearColor, boolean clearFog, long bytes) {}

	/**
	 * For textures copied from the pack (file) or resolved from MC (resource). rawPixel: the GL pixel format and type of a
	 * raw texture ("RGB HALF_FLOAT"), null for PNGs; the copied file then holds tightly packed texels of {@code format}.
	 */
	public record TextureDef(String name, String file, String resource, String format, int dims, int[] size,
	                         String filter, String wrap, boolean mipmap, String rawPixel) {}

	/** alt: colortex indices read from the alt image; shadowAlt: the same for shadowcolor (null = none). */
	public record FlipState(int id, List<Integer> alt, List<Integer> shadowAlt) {
		public FlipState(int id, List<Integer> alt) { this(id, alt, null); }
	}

	/**
	 * One frame command. cmd: clear | compute | fullscreen | mipmaps | geometry | copy_depth | copy | final.

	 * Unused fields are null.
	 */
	public record Command(String cmd, String program, Integer flipState, String phase, String target, String side,
	                      String from, String to, List<Attachment> writes, List<ClearOp> targets) {}

	/** side: main | alt. */
	public record Attachment(int location, String target, String side) {}

	/** side: main | alt | both. color null means "use the target's clear color". */
	public record ClearOp(String name, String side, float[] color) {}

	public record Shadow(boolean enabled, int resolution, float distance, float distanceRenderMul, float entityDistanceMul,
	                     float nearPlane, float farPlane, Float fov, float intervalSize, float sunPathRotation,
	                     boolean[] hardwareFiltering, String culling, float voxelDistance) {}
}
