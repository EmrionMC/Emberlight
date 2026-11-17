package dev.emrion.shaders.converter.nativefmt;

import dev.emrion.shaders.converter.glsl.StageType;
import dev.emrion.shaders.converter.plan.IrisDimensionPlanner;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.VertexLayouts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One dimension of a native pack as an EPF dimension plan (FORMAT.md §7), from the compiled programs and the set-1 bindings
 * each one actually uses (SPIR-V reflection). The frame is the fixed phase order of §4 (the runtime's engine phases);
 * ping-pong (§6.1) uses the EPF flip mechanism, but only where it is needed: a fullscreen pass flips an image only if it
 * also samples it, every other write goes to the side later passes read.
 */
final class NativePlanner {
	private final NativeManifest m;
	private final NativeGlsl glsl;
	private final String dim;
	private final List<NativeCompiler.Prog> progs;
	private final List<NativeCompiler.Compiled> compiled;
	private final Diagnostics diagnostics;

	private final Map<String, Manifest.Program> programs = new TreeMap<>();
	private final Map<String, Set<Integer>> set1 = new LinkedHashMap<>();
	private final Set<Integer> used = new TreeSet<>(List.of(VertexLayouts.BINDING_UNIFORMS, VertexLayouts.BINDING_PHASE));
	private final TreeSet<Integer> alt = new TreeSet<>(), needsAlt = new TreeSet<>();
	private final Map<List<Integer>, Integer> states = new LinkedHashMap<>();

	NativePlanner(NativeManifest m, NativeGlsl glsl, String dim, List<NativeCompiler.Prog> progs, List<NativeCompiler.Compiled> compiled,
	              Diagnostics diagnostics) {
		this.m = m;
		this.glsl = glsl;
		this.dim = dim;
		this.progs = progs;
		this.compiled = compiled;
		this.diagnostics = diagnostics;
	}

	Manifest.Dimension plan() {
		for (var p : progs) program(p);
		var b = glsl.bindings;
		boolean shadowRender = programs.values().stream().anyMatch(p -> p.kind().equals("shadow"));

		state(List.of());
		List<Manifest.Command> setup = phase("setup", true);
		List<Manifest.Command> begin = phase("begin", false);
		int afterBegin = state(List.copyOf(alt));
		List<Manifest.Command> shadowcomp = phase("shadowcomp", false);
		List<Manifest.Command> prepare = phase("prepare", false);
		int afterPrepare = state(List.copyOf(alt));
		List<Manifest.Command> deferred = phase("deferred", false);
		int afterDeferred = state(List.copyOf(alt));
		List<Manifest.Command> composite = phase("composite", false);
		List<Manifest.Command> fin = phase("final", false);

		// ---- targets
		List<Manifest.Target> targets = new ArrayList<>();
		Set<String> uncleared = new TreeSet<>();
		for (var i : m.images()) {
			if (i.screen()) {
				int n = b.colortex.get(i.name());
				float[] cc = i.clear() == null ? new float[]{0, 0, 0, 0} : i.clear();
				if (i.clear() == null) uncleared.add("colortex" + n);
				targets.add(new Manifest.Target("colortex" + n, "color", IrisDimensionPlanner.promote(i.format()), i.format(), 2, i.scale(), null,
						i.mipmaps() ? 0 : 1, needsAlt.contains(n), i.clear() != null, cc, false, 0));
			} else targets.add(new Manifest.Target(i.name(), "image", i.format(), i.format(), i.size().length, null, i.size(), 1, false,
					i.clear() != null, i.clear() == null ? new float[]{0, 0, 0, 0} : i.clear(), false, 0));
		}
		if (glsl.rr) for (String g : NativeManifest.RR_IMAGES) // written in full by the pack's guide pass every frame: no clear
			targets.add(new Manifest.Target(g, "image", "RGBA16F", "RGBA16F", 2, new float[]{1, 1}, null, 1, false, false, new float[]{0, 0, 0, 0}, false, 0));
		for (var buf : m.buffers())
			targets.add(new Manifest.Target(buf.name(), "buffer", null, null, 0, buf.bytesPerPixel() > 0 ? new float[]{1, 1} : null, null, 1, false,
					false, null, false, buf.bytes() > 0 ? buf.bytes() : buf.bytesPerPixel()));
		targets.add(depth("depthtex0", true));
		boolean depth1 = used.contains(b.byName.get("el_depthOpaque")), depth2 = used.contains(b.byName.get("el_depthNoHand"));
		if (depth1) targets.add(depth("depthtex1", false));
		if (depth2) targets.add(depth("depthtex2", false));
		var sh = m.shadow();
		if (sh != null) {
			for (int i = 0; i < 2; i++)
				targets.add(new Manifest.Target("shadowtex" + i, "depth", "D32F", "D32F", 2, null, new int[]{sh.resolution(), sh.resolution()}, 1,
						false, i == 0, null, false, 0));
			for (var i : sh.images()) {
				int n = b.shadowColor.get(i.name());
				targets.add(new Manifest.Target("shadowcolor" + n, "shadow_color", IrisDimensionPlanner.promote(i.format()), i.format(), 2, null,
						new int[]{sh.resolution(), sh.resolution()}, 1, false, i.clear() != null, i.clear() == null ? new float[]{1, 1, 1, 1} : i.clear(), false, 0));
			}
		}

		// ---- frame (the runtime's engine phases, as for converted packs)
		List<Manifest.Command> frame = new ArrayList<>();
		List<Manifest.ClearOp> first = new ArrayList<>();
		for (var t : targets) if (t.kind().equals("image") && t.clear()) first.add(new Manifest.ClearOp(t.name(), "main", null));
		first.add(new Manifest.ClearOp("depthtex0", "main", null));
		if (sh != null) first.add(new Manifest.ClearOp("shadowtex0", "main", null));
		frame.add(clear(first));
		List<Manifest.ClearOp> second = new ArrayList<>();
		for (var t : targets)
			if (t.clear() && (t.kind().equals("color") || t.kind().equals("shadow_color")))
				second.add(new Manifest.ClearOp(t.name(), t.hasAlt() ? "both" : "main", null));
		if (!second.isEmpty()) frame.add(clear(second));
		frame.addAll(begin);
		if (shadowRender) {
			frame.add(geometry("shadow_opaque", afterBegin));
			frame.add(geometry("shadow_entities", afterBegin));
			frame.add(new Manifest.Command("copy_depth", null, null, null, null, null, "shadowtex0", "shadowtex1", null, null));
			frame.add(geometry("shadow_translucent", afterBegin));
		}
		frame.addAll(shadowcomp);
		frame.addAll(prepare);
		frame.add(geometry("sky", afterPrepare));
		frame.add(geometry("opaque", afterPrepare));
		if (depth2) frame.add(new Manifest.Command("copy_depth", null, null, null, null, null, "depthtex0", "depthtex2", null, null));
		frame.add(geometry("hand_solid", afterPrepare));
		if (depth1) frame.add(new Manifest.Command("copy_depth", null, null, null, null, null, "depthtex0", "depthtex1", null, null));
		frame.addAll(deferred);
		frame.add(geometry("translucent", afterDeferred));
		frame.add(geometry("hand_translucent", afterDeferred));
		frame.addAll(composite);
		frame.addAll(fin);
		// history (never cleared) images whose latest value ends on the alt side: back to main for the next frame
		for (int n : alt)
			if (uncleared.contains("colortex" + n)) frame.add(new Manifest.Command("copy", null, null, null, "colortex" + n, null, "alt", "main", null, null));

		List<Manifest.ClearOp> full = new ArrayList<>();
		for (var t : targets) if (!t.kind().equals("depth")) full.add(new Manifest.ClearOp(t.name(), t.hasAlt() ? "both" : "main", null));
		List<Manifest.Command> once = new ArrayList<>();
		once.add(clear(full));
		once.addAll(setup);

		List<Manifest.FlipState> flipStates = new ArrayList<>();
		states.forEach((k, id) -> flipStates.add(new Manifest.FlipState(id, k, null)));
		List<Manifest.Resource> resources = new ArrayList<>();
		resources.add(new Manifest.Resource(VertexLayouts.BINDING_UNIFORMS, "uniform_buffer_dynamic", "EmrionUniforms", null, null, null, null));
		resources.add(new Manifest.Resource(VertexLayouts.BINDING_PHASE, "uniform_buffer_dynamic", "EmrionPhase", null, null, null, null));
		for (var r : b.resources) if (used.contains(r.binding())) resources.add(r);

		Float spr = sh == null ? null : sh.sunPathRotation();
		var shadow = sh == null
				? new Manifest.Shadow(false, 1024, 160, -1, 1, -100.05f, 156, null, 2, 0, new boolean[]{false, false}, "default", 0)
				: new Manifest.Shadow(shadowRender, sh.resolution(), sh.distance(), sh.renderDistanceMultiplier(), sh.entityDistanceMultiplier(),
				-100.05f, 156, null, 2, spr == null ? 0 : spr, new boolean[]{sh.hardwareFiltering(), sh.hardwareFiltering()}, "default", 0);
		return new Manifest.Dimension(programs, resources, targets, flipStates, frame, once, shadow);
	}

	// ---- programs ------------------------------------------------------------------------------------------------------

	void program(NativeCompiler.Prog p) {
		List<NativeCompiler.Compiled> cs = compiled.stream().filter(c -> c.job().prog() == p).toList();
		long failed = cs.stream().filter(c -> c.spv() == null).count();
		if (failed > 0) {
			diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, null, 0, p.key(), null,
					"removed from the " + dim + " plan: " + failed + " stage(s) failed"));
			return;
		}
		Set<Integer> s1 = new TreeSet<>(List.of(VertexLayouts.BINDING_UNIFORMS, VertexLayouts.BINDING_PHASE));
		Set<String> s0 = new TreeSet<>();
		Map<String, String> vertex = new TreeMap<>(), geometryStage = new TreeMap<>();
		String fragment = null, compute = null;
		for (var c : cs) {
			for (var d : c.reflection().descriptors()) {
				if (d.set() == 1) s1.add(d.binding());
				else if (d.set() == 0 && d.binding() < VertexLayouts.SET0_CANONICAL.size()) s0.add(VertexLayouts.SET0_CANONICAL.get(d.binding()));
			}
			switch (c.job().stage()) {
				case VERTEX -> vertex.put(c.job().variant(), c.job().spv());
				case GEOMETRY -> geometryStage.put(c.job().variant(), c.job().spv());
				case FRAGMENT -> fragment = c.job().spv();
				case COMPUTE -> compute = c.job().spv();
				default -> {}
			}
		}
		used.addAll(s1);
		set1.put(p.key(), s1);
		List<Integer> storage = new ArrayList<>();
		for (int b : s1) {
			if (b < VertexLayouts.FIRST_RESOURCE_BINDING) continue;
			var r = glsl.bindings.at(b);
			if (r.builtin() == null && (r.kind().equals("storage_image") || r.kind().equals("storage_buffer"))) storage.add(b);
		}
		List<Manifest.Set0Ref> set0 = s0.stream().sorted(java.util.Comparator.comparingInt(VertexLayouts.SET0_CANONICAL::indexOf))
				.map(n -> new Manifest.Set0Ref(n, n.startsWith("Sampler") || n.startsWith("Emrion") ? "combined_image_sampler"
						: n.equals("CloudFaces") || n.equals("u_SectionTimeInfo") ? "texel_buffer" : "uniform_buffer")).toList();

		var pass = p.pass();
		if (p.geometry()) {
			List<Manifest.Output> outputs = new ArrayList<>();
			if (pass != null) for (int i = 0; i < pass.writes().size(); i++) outputs.add(new Manifest.Output(i, target(pass.writes().get(i), p.shadow())));
			var stages = new Manifest.Stages(new Manifest.VertexStage(vertex), null, null,
					geometryStage.isEmpty() ? null : new Manifest.VertexStage(geometryStage), new Manifest.SpvRef(fragment), null);
			programs.put(p.key(), new Manifest.Program(p.shadow() ? "shadow" : "gbuffers", p.shadow() ? "shadow" : "gbuffers", List.of(p.slot()), stages,
					outputs, pass == null ? new Manifest.Blend(mode("off"), Map.of()) : blend(pass, outputs, mode("vanilla"), p.shadow()),
					new Manifest.AlphaTest("always", 0), set0, List.copyOf(s1), storage, true, null, null, null, List.of(), 1f, null, null));
			return;
		}
		boolean isCompute = pass.kind().equals("compute");
		List<Manifest.Output> outputs = new ArrayList<>();
		if (pass.kind().equals("fullscreen")) for (int i = 0; i < pass.writes().size(); i++) outputs.add(new Manifest.Output(i, target(pass.writes().get(i), false)));
		var stages = isCompute ? new Manifest.Stages(null, null, null, null, null, new Manifest.SpvRef(compute))
				: new Manifest.Stages(new Manifest.VertexStage(vertex), null, null, null, new Manifest.SpvRef(fragment), null);
		List<String> mips = pass.mipmaps().stream().map(i -> target(i, false)).toList();
		programs.put(p.key(), new Manifest.Program(isCompute ? "compute" : "composite", pass.phase(), List.of(), stages, outputs,
				isCompute ? null : blend(pass, outputs, mode("off"), false), null, set0, List.copyOf(s1), storage, false,
				isCompute ? pass.localSize() : null, isCompute ? pass.count() : null, isCompute && pass.count() == null ? pass.dispatchScale() : null,
				mips, pass.scale(), 0, null));
	}

	String target(String image, boolean shadow) {
		var b = glsl.bindings;
		if (shadow) return "shadowcolor" + b.shadowColor.get(image);
		Integer n = b.colortex.get(image);
		return n != null ? "colortex" + n : image;
	}

	Manifest.Blend blend(NativeManifest.Pass pass, List<Manifest.Output> outputs, Manifest.BlendMode def, boolean shadow) {
		Map<String, Manifest.BlendMode> per = new TreeMap<>();
		for (var e : pass.blend().entrySet()) {
			if (e.getKey().equals("default")) def = parseBlend(e.getValue());
			else per.put(target(e.getKey(), shadow), parseBlend(e.getValue()));
		}
		return new Manifest.Blend(def, per);
	}

	/** NativeManifest's blend value: "off" or "SRC DST SRCA DSTA". */
	static Manifest.BlendMode parseBlend(String v) {
		String[] t = v.trim().toLowerCase(Locale.ROOT).split("\\s+");
		return t.length == 4 ? new Manifest.BlendMode("custom", t[0], t[1], t[2], t[3]) : mode("off");
	}

	static Manifest.BlendMode mode(String m) { return new Manifest.BlendMode(m, null, null, null, null); }

	// ---- frame ---------------------------------------------------------------------------------------------------------

	int state(List<Integer> flips) { return states.computeIfAbsent(List.copyOf(flips), k -> states.size()); }

	/** Fullscreen, compute and final passes of one phase in array order. */
	List<Manifest.Command> phase(String phase, boolean setup) {
		List<Manifest.Command> out = new ArrayList<>();
		var b = glsl.bindings;
		for (var p : progs) {
			var pass = p.pass();
			if (p.geometry() || !pass.phase().equals(phase) || !programs.containsKey(p.key())) continue;
			int fs = setup ? state(List.of()) : state(List.copyOf(alt));
			if (pass.kind().equals("compute")) {
				out.add(new Manifest.Command("compute", p.key(), fs, null, null, null, null, null, null, null));
				continue;
			}
			for (String mm : pass.mipmaps()) {
				int n = b.colortex.get(mm);
				out.add(new Manifest.Command("mipmaps", null, null, null, "colortex" + n, alt.contains(n) ? "alt" : "main", null, null, null, null));
			}
			if (pass.kind().equals("final")) {
				out.add(new Manifest.Command("final", p.key(), fs, null, null, null, null, null, null, null));
				continue;
			}
			Set<String> reads = new TreeSet<>();
			for (int x : set1.get(p.key())) {
				if (x < VertexLayouts.FIRST_RESOURCE_BINDING) continue;
				var r = b.at(x);
				if (r.target() != null && r.kind().equals("sampled_image")) reads.add(r.target());
			}
			List<Manifest.Attachment> writes = new ArrayList<>();
			List<Integer> flips = new ArrayList<>();
			for (int i = 0; i < pass.writes().size(); i++) {
				int n = b.colortex.get(pass.writes().get(i));
				boolean onAlt = alt.contains(n);
				// sampled and written by the same pass: write the other side, later passes read it (§6.1)
				boolean flip = reads.contains("colortex" + n);
				String side = flip != onAlt ? "alt" : "main";
				if (side.equals("alt")) needsAlt.add(n);
				if (flip) flips.add(n);
				writes.add(new Manifest.Attachment(i, "colortex" + n, side));
			}
			out.add(new Manifest.Command("fullscreen", p.key(), fs, null, null, null, null, null, writes, null));
			for (int n : flips) if (!alt.remove(n)) alt.add(n);
		}
		return out;
	}

	static Manifest.Command clear(List<Manifest.ClearOp> ops) { return new Manifest.Command("clear", null, null, null, null, null, null, null, null, List.copyOf(ops)); }

	static Manifest.Command geometry(String phase, int flipState) { return new Manifest.Command("geometry", null, flipState, phase, null, null, null, null, null, null); }

	static Manifest.Target depth(String name, boolean clear) {
		return new Manifest.Target(name, "depth", "D32F", "D32F", 2, new float[]{1, 1}, null, 1, false, clear, null, false, 0);
	}
}
