package dev.emrion.shaders.converter.expr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static dev.emrion.shaders.converter.expr.Type.*;

/**
 * The Iris function table (IrisFunctions + stareval casts), used by the type resolver (signatures, priorities,
 * constant parameters) and by the runtime (implementations). Registration order matters for overload ambiguity and
 * mirrors Iris. Values are Java float/int/boolean exactly like stareval's FunctionReturn.
 *
 * <p>Reproduced quirks: n-ary {@code min/max} (3..16 args) evaluate {@code args[1]} instead of {@code args[i]}, so
 * they only look at the first two; {@code &&}/{@code ||} do not short-circuit; the multi-branch {@code if} evaluates
 * the else value after every false condition; float-vector operators (JOML) write into one buffer per overload that
 * all call sites share, so {@code (a+b)+(c+d)} sees the aliased value Iris sees.
 * Not reproduced: the vector {@code equal(...)} functions (registered twice with the same signature in Iris, so every
 * call is ambiguous and fails anyway); integer division by zero / randomInt(0) return 0 instead of throwing.
 */
final class Functions {
	private Functions() {}

	/** Evaluation node. Vectors are float[] (vecN, mat3/4 column-major), int[] (ivecN, __ivecN) or boolean[] (__bvecN). */
	abstract static class Node {
		float f() { throw new IllegalStateException(); }

		int i() { throw new IllegalStateException(); }

		boolean b() { throw new IllegalStateException(); }

		Object o() { throw new IllegalStateException(); }
	}

	/** Per-runtime state shared by nodes. */
	static final class Ctx {
		float frameTime;
		final Random random = new Random();
		private final Map<String, float[]> shared = new HashMap<>();

		float[] shared(String key, int size) { return shared.computeIfAbsent(key, k -> new float[size]); }
	}

	interface Impl { Node make(Node[] a, Ctx ctx); }

	record Fn(String name, Type ret, Type[] params, boolean[] constant, int priority, Impl impl) {
		String key() { return Functions.key(name, params) + ret.id; }
	}

	static String key(String name, Type[] params) {
		StringBuilder sb = new StringBuilder(name).append('(');
		for (int i = 0; i < params.length; i++) sb.append(i == 0 ? "" : ",").append(params[i].id);
		return sb.append(')').toString();
	}

	private static final Map<String, List<Fn>> BY_NAME = new LinkedHashMap<>();
	private static final Map<String, Fn> BY_KEY = new HashMap<>();

	static boolean exists(String name) { return BY_NAME.containsKey(name); }

	/** Overloads of name returning ret, in registration order. Throws for unknown names like stareval. */
	static List<Fn> resolve(String name, Type ret) {
		List<Fn> all = BY_NAME.get(name);
		if (all == null) throw new Resolver.ResolveException("No such function: " + name);
		List<Fn> out = new ArrayList<>();
		for (Fn f : all) if (f.ret == ret) out.add(f);
		return out;
	}

	/** The overload with exactly these parameter and return types (floor(float) exists as float and int). */
	static Fn byKey(String name, Type[] params, Type ret) { return BY_KEY.get(key(name, params) + ret.id); }

	static final Map<String, String> OP_SYMBOL = Map.ofEntries(
			Map.entry("multiply", "*"), Map.entry("divide", "/"), Map.entry("remainder", "%"),
			Map.entry("add", "+"), Map.entry("subtract", "-"),
			Map.entry("equals", "=="), Map.entry("notEquals", "!="), Map.entry("lessThan", "<"), Map.entry("moreThan", ">"),
			Map.entry("lessThanOrEquals", "<="), Map.entry("moreThanOrEquals", ">="),
			Map.entry("and", "&&"), Map.entry("or", "||"), Map.entry("negate", "-"), Map.entry("not", "!"));
	static final Map<String, String> BIN_NAME = new HashMap<>();
	static final Map<String, String> UN_NAME = Map.of("-", "negate", "!", "not");

	// ---- registration helpers ----------------------------------------------------------------------------------

