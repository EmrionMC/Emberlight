package dev.emrion.shaders.converter.plan;

import dev.emrion.shaders.converter.expr.UniformCatalog;
import dev.emrion.shaders.converter.glsl.Transpiler;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/** std140 {@code EmrionUniforms} layout from the union of all referenced uniforms. */
public final class Std140LayoutBuilder implements UniformLayoutBuilder {

	/** Members the transpiler injects for compat built-ins (gl_Fog, chunk offset, gl_TextureMatrix[1]). Always present. */
	public static final Map<String, String> INJECTED = Map.of(
			"emr_CameraBlockPos", "ivec3",
			"emr_CameraOffset", "vec3",
			"emr_FogColor", "vec4",
			"emr_FogStart", "float",
			"emr_FogEnd", "float",
			"emr_FogDensity", "float",
			"emr_LightmapTextureMatrix", "mat4");

	/** EmrionPhase block (set 1 binding 1), fixed. */
	public static final Manifest.Block PHASE = new Manifest.Block("EmrionPhase", 144, List.of(
			member("emr_ProjMat", "mat4", 0), member("emr_ProjMatInverse", "mat4", 64), member("renderStage", "int", 128)));

	/** EmrionDraw push-constant block, fixed. */
	public static final Manifest.Block PUSH = new Manifest.Block("EmrionDraw", 40, List.of(
			member("entityColor", "vec4", 0), member("entityId", "int", 16), member("blockEntityId", "int", 20),
			member("currentRenderedItemId", "int", 24), member("emr_alphaTestRef", "float", 28), member("atlasSize", "ivec2", 32)));

	private static final Set<String> NOT_IN_BLOCK = Set.of(
			"entityColor", "entityId", "blockEntityId", "currentRenderedItemId", "emr_alphaTestRef", "atlasSize",
			"emr_ProjMat", "emr_ProjMatInverse", "renderStage");

	private final Function<String, Optional<UniformCatalog.Builtin>> catalog;

	public Std140LayoutBuilder() { this(UniformCatalog::lookup); }

	public Std140LayoutBuilder(Function<String, Optional<UniformCatalog.Builtin>> catalog) { this.catalog = catalog; }

	private record Decl(String name, String type, int arraySize, String source) {}

	@Override
	public Manifest.UniformLayout build(Collection<Transpiler.ProgramAnalysis> analyses, List<Manifest.CustomUniform> custom,
	                                   Diagnostics diagnostics) {
		Map<String, Manifest.CustomUniform> customByName = new TreeMap<>();
		for (var c : custom) if (c.uniform()) customByName.putIfAbsent(c.name(), c);

		Map<String, Decl> decls = new TreeMap<>();
		INJECTED.forEach((n, t) -> decls.put(n, new Decl(n, t, 0, "builtin")));
		for (var a : analyses) {
			for (var u : a.uniforms()) {
				if (decls.containsKey(u.name()) || NOT_IN_BLOCK.contains(u.name())) continue;
				var cu = customByName.get(u.name());
				if (cu != null) {
					decls.put(u.name(), new Decl(u.name(), cu.type(), 0, "custom"));
					continue;
				}
				var b = catalog.apply(u.name());
				if (b.isEmpty()) {
					diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, null, 0, a.program(), null,
							"uniform " + u.name() + " is neither built-in nor custom; it stays at its default value"));
					continue;
				}
				if (!"block".equals(b.get().location()))
					// shortcut: per-draw built-ins without a slot in EmrionDraw/EmrionPhase are written once per frame; add a slot when a pack needs it

					diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, null, 0, a.program(), null,
							"uniform " + u.name() + " is " + b.get().location() + "-scoped in Iris but updated per frame here"));
				if (!b.get().glslType().equals(u.glslType()))
					diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, null, 0, a.program(), null,
							"uniform " + u.name() + " declared " + u.glslType() + ", Iris provides " + b.get().glslType()));
				decls.put(u.name(), new Decl(u.name(), b.get().glslType(), u.arraySize(), "builtin"));
			}
		}

		// Order: 16-aligned non-vec3 (mats, vec4, arrays), then each vec3 followed by one scalar filling its 4-byte tail,
		// then 8-aligned, then the remaining scalars. Each group sorted by name (decls is a TreeMap).
		List<Decl> big = new ArrayList<>(), vec3 = new ArrayList<>(), eight = new ArrayList<>(), four = new ArrayList<>();
		for (var d : decls.values()) {
			if (d.arraySize() == 0 && isVec3(d.type())) vec3.add(d);
			else if (align(d) == 16) big.add(d);
			else if (align(d) == 8) eight.add(d);
			else four.add(d);
		}
		List<Decl> ordered = new ArrayList<>(big);
		for (var v : vec3) {
			ordered.add(v);
			if (!four.isEmpty()) ordered.add(four.removeFirst());
		}
		ordered.addAll(eight);
		ordered.addAll(four);

		List<Manifest.UniformMember> members = new ArrayList<>();
		int offset = 0;
		for (var d : ordered) {
			offset = roundUp(offset, align(d));
			int stride = d.arraySize() > 0 ? roundUp(size(d.type()), 16) : 0;
			members.add(new Manifest.UniformMember(d.name(), d.type(), offset, d.arraySize(), stride, d.source()));
			offset += d.arraySize() > 0 ? stride * d.arraySize() : size(d.type());
		}
		return new Manifest.UniformLayout("EmrionUniforms", roundUp(offset, 16), List.copyOf(members), List.copyOf(custom), PHASE, PUSH);
	}

	static int roundUp(int v, int a) { return (v + a - 1) / a * a; }

	private static boolean isVec3(String t) { return t.matches("[iub]?vec3"); }

	private static int align(Decl d) {
		if (d.arraySize() > 0) return 16;
		return switch (components(d.type())) {
			case 1 -> 4;
			case 2 -> 8;
			default -> 16;
		};
	}

	/** Byte size of one (non-array) value; matrices are arrays of column vectors with stride 16. */
	static int size(String type) {
		if (type.startsWith("mat")) {
			int cols = type.charAt(3) - '0';
			return cols * 16;
		}
		return components(type) * 4;
	}

	private static int components(String type) {
		if (type.startsWith("mat")) return 16;
		char last = type.charAt(type.length() - 1);
		return Character.isDigit(last) ? last - '0' : 1; // float, int, uint, bool → 1
	}

	private static Manifest.UniformMember member(String name, String type, int offset) {
		return new Manifest.UniformMember(name, type, offset, 0, 0, "builtin");
	}

	/** Deterministic lookup map for {@link Transpiler.EmitContext#uniformMembers()}. */
	public static Map<String, Manifest.UniformMember> byName(Manifest.UniformLayout layout) {
		Map<String, Manifest.UniformMember> m = new TreeMap<>();
		for (var u : layout.members()) m.put(u.name(), u);
		return m;
	}
}
