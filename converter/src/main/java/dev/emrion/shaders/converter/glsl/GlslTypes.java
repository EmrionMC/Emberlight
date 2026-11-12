package dev.emrion.shaders.converter.glsl;

/** Small helpers over GLSL type names (scalars, vectors, matrices, opaque types). */
public final class GlslTypes {
	private GlslTypes() {}

	public static boolean isOpaque(String type) {
		return type.matches("[iu]?(sampler|image|texture|subpassInput).*") || type.equals("atomic_uint");
	}

	public static boolean isSampler(String type) { return type.matches("[iu]?sampler.*"); }

	public static boolean isImage(String type) { return type.matches("[iu]?image.*"); }

	/** "float", "int", "uint", "bool", "double" or null for non-numeric types. */
	public static String base(String type) {
		if (type.equals("float") || type.matches("vec[234]|mat[234](x[234])?")) return "float";
		if (type.equals("int") || type.matches("ivec[234]")) return "int";
		if (type.equals("uint") || type.matches("uvec[234]")) return "uint";
		if (type.equals("bool") || type.matches("bvec[234]")) return "bool";
		if (type.equals("double") || type.matches("dvec[234]|dmat[234](x[234])?")) return "double";
		return null;
	}

	public static boolean isInteger(String type) {
		String b = base(type);
		return "int".equals(b) || "uint".equals(b);
	}

	public static boolean isMatrix(String type) { return type.matches("d?mat.*"); }

	/** Component count of a scalar/vector type, 0 for others. */
	public static int components(String type) {
		if (base(type) == null || isMatrix(type)) return 0;
		char c = type.charAt(type.length() - 1);
		return Character.isDigit(c) ? c - '0' : 1;
	}

	/** Scalar/vector type with base of {@code like} and {@code n} components. */
	public static String vector(String like, int n) {
		String b = base(like);
		if (n == 1) return b;
		return switch (b) {
			case "int" -> "ivec" + n;
			case "uint" -> "uvec" + n;
			case "bool" -> "bvec" + n;
			case "double" -> "dvec" + n;
			default -> "vec" + n;
		};
	}

	/** Interface locations used by one value of the type (vec4/scalars: 1, matCxR: C, dvec3/4: 2). */
	public static int locations(String type) {
		if (type.matches("d?mat[234](x[234])?")) {
			return type.charAt(type.indexOf("mat") + 3) - '0';
		}
		if (type.matches("dvec[34]")) return 2;
		return 1;
	}

	/** Zero value expression for a type (GL default for uniforms); null if unknown. */
	public static String zero(String type) {
		if (base(type) == null) return null;
		return type + "(0)";
	}

	/**
	 * Converts {@code expr} of scalar/vector type {@code from} to {@code to}: truncation via swizzle, extension pads with
	 * 0 and 1 in w ({@code wOne}), like GL's generic attribute default (0,0,0,1).
	 */
	public static String convert(String expr, String from, String to, boolean wOne) {
		if (from.equals(to)) return expr;
		int m = components(from), n = components(to);
		if (m == 0 || n == 0) return to + "(" + expr + ")";
		if (n <= m) {
			if (n == m || m == 1) return to + "(" + expr + ")";
			return to + "((" + expr + ")." + "xyzw".substring(0, n) + ")";
		}
		boolean floating = !isInteger(to) && !"bool".equals(base(to));
		StringBuilder sb = new StringBuilder(to).append('(').append(expr);
		for (int i = m; i < n; i++) {
			boolean one = wOne && i == 3;
			sb.append(", ").append(floating ? (one ? "1.0" : "0.0") : "bool".equals(base(to)) ? (one ? "true" : "false") : (one ? "1" : "0"));
		}
		return sb.append(')').toString();
	}
}
