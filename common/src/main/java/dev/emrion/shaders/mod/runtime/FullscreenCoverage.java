package dev.emrion.shaders.mod.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Proves from a fullscreen pass's vertex SPIR-V that Iris's quad ({@code vkCmdDraw(6)}, two triangles) covers the whole
 * viewport: the entry point is interpreted once per vertex index, anything it cannot evaluate (uniforms, unsupported
 * instructions, branches on unknown values that may store) makes the result unknown, and unknown is "not covered".
 * Packs move the quad from its UVs (bloom tiles), so a copy optimisation must not assume full coverage without this.
 */
final class FullscreenCoverage {
	private FullscreenCoverage() {}

	static final int VERTICES = 6;
	private static final int STEP_LIMIT = 200_000;

	/** True only if every vertex position is known and the two triangles cover NDC [-1,1]² inside the depth range. */
	static boolean covers(byte[] spv) {
		if (spv == null || spv.length < 20) return false;
		try {
			var m = new Module(spv);
			double[][] ndc = new double[VERTICES][];
			for (int v = 0; v < VERTICES; v++) {
				double[] p = m.position(v);
				if (p == null) return false;
				for (double c : p) if (Double.isNaN(c)) return false;
				if (!(p[3] > 0) || p[2] < 0 || p[2] > p[3]) return false;
				ndc[v] = new double[] {p[0] / p[3], p[1] / p[3]};
			}
			return coversSquare(new double[][] {ndc[0], ndc[1], ndc[2]}, new double[][] {ndc[3], ndc[4], ndc[5]});
		} catch (RuntimeException e) {
			return false; // malformed or unexpected module: unproven
		}
	}

	/** Exact: area(square ∩ (a ∪ b)) == 4, the union's area by inclusion-exclusion of convex clips. */
	static boolean coversSquare(double[][] a, double[][] b) {
		double[][] sq = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
		double[][] ca = clip(sq, a), cb = clip(sq, b), cab = clip(ca, b);
		return area(ca) + area(cb) - area(cab) > 4 - 1e-6;
	}

