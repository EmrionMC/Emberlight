package dev.emrion.shaders.converter.glsl;

import dev.emrion.shaders.converter.glsl.GlslLexer.Token;
import dev.emrion.shaders.converter.glsl.GlslParser.Item;
import dev.emrion.shaders.converter.glsl.GlslParser.ItemKind;
import dev.emrion.shaders.converter.pack.ResolvedPack;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.rt.RtPatchTable;
import dev.emrion.shaders.format.Manifest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * RT patch points (RAYTRACING.md §3.3): replaces the bodies of the patch table's functions in the preprocessed stages, so a match
 * means "live in this stage with these settings". All or nothing per effect: if any dimension is matched only partly or a needed
 * pack symbol is missing, the effect is dropped and the pack stays exactly as without it (the reason goes to the manifest).
 * <p>Line numbers stay valid: helpers and replacement bodies are emitted on one line, and a replaced body keeps its newlines.
 * The transpiler then sees ordinary GLSL plus {@code rayQueryEXT}, which makes it emit {@code #version 460} for that stage only.
 */
public final class RtPatcher {
	private RtPatcher() {}

	public record Result(ResolvedPack pack, Manifest.Rt rt, List<Diagnostic> diagnostics) {}

	private record Match(Item function, RtPatchTable.Patch patch) {}

	private record StageMatch(int program, int stage, List<Match> matches) {}

	public static Result apply(ResolvedPack pack, Set<String> request) throws java.io.IOException {
		for (String t : RtPatchTable.TABLES) {
			RtPatchTable candidate = RtPatchTable.load(t);
			boolean all = true;
			for (String f : candidate.detect()) all &= pack.source().read("shaders/" + f).isPresent();
			if (all) return apply(pack, request, candidate);
		}
		return apply(pack, request, null);
	}

	/** @param table null = no table matches this pack */
	public static Result apply(ResolvedPack pack, Set<String> request, RtPatchTable table) {
		Map<String, Map<String, String>> effects = new TreeMap<>();
		for (String r : request) {
			int c = r.indexOf(':');
			var opts = effects.computeIfAbsent(c < 0 ? r : r.substring(0, c), k -> new LinkedHashMap<>());
			if (c >= 0) {
				String o = r.substring(c + 1);
				int eq = o.indexOf('=');
				opts.put(eq < 0 ? o : o.substring(0, eq), eq < 0 ? "1" : o.substring(eq + 1));
			}
		}
		List<String> applied = new ArrayList<>();
		Map<String, String> unavailable = new TreeMap<>();
		Map<String, List<String>> patched = new TreeMap<>();
		List<Diagnostic> diagnostics = new ArrayList<>();
		for (var e : effects.entrySet()) {
			String name = e.getKey();
			RtPatchTable.Effect effect = table == null ? null : table.effects().get(name);
			String reason = table == null ? "no RT patch table for this pack" : effect == null ? "table " + table.id() + " has no " + name + " patches" : null;
			if (reason == null) {
				List<String> where = new ArrayList<>();
				try {
					pack = patchEffect(pack, name, effect, e.getValue(), where);
					applied.addAll(request.stream().filter(x -> x.equals(name) || x.startsWith(name + ":")).sorted().toList());
					patched.put(name, where);
					diagnostics.add(new Diagnostic(Diagnostic.Severity.INFO, null, 0, null, null,
							"RT " + name + " applied (" + table.id() + "): " + where.size() + " stages patched: " + String.join(", ", where)));
				} catch (Unavailable u) {
					reason = u.getMessage();
				}
			}
			if (reason != null) {
				unavailable.put(name, reason);
				diagnostics.add(Diagnostic.warning(null, 0, "RT " + name + " unavailable: " + reason));
			}
		}
		return new Result(pack, new Manifest.Rt(applied, unavailable, patched), diagnostics);
	}

	/** @throws Unavailable with the reason the effect cannot be applied */
	private static ResolvedPack patchEffect(ResolvedPack pack, String name, RtPatchTable.Effect effect, Map<String, String> opts, List<String> where) {
		for (String o : opts.keySet())
			if (!effect.options().containsKey(o)) throw new Unavailable("unknown option '" + o + "'");
		Map<String, List<ResolvedPack.ProgramSource>> dims = new TreeMap<>(pack.dimensions());
		List<String> misses = new ArrayList<>();
		boolean any = false;
		for (var d : dims.entrySet()) {
			List<ResolvedPack.ProgramSource> programs = new ArrayList<>(d.getValue());
			List<StageMatch> found = new ArrayList<>();
			for (int p = 0; p < programs.size(); p++) {
				if (!programs.get(p).enabled()) continue;
				var stages = programs.get(p).stages();
				for (int s = 0; s < stages.size(); s++) {
					List<Token> t = GlslLexer.lex(stages.get(s).text());
					List<Match> m = matches(GlslParser.parse(t), t, effect);
					if (!m.isEmpty()) found.add(new StageMatch(p, s, m));
				}
			}
			if (found.isEmpty()) {
				misses.add(d.getKey());
				continue;
			}
			for (String req : effect.requires()) {
				boolean hit = found.stream().anyMatch(f -> programs.get(f.program()).name().equals(req));
				if (!hit) throw new Unavailable(d.getKey() + ": " + req + " has no live patch point (the pack's version or settings differ)");
			}
			String skip = skipIds(programs, effect);
			for (StageMatch f : found) {
				var src = programs.get(f.program()).stages().get(f.stage());
				String label = d.getKey() + "/" + src.program() + "." + src.stage().extension;
				String text = patchStage(src.text(), name, effect, opts, skip, label);
				var stages = new ArrayList<>(programs.get(f.program()).stages());
				stages.set(f.stage(), new PreprocessedStage(src.program(), src.group(), src.stage(), src.sourcePath(), text,
						src.lineMap(), src.directives()));
				var old = programs.get(f.program());
				programs.set(f.program(), new ResolvedPack.ProgramSource(old.name(), old.enabled(), List.copyOf(stages)));
				where.add(label);
			}
			d.setValue(List.copyOf(programs));
			any = true;
		}
		if (!any) throw new Unavailable("no live patch point in " + String.join(", ", misses) + " (the effect is off in the pack settings?)");
		return new ResolvedPack(pack.source(), pack.options(), pack.properties(), pack.idMaps(), pack.dimensionMap(), dims);
	}

	static List<Match> matches(List<Item> items, List<Token> t, RtPatchTable.Effect effect) {
		List<Match> out = new ArrayList<>();
		for (Item it : items) {
			if (it.kind != ItemKind.FUNCTION) continue;
			String sig = signature(t, it);
			for (var p : effect.patches()) if (normalise(p.function()).equals(sig)) out.add(new Match(it, p));
		}
		return out;
	}

	/** "vec3 name(vec3,float)": return type, name, parameter types without names and without the default "in". */
	static String signature(List<Token> t, Item fn) {
		int paren = fn.nameIdx + 1, close = GlslParser.match(t, paren);
		List<String> params = new ArrayList<>();
		List<String> cur = new ArrayList<>();
		int depth = 0;
		for (int i = paren + 1; i <= close; i++) {
			String x = t.get(i).text;
			if (depth == 0 && (x.equals(",") || i == close)) {
				while (cur.size() > 1 && cur.getLast().equals("]")) { // array suffix of the name
					while (!cur.removeLast().equals("[")) { }
				}
				if (cur.size() > 1) cur.removeLast();
				cur.remove("in");
				if (!cur.isEmpty() && !(cur.size() == 1 && cur.getFirst().equals("void"))) params.add(String.join(" ", cur));
				cur = new ArrayList<>();
				continue;
			}
			if (x.equals("(") || x.equals("[")) depth++;
			if (x.equals(")") || x.equals("]")) depth--;
			cur.add(x);
		}
		return GlslParser.text(t, fn.typeStart, fn.nameIdx) + " " + fn.name + "(" + String.join(",", params) + ")";
	}

	static String normalise(String sig) {
		return sig.trim().replaceAll("\\s+", " ").replaceAll(" ?([(),]) ?", "$1");
	}

	/** The foliage skip list, only when the dimension's shadow pass really discards those ids (an option of the pack). */
	private static String skipIds(List<ResolvedPack.ProgramSource> programs, RtPatchTable.Effect effect) {
		if (effect.skipIds() == null || effect.skipIds().isEmpty()) return "false";
		for (var p : programs) {
			if (!p.name().equals("shadow") || !p.enabled()) continue;
			for (var s : p.stages()) {
				if (s.stage() != StageType.FRAGMENT) continue;
				if (GlslLexer.lex(s.text()).stream().anyMatch(k -> k.is(effect.skipIdsWhen()))) {
					return String.join(" || ", effect.skipIds().stream().map(id -> "id == " + id).toList());
				}
			}
		}
		return "false";
	}

	private static final class Unavailable extends RuntimeException {
		Unavailable(String reason) {
			super(reason, null, false, false);
		}
	}

	private static String patchStage(String text, String effectName, RtPatchTable.Effect effect, Map<String, String> opts,
	                                 String skip, String label) {
		List<Token> t = GlslLexer.lex(text);
		List<Item> items = GlslParser.parse(t);
		List<Match> ms = matches(items, t, effect);
		Item first = ms.getFirst().function();
		Set<String> before = new HashSet<>(), anywhere = new HashSet<>();
		for (Item it : items) {
			Set<String> names = new HashSet<>();
			if ((it.kind == ItemKind.FUNCTION || it.kind == ItemKind.PROTOTYPE) && it.name != null) names.add(it.name);
			if (it.kind == ItemKind.DECL) it.decls.forEach(d -> names.add(d.name()));
			anywhere.addAll(names);
			if (it.start < first.start) before.addAll(names);
		}
		for (String n : effect.needs())
			if (!before.contains(n)) throw new Unavailable(label + ": '" + n + "' is not declared before " + first.name);
		StringBuilder helper = new StringBuilder();
		for (var u : effect.uniforms().entrySet()) {
			if (before.contains(u.getKey())) continue;
			if (anywhere.contains(u.getKey())) throw new Unavailable(label + ": '" + u.getKey() + "' is declared after " + first.name);
			helper.append("uniform ").append(u.getValue()).append(' ').append(u.getKey()).append(";\n");
		}
		for (var o : effect.options().entrySet()) {
			String v = opts.getOrDefault(o.getKey(), o.getValue());
			boolean f = o.getValue().contains(".");
			try {
				v = f ? Float.toString(Float.parseFloat(v)) : Integer.toString(Integer.parseInt(v));
			} catch (NumberFormatException e) {
				throw new Unavailable("option " + o.getKey() + "=" + v + " is not a number");
			}
			helper.append("const ").append(f ? "float" : "int").append(" emrRt_").append(effectName).append('_').append(o.getKey())
					.append(" = ").append(v).append(";\n");
		}
		// shortcut: one effect per stage owns emrRtSkip and the common library; a second effect (RT-4) must share them
		helper.append("bool emrRtSkip(int id) { return ").append(skip).append("; }\n");
		helper.append(RtPatchTable.resource("rt/emrion_rt.glsl")).append('\n');
		helper.append(RtPatchTable.resource(effect.helpers())).append('\n');

		StringBuilder out = new StringBuilder(text.length() + helper.length() + 1024);
		for (int i = 0; i < t.size(); i++) {
			Token k = t.get(i);
			out.append(k.ws);
			if (i == first.start) out.append(oneLine(helper.toString())).append(' ');
			Match m = null;
			for (Match x : ms) if (x.function().bodyStart == i) m = x;
			if (m == null) {
				out.append(k.text);
				continue;
			}
			int newlines = 0;
			for (int j = i; j <= m.function().bodyEnd; j++) {
				if (j > i) newlines += GlslTranspiler.Stage.newlines(t.get(j).ws);
				newlines += GlslTranspiler.Stage.newlines(t.get(j).text);
			}
			out.append("{ ").append(oneLine(RtPatchTable.resource(m.patch().body()))).append(" }").append("\n".repeat(newlines));
			i = m.function().bodyEnd;
		}
		return out.append('\n').toString();
	}

	/** GLSL without comments and newlines (no directives allowed), so inserting it keeps the line numbers. */
	static String oneLine(String glsl) {
		StringBuilder sb = new StringBuilder();
		for (Token k : GlslLexer.lex(glsl)) {
			if (k.kind == GlslLexer.Kind.DIRECTIVE) throw new IllegalArgumentException("directive in RT helper: " + k.text);
			if (!k.ws.isEmpty() && !sb.isEmpty()) sb.append(' ');
			sb.append(k.text);
		}
		return sb.toString();
	}
}
