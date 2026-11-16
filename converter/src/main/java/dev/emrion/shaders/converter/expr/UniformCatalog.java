package dev.emrion.shaders.converter.expr;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Every Iris built-in uniform with GLSL type and update frequency, following Iris CommonUniforms. */
public final class UniformCatalog {
	private UniformCatalog() {}

	/** frequency: per_frame | per_tick | per_phase | per_draw | once. location: block (EmrionUniforms) | phase (EmrionPhase) | push (EmrionDraw). */
	public record Builtin(String name, String glslType, String frequency, String location) {}

	/** name type frequency location input?  ("in" = usable inside custom-uniform expressions, i.e. Iris non-dynamic) */
	private static final String TABLE = """
			near float once block in
			far float per_frame block in
			cameraPosition vec3 per_frame block in
			eyeAltitude float per_frame block in
			previousCameraPosition vec3 per_frame block in
			cameraPositionInt ivec3 per_frame block in
			cameraPositionFract vec3 per_frame block in
			previousCameraPositionInt ivec3 per_frame block in
			previousCameraPositionFract vec3 per_frame block in
			viewHeight float per_frame block in
			viewWidth float per_frame block in
			aspectRatio float per_frame block in
			worldTime int per_tick block in
			worldDay int per_tick block in
			moonPhase int per_tick block in
			frameCounter int per_frame block in
			frameTime float per_frame block in
			frameTimeCounter float per_frame block in
			biome int per_tick block in
			biome_category int per_tick block in
			biome_precipitation int per_tick block in
			rainfall float per_tick block in
			temperature float per_tick block in
			sunAngle float per_frame block in
			sunPosition vec3 per_frame block in
			moonPosition vec3 per_frame block in
			shadowAngle float per_frame block in
			shadowLightPosition vec3 per_frame block in
			endFlashPosition vec3 per_frame block in
			upPosition vec3 per_frame block in
			currentColorSpace int per_tick block in
			chunkFadeTimeInv float per_frame block in
			textureFilteringMode int per_frame block in
			bedrockLevel int per_frame block in
			cloudHeight float per_frame block in
			heightLimit int per_frame block in
			logicalHeightLimit int per_frame block in
			hasCeiling bool per_frame block in
			hasSkylight bool per_frame block in
			ambientLight float per_frame block in
			feetInWater bool per_tick block in
			inSwimmingAnimation bool per_tick block in
			isRiding bool per_tick block in
			isElytraFlying bool per_tick block in
			vehicleInWater bool per_tick block in
			vehicleId int per_tick block in
			vehicleLookVector vec3 per_frame block in
			relativeVehiclePosition vec3 per_frame block in
			thunderStrength float per_frame block in
			currentPlayerHealth float per_tick block in
			heavyFog bool per_tick block in
			maxPlayerHealth float per_tick block in
			currentPlayerHunger float per_tick block in
			maxPlayerHunger float per_tick block in
			endFlashIntensity float per_tick block in
			previousEndFlashIntensity float per_tick block in
			currentPlayerArmor float per_tick block in
			maxPlayerArmor float per_tick block in
			currentPlayerAir float per_tick block in
			maxPlayerAir float per_tick block in
			firstPersonCamera bool per_frame block in
			isSpectator bool per_tick block in
			currentSelectedBlockId int per_frame block in
			seaLevel int per_frame block in
			currentSelectedBlockPos vec3 per_frame block in
			eyePosition vec3 per_frame block in
			cloudTime float per_tick block in
			relativeEyePosition vec3 per_frame block in
			playerLookVector vec3 per_frame block in
			playerBodyVector vec3 per_frame block in
			lightningBoltPosition vec4 per_tick block in
			currentDate ivec3 per_tick block in
			currentTime ivec3 per_tick block in
			currentYearTime ivec2 per_tick block in
			gbufferModelView mat4 per_frame block in
			gbufferModelViewInverse mat4 per_frame block in
			gbufferPreviousModelView mat4 per_frame block in
			gbufferProjection mat4 per_frame block in
			gbufferProjectionInverse mat4 per_frame block in
			gbufferPreviousProjection mat4 per_frame block in
			dhProjection mat4 per_frame block in
			dhProjectionInverse mat4 per_frame block in
			dhPreviousProjection mat4 per_frame block in
			shadowModelView mat4 per_frame block in
			shadowModelViewInverse mat4 per_frame block in
			shadowProjection mat4 per_frame block in
			shadowProjectionInverse mat4 per_frame block in
			heldItemId int per_frame block in
			heldItemId2 int per_frame block in
			heldBlockLightValue int per_frame block in
			heldBlockLightValue2 int per_frame block in
			heldBlockLightColor vec3 per_frame block in
			heldBlockLightColor2 vec3 per_frame block in
			hideGUI bool per_frame block in
			isRightHanded bool per_frame block in
			isEyeInWater int per_frame block in
			blindness float per_frame block in
			darknessFactor float per_frame block in
			darknessLightFactor float per_frame block in
			nightVision float per_frame block in
			is_sneaking bool per_frame block in
			is_sprinting bool per_frame block in
			is_hurt bool per_frame block in
			is_invisible bool per_frame block in
			is_burning bool per_frame block in
			is_on_ground bool per_frame block in
			screenBrightness float per_frame block in
			entityColor vec4 per_draw push in
			blockEntityId int per_draw push in
			currentRenderedItemId int per_draw push in
			anisotropicFiltering int per_frame block in
			pi float once block in
			playerMood float per_tick block in
			constantMood float per_tick block in
			eyeBrightness ivec2 per_frame block in
			eyeBrightnessSmooth ivec2 per_frame block in
			rainStrength float per_tick block in
			wetness float per_tick block in
			skyColor vec3 per_frame block in
			dhFarPlane float per_frame block in
			dhNearPlane float per_frame block in
			dhRenderDistance int per_frame block in
			iris_DefaultNormalMat mat3 per_frame block in
			iris_DefaultProjectionMatrixInverse mat4 per_frame block in
			iris_DefaultModelViewMatrixInverse mat4 per_frame block in
			iris_ShadowModelViewMatrixInverse mat4 per_frame block in
			iris_ShadowProjectionMatrixInverse mat4 per_frame block in
			iris_LightmapTextureMatrix mat4 once block -
			centerDepthSmooth float per_frame block -
			iris_ScreenSize vec2 per_frame block -
			fogColor vec3 per_frame block -
			entityId int per_draw push -
			atlasSize ivec2 per_draw push -
			gtextureId int per_draw push -
			gtextureSize ivec2 per_draw push -
			textureReloadCount int per_draw push -
			blendFunc ivec4 per_draw push -
			alphaTestRef float per_draw push -
			iris_currentAlphaTest float per_draw push -
			renderStage int per_phase phase -
			fogMode int per_phase phase -
			fogShape int per_phase phase -
			fogDensity float per_phase phase -
			fogStart float per_phase phase -
			fogEnd float per_phase phase -
			iris_FogColor vec4 per_phase phase -
			iris_FogStart float per_phase phase -
			iris_FogEnd float per_phase phase -
			iris_FogDensity float per_phase phase -
			""";

