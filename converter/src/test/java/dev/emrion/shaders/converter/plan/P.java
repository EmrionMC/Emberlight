package dev.emrion.shaders.converter.plan;

import dev.emrion.shaders.converter.glsl.Directives;
import dev.emrion.shaders.converter.glsl.PreprocessedStage;
import dev.emrion.shaders.converter.glsl.ProgramGroup;
import dev.emrion.shaders.converter.glsl.StageType;
import dev.emrion.shaders.converter.glsl.Transpiler;
import dev.emrion.shaders.converter.pack.ResolvedPack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Hand-written program fixture: builds a ProgramSource and a ProgramAnalysis. */
final class P {
	final String name;
	final ProgramGroup group;
	List<Integer> drawBuffers;
	final Map<String, String> consts = new HashMap<>();
	final List<Transpiler.UniformDecl> uniforms = new ArrayList<>();
	final List<Transpiler.SamplerDecl> samplers = new ArrayList<>();
	final List<Transpiler.ImageDecl> images = new ArrayList<>();
	final List<Transpiler.BufferDecl> buffers = new ArrayList<>();
	boolean compute;
	int[] localSize, workGroups;
	float[] workGroupsRender;

	P(String name) {
		this.name = name;
		this.group = name.startsWith("gbuffers_") ? ProgramGroup.GBUFFERS
				: name.startsWith("shadowcomp") ? ProgramGroup.SHADOWCOMP
				: name.startsWith("shadow") ? ProgramGroup.SHADOW
				: ProgramGroup.valueOf(name.replaceAll("[0-9_].*$", "").toUpperCase());
	}

	static P p(String name) { return new P(name); }

	P draw(Integer... b) { drawBuffers = List.of(b); return this; }

	P sampler(String n, String t) { samplers.add(new Transpiler.SamplerDecl(n, t)); return this; }

	P samplers(String... names) {
		for (String n : names) sampler(n, "sampler2D");
		return this;
	}

	P image(String n, String t, String access) { images.add(new Transpiler.ImageDecl(n, t, null, access)); return this; }

	P buffer(String block, int binding, boolean readonly) { buffers.add(new Transpiler.BufferDecl(block, binding, readonly, true)); return this; }

	P uniform(String n, String t) { uniforms.add(new Transpiler.UniformDecl(n, t, 0, null)); return this; }

	P uniform(String n, String t, int arraySize) { uniforms.add(new Transpiler.UniformDecl(n, t, arraySize, null)); return this; }

	P c(String k, String v) { consts.put(k, v); return this; }

	P compute(int[] local, int[] groups) { compute = true; localSize = local; workGroups = groups; return this; }

	private List<PreprocessedStage> stages() {
		if (compute)
			return List.of(new PreprocessedStage(name, group, StageType.COMPUTE, "shaders/world0/" + name + ".csh", "", null,
					new Directives(null, false, consts, localSize, workGroups, workGroupsRender)));
		return List.of(
				new PreprocessedStage(name, group, StageType.VERTEX, "shaders/world0/" + name + ".vsh", "", null,
						new Directives(null, false, Map.of(), null, null, null)),
				new PreprocessedStage(name, group, StageType.FRAGMENT, "shaders/world0/" + name + ".fsh", "", null,
						new Directives(drawBuffers, false, consts, null, null, null)));
	}

	ResolvedPack.ProgramSource source() { return new ResolvedPack.ProgramSource(name, true, stages()); }

	Transpiler.ProgramAnalysis analysis() {
		return new Transpiler.ProgramAnalysis(name, group, stages(), uniforms, samplers, images, buffers, List.of(), List.of(),
				List.of(), List.of());
	}
}
