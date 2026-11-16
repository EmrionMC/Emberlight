package dev.emrion.shaders.converter.plan;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;

/** Typed pack-wide flags for {@code Manifest.properties} with Iris defaults. */
public final class PackProperties {
	private PackProperties() {}

	public static Map<String, Object> from(SequencedMap<String, String> p) {
		Map<String, Object> m = new LinkedHashMap<>();
		bool(m, p, "sun", "sun", true);
		bool(m, p, "moon", "moon", true);
		bool(m, p, "stars", "stars", true);
		bool(m, p, "sky", "sky", true);
		String clouds = p.get("clouds");
		if (clouds != null) m.put("clouds", clouds.trim().toLowerCase(Locale.ROOT));
		bool(m, p, "underwaterOverlay", "underwaterOverlay", false);
		bool(m, p, "vignette", "vignette", false);
		bool(m, p, "oldLighting", "oldLighting", false);
		bool(m, p, "oldHandLight", "oldHandLight", true);
		bool(m, p, "separateAo", "separateAo", false);
		bool(m, p, "supportsColorCorrection", "supportsColorCorrection", false);
		bool(m, p, "beaconBeamDepth", "beacon.beam.depth", false);
		bool(m, p, "rainDepth", "rain.depth", false);
		bool(m, p, "separateEntityDraws", "separateEntityDraws", false);
		String weather = p.get("weather");
		String[] w = weather == null ? new String[0] : weather.trim().split("\\s+");
		m.put("weather", w.length == 0 || w[0].equals("true"));
		m.put("weatherParticles", w.length < 2 || w[1].equals("true"));
		// particles.ordering wins over particles.before.deferred; separateEntityDraws implies mixed. Absent = unset.
		String ordering = p.get("particles.ordering");
		if (ordering != null) m.put("particlesOrdering", ordering.trim().toLowerCase(Locale.ROOT));
		else if (Boolean.TRUE.equals(parse(p.get("particles.before.deferred")))) m.put("particlesOrdering", "before");
		else if (Boolean.TRUE.equals(m.get("separateEntityDraws"))) m.put("particlesOrdering", "mixed");
		m.put("shadowCulling", switch (p.getOrDefault("shadow.culling", "").trim()) {
			case "false" -> "distance";
			case "true" -> "advanced";
			case "reversed", "safe_zone" -> "reversed";
			default -> "default";
		});
		bool(m, p, "shadowTerrain", "shadowTerrain", true);
		bool(m, p, "shadowTranslucent", "shadowTranslucent", true);
		bool(m, p, "shadowEntities", "shadowEntities", true);
		bool(m, p, "shadowPlayer", "shadowPlayer", false);
		bool(m, p, "shadowBlockEntities", "shadowBlockEntities", true);
		bool(m, p, "shadowLightBlockEntities", "shadowLightBlockEntities", false);
		bool(m, p, "voxelizeLightBlocks", "voxelizeLightBlocks", false);
		bool(m, p, "breaksAnisotropy", "breaksAnisotropy", false);
		bool(m, p, "frustumCulling", "frustum.culling", true);
		bool(m, p, "occlusionCulling", "occlusion.culling", true);
		bool(m, p, "allowConcurrentCompute", "allowConcurrentCompute", false);
		bool(m, p, "endFlashShadows", "endFlashShadows", false);
		bool(m, p, "skipAllRendering", "skipAllRendering", false);
		return m;
	}

	private static void bool(Map<String, Object> m, SequencedMap<String, String> p, String name, String key, boolean def) {
		Boolean b = parse(p.get(key));
		m.put(name, b == null ? def : b);
	}

	/** Iris booleans: true/1, false/0. */
	private static Boolean parse(String v) {
		if (v == null) return null;
		return switch (v.trim()) {
			case "true", "1" -> true;
			case "false", "0" -> false;
			default -> null;
		};
	}
}
