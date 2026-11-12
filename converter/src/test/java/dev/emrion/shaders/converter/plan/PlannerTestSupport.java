package dev.emrion.shaders.converter.plan;

import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.stream.Collectors;

/** Runs the planner on hand-written fixtures and offers small lookups over the result. */
final class PlannerTestSupport {
	private PlannerTestSupport() {}

	static SequencedMap<String, String> props(String... kv) {
		SequencedMap<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
		return m;
	}

	static DimensionPlannerResult plan(SequencedMap<String, String> props, P... programs) {
		var analyses = Arrays.stream(programs).collect(Collectors.toMap(p -> p.name, P::analysis));
		var sources = Arrays.stream(programs).map(P::source).toList();
		var diags = new Diagnostics();
		var layout = new Std140LayoutBuilder(TestCatalog::lookup).build(analyses.values(), List.of(), diags);
		var plan = new IrisDimensionPlanner().plan("world0", sources, analyses, props, Map.of(), layout, diags);
		return new DimensionPlannerResult(plan, diags);
	}

	record DimensionPlannerResult(DimensionPlanner.DimensionPlan plan, Diagnostics diags) {
		Manifest.Dimension dim() { return plan.dimension(); }

		Manifest.Target target(String name) {
			return dim().targets().stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
		}

		Manifest.Resource binding(String program, String sampler) {
			Integer b = plan.emits().get(program).context().resourceBinding().apply(sampler);
			if (b == null) return null;
			return dim().resources().stream().filter(r -> r.binding() == b).findFirst().orElseThrow();
		}

		List<Integer> flipState(int id) {
			return dim().flipStates().stream().filter(f -> f.id() == id).findFirst().orElseThrow().alt();
		}

		Manifest.Command command(String cmd, String program) {
			return dim().frame().stream().filter(c -> c.cmd().equals(cmd) && Objects.equals(c.program(), program)).findFirst().orElse(null);
		}

		/** Frame as compact strings, e.g. "fullscreen composite", "geometry opaque", "mipmaps colortex0 main". */
		List<String> frame() {
			return dim().frame().stream().map(c -> switch (c.cmd()) {
				case "geometry" -> "geometry " + c.phase();
				case "mipmaps" -> "mipmaps " + c.target() + " " + c.side();
				case "copy_depth" -> "copy_depth " + c.from() + " " + c.to();
				case "copy" -> "copy " + c.target();
				case "clear" -> "clear";
				default -> c.cmd() + " " + c.program();
			}).toList();
		}

		String side(String program, String target) {
			return command("fullscreen", program).writes().stream().filter(w -> w.target().equals(target)).findFirst().orElseThrow().side();
		}
	}
}
