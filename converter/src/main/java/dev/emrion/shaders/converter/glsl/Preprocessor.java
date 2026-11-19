package dev.emrion.shaders.converter.glsl;

import dev.emrion.shaders.converter.glsl.PpToken.Kind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C preprocessor with the observable behaviour of JCPP 1.4.14 as Iris drives it: #version/#extension hoisted, other directives
 * consumed, errors only logged. Output keeps source lines aligned (line {@code hoisted.size() + k} is source line {@code k}).
 */
public final class Preprocessor {
	/** PROPERTIES: no hoisting, unknown directives are silent (in .properties '#' starts a comment). */
	public enum Mode { GLSL, PROPERTIES }

	/** line is a line of the input text (1-based). */
	public record Message(int line, String text) {}

	/** hoistedLines: input line of each hoisted directive. */
	public record Result(String text, String textWithComments, List<String> hoisted, List<Integer> hoistedLines,
	                     List<Message> warnings) {}

	private record Macro(String name, List<String> params, List<PpToken> body) {
		boolean function() {
			return params != null;
		}
	}

	private static final class Cond {
		final boolean parentActive;
		boolean active, taken, sawElse;

		Cond(boolean parentActive, boolean active) {
			this.parentActive = parentActive;
			this.active = active;
			this.taken = active || !parentActive;
		}
	}

	private interface Source {
		PpToken next();

		void pushFront(List<PpToken> tokens);
	}

	private final Mode mode;
	private final Map<String, Macro> macros = new HashMap<>();
	private final Deque<Cond> conds = new ArrayDeque<>();
	private final List<Message> warnings = new ArrayList<>();
	private final List<String> hoisted = new ArrayList<>();
	private final List<Integer> hoistedLines = new ArrayList<>();
	private final StringBuilder plain = new StringBuilder();
	private final StringBuilder commented = new StringBuilder();
	private int plainLines, commentedLines;
	private int counter;
	private PpLexer lexer;
	/** Tokens all macro expansions may still produce (exponential macro chains); see {@link #EXPANSION_BUDGET}. */
	private long budget = EXPANSION_BUDGET;
	/** Nesting of argument pre-expansion; {@code I(I(I(...)))} thousands deep would overflow the Java stack. */
	private int depth;
	private boolean depthWarned;
	/** The last emitted token ended where an expansion began or ended: a following token must not merge with it. */
	private boolean boundary;
	private PpToken last;

	/** Per run: the most any stage of the 20 test packs needs is under 7,000 tokens; bounds time and output (tens of MB). */
	static final long EXPANSION_BUDGET = 5_000_000;
	static final int MAX_ARG_DEPTH = 256;
	/** Zero-width space around expansion results: {@link #emit} keeps the tokens on both sides apart ({@code 1.0-BIAS}). */
	private static final PpToken BOUNDARY = new PpToken(Kind.WS, "", 0, Set.of(), false);

	private Preprocessor(Mode mode, Map<String, String> predefined) {
		this.mode = mode;
		predefined.forEach((k, v) -> macros.put(k, new Macro(k, null, bodyTokens(PpLexer.lexAll(v), null))));
	}

	public static Result run(String source, Map<String, String> predefined, Mode mode) {
		Preprocessor pp = new Preprocessor(mode, predefined);
		pp.process(source.replace("\0", ""));
		return new Result(pp.plain.toString(), pp.commented.toString(), List.copyOf(pp.hoisted),
				List.copyOf(pp.hoistedLines), List.copyOf(pp.warnings));
	}

	private boolean active() {
		return conds.isEmpty() || conds.peek().active;
	}

	private void warn(int line, String text) {
		warnings.add(new Message(line, text));
	}

	// ---- main loop ---------------------------------------------------------------------------------------------------

	private void process(String source) {
		lexer = new PpLexer(source);
		Deque<PpToken> pending = new ArrayDeque<>();
		Source main = new Source() {
			public PpToken next() {
				PpToken t = pending.pollFirst();
				return t != null ? t : lexer.next();
			}

			public void pushFront(List<PpToken> tokens) {
				for (int k = tokens.size() - 1; k >= 0; k--) pending.addFirst(tokens.get(k));
			}
		};
		boolean lineStart = true;
		while (true) {
			PpToken t = main.next();
			if (t.kind() == Kind.EOF) break;
			if (t.kind() == Kind.NEWLINE) {
				padTo(t.line());
				lineStart = true;
				continue;
			}
			if (lineStart && t.src() && t.is("#")) {
				directive(main, t);
				lineStart = true;
				continue;
			}
			if (t.kind() != Kind.WS && t.kind() != Kind.COMMENT) lineStart = false;
			if (!active()) continue;
			if (t.kind() == Kind.IDENT && expand(t, main)) continue;
			emit(t);
		}
		if (!conds.isEmpty()) warn(lexer.line(), "Unterminated #if");
	}