	private static final Map<String, Builtin> BY_NAME = new LinkedHashMap<>();
	private static final Set<String> INPUTS = new TreeSet<>();

	static {
		for (String line : TABLE.strip().split("\n")) {
			String[] p = line.strip().split(" ");
			BY_NAME.put(p[0], new Builtin(p[0], p[1], p[2], p[3]));
			if (p[4].equals("in")) INPUTS.add(p[0]);
		}
	}

	/** Vanilla 26.3 biome ids (data/minecraft/worldgen/biome). Only used to warn about BIOME_* typos; ids stay symbolic. */
	public static final Set<String> VANILLA_BIOMES = Set.of("badlands", "bamboo_jungle", "basalt_deltas", "beach",
			"birch_forest", "cherry_grove", "cold_ocean", "crimson_forest", "dappled_forest", "dark_forest", "deep_cold_ocean",
			"deep_dark", "deep_frozen_ocean", "deep_lukewarm_ocean", "deep_ocean", "desert", "dripstone_caves", "end_barrens",
			"end_highlands", "end_midlands", "eroded_badlands", "flower_forest", "forest", "frozen_ocean", "frozen_peaks",
			"frozen_river", "grove", "ice_spikes", "jagged_peaks", "jungle", "lukewarm_ocean", "lush_caves", "mangrove_swamp",
			"meadow", "mushroom_fields", "nether_wastes", "ocean", "old_growth_birch_forest", "old_growth_pine_taiga",
			"old_growth_spruce_taiga", "pale_garden", "plains", "river", "savanna", "savanna_plateau", "small_end_islands",
			"snowy_beach", "snowy_plains", "snowy_slopes", "snowy_taiga", "soul_sand_valley", "sparse_jungle", "stony_peaks",
			"stony_shore", "sulfur_caves", "sunflower_plains", "swamp", "taiga", "the_end", "the_void", "warm_ocean",
			"warped_forest", "windswept_forest", "windswept_gravelly_hills", "windswept_hills", "windswept_savanna",
			"wooded_badlands");

	public static Optional<Builtin> lookup(String name) {
		return Optional.ofNullable(BY_NAME.get(name));
	}

	public static Collection<Builtin> all() {
		return Collections.unmodifiableCollection(BY_NAME.values());
	}

	/** Built-ins a custom-uniform expression may use: only Iris's non-dynamic uniforms, the per-draw ids at their ONCE values. */
	public static Set<String> expressionInputs() {
		return Collections.unmodifiableSet(INPUTS);
	}

	/** Expression-language type of an input built-in, or null if name is not an input. */
	static Type inputType(String name) {
		return INPUTS.contains(name) ? Type.of(BY_NAME.get(name).glslType()) : null;
	}
}
