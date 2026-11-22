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
 * Replaces patch-table function bodies in the preprocessed stages; all or nothing per effect, else the pack stays unpatched.
 * Patches are emitted on one line, so pack line numbers stay valid.
 */
public final class RtPatcher {
	private RtPatcher() {}

	/** @param frees the applied effects' {@link RtPatchTable.Effect#frees}, for {@link #free} once the stages are analysed */
	public record Result(ResolvedPack pack, Manifest.Rt rt, List<Diagnostic> diagnostics, List<String> frees) {}

	private record Match(Item function, RtPatchTable.Patch patch) {}

	private record StageMatch(int program, int stage, List<Match> matches) {}

	/** The pack's own table ({@link RtPatchTable#PACK_TABLE}) wins over the bundled ones; a broken one is reported, not skipped. */
	public static Result apply(ResolvedPack pack, Set<String> request) throws java.io.IOException {
		RtPatchTable own;
		try {
			own = RtPatchTable.fromPack(pack.source());
		} catch (IllegalArgumentException e) {
			return apply(pack, request, null, RtPatchTable.PACK_TABLE + ": " + e.getMessage());
		}
		if (own != null) {
			String miss = missing(pack.source(), own);
			return miss == null ? apply(pack, request, own)
					: apply(pack, request, null, RtPatchTable.PACK_TABLE + " targets " + own.pack() + " " + own.version() + " but shaders/" + miss + " is missing");
		}
		for (String t : RtPatchTable.TABLES) {
			RtPatchTable candidate = RtPatchTable.load(t);
			if (missing(pack.source(), candidate) == null) return apply(pack, request, candidate);
		}
		return apply(pack, request, null);
	}

	/** Pack option → lowest value the requested effects need, from the table {@link #apply} would use (empty: none). */
	public static Map<String, String> packSettings(dev.emrion.shaders.converter.pack.PackSource src, Set<String> request) throws java.io.IOException {
		RtPatchTable table;
		try {
			table = RtPatchTable.fromPack(src);
			if (table != null && missing(src, table) != null) return Map.of();
		} catch (IllegalArgumentException e) {
			return Map.of();
		}
		if (table == null) for (String t : RtPatchTable.TABLES) {
			RtPatchTable candidate = RtPatchTable.load(t);
			if (missing(src, candidate) == null) { table = candidate; break; }
		}
		if (table == null || table.packSettings() == null) return Map.of();
		Map<String, String> out = new TreeMap<>();
		for (String r : request) {
			var s = table.packSettings().get(r.indexOf(':') < 0 ? r : r.substring(0, r.indexOf(':')));
			if (s != null) s.forEach((k, v) -> out.merge(k, v, (a, b) -> {
				try {
					return Double.parseDouble(a) >= Double.parseDouble(b) ? a : b;
				} catch (NumberFormatException e) {
					return a;
				}
			}));
		}
		return out;
	}

	/** The effects the table {@link #apply} would use for this pack offers; empty: no usable table (none, broken or for another version). */
	public static Set<String> tableEffects(dev.emrion.shaders.converter.pack.PackSource src) throws java.io.IOException {
		try {
			RtPatchTable own = RtPatchTable.fromPack(src);
			if (own != null) return missing(src, own) == null ? Set.copyOf(own.effects().keySet()) : Set.of();
		} catch (IllegalArgumentException e) {
			return Set.of();
		}
		for (String t : RtPatchTable.TABLES) {
			RtPatchTable candidate = RtPatchTable.load(t);
			if (missing(src, candidate) == null) return Set.copyOf(candidate.effects().keySet());
		}
		return Set.of();
	}

	/** The first detect file the pack lacks, or null. */
	private static String missing(dev.emrion.shaders.converter.pack.PackSource src, RtPatchTable table) throws java.io.IOException {
		for (String f : table.detect()) if (src.read("shaders/" + f).isEmpty()) return f;
		return null;
	}

	/** @param table null = no table matches this pack */
	public static Result apply(ResolvedPack pack, Set<String> request, RtPatchTable table) {
		return apply(pack, request, table, "no RT patch table for this pack");
	}