	private void emit(PpToken t) {
		PpToken prev = last;
		if (!t.text().isEmpty()) last = t;
		switch (t.kind()) {
			case COMMENT -> {
				commented.append(t.text());
				int nl = count(t.text());
				commentedLines += nl;
				if (nl == 0) plain.append(' ');
				else plain.append("\n".repeat(nl));
				plainLines += nl;
			}
			case NEWLINE -> {
				plain.append(' ');
				commented.append(' ');
			}
			case PLACEMARKER -> {
			}
			default -> {
				if (t.text().isEmpty()) { // BOUNDARY
					boundary = true;
					return;
				}
				if (boundary && prev != null && merges(prev, t)) {
					plain.append(' ');
					commented.append(' ');
				}
				boundary = false;
				plain.append(t.text());
				commented.append(t.text());
			}
		}
	}

	/** Written next to each other, GLSL would lex the two tokens differently (as one word or one longer operator). */
	static boolean merges(PpToken a, PpToken b) {
		char x = a.text().charAt(a.text().length() - 1), y = b.text().charAt(0);
		if (a.kind() == Kind.IDENT || a.kind() == Kind.NUMBER) return PpLexer.isIdentPart(y) || a.kind() == Kind.NUMBER && y == '.';
		if (x == '.') return Character.isDigit(y);
		return a.kind() == Kind.PUNCT && b.kind() == Kind.PUNCT && Set.of("++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "<=", ">=", "==",
				"!=", "&&", "||", "^^", "//", "/*", "*/").contains("" + x + y);
	}

	private static int count(String s) {
		int c = 0;
		for (int k = 0; k < s.length(); k++) if (s.charAt(k) == '\n') c++;
		return c;
	}

	/** Keeps output on the source line: after source line {@code line} ends, both outputs have {@code line} newlines. */
	private void padTo(int line) {
		while (plainLines < line) {
			plain.append('\n');
			plainLines++;
		}
		while (commentedLines < line) {
			commented.append('\n');
			commentedLines++;
		}
	}

	// ---- directives --------------------------------------------------------------------------------------------------

	private void directive(Source src, PpToken hash) {
		List<PpToken> line = new ArrayList<>();
		PpToken t;
		while ((t = src.next()).kind() != Kind.NEWLINE && t.kind() != Kind.EOF) line.add(t);
		int lineNo = hash.line();
		int k = skipSpace(line, 0);
		String name = k < line.size() && line.get(k).kind() == Kind.IDENT ? line.get(k).text() : null;
		List<PpToken> rest = line.subList(Math.min(line.size(), k + 1), line.size());
		boolean wasActive = active();
		if (name != null) {
			switch (name) {
				case "if" -> { pushIf(wasActive && evalIf(rest, lineNo)); finishLine(t); return; }
				case "ifdef", "ifndef" -> {
					pushIf(wasActive && (macros.containsKey(firstIdent(rest)) == name.equals("ifdef")));
					finishLine(t);
					return;
				}
				case "elif" -> { elif(rest, lineNo); finishLine(t); return; }
				case "else" -> { orElse(lineNo); finishLine(t); return; }
				case "endif" -> {
					if (conds.isEmpty()) warn(lineNo, "#endif without #if");
					else conds.pop();
					finishLine(t);
					return;
				}
				default -> {
				}
			}
		}
		if (wasActive && name != null) {
			switch (name) {
				case "define" -> define(rest, lineNo);
				case "undef" -> macros.remove(firstIdent(rest));
				case "version", "extension" -> {
					// Iris finds these by replacing the literal text "#version"/"#extension"; "# version" is unknown to JCPP.
					if (mode == Mode.GLSL && k == 0) hoist(name, rest, lineNo);
					else unknown(name, lineNo);
				}
				case "pragma", "line" -> {
				}
				case "error", "warning" -> warn(lineNo, "#" + name + " " + join(rest, true).trim());
				default -> unknown(name, lineNo);
			}
		} else if (wasActive && k < line.size() && mode == Mode.GLSL) {
			warn(lineNo, "Preprocessor directive not a word");
		}
		finishLine(t);
	}

