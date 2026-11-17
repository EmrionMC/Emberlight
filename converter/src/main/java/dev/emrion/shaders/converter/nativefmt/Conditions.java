package dev.emrion.shaders.converter.nativefmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pass {@code enabled} conditions (NATIVE_FORMAT.md §10.4): bool, int and enum options, integer literals, {@code ! && ||},
 * comparisons and parentheses, evaluated with the resolved option values when the pack is compiled.
 */
final class Conditions {
	private Conditions() {}

	/** Engine state a condition may test (§10.4), 1 or 0 when the pack is compiled. */
	static final java.util.Set<String> ENGINE = java.util.Set.of("EL_UPSCALING", "EL_RR_ACTIVE");

	private static final Pattern TOKEN = Pattern.compile("\\s*(&&|\\|\\||==|!=|<=|>=|[<>!()]|[A-Za-z_][A-Za-z0-9_]*|\\d+)");

	private static List<String> tokens(String expr, Consumer<String> error) {
		List<String> out = new ArrayList<>();
		Matcher m = TOKEN.matcher(expr);
		int at = 0;
		while (at < expr.length()) {
			if (!m.find(at) || m.start() != at) {
				if (expr.substring(at).isBlank()) break;
				error.accept("unexpected \"" + expr.substring(at).strip() + "\"");
				return null;
			}
			out.add(m.group(1));
			at = m.end();
		}
		return out;
	}

	/** Reports the first problem; silent when the expression is valid. */
	static void check(String expr, Map<String, NativeManifest.Option> options, Consumer<String> error) {
		List<String> t = tokens(expr, error);
		if (t == null) return;
		if (t.isEmpty()) {
			error.accept("empty condition");
			return;
		}
		new Checker(t, options, error).run();
	}

	/**
	 * Evaluates a checked condition; {@code value} gives an option's or enum constant's integer value (bool: 1/0), C semantics
	 * (non-zero = true).
	 */
	static long eval(String expr, java.util.function.Function<String, Long> value) {
		return new Evaluator(tokens(expr, e -> {}), value).or();
	}

	private static final class Evaluator {
		final List<String> t;
		final java.util.function.Function<String, Long> value;
		int i;

		Evaluator(List<String> t, java.util.function.Function<String, Long> value) {
			this.t = t;
			this.value = value;
		}

		String peek() { return i < t.size() ? t.get(i) : null; }

		long or() {
			long v = and();
			while ("||".equals(peek())) { i++; long r = and(); v = v != 0 || r != 0 ? 1 : 0; }
			return v;
		}

		long and() {
			long v = cmp();
			while ("&&".equals(peek())) { i++; long r = cmp(); v = v != 0 && r != 0 ? 1 : 0; }
			return v;
		}

		long cmp() {
			long a = unary();
			String op = peek();
			if (op == null || !List.of("==", "!=", "<", "<=", ">", ">=").contains(op)) return a;
			i++;
			long b = unary();
			boolean r = switch (op) {
				case "==" -> a == b;
				case "!=" -> a != b;
				case "<" -> a < b;
				case "<=" -> a <= b;
				case ">" -> a > b;
				default -> a >= b;
			};
			return r ? 1 : 0;
		}

		long unary() {
			String s = t.get(i++);
			switch (s) {
				case "!" -> { return unary() == 0 ? 1 : 0; }
				case "(" -> {
					long v = or();
					i++; // ")"
					return v;
				}
				case "true" -> { return 1; }
				case "false" -> { return 0; }
				default -> {
					if (s.matches("\\d+")) return Long.parseLong(s);
					Long v = value.apply(s);
					return v == null ? 0 : v;
				}
			}
		}
	}

	/** NAME_VALUE of an enum option → its index, else null. */
	static String enumConstant(String tok, Map<String, NativeManifest.Option> options) {
		for (NativeManifest.Option o : options.values()) {
			if (!o.type().equals("enum") || !tok.startsWith(o.name() + "_")) continue;
			int i = o.values().indexOf(tok.substring(o.name().length() + 1).toLowerCase(java.util.Locale.ROOT));
			if (i >= 0 && tok.equals(tok.toUpperCase(java.util.Locale.ROOT))) return Integer.toString(i);
		}
		return null;
	}

	/** or := and ('||' and)*; and := cmp ('&&' cmp)*; cmp := unary (op unary)?; unary := '!' unary | '(' or ')' | atom. */
	private static final class Checker {
		final List<String> t;
		final Map<String, NativeManifest.Option> options;
		final Consumer<String> error;
		int i;
		boolean failed;

		Checker(List<String> t, Map<String, NativeManifest.Option> options, Consumer<String> error) {
			this.t = t;
			this.options = options;
			this.error = error;
		}

		void run() {
			or();
			if (!failed && i < t.size()) fail("unexpected \"" + t.get(i) + "\"");
		}

		void fail(String msg) {
			if (!failed) error.accept(msg);
			failed = true;
		}

		String peek() { return i < t.size() ? t.get(i) : null; }

		void or() {
			and();
			while (!failed && "||".equals(peek())) { i++; and(); }
		}

		void and() {
			cmp();
			while (!failed && "&&".equals(peek())) { i++; cmp(); }
		}

		void cmp() {
			unary();
			if (!failed && peek() != null && List.of("==", "!=", "<", "<=", ">", ">=").contains(peek())) { i++; unary(); }
		}

		void unary() {
			String s = peek();
			if (s == null) { fail("condition ends too early"); return; }
			i++;
			switch (s) {
				case "!" -> unary();
				case "(" -> {
					or();
					if (!failed && !")".equals(peek())) fail("missing \")\"");
					else i++;
				}
				default -> atom(s);
			}
		}

		void atom(String s) {
			if (s.matches("\\d+") || s.equals("true") || s.equals("false") || ENGINE.contains(s)) return;
			if (!s.matches("[A-Za-z_]\\w*")) { fail("unexpected \"" + s + "\""); return; }
			NativeManifest.Option o = options.get(s);
			if (o != null) {
				if (o.type().equals("float") || o.type().equals("color")) fail(o.type() + " option " + s + " cannot be used in a condition (use bool, int or enum)");
				return;
			}
			if (enumConstant(s, options) == null) fail("unknown option \"" + s + "\"");
		}
	}
}
