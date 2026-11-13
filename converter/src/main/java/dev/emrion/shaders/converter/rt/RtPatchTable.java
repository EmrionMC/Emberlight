package dev.emrion.shaders.converter.rt;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * One RT patch table (RAYTRACING.md §3.3): {@code converter/src/main/resources/rt/<id>.json}, our own file (no pack code).
 * A table applies to a pack when every {@code detect} file exists under {@code shaders/}.
 */
public record RtPatchTable(String id, List<String> detect, Map<String, Effect> effects) {
	/** Tables tried in order; the first whose detect files all exist wins. */
	public static final List<String> TABLES = List.of("rt/complementary-r5.json");

	/**
	 * @param requires    programs that must contain a patched function in every dimension the effect applies to
	 * @param options     option → default value (an int, or a float when it contains '.'); emitted as {@code const emrRt_<effect>_<option>}
	 * @param helpers     effect helper library inserted after {@code rt/emrion_rt.glsl}
	 * @param uniforms    built-in uniforms the helpers read: declared by us when the stage does not declare them
	 * @param needs       pack symbols (functions, globals, uniforms) the helpers and bodies call: must be declared before the patch
	 * @param skipIds     block ids (mc_Entity) whose hits are ignored, only when the dimension's shadow fragment stage contains {@code skipIdsWhen}
	 */
	public record Effect(List<String> requires, Map<String, String> options, String helpers, Map<String, String> uniforms,
	                     List<String> needs, List<Integer> skipIds, String skipIdsWhen, List<Patch> patches) {}

	/** function = "returnType name(paramType,...)" after preprocessing ("in" dropped, other qualifiers kept). */
	public record Patch(String function, String body) {}

	/**
	 * Hash of every table and every GLSL file it references: part of the RT variants' cache key, so editing an RT body
	 * invalidates RT caches without bumping Converter.VERSION (which would change the RT-off key too).
	 */
	public static String fingerprint() {
		StringBuilder all = new StringBuilder(resource("rt/emrion_rt.glsl"));
		for (String t : TABLES) {
			all.append(resource(t));
			for (Effect e : load(t).effects().values()) {
				all.append(resource(e.helpers()));
				for (Patch p : e.patches()) all.append(resource(p.body()));
			}
		}
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(all.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	public static RtPatchTable load(String resource) {
		return new Gson().fromJson(resource(resource), RtPatchTable.class);
	}

	public static String resource(String path) {
		try (InputStream in = RtPatchTable.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("missing resource " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
