package dev.emrion.shaders.converter.options;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites option lines in source text exactly like Iris (OptionAnnotatedSource.edit). Line count and
 * positions never change. Every annotated line is edited, using that line's own default.
 */
public final class OptionApplier {
	private OptionApplier() {}

	public static List<String> apply(List<String> lines, Map<Integer, OptionLine> annotations, OptionValues values) {
		if (annotations == null || annotations.isEmpty()) return lines;
		List<String> out = new ArrayList<>(lines);
		annotations.forEach((i, opt) -> out.set(i, edit(lines.get(i), opt, values)));
		return out;
	}

	static String edit(String line, OptionLine opt, OptionValues values) {
		return switch (opt.kind()) {
			case DEFINE_BOOL -> {
				Boolean v = values.changedBoolean(opt.name());
				boolean on = v != null ? v : Boolean.parseBoolean(opt.defaultValue());
				if (line.trim().startsWith("//") && on) yield stripLeadingComment(line);
				// Iris quirk: a default-off line gets one more "//" every time; harmless, it stays commented out.
				yield on ? line : "//" + line;
			}
			case CONST_BOOL -> {
				Boolean v = values.changedBoolean(opt.name());
				yield v == null ? line : editConst(line, opt.defaultValue(), v.toString());
			}
			case DEFINE_STRING -> {
				String v = values.changedString(opt.name());
				yield v == null ? line : "#define " + opt.name() + " " + v + " // OptionAnnotatedSource: Changed option";
			}
			case CONST_STRING -> {
				String v = values.changedString(opt.name());
				yield v == null ? line : editConst(line, opt.defaultValue(), v);
			}
		};
	}

	/** Iris removeLeadingComment: trims, then drops "//" and any further slashes. */
	private static String stripLeadingComment(String line) {
		LineCursor c = new LineCursor(line);
		c.whitespace();
		c.comment();
		return c.rest();
	}

	/** Replaces the first occurrence of the old value after the first '='. */
	private static String editConst(String line, String oldValue, String newValue) {
		int eq = line.indexOf('=');
		return line.substring(0, eq) + line.substring(eq).replaceFirst(Pattern.quote(oldValue), Matcher.quoteReplacement(newValue));
	}
}
