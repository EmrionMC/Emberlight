package dev.emrion.shaders.converter.expr;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;

/**
 * Parses uniform.&lt;type&gt;.&lt;name&gt; / variable.&lt;type&gt;.&lt;name&gt; entries of shaders.properties (iris-spec §4.3)
 * into type-checked, serialised ASTs (FORMAT.md §6.1), in evaluation order. Owner: agent (e).
 *
 * <p>Iris behaviour kept: the first declaration of a name wins; a declaration that fails to parse or type-check is
 * dropped, and so is everything that references it; a declaration may reference later ones (the output is
 * topologically sorted, stable in declaration order); a name that shadows a built-in is dropped (references keep
 * reading the built-in). Every {@code smooth(...)} call site gets its own {@code stateId} (0, 1, … in output order);
 * the optional first "id" argument is <b>ignored</b> like in Iris (each call has its own SmoothFloat even when two
 * calls pass the same id), so it is only kept as the informational {@code "id"} field.
 * Deviation: a reference cycle drops only the uniforms in/depending on the cycle (Iris rejects all custom uniforms).
 */
public final class CustomUniformParser {
	private CustomUniformParser() {}

	static final String FILE = "shaders/shaders.properties";

	private record Decl(String name, Type type, boolean uniform, String source, ExprParser.El ast) {}

	public static List<Manifest.CustomUniform> parse(SequencedMap<String, String> properties, Diagnostics diagnostics) {
		Map<String, Decl> decls = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : properties.entrySet()) {
			String key = e.getKey();
			boolean uniform = key.startsWith("uniform.");
			if (!uniform && !key.startsWith("variable.")) continue;
			String[] parts = key.substring(uniform ? 8 : 9).split("\\.");
			if (parts.length != 2) {
				warn(diagnostics, "Custom uniforms/variables should take the form <kind>.<type>.<name>; ignoring " + key);
				continue;
			}
			String name = parts[1];
			if (decls.containsKey(name)) {
				warn(diagnostics, "Ignoring duplicated custom uniform name: " + name);
				continue;
			}
			Type type = Type.declared(parts[0]);
			if (type == null) {
				warn(diagnostics, "Ignoring invalid uniform type: " + parts[0] + " of " + name);
				continue;
			}
			try {
				decls.put(name, new Decl(name, type, uniform, e.getValue(), ExprParser.parse(e.getValue())));
			} catch (ExprParser.ParseException ex) {
				error(diagnostics, "Failed to parse custom " + kind(uniform) + " " + name + " = " + e.getValue() + ": " + ex.getMessage());
			}
		}

		// type resolution (inputs win over custom names, like Iris' variableTypeMap)
		Map<String, JsonObject> resolved = new LinkedHashMap<>();
		Map<String, Set<String>> deps = new HashMap<>();
		for (Decl d : decls.values()) {
			JsonObject expr;
			try {
				expr = Resolver.compile(d.source, d.type, n -> {
					Type t = UniformCatalog.inputType(n);
					if (t != null) return t;
					Decl other = decls.get(n);
					return other == null ? null : other.type;
				});
			} catch (RuntimeException ex) {
				// Iris (stareval) has the same overloads, e.g. no vec2 * float (MakeUp); it logs a warning and skips the uniform
				warn(diagnostics, "Failed to resolve custom " + kind(d.uniform) + " " + d.name + " = " + d.source + ": " + ex.getMessage()
						+ "; skipped like Iris does, the uniform keeps its default value");
				continue;
			}
			if (UniformCatalog.inputType(d.name) != null) {
				error(diagnostics, "Custom " + kind(d.uniform) + " shadows built-in uniform: " + d.name);
				continue;
			}
			Set<String> refs = new LinkedHashSet<>();
			walk(expr, o -> {
				String op = o.get("op").getAsString();
				if (op.equals("var") && UniformCatalog.inputType(o.get("name").getAsString()) == null) refs.add(o.get("name").getAsString());
				if (op.equals("const")) {
					String biome = o.get("name").getAsString().substring(6).toLowerCase(Locale.ROOT);
					if (!UniformCatalog.VANILLA_BIOMES.contains(biome))
						warn(diagnostics, d.name + ": " + o.get("name").getAsString() + " is not a vanilla biome; it resolves at runtime or breaks the uniform");
				}
			});
			resolved.put(d.name, expr);
			deps.put(d.name, refs);
		}

		// stable topological order; broken references and cycles drop the dependents
		Map<String, Integer> state = new HashMap<>();
		List<String> order = new ArrayList<>();
		for (String name : resolved.keySet()) visit(name, resolved, deps, state, order, diagnostics);

		List<Manifest.CustomUniform> out = new ArrayList<>();
		int[] stateId = {0};
		for (String name : order) {
			Decl d = decls.get(name);
			JsonObject expr = resolved.get(name);
			walk(expr, o -> {
				if (o.get("op").getAsString().equals("call") && o.get("fn").getAsString().equals("smooth")) o.addProperty("stateId", stateId[0]++);
			});
			out.add(new Manifest.CustomUniform(name, d.type.id, d.uniform, expr));
		}
		return out;
	}

	private static final int VISITING = 1, OK = 2, BROKEN = 3;

	private static boolean visit(String name, Map<String, JsonObject> resolved, Map<String, Set<String>> deps,
	                             Map<String, Integer> state, List<String> order, Diagnostics diagnostics) {
		Integer s = state.get(name);
		if (s != null) return s == OK;
		state.put(name, VISITING);
		String problem = null;
		for (String dep : deps.get(name)) {
			Integer ds = state.get(dep);
			if (!resolved.containsKey(dep)) problem = "references broken or unknown " + dep;
			else if (ds != null && ds == VISITING) problem = "is part of a reference cycle through " + dep;
			else if (!visit(dep, resolved, deps, state, order, diagnostics)) problem = "references broken " + dep;
			if (problem != null) break;
		}
		if (problem != null) {
			state.put(name, BROKEN);
			error(diagnostics, "Custom uniform " + name + " won't work: it " + problem);
			return false;
		}
		state.put(name, OK);
		order.add(name);
		return true;
	}

	interface Visitor { void visit(JsonObject node); }

	/** Pre-order walk over every AST node. */
	static void walk(JsonObject node, Visitor v) {
		v.visit(node);
		for (String k : new String[]{"arg", "lhs", "rhs"}) if (node.has(k)) walk(node.getAsJsonObject(k), v);
		if (node.has("args")) for (JsonElement a : node.getAsJsonArray("args")) walk(a.getAsJsonObject(), v);
	}

	private static String kind(boolean uniform) { return uniform ? "uniform" : "variable"; }

	private static void warn(Diagnostics d, String msg) { d.add(Diagnostic.warning(FILE, 0, msg)); }

	private static void error(Diagnostics d, String msg) { d.add(Diagnostic.error(FILE, 0, msg)); }
}
