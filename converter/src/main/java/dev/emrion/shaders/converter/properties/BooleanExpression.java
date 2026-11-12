package dev.emrion.shaders.converter.properties;

import dev.emrion.shaders.converter.options.OptionValues;

import java.util.function.Predicate;

/**
 * {@code program.<path>.enabled} expressions (Iris BooleanParser): option names, true/1, false/0, '!', "&&" (binds
 * tighter), "||" and parentheses. Unknown names are true (Iris warns), and any parse error makes the whole
 * expression true.
 */
public final class BooleanExpression {
	private final String s;
	private final Predicate<String> lookup;
	private int i;

	private BooleanExpression(String s, Predicate<String> lookup) {
		this.s = s;
		this.lookup = lookup;
	}

	public static boolean evaluate(String expression, OptionValues values) {
		return evaluate(expression, values::booleanOrDefault);
	}

	public static boolean evaluate(String expression, Predicate<String> lookup) {
		BooleanExpression p = new BooleanExpression(expression.replace(" ", ""), lookup);
		try {
			boolean v = p.or();
			return p.i < p.s.length() || v;
		} catch (IllegalStateException e) {
			return true;
		}
	}

	private boolean or() {
		boolean v = and();
		while (i < s.length() && s.charAt(i) == '|') {
			i += 2;
			v = and() | v;
		}
		return v;
	}

	private boolean and() {
		boolean v = unary();
		while (i < s.length() && s.charAt(i) == '&') {
			i += 2;
			v = unary() & v;
		}
		return v;
	}

	private boolean unary() {
		if (i >= s.length()) throw new IllegalStateException();
		char c = s.charAt(i);
		if (c == '!') {
			i++;
			return !unary();
		}
		if (c == '(') {
			i++;
			boolean v = or();
			if (i >= s.length() || s.charAt(i) != ')') throw new IllegalStateException();
			i++;
			return v;
		}
		int start = i;
		while (i < s.length() && "!&|()".indexOf(s.charAt(i)) < 0) i++;
		if (start == i) throw new IllegalStateException();
		String name = s.substring(start, i);
		return switch (name) {
			case "true", "1" -> true;
			case "false", "0" -> false;
			default -> lookup.test(name);
		};
	}
}
