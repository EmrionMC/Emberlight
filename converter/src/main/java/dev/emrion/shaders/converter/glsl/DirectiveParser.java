package dev.emrion.shaders.converter.glsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source directives (iris-spec §3.2–3.4), read from the comment-preserving preprocessor output like Iris does, so
 * directives inside {@code /* *}{@code /} count and inactive {@code #if} branches do not.
 */
public final class DirectiveParser {
	private DirectiveParser() {}

	public record DrawBuffers(List<Integer> buffers, boolean renderTargets) {}

	private static final Pattern LAYOUT_IN = Pattern.compile("layout\\s*\\(([^)]*)\\)\\s*in\\s*;");
	private static final Pattern LOCAL_SIZE = Pattern.compile("local_size_([xyz])\\s*=\\s*(\\d+)");
	private static final Set<String> INT_CONSTS = Set.of("shadowMapResolution", "noiseTextureResolution");
	private static final Set<String> FLOAT_CONSTS = Set.of("sunPathRotation", "ambientOcclusionLevel", "wetnessHalflife",
			"drynessHalflife", "eyeBrightnessHalflife", "centerDepthHalflife", "shadowMapFov", "shadowDistance",
			"shadowNearPlane", "shadowFarPlane", "voxelDistance", "entityShadowDistanceMul", "shadowDistanceRenderMul",
			"shadowIntervalSize");

	/** @param plain comment-free text (for layout(local_size)); commented: comment-preserving text */
	public static Directives parse(StageType stage, String plain, String commented, Consumer<String> warnings) {
		DrawBuffers db = stage == StageType.FRAGMENT ? drawBuffers(commented, warnings) : null;
		Map<String, String> consts = consts(commented);
		int[] localSize = null, workGroups = null;
		float[] workGroupsRender = null;
		if (stage == StageType.COMPUTE) {
			localSize = localSize(plain);
			workGroups = ints(consts.get("workGroups"), "ivec3", 3);
			float[] wr = floats(consts.get("workGroupsRender"), "vec2", 2);
			workGroupsRender = wr;
		}
		return new Directives(db == null ? null : db.buffers(), db != null && db.renderTargets(), consts, localSize,
				workGroups, workGroupsRender);
	}

	/** Last DRAWBUFFERS/RENDERTARGETS comment wins; null if there is none (Iris then uses {0}). */
	public static DrawBuffers drawBuffers(String commented, Consumer<String> warnings) {
		int[] dbAt = new int[1], rtAt = new int[1];
		String db = findComment(commented, "DRAWBUFFERS", dbAt);
		String rt = findComment(commented, "RENDERTARGETS", rtAt);
		if (db == null && rt == null) return null;
		if (rt == null || (db != null && dbAt[0] > rtAt[0])) {
			List<Integer> list = new ArrayList<>();
			for (char c : db.toCharArray()) {
				int d = Character.digit(c, 10);
				if (d < 0) warnings.accept("DRAWBUFFERS only accepts digits 0-9, got '" + c + "'");
				else list.add(d);
			}
			return new DrawBuffers(List.copyOf(list), false);
		}
		List<Integer> list = new ArrayList<>();
		for (String part : rt.split(",")) {
			try {
				list.add(Integer.parseInt(part));
			} catch (NumberFormatException e) {
				// Iris quirk: Integer.parseInt without trim, so "0, 1" makes Iris fail to load the program.
				warnings.accept("RENDERTARGETS entry '" + part + "' is not a plain integer (Iris fails to load this)");
				try {
					list.add(Integer.parseInt(part.trim()));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return new DrawBuffers(List.copyOf(list), true);
	}

	/** Iris CommentDirectiveParser: only the last "KEY:" counts and it must directly follow a "/*". */
	private static String findComment(String text, String key, int[] at) {
		int idx = text.lastIndexOf(key + ":");
		if (idx < 0 || !text.substring(0, idx).trim().endsWith("/*")) return null;
		String rest = text.substring(idx + key.length() + 1);
		int end = rest.indexOf("*/");
		if (end < 0) return null;
		at[0] = idx;
		return rest.substring(0, end).trim();
	}

	/**
	 * Every {@code const <int|float|vec2|ivec3|vec4|bool> <name> = <value>;} line, last valid one per name wins.
	 * For names Iris knows, a value of the wrong declared type or unparsable literal is skipped (Iris ignores it too).
	 */
	public static Map<String, String> consts(String commented) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String line : commented.split("\\R")) {
			if (!line.contains("const") || !line.contains("=") || !line.contains(";")) continue;
			String[] d = constLine(line);
			if (d != null && accepts(d[0], d[1], d[2])) out.put(d[1], d[2]);
		}
		return out;
	}

	/** Iris ConstDirectiveParser line grammar → {type, name, value} or null. */
	static String[] constLine(String line) {
		String l = line.trim();
		if (!l.startsWith("const")) return null;
		l = l.substring(5);
		if (l.isEmpty() || !Character.isWhitespace(l.charAt(0))) return null;
		l = l.trim();
		String type = null;
		for (String t : new String[]{"int", "float", "vec2", "ivec3", "vec4", "bool"}) {
			if (l.startsWith(t)) {
				type = t;
				break;
			}
		}
		if (type == null) return null;
		l = l.substring(type.length());
		if (l.isEmpty() || !Character.isWhitespace(l.charAt(0))) return null;
		int eq = l.indexOf('=');
		if (eq < 0) return null;
		String name = l.substring(0, eq).trim();
		if (name.isEmpty() || !name.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_')) return null;
		String rest = l.substring(eq + 1);
		int semi = rest.indexOf(';');
		if (semi < 0) return null;
		return new String[]{type, name, rest.substring(0, semi).trim()};
	}

	private static boolean accepts(String type, String name, String value) {
		String expected = expectedType(name);
		if (expected == null) return true;
		if (!expected.equals(type)) return false;
		try {
			switch (type) {
				case "bool" -> {
					return value.equals("true") || value.equals("false");
				}
				case "int" -> {
					if (!name.endsWith("Format")) Integer.parseInt(value);
				}
				case "float" -> Float.parseFloat(value);
				default -> {
				}
			}
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/** Declared type Iris dispatches on for a const directive name, or null if Iris has no handler for it. */
	static String expectedType(String name) {
		if (name.equals("workGroups")) return "ivec3";
		if (name.equals("workGroupsRender")) return "vec2";
		if (name.endsWith("ClearColor")) return "vec4";
		if (name.endsWith("Format")) return "int";
		if (INT_CONSTS.contains(name)) return "int";
		if (FLOAT_CONSTS.contains(name)) return "float";
		if (name.endsWith("Clear") || name.endsWith("Mipmap") || name.endsWith("MipmapEnabled") || name.endsWith("Nearest")
				|| name.startsWith("shadowHardwareFiltering") || name.startsWith("generateShadow")) return "bool";
		return null;
	}

	static int[] localSize(String plain) {
		Matcher m = LAYOUT_IN.matcher(plain);
		int[] size = null;
		while (m.find()) {
			Matcher ls = LOCAL_SIZE.matcher(m.group(1));
			while (ls.find()) {
				if (size == null) size = new int[]{1, 1, 1};
				size[ls.group(1).charAt(0) - 'x'] = Integer.parseInt(ls.group(2));
			}
		}
		return size;
	}

	private static String[] ctorArgs(String value, String ctor, int n) {
		if (value == null || !value.startsWith(ctor)) return null;
		String args = value.substring(ctor.length()).trim();
		if (!args.startsWith("(") || !args.endsWith(")")) return null;
		String[] parts = args.substring(1, args.length() - 1).split(",");
		return parts.length == n ? parts : null;
	}

	static int[] ints(String value, String ctor, int n) {
		String[] p = ctorArgs(value, ctor, n);
		if (p == null) return null;
		try {
			int[] r = new int[n];
			for (int i = 0; i < n; i++) r[i] = Integer.parseInt(p[i].trim());
			return r;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static float[] floats(String value, String ctor, int n) {
		String[] p = ctorArgs(value, ctor, n);
		if (p == null) return null;
		try {
			float[] r = new float[n];
			for (int i = 0; i < n; i++) r[i] = Float.parseFloat(p[i].trim());
			return r;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
