package dev.emrion.shaders.converter.expr;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.emrion.shaders.converter.expr.Functions.Node;
import dev.emrion.shaders.format.Manifest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Evaluates the custom uniforms/variables of a manifest once per frame, like Iris' CustomUniforms.update() in
 * beginLevelRendering. The ASTs are compiled once into a node tree, so {@link #update} does not allocate.
 * Not thread-safe; call update from the render thread, then read values by index or name.
 */
public final class CustomUniformRuntime {
	/** Holds the current value of a built-in or custom; doubles as the node that reads it. */
	private static final class Slot extends Node {
		final String name;
		final Type type;
		float fv;
		int iv;
		boolean bv;
		final float[] fa;
		final int[] ia;

		Slot(String name, Type type) {
			this.name = name;
			this.type = type;
			boolean ints = type == Type.IVEC2 || type == Type.IVEC3 || type == Type.IVEC4;
			fa = !type.isScalar() && !ints ? new float[type.size] : null;
			ia = ints ? new int[type.size] : null;
		}

		@Override float f() { return fv; }

		@Override int i() { return iv; }

		@Override boolean b() { return bv; }

		@Override Object o() { return fa != null ? fa : ia; }
	}

	private final Functions.Ctx ctx = new Functions.Ctx();
	private final Map<String, Slot> builtins = new LinkedHashMap<>();
	private final Slot[] builtinArray;
	private final Slot[] customs;
	private final Node[] roots;
	private final Map<String, Integer> index = new HashMap<>();
	private final List<String> broken = new ArrayList<>();

	/** host is only asked for biome ids here; values come from the host passed to {@link #update}. */
	public CustomUniformRuntime(List<Manifest.CustomUniform> uniforms, HostValues host) {
		customs = new Slot[uniforms.size()];
		roots = new Node[uniforms.size()];
		for (int k = 0; k < uniforms.size(); k++) {
			Manifest.CustomUniform u = uniforms.get(k);
			customs[k] = new Slot(u.name(), Type.of(u.type()));
			try {
				roots[k] = compile(u.expr(), host);
				if (!u.type().equals(u.expr().get("type").getAsString()))
					throw new IllegalArgumentException(u.name() + ": expression type " + u.expr().get("type") + " != " + u.type());
			} catch (Broken b) {
				broken.add(u.name());
			}
			index.put(u.name(), k);
		}
		builtinArray = builtins.values().toArray(new Slot[0]);
	}

	/** A biome constant or a broken custom is referenced: the uniform stays 0, like Iris drops it. */
	private static final class Broken extends RuntimeException {
		Broken() { super(null, null, false, false); }
	}

	/** Evaluates every custom uniform/variable in order. frameTimeSeconds is Iris' frameTime (smooth Δt). */
	public void update(HostValues host, double frameTimeSeconds) {
		for (Slot s : builtinArray) {
			switch (s.type) {
				case FLOAT -> s.fv = host.getFloat(s.name);
				case INT -> s.iv = host.getInt(s.name);
				case BOOL -> s.bv = host.getBool(s.name);
				case IVEC2, IVEC3 -> host.getInts(s.name, s.ia);
				default -> host.getFloats(s.name, s.fa);
			}
		}
		ctx.frameTime = (float) frameTimeSeconds;
		for (int k = 0; k < customs.length; k++) {
			Node root = roots[k];
			if (root == null) continue;
			Slot s = customs[k];
			switch (s.type) {
				case FLOAT -> s.fv = root.f();
				case INT -> s.iv = root.i();
				case BOOL -> s.bv = root.b();
				default -> System.arraycopy((float[]) root.o(), 0, s.fa, 0, s.fa.length);
			}
		}
	}

	/** Index of a custom uniform/variable, or -1. */
	public int indexOf(String name) { return index.getOrDefault(name, -1); }

	public int size() { return customs.length; }

	public String name(int index) { return customs[index].name; }

	/** bool | int | float | vec2 | vec3 | vec4 */
	public String type(int index) { return customs[index].type.id; }

	public float getFloat(int index) { return customs[index].fv; }

	public int getInt(int index) { return customs[index].iv; }

	public boolean getBool(int index) { return customs[index].bv; }

	/** Live array of a vecN value; do not modify. */
	public float[] getVector(int index) { return customs[index].fa; }

	public float getFloat(String name) { return getFloat(require(name)); }

	public int getInt(String name) { return getInt(require(name)); }

	public boolean getBool(String name) { return getBool(require(name)); }

	public float[] getVector(String name) { return getVector(require(name)); }

	/** Customs that can never evaluate (unknown biome constant or broken dependency); their value stays 0. */
	public List<String> broken() { return List.copyOf(broken); }

	private int require(String name) {
		Integer k = index.get(name);
		if (k == null) throw new IllegalArgumentException("no custom uniform " + name);
		return k;
	}

	// ---- compilation --------------------------------------------------------------------------------------------

	private Node compile(JsonObject o, HostValues host) {
		Type type = Type.of(o.get("type").getAsString());
		switch (o.get("op").getAsString()) {
			case "lit" -> {
				if (type == Type.INT) return intConst(o.get("value").getAsInt());
				float v = o.get("value").getAsFloat();
				return new Node() { @Override float f() { return v; } };
			}
			case "const" -> {
				OptionalInt id = host.biomeId(o.get("name").getAsString());
				if (id.isEmpty()) throw new Broken();
				return intConst(id.getAsInt());
			}
			case "var" -> { return variable(o.get("name").getAsString(), type); }
			case "cast" -> {
				Node a = compile(o.getAsJsonObject("arg"), host);
				if (type == Type.FLOAT) return new Node() { @Override float f() { return a.i(); } };
				return new Node() { @Override int i() { return (int) a.f(); } };
			}
			case "un" -> { return call(Functions.UN_NAME.get(o.get("operator").getAsString()), type, children(o, host, "arg")); }
			case "bin" -> { return call(Functions.BIN_NAME.get(o.get("operator").getAsString()), type, children(o, host, "lhs", "rhs")); }
			case "member" -> { return call("<access$" + o.get("field").getAsString() + ">", type, children(o, host, "arg")); }
			case "vec" -> {
				String fn = (type.id.startsWith("__") ? type.id.substring(2) : type.id);
				return call(fn, type, children(o, host, "args"));
			}
			case "call" -> {
				String fn = o.get("fn").getAsString();
				Typed args = children(o, host, "args");
				if (fn.equals("smooth")) {
					if (args.nodes.length < 1 || args.nodes.length > 3) throw new IllegalArgumentException("smooth arity");
					return Functions.smoothNode(args.nodes, new Functions.SmoothState(), ctx);
				}
				return call(fn, type, args);
			}
			default -> throw new IllegalArgumentException("unknown op " + o.get("op"));
		}
	}

	private record Typed(Node[] nodes, Type[] types) {}

	private Typed children(JsonObject o, HostValues host, String... keys) {
		List<JsonObject> list = new ArrayList<>();
		for (String k : keys) {
			JsonElement e = o.get(k);
			if (e.isJsonArray()) for (JsonElement a : e.getAsJsonArray()) list.add(a.getAsJsonObject());
			else list.add(e.getAsJsonObject());
		}
		Node[] nodes = new Node[list.size()];
		Type[] types = new Type[list.size()];
		for (int j = 0; j < nodes.length; j++) {
			nodes[j] = compile(list.get(j), host);
			types[j] = Type.of(list.get(j).get("type").getAsString());
		}
		return new Typed(nodes, types);
	}

	private Node call(String fn, Type ret, Typed args) {
		Functions.Fn f = Functions.byKey(fn, args.types, ret);
		if (f == null) throw new IllegalArgumentException("no function " + Functions.key(fn, args.types) + " -> " + ret.id);
		return f.impl().make(args.nodes, ctx);
	}

	private Node variable(String name, Type type) {
		Integer k = index.get(name);
		if (k != null) {
			if (roots[k] == null) throw new Broken();
			return customs[k];
		}
		switch (name) {
			case "pi" -> { float pi = (float) Math.PI; return new Node() { @Override float f() { return pi; } }; }
			case "blockEntityId", "currentRenderedItemId" -> { return intConst(-1); }
			case "entityColor" -> {
				float[] zero = new float[4];
				return new Node() { @Override Object o() { return zero; } };
			}
			default -> { }
		}
		if (UniformCatalog.inputType(name) != type)
			throw new IllegalArgumentException("unknown or mistyped variable " + name + " (" + type.id + "); customs must come after what they reference");
		return builtins.computeIfAbsent(name, n -> new Slot(n, type));
	}

	private static Node intConst(int v) {
		return new Node() { @Override int i() { return v; } };
	}
}
