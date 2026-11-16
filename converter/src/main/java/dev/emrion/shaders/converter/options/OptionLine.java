package dev.emrion.shaders.converter.options;

import java.util.List;

/** One option declaration on a source line. {@code declType} is null for #define options. */
public record OptionLine(Kind kind, String name, String defaultValue, List<String> allowed, String comment, String declType) {
	public enum Kind { DEFINE_BOOL, DEFINE_STRING, CONST_BOOL, CONST_STRING }

	public boolean isBoolean() {
		return kind == Kind.DEFINE_BOOL || kind == Kind.CONST_BOOL;
	}

	public boolean isConst() {
		return kind == Kind.CONST_BOOL || kind == Kind.CONST_STRING;
	}
}
