package dev.emrion.shaders.converter.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the Iris custom-uniform expression language, quirks of stareval kept: {@code && ||} share one priority
 * (so {@code a || b && c} is {@code (a || b) && c}), no unary {@code +}, and {@code 1e-5} is not a number.
 */
final class ExprParser {
	private ExprParser() {}

	/** Raw syntax tree. Operator names are the stareval names (multiply, add, and, negate, …). */
	sealed interface El permits Num, Id, Access, Call, Bin, Un {}

	record Num(String text) implements El {}

	record Id(String name) implements El {}

	record Access(El base, String field) implements El {}

	record Call(String fn, List<El> args) implements El {}

	record Bin(String op, El lhs, El rhs) implements El {}

	record Un(String op, El arg) implements El {}

	static final class ParseException extends RuntimeException {
		ParseException(String message, int index) { super(message + " (at " + index + ")"); }
	}

	private record UnaryTok(String op) {}

	private record PartialBin(El lhs, String op, int priority) {}

	private static final class Args { final List<El> list = new ArrayList<>(); }

	static El parse(String input) {
		List<Object> stack = new ArrayList<>();
		int n = input.length();
		int pos = 0;
		while (pos < n) {
			while (pos < n && Character.isWhitespace(input.charAt(pos))) pos++;
			if (pos >= n) break;
			char c = input.charAt(pos++);
			Object top = stack.isEmpty() ? null : stack.getLast();
			if (isIdStart(c)) {
				int s = pos - 1;
				while (pos < n && isIdPart(input.charAt(pos))) pos++;
				stack.add(new Id(input.substring(s, pos)));
			} else if (c == '.' && (top instanceof Id || top instanceof Access)) {
				while (pos < n && Character.isWhitespace(input.charAt(pos))) pos++;
				if (pos >= n) throw new ParseException("An expression can't end with '.'", pos);
				char start = input.charAt(pos);
				if (!isIdPart(start)) throw new ParseException("Expected a valid accessor but got '" + start + "'", pos);
				int s = pos;
				while (pos < n && isIdPart(input.charAt(pos))) pos++;
				stack.removeLast();
				stack.add(new Access((El) top, input.substring(s, pos)));
			} else if (isNumberStart(c)) {
				int s = pos - 1;
				while (pos < n && (isNumberStart(input.charAt(pos)) || isLetter(input.charAt(pos)))) pos++;
				stack.add(new Num(input.substring(s, pos)));
			} else if (c == '(') {
				stack.add(new Args());
			} else if (c == ',') {
				if (!(top instanceof El)) throw new ParseException("Expected an expression before a comma ','", pos);
				commaReduce(stack, pos);
			} else if (c == ')') {
				closing(stack, pos);
			} else if (top instanceof El) {
				// binary operator
				String op;
				int prio;
				switch (c) {
					case '*' -> { op = "multiply"; prio = 0; }
					case '/' -> { op = "divide"; prio = 0; }
					case '%' -> { op = "remainder"; prio = 0; }
					case '+' -> { op = "add"; prio = 1; }
					case '-' -> { op = "subtract"; prio = 1; }
					case '=' -> { pos = expect(input, pos, '='); op = "equals"; prio = 2; }
					case '!' -> { pos = expect(input, pos, '='); op = "notEquals"; prio = 2; }
					case '<' -> {
						if (pos < n && input.charAt(pos) == '=') { pos++; op = "lessThanOrEquals"; } else op = "lessThan";
						prio = 2;
					}
					case '>' -> {
						if (pos < n && input.charAt(pos) == '=') { pos++; op = "moreThanOrEquals"; } else op = "moreThan";
						prio = 2;
					}
					case '≠' -> { op = "notEquals"; prio = 2; }
					case '≤' -> { op = "lessThanOrEquals"; prio = 2; }
					case '≥' -> { op = "moreThanOrEquals"; prio = 2; }
					case '&' -> { pos = expect(input, pos, '&'); op = "and"; prio = 3; }
					case '|' -> { pos = expect(input, pos, '|'); op = "or"; prio = 3; }
					default -> throw new ParseException("Unexpected character '" + c + "'", pos);
				}
				El left = reducePop(stack, prio);
				stack.add(new PartialBin(left, op, prio));
			} else if (c == '-') {
				stack.add(new UnaryTok("negate"));
			} else if (c == '!') {
				stack.add(new UnaryTok("not"));
			} else {
				throw new ParseException("Unexpected character '" + c + "'", pos);
			}
		}
		return getFinal(stack, pos);
	}

