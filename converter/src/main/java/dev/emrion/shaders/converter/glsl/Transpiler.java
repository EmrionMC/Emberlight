package dev.emrion.shaders.converter.glsl;

import dev.emrion.shaders.converter.report.Diagnostic;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Old/compat GLSL → Vulkan GLSL 450 (ARCHITECTURE §4.1). Two phases, because the shared uniform block and the
 * resource table are the union over all programs:
 * <ol>
 *   <li>{@link #analyze} every program (all stages together, so varyings can be linked by name);</li>
 *   <li>the converter builds the {@code EmrionUniforms} layout and set-1 resource table from all analyses;</li>
 *   <li>{@link #emit} each program once per vertex variant.</li>
 * </ol>
 * Owner: agent (b). Implementations must be stateless / thread-safe.
 */
public interface Transpiler {

	ProgramAnalysis analyze(List<PreprocessedStage> stages);

	/** Emits one GLSL 450 source per stage for the given vertex variant ("fullscreen" for composite-like programs). */
	EmitResult emit(ProgramAnalysis analysis, EmitContext context, String vertexVariant);

	/** Everything the converter needs to know about a program's interface. */
	record ProgramAnalysis(
			String program,
			ProgramGroup group,
			List<PreprocessedStage> stages,
			List<UniformDecl> uniforms,      // non-opaque uniforms that are referenced (built-in or custom; name+type)
			List<SamplerDecl> samplers,      // sampler*/usampler*/isampler* that are referenced
			List<ImageDecl> images,          // image* uniforms that are referenced
			List<BufferDecl> buffers,        // SSBO blocks
			List<String> set0,               // MC per-draw resources needed (names from VertexLayouts.SET0_CANONICAL)
			List<String> vertexInputs,       // canonical input names used (VertexLayouts.INPUT_LOCATIONS)
			List<Integer> fragmentOutputs,   // gl_FragData indices / out locations actually written
			List<Diagnostic> diagnostics
	) {}

	/** glslType e.g. "mat4", "ivec2", "float"; arraySize 0 = not an array; initializer = GLSL literal text or null. */
	record UniformDecl(String name, String glslType, int arraySize, String initializer) {}

	/** glslType e.g. "sampler2D", "sampler2DShadow", "usampler3D". */
	record SamplerDecl(String name, String glslType) {}

	/** format = layout qualifier e.g. "r16ui", null if none; access: readonly | writeonly | readwrite. */
	record ImageDecl(String name, String glslType, String format, String access) {}

	/** declaredBinding = the pack's "binding = N" (Iris bufferObject index). */
	record BufferDecl(String blockName, int declaredBinding, boolean readonly) {}

	/**
	 * Inputs for code generation.
	 * uniformMembers: name → member of the shared block (absent = uniform not provided → const default).
	 * resourceBinding: (pack sampler/image/buffer name) → set-1 binding for this program, or null if unbound (→ error/dummy).
	 * outputTargets: fragment output index (DRAWBUFFERS position) → attachment location.
	 */
	record EmitContext(
			Map<String, dev.emrion.shaders.format.Manifest.UniformMember> uniformMembers,
			Function<String, Integer> resourceBinding,
			Map<Integer, Integer> outputLocations,
			dev.emrion.shaders.format.Manifest.AlphaTest alphaTest
	) {}

	/** glsl: stage → GLSL 450 text. */
	record EmitResult(Map<StageType, String> glsl, List<Diagnostic> diagnostics) {}
}
