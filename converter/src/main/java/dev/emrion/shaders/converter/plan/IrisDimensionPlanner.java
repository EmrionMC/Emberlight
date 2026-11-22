package dev.emrion.shaders.converter.plan;

import dev.emrion.shaders.converter.glsl.Directives;
import dev.emrion.shaders.converter.glsl.PreprocessedStage;
import dev.emrion.shaders.converter.glsl.ProgramGroup;
import dev.emrion.shaders.converter.glsl.StageType;
import dev.emrion.shaders.converter.glsl.Transpiler;
import dev.emrion.shaders.converter.pack.ResolvedPack;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.Manifest.Command;
import dev.emrion.shaders.format.VertexLayouts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Iris-faithful dimension planner: decides everything statically by simulating one frame the way Iris builds its pipeline. */
public final class IrisDimensionPlanner implements DimensionPlanner {

	@Override
	public DimensionPlan plan(String dimension, List<ResolvedPack.ProgramSource> programs,
	                          Map<String, Transpiler.ProgramAnalysis> analyses, SequencedMap<String, String> properties,
	                          Map<String, String> optionValues, Manifest.UniformLayout uniforms, Diagnostics diagnostics) {
		return new Run(programs, analyses, properties, uniforms, diagnostics).run();
	}

	/** Pass programs; {@code <pass>_csh} is the slot-0 compute of a pass that also has a fullscreen program (DefaultPackLoader). */
	private static final Pattern PASS = Pattern.compile("(setup|begin|shadowcomp|prepare|deferred|composite|final|shadow)(\\d*)(?:_([a-z]|csh))?");
	private static final Pattern COLORTEX = Pattern.compile("colortex(\\d+)");
	private static final Pattern SHADOWCOLOR = Pattern.compile("shadowcolor(\\d)");
	private static final List<ProgramGroup> STAGE_ORDER = List.of(ProgramGroup.SHADOWCOMP, ProgramGroup.BEGIN, ProgramGroup.PREPARE,
			ProgramGroup.GBUFFERS, ProgramGroup.SHADOW, ProgramGroup.FINAL, ProgramGroup.DEFERRED, ProgramGroup.COMPOSITE);
	private static final Set<String> KNOWN_FORMATS = Set.of(
			"R8", "RG8", "RGB8", "RGBA8", "R8_SNORM", "RG8_SNORM", "RGB8_SNORM", "RGBA8_SNORM", "R16", "RG16", "RGB16", "RGBA16",
			"R16_SNORM", "RG16_SNORM", "RGB16_SNORM", "RGBA16_SNORM", "R16F", "RG16F", "RGB16F", "RGBA16F", "R32F", "RG32F",
			"RGB32F", "RGBA32F", "R8I", "RG8I", "RGB8I", "RGBA8I", "R8UI", "RG8UI", "RGB8UI", "RGBA8UI", "R16I", "RG16I", "RGB16I",
			"RGBA16I", "R16UI", "RG16UI", "RGB16UI", "RGBA16UI", "R32I", "RG32I", "RGB32I", "RGBA32I", "R32UI", "RG32UI", "RGB32UI",
			"RGBA32UI", "RGBA2", "RGBA4", "R3_G3_B2", "RGB5_A1", "RGB565", "RGB10_A2", "RGB10_A2UI", "R11F_G11F_B10F", "RGB9_E5");
	private static final String MC_BLOCK_ATLAS = "minecraft:textures/atlas/blocks.png";

	/** Per-program context from the flip simulation: the read-side flip set, overrides and mipmap state it sees. */
	private record Ctx(String texStage, List<Integer> flip, Set<Integer> flippedAtLeastOnce, Set<String> mipOn, int flipState) {}

	private record Pass(int index, TreeMap<String, String> computes, String[] program) {
		Pass(int index) { this(index, new TreeMap<>(), new String[1]); }
	}

	/** A set-1 resource without its binding; equal keys share one binding. */
	private record Res(String kind, String target, String texture, String builtin, Manifest.SamplerDef sampler) {}

	private static final Res SET0 = new Res("set0", null, null, null, null);

	/** RGB-only formats are not colour-attachment capable in Vulkan; promote them. */
	public static String promote(String f) {
		return switch (f) {
			case "R3_G3_B2" -> "RGBA8";
			case "RGB9_E5" -> "RGBA16F";
			case "RGB10_A2", "RGB10_A2UI", "RGB5_A1", "RGB565" -> f;
			default -> f.startsWith("RGB") && !f.startsWith("RGBA") ? "RGBA" + f.substring(3) : f;
		};
	}

	private static final class Run {
		final List<ResolvedPack.ProgramSource> sources;
		final Map<String, Transpiler.ProgramAnalysis> analyses;
		final SequencedMap<String, String> props;
		final Manifest.UniformLayout uniforms;
		final Diagnostics diag;
		final Map<String, String> consts = new HashMap<>();

		final Map<ProgramGroup, TreeMap<Integer, Pass>> passes = new HashMap<>();
		final SequencedMap<String, List<String>> geometry = new LinkedHashMap<>(); // planned program → Iris ids it serves
		final Map<String, List<String>> stageTextures = new HashMap<>();      // texture stage → sampler → value
		final Map<String, String[]> images = new LinkedHashMap<>();            // image name → property tokens
		final Map<String, String> imageBySampler = new HashMap<>();
		final Set<Integer> colortex = new TreeSet<>(Set.of(0));
		final Set<String> shadowColors = new TreeSet<>();
		boolean shadowTargets, shadowRender;

		// flip simulation state
		final Set<Integer> flips = new TreeSet<>();
		final Set<Integer> needsAlt = new TreeSet<>();
		final Set<String> mipOn = new HashSet<>();
		final Set<Integer> mipmapped = new TreeSet<>();
		/** shadowcolor indices a shadowcomp pass mipmaps; Iris leaves their mip filter on, so every program samples them mipped. */
		final Set<Integer> shadowcompMips = new TreeSet<>();
		Set<Integer> lastAtLeastOnce = Set.of();
		final SequencedMap<List<List<Integer>>, Integer> flipStates = new LinkedHashMap<>(); // [colortex alt, shadowcolor alt]
		// shadowcolor flips happen only in shadowcomp; every other pass reads the final state (Iris ShadowRenderTargets)
		final Map<String, List<Integer>> shadowSnapshots = new HashMap<>();
		final Set<Integer> shadowNeedsAlt = new TreeSet<>();
		List<Integer> shadowFinal = List.of();
		final SequencedMap<String, Ctx> ctx = new LinkedHashMap<>(); // planned programs in frame order

		final SequencedMap<Res, Integer> resources = new LinkedHashMap<>();
		final SequencedMap<String, Manifest.TextureDef> textures = new LinkedHashMap<>(); // by property value
		final Set<String> referencedTargets = new HashSet<>();

		Run(List<ResolvedPack.ProgramSource> sources, Map<String, Transpiler.ProgramAnalysis> analyses,
		    SequencedMap<String, String> props, Manifest.UniformLayout uniforms, Diagnostics diag) {
			this.sources = sources;
			this.analyses = new TreeMap<>(analyses);
			this.props = props;
			this.uniforms = uniforms;
			this.diag = diag;
		}

