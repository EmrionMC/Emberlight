package dev.emrion.shaders.converter.rt;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * One RT patch table: bundled ({@code resources/rt/<id>.json}, our own file) or shipped by the pack itself
 * ({@link #PACK_TABLE}, see docs/RT-PATCH-TABLES.md). A table applies to a pack when every {@code detect} file exists under
 * {@code shaders/}.
 *
 * @param schema  table format version ({@link #SCHEMA}); required in pack tables
 * @param pack    pack name and {@code version} the table targets (shown in diagnostics); required in pack tables
 * @param files   null: paths are classpath resources; else path → GLSL read from the pack at load (never taken from the JSON)
 * @param packSettings effect → pack option → lowest value the effect needs (its patches hook into code the pack only compiles
 *                from there on); raised for the RT conversion when the user's settings are below it. Null = none
 */
public record RtPatchTable(String id, List<String> detect, Map<String, Effect> effects, Integer schema, String pack, String version,
                           Map<String, String> files, Map<String, Map<String, String>> packSettings) {
	/** Bundled tables tried in order after the pack's own; the first whose detect files all exist wins. */
	public static final List<String> TABLES = List.of("rt/complementary-r5.json", "rt/chocapic-taa.json");
	/** Newest table format this converter reads. */
	public static final int SCHEMA = 1;
	/** A pack's own table; its snippet paths are relative to {@link #PACK_DIR}. */
	public static final String PACK_DIR = "shaders/emberlight/", PACK_TABLE = PACK_DIR + "rt.json";

	public RtPatchTable(String id, List<String> detect, Map<String, Effect> effects) {
		this(id, detect, effects, SCHEMA, null, null, null, null);
	}

	/** "id", or "id (pack version, from the pack)" for a pack table. */
	public String label() {
		return files == null ? id : id + " (" + pack + " " + version + ", from the pack)";
	}

	/** A snippet's text: a classpath resource for bundled tables, the pack file for pack tables. */
	public String text(String path) {
		if (files == null) return resource(path);
		String t = files.get(path);
		if (t == null) throw new IllegalArgumentException(PACK_DIR + path + " was not loaded");
		return t;
	}

	/** Comma-separated snippet paths, concatenated with newlines (null = none). */
	public String texts(String paths) {
		StringBuilder sb = new StringBuilder();
		if (paths != null) for (String p : paths.split(",")) if (!p.isBlank()) sb.append(text(p.trim())).append('\n');
		return sb.toString();
	}

	/**
	 * The pack's own table ({@link #PACK_TABLE}) with its snippets, or null when the pack has none.
	 * @throws IllegalArgumentException with a message for the pack author when the table is malformed, newer than
	 *                                  {@link #SCHEMA}, or names a missing or unsafe file
	 */
	public static RtPatchTable fromPack(dev.emrion.shaders.converter.pack.PackSource src) throws IOException {
		var json = src.readString(PACK_TABLE);
		if (json.isEmpty()) return null;
		RtPatchTable t;
		try {
			t = new Gson().fromJson(json.get(), RtPatchTable.class);
		} catch (RuntimeException e) { // JsonParseException, or a value of the wrong type
			throw new IllegalArgumentException("invalid JSON: " + e.getMessage());
		}
		if (t == null) throw new IllegalArgumentException("empty file");
		if (t.schema() == null) throw new IllegalArgumentException("missing \"schema\" (this Emberlight reads schema " + SCHEMA + ")");
		if (t.schema() > SCHEMA || t.schema() < 1)
			throw new IllegalArgumentException("schema " + t.schema() + " is not supported (this Emberlight reads schema 1 to " + SCHEMA + "; update Emberlight?)");
		if (t.id() == null || t.pack() == null || t.version() == null) throw new IllegalArgumentException("\"id\", \"pack\" and \"version\" are required");
		if (t.effects() == null || t.effects().isEmpty()) throw new IllegalArgumentException("no \"effects\"");
		List<String> detect = t.detect() == null ? List.of() : t.detect();
		for (String d : detect) checkPath(d, "detect");
		Map<String, String> files = new java.util.HashMap<>();
		for (var e : t.effects().entrySet()) { // every path checked before any is read
			if (e.getValue() == null) throw new IllegalArgumentException("effect " + e.getKey() + " is null");
			for (String p : paths(e.getKey(), e.getValue())) checkPath(p, "effect " + e.getKey());
		}
		for (var e : t.effects().entrySet())
			for (String p : paths(e.getKey(), e.getValue()))
				if (!files.containsKey(p))
					files.put(p, src.readString(PACK_DIR + p)
							.orElseThrow(() -> new IllegalArgumentException("effect " + e.getKey() + ": " + PACK_DIR + p + " not found")));
		return new RtPatchTable(t.id(), detect, t.effects(), t.schema(), t.pack(), t.version(), Map.copyOf(files), t.packSettings());
	}

	/** Pack paths are plain relative paths: no absolute paths, '..', '.', backslashes, drive letters or other odd characters. */
	static String checkPath(String p, String where) {
		if (p == null || !p.matches("[A-Za-z0-9_\\-.]+(/[A-Za-z0-9_\\-.]+)*") || java.util.Arrays.asList(p.split("/")).stream().anyMatch(s -> s.matches("\\.+")))
			throw new IllegalArgumentException(where + ": path '" + p + "' is not allowed (relative to " + PACK_DIR + ", letters, digits, _-./ only, no '..')");
		return p;
	}

	/** Every snippet path an effect and its variants name; checks the required patch and compute fields. */
	private static List<String> paths(String name, Effect e0) {
		List<Effect> flavours = new java.util.ArrayList<>(List.of(e0));
		if (e0.variants() != null) for (Variant v : e0.variants().values()) flavours.add(e0.with(v));
		List<String> out = new java.util.ArrayList<>();
		for (Effect e : flavours) {
			if (e.helpers() != null) for (String h : e.helpers().split(",")) if (!h.isBlank()) out.add(h.trim());
			for (Patch p : e.patches()) {
				if (p == null || p.function() == null || p.body() == null) throw new IllegalArgumentException("effect " + name + ": a patch needs \"function\" and \"body\"");
				out.add(p.body());
			}
			if (e.computes() != null) for (Compute c : e.computes()) {
				if (c == null || c.program() == null || c.body() == null) throw new IllegalArgumentException("effect " + name + ": a compute needs \"program\" and \"body\"");
				if (c.helpers() != null) out.addAll(c.helpers());
				out.add(c.body());
			}
		}
		return out;
	}

	/**
	 * @param requires    programs that must contain a patched function in every dimension the effect applies to
	 * @param options     option → default value (an int, or a float when it contains '.'); emitted as {@code const emrRt_<effect>_<option>}
	 * @param helpers     effect helper library inserted after {@code rt/emrion_rt.glsl}
	 * @param uniforms    built-in uniforms the helpers read: declared by us when the stage does not declare them
	 * @param needs       pack symbols (functions, globals, uniforms) the helpers and bodies call: must be declared before the patch
	 * @param skipIds     block ids (mc_Entity) whose hits are ignored ({@code emrRt_<effect>_skip}), only when the dimension's shadow
	 *                    fragment stage contains {@code skipIdsWhen}
	 * @param frees       pack resources (shaders.properties keys: {@code bufferObject.N}, {@code image.X}) the patched pack no longer
	 *                    reads: shrunk to one element in the RT variant once the converter has checked no live code uses them
	 * @param dimensions  dimension folders the effect applies to (null = all)
	 * @param library     false: the patched stages get only {@code helpers}, not the common library (null = true)
	 * @param computes    Emrion compute programs added to every patched dimension (Iris pass computes, e.g. {@code deferred_a})
	 * @param images      Iris {@code image.<name>} directives added to the properties; {@code $<option>} is replaced by the option value
	 * @param variants    request option → {@link Variant} merged into the effect when the request names it ({@code shadows:rtx});
	 *                    without it the effect is exactly as listed here
	 * @param lifts       name → {@link Lift}: {@code @name} in the effect's helpers and bodies is replaced by those statements of the
	 *                    user's pack
	 */
	public record Effect(List<String> requires, Map<String, String> options, String helpers, Map<String, String> uniforms,
	                     List<String> needs, List<Integer> skipIds, String skipIdsWhen, List<String> frees, List<Patch> patches,
	                     List<String> dimensions, Boolean library, List<Compute> computes, Map<String, String> images,
	                     Map<String, Variant> variants, Map<String, Lift> lifts) {
		public Effect { // absent lists and maps (pack tables may leave them out) are empty
			requires = requires == null ? List.of() : requires;
			options = options == null ? Map.of() : options;
			uniforms = uniforms == null ? Map.of() : uniforms;
			needs = needs == null ? List.of() : needs;
			patches = patches == null ? List.of() : patches;
			lifts = lifts == null ? Map.of() : lifts;
		}

		public Effect(List<String> requires, Map<String, String> options, String helpers, Map<String, String> uniforms,
		              List<String> needs, List<Integer> skipIds, String skipIdsWhen, List<String> frees, List<Patch> patches) {
			this(requires, options, helpers, uniforms, needs, skipIds, skipIdsWhen, frees, patches, null, null, null, null, null, null);
		}

		/** This effect with {@code v} merged in: options, uniforms, needs and images added, helpers appended, patches and computes replaced. */
		public Effect with(Variant v) {
			var o = new java.util.LinkedHashMap<>(options);
			if (v.options() != null) o.putAll(v.options());
			var u = new java.util.LinkedHashMap<>(uniforms);
			if (v.uniforms() != null) u.putAll(v.uniforms());
			var n = new java.util.ArrayList<>(needs);
			if (v.needs() != null) n.addAll(v.needs());
			Map<String, String> im = images;
			if (v.images() != null) {
				im = new java.util.LinkedHashMap<>(images == null ? Map.of() : images);
				im.putAll(v.images());
			}
			return new Effect(requires, o, v.helpers() == null ? helpers : helpers == null ? v.helpers() : helpers + "," + v.helpers(), u, n, skipIds, skipIdsWhen, frees,
					v.patches() == null ? patches : v.patches(), dimensions, library, v.computes() == null ? computes : v.computes(), im, null, lifts);
		}
	}

	/** An optional flavour of an effect (RT style "RTX"): see {@link Effect#with}. {@code helpers}: comma-separated, appended. */
	public record Variant(Map<String, String> options, String helpers, Map<String, String> uniforms, List<String> needs,
	                      List<Patch> patches, List<Compute> computes, Map<String, String> images) {}

	/**
	 * function = "returnType name(paramType,...)" after preprocessing ("in" dropped, other qualifiers kept).
	 * @param programs null = any program; otherwise only these, and a stage of such a program that matches any patch of the effect
	 *                 must match every patch listed for its program (all or nothing)
	 * @param needs    pack symbols only this body uses: must be declared before this function
	 * @param bare     the body uses no helpers: a stage whose matches are all bare gets no helper library
	 * @param after    null or a statement of the function (GLSL tokens, matched ignoring whitespace, exactly once) after which
	 *                 the body is inserted
	 * @param find     null or a token sequence (exactly once in the body) replaced by {@code body}'s text; with neither,
	 *                 {@code body} replaces the function body
	 * @param optional a stage of one of {@code programs} need not match this patch (two patches for code a pack setting switches
	 *                 between, where one of them is enough)
	 * @param oneOf    null or a group name: a stage needs only one patch of the group (the same anchor in two pack versions)
	 */
	public record Patch(String function, String body, List<String> programs, List<String> needs, boolean bare, String after, String find,
	                    boolean optional, String oneOf) {
		public Patch(String function, String body) {
			this(function, body, null, null, false, null, null);
		}

		public Patch(String function, String body, List<String> programs, List<String> needs, boolean bare) {
			this(function, body, programs, needs, bare, null, null);
		}

		public Patch(String function, String body, List<String> programs, List<String> needs, boolean bare, String after, String find) {
			this(function, body, programs, needs, bare, after, find, false, null);
		}
	}

	/**
	 * Statements taken from the user's own pack at conversion time, so the table need not repeat pack code: {@code statements}
	 * (default 1) whole statements (an if with its else chain, a block) starting at {@code at} (tokens, exactly once in the body of
	 * {@code function} in the same stage type of {@code program}, null = the patched program; read before any effect patched it).
	 * Every name they use must be declared in front of the {@code @name} marker (the snippet's own declarations, the patched
	 * function's, the stage's globals) or be a GLSL built-in. When that fails or {@code at} is missing the effect is unavailable,
	 * unless {@code otherwise} is given: then that text stands in (an INFO diagnostic says so). With {@code text} instead of
	 * {@code at}, nothing is taken from the pack: the marker becomes {@code text} when the pack has {@code function}, else
	 * {@code otherwise} (a snippet that must follow a parameter list that differs between pack versions).
	 */
	public record Lift(String program, String function, String at, Integer statements, String otherwise, String text) {}

	/** One compute program: the library, {@code helpers} and {@code body} (a whole compute stage; {@code $<option>} replaced). */
	public record Compute(String program, List<String> helpers, String body) {
		public Compute {
			helpers = helpers == null ? List.of() : helpers;
		}
	}

	/**
	 * Hash of every table and the GLSL it references: part of the RT cache key, so editing an RT body invalidates RT caches
	 * without bumping Converter.VERSION (which would change the RT-off key too).

	 */
	public static String fingerprint() {
		StringBuilder all = new StringBuilder(resource("rt/emrion_rt.glsl"));
		for (String t : TABLES) {
			all.append(resource(t));
			for (Effect e0 : load(t).effects().values()) {
				List<Effect> flavours = new java.util.ArrayList<>(List.of(e0));
				if (e0.variants() != null) for (Variant v : e0.variants().values()) flavours.add(e0.with(v));
				for (Effect e : flavours) {
					all.append(resources(e.helpers()));
					for (Patch p : e.patches()) all.append(resource(p.body())).append(p.find() == null ? "" : p.find());
					all.append(e.lifts());
					if (e.computes() != null)
						for (Compute c : e.computes()) {
							for (String h : c.helpers()) all.append(resource(h));
							all.append(resource(c.body()));
						}
					if (e.images() != null) all.append(e.images());
				}
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

	/** Comma-separated resource paths, concatenated with newlines (an effect's helpers plus its variant's). */
	public static String resources(String paths) {
		StringBuilder sb = new StringBuilder();
		if (paths != null) for (String p : paths.split(",")) sb.append(resource(p.trim())).append('\n');
		return sb.toString();
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
