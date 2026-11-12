package dev.emrion.shaders.converter.expr;

/**
 * Value types of the Iris expression language (kroppeb stareval + Iris VectorType/MatrixType).
 * Note Iris has two distinct int-vector families: the JOML ones ({@code ivec2..4}, only produced by built-ins such as
 * eyeBrightness and only usable with {@code .x}-style access) and the array ones ({@code __ivecN}/{@code __bvecN},
 * produced by the {@code ivecN()/bvecN()} constructors and the vectorised int/bool functions). They never mix.
 */
public enum Type {
	BOOL("bool", 1), INT("int", 1), FLOAT("float", 1),
	VEC2("vec2", 2), VEC3("vec3", 3), VEC4("vec4", 4),
	IVEC2("ivec2", 2), IVEC3("ivec3", 3), IVEC4("ivec4", 4),
	MAT3("mat3", 9), MAT4("mat4", 16),
	AIVEC2("__ivec2", 2), AIVEC3("__ivec3", 3), AIVEC4("__ivec4", 4),
	ABVEC2("__bvec2", 2), ABVEC3("__bvec3", 3), ABVEC4("__bvec4", 4);

	public final String id;
	/** component count (1 for scalars) */
	public final int size;

	Type(String id, int size) {
		this.id = id;
		this.size = size;
	}

	public static Type of(String id) {
		for (Type t : values()) if (t.id.equals(id)) return t;
		throw new IllegalArgumentException("unknown expression type: " + id);
	}

	public boolean isScalar() { return this == BOOL || this == INT || this == FLOAT; }

	/** Iris {@code VectorType.of(primitive, size)}: float → JOML vecN, int/bool → array vectors. */
	static Type vectorOf(Type prim, int size) {
		return switch (prim) {
			case FLOAT -> new Type[]{VEC2, VEC3, VEC4}[size - 2];
			case INT -> new Type[]{AIVEC2, AIVEC3, AIVEC4}[size - 2];
			case BOOL -> new Type[]{ABVEC2, ABVEC3, ABVEC4}[size - 2];
			default -> throw new IllegalArgumentException(prim.id);
		};
	}

	/** Declared custom-uniform types (CustomUniforms.Builder.types). */
	static Type declared(String id) {
		return switch (id) {
			case "bool" -> BOOL;
			case "int" -> INT;
			case "float" -> FLOAT;
			case "vec2" -> VEC2;
			case "vec3" -> VEC3;
			case "vec4" -> VEC4;
			default -> null;
		};
	}
}