		DimensionPlan run() {
			collectPackConsts();
			parseProperties();
			classify();
			activateShadows();
			allocateColortexByName();
			simulateShadowFlips();

			List<Integer> none = List.of();
			state(none);
			List<Command> setup = runStage(ProgramGroup.SETUP);
			List<Command> begin = runStage(ProgramGroup.BEGIN);
			int afterBegin = state(List.copyOf(flips));
			List<Command> shadowComputes = new ArrayList<>();
			for (var pass : passes(ProgramGroup.SHADOW).values())
				for (var c : pass.computes().values()) {
					ctx.put(c, new Ctx("gbuffers", List.copyOf(flips), Set.of(), Set.of(), afterBegin));
					shadowComputes.add(cmd("compute", c, afterBegin));
				}
			for (var e : geometry.entrySet())
				if (e.getKey().startsWith("shadow")) ctx.put(e.getKey(), new Ctx("gbuffers", List.copyOf(flips), Set.of(), Set.of(), afterBegin));
			List<Command> shadowcomp = runStage(ProgramGroup.SHADOWCOMP);
			List<Command> prepare = runStage(ProgramGroup.PREPARE);
			int afterPrepare = state(List.copyOf(flips));
			for (var e : geometry.entrySet())
				if (!e.getKey().startsWith("shadow")) ctx.put(e.getKey(), new Ctx("gbuffers", List.copyOf(flips), Set.of(), Set.of(), afterPrepare));
			List<Command> deferred = runStage(ProgramGroup.DEFERRED);
			int afterDeferred = state(List.copyOf(flips));
			List<Command> composite = runStage(ProgramGroup.COMPOSITE);
			// Without a final program Iris copies colortex0 (read side) to the screen: a "final" command with no program.
			passes.computeIfAbsent(ProgramGroup.FINAL, g -> new TreeMap<>()).computeIfAbsent(0, Pass::new);
			List<Command> fin = runStage(ProgramGroup.FINAL);
			List<Integer> finalFlips = List.copyOf(flips);

			var programs = buildPrograms();
			var emits = buildEmits();
			var targets = buildTargets();
			Set<String> cleared = new HashSet<>();
			for (var t : targets) if (t.clear()) cleared.add(t.name());

			// ---- frame ----
			List<Command> frame = new ArrayList<>();
			List<Manifest.ClearOp> first = new ArrayList<>();
			for (var t : targets) if (t.kind().equals("image") && t.clear()) first.add(new Manifest.ClearOp(t.name(), "main", null));
			first.add(new Manifest.ClearOp("depthtex0", "main", null));
			if (shadowRender) first.add(new Manifest.ClearOp("shadowtex0", "main", null));
			frame.add(clear(first));
			if (shadowRender) frame.addAll(shadowComputes);
			List<Manifest.ClearOp> second = new ArrayList<>();
			for (var t : targets)
				if (t.clear() && (t.kind().equals("color") || t.kind().equals("shadow_color") && shadowRender))
					second.add(new Manifest.ClearOp(t.name(), t.hasAlt() ? "both" : "main", null));
			if (!second.isEmpty()) frame.add(clear(second));
			frame.addAll(begin);
			if (shadowRender) {
				frame.add(geometryCmd("shadow_opaque", afterBegin));
				frame.add(geometryCmd("shadow_entities", afterBegin));
				frame.add(copyDepth("shadowtex0", "shadowtex1"));
				frame.add(geometryCmd("shadow_translucent", afterBegin));
				for (var t : targets)
					if (t.name().startsWith("shadowtex") ? t.mipLevels() == 0 : t.kind().equals("shadow_color") && colorMip(t.name().charAt(11) - '0'))
						frame.add(mipmaps(t.name(), "main"));
				frame.addAll(shadowcomp);
			}
			frame.addAll(prepare);
			frame.add(geometryCmd("sky", afterPrepare));
			frame.add(geometryCmd("opaque", afterPrepare));
			boolean depth1 = referencedTargets.contains("depthtex1"), depth2 = referencedTargets.contains("depthtex2");
			if (depth2) frame.add(copyDepth("depthtex0", "depthtex2"));
			frame.add(geometryCmd("hand_solid", afterPrepare));
			if (depth1) frame.add(copyDepth("depthtex0", "depthtex1"));
			frame.addAll(deferred);
			frame.add(geometryCmd("translucent", afterDeferred));
			frame.add(geometryCmd("hand_translucent", afterDeferred));
			frame.addAll(composite);
			frame.addAll(fin);
			// End-of-frame "swap": alt → main for flipped buffers that are not cleared.
			for (int n : finalFlips)
				if (!cleared.contains("colortex" + n))
					frame.add(new Command("copy", null, null, null, "colortex" + n, null, "alt", "main", null, null));

			// ---- once: full clear of everything, then setup computes ----
			List<Manifest.ClearOp> full = new ArrayList<>();
			for (var t : targets)
				if (!t.kind().equals("depth")) full.add(new Manifest.ClearOp(t.name(), t.hasAlt() ? "both" : "main", null));
			List<Command> once = new ArrayList<>();
			once.add(clear(full));
			once.addAll(setup);

			List<Manifest.FlipState> states = new ArrayList<>();
			flipStates.forEach((k, id) -> states.add(new Manifest.FlipState(id, k.get(0), k.get(1).isEmpty() ? null : k.get(1))));
			List<Manifest.Resource> res = new ArrayList<>();
			res.add(new Manifest.Resource(VertexLayouts.BINDING_UNIFORMS, "uniform_buffer_dynamic", "EmrionUniforms", null, null, null, null));
			res.add(new Manifest.Resource(VertexLayouts.BINDING_PHASE, "uniform_buffer_dynamic", "EmrionPhase", null, null, null, null));
			resources.forEach((r, b) -> res.add(new Manifest.Resource(b, r.kind(), null, r.target(), r.texture(), r.builtin(), r.sampler())));

			var dim = new Manifest.Dimension(programs, res, targets, states, frame, once, shadowSettings());
			return new DimensionPlan(dim, emits, List.copyOf(textures.values()));
		}

		// ---------------------------------------------------------------------------------------------------------
		// Inputs

		/** Pack-wide const directives: fragment sources in Iris's collection order, later wins. */
		void collectPackConsts() {
			for (var g : STAGE_ORDER) {
				List<ResolvedPack.ProgramSource> group = new ArrayList<>();
				for (var s : sources) if (s.enabled() && !s.stages().isEmpty() && s.stages().getFirst().group() == g) group.add(s);
				group.sort((a, b) -> Integer.compare(order(a.name()), order(b.name())));
				for (var s : group) {
					var f = fragment(s.stages());
					if (f != null) consts.putAll(f.consts());
				}
			}
		}

		int order(String name) {
			int i = 0;
			for (String id : IrisPrograms.FALLBACK.keySet()) {
				if (id.equals(name)) return i;
				i++;
			}
			Matcher m = PASS.matcher(name);
			return m.matches() && !m.group(2).isEmpty() ? Integer.parseInt(m.group(2)) : 0;
		}