	/** @param noTable the reason every effect is unavailable when {@code table} is null */
	private static Result apply(ResolvedPack pack, Set<String> request, RtPatchTable table, String noTable) {
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
		List<String> frees = new ArrayList<>();
		java.util.Set<Integer> anyHitIds = new java.util.TreeSet<>();
		ResolvedPack original = pack; // lifts read the user's pack as it is, not as an earlier effect patched it
		for (var e : effects.entrySet()) {
			String name = e.getKey();
			RtPatchTable.Effect effect = table == null ? null : table.effects().get(name);
			String reason = table == null ? noTable : effect == null ? "table " + table.label() + " has no " + name + " patches" : null;
			if (reason == null) {
				List<String> where = new ArrayList<>();
				try {
					pack = patchEffect(pack, original, table, name, effect, e.getValue(), where, anyHitIds, diagnostics);
					applied.addAll(request.stream().filter(x -> x.equals(name) || x.startsWith(name + ":")).sorted().toList());
					patched.put(name, where);
					if (effect.frees() != null) frees.addAll(effect.frees());
					diagnostics.add(new Diagnostic(Diagnostic.Severity.INFO, null, 0, null, null,
							"RT " + name + " applied (" + table.label() + "): " + where.size() + " stages patched: " + String.join(", ", where)));
				} catch (Unavailable u) {
					reason = u.getMessage();
				} catch (RuntimeException x) { // a pack table's mistake (a directive in a snippet, a bad program name) is the author's to fix
					if (table.files() == null) throw x;
					reason = "pack table error: " + x.getMessage();
				}
			}
			if (reason != null) {
				unavailable.put(name, reason);
				diagnostics.add(Diagnostic.warning(null, 0, "RT " + name + " unavailable: " + reason));
			}
		}
		return new Result(pack, new Manifest.Rt(applied, unavailable, patched, List.copyOf(anyHitIds), table == null ? null : table.id()), diagnostics, frees);
	}

	/** Shrinks pack buffers and images an applied effect made unused (no live program reads them); bindings stay. */
	public static ResolvedPack free(ResolvedPack pack, List<String> keys, Map<String, Map<String, Transpiler.ProgramAnalysis>> analyses,
	                                List<Diagnostic> diagnostics) {
		var props = new java.util.LinkedHashMap<>(pack.properties());
		List<String> freed = new ArrayList<>();
		for (String key : keys) {
			String v = props.get(key);
			if (v == null) continue;
			String[] t = v.trim().split("\\s+");
			boolean image = key.startsWith("image.");
			if (image && (t.length < 7 || Boolean.parseBoolean(t[5]))) continue; // screen-relative images: keep
			String name = key.substring(key.indexOf('.') + 1);
			String user = null;
			for (var dim : analyses.entrySet())
				for (var a : dim.getValue().values()) {
					boolean uses = image ? a.images().stream().anyMatch(i -> i.name().equals(name)) || a.samplers().stream().anyMatch(s -> s.name().equals(t[0]))
							: a.buffers().stream().anyMatch(b -> b.used() && !b.blockName().equals("EmrRtSections") && Integer.toString(b.declaredBinding()).equals(name));
					if (uses && user == null) user = dim.getKey() + "/" + a.program();
				}
			if (user != null) {
				diagnostics.add(new Diagnostic(Diagnostic.Severity.INFO, null, 0, null, null, "RT: " + key + " kept, still used by " + user));
				continue;
			}
			if (image) {
				for (int i = 6; i < t.length; i++) t[i] = "1";
				props.put(key, String.join(" ", t));
			} else props.put(key, "16");
			freed.add(key);
		}
		if (freed.isEmpty()) return pack;
		diagnostics.add(new Diagnostic(Diagnostic.Severity.INFO, null, 0, null, null, "RT: freed " + String.join(", ", freed) + " (no live code uses them)"));
		return new ResolvedPack(pack.source(), pack.options(), props, pack.idMaps(), pack.dimensionMap(), pack.dimensions());
	}