	private static double side(double[] p, double[] q, double[] r) {
		return (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0]);
	}

	/** Sutherland-Hodgman: convex {@code poly} clipped to triangle {@code t} (either winding). */
	private static double[][] clip(double[][] poly, double[][] t) {
		double o = Math.signum(side(t[0], t[1], t[2]));
		if (o == 0) return new double[0][];
		for (int e = 0; e < 3 && poly.length > 0; e++) {
			double[] p = t[e], q = t[(e + 1) % 3];
			List<double[]> out = new ArrayList<>();
			for (int i = 0; i < poly.length; i++) {
				double[] cur = poly[i], prev = poly[(i + poly.length - 1) % poly.length];
				double dc = o * side(p, q, cur), dp = o * side(p, q, prev);
				if ((dc >= 0) != (dp >= 0)) {
					double s = dp / (dp - dc);
					out.add(new double[] {prev[0] + s * (cur[0] - prev[0]), prev[1] + s * (cur[1] - prev[1])});
				}
				if (dc >= 0) out.add(cur);
			}
			poly = out.toArray(double[][]::new);
		}
		return poly;
	}

	private static double area(double[][] poly) {
		double a = 0;
		for (int i = 0; i < poly.length; i++) {
			double[] p = poly[i], q = poly[(i + 1) % poly.length];
			a += p[0] * q[1] - q[0] * p[1];
		}
		return Math.abs(a) / 2;
	}

	// ---- interpreter ------------------------------------------------------------------------------------------------

	// SPIR-V opcodes
	private static final int ENTRY_POINT = 15, TYPE_BOOL = 20, TYPE_INT = 21, TYPE_FLOAT = 22, TYPE_VECTOR = 23,
			TYPE_MATRIX = 24, TYPE_ARRAY = 28, TYPE_RUNTIME_ARRAY = 29, TYPE_STRUCT = 30, TYPE_POINTER = 32, CONSTANT_TRUE = 41,
			CONSTANT_FALSE = 42, CONSTANT = 43, CONSTANT_COMPOSITE = 44, CONSTANT_NULL = 46, FUNCTION = 54, FUNCTION_END = 56,
			FUNCTION_CALL = 57, VARIABLE = 59, LOAD = 61, STORE = 62, COPY_MEMORY = 63, COPY_MEMORY_SIZED = 64, ACCESS_CHAIN = 65,
			IN_BOUNDS_ACCESS_CHAIN = 66, DECORATE = 71, MEMBER_DECORATE = 72, VECTOR_SHUFFLE = 79, COMPOSITE_CONSTRUCT = 80,
			COMPOSITE_EXTRACT = 81, COMPOSITE_INSERT = 82, COPY_OBJECT = 83, PHI = 245, LOOP_MERGE = 246, SELECTION_MERGE = 247,
			LABEL = 248, BRANCH = 249, BRANCH_CONDITIONAL = 250, SWITCH = 251, RETURN = 253, RETURN_VALUE = 254;
	private static final int BUILTIN = 11, POSITION = 0, VERTEX_INDEX = 42, INSTANCE_INDEX = 43, BASE_VERTEX = 4424, BASE_INSTANCE = 4425;
	private static final int INPUT = 1, OUTPUT = 3, PRIVATE = 6;
	/** Instructions without a result id that cannot write a shader output: ignored. */
	private static final Set<Integer> NO_RESULT = Set.of(0, 8, 317, 3, 4, 5, 6, 7, 10, 11, 14, 16, 17, 71, 72, 73, 74, 75, 99, 224, 225,
			331, 332);

	private record Ptr(int root, int offset, int type) {}

	private static final class Module {
		final Map<Integer, int[]> types = new HashMap<>(), consts = new HashMap<>();
		final Map<Integer, Integer> builtin = new HashMap<>(), sizes = new HashMap<>();
		final Map<Long, Integer> memberBuiltin = new HashMap<>();
		final List<int[]> globals = new ArrayList<>(), body = new ArrayList<>();
		final Map<Integer, Integer> labels = new HashMap<>(), chainBase = new HashMap<>();
		final Map<Integer, double[]> constValues = new HashMap<>();
		int positionVar = -1, positionOffset;

		Module(byte[] spv) {
			IntBuffer w = ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
			if (w.get(0) != 0x07230203) throw new IllegalArgumentException("not SPIR-V");
			int entry = -1;
			boolean inEntry = false;
			for (int i = 5; i < w.limit(); ) {
				int op = w.get(i) & 0xFFFF, len = w.get(i) >>> 16;
				if (len == 0 || i + len > w.limit()) throw new IllegalArgumentException("malformed");
				int[] ins = new int[len];
				w.get(i, ins);
				ins[0] = op; // word count dropped: d[0] is the opcode below
				i += len;
				switch (op) {
					case ENTRY_POINT -> {
						if (ins[1] != 0 || entry >= 0) throw new IllegalArgumentException("not one vertex entry point");
						entry = ins[2];
					}
					case DECORATE -> {
						if (ins[2] == BUILTIN) builtin.put(ins[1], ins[3]);
					}
					case MEMBER_DECORATE -> {
						if (ins[3] == BUILTIN) memberBuiltin.put((long) ins[1] << 32 | ins[2], ins[4]);
					}
					case FUNCTION -> inEntry = ins[2] == entry;
					case FUNCTION_END -> inEntry = false;
					default -> {
						if (inEntry) {
							if (op == LABEL) labels.put(ins[1], body.size());
							if (op == ACCESS_CHAIN || op == IN_BOUNDS_ACCESS_CHAIN) chainBase.put(ins[2], ins[3]);
							body.add(ins);
						} else if (op >= 19 && op <= 39) types.put(ins[1], ins);
						else if (op >= CONSTANT_TRUE && op <= 52) consts.put(ins[2], ins);
						else if (op == VARIABLE) globals.add(ins);
					}
				}
			}
			if (entry < 0 || body.isEmpty()) throw new IllegalArgumentException("no entry point body");
			for (int[] v : globals) {
				if (v[3] != OUTPUT) continue;
				int t = types.get(v[1])[3];
				if (builtin.getOrDefault(v[2], -1) == POSITION) {
					positionVar = v[2];
					positionOffset = 0;
				} else if (types.get(t)[0] == TYPE_STRUCT) {
					for (int m = 0; m + 2 < types.get(t).length; m++) {
						if (memberBuiltin.getOrDefault((long) t << 32 | m, -1) == POSITION) {
							positionVar = v[2];
							positionOffset = child(t, m)[0];
						}
					}
				}
			}
			if (positionVar < 0) throw new IllegalArgumentException("no gl_Position");
		}

		// ---- types: values are flattened to doubles (NaN = unknown), ints keep their value per signedness ----

		int size(int t) {
			Integer s = sizes.get(t);
			if (s != null) return s;
			int[] d = types.get(t);
			int r = switch (d == null ? -1 : d[0]) {
				case TYPE_VECTOR, TYPE_MATRIX -> d[3] * size(d[2]);
				case TYPE_ARRAY -> (int) constant(d[3])[0] * size(d[2]);
				case TYPE_RUNTIME_ARRAY -> 0;
				case TYPE_STRUCT -> {
					int sum = 0;
					for (int k = 2; k < d.length; k++) sum += size(d[k]);
					yield sum;
				}
				default -> 1;
			};
			sizes.put(t, r);
			return r;
		}

		/** {offset, type} of element/member {@code i} of composite type {@code t}; null if out of range. */
		int[] child(int t, int i) {
			int[] d = types.get(t);
			switch (d[0]) {
				case TYPE_VECTOR, TYPE_MATRIX, TYPE_ARRAY -> {
					int n = d[0] == TYPE_ARRAY ? (int) constant(d[3])[0] : d[3];
					return i < 0 || i >= n ? null : new int[] {i * size(d[2]), d[2]};
				}
				case TYPE_STRUCT -> {
					if (i < 0 || i + 2 >= d.length) return null;
					int off = 0;
					for (int k = 2; k < i + 2; k++) off += size(d[k]);
					return new int[] {off, d[i + 2]};
				}
				default -> {
					return null;
				}
			}
		}

		int scalar(int t) {
			int[] d = types.get(t);
			return d != null && (d[0] == TYPE_VECTOR || d[0] == TYPE_MATRIX) ? scalar(d[2]) : t;
		}

		boolean isFloat(int t) {
			int[] d = types.get(scalar(t));
			return d != null && d[0] == TYPE_FLOAT;
		}

		boolean signed(int t) {
			int[] d = types.get(scalar(t));
			return d != null && d[0] == TYPE_INT && d[3] == 1;
		}

		boolean is32(int t) {
			int[] d = types.get(scalar(t));
			return d == null || d[0] == TYPE_BOOL || d[2] == 32;
		}

		double[] constant(int id) {
			double[] c = constValues.get(id);
			if (c != null) return c;
			int[] d = consts.get(id);
			if (d == null) throw new IllegalArgumentException("unknown constant");
			int t = d[1];
			c = switch (d[0]) {
				case CONSTANT_TRUE -> new double[] {1};
				case CONSTANT_FALSE -> new double[] {0};
				case CONSTANT -> new double[] {!is32(t) ? Double.NaN
						: isFloat(t) ? Float.intBitsToFloat(d[3]) : signed(t) ? d[3] : Integer.toUnsignedLong(d[3])};
				case CONSTANT_COMPOSITE -> {
					double[] r = new double[0];
					for (int k = 3; k < d.length; k++) r = concat(r, constant(d[k]));
					yield r;
				}
				case CONSTANT_NULL -> new double[size(t)];
				default -> nan(size(t)); // spec constants: may be overridden
			};
			constValues.put(id, c);
			return c;
		}

		// ---- execution ----

		/** gl_Position for vertex index {@code vertex}, or null if it could not be evaluated. */
		double[] position(int vertex) {
			Map<Integer, double[]> vals = new HashMap<>(), mem = new HashMap<>();
			Map<Integer, Ptr> ptrs = new HashMap<>();
			for (int[] v : globals) {
				int pointee = types.get(v[1])[3];
				ptrs.put(v[2], new Ptr(v[2], 0, pointee));
				int b = builtin.getOrDefault(v[2], -1);
				if (v[3] == INPUT) mem.put(v[2], b == VERTEX_INDEX ? new double[] {vertex}
						: b == INSTANCE_INDEX || b == BASE_VERTEX || b == BASE_INSTANCE ? new double[1] : nan(size(pointee)));
				else if (v[3] == OUTPUT || v[3] == PRIVATE) mem.put(v[2], v.length > 4 ? constant(v[4]).clone() : nan(size(pointee)));
				// other storage classes (uniforms, buffers): not in mem, loads give unknown
			}
			int pc = 0, block = body.get(0)[1], prev = -1, merge = -1;
			for (int steps = 0; steps < STEP_LIMIT; steps++) {
				int[] ins = body.get(pc++);
				int op = ins[0] & 0xFFFF;
				switch (op) {
					case LABEL -> {
						prev = block;
						block = ins[1];
					}
					case RETURN, RETURN_VALUE -> {
						double[] m = mem.get(positionVar);
						return Arrays.copyOfRange(m, positionOffset, positionOffset + 4);
					}
					case SELECTION_MERGE, LOOP_MERGE -> merge = ins[1];
					case BRANCH -> {
						pc = labels.get(ins[1]);
						merge = -1;
					}
					case BRANCH_CONDITIONAL, SWITCH -> {
						double sel = val(vals, ins[1])[0];
						int target;
						if (Double.isNaN(sel)) {
							// unknown: skip the construct, forgetting whatever it may store
							if (merge < 0 || !taint(ins, merge, mem)) return null;
							target = merge;
							block = -1; // phis in the merge block: predecessor unknown
						} else if (op == BRANCH_CONDITIONAL) target = sel != 0 ? ins[2] : ins[3];
						else {
							target = ins[2];
							for (int k = 3; k + 1 < ins.length; k += 2) if (ins[k] == (int) (long) sel) target = ins[k + 1];
						}
						pc = labels.get(target);
						merge = -1;
					}
					case PHI -> {
						double[] r = null;
						for (int k = 3; k + 1 < ins.length; k += 2) if (ins[k + 1] == prev) r = val(vals, ins[k]);
						vals.put(ins[2], r != null ? r : nan(size(ins[1])));
					}
					case VARIABLE -> {
						int pointee = types.get(ins[1])[3];
						mem.put(ins[2], ins.length > 4 ? val(vals, ins[4]).clone() : nan(size(pointee)));
						ptrs.put(ins[2], new Ptr(ins[2], 0, pointee));
					}
					case ACCESS_CHAIN, IN_BOUNDS_ACCESS_CHAIN -> {
						Ptr base = ptrs.get(ins[3]);
						if (base == null) return null;
						int off = base.offset, t = base.type;
						for (int k = 4; k < ins.length && off >= 0; k++) {
							double idx = val(vals, ins[k])[0];
							int[] c = Double.isNaN(idx) ? null : child(t, (int) idx);
							if (c == null) off = -1;
							else {
								off += c[0];
								t = c[1];
							}
						}
						ptrs.put(ins[2], new Ptr(base.root, off, off < 0 ? -1 : t));
					}
					case LOAD -> {
						Ptr p = ptrs.get(ins[3]);
						double[] m = p == null ? null : mem.get(p.root);
						int n = size(ins[1]);
						vals.put(ins[2], m == null || p.offset < 0 || p.offset + n > m.length ? nan(n) : Arrays.copyOfRange(m, p.offset, p.offset + n));
					}
					case STORE -> {
						Ptr p = ptrs.get(ins[1]);
						if (p == null) return null;
						double[] m = mem.get(p.root);
						if (m == null) continue; // uniform/buffer memory: not an output
						if (p.offset < 0) Arrays.fill(m, Double.NaN);
						else {
							double[] v = val(vals, ins[2]);
							if (p.offset + v.length > m.length) Arrays.fill(m, Double.NaN);
							else System.arraycopy(v, 0, m, p.offset, v.length);
						}
					}
					case FUNCTION_CALL, COPY_MEMORY, COPY_MEMORY_SIZED -> {
						return null; // may write anything (optimised modules inline their functions)
					}
					default -> {
						if (op >= 227 && op <= 242 || op == 252 || op == 255 || op == 4416 || op == 5380) return null; // atomics, kill, unreachable
						if (NO_RESULT.contains(op) || ins.length < 3 || !types.containsKey(ins[1])) continue;
						double[] r = eval(op, ins, vals);
						vals.put(ins[2], r != null ? r : nan(size(ins[1])));
					}
				}
			}
			return null;
		}

		/** Unknown branch: forget every root variable stored in the blocks reachable from its targets before {@code merge}. */
		private boolean taint(int[] branch, int merge, Map<Integer, double[]> mem) {
			ArrayDeque<Integer> todo = new ArrayDeque<>();
			Set<Integer> seen = new HashSet<>();
			if ((branch[0] & 0xFFFF) == BRANCH_CONDITIONAL) {
				todo.add(branch[2]);
				todo.add(branch[3]);
			} else {
				todo.add(branch[2]);
				for (int k = 4; k < branch.length; k += 2) todo.add(branch[k]);
			}
			while (!todo.isEmpty()) {
				int label = todo.poll();
				if (label == merge || !seen.add(label)) continue;
				for (int i = labels.get(label) + 1; i < body.size(); i++) {
					int[] ins = body.get(i);
					int op = ins[0] & 0xFFFF;
					if (op == STORE) {
						int root = ins[1];
						while (chainBase.containsKey(root)) root = chainBase.get(root);
						double[] m = mem.get(root);
						if (m != null) Arrays.fill(m, Double.NaN);
					} else if (op == FUNCTION_CALL || op == COPY_MEMORY || op == COPY_MEMORY_SIZED || op >= 227 && op <= 242) return false;
					else if (op == BRANCH) todo.add(ins[1]);
					else if (op == BRANCH_CONDITIONAL) {
						todo.add(ins[2]);
						todo.add(ins[3]);
					} else if (op == SWITCH) {
						todo.add(ins[2]);
						for (int k = 4; k < ins.length; k += 2) todo.add(ins[k]);
					}
					if (op == BRANCH || op == BRANCH_CONDITIONAL || op == SWITCH || op == RETURN || op == RETURN_VALUE || op == LABEL) break;
				}
			}
			return true;
		}

		/** The value of {@code id}; unknown (NaN) if it was never computed (skipped block). */
		private double[] val(Map<Integer, double[]> vals, int id) {
			double[] v = vals.get(id);
			if (v == null && consts.containsKey(id)) v = constant(id);
			if (v != null) return v;
			int t = typeOf(id);
			return nan(t < 0 ? 1 : size(t));
		}

		/** The value of a pure instruction, or null (unknown) if not supported. */
		private double[] eval(int op, int[] ins, Map<Integer, double[]> vals) {
			int t = ins[1], n = size(t);
			boolean f = isFloat(t), s = signed(t);
			switch (op) {
				case COPY_OBJECT -> {
					return val(vals, ins[3]);
				}
				case COMPOSITE_CONSTRUCT -> {
					double[] r = new double[0];
					for (int k = 3; k < ins.length; k++) r = concat(r, val(vals, ins[k]));
					return r.length == n ? r : null;
				}
				case COMPOSITE_EXTRACT, COMPOSITE_INSERT -> {
					boolean insert = op == COMPOSITE_INSERT;
					int src = insert ? ins[4] : ins[3];
					int ct = insert ? t : typeOf(src);
					if (ct < 0) return null;
					int off = 0;
					for (int k = insert ? 5 : 4; k < ins.length; k++) {
						int[] c = child(ct, ins[k]);
						if (c == null) return null;
						off += c[0];
						ct = c[1];
					}
					double[] whole = val(vals, src);
					if (!insert) return off + n <= whole.length ? Arrays.copyOfRange(whole, off, off + n) : null;
					double[] r = whole.clone(), part = val(vals, ins[3]);
					if (off + part.length > r.length) return null;
					System.arraycopy(part, 0, r, off, part.length);
					return r;
				}
				case VECTOR_SHUFFLE -> {
					double[] a = val(vals, ins[3]), b = val(vals, ins[4]);
					double[] r = new double[ins.length - 5];
					for (int k = 0; k < r.length; k++) {
						int c = ins[5 + k];
						r[k] = c < 0 || c >= a.length + b.length ? Double.NaN : c < a.length ? a[c] : b[c - a.length];
					}
					return r;
				}
				case 142, 143 -> { // vector/matrix times scalar
					double[] a = val(vals, ins[3]), r = new double[n];
					double k = val(vals, ins[4])[0];
					if (a.length != n) return null;
					for (int i = 0; i < n; i++) r[i] = f32(a[i] * k);
					return r;
				}
				case 145 -> { // matrix times vector: column-major, result rows = n
					double[] v = val(vals, ins[4]), m = val(vals, ins[3]), r = new double[n];
					if (m.length != n * v.length) return null;
					for (int i = 0; i < n; i++) {
						double sum = 0;
						for (int c = 0; c < v.length; c++) sum += m[c * n + i] * v[c];
						r[i] = f32(sum);
					}
					return r;
				}
				case 144 -> { // vector times matrix
					double[] v = val(vals, ins[3]), m = val(vals, ins[4]), r = new double[n];
					if (m.length != n * v.length) return null;
					for (int c = 0; c < n; c++) {
						double sum = 0;
						for (int i = 0; i < v.length; i++) sum += v[i] * m[c * v.length + i];
						r[c] = f32(sum);
					}
					return r;
				}
				case 148 -> { // dot
					double[] a = val(vals, ins[3]), b = val(vals, ins[4]);
					if (a.length != b.length) return null;
					double sum = 0;
					for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
					return new double[] {f32(sum)};
				}
				case 169 -> { // select
					double[] c = val(vals, ins[3]), a = val(vals, ins[4]), b = val(vals, ins[5]), r = new double[n];
					if (a.length != n || b.length != n) return null;
					for (int i = 0; i < n; i++) {
						double ci = c[c.length == 1 ? 0 : Math.min(i, c.length - 1)];
						r[i] = Double.isNaN(ci) ? Double.NaN : ci != 0 ? a[i] : b[i];
					}
					return r;
				}
				default -> {}
			}
			if (!is32(t)) return null;
			// component-wise unary and binary operations
			boolean unary = switch (op) {
				case 109, 110, 111, 112, 126, 127, 168, 200 -> true;
				default -> false;
			};
			if (!unary && !(op >= 128 && op <= 136 || op >= 164 && op <= 199)) return null;
			double[] a = val(vals, ins[3]), b = unary ? a : val(vals, ins[4]), r = new double[n];
			if (a.length != n || b.length != n) return null;
			for (int i = 0; i < n; i++) {
				double x = a[i], y = b[i];
				if (Double.isNaN(x) || Double.isNaN(y)) {
					r[i] = Double.NaN;
					continue;
				}
				int ix = (int) (long) x, iy = (int) (long) y;
				double v = switch (op) {
					case 109 -> Integer.toUnsignedLong((int) (long) x); // FToU (truncates)
					case 110 -> (int) x; // FToS
					case 111 -> f32(ix); // SToF
					case 112 -> f32(Integer.toUnsignedLong(ix)); // UToF
					case 126 -> -ix;
					case 127 -> -x;
					case 168 -> x == 0 ? 1 : 0;
					case 200 -> ~ix;
					case 128 -> ix + iy;
					case 129 -> f32(x + y);
					case 130 -> ix - iy;
					case 131 -> f32(x - y);
					case 132 -> ix * iy;
					case 133 -> f32(x * y);
					case 134 -> iy == 0 ? Double.NaN : Integer.divideUnsigned(ix, iy);
					case 135 -> iy == 0 ? Double.NaN : ix / iy;
					case 136 -> f32(x / y);
					case 164 -> (x != 0) == (y != 0) ? 1 : 0;
					case 165 -> (x != 0) != (y != 0) ? 1 : 0;
					case 166 -> x != 0 || y != 0 ? 1 : 0;
					case 167 -> x != 0 && y != 0 ? 1 : 0;
					case 170 -> ix == iy ? 1 : 0;
					case 171 -> ix != iy ? 1 : 0;
					case 172 -> Integer.compareUnsigned(ix, iy) > 0 ? 1 : 0;
					case 173 -> ix > iy ? 1 : 0;
					case 174 -> Integer.compareUnsigned(ix, iy) >= 0 ? 1 : 0;
					case 175 -> ix >= iy ? 1 : 0;
					case 176 -> Integer.compareUnsigned(ix, iy) < 0 ? 1 : 0;
					case 177 -> ix < iy ? 1 : 0;
					case 178 -> Integer.compareUnsigned(ix, iy) <= 0 ? 1 : 0;
					case 179 -> ix <= iy ? 1 : 0;
					case 180, 181 -> x == y ? 1 : 0;
					case 182, 183 -> x != y ? 1 : 0;
					case 184, 185 -> x < y ? 1 : 0;
					case 186, 187 -> x > y ? 1 : 0;
					case 188, 189 -> x <= y ? 1 : 0;
					case 190, 191 -> x >= y ? 1 : 0;
					case 194 -> ix >>> (iy & 31);
					case 195 -> ix >> (iy & 31);
					case 196 -> ix << (iy & 31);
					case 197 -> ix | iy;
					case 198 -> ix ^ iy;
					case 199 -> ix & iy;
					default -> Double.NaN;
				};
				// integer results keep the result type's signedness
				r[i] = f || Double.isNaN(v) || types.get(scalar(t))[0] == TYPE_BOOL ? v : s ? (int) (long) v : Integer.toUnsignedLong((int) (long) v);
			}
			return r;
		}

		private final Map<Integer, Integer> resultTypes = new HashMap<>();

		/** Result type of {@code id} (constants and entry-point instructions), or -1. */
		int typeOf(int id) {
			if (consts.containsKey(id)) return consts.get(id)[1];
			if (resultTypes.isEmpty()) for (int[] ins : body) if (ins.length >= 3 && types.containsKey(ins[1])) resultTypes.put(ins[2], ins[1]);
			return resultTypes.getOrDefault(id, -1);
		}
	}

	private static double f32(double x) {
		return (float) x;
	}

	private static double[] nan(int n) {
		double[] r = new double[n];
		Arrays.fill(r, Double.NaN);
		return r;
	}

	private static double[] concat(double[] a, double[] b) {
		if (a == null || b == null) return null;
		double[] r = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, r, a.length, b.length);
		return r;
	}
}