		void parseProperties() {
			props.forEach((k, v) -> {
				if (k.startsWith("texture.") && !k.equals("texture.noise")) {
					String[] p = k.split("\\.");
					if (p.length >= 3) stageTextures.computeIfAbsent(p[1] + "." + p[2], x -> new ArrayList<>()).add(v.trim());
				} else if (k.startsWith("image.")) {
					String[] t = v.trim().split("\\s+");
					if (t.length < 7) {
						warn(null, "invalid image directive " + k + " = " + v);
						return;
					}
					String name = k.substring(6);
					images.put(name, t);
					if (!t[0].equals("none")) imageBySampler.put(t[0], name);
				}
			});
		}

		/** A raw texture (value with spaces) only replaces a sampler declared with its type (texture.deferred.colortex6.1). */
		String stageTexture(String stage, String sampler, String glslType) {
			var l = stageTextures.get(stage + "." + sampler);
			if (l == null) return null;
			for (String v : l.reversed()) {
				String[] p = v.split("\\s+");
				if (p.length == 1) return v;
				String dim = switch (p[1].toUpperCase(Locale.ROOT)) {
					case "TEXTURE_1D" -> "sampler1D";
					case "TEXTURE_3D" -> "sampler3D";
					case "TEXTURE_RECTANGLE" -> "sampler2DRect";
					default -> "sampler2D";
				};
				if (glslType.replaceFirst("^[iu]", "").equals(dim)) return v;
			}
			return null;
		}

		void classify() {
			Set<String> geometryNames = new TreeSet<>();
			for (var a : analyses.values()) {
				boolean compute = a.stages().stream().anyMatch(s -> s.stage() == StageType.COMPUTE);
				if (a.group() == ProgramGroup.GBUFFERS || a.group() == ProgramGroup.SHADOW && !compute) {
					geometryNames.add(a.program());
					continue;
				}
				Matcher m = PASS.matcher(a.program());
				if (!m.matches()) {
					info(a.program(), "not an Iris pass name; skipped");
					continue;
				}
				int index = m.group(2).isEmpty() ? 0 : Integer.parseInt(m.group(2));
				var pass = passes.computeIfAbsent(a.group(), g -> new TreeMap<>()).computeIfAbsent(index, Pass::new);
				if (compute) pass.computes().put(m.group(3) == null || m.group(3).equals("csh") ? "" : m.group(3), a.program());
				else pass.program()[0] = a.program();
			}
			// Iris ProgramId fallback chains.
			for (String id : IrisPrograms.FALLBACK.keySet()) {
				String p = id;
				while (p != null && !geometryNames.contains(p)) p = IrisPrograms.FALLBACK.get(p);
				if (p != null && !IrisPrograms.VARIANTS.get(id).isEmpty())
					geometry.computeIfAbsent(p, x -> new ArrayList<>()).add(id);
			}
			for (String n : geometryNames)
				if (!geometry.containsKey(n)) info(n, "no MC geometry is drawn with this program; skipped");
		}

		void activateShadows() {
			Boolean prop = bool(props.get("shadow.enabled"));
			boolean sampled = false;
			for (var e : analyses.entrySet()) {
				var a = e.getValue();
				// gbuffers count too: Iris's ShaderMap builds them (asking for the shadow targets) before the shadow keys
				if (a.group() == ProgramGroup.GBUFFERS ? !geometry.containsKey(e.getKey())
						: a.group().isGeometry() || a.group() == ProgramGroup.SETUP || a.group() == ProgramGroup.SHADOWCOMP) continue;
				for (var s : a.samplers()) sampled |= isShadowSampler(s.name());
				for (var i : a.images()) sampled |= i.name().startsWith("shadowcolorimg");
			}
			shadowTargets = Boolean.TRUE.equals(prop) || sampled;
			shadowRender = shadowTargets && !Boolean.FALSE.equals(prop);
			if (!shadowRender) {
				geometry.keySet().removeIf(n -> n.startsWith("shadow"));
				passes.remove(ProgramGroup.SHADOWCOMP);
				passes.remove(ProgramGroup.SHADOW);
			}
		}

		static boolean isShadowSampler(String n) {
			return n.equals("shadow") || n.equals("watershadow") || n.startsWith("shadowtex") || n.startsWith("shadowcolor");
		}

		/** Iris creates colortex lazily: draw buffers of any program, samplers by name or alias, colorimgN. */
		void allocateColortexByName() {
			for (String p : planned()) {
				var a = analyses.get(p);
				boolean full = !a.group().isGeometry();
				if (a.group() == ProgramGroup.GBUFFERS || full && a.group() != ProgramGroup.SHADOWCOMP && a.group() != ProgramGroup.FINAL && !isCompute(a))
					colortex.addAll(drawBuffers(a));
				for (var s : a.samplers()) {
					int n = colortexIndex(s.name());
					if (n >= 0 && (full || n >= 4)) colortex.add(n);
				}
				for (var i : a.images()) {
					Matcher m = Pattern.compile("colorimg(\\d+)").matcher(i.name());
					if (m.matches()) colortex.add(Integer.parseInt(m.group(1)));
				}
			}
		}

		List<String> planned() {
			List<String> l = new ArrayList<>(geometry.keySet());
			for (var g : passes.values())
				for (var p : g.values()) {
					l.addAll(p.computes().values());
					if (p.program()[0] != null) l.add(p.program()[0]);
				}
			return l;
		}

		// ---------------------------------------------------------------------------------------------------------
		// Flip simulation

		TreeMap<Integer, Pass> passes(ProgramGroup g) { return passes.getOrDefault(g, new TreeMap<>()); }

		int state(List<Integer> alt) { return state(alt, shadowFinal); }

		int state(List<Integer> alt, List<Integer> shadowAlt) { return flipStates.computeIfAbsent(List.of(alt, shadowAlt), k -> flipStates.size()); }

		/** Iris ShadowCompositeRenderer simulated once: each shadowcomp pass reads its snapshot, writes the other side and flips it. */
		void simulateShadowFlips() {
			Set<Integer> f = new TreeSet<>();
			explicitFlips("shadowcomp_pre").forEach((n, b) -> { if (b && !f.remove(n)) f.add(n); });
			for (var pass : passes(ProgramGroup.SHADOWCOMP).values()) {
				List<Integer> snap = List.copyOf(f);
				pass.computes().values().forEach(c -> shadowSnapshots.put(c, snap));
				String p = pass.program()[0];
				if (p == null) continue;
				shadowSnapshots.put(p, snap);
				var explicit = explicitFlips(p);
				for (int n : drawBuffers(analyses.get(p))) {
					if (!snap.contains(n)) shadowNeedsAlt.add(n); // writes alt
					if (!Boolean.FALSE.equals(explicit.get(n)) && !f.remove(n)) f.add(n);
				}
				explicit.forEach((n, b) -> { if (b && !f.remove(n)) f.add(n); });
			}
			shadowNeedsAlt.addAll(f);
			shadowFinal = List.copyOf(f);
		}

		void toggle(int n) {
			if (!flips.remove(n)) flips.add(n);
			needsAlt.add(n);
		}

