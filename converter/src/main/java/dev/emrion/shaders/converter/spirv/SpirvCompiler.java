package dev.emrion.shaders.converter.spirv;

import dev.emrion.shaders.converter.glsl.LineMap;
import dev.emrion.shaders.converter.glsl.StageType;
import dev.emrion.shaders.converter.report.Diagnostic;

import java.util.List;

/**
 * GLSL 450 → SPIR-V via lwjgl-shaderc (target Vulkan 1.2 / SPIR-V 1.5, optimisation level "performance").
 * Must be thread-safe (pool shaderc compilers like MC's GlslCompiler does).

 */
public interface SpirvCompiler extends AutoCloseable {

	/**
	 * @param lineMap maps a 1-based line of {@code glsl450} to the file/line reported in diagnostics; a null file
	 *                there falls back to {@code name}.
	 */
	Result compile(String name, String glsl450, StageType stage, LineMap lineMap);

	default Result compile(String name, String glsl450, StageType stage) {
		return compile(name, glsl450, stage, LineMap.IDENTITY_UNKNOWN);
	}

	/**
	 * Names the compiler build for the per-stage SPIR-V cache (its options are code, covered by Converter.VERSION);
	 * null: its output is never cached.
	 */
	default String cacheId() { return null; }

	/** spv is null on failure; diagnostics carry shaderc messages mapped back to the input line. */
	record Result(byte[] spv, List<Diagnostic> diagnostics) {}

	@Override
	void close();
}