	private void finishLine(PpToken newline) {
		if (newline.kind() == Kind.NEWLINE) padTo(newline.line());
	}

	private void unknown(String name, int line) {
		if (mode == Mode.GLSL) warn(line, "Unknown preprocessor directive #" + name);
	}

	private void hoist(String name, List<PpToken> rest, int line) {
		hoisted.add("#" + name + " " + join(rest, false).trim());
		hoistedLines.add(line);
	}

	private void pushIf(boolean value) {
		conds.push(new Cond(active(), value));
	}

	private void elif(List<PpToken> rest, int line) {
		Cond c = conds.peek();
		if (c == null) {
			warn(line, "#elif without #if");
			return;
		}
		if (c.sawElse) warn(line, "#elif after #else");
		if (c.parentActive && !c.taken) {
			c.active = evalIf(rest, line);
			c.taken = c.active;
		} else {
			c.active = false;
		}
	}

	private void orElse(int line) {
		Cond c = conds.peek();
		if (c == null) {
			warn(line, "#else without #if");
			return;
		}
		c.active = c.parentActive && !c.taken;
		c.taken = true;
		c.sawElse = true;
	}

	private boolean evalIf(List<PpToken> rest, int line) {
		List<PpToken> resolved = new ArrayList<>();
		for (int k = 0; k < rest.size(); k++) {
			PpToken t = rest.get(k);
			if (t.kind() == Kind.IDENT && t.text().equals("defined")) {
				int j = skipSpace(rest, k + 1);
				boolean paren = j < rest.size() && rest.get(j).is("(");
				if (paren) j = skipSpace(rest, j + 1);
				String id = j < rest.size() && rest.get(j).kind() == Kind.IDENT ? rest.get(j).text() : null;
				if (id == null) {
					warn(line, "Bad 'defined' in #if");
					return false;
				}
				if (paren) {
					j = skipSpace(rest, j + 1);
					if (j >= rest.size() || !rest.get(j).is(")")) {
						warn(line, "Missing ')' after 'defined' in #if");
						return false;
					}
				}
				resolved.add(new PpToken(Kind.NUMBER, macros.containsKey(id) ? "1" : "0", line, Set.of(), false));
				k = j;
			} else {
				resolved.add(t);
			}
		}
		try {
			return PpExpression.evaluate(expandList(resolved)) != 0;
		} catch (PpExpression.EvalException e) {
			warn(line, e.getMessage());
			return false;
		}
	}

	private void define(List<PpToken> rest, int line) {
		int k = skipSpace(rest, 0);
		if (k >= rest.size() || rest.get(k).kind() != Kind.IDENT) {
			warn(line, "Bad #define");
			return;
		}
		String name = rest.get(k).text();
		k++;
		List<String> params = null;
		if (k < rest.size() && rest.get(k).is("(")) {
			params = new ArrayList<>();
			k = skipSpace(rest, k + 1);
			while (k < rest.size() && !rest.get(k).is(")")) {
				PpToken p = rest.get(k);
				if (p.kind() == Kind.IDENT) params.add(p.text());
				else if (!p.is(",")) {
					// shortcut: no variadic macros (GLSL has none), add if a pack needs __VA_ARGS__
					warn(line, "Unsupported macro parameter '" + p.text() + "' in #define " + name);
					return;
				}
				k = skipSpace(rest, k + 1);
			}
			k++;
		}
		macros.put(name, new Macro(name, params, bodyTokens(rest.subList(Math.min(k, rest.size()), rest.size()), params)));
	}

	/** Drops comments and surrounding space; marks ## as PASTE and # before a parameter as STRINGIFY. */
	private static List<PpToken> bodyTokens(List<PpToken> raw, List<String> params) {
		List<PpToken> body = new ArrayList<>();
		for (PpToken t : raw) {
			if (t.kind() == Kind.COMMENT) t = new PpToken(Kind.WS, " ", t.line(), Set.of(), false);
			if (t.kind() == Kind.WS && (body.isEmpty() || body.getLast().kind() == Kind.WS)) continue;
			if (t.is("##")) t = new PpToken(Kind.PASTE, "##", t.line(), Set.of(), false);
			body.add(t);
		}
		while (!body.isEmpty() && body.getLast().kind() == Kind.WS) body.removeLast();
		if (params != null) {
			for (int k = 0; k < body.size(); k++) {
				if (body.get(k).is("#")) {
					int j = skipSpace(body, k + 1);
					if (j < body.size() && body.get(j).kind() == Kind.IDENT && params.contains(body.get(j).text())) {
						body.set(k, new PpToken(Kind.STRINGIFY, "#", body.get(k).line(), Set.of(), false));
					}
				}
			}
		}
		return List.copyOf(body);
	}