		List<Command> runStage(ProgramGroup g) {
			List<Command> out = new ArrayList<>();
			String stage = texStage(g);
			boolean flipsColortex = g != ProgramGroup.SETUP && g != ProgramGroup.SHADOWCOMP && g != ProgramGroup.FINAL;
			String preKey = g.name().toLowerCase(Locale.ROOT) + "_pre";
			if (flipsColortex) explicitFlips(preKey).forEach((n, f) -> { if (f) toggle(n); });
			else if (g != ProgramGroup.SHADOWCOMP && !explicitFlips(preKey).isEmpty()) warn(null, "flip." + preKey + " is not supported");
			Set<Integer> atLeastOnce = new TreeSet<>(g == ProgramGroup.FINAL ? lastAtLeastOnce : Set.of());
			for (var pass : passes(g).values()) {
				List<Integer> snap = g == ProgramGroup.SETUP ? List.of() : List.copyOf(flips);
				String first = pass.program()[0] != null ? pass.program()[0] : pass.computes().isEmpty() ? null : pass.computes().firstEntry().getValue();
				List<Integer> shadowSnap = g == ProgramGroup.SHADOWCOMP ? shadowSnapshots.getOrDefault(first, List.of()) : shadowFinal;
				int fs = state(snap, shadowSnap);
				for (String c : pass.computes().values()) {
					ctx.put(c, new Ctx(stage, snap, Set.copyOf(atLeastOnce), Set.copyOf(mipOn), fs));
					out.add(cmd("compute", c, fs));
				}
				String p = pass.program()[0];
				if (p == null) {
					if (g == ProgramGroup.FINAL) out.add(cmd("final", null, fs));
					continue; // compute-only passes do not flip
				}
				var a = analyses.get(p);
				for (int n : mipmapBuffers(a)) {
					if (g == ProgramGroup.SHADOWCOMP) { // Iris ShadowCompositeRenderer: the directives address shadowcolorN
						if (n >= 8) continue;
						shadowColors.add("shadowcolor" + n);
						shadowcompMips.add(n);
						out.add(mipmaps("shadowcolor" + n, shadowSnap.contains(n) ? "alt" : "main"));
						continue;
					}
					if (!colortex.contains(n)) continue;
					String side = snap.contains(n) ? "alt" : "main";
					out.add(mipmaps("colortex" + n, side));
					mipOn.add(n + ":" + side);
					mipmapped.add(n);
				}
				ctx.put(p, new Ctx(stage, snap, Set.copyOf(atLeastOnce), Set.copyOf(mipOn), fs));
				if (g == ProgramGroup.FINAL) {
					out.add(cmd("final", p, fs));
					continue;
				}
				List<Manifest.Attachment> writes = new ArrayList<>();
				List<Integer> db = drawBuffers(a);
				for (int i = 0; i < db.size(); i++) {
					int n = db.get(i);
					if (g == ProgramGroup.SHADOWCOMP) {
						shadowColors.add("shadowcolor" + n);
						writes.add(new Manifest.Attachment(i, "shadowcolor" + n, shadowSnap.contains(n) ? "main" : "alt"));
						continue;
					}
					String side = snap.contains(n) ? "main" : "alt";
					if (side.equals("alt")) needsAlt.add(n);
					writes.add(new Manifest.Attachment(i, "colortex" + n, side));
				}
				out.add(new Command("fullscreen", p, fs, null, null, null, null, null, writes, null));
				if (!flipsColortex) continue; // shadowcomp flips shadowcolor: simulateShadowFlips
				var explicit = explicitFlips(p);
				for (int n : db) {
					if (Boolean.FALSE.equals(explicit.get(n))) continue;
					toggle(n);
					atLeastOnce.add(n);
				}
				// Iris quirk: flip.<pass>.<buf>=true toggles again even when the pass draws to that buffer.
				explicit.forEach((n, f) -> {
					if (f) {
						toggle(n);
						atLeastOnce.add(n);
					}
				});
			}
			if (g == ProgramGroup.COMPOSITE) lastAtLeastOnce = atLeastOnce;
			return out;
		}

		Map<Integer, Boolean> explicitFlips(String pass) {
			Map<Integer, Boolean> m = new TreeMap<>();
			String prefix = "flip." + pass + ".";
			props.forEach((k, v) -> {
				if (!k.startsWith(prefix)) return;
				int n = colortexIndex(k.substring(prefix.length()));
				Boolean b = bool(v);
				if (n >= 0 && b != null) m.put(n, b);
			});
			return m;
		}

		static String texStage(ProgramGroup g) {
			return switch (g) {
				case SETUP -> "setup";
				case BEGIN -> "begin";
				case SHADOWCOMP -> "shadowcomp";
				case PREPARE -> "prepare";
				case GBUFFERS, SHADOW -> "gbuffers";
				case DEFERRED -> "deferred";
				case COMPOSITE, FINAL -> "composite";
			};
		}

		// ---------------------------------------------------------------------------------------------------------
		// Programs and resources

		final Map<String, Map<String, Integer>> bindings = new HashMap<>();

