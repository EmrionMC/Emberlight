package dev.emrion.shaders.converter.options;

import java.util.List;

/**
 * One option declaration found on a source line (iris-spec §3.3).
 *
 * @param declType "int"/"float"/"bool" for const options, null for #define options
 * @param allowed  allowed values for string options (default appended if missing), empty for booleans
 * @param comment  comment text without the allowed-values list, or null
 */
public record OptionLine(Kind kind, String name, String defaultValue, List<String> allowed, String comment, String declType) {
	public enum Kind { DEFINE_BOOL, DEFINE_STRING, CONST_BOOL, CONST_STRING }

	public boolean isBoolean() {
		return kind == Kind.DEFINE_BOOL || kind == Kind.CONST_BOOL;
	}

	public boolean isConst() {
		return kind == Kind.CONST_BOOL || kind == Kind.CONST_STRING;
	}
}