	private static int skipSpace(List<PpToken> l, int k) {
		while (k < l.size() && (l.get(k).kind() == Kind.WS || l.get(k).kind() == Kind.COMMENT)) k++;
		return k;
	}

	private static String firstIdent(List<PpToken> rest) {
		int k = skipSpace(rest, 0);
		return k < rest.size() ? rest.get(k).text() : "";
	}

	private static String join(List<PpToken> toks, boolean withComments) {
		StringBuilder b = new StringBuilder();
		for (PpToken t : toks) if (withComments || t.kind() != Kind.COMMENT) b.append(t.text());
		return b.toString();
	}

	// ---- macro expansion (Prosser's hide-set algorithm) ---------------------------------------------------------------

	private List<PpToken> expandList(List<PpToken> tokens) {
		if (depth >= MAX_ARG_DEPTH) {
			if (!depthWarned) warn(lexer == null ? 0 : lexer.line(), "Macro arguments nested deeper than " + MAX_ARG_DEPTH + " levels; the rest stays unexpanded");
			depthWarned = true;
			return tokens;
		}
		depth++;
		try {
			return expandListNested(tokens);
		} finally {
			depth--;
		}
	}

	private List<PpToken> expandListNested(List<PpToken> tokens) {
		Deque<PpToken> q = new ArrayDeque<>(tokens);
		Source src = new Source() {
			public PpToken next() {
				PpToken t = q.pollFirst();
				return t != null ? t : PpToken.EOF_TOKEN;
			}

			public void pushFront(List<PpToken> ts) {
				for (int k = ts.size() - 1; k >= 0; k--) q.addFirst(ts.get(k));
			}
		};
		List<PpToken> out = new ArrayList<>();
		for (PpToken t; (t = src.next()).kind() != Kind.EOF; ) {
			if (t.kind() == Kind.IDENT && expand(t, src)) continue;
			out.add(t);
		}
		return out;
	}

	/** If t names a macro that may expand here, pushes the replacement onto src and returns true. */
	private boolean expand(PpToken t, Source src) {
		String name = t.text();
		switch (name) {
			case "__LINE__" -> {
				src.pushFront(List.of(new PpToken(Kind.NUMBER, Integer.toString(t.src() ? t.line() : lexer.line()), t.line(), t.hide(), false)));
				return true;
			}
			case "__FILE__" -> {
				src.pushFront(List.of(new PpToken(Kind.STRING, "\"<no file>\"", t.line(), t.hide(), false)));
				return true;
			}
			case "__COUNTER__" -> {
				src.pushFront(List.of(new PpToken(Kind.NUMBER, Integer.toString(counter++), t.line(), t.hide(), false)));
				return true;
			}
			default -> {
			}
		}
		Macro m = macros.get(name);
		if (m == null || t.hide().contains(name)) return false;
		if (budget < 0) return false; // exhausted: the rest stays unexpanded (and fails to compile) instead of hanging
		if (!m.function()) {
			src.pushFront(substitute(m, null, union(t.hide(), name)));
			return true;
		}
		List<PpToken> skipped = new ArrayList<>();
		PpToken u = src.next();
		while (u.isSpace()) {
			skipped.add(u);
			u = src.next();
		}
		if (!u.is("(")) {
			List<PpToken> back = new ArrayList<>(skipped);
			if (u.kind() != Kind.EOF) back.add(u);
			src.pushFront(back);
			return false;
		}
		List<List<PpToken>> args = new ArrayList<>();
		List<PpToken> cur = new ArrayList<>();
		int depth = 0;
		PpToken close;
		while (true) {
			PpToken a = src.next();
			if (a.kind() == Kind.EOF) {
				warn(t.line(), "Unterminated call of macro " + name);
				return false;
			}
			if (a.is("(")) depth++;
			else if (a.is(")")) {
				if (depth == 0) {
					close = a;
					break;
				}
				depth--;
			} else if (a.is(",") && depth == 0) {
				args.add(cur);
				cur = new ArrayList<>();
				continue;
			}
			cur.add(a);
		}
		args.add(cur);
		if (m.params().isEmpty() && args.size() == 1 && args.getFirst().stream().allMatch(PpToken::isSpace)) args.clear();
		if (args.size() != m.params().size()) {
			warn(t.line(), "Macro " + name + " expects " + m.params().size() + " arguments, got " + args.size());
			return false;
		}
		Set<String> hs = new HashSet<>(t.hide());
		hs.retainAll(close.hide());
		hs.add(name);
		src.pushFront(substitute(m, args, Set.copyOf(hs)));
		return true;
	}