		SequencedMap<String, Manifest.Program> buildPrograms() {
			SequencedMap<String, Manifest.Program> out = new LinkedHashMap<>();
			for (var e : ctx.entrySet()) {
				String name = e.getKey();
				var a = analyses.get(name);
				var c = e.getValue();
				boolean compute = isCompute(a);
				boolean geo = a.group().isGeometry() && !compute;
				String kind = compute ? "compute" : a.group() == ProgramGroup.GBUFFERS ? "gbuffers" : geo ? "shadow" : "composite";

				Map<String, Integer> b = new TreeMap<>();
				Set<Integer> storage = new TreeSet<>();
				boolean water = a.samplers().stream().anyMatch(s -> s.name().equals("watershadow"));
				for (var s : a.samplers()) {
					Res r = switch (s.name()) { // RT helpers (RtPatcher): the TLAS and the block atlas for alpha tests
						case "emrRtTlas" -> new Res("acceleration_structure", null, null, "emrion_tlas", null);
						case "emrRtAtlas" -> new Res("sampled_image", null, null, "block_atlas", null);
						case "emrRtAo" -> new Res("sampled_image", null, null, "emrion_rtao", sampler("nearest", "none", "clamp", null));
						case "emrRtEntityAtlas" -> new Res("sampled_image", null, null, "emrion_rt_entity_atlas", sampler("nearest", "none", "clamp", null));
						default -> resolveSampler(c, a.group(), s.name(), s.glslType(), water);
					};
					if (r == SET0) continue;
					if (r == null) {
						// GL reads texture unit 0 through an unbound sampler: colortex0 in composite-like passes. In gbuffers it
						// would be the albedo; a white texture (or depth 1.0 for shadow samplers = fully lit) is the safe
						// equivalent (e.g. shadowtex0 in the Nether, where Iris creates no shadow targets).
						r = !a.group().isGeometry() ? resolveSampler(c, a.group(), "colortex0", "sampler2D", false) : null;
						// colortex0 itself can be unresolvable (an unsupported texture.composite.colortex0 override, Photon)
						if (r == null) r = s.glslType().endsWith("Shadow") ? new Res("sampled_image", null, null, "white_depth", sampler("linear", "none", "clamp", "lequal"))
								: new Res("sampled_image", null, null, "white", sampler("nearest", "none", "clamp", null));
						info(name, "sampler " + s.name() + " has no Iris source; bound to the unit-0 equivalent");
					}
					b.put(s.name(), bind(r));
				}
				for (var i : a.images()) {
					Res r = resolveImage(i.name());
					if (r == null) {
						warn(name, "image " + i.name() + " has no Iris source; left unbound");
						continue;
					}
					b.put(i.name(), bind(r));
					if (!"readonly".equals(i.access())) storage.add(b.get(i.name()));
				}
				for (var buf : a.buffers()) {
					if (!buf.used()) continue; // not emitted (GlslTranspiler.emitBlock)
					if (buf.blockName().equals("EmrRtSections")) { // RT helper section table (RtPatcher)
						b.put(buf.blockName(), bind(new Res("storage_buffer", null, null, "emrion_rt_sections", null)));
						continue;
					}
					if (buf.blockName().equals("EmrRtLights")) { // RT block-light grid (rt/complementary/blocklight.glsl)
						b.put(buf.blockName(), bind(new Res("storage_buffer", null, null, "emrion_rt_lights", null)));
						continue;
					}
					String key = "bufferObject." + buf.declaredBinding();
					if (!props.containsKey(key)) {
						warn(name, "SSBO binding " + buf.declaredBinding() + " has no " + key + "; left unbound");
						continue;
					}
					Res r = new Res("storage_buffer", "bufferObject" + buf.declaredBinding(), null, null, null);
					b.put(buf.blockName(), bind(r));
					b.put("bufferObject" + buf.declaredBinding(), bind(r)); // the transpiler resolves SSBOs by this name
					if (!buf.readonly()) storage.add(b.get(buf.blockName()));
				}
				bindings.put(name, b);
				Set<Integer> set1 = new TreeSet<>(List.of(VertexLayouts.BINDING_UNIFORMS, VertexLayouts.BINDING_PHASE));
				set1.addAll(b.values());

				List<Manifest.Output> outputs = new ArrayList<>();
				if (!compute && a.group() != ProgramGroup.FINAL) {
					List<Integer> db = drawBuffers(a);
					String prefix = a.group() == ProgramGroup.SHADOW || a.group() == ProgramGroup.SHADOWCOMP ? "shadowcolor" : "colortex";
					for (int i = 0; i < db.size(); i++) outputs.add(new Manifest.Output(i, prefix + db.get(i)));
					if (prefix.equals("shadowcolor")) for (var o : outputs) shadowColors.add(o.target());
				}

				var stages = stages(a, compute, geo);
				var d = directives(a);
				List<String> mips = new ArrayList<>();
				if (!geo && !compute) for (int n : mipmapBuffers(a))
					if (a.group() == ProgramGroup.SHADOWCOMP ? n < 8 : colortex.contains(n)) mips.add((a.group() == ProgramGroup.SHADOWCOMP ? "shadowcolor" : "colortex") + n);
				out.put(name, new Manifest.Program(kind, a.group().name().toLowerCase(Locale.ROOT),
						geo ? List.copyOf(geometry.get(name)) : List.of(), stages, outputs,
						compute ? null : blend(name, kind, outputs), geo ? alphaTest(name) : null,
						a.set0().stream().map(IrisDimensionPlanner::set0Ref).toList(), List.copyOf(set1), List.copyOf(storage), geo,
						compute ? d.localSize() : null, compute ? d.workGroups() : null, compute ? d.workGroupsRender() : null,
						mips, viewport(name)[0], geo ? null : 0, viewport(name)[1] == 0 && viewport(name)[2] == 0 ? null
								: new float[]{viewport(name)[1], viewport(name)[2]}));
			}
			return out;
		}

		Map<String, ProgramEmit> buildEmits() {
			var members = Std140LayoutBuilder.byName(uniforms);
			Map<String, ProgramEmit> out = new LinkedHashMap<>();
			for (String name : ctx.keySet()) {
				var a = analyses.get(name);
				boolean compute = isCompute(a);
				boolean geo = a.group().isGeometry() && !compute;
				Map<Integer, Integer> locations = new TreeMap<>();
				if (!compute) {
					int count = a.group() == ProgramGroup.FINAL ? 1 : drawBuffers(a).size();
					for (int i = 0; i < count; i++) locations.put(i, i);
				}
				List<String> variants = compute ? List.of() : geo ? variants(name) : List.of("fullscreen");
				var b = bindings.get(name);
				out.put(name, new ProgramEmit(new Transpiler.EmitContext(members, b::get, locations, geo ? alphaTest(name) : null), variants));
			}
			return out;
		}

		List<String> variants(String program) {
			Set<String> v = new LinkedHashSet<>();
			for (String id : geometry.get(program)) v.addAll(IrisPrograms.VARIANTS.get(id));
			return List.copyOf(v);
		}

		Manifest.Stages stages(Transpiler.ProgramAnalysis a, boolean compute, boolean geo) {
			if (compute) return new Manifest.Stages(null, null, null, null, null, new Manifest.SpvRef(null));
			Map<String, String> v = new LinkedHashMap<>();
			for (String k : geo ? variants(a.program()) : List.of("fullscreen")) v.put(k, null);
			Map<StageType, Manifest.VertexStage> mid = new java.util.EnumMap<>(StageType.class);
			for (var s : a.stages()) if (s.stage().intermediate()) mid.put(s.stage(), new Manifest.VertexStage(v));
			return new Manifest.Stages(new Manifest.VertexStage(v), mid.get(StageType.TESS_CONTROL), mid.get(StageType.TESS_EVAL),
					mid.get(StageType.GEOMETRY), new Manifest.SpvRef(null), null);
		}

		int bind(Res r) {
			int next = VertexLayouts.FIRST_RESOURCE_BINDING + resources.size();
			if (r.target() != null) referencedTargets.add(r.target());
			return resources.computeIfAbsent(r, k -> next);
		}

		/** Name → resource, per program group, with stage custom-texture overrides. */
		Res resolveSampler(Ctx c, ProgramGroup g, String name, String type, boolean water) {
			boolean full = !g.isGeometry();
			String canon = canonical(name, full, water);
			if (SET0.kind().equals(canon)) return SET0;
			if (canon != null) {
				for (String alias : aliases(canon, water)) {
					String v = stageTexture(c.texStage(), alias, type);
					// Iris: PNG overrides skip colortex that flipped before; raw ones rename the sampler in the source, always
					boolean flipped = canon.startsWith("colortex") && c.flippedAtLeastOnce().contains(colortexIndex(canon));
					if (v != null && (!flipped || v.contains(" "))) return textureRes(v, alias);
				}
				return builtinRes(canon, c, type);
			}
			String custom = props.get("customTexture." + name);
			if (custom != null) return textureRes(custom, name);
			String image = imageBySampler.get(name);
			if (image != null) {
				boolean integer = isInteger(images.get(image)[2].toUpperCase(Locale.ROOT));
				return new Res("sampled_image", image, null, null, sampler(integer ? "nearest" : "linear", "none", "clamp", null));
			}
			return null;
		}

		String canonical(String name, boolean full, boolean water) {
			int n = colortexIndex(name);
			if (n >= 0) return full || n >= 4 ? "colortex" + n : null;
			Matcher sc = SHADOWCOLOR.matcher(name);
			if (sc.matches() || name.equals("shadowcolor")) return shadowTargets ? (sc.matches() ? name : "shadowcolor0") : null;
			return switch (name) {
				case "tex", "texture", "gtexture", "u_MainSampler", "lightmap", "iris_overlay" -> full ? null : SET0.kind();
				case "depthtex0", "depthtex1", "depthtex2", "noisetex" -> name;
				case "gdepthtex" -> full ? "depthtex0" : null;
				case "shadowtex0", "shadowtex1", "shadowtex0HW", "shadowtex1HW", "watershadow", "shadow" -> !shadowTargets ? null
						: name.equals("watershadow") ? "shadowtex0" : name.equals("shadow") ? (water ? "shadowtex1" : "shadowtex0")
						: name.substring(0, 10);
				default -> null;
			};
		}

