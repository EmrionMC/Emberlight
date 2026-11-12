package dev.emrion.shaders.converter.pack;

import dev.emrion.shaders.converter.glsl.PreprocessedStage;
import dev.emrion.shaders.converter.glsl.StageType;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pack-wide const directives of one dimension (render-target formats/clears, shadow settings, …): Iris merges the
 * fragment-stage consts of every enabled program in its directive order, a later program overriding an earlier one
 * (iris-spec §3.4).
 */
public final class PackConsts {
	private PackConsts() {}

	public static Map<String, String> merge(List<ResolvedPack.ProgramSource> programs) {
		Map<String, String> out = new LinkedHashMap<>();
		programs.stream().filter(ResolvedPack.ProgramSource::enabled)
				.flatMap(p -> p.stages().stream())
				.filter(s -> s.stage() == StageType.FRAGMENT && ProgramCatalog.directiveRank(s.group()) >= 0)
				.sorted(Comparator.comparingInt((PreprocessedStage s) -> ProgramCatalog.directiveRank(s.group())))
				.forEach(s -> out.putAll(s.directives().consts()));
		return out;
	}
}