	private static void add(String name, Type ret, Type[] params, Impl impl) {
		add(new Fn(name, ret, params, new boolean[params.length], 0, impl));
	}

	private static void add(Fn fn) {
		BY_NAME.computeIfAbsent(fn.name, k -> new ArrayList<>()).add(fn);
		BY_KEY.putIfAbsent(fn.key(), fn);
	}

	interface F1 { float ap(float a); }

	interface F2 { float ap(float a, float b); }

	interface F3 { float ap(float a, float b, float c); }

	interface I1 { int ap(int a); }

	interface I2 { int ap(int a, int b); }

	interface I3 { int ap(int a, int b, int c); }

	interface FF2B { boolean ap(float a, float b); }

	interface FFF2B { boolean ap(float a, float b, float c); }

	interface II2B { boolean ap(int a, int b); }

	interface III2B { boolean ap(int a, int b, int c); }

	interface BB2B { boolean ap(boolean a, boolean b); }

	private static Type[] t(Type... ts) { return ts; }

	private static void f1(String name, F1 op) {
		add(name, FLOAT, t(FLOAT), (a, c) -> new Node() { float f() { return op.ap(a[0].f()); } });
	}

	private static void f2(String name, F2 op) {
		add(name, FLOAT, t(FLOAT, FLOAT), (a, c) -> new Node() {
			float f() { float x = a[0].f(); return op.ap(x, a[1].f()); }
		});
	}

	private static void f3(String name, F3 op) {
		add(name, FLOAT, t(FLOAT, FLOAT, FLOAT), (a, c) -> new Node() {
			float f() { float x = a[0].f(), y = a[1].f(); return op.ap(x, y, a[2].f()); }
		});
	}

	private static Fn i1(String name, I1 op) {
		return new Fn(name, INT, t(INT), new boolean[1], 0, (a, c) -> new Node() { int i() { return op.ap(a[0].i()); } });
	}

	private static Fn i2(String name, I2 op) {
		return new Fn(name, INT, t(INT, INT), new boolean[2], 0, (a, c) -> new Node() {
			int i() { int x = a[0].i(); return op.ap(x, a[1].i()); }
		});
	}

	private static Fn i3(String name, I3 op) {
		return new Fn(name, INT, t(INT, INT, INT), new boolean[3], 0, (a, c) -> new Node() {
			int i() { int x = a[0].i(), y = a[1].i(); return op.ap(x, y, a[2].i()); }
		});
	}

	private static Fn bb2b(String name, BB2B op) {
		return new Fn(name, BOOL, t(BOOL, BOOL), new boolean[2], 0, (a, c) -> new Node() {
			boolean b() { boolean x = a[0].b(); return op.ap(x, a[1].b()); }
		});
	}

	private static Fn ii2b(String name, II2B op) {
		return new Fn(name, BOOL, t(INT, INT), new boolean[2], 0, (a, c) -> new Node() {
			boolean b() { int x = a[0].i(); return op.ap(x, a[1].i()); }
		});
	}

	private static void ff2b(String name, FF2B op) {
		add(name, BOOL, t(FLOAT, FLOAT), (a, c) -> new Node() {
			boolean b() { float x = a[0].f(); return op.ap(x, a[1].f()); }
		});
	}

	/** Iris addVectorizable: scalar + component-wise versions over __ivecN / __bvecN. */
	private static void vectorizable(Fn scalar) {
		add(scalar);
		for (int size = 2; size <= 4; size++) add(vectorized(scalar, size));
	}

	/** Lane node: reads component idx of the array an argument evaluated to (Iris ElementAccessExpression). */
	private static final class Lane extends Node {
		Object vec;
		int idx;

		int i() { return ((int[]) vec)[idx]; }

		boolean b() { return ((boolean[]) vec)[idx]; }
	}