		static List<String> aliases(String canon, boolean water) {
			int n = colortexIndex(canon);
			if (n >= 0) return n < 8 ? List.of(canon, IrisPrograms.LEGACY.get(n)) : List.of(canon);
			return switch (canon) {
				case "depthtex0" -> List.of("depthtex0", "gdepthtex");
				case "shadowtex0" -> List.of("shadowtex0", water ? "watershadow" : "shadow");
				case "shadowtex1" -> water ? List.of("shadowtex1", "shadow") : List.of("shadowtex1");
				case "shadowcolor0" -> List.of("shadowcolor0", "shadowcolor");
				default -> List.of(canon);
			};
		}

		Res builtinRes(String canon, Ctx c, String type) {
			int n = colortexIndex(canon);
			if (n >= 0) {
				String side = c.flip().contains(n) ? "alt" : "main";
				boolean mip = c.mipOn().contains(n + ":" + side);
				boolean integer = isInteger(colortexFormat(n)[1]);
				return new Res("sampled_image", canon, null, null, sampler(integer ? "nearest" : "linear", !mip ? "none" : integer ? "nearest" : "linear", "clamp", null));
			}
			if (canon.startsWith("depthtex")) return new Res("sampled_image", canon, null, null, sampler("nearest", "none", "clamp", null));
			if (canon.startsWith("shadowtex")) {
				int i = canon.charAt(9) - '0';
				boolean compare = type.contains("Shadow");
				return new Res("sampled_image", canon, null, null, sampler(depthNearest(i) ? "nearest" : "linear",
						depthMip(i) ? "linear" : "none", "clamp", compare ? "lequal" : null));
			}
			if (canon.startsWith("shadowcolor")) {
				int i = canon.charAt(11) - '0';
				shadowColors.add(canon);
				boolean integer = isInteger(consts.getOrDefault(canon + "Format", "").trim().toUpperCase(Locale.ROOT));
				String mip = !colorMip(i) && !shadowcompMips.contains(i) ? "none" : integer ? "nearest" : "linear";
				return new Res("sampled_image", canon, null, null, sampler(colorNearest(i) || integer ? "nearest" : "linear", mip, "clamp", null));
			}
			if (canon.equals("noisetex")) return new Res("sampled_image", null, noiseTexture(), null, sampler("linear", "none", "repeat", null));
			throw new IllegalArgumentException("no builtin resource " + canon);
		}

		Res resolveImage(String name) {
			Matcher m = Pattern.compile("(shadow)?colorimg(\\d+)").matcher(name);
			if (m.matches()) {
				int n = Integer.parseInt(m.group(2));
				if (m.group(1) == null) return new Res("storage_image", "colortex" + n, null, null, null);
				if (!shadowTargets) return null;
				shadowColors.add("shadowcolor" + n);
				return new Res("storage_image", "shadowcolor" + n, null, null, null);
			}
			return images.containsKey(name) ? new Res("storage_image", name, null, null, null) : null;
		}

		/** A custom texture's sampler is the texture's own (Iris PNG default nearest/repeat, .mcmeta blur/clamp). */
		Res textureRes(String value, String sampler) {
			if (value.equals(MC_BLOCK_ATLAS)) return new Res("sampled_image", null, null, "block_atlas", null);
			String name = texture(value, sampler);
			return name == null ? null : new Res("sampled_image", null, name, null, null);
		}

		String texture(String value, String hint) {
			var existing = textures.get(value);
			if (existing != null) return existing.name();
			if (value.contains(" ")) return rawTexture(value);
			boolean resource = value.contains(":");
			String base = resource ? hint : value.replaceAll("^.*/", "").replaceAll("\\.[^.]*$", "");
			String name = base;
			for (int i = 2; nameTaken(name); i++) name = base + "_" + i;
			String file = resource ? null : "shaders/" + value.replaceFirst("^/", "");
			textures.put(value, new Manifest.TextureDef(name, file, resource ? value : null, "RGBA8", 2, null, "nearest", "repeat", false, null));
			return name;
		}

		/**
		 * Raw texture {@code <file> <TEXTURE_1D|2D|3D|RECTANGLE> <internal format> <w> [h] [d] <pixel format>
		 * <pixel type>}: Iris samples it linear/clamped unless an .mcmeta says otherwise. The file is converted on copy.
		 */
		String rawTexture(String value) {
			String[] p = value.trim().split("\\s+");
			String target = p[1].toUpperCase(Locale.ROOT);
			int dims = switch (target) {
				case "TEXTURE_1D" -> 1;
				case "TEXTURE_2D", "TEXTURE_RECTANGLE" -> 2;
				case "TEXTURE_3D" -> 3;
				default -> 0;
			};
			if (p.length != dims + 5 || !KNOWN_FORMATS.contains(p[2].toUpperCase(Locale.ROOT))) {
				warn(null, "unsupported raw custom texture '" + value + "'");
				return null;
			}
			int[] size = new int[dims];
			try {
				for (int i = 0; i < dims; i++) size[i] = Integer.parseInt(p[3 + i]);
			} catch (NumberFormatException e) {
				warn(null, "invalid raw custom texture size '" + value + "'");
				return null;
			}
			String base = p[0].replaceAll("^.*/", "").replaceAll("\\.[^.]*$", "");
			String name = base;
			for (int i = 2; nameTaken(name); i++) name = base + "_" + i;
			textures.put(value, new Manifest.TextureDef(name, "shaders/" + p[0].replaceFirst("^/", ""), null, p[2].toUpperCase(Locale.ROOT), dims,
					size, "linear", "clamp", false, p[dims + 3].toUpperCase(Locale.ROOT) + " " + p[dims + 4].toUpperCase(Locale.ROOT)));
			return name;
		}

		boolean nameTaken(String n) { return textures.values().stream().anyMatch(t -> t.name().equals(n)); }

		/** noisetex: texture.noise PNG, else Iris's generated noise of noiseTextureResolution². */
		String noiseTexture() {
			String v = props.get("texture.noise");
			if (v != null) {
				String n = texture(v.trim(), "noisetex");
				if (n != null) return n;
			}
			String key = "emrion:noise";
			if (!textures.containsKey(key)) {
				int res = (int) num("noiseTextureResolution", 256);
				textures.put(key, new Manifest.TextureDef("noisetex", null, key, "RGBA8", 2, new int[]{res, res}, "linear", "repeat", false, null));
			}
			return "noisetex";
		}

		// ---------------------------------------------------------------------------------------------------------
		// Targets

