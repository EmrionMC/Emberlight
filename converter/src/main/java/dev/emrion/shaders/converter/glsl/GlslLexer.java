package dev.emrion.shaders.converter.glsl;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer for preprocessed GLSL. Each token keeps the whitespace before it, so concatenating {@code ws + text}
 * reproduces the input (comments, if any, are folded into whitespace). Directive lines ({@code #...}) are one token.
 */
public final class GlslLexer {
	private GlslLexer() {}

	public enum Kind { IDENT, NUMBER, PUNCT, DIRECTIVE }

	public static final class Token {
		public final Kind kind;
		public final String text;
		public final String ws;

		Token(Kind kind, String text, String ws) {
			this.kind = kind;
			this.text = text;
			this.ws = ws;
		}

		public boolean is(String s) { return text.equals(s); }

		public boolean ident() { return kind == Kind.IDENT; }

		@Override
		public String toString() { return text; }
	}

	private static final String[] OPS = {
			"<<=", ">>=", "++", "--", "&&", "||", "^^", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
			"<<", ">>"};

	public static List<Token> lex(String src) {
		List<Token> out = new ArrayList<>();
		int n = src.length(), i = 0;
		StringBuilder ws = new StringBuilder();
		boolean lineStart = true;
		while (i < n) {
			char c = src.charAt(i);
			if (c == '\n') { ws.append(c); i++; lineStart = true; continue; }
			if (Character.isWhitespace(c)) { ws.append(c); i++; continue; }
			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
				while (i < n && src.charAt(i) != '\n') i++;
				ws.append(' ');
				continue;
			}
			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
				int e = src.indexOf("*/", i + 2);
				e = e < 0 ? n : e + 2;
				for (int k = i; k < e; k++) if (src.charAt(k) == '\n') ws.append('\n');
				ws.append(' ');
				i = e;
				continue;
			}
			int s = i;
			Kind kind;
			if (c == '#' && lineStart) {
				while (i < n && src.charAt(i) != '\n') i++;
				kind = Kind.DIRECTIVE;
			} else if (Character.isLetter(c) || c == '_') {
				while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
				kind = Kind.IDENT;
			} else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
				i++;
				while (i < n) {
					char d = src.charAt(i);
					if (Character.isLetterOrDigit(d) || d == '.' || d == '_') i++;
					else if ((d == '+' || d == '-') && (src.charAt(i - 1) == 'e' || src.charAt(i - 1) == 'E')
							&& !src.startsWith("0x", s) && !src.startsWith("0X", s)) i++;
					else break;
				}
				kind = Kind.NUMBER;
			} else {
				i++;
				for (String op : OPS) {
					if (src.startsWith(op, s)) { i = s + op.length(); break; }
				}
				kind = Kind.PUNCT;
			}
			out.add(new Token(kind, src.substring(s, i), ws.toString()));
			ws.setLength(0);
			lineStart = false;
		}
		return out;
	}
}
