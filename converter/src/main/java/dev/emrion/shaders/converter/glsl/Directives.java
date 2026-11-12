package dev.emrion.shaders.converter.glsl;

import java.util.List;
import java.util.Map;

/**
 * Per-stage source directives (iris-spec §3.2-3.4). Producer: agent (a) DirectiveParser.
 * drawBuffers: from the last DRAWBUFFERS/RENDERTARGETS comment, null if none (Iris default then applies).
 * consts: every "const <type> <name> = <literal>;" Iris understands (shadowMapResolution, colortex5Format, colortex0MipmapEnabled, ...),
 * value as written (e.g. "RGBA16F", "2048", "true"). Last occurrence wins.
 * localSize / workGroups / workGroupsRender: compute only, else null.
 */
public record Directives(
		List<Integer> drawBuffers,
		boolean renderTargetsSyntax,
		Map<String, String> consts,
		int[] localSize,
		int[] workGroups,
		float[] workGroupsRender
) {}