	private static Fn vectorized(Fn scalar, int size) {
		Type[] params = new Type[scalar.params.length];
		for (int p = 0; p < params.length; p++) params[p] = Type.vectorOf(scalar.params[p], size);
		Type ret = Type.vectorOf(scalar.ret, size);
		return new Fn(scalar.name, ret, params, new boolean[params.length], 0, (a, c) -> {
			Lane[] lanes = new Lane[a.length];
			for (int p = 0; p < lanes.length; p++) lanes[p] = new Lane();
			Node inner = scalar.impl.make(lanes, c);
			boolean isInt = scalar.ret == INT;
			int[] oi = isInt ? new int[size] : null;
			boolean[] ob = isInt ? null : new boolean[size];
			return new Node() {
				Object o() {
					for (int p = 0; p < lanes.length; p++) lanes[p].vec = a[p].o();
					for (int j = 0; j < size; j++) {
						for (Lane l : lanes) l.idx = j;
						if (isInt) oi[j] = inner.i();
						else ob[j] = inner.b();
					}
					return isInt ? oi : ob;
				}
			};
		});
	}

	/** Iris addBooleanVectorizable: scalar II→B plus "all components" versions over __ivecN returning bool. */
	private static void booleanVectorizable(Fn scalar) {
		add(scalar);
		for (int size = 2; size <= 4; size++) {
			int sz = size;
			Type v = Type.vectorOf(INT, size);
			add(new Fn(scalar.name, BOOL, t(v, v), new boolean[2], 0, (a, c) -> {
				Lane[] lanes = {new Lane(), new Lane()};
				Node inner = scalar.impl.make(lanes, c);
				return new Node() {
					boolean b() {
						lanes[0].vec = a[0].o();
						lanes[1].vec = a[1].o();
						for (int j = 0; j < sz; j++) {
							lanes[0].idx = j;
							lanes[1].idx = j;
							if (!inner.b()) return false;
						}
						return true;
					}
				};
			}));
		}
	}

	private static final Type[] FVECS = {VEC2, VEC3, VEC4};

	/** JOML unary op: result written into the overload-wide shared buffer. */
	private static void joml1(String name, F1 op) {
		for (Type v : FVECS) {
			String sk = name + v.id;
			add(name, v, t(v), (a, c) -> {
				float[] out = c.shared(sk, v.size);
				return new Node() {
					Object o() {
						float[] x = (float[]) a[0].o();
						for (int j = 0; j < out.length; j++) out[j] = op.ap(x[j]);
						return out;
					}
				};
			});
		}
	}

	private static void joml2(String name, F2 op) {
		for (Type v : FVECS) {
			String sk = name + v.id;
			add(name, v, t(v, v), (a, c) -> {
				float[] out = c.shared(sk, v.size);
				return new Node() {
					Object o() {
						float[] x = (float[]) a[0].o();
						float[] y = (float[]) a[1].o();
						for (int j = 0; j < out.length; j++) out[j] = op.ap(x[j], y[j]);
						return out;
					}
				};
			});
		}
	}

	private static float floorMod(float a, float b) { return (a % b + b) % b; }

	// ---- the table (IrisFunctions static init order) -----------------------------------------------------------

