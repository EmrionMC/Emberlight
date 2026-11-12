package dev.emrion.shaders.converter.glsl;

import java.util.Set;

/**
 * Preprocessor token. {@code hide} is the C hide set (macros that must not expand this token again); {@code src} is true
 * for tokens read straight from the source text (only those can start a directive).
 */
record PpToken(Kind kind, String text, int line, Set<String> hide, boolean src) {
	enum Kind { IDENT, NUMBER, STRING, PUNCT, WS, NEWLINE, COMMENT, PASTE, STRINGIFY, PLACEMARKER, EOF }

	static final PpToken EOF_TOKEN = new PpToken(Kind.EOF, "", 0, Set.of(), false);

	boolean isSpace() {
		return kind == Kind.WS || kind == Kind.NEWLINE || kind == Kind.COMMENT;
	}

	boolean is(String punct) {
		return kind == Kind.PUNCT && text.equals(punct);
	}

	PpToken withHide(Set<String> h) {
		return new PpToken(kind == Kind.NEWLINE ? Kind.WS : kind, kind == Kind.NEWLINE ? " " : text, line, h, false);
	}
}