	private static int expect(String input, int pos, char c) {
		if (pos >= input.length() || input.charAt(pos) != c) throw new ParseException("Expected '" + c + "'", pos);
		return pos + 1;
	}

	/** Pops an expression, folding pending operators whose priority is &lt;= the given one. */
	private static El reducePop(List<Object> stack, int priority) {
		El token = (El) stack.removeLast();
		while (!stack.isEmpty()) {
			Object x = stack.getLast();
			if (x instanceof UnaryTok u && -1 <= priority) {
				stack.removeLast();
				token = new Un(u.op(), token);
			} else if (x instanceof PartialBin p && p.priority() <= priority) {
				stack.removeLast();
				token = new Bin(p.op(), p.lhs(), token);
			} else break;
		}
		return token;
	}

	private static void commaReduce(List<Object> stack, int index) {
		El expr = reducePop(stack, Integer.MAX_VALUE);
		if (stack.isEmpty()) throw new ParseException("Expected an opening bracket '(' before ',' or ')'", index);
		if (!(stack.getLast() instanceof Args args)) throw new ParseException("Expected '(' or ',' before an expression followed by ',' or ')'", index);
		args.list.add(expr);
	}

	private static void closing(List<Object> stack, int index) {
		boolean exprOnTop = !stack.isEmpty() && stack.getLast() instanceof El;
		if (exprOnTop) commaReduce(stack, index);
		if (stack.isEmpty()) throw new ParseException("A closing bracket ')' can't be the first character of an expression", index);
		if (!(stack.removeLast() instanceof Args args)) throw new ParseException("Expected '(' or ',' before an expression followed by ',' or ')'", index);
		if (!stack.isEmpty() && stack.getLast() instanceof Id id) {
			stack.removeLast();
			stack.add(new Call(id.name(), List.copyOf(args.list)));
		} else if (args.list.isEmpty()) {
			throw new ParseException("Encountered empty brackets that aren't a call", index);
		} else if (args.list.size() > 1) {
			throw new ParseException("Encountered too many expressions in brackets that aren't a call", index);
		} else if (!exprOnTop) {
			throw new ParseException("Encountered a trailing comma in brackets that aren't a call", index);
		} else {
			stack.add(args.list.getFirst());
		}
	}

	private static El getFinal(List<Object> stack, int end) {
		if (stack.isEmpty()) throw new ParseException("The input seems to be empty", end);
		if (stack.getLast() instanceof El) {
			El result = reducePop(stack, Integer.MAX_VALUE);
			if (stack.isEmpty()) return result;
			if (stack.getLast() instanceof Args) throw new ParseException("Expected a closing bracket", end);
			throw new ParseException("Unexpected tokens left at the end of the expression", end);
		}
		Object top = stack.getLast();
		if (top instanceof Args) throw new ParseException("Expected a closing bracket", end);
		if (top instanceof UnaryTok || top instanceof PartialBin)
			throw new ParseException("Expected an identifier, constant or subexpression on the right side of the operator", end);
		throw new ParseException("Unexpected token at the end of the expression", end);
	}

	private static boolean isLetter(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }

	private static boolean isIdStart(char c) { return isLetter(c) || c == '_'; }

	private static boolean isIdPart(char c) { return isIdStart(c) || (c >= '0' && c <= '9'); }

	private static boolean isNumberStart(char c) { return (c >= '0' && c <= '9') || c == '.'; }
}