	static {
		OP_SYMBOL.forEach((name, sym) -> { if (!name.equals("negate") && !name.equals("not")) BIN_NAME.put(sym, name); });

		// unary
		vectorizable(i1("negate", x -> -x));
		f1("negate", x -> -x);
		joml1("negate", x -> -x);

		// binary arithmetic
		vectorizable(i2("add", Integer::sum));
		f2("add", Float::sum);
		joml2("add", Float::sum);
		vectorizable(i2("subtract", (x, y) -> x - y));
		f2("subtract", (x, y) -> x - y);
		joml2("subtract", (x, y) -> x - y);
		vectorizable(i2("multiply", (x, y) -> x * y));
		f2("multiply", (x, y) -> x * y);
		joml2("multiply", (x, y) -> x * y);
		f2("divide", (x, y) -> x / y);
		joml2("divide", (x, y) -> x / y);
		vectorizable(i2("remainder", (x, y) -> y == 0 ? 0 : x % y));
		f2("remainder", (x, y) -> x % y);

		// comparisons
		booleanVectorizable(ii2b("equals", (x, y) -> x == y));
		ff2b("equals", (x, y) -> x == y);
		booleanVectorizable(ii2b("notEquals", (x, y) -> x != y));
		ff2b("notEquals", (x, y) -> x != y);
		add(ii2b("lessThanOrEquals", (x, y) -> x <= y));
		ff2b("lessThanOrEquals", (x, y) -> x <= y);
		add(ii2b("moreThanOrEquals", (x, y) -> x >= y));
		ff2b("moreThanOrEquals", (x, y) -> x >= y);
		add(ii2b("lessThan", (x, y) -> x < y));
		ff2b("lessThan", (x, y) -> x < y);
		add(ii2b("moreThan", (x, y) -> x > y));
		ff2b("moreThan", (x, y) -> x > y);

		// boolean
		vectorizable(bb2b("equals", (x, y) -> x == y));
		vectorizable(bb2b("notEquals", (x, y) -> x != y));
		vectorizable(bb2b("and", (x, y) -> x && y));
		vectorizable(bb2b("or", (x, y) -> x || y));
		vectorizable(new Fn("not", BOOL, t(BOOL), new boolean[1], 0, (a, c) -> new Node() { boolean b() { return !a[0].b(); } }));

		// trigonometry
		f1("torad", x -> (float) Math.toRadians(x));
		f1("todeg", x -> (float) Math.toDegrees(x));
		f1("radians", x -> (float) Math.toRadians(x));
		f1("degrees", x -> (float) Math.toDegrees(x));
		f1("sin", x -> (float) Math.sin(x));
		f1("cos", x -> (float) Math.cos(x));
		f1("tan", x -> (float) Math.tan(x));
		f1("asin", x -> (float) Math.asin(x));
		f1("acos", x -> (float) Math.acos(x));
		f1("atan", x -> (float) Math.atan(x));
		f2("atan", (y, x) -> (float) Math.atan2(y, x));
		f2("atan2", (y, x) -> (float) Math.atan2(y, x));

		// exponential
		f2("pow", (x, y) -> (float) Math.pow(x, y));
		f1("exp", x -> (float) Math.exp(x));
		f1("log", x -> (float) Math.log(x));
		f1("exp2", x -> (float) Math.pow(2, x));
		f1("log2", x -> (float) (Math.log(x) / Math.log(2)));
		f1("sqrt", x -> (float) Math.sqrt(x));
		f1("log10", x -> (float) Math.log10(x));
		f2("log", (base, x) -> (float) (Math.log(x) / Math.log(base)));
		f1("exp10", x -> (float) Math.pow(10, x));

		// common
		vectorizable(i1("abs", Math::abs));
		f1("abs", Math::abs);
		joml1("abs", Math::abs);
		f1("sign", Math::signum);
		f1("signum", Math::signum);
		f1("floor", x -> (float) Math.floor(x));
		add("floor", INT, t(FLOAT), (a, c) -> new Node() { int i() { return (int) Math.floor(a[0].f()); } });
		joml1("floor", x -> (float) Math.floor(x));
		f1("ceil", x -> (float) Math.ceil(x));
		add("ceil", INT, t(FLOAT), (a, c) -> new Node() { int i() { return (int) Math.ceil(a[0].f()); } });
		joml1("ceil", x -> (float) Math.ceil(x));
		f1("frac", x -> (float) (x - Math.floor(x)));

		vectorizable(i2("min", Math::min));
		f2("min", Math::min);
		joml2("min", Math::min);
		vectorizable(i2("max", Math::max));
		f2("max", Math::max);
		joml2("max", Math::max);
		for (int len = 3; len <= 16; len++) {
			Type[] fs = new Type[len];
			Arrays.fill(fs, FLOAT);
			Type[] is = new Type[len];
			Arrays.fill(is, INT);
			for (boolean isMax : new boolean[]{false, true}) {
				String name = isMax ? "max" : "min";
				// quirk: Iris re-evaluates params[1] in the loop, so only the first two arguments count
				add(name, FLOAT, fs, (a, c) -> new Node() {
					float f() {
						float m = a[0].f();
						for (int j = 1; j < a.length; j++) m = isMax ? Math.max(m, a[1].f()) : Math.min(m, a[1].f());
						return m;
					}
				});
			}
			for (boolean isMax : new boolean[]{false, true}) {
				String name = isMax ? "max" : "min";
				vectorizable(new Fn(name, INT, is, new boolean[len], 0, (a, c) -> new Node() {
					int i() {
						int m = a[0].i();
						for (int j = 1; j < a.length; j++) m = isMax ? Math.max(m, a[1].i()) : Math.min(m, a[1].i());
						return m;
					}
				}));
			}
		}

		vectorizable(i3("clamp", (v, lo, hi) -> Math.max(lo, Math.min(hi, v))));
		f3("clamp", (v, lo, hi) -> Math.max(lo, Math.min(hi, v)));
		for (Type v : FVECS) {
			String sk = "clamp" + v.id;
			add("clamp", v, t(v, v, v), (a, c) -> {
				float[] out = c.shared(sk, v.size);
				return new Node() {
					Object o() {
						float[] x = (float[]) a[0].o();
						float[] lo = (float[]) a[1].o();
						float[] hi = (float[]) a[2].o();
						for (int j = 0; j < out.length; j++) out[j] = Math.min(x[j], hi[j]);
						for (int j = 0; j < out.length; j++) out[j] = Math.max(out[j], lo[j]);
						return out;
					}
				};
			});
		}
		f3("mix", (x, y, w) -> x + (y - x) * w);
		vectorizable(i2("edge", (e, x) -> x < e ? 0 : 1));
		f2("edge", (e, x) -> x < e ? 0 : 1);

		vectorizable(i2("fmod", (x, y) -> y == 0 ? 0 : Math.floorMod(x, y)));
		f2("fmod", Functions::floorMod);

		vectorizable(new Fn("randomInt", INT, t(), new boolean[0], 0, (a, c) -> new Node() { int i() { return c.random.nextInt(); } }));
		vectorizable(new Fn("randomInt", INT, t(INT), new boolean[1], 0, (a, c) -> new Node() {
			int i() { int bound = a[0].i(); return bound <= 0 ? 0 : c.random.nextInt(bound); }
		}));
		vectorizable(new Fn("randomInt", INT, t(INT, INT), new boolean[2], 0, (a, c) -> new Node() {
			int i() { int lo = a[0].i(), hi = a[1].i(); return hi - lo <= 0 ? 0 : c.random.nextInt(hi - lo) + lo; }
		}));
		add("random", FLOAT, t(), (a, c) -> new Node() { float f() { return c.random.nextFloat(); } });
		f2Random();

		// if(bool, T, T) for primitives, then all vector types, then the fake-vararg chains
		for (Type type : new Type[]{BOOL, INT, FLOAT}) add("if", type, t(BOOL, type, type), Functions::if3);
		for (Type prim : new Type[]{BOOL, INT, FLOAT})
			for (int size = 2; size <= 4; size++) {
				Type v = Type.vectorOf(prim, size);
				add("if", v, t(BOOL, v, v), Functions::if3);
			}
		for (int len = 2; len <= 16; len++)
			for (Type type : new Type[]{BOOL, INT, FLOAT}) {
				Type[] ps = new Type[len * 2 + 1];
				for (int j = 0; j < len * 2; j += 2) {
					ps[j] = BOOL;
					ps[j + 1] = type;
				}
				ps[len * 2] = type;
				add("if", type, ps, (a, c) -> ifChain(a, type));
			}

		// smooth: (target) 0, (id, target) 1, (target, fade) 0, (id, target, fade) 1, (target, up, down) 0, (id, target, up, down) 1
		smooth(t(FLOAT), new boolean[]{false}, 0);
		smooth(t(FLOAT, FLOAT), new boolean[]{true, false}, 1);
		smooth(t(FLOAT, FLOAT), new boolean[]{false, false}, 0);
		smooth(t(FLOAT, FLOAT, FLOAT), new boolean[]{true, false, false}, 1);
		smooth(t(FLOAT, FLOAT, FLOAT), new boolean[]{false, false, false}, 0);
		smooth(t(FLOAT, FLOAT, FLOAT, FLOAT), new boolean[]{true, false, false, false}, 1);

		// casts
		add("<cast>", FLOAT, t(INT), Functions::castIF);
		add("toFloat", FLOAT, t(INT), Functions::castIF);
		add("toInt", INT, t(FLOAT), (a, c) -> new Node() { int i() { return (int) a[0].f(); } });

		// boolean functions
		add("between", BOOL, t(INT, INT, INT), (a, c) -> new Node() {
			boolean b() { int x = a[0].i(), lo = a[1].i(), hi = a[2].i(); return x >= lo && x <= hi; }
		});
		add("between", BOOL, t(FLOAT, FLOAT, FLOAT), (a, c) -> new Node() {
			boolean b() { float x = a[0].f(), lo = a[1].f(), hi = a[2].f(); return x >= lo && x <= hi; }
		});
		add("equals", BOOL, t(FLOAT, FLOAT, FLOAT), (a, c) -> new Node() {
			boolean b() { float x = a[0].f(), y = a[1].f(), eps = a[2].f(); return Math.abs(x - y) <= eps; }
		});
		for (int len = 2; len <= 32; len++) {
			Type[] ps = new Type[len];
			Arrays.fill(ps, FLOAT);
			add("in", BOOL, ps, (a, c) -> new Node() {
				boolean b() {
					float v = a[0].f();
					for (int j = 1; j < a.length; j++) if (a[j].f() == v) return true;
					return false;
				}
			});
		}

		// vector constructors: bvecN, ivecN (array vectors), then vecN (JOML)
		for (Type prim : new Type[]{BOOL, INT})
			for (int size = 2; size <= 4; size++) {
				Type v = Type.vectorOf(prim, size);
				Type[] ps = new Type[size];
				Arrays.fill(ps, prim);
				add((prim == BOOL ? "bvec" : "ivec") + size, v, ps, (a, c) -> {
					if (prim == INT) {
						int[] out = new int[a.length];
						return new Node() { Object o() { for (int j = 0; j < out.length; j++) out[j] = a[j].i(); return out; } };
					}
					boolean[] out = new boolean[a.length];
					return new Node() { Object o() { for (int j = 0; j < out.length; j++) out[j] = a[j].b(); return out; } };
				});
			}
		for (Type v : FVECS) {
			Type[] ps = new Type[v.size];
			Arrays.fill(ps, FLOAT);
			add("vec" + v.size, v, ps, (a, c) -> {
				float[] out = new float[a.length];
				return new Node() { Object o() { for (int j = 0; j < out.length; j++) out[j] = a[j].f(); return out; } };
			});
		}

		// accessors: <access$x> etc. on JOML vectors, and matrix columns
		String[][] names = {{"0", "r", "x", "s"}, {"1", "g", "y", "t"}, {"2", "b", "z", "p"}, {"3", "a", "w", "q"}};
		for (int comp = 0; comp < 4; comp++)
			for (String n : names[comp]) {
				int idx = comp;
				for (int size = Math.max(2, comp + 1); size <= 4; size++) {
					Type fv = FVECS[size - 2];
					Type iv = new Type[]{IVEC2, IVEC3, IVEC4}[size - 2];
					add("<access$" + n + ">", FLOAT, t(fv), (a, c) -> new Node() { float f() { return ((float[]) a[0].o())[idx]; } });
					add("<access$" + n + ">", INT, t(iv), (a, c) -> new Node() { int i() { return ((int[]) a[0].o())[idx]; } });
				}
			}
		for (int col = 0; col < 4; col++)
			for (String n : names[col]) {
				int cc = col;
				add("<access$" + n + ">", VEC4, t(MAT4), (a, c) -> {
					float[] out = new float[4];
					return new Node() {
						Object o() { System.arraycopy((float[]) a[0].o(), cc * 4, out, 0, 4); return out; }
					};
				});
			}
	}

