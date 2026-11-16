package dev.emrion.shaders.converter.plan;

import dev.emrion.shaders.converter.expr.UniformCatalog;

import java.util.Map;
import java.util.Optional;

/** Small built-in uniform table for tests (a subset of Iris's built-ins). */
final class TestCatalog {
	private TestCatalog() {}

	private static final Map<String, String> TYPES = Map.ofEntries(
			Map.entry("gbufferModelView", "mat4"), Map.entry("gbufferModelViewInverse", "mat4"),
			Map.entry("gbufferProjection", "mat4"), Map.entry("gbufferProjectionInverse", "mat4"),
			Map.entry("gbufferPreviousModelView", "mat4"), Map.entry("gbufferPreviousProjection", "mat4"),
			Map.entry("shadowModelView", "mat4"), Map.entry("shadowProjection", "mat4"),
			Map.entry("iris_DefaultNormalMat", "mat3"),
			Map.entry("cameraPosition", "vec3"), Map.entry("previousCameraPosition", "vec3"),
			Map.entry("cameraPositionInt", "ivec3"), Map.entry("cameraPositionFract", "vec3"),
			Map.entry("sunPosition", "vec3"), Map.entry("skyColor", "vec3"), Map.entry("fogColor", "vec3"),
			Map.entry("lightningBoltPosition", "vec4"),
			Map.entry("eyeBrightness", "ivec2"), Map.entry("atlasSize", "ivec2"),
			Map.entry("near", "float"), Map.entry("far", "float"), Map.entry("viewWidth", "float"),
			Map.entry("viewHeight", "float"), Map.entry("rainStrength", "float"), Map.entry("frameTimeCounter", "float"),
			Map.entry("frameCounter", "int"), Map.entry("worldDay", "int"), Map.entry("isEyeInWater", "int"),
			Map.entry("isRightHanded", "bool"), Map.entry("hideGUI", "bool"),
			Map.entry("entityId", "int"), Map.entry("blockEntityId", "int"), Map.entry("currentRenderedItemId", "int"),
			Map.entry("entityColor", "vec4"), Map.entry("renderStage", "int"));

	static Optional<UniformCatalog.Builtin> lookup(String name) {
		String t = TYPES.get(name);
		if (t == null) return Optional.empty();
		String loc = switch (name) {
			case "entityId", "blockEntityId", "currentRenderedItemId", "entityColor" -> "push";
			case "renderStage" -> "phase";
			default -> "block";
		};
		return Optional.of(new UniformCatalog.Builtin(name, t, "per_frame", loc));
	}
}
