package dev.emrion.shaders.converter.glsl;

import dev.emrion.shaders.converter.glsl.GlslLexer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Light structural parse into global-scope items; expressions and statements are not parsed, rewriters work on token ranges. */
public final class GlslParser {
	private GlslParser() {}

	public enum ItemKind { DIRECTIVE, FUNCTION, PROTOTYPE, DECL, BLOCK, QUALIFIER_ONLY, OTHER, EMPTY }

	/** Token index ranges are half-open; -1 = absent. */
	public record Declarator(int nameIdx, String name, int arrStart, int arrEnd, int initStart, int initEnd) {
		public boolean hasArray() { return arrStart >= 0; }

		public boolean hasInit() { return initStart >= 0; }
	}

	public static final class Item {
		public ItemKind kind;
		public final int start;
		public final int end;
		public final List<String> quals = new ArrayList<>();
		/** layout(...) entries as {key, value-or-null}; null when there is no layout qualifier. */
		public List<String[]> layout;
		public int typeStart = -1, typeEnd = -1;
		public final List<Declarator> decls = new ArrayList<>();
		/** Function name, prototype name or block name. */
		public String name;
		public int nameIdx = -1;
		/** FUNCTION: '{' and matching '}' of the body. BLOCK: braces of the member list. */
		public int bodyStart = -1, bodyEnd = -1;

		Item(ItemKind kind, int start, int end) {
			this.kind = kind;
			this.start = start;
			this.end = end;
		}

		public boolean has(String qual) { return quals.contains(qual); }

		/** uniform | in | out | attribute | varying | buffer | shared | const | null. */
		public String storage() {
			for (String q : quals) if (STORAGE.contains(q)) return q;
			return null;
		}
	}

	public static final Set<String> STORAGE = Set.of("uniform", "in", "out", "inout", "attribute", "varying", "buffer", "shared", "const");
	public static final Set<String> QUALIFIERS = Set.of("const", "uniform", "in", "out", "inout", "attribute", "varying", "buffer",
			"shared", "flat", "smooth", "noperspective", "centroid", "patch", "invariant", "precise", "highp", "mediump", "lowp",
			"coherent", "volatile", "restrict", "readonly", "writeonly");

	public static List<Item> parse(List<Token> t) {
		List<Item> items = new ArrayList<>();
		int n = t.size(), i = 0;
		while (i < n) {
			if (t.get(i).kind == GlslLexer.Kind.DIRECTIVE) {
				items.add(new Item(ItemKind.DIRECTIVE, i, i + 1));
				i++;
				continue;
			}
			int s = i, depth = 0;
			boolean function = false;
			int end = n;
			for (; i < n; i++) {
				String x = t.get(i).text;
				if (x.equals("(") || x.equals("[")) depth++;
				else if (x.equals(")") || x.equals("]")) depth--;
				else if (x.equals("{")) {
					if (depth == 0 && i > s && t.get(i - 1).is(")")) {
						end = match(t, i) + 1;
						function = true;
						break;
					}
					depth++;
				} else if (x.equals("}")) depth--;
				else if (x.equals(";") && depth == 0) { end = i + 1; break; }
			}
			i = end;
			items.add(function ? parseFunction(t, s, end) : parseDecl(t, s, end));
		}
		return items;
	}

	/** Index of the bracket that closes the one at {@code open} (or the last token if unbalanced). */
	public static int match(List<Token> t, int open) {
		int depth = 0;
		for (int i = open; i < t.size(); i++) {
			String x = t.get(i).text;
			if (x.equals("(") || x.equals("[") || x.equals("{")) depth++;
			else if (x.equals(")") || x.equals("]") || x.equals("}")) {
				if (--depth == 0) return i;
			}
		}
		return t.size() - 1;
	}

	private static int qualifiers(List<Token> t, int i, int end, Item it) {
		while (i < end) {
			Token k = t.get(i);
			if (k.is("layout") && i + 1 < end && t.get(i + 1).is("(")) {
				int close = match(t, i + 1);
				if (it.layout == null) it.layout = new ArrayList<>();
				int a = i + 2;
				while (a < close) {
					int b = a;
					int depth = 0;
					while (b < close && !(depth == 0 && t.get(b).is(","))) {
						if (t.get(b).is("(")) depth++;
						else if (t.get(b).is(")")) depth--;
						b++;
					}
					String key = t.get(a).text;
					String val = null;
					if (a + 1 < b && t.get(a + 1).is("=")) val = text(t, a + 2, b);
					it.layout.add(new String[] {key, val});
					a = b + 1;
				}
				i = close + 1;
			} else if (QUALIFIERS.contains(k.text)
					|| (k.is("sample") && i + 1 < end && Set.of("in", "out", "centroid", "flat", "smooth", "noperspective").contains(t.get(i + 1).text))) {
				it.quals.add(k.text);
				i++;
			} else break;
		}
		return i;
	}