	/** @throws Unavailable with the reason the effect cannot be applied */
	private static ResolvedPack patchEffect(ResolvedPack pack, ResolvedPack original, RtPatchTable table, String name, RtPatchTable.Effect effect,
	                                        Map<String, String> opts, List<String> where, java.util.Set<Integer> anyHitIds, List<Diagnostic> diagnostics) {
		if (effect.variants() != null) // a named variant (shadows:rtx) is merged in and is not an option of its own
			for (var v : effect.variants().entrySet()) if (opts.remove(v.getKey()) != null) effect = effect.with(v.getValue());
		for (var it = opts.keySet().iterator(); it.hasNext(); ) {
			String o = it.next();
			if (effect.options().containsKey(o)) continue;
			if (table.files() == null) throw new Unavailable("unknown option '" + o + "'");
			// pack tables need not declare every option or style variant the mod sends (shadows:rtx, :rays=N): it keeps them working
			diagnostics.add(new Diagnostic(Diagnostic.Severity.INFO, null, 0, null, null, "RT " + name + ": option '" + o + "' not in the pack's table, ignored"));
			it.remove();
		}
		for (var o : effect.options().entrySet()) { // "@NAME": the default is the pack option NAME (e.g. its AO strength slider)
			if (!o.getValue().startsWith("@") || opts.containsKey(o.getKey())) continue;
			String v = pack.options() == null ? null : pack.options().values().get(o.getValue().substring(1));
			if (v == null) throw new Unavailable("pack option " + o.getValue().substring(1) + " not found");
			opts.put(o.getKey(), v);
		}
		Map<String, List<ResolvedPack.ProgramSource>> dims = new TreeMap<>(pack.dimensions());
		List<String> misses = new ArrayList<>();
		boolean any = false;
		for (var d : dims.entrySet()) {
			if (effect.dimensions() != null && !effect.dimensions().contains(d.getKey())) continue;
			List<ResolvedPack.ProgramSource> programs = new ArrayList<>(d.getValue());
			List<StageMatch> found = new ArrayList<>();
			for (int p = 0; p < programs.size(); p++) {
				if (!programs.get(p).enabled()) continue;
				var stages = programs.get(p).stages();
				for (int s = 0; s < stages.size(); s++) {
					List<Token> t = GlslLexer.lex(stages.get(s).text());
					String program = programs.get(p).name();
					List<Match> m = matches(GlslParser.parse(t), t, effect, program);
					if (m.isEmpty()) continue;
					var missing = effect.patches().stream() // a stage that matches part of a program's patch set is never half-patched
							.filter(patch -> !patch.optional() && patch.programs() != null && patch.programs().contains(program) && m.stream().noneMatch(x -> x.patch() == patch
									|| patch.oneOf() != null && patch.oneOf().equals(x.patch().oneOf())))
							.findFirst();
					if (missing.isPresent()) {
						// only optional extras matched (the Nether's composite without world-space reflections): not one of this effect's stages
						if (m.stream().allMatch(x -> x.patch().optional())) {
							diagnostics.add(new Diagnostic(Diagnostic.Severity.INFO, null, 0, null, null, "RT " + name + ": " + d.getKey() + "/" + program
									+ " left out: only optional patches matched (no '" + missing.get().function() + "')"));
							continue;
						}
						throw new Unavailable(d.getKey() + ": patch '" + missing.get().function() + "' matched no function in " + program
								+ " (the pack's version or settings differ)");
					}
					found.add(new StageMatch(p, s, m));
				}
			}
			if (found.isEmpty()) {
				misses.add(d.getKey().isEmpty() ? "shaders/" : d.getKey());
				continue;
			}
			for (String req : effect.requires()) {
				boolean hit = found.stream().anyMatch(f -> programs.get(f.program()).name().equals(req));
				if (!hit) throw new Unavailable(d.getKey() + ": no patch matched in required program " + req + " (looked for " + wanted(effect, req)
						+ "; the pack's version or settings differ)");
			}
			String skip = skipIds(programs, effect);
			if (!skip.equals("false")) anyHitIds.addAll(effect.skipIds());
			List<Diagnostic> liftNotes = new ArrayList<>();
			Map<PreprocessedStage, Map.Entry<List<Token>, List<Item>>> parsed = new java.util.IdentityHashMap<>(); // lift sources, parsed once
			for (StageMatch f : found) {
				var src = programs.get(f.program()).stages().get(f.stage());
				String label = d.getKey() + "/" + src.program() + "." + src.stage().extension;
				var lifts = new Lifts(effect, original.dimensions().get(d.getKey()), src.program(), src.stage(), label, liftNotes, parsed);
				String text = patchStage(table, src.text(), src.program(), name, effect, opts, skip, label, lifts);
				var stages = new ArrayList<>(programs.get(f.program()).stages());
				stages.set(f.stage(), new PreprocessedStage(src.program(), src.group(), src.stage(), src.sourcePath(), text,
						src.lineMap(), src.directives()));
				var old = programs.get(f.program());
				programs.set(f.program(), new ResolvedPack.ProgramSource(old.name(), old.enabled(), List.copyOf(stages)));
				where.add(label);
			}
			diagnostics.addAll(liftNotes);
			if (effect.computes() != null)
				for (var c : effect.computes()) {
					if (c.skipIf() != null) {
						String[] kv = c.skipIf().split("=", 2);
						if (kv[1].equals(opts.getOrDefault(kv[0], effect.options().get(kv[0])))) continue;
					}
					if (programs.stream().anyMatch(p -> p.name().equals(c.program())))
						throw new Unavailable(d.getKey() + ": the pack already has a program " + c.program());
					programs.add(new ResolvedPack.ProgramSource(c.program(), true, List.of(computeStage(table, d.getKey(), c, name, effect, opts))));
					where.add(d.getKey() + "/" + c.program() + ".csh");
				}
			d.setValue(List.copyOf(programs));
			any = true;
		}
		if (!any) throw new Unavailable("no patch matched in " + String.join(", ", misses) + " (looked for " + wanted(effect, null)
				+ (effect.requires().isEmpty() ? "" : ", required in " + String.join(", ", effect.requires())) + "; the effect is off in the pack settings, or the signature differs?)");
		var props = pack.properties();
		if (effect.images() != null) {
			props = new java.util.LinkedHashMap<>(props);
			for (var i : effect.images().entrySet()) {
				String v = substitute(i.getValue(), effect, opts), old = props.get("image." + i.getKey());
				// the same image from another effect is shared (RTX GI reads RTX block light's emrBlG)
				if (old != null && !old.equals(v)) throw new Unavailable("the pack already declares image." + i.getKey());
				props.put("image." + i.getKey(), v);
			}
		}
		return new ResolvedPack(pack.source(), pack.options(), props, pack.idMaps(), pack.dimensionMap(), dims);
	}

