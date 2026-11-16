package dev.emrion.shaders.converter.options;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses a single source line into an option declaration or an {@code #ifdef}/{@code #ifndef} reference, with Iris's
 * exact rules (Iris OptionAnnotatedSource). Each line is looked at in isolation.
 */
final class OptionLineParser {
	private OptionLineParser() {}

	/** Names Iris accepts as configurable {@code const} options. */
	static final Set<String> CONST_OPTION_NAMES;

	static {
		Set<String> s = new HashSet<>(List.of("shadowMapResolution", "shadowDistance", "voxelDistance",
				"shadowDistanceRenderMul", "entityShadowDistanceMul", "shadowIntervalSize", "generateShadowMipmap",
				"generateShadowColorMipmap", "shadowHardwareFiltering", "shadowtex0Mipmap", "shadowtexMipmap",
				"shadowtex1Mipmap", "shadowtex0Nearest", "shadowtexNearest", "shadow0MinMagNearest", "shadowtex1Nearest",
				"shadow1MinMagNearest", "wetnessHalflife", "drynessHalflife", "eyeBrightnessHalflife", "centerDepthHalflife",
				"sunPathRotation", "ambientOcclusionLevel", "superSamplingLevel", "noiseTextureResolution"));
		for (int i = 0; i < 8; i++) {
			for (String c : new String[]{"shadowcolor", "shadowColor"}) {
				s.add(c + i + "Mipmap");
				s.add(c + i + "Nearest");
				s.add(c + i + "MinMagNearest");
			}
			s.add("shadowHardwareFiltering" + i);
		}
		CONST_OPTION_NAMES = Set.copyOf(s);
	}

	/** Result of one line: an option, an #ifdef reference, or neither. */
	record Parsed(OptionLine option, String reference) {
		static final Parsed NONE = new Parsed(null, null);
	}

	static Parsed parse(String lineText) {
		if (!lineText.contains("#define") && !lineText.contains("const")
				&& !lineText.contains("#ifdef") && !lineText.contains("#ifndef")) return Parsed.NONE;
		LineCursor line = new LineCursor(lineText.trim());
		if (line.literal("#ifdef") || line.literal("#ifndef")) {
			// Iris quirk: only "#ifdef NAME" with nothing after it (not even a comment) registers a boolean option.
			if (!line.whitespace()) return Parsed.NONE;
			String name = line.word();
			line.whitespace();
			return name != null && line.atEnd() ? new Parsed(null, name) : Parsed.NONE;
		}
		if (line.literal("const")) return new Parsed(parseConst(line), null);
		if (line.contains("#define")) return new Parsed(parseDefine(line), null);
		return Parsed.NONE;
	}

	private static OptionLine parseConst(LineCursor line) {
		if (!line.whitespace()) return null;
		String type;
		if (line.literal("int")) type = "int";
		else if (line.literal("float")) type = "float";
		else if (line.literal("bool")) type = "bool";
		else return null;
		if (!line.whitespace()) return null;
		String name = line.word();
		if (name == null) return null;
		line.whitespace();
		if (!line.literal("=")) return null;
		line.whitespace();
		String value = line.wordOrNumber();
		if (value == null) return null;
		line.whitespace();
		if (!line.literal(";")) return null;
		line.whitespace();
		String comment = null;
		if (line.comment()) comment = line.rest().trim();
		else if (!line.atEnd()) return null;
		if (!CONST_OPTION_NAMES.contains(name)) return null;
		if (type.equals("bool")) {
			if (!value.equals("true") && !value.equals("false")) return null;
			return new OptionLine(OptionLine.Kind.CONST_BOOL, name, value, List.of(), emptyToNull(comment), type);
		}
		return stringOption(OptionLine.Kind.CONST_STRING, name, value, comment, type);
	}

	private static OptionLine parseDefine(LineCursor line) {
		boolean commented = line.comment();
		line.whitespace();
		if (!line.literal("#define")) return null;
		if (!line.whitespace()) return null;
		String name = line.word();
		if (name == null) return null;
		boolean ws = line.whitespace();
		String defaultBool = commented ? "false" : "true";
		if (line.atEnd()) return new OptionLine(OptionLine.Kind.DEFINE_BOOL, name, defaultBool, List.of(), null, null);
		if (line.comment()) {
			return new OptionLine(OptionLine.Kind.DEFINE_BOOL, name, defaultBool, List.of(), emptyToNull(line.rest().trim()), null);
		}
		// A leading "//" is only allowed on boolean defines.
		if (!ws || commented) return null;
		String value = line.wordOrNumber();
		if (value == null) return null;
		ws = line.whitespace();
		if (line.atEnd()) return null;
		if (!line.comment()) return null;
		return stringOption(OptionLine.Kind.DEFINE_STRING, name, value, line.rest().trim(), null);
	}

	/** Iris StringOption.create: needs "[a b c]" in the comment; split on single spaces; default appended if missing. */
	private static OptionLine stringOption(OptionLine.Kind kind, String name, String value, String comment, String type) {
		if (comment == null) return null;
		int open = comment.indexOf('[');
		if (open < 0) return null;
		int close = comment.indexOf(']', open);
		if (close < 0) return null;
		List<String> allowed = new ArrayList<>(List.of(comment.substring(open + 1, close).split(" ")));
		if (!allowed.contains(value)) allowed.add(value);
		String rest = (comment.substring(0, open) + comment.substring(close + 1)).trim();
		return new OptionLine(kind, name, value, List.copyOf(allowed), emptyToNull(rest), type);
	}

	private static String emptyToNull(String s) {
		return s == null || s.isEmpty() ? null : s;
	}
}