	private static Set<String> union(Set<String> s, String name) {
		if (s.isEmpty()) return Set.of(name);
		Set<String> r = new HashSet<>(s);
		r.add(name);
		return Set.copyOf(r);
	}

	private List<PpToken> substitute(Macro m, List<List<PpToken>> args, Set<String> hs) {
		List<PpToken> body = m.body();
		List<PpToken> out = new ArrayList<>();
		for (int k = 0; k < body.size(); k++) {
			PpToken b = body.get(k);
			if (b.kind() == Kind.STRINGIFY) {
				k = skipSpace(body, k + 1);
				out.add(new PpToken(Kind.STRING, stringify(args.get(m.params().indexOf(body.get(k).text()))), b.line(), Set.of(), false));
				continue;
			}
			int p = args == null || b.kind() != Kind.IDENT ? -1 : m.params().indexOf(b.text());
			if (p < 0) {
				out.add(b);
				continue;
			}
			boolean pasted = adjacentPaste(body, k);
			List<PpToken> arg = trim(args.get(p));
			if (pasted) {
				if (arg.isEmpty()) out.add(new PpToken(Kind.PLACEMARKER, "", b.line(), Set.of(), false));
				else out.addAll(arg);
			} else {
				out.add(BOUNDARY);
				out.addAll(expandList(arg));
				out.add(BOUNDARY);
			}
		}
		List<PpToken> pasted = paste(out);
		List<PpToken> result = new ArrayList<>(pasted.size() + 2);
		result.add(BOUNDARY);
		for (PpToken t : pasted) {
			if (t.kind() == Kind.PLACEMARKER) continue;
			result.add(t.withHide(t.hide().isEmpty() ? hs : unionAll(t.hide(), hs)));
		}
		result.add(BOUNDARY);
		budget -= result.size();
		if (budget < 0 && budget + result.size() >= 0)
			warn(lexer == null ? 0 : lexer.line(), "Macro expansion exceeds " + EXPANSION_BUDGET + " tokens (recursive macro chain?); the rest stays unexpanded");
		return result;
	}

	private static Set<String> unionAll(Set<String> a, Set<String> b) {
		Set<String> r = new HashSet<>(a);
		r.addAll(b);
		return Set.copyOf(r);
	}

	private static boolean adjacentPaste(List<PpToken> body, int k) {
		int j = k - 1;
		while (j >= 0 && body.get(j).kind() == Kind.WS) j--;
		if (j >= 0 && body.get(j).kind() == Kind.PASTE) return true;
		j = skipSpace(body, k + 1);
		return j < body.size() && body.get(j).kind() == Kind.PASTE;
	}

	private static List<PpToken> paste(List<PpToken> in) {
		List<PpToken> out = new ArrayList<>();
		for (int k = 0; k < in.size(); k++) {
			PpToken t = in.get(k);
			if (t.kind() != Kind.PASTE) {
				out.add(t);
				continue;
			}
			while (!out.isEmpty() && out.getLast().kind() == Kind.WS) out.removeLast();
			int j = skipSpace(in, k + 1);
			String left = out.isEmpty() ? "" : out.removeLast().text();
			String right = j < in.size() ? in.get(j).text() : "";
			out.addAll(left.isEmpty() && right.isEmpty()
					? List.of(new PpToken(Kind.PLACEMARKER, "", t.line(), Set.of(), false))
					: PpLexer.lexAll(left + right));
			k = j;
		}
		return out;
	}

	private static List<PpToken> trim(List<PpToken> arg) {
		int a = 0, b = arg.size();
		while (a < b && arg.get(a).isSpace()) a++;
		while (b > a && arg.get(b - 1).isSpace()) b--;
		return arg.subList(a, b);
	}

	private static String stringify(List<PpToken> arg) {
		StringBuilder b = new StringBuilder("\"");
		boolean space = false;
		for (PpToken t : trim(arg)) {
			if (t.isSpace()) {
				space = true;
				continue;
			}
			if (space) b.append(' ');
			space = false;
			String s = t.text();
			if (t.kind() == Kind.STRING) s = s.replace("\\", "\\\\").replace("\"", "\\\"");
			b.append(s);
		}
		return b.append('"').toString();
	}
}