	/** The patch functions looked for in {@code program} (null: every patch, with its programs). */
	private static String wanted(RtPatchTable.Effect effect, String program) {
		List<String> w = new ArrayList<>();
		for (var p : effect.patches())
			if (program == null) w.add("'" + p.function() + "' in " + (p.programs() == null ? "any program" : String.join("/", p.programs())));
			else if (p.programs() == null || p.programs().contains(program)) w.add("'" + p.function() + "'");
		return w.isEmpty() ? "no patches" : String.join(", ", w);
	}

	/** {@code $<option>} → the option's value (requested or default). */
	private static String substitute(String text, RtPatchTable.Effect effect, Map<String, String> opts) {
		for (var o : effect.options().entrySet()) text = text.replace("$" + o.getKey(), opts.getOrDefault(o.getKey(), o.getValue()));
		return text;
	}

	/** An Emrion compute program: option constants, the common library, its helpers and its body. */
	private static PreprocessedStage computeStage(RtPatchTable table, String dim, RtPatchTable.Compute c, String effectName, RtPatchTable.Effect effect,
	                                              Map<String, String> opts) {
		StringBuilder text = new StringBuilder("#version 450\n").append(options(effectName, effect, opts));
		// Iris dimension folder (world0, world-1, world1): computes have no pack define like NETHER or END
		text.append("const int emrRtDim = ").append(dim.matches("world-?\\d+") ? dim.substring(5) : "0").append(";\n");
		text.append(RtPatchTable.resource("rt/emrion_rt.glsl")).append('\n');
		for (String h : c.helpers()) text.append(table.text(h)).append('\n');
		text.append(substitute(table.text(c.body()), effect, opts)).append('\n');
		String plain = text.toString().replaceAll("//[^\n]*", "");
		ProgramGroup group = ProgramGroup.valueOf(c.program().replaceAll("[^a-z].*", "").toUpperCase(java.util.Locale.ROOT));
		return new PreprocessedStage(c.program(), group, StageType.COMPUTE, "emrion:" + dim + "/" + c.program() + ".csh", plain,
				LineMap.IDENTITY_UNKNOWN, DirectiveParser.parse(StageType.COMPUTE, plain, plain, w -> {}));
	}

	private static String options(String effectName, RtPatchTable.Effect effect, Map<String, String> opts) {
		StringBuilder helper = new StringBuilder();
		for (var o : effect.options().entrySet()) {
			String v = opts.getOrDefault(o.getKey(), o.getValue());
			boolean f = o.getValue().contains(".") || o.getValue().startsWith("@") && v.contains("."); // a pack slider may be a float
			try {
				v = f ? Float.toString(Float.parseFloat(v)) : Integer.toString(Integer.parseInt(v));
			} catch (NumberFormatException e) {
				throw new Unavailable("option " + o.getKey() + "=" + v + " is not a number");
			}
			helper.append("const ").append(f ? "float" : "int").append(" emrRt_").append(effectName).append('_').append(o.getKey())
					.append(" = ").append(v).append(";\n");
		}
		return helper.toString();
	}

