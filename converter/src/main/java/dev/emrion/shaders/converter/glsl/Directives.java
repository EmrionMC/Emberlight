package dev.emrion.shaders.converter.glsl;

import java.util.List;
import java.util.Map;

/**
 * Per-stage source directives. drawBuffers: last DRAWBUFFERS/RENDERTARGETS, null = Iris default. consts: every const Iris
 * understands, value as written, last wins. Compute sizes are null for other stages.
 */
public record Directives(
		List<Integer> drawBuffers,
		boolean renderTargetsSyntax,
		Map<String, String> consts,
		int[] localSize,
		int[] workGroups,
		float[] workGroupsRender
) {}