	private static Item parseFunction(List<Token> t, int s, int e) {
		Item it = new Item(ItemKind.FUNCTION, s, e);
		int i = qualifiers(t, s, e, it);
		it.typeStart = i;
		int paren = i;
		while (paren < e && !t.get(paren).is("(")) paren++;
		it.typeEnd = paren - 1;
		it.nameIdx = paren - 1;
		it.name = t.get(paren - 1).text;
		it.bodyStart = match(t, paren) + 1;
		it.bodyEnd = e - 1;
		return it;
	}

	private static Item parseDecl(List<Token> t, int s, int e) {
		Token first = t.get(s);
		if (e - s == 1 && first.is(";")) return new Item(ItemKind.EMPTY, s, e);
		if (first.is("precision")) return new Item(ItemKind.OTHER, s, e);
		Item it = new Item(ItemKind.DECL, s, e);
		int last = t.get(e - 1).is(";") ? e - 1 : e;
		int i = qualifiers(t, s, last, it);
		if (i >= last) {
			it.kind = ItemKind.QUALIFIER_ONLY;
			return it;
		}
		if (t.get(i).is("struct")) {
			it.typeStart = i;
			int brace = i;
			while (brace < last && !t.get(brace).is("{")) brace++;
			i = match(t, brace) + 1;
			it.typeEnd = i;
		} else if (t.get(i).ident() && i + 1 < last && t.get(i + 1).is("{")) {
			it.kind = ItemKind.BLOCK;
			it.name = t.get(i).text;
			it.nameIdx = i;
			it.bodyStart = i + 1;
			it.bodyEnd = match(t, i + 1);
			i = it.bodyEnd + 1;
		} else {
			it.typeStart = i++;
			while (i < last && t.get(i).is("[")) i = match(t, i) + 1;
			it.typeEnd = i;
			if (i + 1 < last && t.get(i).ident() && t.get(i + 1).is("(")) {
				it.kind = ItemKind.PROTOTYPE;
				it.name = t.get(i).text;
				it.nameIdx = i;
				return it;
			}
		}
		while (i < last) {
			if (!t.get(i).ident()) { it.kind = ItemKind.OTHER; return it; }
			int nameIdx = i++;
			int as = -1, ae = -1, is = -1, ie = -1;
			if (i < last && t.get(i).is("[")) {
				as = i;
				while (i < last && t.get(i).is("[")) i = match(t, i) + 1;
				ae = i;
			}
			if (i < last && t.get(i).is("=")) {
				is = ++i;
				int depth = 0;
				while (i < last) {
					String x = t.get(i).text;
					if (x.equals("(") || x.equals("[") || x.equals("{")) depth++;
					else if (x.equals(")") || x.equals("]") || x.equals("}")) depth--;
					else if (x.equals(",") && depth == 0) break;
					i++;
				}
				ie = i;
			}
			it.decls.add(new Declarator(nameIdx, t.get(nameIdx).text, as, ae, is, ie));
			if (i < last && t.get(i).is(",")) i++;
			else if (i < last) { it.kind = ItemKind.OTHER; return it; }
		}
		return it;
	}

	/** Tokens [from, to) joined with single spaces where needed (no original whitespace). */
	public static String text(List<Token> t, int from, int to) {
		StringBuilder sb = new StringBuilder();
		for (int i = from; i < to; i++) {
			Token k = t.get(i);
			if (!sb.isEmpty() && needsSpace(sb.charAt(sb.length() - 1), k.text.charAt(0))) sb.append(' ');
			sb.append(k.text);
		}
		return sb.toString();
	}

	private static boolean needsSpace(char a, char b) {
		return (Character.isLetterOrDigit(a) || a == '_') && (Character.isLetterOrDigit(b) || b == '_');
	}
}