	static List<Match> matches(List<Item> items, List<Token> t, RtPatchTable.Effect effect, String program) {
		List<Match> out = new ArrayList<>();
		for (Item it : items) {
			if (it.kind == ItemKind.DECL && it.decls.size() == 1 && it.decls.getFirst().hasInit()) { // "type name": a global's initialiser
				var d = it.decls.getFirst(); // "vec2[8] offsets" or "vec2 offsets[8]", as the pack writes it
				String decl = GlslParser.text(t, it.typeStart, it.typeEnd) + " " + d.name() + (d.hasArray() ? GlslParser.text(t, d.arrStart(), d.arrEnd()) : "");
				for (var p : effect.patches())
					if ((p.programs() == null || p.programs().contains(program)) && !p.function().contains("(") && sameFunction(p.function(), decl))
						out.add(new Match(it, p));
				continue;
			}
			if (it.kind != ItemKind.FUNCTION) continue;
			String sig = signature(t, it);
			for (var p : effect.patches())
				// a find patch matches only where its anchor occurs: "void main()" also names the vertex stages
				if ((p.programs() == null || p.programs().contains(program)) && sameFunction(p.function(), sig)
						&& (p.find() == null || contains(t, it, GlslLexer.lex(p.find())))) out.add(new Match(it, p));
		}
		return out;
	}