	private static void f2Random() {
		add("random", FLOAT, t(FLOAT, FLOAT), (a, c) -> new Node() {
			float f() { float lo = a[0].f(), hi = a[1].f(); return lo + c.random.nextFloat() * (hi - lo); }
		});
	}

	private static Node castIF(Node[] a, Ctx c) {
		return new Node() { float f() { return a[0].i(); } };
	}

	private static Node if3(Node[] a, Ctx c) {
		return new Node() {
			float f() { return a[0].b() ? a[1].f() : a[2].f(); }

			int i() { return a[0].b() ? a[1].i() : a[2].i(); }

			boolean b() { return a[0].b() ? a[1].b() : a[2].b(); }

			Object o() { return a[0].b() ? a[1].o() : a[2].o(); }
		};
	}

	/** Iris fake-vararg if: the else value is evaluated after every false condition (only the last one counts). */
	private static Node ifChain(Node[] a, Type type) {
		int last = a.length - 1;
		return new Node() {
			float f() {
				float r = 0;
				for (int j = 0; j < last; j += 2) {
					if (a[j].b()) return a[j + 1].f();
					r = a[last].f();
				}
				return r;
			}

			int i() {
				int r = 0;
				for (int j = 0; j < last; j += 2) {
					if (a[j].b()) return a[j + 1].i();
					r = a[last].i();
				}
				return r;
			}

			boolean b() {
				boolean r = false;
				for (int j = 0; j < last; j += 2) {
					if (a[j].b()) return a[j + 1].b();
					r = a[last].b();
				}
				return r;
			}
		};
	}