		List<Manifest.Target> buildTargets() {
			List<Manifest.Target> out = new ArrayList<>();
			for (int n : colortex) {
				String[] f = colortexFormat(n);
				String clearKey = ct(n, "Clear");
				boolean clear = !Boolean.FALSE.equals(bool(clearKey == null ? null : consts.get(clearKey)));
				String ccKey = ct(n, "ClearColor");
				float[] cc = ccKey == null ? null : vec4(consts.get(ccKey));
				boolean fog = cc == null && n == 0;
				if (cc == null && n != 0) cc = n == 1 ? new float[]{1, 1, 1, 1} : new float[]{0, 0, 0, 0};
				float[] rel = {1, 1};
				int[] abs = null;
				String size = props.get("size.buffer.colortex" + n);
				if (size == null && n < 8) size = props.get("size.buffer." + IrisPrograms.LEGACY.get(n));
				if (size != null) {
					String[] t = size.trim().split("\\s+");
					try {
						if (t.length != 2) throw new NumberFormatException();
						abs = new int[2];
						for (int i = 0; i < 2; i++) {
							if (t[i].contains(".")) rel[i] = Float.parseFloat(t[i]);
							else abs[i] = Integer.parseInt(t[i]);
						}
					} catch (NumberFormatException e) { // one bad line must not drop the dimension: screen size
						rel[0] = rel[1] = 1;
						abs = null;
						warn(null, "invalid size.buffer.colortex" + n + " = " + size);
					}
				}
				out.add(new Manifest.Target("colortex" + n, "color", f[1], f[0], 2, rel, abs, mipmapped.contains(n) ? 0 : 1,
						needsAlt.contains(n), clear, cc, fog, 0));
			}
			out.add(depth("depthtex0", true));
			for (String d : List.of("depthtex1", "depthtex2")) if (referencedTargets.contains(d)) out.add(depth(d, false));
			if (shadowTargets) {
				int res = (int) num("shadowMapResolution", 1024);
				for (int i = 0; i < 2; i++)
					out.add(new Manifest.Target("shadowtex" + i, "depth", "D32F", "D32F", 2, null, new int[]{res, res},
							depthMip(i) ? 0 : 1, false, i == 0, null, false, 0));
				if (shadowRender) for (var g : geometry.keySet()) if (g.startsWith("shadow")) for (int n : drawBuffers(analyses.get(g))) shadowColors.add("shadowcolor" + n);
				for (String sc : shadowColors) {
					int i = sc.charAt(11) - '0';
					String[] f = format(consts.get(sc + "Format"));
					boolean clear = !Boolean.FALSE.equals(bool(consts.get(sc + "Clear")));
					float[] cc = vec4(consts.get(sc + "ClearColor"));
					out.add(new Manifest.Target(sc, "shadow_color", f[1], f[0], 2, null, new int[]{res, res}, colorMip(i) || shadowcompMips.contains(i) ? 0 : 1,
							shadowNeedsAlt.contains(i), clear, cc == null ? new float[]{1, 1, 1, 1} : cc, false, 0));
				}
			}
			images.forEach((name, t) -> {
				String[] f = format(t[2]);
				boolean relative = Boolean.parseBoolean(t[5]);
				int[] abs = null;
				float[] rel = null;
				int dims = 2;
				if (relative && t.length >= 8) rel = new float[]{Float.parseFloat(t[6]), Float.parseFloat(t[7])};
				else {
					dims = t.length - 6;
					abs = new int[dims];
					for (int i = 0; i < dims; i++) abs[i] = Integer.parseInt(t[6 + i]);
				}
				out.add(new Manifest.Target(name, "image", f[1], f[0], dims, rel, abs, 1, false, Boolean.parseBoolean(t[4]),
						new float[]{0, 0, 0, 0}, false, 0));
			});
			props.forEach((k, v) -> {
				Matcher m = Pattern.compile("bufferObject\\.(\\d+)").matcher(k);
				if (!m.matches()) return;
				String[] t = v.trim().split("\\s+");
				long bytes = Long.parseLong(t[0]);
				if (bytes < 1) return;
				boolean relative = t.length >= 4 && Boolean.parseBoolean(t[1]);
				float[] rel = relative ? new float[]{Float.parseFloat(t[2]), Float.parseFloat(t[3])} : null;
				out.add(new Manifest.Target("bufferObject" + m.group(1), "buffer", null, null, 0, rel, null, 1, false, false, null, false, bytes));
			});
			return out;
		}

		static Manifest.Target depth(String name, boolean clear) {
			return new Manifest.Target(name, "depth", "D32F", "D32F", 2, new float[]{1, 1}, null, 1, false, clear, null, false, 0);
		}

		/** const key for colortexN or its legacy alias, colortexN preferred; null if neither is set. */
		String ct(int n, String suffix) {
			String k = "colortex" + n + suffix;
			if (consts.containsKey(k)) return k;
			String alias = n < 8 ? IrisPrograms.LEGACY.get(n) + suffix : null;
			return alias != null && consts.containsKey(alias) ? alias : null;
		}

		/** [declared, promoted]. */
		String[] colortexFormat(int n) {
			String k = ct(n, "Format");
			return format(k == null ? null : consts.get(k));
		}

		String[] format(String raw) {
			String f = raw == null ? "RGBA8" : raw.trim().toUpperCase(Locale.ROOT);
			if (f.equals("RGBA")) f = "RGBA8";
			if (!KNOWN_FORMATS.contains(f)) {
				warn(null, "unknown texture format " + raw + ", using RGBA8");
				f = "RGBA8";
			}
			return new String[]{f, promote(f)};
		}

		static boolean isInteger(String f) { return f.endsWith("I"); }

		// ---------------------------------------------------------------------------------------------------------
		// Shadow directives

		boolean any(String... keys) {
			for (String k : keys) if (Boolean.TRUE.equals(bool(consts.get(k)))) return true;
			return false;
		}

		boolean hw(int i) {
			Boolean b = bool(consts.get("shadowHardwareFiltering" + i));
			return b != null ? b : any("shadowHardwareFiltering");
		}

		boolean depthMip(int i) { return any("generateShadowMipmap", "shadowtex" + i + "Mipmap") || i == 0 && any("shadowtexMipmap"); }

		boolean depthNearest(int i) {
			return any("shadowtex" + i + "Nearest", "shadow" + i + "MinMagNearest") || i == 0 && any("shadowtexNearest");
		}

		boolean colorMip(int i) { return any("generateShadowColorMipmap", "shadowcolor" + i + "Mipmap", "shadowColor" + i + "Mipmap"); }

		boolean colorNearest(int i) {
			return any("shadowcolor" + i + "Nearest", "shadowColor" + i + "Nearest", "shadowColor" + i + "MinMagNearest");
		}

		Manifest.Shadow shadowSettings() {
			String fov = consts.get("shadowMapFov");
			String culling = switch (props.getOrDefault("shadow.culling", "").trim()) {
				case "false" -> "distance";
				case "true" -> "advanced";
				case "reversed", "safe_zone" -> "reversed";
				default -> "default";
			};
			return new Manifest.Shadow(shadowRender, (int) num("shadowMapResolution", 1024), num("shadowDistance", 160),
					num("shadowDistanceRenderMul", -1), num("entityShadowDistanceMul", 1), num("shadowNearPlane", -100.05f),
					num("shadowFarPlane", 156), fov == null ? null : num("shadowMapFov", 0), num("shadowIntervalSize", 2),
					num("sunPathRotation", 0), new boolean[]{hw(0), hw(1)}, culling, num("voxelDistance", 0));
		}