	private static boolean contains(List<Token> t, Item fn, List<Token> f) {
		for (int i = fn.bodyStart; i + f.size() - 1 <= fn.bodyEnd; i++) {
			boolean eq = true;
			for (int j = 0; j < f.size() && eq; j++) eq = t.get(i + j).text.equals(f.get(j).text);
			if (eq) return true;
		}
		return false;
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

	/** {@code function} of a patch or lift: one signature, or alternatives separated by '|' (a pack version that changed it). */
	static boolean sameFunction(String function, String sig) {
		for (String f : function.split("\\|")) if (normalise(f).equals(sig)) return true;
		return false;
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

	/** Patches one stage for one effect: the common library once per stage, each effect's helpers before its first patched function. */
	private static String patchStage(RtPatchTable table, String text, String program, String effectName, RtPatchTable.Effect effect, Map<String, String> opts,
	                                 String skip, String label, Lifts lifts) {
		List<Token> t = GlslLexer.lex(text);
		List<Item> items = GlslParser.parse(t);
		List<Match> ms = matches(items, t, effect, program);
		Item first = ms.getFirst().function();
		Set<String> anywhere = new HashSet<>();
		Map<Item, Set<String>> declaredBefore = new java.util.HashMap<>();
		for (Match m : ms) declaredBefore.put(m.function(), new HashSet<>());
		for (Item it : items) {
			Set<String> names = new HashSet<>();
			if ((it.kind == ItemKind.FUNCTION || it.kind == ItemKind.PROTOTYPE) && it.name != null) names.add(it.name);
			if (it.kind == ItemKind.DECL) it.decls.forEach(d -> names.add(d.name()));
			anywhere.addAll(names);
			for (var e : declaredBefore.entrySet()) if (it.start < e.getKey().start) e.getValue().addAll(names);
		}
		Set<String> before = declaredBefore.get(first);
		for (Match m : ms)
			if (m.patch().needs() != null) for (String n : m.patch().needs())
				if (!declaredBefore.get(m.function()).contains(n)) throw new Unavailable(label + ": '" + n + "' is not declared before " + m.function().name);
		boolean bare = ms.stream().allMatch(m -> m.patch().bare());
		if (!bare) for (String n : effect.needs())
			if (!before.contains(n)) throw new Unavailable(label + ": '" + n + "' is not declared before " + first.name);
		StringBuilder helper = new StringBuilder();
		if (!bare) {
			for (var u : effect.uniforms().entrySet()) {
				if (before.contains(u.getKey())) continue;
				if (anywhere.contains(u.getKey())) throw new Unavailable(label + ": '" + u.getKey() + "' is declared after " + first.name);
				helper.append("uniform ").append(u.getValue()).append(' ').append(u.getKey()).append(";\n");
			}
			helper.append(options(effectName, effect, opts));
			if (effect.skipIds() != null) helper.append("bool emrRt_").append(effectName).append("_skip(int id) { return ").append(skip).append("; }\n");
			helper.append(lifts.expand(table.texts(effect.helpers()), before));
		}
		// the common library: once per stage, in front of everything (no pack symbols), whichever effect comes first
		int common = -1;
		if (!bare && !Boolean.FALSE.equals(effect.library()) && !anywhere.contains("emrRtTlas"))
			for (Item it : items) if (it.kind != ItemKind.DIRECTIVE) { common = it.start; break; }

		// token index -> {last replaced token, replacement}
		Map<Integer, Object[]> edits = new TreeMap<>();
		for (Match m : ms) {
			try {
				String raw = table.text(m.patch().body());
				Set<String> known = declaredBefore.get(m.function());
				if (m.function().kind == ItemKind.DECL) {
					var d = m.function().decls.getFirst();
					edits.put(d.initStart(), new Object[]{d.initEnd() - 1, oneLine(raw)});
					continue;
				}
				if (m.patch().after() != null) {
					int at = anchor(t, m, label);
					edits.put(at, new Object[]{at, t.get(at).text + " " + lifts.expand(raw, Lifts.known(known, t, m.function(), at))});
					continue;
				}
				if (m.patch().find() == null) {
					edits.put(m.function().bodyStart, new Object[]{m.function().bodyEnd, "{ " + lifts.expand(raw, Lifts.known(known, t, m.function(), m.function().bodyStart)) + " }"});
					continue;
				}
				List<Token> f = GlslLexer.lex(m.patch().find());
				int at = -1;
				for (int i = m.function().bodyStart; i + f.size() - 1 <= m.function().bodyEnd; i++) {
					boolean eq = true;
					for (int j = 0; j < f.size() && eq; j++) eq = t.get(i + j).text.equals(f.get(j).text);
					if (!eq) continue;
					if (at >= 0) throw new Unavailable(label + ": '" + m.patch().find() + "' occurs more than once in " + m.function().name);
					at = i;
				}
				if (at < 0) throw new Unavailable(label + ": '" + m.patch().find() + "' not found in " + m.function().name);
				int end = at + f.size() - 1;
				if (m.patch().statements() != null) {
					if (m.patch().statements() < 1) throw new IllegalArgumentException(label + ": \"statements\" must be at least 1");
					if (!t.get(at - 1).is(";") && !t.get(at - 1).is("{") && !t.get(at - 1).is("}") && !t.get(at - 1).is(")"))
						throw new Unavailable(label + ": '" + m.patch().find() + "' does not start a statement in " + m.function().name);
					end = at - 1;
					for (int n = 0; n < m.patch().statements() && end < m.function().bodyEnd; n++) end = Lifts.statementEnd(t, end + 1);
					if (end >= m.function().bodyEnd) throw new Unavailable(label + ": " + m.function().name + " ends before the statements at '" + m.patch().find() + "'");
				}
				edits.put(at, new Object[]{end, lifts.expand(raw, Lifts.known(known, t, m.function(), at))});
			} catch (Unavailable u) { // an optional patch whose lift or statements don't fit this pack is left out, like a missing find
				if (!m.patch().optional()) throw u;
				lifts.notes().add(new Diagnostic(Diagnostic.Severity.INFO, null, 0, null, null, "RT " + label + ": optional patch of " + m.function().name + " left out: " + u.getMessage()));
			}
		}
		StringBuilder out = new StringBuilder(text.length() + helper.length() + 1024);
		for (int i = 0; i < t.size(); i++) {
			Token k = t.get(i);
			out.append(k.ws);
			if (i == common) out.append(oneLine(RtPatchTable.resource("rt/emrion_rt.glsl"))).append(' ');
			if (i == first.start && !helper.isEmpty()) out.append(oneLine(helper.toString())).append(' ');
			Object[] e = edits.get(i);
			if (e == null) {
				out.append(k.text);
				continue;
			}
			int end = (Integer) e[0], newlines = 0;
			for (int j = i; j <= end; j++) {
				if (j > i) newlines += GlslTranspiler.Stage.newlines(t.get(j).ws);
				newlines += GlslTranspiler.Stage.newlines(t.get(j).text);
			}
			out.append((String) e[1]).append("\n".repeat(newlines));
			i = end;
		}
		return out.append('\n').toString();
	}

	/** Index of the last token of the patch's {@code after} statement inside the function body (exactly one occurrence). */
	private static int anchor(List<Token> t, Match m, String label) {
		List<String> want = GlslLexer.lex(m.patch().after()).stream().map(k -> k.text).toList();
		int found = -1;
		for (int i = m.function().bodyStart; i + want.size() - 1 <= m.function().bodyEnd; i++) {
			boolean eq = true;
			for (int j = 0; j < want.size() && eq; j++) eq = t.get(i + j).text.equals(want.get(j));
			if (!eq) continue;
			if (found >= 0) throw new Unavailable(label + ": '" + m.patch().after() + "' occurs more than once in " + m.function().name);
			found = i + want.size() - 1;
		}
		if (found < 0) throw new Unavailable(label + ": " + m.function().name + " has no '" + m.patch().after() + "' (the pack's version or settings differ)");
		return found;
	}

	/**
	 * The {@code @name} markers of an effect's helpers and bodies, replaced by statements of the user's own pack
	 * ({@link RtPatchTable.Lift}): the table ships only its glue, the pack code comes from the pack being converted.
	 * @param originals the dimension's programs before any effect patched them
	 */
	private record Lifts(RtPatchTable.Effect effect, List<ResolvedPack.ProgramSource> originals, String program, StageType stage, String label,
	                     List<Diagnostic> notes, Map<PreprocessedStage, Map.Entry<List<Token>, List<Item>>> parsed) {
		static final Set<String> TYPES = Set.of("void", "bool", "int", "uint", "float", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4",
				"uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4");
		/** Keywords and built-in functions a lifted statement may use without a declaration. */
		static final Set<String> BUILTINS = Set.of("if", "else", "for", "while", "do", "return", "break", "continue", "discard", "true", "false",
				"const", "radians", "degrees", "sin", "cos", "tan", "asin", "acos", "atan", "pow", "exp", "log", "exp2", "log2", "sqrt",
				"inversesqrt", "abs", "sign", "floor", "ceil", "fract", "mod", "min", "max", "clamp", "mix", "step", "smoothstep", "round",
				"trunc", "length", "distance", "dot", "cross", "normalize", "reflect", "refract", "faceforward", "any", "all", "not", "equal",
				"notEqual", "lessThan", "greaterThan", "lessThanEqual", "greaterThanEqual", "texture", "texture2D", "texture2DLod",
				"texture3D", "textureLod", "texelFetch", "textureSize", "shadow2D", "dFdx", "dFdy", "fwidth", "transpose", "inverse");

		/** Names declared in front of token {@code at} of {@code fn}: {@code globals} plus its parameters and locals. */
		static Set<String> known(Set<String> globals, List<Token> t, Item fn, int at) {
			Set<String> k = new HashSet<>(globals);
			k.addAll(declared(t, fn.nameIdx, at));
			return k;
		}

		/** Names declared ("type name[ = ...][, name]...") in tokens [from, to); scopes are ignored. */
		static Set<String> declared(List<Token> t, int from, int to) {
			Set<String> out = new HashSet<>();
			for (int i = from; i + 1 < to; i++) {
				if (!TYPES.contains(t.get(i).text) || !t.get(i + 1).ident()) continue;
				out.add(t.get(i + 1).text);
				for (int j = i + 2, depth = 0; j + 1 < to && depth >= 0 && !t.get(j).is(";") && !t.get(j).is("{"); j++) {
					String x = t.get(j).text;
					if (x.equals("(") || x.equals("[")) depth++;
					else if (x.equals(")") || x.equals("]")) depth--;
					else if (depth == 0 && x.equals(",") && t.get(j + 1).ident()) out.add(t.get(j + 1).text);
				}
			}
			return out;
		}

		/** {@code glsl} with its markers replaced, on one line ({@link #oneLine}); {@code known}: names declared where it goes. */
		String expand(String glsl, Set<String> known) {
			List<Token> t = GlslLexer.lex(glsl);
			StringBuilder sb = new StringBuilder();
			Set<String> lifted = new HashSet<>(); // what earlier markers' statements declared, for the later ones
			for (int i = 0; i < t.size(); i++) {
				Token k = t.get(i);
				sb.append(k.ws);
				if (!k.is("@") || i + 1 == t.size() || !t.get(i + 1).ident()) {
					sb.append(k.text);
					continue;
				}
				Set<String> here = new HashSet<>(known);
				here.addAll(declared(t, 0, i));
				here.addAll(lifted);
				String code = lift(t.get(++i).text, here);
				List<Token> ct = GlslLexer.lex(code);
				lifted.addAll(declared(ct, 0, ct.size()));
				sb.append(' ').append(code).append(' ');
			}
			return oneLine(sb.toString());
		}

		private String lift(String name, Set<String> known) {
			RtPatchTable.Lift l = effect.lifts().get(name);
			if (l == null) throw new IllegalArgumentException(label + ": the table has no lift '" + name + "'");
			String prog = l.program() == null ? program : l.program();
			String why = prog + " has no " + stage.extension + " stage with '" + l.function() + "'";
			for (var p : originals) {
				if (!p.name().equals(prog) || !p.enabled()) continue;
				for (var s : p.stages()) {
					if (s.stage() != stage) continue;
					var tp = parsed.computeIfAbsent(s, x -> {
						List<Token> lexed = GlslLexer.lex(x.text());
						return Map.entry(lexed, GlslParser.parse(lexed));
					});
					List<Token> t = tp.getKey();
					for (Item fn : tp.getValue()) {
						if (fn.kind != ItemKind.FUNCTION || !sameFunction(l.function(), signature(t, fn))) continue;
						if (l.text() != null) return l.text();
						why = take(l, t, fn, known);
						if (!why.startsWith("!")) return why;
						why = why.substring(1);
					}
				}
			}
			if (l.otherwise() == null) throw new Unavailable(label + ": @" + name + ": " + why + " (the pack's version or settings differ)");
			notes.add(new Diagnostic(Diagnostic.Severity.INFO, null, 0, null, null, "RT " + label + ": @" + name + " left out: " + why));
			return l.otherwise();
		}

		/** The lifted statements, or "!" + why not. */
		private static String take(RtPatchTable.Lift l, List<Token> t, Item fn, Set<String> known) {
			List<Token> a = GlslLexer.lex(l.at());
			int s = -1;
			for (int i = fn.bodyStart; i + a.size() <= fn.bodyEnd; i++) {
				boolean eq = true;
				for (int j = 0; j < a.size() && eq; j++) eq = t.get(i + j).text.equals(a.get(j).text);
				if (!eq) continue;
				if (s >= 0) return "!'" + l.at() + "' occurs more than once in " + fn.name;
				s = i;
			}
			if (s < 0) return "!" + fn.name + " has no '" + l.at() + "'";
			int e = s - 1;
			for (int n = 0; n < (l.statements() == null ? 1 : l.statements()); n++) {
				e = statementEnd(t, e + 1);
				if (e >= fn.bodyEnd) return "!" + fn.name + " ends before the statements at '" + l.at() + "'";
			}
			Set<String> own = declared(t, s, e + 1);
			StringBuilder sb = new StringBuilder();
			for (int i = s; i <= e; i++) {
				Token k = t.get(i);
				if (k.kind == GlslLexer.Kind.DIRECTIVE) return "!a directive inside the statements at '" + l.at() + "'";
				if (k.ident() && !t.get(i - 1).is(".") && !TYPES.contains(k.text) && !BUILTINS.contains(k.text) && !k.text.startsWith("gl_")
						&& !own.contains(k.text) && !known.contains(k.text))
					return "!the statements at '" + l.at() + "' use '" + k.text + "', which is not declared where they go";
				sb.append(i == s ? "" : k.ws).append(k.text);
			}
			return sb.toString();
		}

		/** Index of the last token of the statement starting at {@code i}. */
		static int statementEnd(List<Token> t, int i) {
			String x = t.get(i).text;
			if (x.equals("{")) return GlslParser.match(t, i);
			if (x.equals("if") || x.equals("for") || x.equals("while")) {
				int e = statementEnd(t, GlslParser.match(t, i + 1) + 1);
				return x.equals("if") && e + 1 < t.size() && t.get(e + 1).is("else") ? statementEnd(t, e + 2) : e;
			}
			for (int depth = 0; i < t.size(); i++) {
				String y = t.get(i).text;
				if (y.equals("(") || y.equals("[") || y.equals("{")) depth++;
				else if (y.equals(")") || y.equals("]") || y.equals("}")) depth--;
				else if (depth == 0 && y.equals(";")) return i;
			}
			return t.size() - 1;
		}
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
