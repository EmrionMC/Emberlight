package dev.emrion.shaders.converter.glsl;

import dev.emrion.shaders.converter.glsl.PpToken.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tokenizer for the C-style preprocessor. Backslash-newline splices are removed everywhere (as JCPP does) but still
 * counted as lines, so {@link PpToken#line()} stays the physical source line.
 */
final class PpLexer {
	private static final String[] TWO_CHAR = {"##", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||"};

	private final String s;
	private final int n;
	private int pos;
	private int line = 1;

	PpLexer(String source) {
		this.s = source;
		this.n = source.length();
	}

	int line() {
		return line;
	}

	/** Lexes a whole string (used for re-lexing pasted tokens). */
	static List<PpToken> lexAll(String text) {
		PpLexer lx = new PpLexer(text);
		List<PpToken> out = new ArrayList<>();
		for (PpToken t; (t = lx.next()).kind() != Kind.EOF; ) out.add(t);
		return out;
	}

	private void splices() {
		while (pos + 1 < n && s.charAt(pos) == '\\' && s.charAt(pos + 1) == '\n') {
			pos += 2;
			line++;
		}
	}

	private char peek() {
		splices();
		return pos < n ? s.charAt(pos) : '\0';
	}

	private char peek2() {
		splices();
		int p = pos + 1;
		while (p + 1 < n && s.charAt(p) == '\\' && s.charAt(p + 1) == '\n') p += 2;
		return p < n ? s.charAt(p) : '\0';
	}

	PpToken next() {
		splices();
		if (pos >= n) return PpToken.EOF_TOKEN;
		int startLine = line;
		int start = pos;
		char c = s.charAt(pos);
		Kind kind;
		if (c == '\n') {
			pos++;
			line++;
			return new PpToken(Kind.NEWLINE, "\n", startLine, Set.of(), true);
		} else if (c == ' ' || c == '\t' || c == '\r' || c == '\f' || c == 0x0B) {
			do pos++; while (isSpace(peek()));
			kind = Kind.WS;
		} else if (c == '/' && peek2() == '/') {
			while (pos < n && peek() != '\n' && pos < n) pos++;
			kind = Kind.COMMENT;
		} else if (c == '/' && peek2() == '*') {
			pos += 2;
			while (pos < n) {
				char d = s.charAt(pos);
				if (d == '*' && pos + 1 < n && s.charAt(pos + 1) == '/') {
					pos += 2;
					break;
				}
				if (d == '\n') line++;
				pos++;
			}
			kind = Kind.COMMENT;
		} else if (isIdentStart(c)) {
			do pos++; while (isIdentPart(peek()));
			kind = Kind.IDENT;
		} else if (isDigit(c) || (c == '.' && isDigit(peek2()))) {
			pos++;
			while (true) {
				char d = peek();
				if ((d == '+' || d == '-') && "eEpP".indexOf(s.charAt(pos - 1)) >= 0) pos++;
				else if (isIdentPart(d) || d == '.') pos++;
				else break;
			}
			kind = Kind.NUMBER;
		} else if (c == '"') {
			pos++;
			while (pos < n) {
				char d = peek();
				if (d == '\n' || pos >= n) break;
				pos++;
				if (d == '"') break;
				if (d == '\\' && pos < n && s.charAt(pos) != '\n') pos++;
			}
			kind = Kind.STRING;
		} else {
			pos++;
			kind = Kind.PUNCT;
			if (pos < n) {
				char d = peek();
				for (String op : TWO_CHAR) {
					if (op.charAt(0) == c && op.charAt(1) == d) {
						pos++;
						break;
					}
				}
			}
		}
		String text = s.substring(start, pos);
		if (kind != Kind.STRING && text.indexOf('\\') >= 0) text = text.replace("\\\n", "");
		return new PpToken(kind, text, startLine, Set.of(), true);
	}

	private static boolean isSpace(char c) {
		return c == ' ' || c == '\t' || c == '\r' || c == '\f' || c == 0x0B;
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	static boolean isIdentStart(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || (c > 127 && Character.isLetter(c));
	}

	static boolean isIdentPart(char c) {
		return isIdentStart(c) || isDigit(c);
	}
}
