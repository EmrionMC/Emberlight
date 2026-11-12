package dev.emrion.shaders.converter.glsl;

import dev.emrion.shaders.converter.glsl.PpToken.Kind;

import java.util.ArrayList;
import java.util.List;

/**
 * Integer evaluator for {@code #if}/{@code #elif} after {@code defined} and macro expansion. Identifiers left over are 0.
 * JCPP quirk (matched on purpose): floating literals are truncated to integers, so {@code 1.00 > 0.5} is {@code 1 > 0}.
 */
final class PpExpression {
	static final class EvalException extends Exception {
		EvalException(String message) {
			super(message);
		}
	}

	private final List<PpToken> toks = new ArrayList<>();
	private int i;

	private PpExpression(List<PpToken> tokens) {
		for (PpToken t : tokens) if (!t.isSpace() && t.kind() != Kind.PLACEMARKER) toks.add(t);
	}

	static long evaluate(List<PpToken> tokens) throws EvalException {
		PpExpression e = new PpExpression(tokens);
		if (e.toks.isEmpty()) throw new EvalException("Empty #if expression");
		long v = e.ternary();
		if (e.i < e.toks.size()) throw new EvalException("Unexpected token in #if expression: " + e.toks.get(e.i).text());
		return v;
	}

	private PpToken peek() {
		return i < toks.size() ? toks.get(i) : PpToken.EOF_TOKEN;
	}

	private boolean accept(String op) {
		if (peek().is(op)) {
			i++;
			return true;
		}
		return false;
	}

	private long ternary() throws EvalException {
		long c = binary(0);
		if (accept("?")) {
			long a = ternary();
			if (!accept(":")) throw new EvalException("Missing ':' in #if expression");
			long b = ternary();
			return c != 0 ? a : b;
		}
		return c;
	}

	private static final String[][] LEVELS = {
			{"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<", ">", "<=", ">="}, {"<<", ">>"}, {"+", "-"}, {"*", "/", "%"}};

	private long binary(int level) throws EvalException {
		if (level == LEVELS.length) return unary();
		long l = binary(level + 1);
		outer:
		while (true) {
			for (String op : LEVELS[level]) {
				if (accept(op)) {
					long r = binary(level + 1);
					l = apply(op, l, r);
					continue outer;
				}
			}
			return l;
		}
	}

	private static long apply(String op, long l, long r) throws EvalException {
		return switch (op) {
			case "||" -> (l != 0 || r != 0) ? 1 : 0;
			case "&&" -> (l != 0 && r != 0) ? 1 : 0;
			case "|" -> l | r;
			case "^" -> l ^ r;
			case "&" -> l & r;
			case "==" -> l == r ? 1 : 0;
			case "!=" -> l != r ? 1 : 0;
			case "<" -> l < r ? 1 : 0;
			case ">" -> l > r ? 1 : 0;
			case "<=" -> l <= r ? 1 : 0;
			case ">=" -> l >= r ? 1 : 0;
			case "<<" -> l << r;
			case ">>" -> l >> r;
			case "+" -> l + r;
			case "-" -> l - r;
			case "*" -> l * r;
			case "/" -> {
				if (r == 0) throw new EvalException("Division by zero in #if expression");
				yield l / r;
			}
			case "%" -> {
				if (r == 0) throw new EvalException("Division by zero in #if expression");
				yield l % r;
			}
			default -> throw new IllegalStateException(op);
		};
	}

	private long unary() throws EvalException {
		if (accept("!")) return unary() == 0 ? 1 : 0;
		if (accept("-")) return -unary();
		if (accept("+")) return unary();
		if (accept("~")) return ~unary();
		if (accept("(")) {
			long v = ternary();
			if (!accept(")")) throw new EvalException("Missing ')' in #if expression");
			return v;
		}
		PpToken t = peek();
		i++;
		return switch (t.kind()) {
			case NUMBER -> parseNumber(t.text());
			case IDENT -> 0;
			default -> throw new EvalException("Bad token in #if expression: " + (t.kind() == Kind.EOF ? "end of line" : t.text()));
		};
	}

	static long parseNumber(String text) throws EvalException {
		String s = text;
		try {
			if (s.startsWith("0x") || s.startsWith("0X")) {
				s = stripSuffix(s.substring(2), "uUlL");
				return Long.parseUnsignedLong(s, 16);
			}
			if (s.indexOf('.') >= 0 || s.indexOf('e') >= 0 || s.indexOf('E') >= 0) {
				return (long) Double.parseDouble(stripSuffix(s, "fFlL"));
			}
			s = stripSuffix(s, "uUlL");
			if (s.length() > 1 && s.charAt(0) == '0') return Long.parseLong(s, 8);
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			throw new EvalException("Bad number in #if expression: " + text);
		}
	}

	private static String stripSuffix(String s, String suffixChars) {
		int end = s.length();
		while (end > 0 && suffixChars.indexOf(s.charAt(end - 1)) >= 0) end--;
		return s.substring(0, end);
	}
}
