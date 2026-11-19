package dev.emrion.shaders.converter.nativefmt;

import dev.emrion.shaders.converter.report.Diagnostic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * emberlight.json, parsed and validated (docs/NATIVE_FORMAT.md). Every problem becomes a diagnostic against
 * {@code emberlight.json:<line>}; {@link #parse} never throws for pack errors.
 */
public record NativeManifest(
		int format, String id, String name, String version, Set<String> requires, Map<String, Object> settings,
		List<Image> images, List<Buffer> buffers, List<Texture> textures, Shadow shadow, List<Pass> passes,
		List<Uniform> uniforms, List<Option> options, List<Page> pages, Map<String, Map<String, String>> profiles,
		Map<String, Map<Integer, List<String>>> materials, List<Dimension> dimensions
) {
	public static final String FILE = "emberlight.json";
	public static final int FORMAT = 1;

	/** size null = screen-relative (scale); clear null = never cleared. */
	public record Image(String name, int line, String format, float[] scale, int[] size, float[] clear, boolean history,
	                    boolean mipmaps, boolean storage) {
		boolean screen() { return size == null; }

		boolean integer() { return format.endsWith("I"); }
	}

	public record Buffer(String name, int line, long bytes, long bytesPerPixel) {}

	/** raw: format, size, pixel ("RGBA HALF_FLOAT") or null for PNG / resource. */
	public record Texture(String name, int line, String file, String resource, String filter, String wrap, String rawFormat,
	                      int[] rawSize, String rawPixel) {}

	public record Shadow(int resolution, float distance, Float sunPathRotation, float renderDistanceMultiplier,
	                     float entityDistanceMultiplier, boolean hardwareFiltering, List<Image> images) {}

	/** stages: vertex | fragment | geometryShader | compute → pack path. blend: "default" or image → Iris blend value. */
	public record Pass(String name, int line, String kind, String phase, List<String> classes, Map<String, String> stages,
	                   List<String> writes, Map<String, String> blend, Float alphaCutoff, Map<String, String> defines,
	                   String enabled, int enabledLine, boolean raytracing, float scale, List<String> mipmaps, int[] localSize,
	                   int[] count, float[] dispatchScale, boolean raygen) {}

	/** A compute pass that writes RR guides: it only exists while Ray Reconstruction runs (§14.4). */
	static boolean writesRrGuides(Pass p) { return p.writes().stream().anyMatch(RR_IMAGES::contains); }

	public record Uniform(String name, String type, String expr, boolean variable) {}

	/** type: bool | int | float | enum | color. values: the allowed values as GLSL text (enum: value names). */
	public record Option(String name, int line, String type, String defaultValue, List<String> values, boolean slider,
	                     String label, String comment) {}

	public record Page(String name, int line, int columns, List<String> items) {}

	public record Dimension(String name, int line, List<String> match, Map<String, String> defines, List<String> disable) {}

	// ---- static tables ------------------------------------------------------------------------------------------------

	public static final List<String> FORMATS = List.of("R8", "RG8", "RGBA8", "R8_SNORM", "RG8_SNORM", "RGBA8_SNORM", "R16", "RG16",
			"RGBA16", "R16_SNORM", "RG16_SNORM", "RGBA16_SNORM", "R16F", "RG16F", "RGBA16F", "R32F", "RG32F", "RGBA32F", "R8I", "RG8I",
			"RGBA8I", "R8UI", "RG8UI", "RGBA8UI", "R16I", "RG16I", "RGBA16I", "R16UI", "RG16UI", "RGBA16UI", "R32I", "RG32I", "RGBA32I",
			"R32UI", "RG32UI", "RGBA32UI", "RGB10_A2", "R11F_G11F_B10F");

	/** Phases in frame order (NATIVE_FORMAT.md §4); opaque/translucent/shadow/final are not for fullscreen/compute passes. */
	public static final List<String> PHASES = List.of("setup", "begin", "shadow", "shadowcomp", "prepare", "opaque", "deferred",
			"translucent", "composite", "final");
	static final Set<String> PASS_PHASES = Set.of("setup", "begin", "shadowcomp", "prepare", "deferred", "composite");

	/** Geometry class → Iris program id (the planner's fallback table does the rest). */
	public static final Map<String, String> CLASSES = new LinkedHashMap<>();
	static final Set<String> TRANSLUCENT = Set.of("block_entities_translucent", "entities_translucent", "particles_translucent",
			"weather", "terrain_translucent", "hand_translucent");
	static final Set<String> CUTOUT = Set.of("terrain_cutout", "entities", "block_entities", "particles", "hand", "textured",
			"textured_lit", "entities_translucent", "block_entities_translucent", "particles_translucent", "hand_translucent",
			"terrain_translucent", "weather", "damage", "shadow", "shadow_cutout", "shadow_entities", "shadow_block_entities",
			"shadow_translucent", "emissive_eyes", "glint", "beacon", "sky_textured", "clouds", "lightning", "entities_glowing");

	static {
		String[][] t = {{"basic", "gbuffers_basic"}, {"lines", "gbuffers_line"}, {"textured", "gbuffers_textured"},
				{"textured_lit", "gbuffers_textured_lit"}, {"sky", "gbuffers_skybasic"}, {"sky_textured", "gbuffers_skytextured"},
				{"clouds", "gbuffers_clouds"}, {"terrain_solid", "gbuffers_terrain_solid"}, {"terrain_cutout", "gbuffers_terrain_cutout"},
				{"damage", "gbuffers_damagedblock"}, {"block_entities", "gbuffers_block"},
				{"block_entities_translucent", "gbuffers_block_translucent"}, {"beacon", "gbuffers_beaconbeam"},
				{"entities", "gbuffers_entities"}, {"entities_translucent", "gbuffers_entities_translucent"},
				{"entities_glowing", "gbuffers_entities_glowing"}, {"lightning", "gbuffers_lightning"}, {"glint", "gbuffers_armor_glint"},
				{"emissive_eyes", "gbuffers_spidereyes"}, {"particles", "gbuffers_particles"},
				{"particles_translucent", "gbuffers_particles_translucent"}, {"weather", "gbuffers_weather"},
				{"terrain_translucent", "gbuffers_water"}, {"hand", "gbuffers_hand"}, {"hand_translucent", "gbuffers_hand_water"},
				{"shadow", "shadow"}, {"shadow_solid", "shadow_solid"}, {"shadow_cutout", "shadow_cutout"},
				{"shadow_translucent", "shadow_water"}, {"shadow_entities", "shadow_entities"}, {"shadow_block_entities", "shadow_block"},
				{"shadow_lightning", "shadow_lightning"}};
		for (String[] e : t) CLASSES.put(e[0], e[1]);
	}

	/** Native fallback chains (§5.1); a class whose chain reaches no pass is not drawn. */
	static final Map<String, String> FALLBACK = Map.ofEntries(Map.entry("lines", "basic"), Map.entry("textured", "basic"),
			Map.entry("textured_lit", "textured"), Map.entry("sky", "basic"), Map.entry("sky_textured", "textured"),
			Map.entry("clouds", "textured"), Map.entry("terrain_solid", "textured_lit"), Map.entry("terrain_cutout", "terrain_solid"),
			Map.entry("damage", "terrain_solid"), Map.entry("block_entities", "terrain_solid"),
			Map.entry("block_entities_translucent", "block_entities"), Map.entry("beacon", "textured"), Map.entry("entities", "textured_lit"),
			Map.entry("entities_translucent", "entities"), Map.entry("entities_glowing", "entities"), Map.entry("lightning", "entities"),
			Map.entry("glint", "textured"), Map.entry("emissive_eyes", "textured"), Map.entry("particles", "textured_lit"),
			Map.entry("particles_translucent", "particles"), Map.entry("weather", "textured_lit"), Map.entry("terrain_translucent", "terrain_solid"),
			Map.entry("hand", "textured_lit"), Map.entry("hand_translucent", "hand"), Map.entry("shadow_solid", "shadow"),
			Map.entry("shadow_cutout", "shadow"), Map.entry("shadow_translucent", "shadow"), Map.entry("shadow_entities", "shadow"),
			Map.entry("shadow_block_entities", "shadow"), Map.entry("shadow_lightning", "shadow_entities"));

	static final Set<String> FEATURES = Set.of("ray_query");
	static final Map<String, String> SETTINGS = Map.ofEntries(Map.entry("clouds", "fancy|fast|off"), Map.entry("sun", "bool"),
			Map.entry("moon", "bool"), Map.entry("stars", "bool"), Map.entry("separateAo", "bool"),
			Map.entry("vanillaDirectionalShade", "bool"), Map.entry("ambientOcclusion", "number"), Map.entry("rayTracedEntities", "bool"),
			Map.entry("underwaterOverlay", "bool"), Map.entry("vignette", "bool"), Map.entry("rayReconstruction", "bool"));
	/** DLSS Ray Reconstruction's guides (§14.4): render-size RGBA16F storage images Emberlight provides while RR runs. */
	public static final List<String> RR_IMAGES = List.of("el_rrDiffuse", "el_rrSpecular", "el_rrNormal");
	/** §14.1: render-size R32F storage image, the view-ray distance of what a pixel shows (a mirror's reflected image), for the motion vectors. */
	public static final String MOTION_IMAGE = "el_motionDistance";

	boolean writesMotionDistance() { return passes.stream().anyMatch(p -> p.writes().contains(MOTION_IMAGE)); }
	static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,64}");
	static final Pattern RES_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,47}");
	static final Pattern PASS_NAME = Pattern.compile("[a-z][a-z0-9_]{0,47}");
	static final Pattern OPTION_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
	static final Pattern ENUM_VALUE = Pattern.compile("[a-z0-9_]{1,32}");
	static final Pattern BLEND_FACTOR = Pattern.compile("zero|one|(one_minus_)?(src|dst)_(color|alpha)");
	static final int MAX_SCREEN_IMAGES = 16, MAX_SIZED_IMAGES = 8, MAX_OPTION_VALUES = 256;

	// ---- parsing ------------------------------------------------------------------------------------------------------

	/**
	 * @param fileExists checks pack paths (stage sources, textures)
	 * @param out        errors and warnings, file = emberlight.json
	 * @return null when the file is not readable JSON or has errors
	 */
	public static NativeManifest parse(String text, Predicate<String> fileExists, List<Diagnostic> out) {
		JsonNode root;
		try {
			root = JsonNode.parse(text);
		} catch (JsonNode.SyntaxError e) {
			out.add(Diagnostic.error(FILE, e.line, e.getMessage()));
			return null;
		}
		Parser p = new Parser(fileExists, out);
		NativeManifest m = p.manifest(root);
		return out.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR) ? null : m;
	}

	private static final class Parser {
		final Predicate<String> exists;
		final List<Diagnostic> out;

		Parser(Predicate<String> exists, List<Diagnostic> out) {
			this.exists = exists;
			this.out = out;
		}

		void error(JsonNode at, String msg) { out.add(Diagnostic.error(FILE, at == null ? 0 : at.line(), msg)); }

		void warn(JsonNode at, String msg) { out.add(Diagnostic.warning(FILE, at == null ? 0 : at.line(), msg)); }

		/** The object's entries; unknown keys (not in allowed, not x-) are errors. */
		Map<String, JsonNode> obj(JsonNode n, String what, Set<String> allowed) {
			if (n == null) return Map.of();
			Map<String, JsonNode> m = n.object();
			if (m == null) {
				error(n, what + " must be an object, got " + n.type());
				return Map.of();
			}
			for (var e : m.entrySet())
				if (allowed != null && !allowed.contains(e.getKey()) && !e.getKey().startsWith("x-"))
					error(e.getValue(), "unknown key \"" + e.getKey() + "\" in " + what + " (allowed: " + String.join(", ", allowed) + ")");
			return m;
		}

		List<JsonNode> arr(JsonNode n, String what) {
			if (n == null) return List.of();
			List<JsonNode> l = n.array();
			if (l == null) {
				error(n, what + " must be an array, got " + n.type());
				return List.of();
			}
			return l;
		}

		String str(JsonNode n, String what, String def) {
			if (n == null) return def;
			if (n.value() instanceof String s) return s;
			error(n, what + " must be a string, got " + n.type());
			return def;
		}

		String req(Map<String, JsonNode> m, String key, String what, JsonNode at) {
			if (!m.containsKey(key)) {
				error(at, what + " needs \"" + key + "\"");
				return null;
			}
			return str(m.get(key), what + "." + key, null);
		}

		Double num(JsonNode n, String what) {
			if (n == null) return null;
			if (n.value() instanceof Double d) return d;
			error(n, what + " must be a number, got " + n.type());
			return null;
		}

		boolean bool(JsonNode n, String what, boolean def) {
			if (n == null) return def;
			if (n.value() instanceof Boolean b) return b;
			error(n, what + " must be true or false, got " + n.type());
			return def;
		}

		float[] floats(JsonNode n, String what, int min, int max) {
			if (n == null) return null;
			if (n.value() instanceof Double d && min == 1) return new float[]{d.floatValue()};
			List<JsonNode> l = n.value() instanceof Double ? List.of(n) : arr(n, what);
			if (l.size() < min || l.size() > max) {
				error(n, what + " needs " + (min == max ? min : min + "-" + max) + " numbers");
				return null;
			}
			float[] f = new float[l.size()];
			for (int i = 0; i < f.length; i++) {
				Double d = num(l.get(i), what);
				f[i] = d == null ? 0 : d.floatValue();
			}
			return f;
		}

		int[] ints(JsonNode n, String what, int min, int max, int lo, int hi) {
			float[] f = floats(n, what, min, max);
			if (f == null) return null;
			int[] r = new int[f.length];
			for (int i = 0; i < f.length; i++) {
				if (f[i] != Math.rint(f[i]) || f[i] < lo || f[i] > hi) {
					error(n, what + " must hold integers in " + lo + "-" + hi);
					return null;
				}
				r[i] = (int) f[i];
			}
			return r;
		}

		List<String> strings(JsonNode n, String what) {
			List<String> r = new ArrayList<>();
			for (JsonNode e : arr(n, what)) {
				String s = str(e, what + " entry", null);
				if (s != null) r.add(s);
			}
			return r;
		}

		Map<String, String> defines(JsonNode n, String what) {
			Map<String, String> r = new LinkedHashMap<>();
			for (var e : obj(n, what, null).entrySet()) {
				if (!OPTION_NAME.matcher(e.getKey()).matches() || e.getKey().startsWith("EL_"))
					error(e.getValue(), what + ": define name \"" + e.getKey() + "\" must be UPPER_CASE and not start with EL_");
				Object v = e.getValue().value();
				r.put(e.getKey(), v instanceof Double d ? glslNumber(d) : v instanceof Boolean b ? (b ? "1" : "0") : String.valueOf(v));
			}
			return r;
		}

		/** A pack-relative file path that must exist. */
		String path(JsonNode n, String what) {
			String p = str(n, what, null);
			if (p == null) return null;
			if (p.startsWith("/") || p.contains("\\") || p.contains("..") || p.contains("//")) {
				error(n, what + ": \"" + p + "\" must be a relative path with '/' and without '..'");
				return null;
			}
			if (!exists.test(p)) {
				error(n, what + ": file \"" + p + "\" not found in the pack");
				return null;
			}
			return p;
		}

		NativeManifest manifest(JsonNode root) {
			Map<String, JsonNode> m = obj(root, "the manifest", Set.of("format", "id", "name", "version", "authors", "description",
					"license", "website", "requires", "settings", "images", "buffers", "textures", "shadow", "passes", "uniforms",
					"options", "pages", "profiles", "materials", "dimensions"));
			Double f = num(m.get("format"), "format");
			if (f == null) {
				if (!m.containsKey("format")) error(root, "missing \"format\" (this Emberlight reads format " + FORMAT + ")");
				return null;
			}
			if (f > FORMAT) {
				error(m.get("format"), "format " + glslNumber(f) + " needs a newer Emberlight (this one reads format " + FORMAT + ")");
				return null;
			}
			if (f != FORMAT) {
				error(m.get("format"), "unknown format " + glslNumber(f) + " (this Emberlight reads format " + FORMAT + ")");
				return null;
			}
			String id = req(m, "id", "the manifest", root);
			if (id != null && !ID.matcher(id).matches()) error(m.get("id"), "id \"" + id + "\" must match [a-z0-9_-]{1,64}");
			String name = req(m, "name", "the manifest", root);
			String version = req(m, "version", "the manifest", root);
			for (String k : List.of("description", "license", "website")) str(m.get(k), k, null);
			if (m.get("authors") != null && !(m.get("authors").value() instanceof String)) strings(m.get("authors"), "authors");

			Set<String> requires = new LinkedHashSet<>();
			for (JsonNode r : arr(m.get("requires"), "requires")) {
				String s = str(r, "requires entry", null);
				if (s != null && !FEATURES.contains(s)) error(r, "unknown feature \"" + s + "\" in requires (known: " + String.join(", ", FEATURES) + ")");
				else if (s != null) requires.add(s);
			}
			Map<String, Object> settings = settings(m.get("settings"));

			List<Image> images = new ArrayList<>();
			for (var e : obj(m.get("images"), "images", null).entrySet()) {
				Image i = image(e.getKey(), e.getValue(), "image");
				if (i != null) images.add(i);
			}
			long screen = images.stream().filter(Image::screen).count();
			if (screen > MAX_SCREEN_IMAGES) error(m.get("images"), screen + " screen-sized images, at most " + MAX_SCREEN_IMAGES);
			if (images.size() - screen > MAX_SIZED_IMAGES) error(m.get("images"), (images.size() - screen) + " absolute-size images, at most " + MAX_SIZED_IMAGES);

			List<Buffer> buffers = new ArrayList<>();
			for (var e : obj(m.get("buffers"), "buffers", null).entrySet()) {
				String what = "buffer \"" + e.getKey() + "\"";
				resName(e.getKey(), e.getValue(), what);
				var b = obj(e.getValue(), what, Set.of("bytes", "bytesPerPixel"));
				Double bytes = num(b.get("bytes"), what + ".bytes"), bpp = num(b.get("bytesPerPixel"), what + ".bytesPerPixel");
				if ((bytes == null) == (bpp == null)) error(e.getValue(), what + " needs exactly one of \"bytes\" or \"bytesPerPixel\"");
				else if ((bytes != null ? bytes : bpp) < 1 || (bytes != null ? bytes : bpp) > (1L << 30)) error(e.getValue(), what + ": size must be 1 B to 1 GiB");
				else buffers.add(new Buffer(e.getKey(), e.getValue().line(), bytes == null ? 0 : bytes.longValue(), bpp == null ? 0 : bpp.longValue()));
			}

			List<Texture> textures = new ArrayList<>();
			for (var e : obj(m.get("textures"), "textures", null).entrySet()) {
				Texture t = texture(e.getKey(), e.getValue());
				if (t != null) textures.add(t);
			}
			Set<String> names = new LinkedHashSet<>();
			for (Image i : images) unique(names, i.name(), m.get("images"));
			for (Buffer b : buffers) unique(names, b.name(), m.get("buffers"));
			for (Texture t : textures) unique(names, t.name(), m.get("textures"));

			Shadow shadow = shadow(m.get("shadow"), names);
			List<Option> options = options(m.get("options"));
			Map<String, Option> byName = new LinkedHashMap<>();
			for (Option o : options) if (byName.putIfAbsent(o.name(), o) != null) error(m.get("options"), "option " + o.name() + " is declared twice");
			List<Pass> passes = passes(m.get("passes"), root, images, shadow, buffers, byName, requires);
			boolean rrGuides = passes.stream().anyMatch(NativeManifest::writesRrGuides);
			if (Boolean.TRUE.equals(settings.get("rayReconstruction"))) {
				for (String g : RR_IMAGES)
					if (passes.stream().noneMatch(p -> p.writes().contains(g))) error(m.get("settings"), "settings.rayReconstruction: no compute pass writes " + g + " (§14.4)");
				if (!requires.contains("ray_query")) error(m.get("settings"), "settings.rayReconstruction needs \"requires\": [\"ray_query\"] (Ray Reconstruction runs with ray tracing)");
			} else if (rrGuides) error(m.get("passes"), "a pass writes Ray Reconstruction guides (el_rr*): add \"rayReconstruction\": true to settings");
			List<Uniform> uniforms = uniforms(m.get("uniforms"));
			List<Page> pages = pages(m.get("pages"), byName);
			Map<String, Map<String, String>> profiles = profiles(m.get("profiles"), byName);
			Map<String, Map<Integer, List<String>>> materials = materials(m.get("materials"));
			List<Dimension> dims = dimensions(m.get("dimensions"), passes);
			return new NativeManifest(FORMAT, id, name, version, requires, settings, images, buffers, textures, shadow, passes, uniforms,
					options, pages, profiles, materials, dims);
		}

		void unique(Set<String> names, String name, JsonNode at) {
			if (!names.add(name)) error(at, "the name \"" + name + "\" is used by two resources (images, buffers and textures share one namespace)");
		}

		void resName(String name, JsonNode at, String what) {
			if (!RES_NAME.matcher(name).matches()) error(at, what + ": name must match [A-Za-z][A-Za-z0-9_]{0,47}");
			else if (name.toLowerCase(Locale.ROOT).startsWith("el_") || name.startsWith("emr") || name.startsWith("iris_")
					|| name.matches("(colortex|colorimg|shadowcolor|shadowtex|depthtex|gaux|bufferObject)\\d*|noisetex|gtexture|normals|specular|lightmap"))
				error(at, what + ": name is reserved");
		}

		Map<String, Object> settings(JsonNode n) {
			Map<String, Object> r = new LinkedHashMap<>();
			for (var e : obj(n, "settings", SETTINGS.keySet()).entrySet()) {
				String type = SETTINGS.get(e.getKey());
				if (type == null) continue;
				Object v = e.getValue().value();
				boolean ok = switch (type) {
					case "bool" -> v instanceof Boolean;
					case "number" -> v instanceof Double;
					default -> v instanceof String s && List.of(type.split("\\|")).contains(s);
				};
				if (!ok) error(e.getValue(), "settings." + e.getKey() + " must be " + (type.contains("|") ? "one of " + type.replace("|", ", ") : type.equals("bool") ? "true or false" : "a number"));
				else r.put(e.getKey(), v);
			}
			return r;
		}

		Image image(String name, JsonNode n, String kind) {
			String what = kind + " \"" + name + "\"";
			resName(name, n, what);
			var m = obj(n, what, Set.of("format", "scale", "size", "clear", "history", "mipmaps", "storage"));
			String format = req(m, "format", what, n);
			if (format != null && !FORMATS.contains(format)) {
				error(m.get("format"), what + ": unknown format \"" + format + "\" (" + String.join(" ", FORMATS) + ")");
				format = null;
			}
			float[] scale = floats(m.get("scale"), what + ".scale", 1, 2);
			if (scale != null && scale.length == 1) scale = new float[]{scale[0], scale[0]};
			if (scale != null) for (float s : scale) if (!(s > 0 && s <= 4)) error(m.get("scale"), what + ".scale must be > 0 and <= 4");
			int[] size = ints(m.get("size"), what + ".size", 2, 3, 1, 16384);
			boolean history = bool(m.get("history"), what + ".history", false);
			boolean mipmaps = bool(m.get("mipmaps"), what + ".mipmaps", false);
			boolean storage = bool(m.get("storage"), what + ".storage", false);
			if (size != null && (scale != null || mipmaps || history)) error(n, what + ": \"size\" excludes \"scale\", \"mipmaps\" and \"history\"");
			float[] clear = {0, 0, 0, 0};
			JsonNode c = m.get("clear");
			if (c != null && Boolean.FALSE.equals(c.value())) clear = null;
			else if (c != null) {
				float[] f = floats(c, what + ".clear", 4, 4);
				if (f != null) clear = f;
			}
			if (history) {
				if (c != null && clear != null) error(c, what + ": a history image is never cleared; drop \"clear\"");
				clear = null;
			}
			if (format == null) return null;
			return new Image(name, n.line(), format, size == null ? (scale == null ? new float[]{1, 1} : scale) : null, size, clear, history, mipmaps, storage || size == null);
		}

		Texture texture(String name, JsonNode n) {
			String what = "texture \"" + name + "\"";
			resName(name, n, what);
			var m = obj(n, what, Set.of("file", "resource", "filter", "wrap", "raw"));
			String file = m.containsKey("file") ? path(m.get("file"), what + ".file") : null;
			String resource = str(m.get("resource"), what + ".resource", null);
			if ((m.containsKey("file")) == (resource != null)) {
				error(n, what + " needs exactly one of \"file\" or \"resource\"");
				return null;
			}
			if (resource != null && !resource.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) error(m.get("resource"), what + ": resource must be namespace:path");
			String filter = str(m.get("filter"), what + ".filter", "linear"), wrap = str(m.get("wrap"), what + ".wrap", "repeat");
			if (!filter.equals("linear") && !filter.equals("nearest")) error(m.get("filter"), what + ".filter must be linear or nearest");
			if (!wrap.equals("repeat") && !wrap.equals("clamp")) error(m.get("wrap"), what + ".wrap must be repeat or clamp");
			String rawFormat = null, rawPixel = null;
			int[] rawSize = null;
			if (m.containsKey("raw")) {
				var r = obj(m.get("raw"), what + ".raw", Set.of("format", "size", "pixel"));
				rawFormat = req(r, "format", what + ".raw", m.get("raw"));
				if (rawFormat != null && !FORMATS.contains(rawFormat) && !rawFormat.matches("RGB(8|16|16F|32F|8_SNORM)")) error(r.get("format"), what + ".raw: unknown format " + rawFormat);
				rawSize = ints(r.get("size"), what + ".raw.size", 2, 3, 1, 16384);
				if (rawSize == null) error(m.get("raw"), what + ".raw needs \"size\" [w, h] or [w, h, d]");
				rawPixel = req(r, "pixel", what + ".raw", m.get("raw"));
				if (rawPixel != null && !rawPixel.matches("[A-Z_]+ [A-Z_]+")) error(r.get("pixel"), what + ".raw.pixel must be \"<GL format> <GL type>\", e.g. \"RGBA HALF_FLOAT\"");
			}
			return new Texture(name, n.line(), file, resource, filter, wrap, rawFormat, rawSize, rawPixel);
		}

		Shadow shadow(JsonNode n, Set<String> names) {
			if (n == null) return null;
			var m = obj(n, "shadow", Set.of("resolution", "distance", "sunPathRotation", "renderDistanceMultiplier",
					"entityDistanceMultiplier", "hardwareFiltering", "images"));
			Double res = num(m.get("resolution"), "shadow.resolution"), dist = num(m.get("distance"), "shadow.distance");
			int resolution = res == null ? 2048 : res.intValue();
			if (resolution < 256 || resolution > 16384 || Integer.bitCount(resolution) != 1) error(m.get("resolution"), "shadow.resolution must be a power of two in 256-16384");
			Double spr = num(m.get("sunPathRotation"), "shadow.sunPathRotation");
			Double rdm = num(m.get("renderDistanceMultiplier"), "shadow.renderDistanceMultiplier");
			Double edm = num(m.get("entityDistanceMultiplier"), "shadow.entityDistanceMultiplier");
			List<Image> imgs = new ArrayList<>();
			for (var e : obj(m.get("images"), "shadow.images", null).entrySet()) {
				Image i = image(e.getKey(), e.getValue(), "shadow image");
				if (i == null) continue;
				if (!i.screen() || i.history() || i.mipmaps()) error(e.getValue(), "shadow image \"" + i.name() + "\" takes only format and clear");
				unique(names, i.name(), e.getValue());
				imgs.add(i);
			}
			if (imgs.size() > 2) error(m.get("images"), "at most 2 shadow images");
			return new Shadow(resolution, dist == null ? 160f : dist.floatValue(), spr == null ? null : spr.floatValue(),
					rdm == null ? 1f : rdm.floatValue(), edm == null ? 1f : edm.floatValue(),
					bool(m.get("hardwareFiltering"), "shadow.hardwareFiltering", true), imgs);
		}

		List<Pass> passes(JsonNode n, JsonNode root, List<Image> images, Shadow shadow, List<Buffer> buffers,
		                  Map<String, Option> options, Set<String> requires) {
			if (n == null) {
				error(root, "the manifest needs \"passes\"");
				return List.of();
			}
			List<Pass> out = new ArrayList<>();
			Map<String, String> classOwner = new LinkedHashMap<>();
			Set<String> passNames = new LinkedHashSet<>();
			Map<String, Image> imageByName = new LinkedHashMap<>();
			images.forEach(i -> imageByName.put(i.name(), i));
			Set<String> shadowImages = new LinkedHashSet<>();
			if (shadow != null) shadow.images().forEach(i -> shadowImages.add(i.name()));
			int finals = 0;
			for (JsonNode pn : arr(n, "passes")) {
				var m = obj(pn, "pass", Set.of("name", "kind", "phase", "classes", "vertex", "fragment", "geometryShader", "compute",
						"writes", "defines", "enabled", "raytracing", "blend", "alphaCutoff", "scale", "mipmaps", "workgroups", "raygen"));
				String name = req(m, "name", "a pass", pn);
				if (name == null) continue;
				String what = "pass \"" + name + "\"";
				if (!PASS_NAME.matcher(name).matches()) error(m.get("name"), what + ": name must match [a-z][a-z0-9_]{0,47}");
				if (!passNames.add(name)) error(m.get("name"), what + " is declared twice");
				String kind = req(m, "kind", what, pn);
				if (kind == null) continue;
				Set<String> stageKeys = switch (kind) {
					case "geometry" -> Set.of("vertex", "fragment", "geometryShader");
					case "fullscreen", "final" -> Set.of("vertex", "fragment");
					case "compute" -> Set.of("compute");
					default -> null;
				};
				if (stageKeys == null) {
					error(m.get("kind"), what + ": kind must be geometry, fullscreen, compute or final");
					continue;
				}
				Map<String, String> stages = new LinkedHashMap<>();
				for (String k : List.of("vertex", "geometryShader", "fragment", "compute")) {
					if (!m.containsKey(k)) continue;
					if (!stageKeys.contains(k)) error(m.get(k), what + ": a " + kind + " pass has no \"" + k + "\" stage");
					else {
						String p = path(m.get(k), what + "." + k);
						if (p != null && !p.startsWith("shaders/")) error(m.get(k), what + "." + k + ": shader sources live under shaders/");
						if (p != null) stages.put(k, p);
					}
				}
				boolean needsVertex = kind.equals("geometry");
				if (needsVertex && !m.containsKey("vertex")) error(pn, what + " needs a \"vertex\" stage");
				if (!kind.equals("compute") && !m.containsKey("fragment")) error(pn, what + " needs a \"fragment\" stage");
				if (kind.equals("compute") && !m.containsKey("compute")) error(pn, what + " needs a \"compute\" stage");

				String phase = str(m.get("phase"), what + ".phase", null);
				List<String> classes = List.of();
				switch (kind) {
					case "geometry" -> {
						if (phase != null) error(m.get("phase"), what + ": geometry passes take their phase from their classes");
						classes = strings(m.get("classes"), what + ".classes");
						if (classes.isEmpty()) error(pn, what + " needs \"classes\" (" + String.join(", ", CLASSES.keySet()) + ")");
						for (String c : classes) {
							if (!CLASSES.containsKey(c)) error(m.get("classes"), what + ": unknown geometry class \"" + c + "\" (" + String.join(", ", CLASSES.keySet()) + ")");
							else if (classOwner.putIfAbsent(c, name) != null) error(m.get("classes"), what + ": class " + c + " is already drawn by pass \"" + classOwner.get(c) + "\"");
						}
						boolean sh = classes.stream().anyMatch(c -> c.startsWith("shadow")), cam = classes.stream().anyMatch(c -> !c.startsWith("shadow"));
						if (sh && cam) error(m.get("classes"), what + " mixes shadow and camera classes; use two passes");
						if (sh && shadow == null) error(m.get("classes"), what + " draws shadow classes but the manifest has no \"shadow\" section");
						phase = sh ? "shadow" : classes.stream().allMatch(TRANSLUCENT::contains) ? "translucent" : "opaque";
					}
					case "final" -> {
						if (phase != null) error(m.get("phase"), what + ": the final pass has no phase");
						phase = "final";
						if (++finals > 1) error(pn, "only one final pass is allowed");
					}
					default -> {
						if (phase == null) error(pn, what + " needs a \"phase\" (" + String.join(", ", PASS_PHASES) + ")");
						else if (!PASS_PHASES.contains(phase)) error(m.get("phase"), what + ": phase must be one of " + String.join(", ", PASS_PHASES));
						if ("setup".equals(phase) && !kind.equals("compute")) error(m.get("phase"), what + ": the setup phase runs compute passes only");
					}
				}

				List<String> writes = strings(m.get("writes"), what + ".writes");
				boolean shadowPass = "shadow".equals(phase);
				for (String w : writes) {
					boolean ok = shadowPass ? shadowImages.contains(w)
							: kind.equals("compute") ? imageByName.containsKey(w) || buffers.stream().anyMatch(b -> b.name().equals(w)) || RR_IMAGES.contains(w) || w.equals(MOTION_IMAGE)
							: imageByName.containsKey(w) && imageByName.get(w).screen();
					if (!ok) error(m.get("writes"), what + " writes unknown " + (shadowPass ? "shadow image" : "image") + " \"" + w + "\" ("
							+ (shadowPass ? "shadow images: " + String.join(", ", shadowImages) : "images: " + String.join(", ",
							images.stream().filter(i -> kind.equals("compute") || i.screen()).map(Image::name).toList())) + ")");
				}
				if (writes.size() != new LinkedHashSet<>(writes).size()) error(m.get("writes"), what + " lists an image twice in writes");
				if (kind.equals("final") && !writes.isEmpty()) error(m.get("writes"), what + ": the final pass writes the display image; drop \"writes\"");
				if ((kind.equals("fullscreen") || kind.equals("geometry")) && writes.isEmpty())
					error(pn, what + " needs \"writes\" (the images its fragment outputs go to)");
				if (writes.size() > 8 && !kind.equals("compute")) error(m.get("writes"), what + " writes more than 8 images");

				Map<String, String> blend = new LinkedHashMap<>();
				if (m.containsKey("blend")) {
					if (!kind.equals("geometry") && !kind.equals("fullscreen")) error(m.get("blend"), what + ": only geometry and fullscreen passes blend");
					JsonNode b = m.get("blend");
					if (b.object() != null && !b.object().containsKey("src")) {
						for (var e : b.object().entrySet()) {
							if (!e.getKey().equals("default") && !writes.contains(e.getKey())) error(e.getValue(), what + ".blend: \"" + e.getKey() + "\" is not written by this pass");
							String v = blend(e.getValue(), what + ".blend." + e.getKey());
							if (v != null) blend.put(e.getKey(), v);
						}
					} else {
						String v = blend(b, what + ".blend");
						if (v != null) blend.put("default", v);
					}
				}
				Float cutoff = null;
				if (m.containsKey("alphaCutoff")) {
					if (!kind.equals("geometry")) error(m.get("alphaCutoff"), what + ": alphaCutoff is for geometry passes");
					if (!Boolean.FALSE.equals(m.get("alphaCutoff").value())) {
						Double d = num(m.get("alphaCutoff"), what + ".alphaCutoff");
						if (d != null && (d < 0 || d > 1)) error(m.get("alphaCutoff"), what + ".alphaCutoff must be in 0-1");
						cutoff = d == null ? null : d.floatValue();
					} else cutoff = 0f;
				}
				Map<String, String> defines = defines(m.get("defines"), what + ".defines");
				String enabled = str(m.get("enabled"), what + ".enabled", null);
				if (enabled != null) Conditions.check(enabled, options, msg -> error(m.get("enabled"), what + ".enabled: " + msg));
				boolean rt = bool(m.get("raytracing"), what + ".raytracing", false);
				if (rt && !requires.contains("ray_query")) error(m.get("raytracing"), what + " uses ray queries: add \"requires\": [\"ray_query\"]");
				if (rt && kind.equals("geometry")) error(m.get("raytracing"), what + ": ray queries are for fullscreen, final and compute passes in format 1");
				boolean raygen = bool(m.get("raygen"), what + ".raygen", false);
				if (raygen && !(rt && kind.equals("compute"))) error(m.get("raygen"), what + ": raygen is for compute passes with \"raytracing\": true");

				float scale = 1;
				if (m.containsKey("scale")) {
					if (!kind.equals("fullscreen")) error(m.get("scale"), what + ": scale is for fullscreen passes");
					Double d = num(m.get("scale"), what + ".scale");
					if (d != null && !(d > 0 && d <= 1)) error(m.get("scale"), what + ".scale must be > 0 and <= 1");
					if (d != null) scale = d.floatValue();
				}
				List<String> mipmaps = strings(m.get("mipmaps"), what + ".mipmaps");
				for (String mm : mipmaps)
					if (!imageByName.containsKey(mm) || !imageByName.get(mm).mipmaps()) error(m.get("mipmaps"), what + ".mipmaps: \"" + mm + "\" is not an image with \"mipmaps\": true");
				if (!mipmaps.isEmpty() && (kind.equals("geometry") || kind.equals("compute"))) error(m.get("mipmaps"), what + ": mipmaps is for fullscreen and final passes");

				int[] local = {8, 8, 1}, count = null;
				float[] dispatch = {1, 1};
				if (m.containsKey("workgroups")) {
					if (!kind.equals("compute")) error(m.get("workgroups"), what + ": workgroups is for compute passes");
					var w = obj(m.get("workgroups"), what + ".workgroups", Set.of("size", "count", "scale"));
					if (w.containsKey("size")) local = ints(w.get("size"), what + ".workgroups.size", 3, 3, 1, 1024);
					if (local != null && local[0] * local[1] * local[2] > 1024) error(w.get("size"), what + ".workgroups.size: at most 1024 invocations");
					if (w.containsKey("count") && w.containsKey("scale")) error(m.get("workgroups"), what + ".workgroups takes \"count\" or \"scale\", not both");
					if (w.containsKey("count")) {
						count = ints(w.get("count"), what + ".workgroups.count", 3, 3, 1, 65535);
						dispatch = null;
					}
					if (w.containsKey("scale")) dispatch = floats(w.get("scale"), what + ".workgroups.scale", 2, 2);
				}
				if ("setup".equals(phase) && count == null && kind.equals("compute")) error(pn, what + ": setup passes need a fixed workgroups.count");
				out.add(new Pass(name, pn.line(), kind, phase, classes, stages, writes, blend, cutoff, defines, enabled,
						m.containsKey("enabled") ? m.get("enabled").line() : 0, rt, scale, mipmaps, local == null ? new int[]{8, 8, 1} : local, count, dispatch, raygen));
			}
			if (finals == 0 && !out.isEmpty()) error(n, "the graph needs one pass of kind \"final\"");
			Map<String, Integer> perPhase = new LinkedHashMap<>();
			for (Pass p : out) if (PASS_PHASES.contains(p.phase()) && perPhase.merge(p.phase(), 1, Integer::sum) == 100) error(n, "at most 99 passes per phase");
			return out;
		}

		/** Native blend value → Iris blend.* value ("off" or "SRC DST SRCA DSTA"). */
		String blend(JsonNode n, String what) {
			if (n.value() instanceof String s) {
				return switch (s) {
					case "off" -> "off";
					case "alpha" -> "SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA";
					case "additive" -> "ONE ONE ONE ONE";
					case "premultiplied" -> "ONE ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA";
					default -> {
						error(n, what + " must be off, alpha, additive, premultiplied or {src, dst, srcAlpha, dstAlpha}");
						yield null;
					}
				};
			}
			var m = obj(n, what, Set.of("src", "dst", "srcAlpha", "dstAlpha"));
			String[] f = new String[4];
			String[] keys = {"src", "dst", "srcAlpha", "dstAlpha"};
			for (int i = 0; i < 4; i++) {
				f[i] = str(m.get(keys[i]), what + "." + keys[i], i < 2 ? null : f[i - 2]);
				if (f[i] == null || !BLEND_FACTOR.matcher(f[i]).matches()) {
					error(n, what + "." + keys[i] + " must be a blend factor (zero, one, src_alpha, one_minus_src_alpha, …)");
					return null;
				}
			}
			return String.join(" ", f).toUpperCase(Locale.ROOT);
		}

		List<Uniform> uniforms(JsonNode n) {
			List<Uniform> r = new ArrayList<>();
			for (var e : obj(n, "uniforms", null).entrySet()) {
				String what = "uniform \"" + e.getKey() + "\"";
				if (!e.getKey().matches("[a-z][A-Za-z0-9_]{0,63}") || e.getKey().startsWith("el_")) error(e.getValue(), what + ": name must be camelCase and not start with el_");
				var m = obj(e.getValue(), what, Set.of("type", "expr", "variable"));
				String type = req(m, "type", what, e.getValue()), expr = req(m, "expr", what, e.getValue());
				if (type != null && !List.of("bool", "int", "float", "vec2", "vec3", "vec4").contains(type)) error(m.get("type"), what + ".type must be bool, int, float, vec2, vec3 or vec4");
				if (type != null && expr != null) r.add(new Uniform(e.getKey(), type, expr, bool(m.get("variable"), what + ".variable", false)));
			}
			return r;
		}

		List<Option> options(JsonNode n) {
			List<Option> r = new ArrayList<>();
			for (JsonNode on : arr(n, "options")) {
				var m = obj(on, "option", Set.of("name", "type", "default", "min", "max", "step", "values", "slider", "label", "comment", "restart"));
				String name = req(m, "name", "an option", on);
				if (name == null) continue;
				String what = "option \"" + name + "\"";
				if (!OPTION_NAME.matcher(name).matches() || name.startsWith("EL_")) error(m.get("name"), what + ": name must match [A-Z][A-Z0-9_]* and not start with EL_");
				String type = req(m, "type", what, on);
				if (type == null) continue;
				JsonNode def = m.get("default");
				if (def == null) {
					error(on, what + " needs a \"default\"");
					continue;
				}
				List<String> values = new ArrayList<>();
				String dv = null;
				switch (type) {
					case "bool" -> {
						if (def.value() instanceof Boolean b) dv = b.toString();
						else error(def, what + ": default must be true or false");
					}
					case "int", "float" -> {
						boolean isInt = type.equals("int");
						Double d = num(def, what + ".default");
						if (d == null) continue;
						int errorsBefore = out.size();
						if (m.containsKey("values")) {
							for (JsonNode v : arr(m.get("values"), what + ".values")) {
								Double x = num(v, what + ".values entry");
								if (x != null && isInt && x != Math.rint(x)) error(v, what + ": values must be integers");
								if (x != null) values.add(isInt ? Long.toString(x.longValue()) : glslFloat(x));
							}
						} else {
							Double min = num(m.get("min"), what + ".min"), max = num(m.get("max"), what + ".max"), step = num(m.get("step"), what + ".step");
							if (min == null || max == null) {
								error(on, what + " needs \"min\" and \"max\" (or \"values\")");
								continue;
							}
							double st = step == null ? (isInt ? 1 : (max - min) / 20) : step;
							if (!(st > 0) || max < min) {
								error(on, what + ": needs min <= max and step > 0");
								continue;
							}
							if (isInt && (min != Math.rint(min) || max != Math.rint(max) || st != Math.rint(st))) error(on, what + ": int options need integer min, max and step");
							if ((max - min) / st + 1 > MAX_OPTION_VALUES + 1e-9) {
								error(on, what + ": more than " + MAX_OPTION_VALUES + " values; use a larger step");
								continue;
							}
							BigDecimal b = BigDecimal.valueOf(min), s = BigDecimal.valueOf(st), hi = BigDecimal.valueOf(max);
							for (; b.compareTo(hi) <= 0; b = b.add(s)) values.add(isInt ? Long.toString(b.longValue()) : glslFloat(b.doubleValue()));
							if (d < min || d > max) error(def, what + ": default " + glslNumber(d) + " is " + (d < min ? "below min " + glslNumber(min) : "above max " + glslNumber(max)));
						}
						dv = isInt ? Long.toString(d.longValue()) : glslFloat(d);
						if (values.isEmpty()) error(on, what + " has no values");
						else if (!values.contains(dv) && (m.containsKey("values") || errorsBefore == out.size())) {
							if (m.containsKey("values")) error(def, what + ": default " + dv + " is not in values");
							else error(def, what + ": default " + dv + " is not on the min/step grid");
						}
						if (values.size() > MAX_OPTION_VALUES) error(on, what + ": more than " + MAX_OPTION_VALUES + " values");
					}
					case "enum" -> {
						for (JsonNode v : arr(m.get("values"), what + ".values")) {
							String s = str(v, what + ".values entry", null);
							if (s != null && !ENUM_VALUE.matcher(s).matches()) error(v, what + ": enum value \"" + s + "\" must match [a-z0-9_]{1,32}");
							else if (s != null) values.add(s);
						}
						if (values.isEmpty()) error(on, what + " needs \"values\"");
						dv = str(def, what + ".default", null);
						if (dv != null && !values.contains(dv)) error(def, what + ": default \"" + dv + "\" is not one of " + String.join(", ", values));
					}
					case "color" -> {
						float[] c = floats(def, what + ".default", 3, 3);
						if (c == null) continue;
						for (float f : c) if (f < 0 || f > 1) error(def, what + ": colour components must be in 0-1");
						dv = glslFloat(c[0]) + "," + glslFloat(c[1]) + "," + glslFloat(c[2]);
					}
					default -> error(m.get("type"), what + ": type must be bool, int, float, enum or color");
				}
				if (dv == null) continue;
				r.add(new Option(name, on.line(), type, dv, values, bool(m.get("slider"), what + ".slider", false),
						str(m.get("label"), what + ".label", null), str(m.get("comment"), what + ".comment", null)));
				bool(m.get("restart"), what + ".restart", false);
			}
			return r;
		}

		List<Page> pages(JsonNode n, Map<String, Option> options) {
			List<Page> r = new ArrayList<>();
			Set<String> names = new LinkedHashSet<>();
			for (JsonNode pn : arr(n, "pages")) {
				var m = obj(pn, "page", Set.of("name", "columns", "items"));
				String name = req(m, "name", "a page", pn);
				if (name == null) continue;
				if (!name.matches("[a-z][a-z0-9_]{0,47}")) error(m.get("name"), "page \"" + name + "\": name must match [a-z][a-z0-9_]*");
				if (!names.add(name)) error(m.get("name"), "page \"" + name + "\" is declared twice");
				Double cols = num(m.get("columns"), "page.columns");
				r.add(new Page(name, pn.line(), cols == null ? 2 : cols.intValue(), strings(m.get("items"), "page \"" + name + "\".items")));
			}
			Set<String> shown = new LinkedHashSet<>();
			for (Page p : r)
				for (String item : p.items()) {
					if (item.equals("<profile>") || item.equals("<empty>")) continue;
					if (item.startsWith("[") && item.endsWith("]")) {
						if (!names.contains(item.substring(1, item.length() - 1))) error(n, "page \"" + p.name() + "\" links unknown page " + item);
					} else if (!options.containsKey(item)) error(n, "page \"" + p.name() + "\" lists unknown option \"" + item + "\"");
					else shown.add(item);
				}
			if (!r.isEmpty()) for (String o : options.keySet()) if (!shown.contains(o)) warn(n, "option " + o + " is on no page (saved, but not shown)");
			return r;
		}

		Map<String, Map<String, String>> profiles(JsonNode n, Map<String, Option> options) {
			Map<String, Map<String, String>> r = new LinkedHashMap<>();
			var all = obj(n, "profiles", null);
			for (var e : all.entrySet()) {
				String what = "profile \"" + e.getKey() + "\"";
				if (!e.getKey().matches("[a-z][a-z0-9_]{0,31}")) error(e.getValue(), what + ": name must match [a-z][a-z0-9_]*");
				Map<String, String> v = new LinkedHashMap<>();
				for (var o : obj(e.getValue(), what, null).entrySet()) {
					if (o.getKey().equals("inherit")) {
						String parent = str(o.getValue(), what + ".inherit", null);
						if (parent != null && !all.containsKey(parent)) error(o.getValue(), what + " inherits unknown profile \"" + parent + "\"");
						if (parent != null) v.put("inherit", parent);
						continue;
					}
					Option opt = options.get(o.getKey());
					if (opt == null) {
						error(o.getValue(), what + " sets unknown option \"" + o.getKey() + "\"");
						continue;
					}
					Object x = o.getValue().value();
					String s = switch (opt.type()) {
						case "bool" -> x instanceof Boolean b ? b.toString() : null;
						case "int" -> x instanceof Double d && d == Math.rint(d) ? Long.toString(d.longValue()) : null;
						case "float" -> x instanceof Double d ? glslFloat(d) : null;
						case "enum" -> x instanceof String str ? str : null;
						default -> null;
					};
					if (opt.type().equals("color")) {
						float[] c = floats(o.getValue(), what + "." + o.getKey(), 3, 3);
						if (c != null) s = glslFloat(c[0]) + "," + glslFloat(c[1]) + "," + glslFloat(c[2]);
					}
					if (s == null || (!opt.values().isEmpty() && !opt.values().contains(s)))
						error(o.getValue(), what + ": invalid value for " + opt.name() + (opt.values().isEmpty() ? "" : " (" + String.join(", ", opt.values()) + ")"));
					else v.put(opt.name(), s);
				}
				r.put(e.getKey(), v);
			}
			return r;
		}

		Map<String, Map<Integer, List<String>>> materials(JsonNode n) {
			Map<String, Map<Integer, List<String>>> r = new LinkedHashMap<>();
			for (var kind : obj(n, "materials", Set.of("blocks", "items", "entities")).entrySet()) {
				Map<Integer, List<String>> ids = new LinkedHashMap<>();
				for (var e : obj(kind.getValue(), "materials." + kind.getKey(), null).entrySet()) {
					int id;
					try {
						id = Integer.parseInt(e.getKey());
					} catch (NumberFormatException x) {
						id = -1;
					}
					if (id < 1 || id > 65535) {
						error(e.getValue(), "materials." + kind.getKey() + ": id \"" + e.getKey() + "\" must be an integer in 1-65535");
						continue;
					}
					List<String> matchers = strings(e.getValue(), "materials." + kind.getKey() + "." + id);
					for (String s : matchers)
						if (!s.matches("#?[a-z0-9_.-]+:[a-z0-9_./-]+(:[a-z0-9_]+=[a-z0-9_]+)*"))
							error(e.getValue(), "materials." + kind.getKey() + "." + id + ": \"" + s + "\" is not namespace:name[:prop=value…] or #namespace:tag");
					ids.put(id, matchers);
				}
				r.put(kind.getKey(), ids);
			}
			return r;
		}

		List<Dimension> dimensions(JsonNode n, List<Pass> passes) {
			List<Dimension> r = new ArrayList<>();
			for (var e : obj(n, "dimensions", null).entrySet()) {
				String what = "dimension override \"" + e.getKey() + "\"";
				if (!e.getKey().matches("[a-z][a-z0-9_]{0,31}") || e.getKey().equals("default")) error(e.getValue(), what + ": name must match [a-z][a-z0-9_]* and not be \"default\"");
				var m = obj(e.getValue(), what, Set.of("match", "defines", "disable"));
				List<String> match = strings(m.get("match"), what + ".match");
				if (match.isEmpty()) error(e.getValue(), what + " needs \"match\" (dimension ids)");
				for (String s : match) if (!s.matches("[a-z0-9_.-]+:([a-z0-9_./-]+|\\*)")) error(m.get("match"), what + ": \"" + s + "\" is not a dimension id (namespace:path or namespace:*)");
				List<String> disable = strings(m.get("disable"), what + ".disable");
				for (String d : disable) if (passes.stream().noneMatch(p -> p.name().equals(d))) error(m.get("disable"), what + " disables unknown pass \"" + d + "\"");
				r.add(new Dimension(e.getKey(), e.getValue().line(), match, defines(m.get("defines"), what + ".defines"), disable));
			}
			return r;
		}
	}

	static String glslNumber(double d) {
		return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : glslFloat(d);
	}

	static String glslFloat(float f) { return glslFloat(Double.parseDouble(Float.toString(f))); }

	/** Shortest decimal text that is a GLSL float literal ("1.0", "0.25"). */
	static String glslFloat(double d) {
		String s = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
		return s.contains(".") ? s : s + ".0";
	}
}
