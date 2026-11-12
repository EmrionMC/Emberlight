package dev.emrion.shaders.mod.debug;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.emrion.shaders.mod.EmrionShadersClient;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Dev only ({@code -Demrion.debug.logPipelines=true}): logs each vanilla pipeline once with its uniforms() order and vertex formats. */
public final class PipelineLog {
	public static final boolean ENABLED = Boolean.getBoolean("emrion.debug.logPipelines");
	private static final Set<RenderPipeline> SEEN = Collections.newSetFromMap(new IdentityHashMap<>());

	private PipelineLog() {}

	public static void seen(RenderPipeline pipeline, CompiledRenderPipeline compiled) {
		if (!(compiled instanceof FrontendRenderPipeline f) || !SEEN.add(pipeline)) return;
		// Bound order (= set-0 binding index) is the backend's list; the frontend record lists every declared uniform.
		String uniforms = ((VulkanRenderPipeline) f.backendRenderPipeline()).uniforms().stream().map(u -> u.name() + ":" + u.type()).collect(Collectors.joining(", "));
		String vertex = f.vertexFormats().stream().filter(Objects::nonNull).map(VertexFormat::toString).collect(Collectors.joining(" + "));
		String targets = f.colorTargetStates().stream().map(t -> t == null ? "-" : t.format() + (t.blendFunction().isPresent() ? "+blend" : ""))
				.collect(Collectors.joining(","));
		EmrionShadersClient.LOG.info("Pipeline {} set0=[{}] vertex=[{}] push={} targets=[{}] depth={}", pipeline.getLocation(), uniforms, vertex,
				f.pushConstantSize(), targets, pipeline.getDepthStencilState() != null);
	}
}