	/** Smooth overloads exist only for resolution; the runtime builds {@link SmoothNode} from the normalised AST. */
	private static void smooth(Type[] params, boolean[] constant, int priority) {
		add(new Fn("smooth", FLOAT, params, constant, priority, (a, c) -> { throw new IllegalStateException("smooth"); }));
	}

	/**
	 * Iris SmoothFloat: exponential smoothing with Δt = frameTime. A fade time t becomes the half-life 0.1·t seconds,
	 * decay k = ln2 / halfLife; 0 means "jump". The first value is taken as is.
	 */
	static final class SmoothState {
		private static final double LN_OF_2 = Math.log(2.0);
		float accumulator;
		boolean hasInitialValue;
		private float cachedHalfLifeUp, cachedDecayUp, cachedHalfLifeDown, cachedDecayDown;

		float update(float value, float halfLifeUp, float halfLifeDown, float frameTime) {
			if (halfLifeUp != cachedHalfLifeUp) {
				cachedHalfLifeUp = halfLifeUp;
				cachedDecayUp = halfLifeUp == 0.0f ? 0 : decay(halfLifeUp * 0.1F);
			}
			if (halfLifeDown != cachedHalfLifeDown) {
				cachedHalfLifeDown = halfLifeDown;
				cachedDecayDown = halfLifeDown == 0.0f ? 0 : decay(halfLifeDown * 0.1F);
			}
			if (!hasInitialValue) {
				accumulator = value;
				hasInitialValue = true;
				return accumulator;
			}
			float decay = value > accumulator ? cachedDecayUp : cachedDecayDown;
			if (decay == 0.0f) {
				accumulator = value;
				return accumulator;
			}
			float alpha = 1.0f - (float) Math.exp(-decay * frameTime);
			accumulator = (1 - alpha) * accumulator + alpha * value;
			return accumulator;
		}

		private static float decay(float halfLife) { return (float) (1.0f / (halfLife / LN_OF_2)); }
	}

	/** args: [target], [target, fade] or [target, up, down] (the optional user id is already dropped). */
	static Node smoothNode(Node[] a, SmoothState state, Ctx ctx) {
		return new Node() {
			float f() {
				float target = a[0].f();
				float up = 1, down = 1;
				if (a.length == 2) up = down = a[1].f();
				else if (a.length == 3) { up = a[1].f(); down = a[2].f(); }
				return state.update(target, up, down, ctx.frameTime);
			}
		};
	}
}