		float num(String key, float def) {
			String v = consts.get(key);
			if (v == null) return def;
			try {
				return Float.parseFloat(v.trim());
			} catch (NumberFormatException e) {
				warn(null, "const " + key + " = " + v + " is not a number");
				return def;
			}
		}

		// ---------------------------------------------------------------------------------------------------------
		// Per-program properties

		Manifest.Blend blend(String program, String kind, List<Manifest.Output> outputs) {
			Manifest.BlendMode def = switch (kind) {
				case "gbuffers" -> program.equals("gbuffers_spidereyes")
						? new Manifest.BlendMode("custom", "src_alpha", "one", "zero", "one") : mode("vanilla");
				default -> mode("off");
			};
			String v = props.get("blend." + program);
			if (v != null) def = parseBlend(program, v, def);
			Map<String, Manifest.BlendMode> per = new TreeMap<>();
			String prefix = "blend." + program + ".";
			for (var e : props.entrySet()) {
				if (!e.getKey().startsWith(prefix)) continue;
				int n = colortexIndex(e.getKey().substring(prefix.length()));
				for (var o : outputs)
					if (o.target().equals("colortex" + n) || o.target().equals("shadowcolor" + n))
						per.put(o.target(), parseBlend(program, e.getValue(), def));
			}
			return new Manifest.Blend(def, per);
		}

		Manifest.BlendMode parseBlend(String program, String v, Manifest.BlendMode fallback) {
			String[] t = v.trim().toLowerCase(Locale.ROOT).split("\\s+");
			if (t.length == 1 && t[0].equals("off")) return mode("off");
			if (t.length == 4) return new Manifest.BlendMode("custom", t[0], t[1], t[2], t[3]);
			warn(program, "invalid blend directive '" + v + "'");
			return fallback;
		}

		static Manifest.BlendMode mode(String m) { return new Manifest.BlendMode(m, null, null, null, null); }

		Manifest.AlphaTest alphaTest(String program) {
			String v = props.get("alphaTest." + program);
			if (v == null) return null;
			String[] t = v.trim().toLowerCase(Locale.ROOT).split("\\s+");
			if (t[0].equals("off") || t[0].equals("false")) return new Manifest.AlphaTest("always", 0);
			try {
				return new Manifest.AlphaTest(t[0].replaceFirst("^gl_", ""), Float.parseFloat(t[1]));
			} catch (RuntimeException e) {
				warn(program, "invalid alphaTest directive '" + v + "'");
				return null;
			}
		}

		/** scale.&lt;program&gt; = scale [offsetX offsetY] (Iris ShaderProperties): {scale, x, y}; unparsable → default, as Iris. */
		float[] viewport(String program) {
			String v = props.get("scale." + program);
			if (v == null) return new float[]{1, 0, 0};
			String[] t = v.trim().split("\\s+");
			try {
				return new float[]{Float.parseFloat(t[0]), t.length > 1 ? Float.parseFloat(t[1]) : 0, t.length > 1 ? Float.parseFloat(t[2]) : 0};
			} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
				warn(program, "unparsable scale." + program + " = " + v + "; ignored");
				return new float[]{1, 0, 0};
			}
		}

		// ---------------------------------------------------------------------------------------------------------
		// Helpers

		List<Integer> drawBuffers(Transpiler.ProgramAnalysis a) {
			var d = directives(a);
			if (d != null && d.drawBuffers() != null) return d.drawBuffers();
			return a.group() == ProgramGroup.SHADOW ? List.of(0, 1) : List.of(0);
		}

		Set<Integer> mipmapBuffers(Transpiler.ProgramAnalysis a) {
			Set<Integer> s = new TreeSet<>();
			var d = directives(a);
			if (d == null) return s;
			for (int n = 0; n < 32; n++) {
				String k = "colortex" + n + "MipmapEnabled";
				String v = d.consts().containsKey(k) || n >= 8 ? d.consts().get(k) : d.consts().get(IrisPrograms.LEGACY.get(n) + "MipmapEnabled");
				if (Boolean.TRUE.equals(bool(v))) s.add(n);
			}
			return s;
		}

		static Directives directives(Transpiler.ProgramAnalysis a) {
			var f = fragment(a.stages());
			if (f != null) return f;
			for (var s : a.stages()) if (s.stage() == StageType.COMPUTE) return s.directives();
			return null;
		}

		static Directives fragment(List<PreprocessedStage> stages) {
			for (var s : stages) if (s.stage() == StageType.FRAGMENT) return s.directives();
			return null;
		}

		static boolean isCompute(Transpiler.ProgramAnalysis a) { return a.stages().stream().anyMatch(s -> s.stage() == StageType.COMPUTE); }

		static int colortexIndex(String name) {
			Matcher m = COLORTEX.matcher(name);
			if (m.matches()) {
				int n = Integer.parseInt(m.group(1));
				return n < 32 ? n : -1;
			}
			return IrisPrograms.legacyIndex(name);
		}

		static Boolean bool(String v) {
			if (v == null) return null;
			return switch (v.trim()) {
				case "true", "1" -> true;
				case "false", "0" -> false;
				default -> null;
			};
		}

		float[] vec4(String v) {
			if (v == null) return null;
			Matcher m = Pattern.compile("vec4\\s*\\(([^)]*)\\)").matcher(v.trim());
			if (!m.matches()) return null;
			String[] p = m.group(1).split(",");
			float[] f = new float[4];
			try {
				if (p.length != 1 && p.length != 4) throw new NumberFormatException();
				for (int i = 0; i < 4; i++) f[i] = Float.parseFloat(p[p.length == 1 ? 0 : i].trim());
			} catch (NumberFormatException e) { // "1.0/255.0" or 3 components: unset, rather than drop the dimension
				warn(null, "invalid vec4 " + v);
				return null;
			}
			return f;
		}

		static Manifest.SamplerDef sampler(String filter, String mip, String wrap, String compare) {
			return new Manifest.SamplerDef(filter, mip, wrap, compare);
		}

		void warn(String program, String msg) { diag.add(new Diagnostic(Diagnostic.Severity.WARNING, null, 0, program, null, msg)); }

		void info(String program, String msg) { diag.add(new Diagnostic(Diagnostic.Severity.INFO, null, 0, program, null, msg)); }
	}

	// ---- command factories ----

	static Command cmd(String c, String program, int flipState) {
		return new Command(c, program, flipState, null, null, null, null, null, null, null);
	}

	static Command clear(List<Manifest.ClearOp> ops) { return new Command("clear", null, null, null, null, null, null, null, null, List.copyOf(ops)); }

	static Command mipmaps(String target, String side) { return new Command("mipmaps", null, null, null, target, side, null, null, null, null); }

	static Command geometryCmd(String phase, int flipState) { return new Command("geometry", null, flipState, phase, null, null, null, null, null, null); }

	static Command copyDepth(String from, String to) { return new Command("copy_depth", null, null, null, null, null, from, to, null, null); }

	static Manifest.Set0Ref set0Ref(String name) {
		String type = name.startsWith("Sampler") || name.startsWith("Emrion") ? "combined_image_sampler" : name.equals("CloudFaces") ? "texel_buffer" : "uniform_buffer";
		return new Manifest.Set0Ref(name, type);
	}
}
