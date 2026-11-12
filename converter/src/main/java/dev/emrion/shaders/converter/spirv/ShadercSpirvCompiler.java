package dev.emrion.shaders.converter.spirv;

import dev.emrion.shaders.converter.glsl.LineMap;
import dev.emrion.shaders.converter.glsl.StageType;
import dev.emrion.shaders.converter.report.Diagnostic;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.util.shaderc.Shaderc.*;

/** Thread-safe: compiler handles are pooled, options are created per call (they are cheap). */
public final class ShadercSpirvCompiler implements SpirvCompiler {
	// shaderc message lines: "<name>:<line>: error: <msg>" or "<name>: error: <msg>"
	private static final Pattern MESSAGE = Pattern.compile("^(.*?):(?:(\\d+):)?\\s*(error|warning):\\s*(.*)$");

	private final ArrayDeque<Long> pool = new ArrayDeque<>();
	private final List<Long> all = new ArrayList<>();

	@Override
	public Result compile(String name, String glsl450, StageType stage, LineMap lineMap) {
		long options = shaderc_compile_options_initialize();
		long compiler = acquire();
		long result = 0;
		// The CharSequence overload encodes on LWJGL's 64 KiB thread stack; real pack stages are bigger.
		ByteBuffer source = org.lwjgl.system.MemoryUtil.memUTF8(glsl450, false);
		ByteBuffer fileName = org.lwjgl.system.MemoryUtil.memUTF8(name);
		ByteBuffer entry = org.lwjgl.system.MemoryUtil.memUTF8("main");
		try {
			shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
			shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_5);
			shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
			// The transpiler emits every set/binding/location explicitly; anything missing must fail, not be guessed.
			shaderc_compile_options_set_auto_bind_uniforms(options, false);
			shaderc_compile_options_set_auto_map_locations(options, false);
			result = shaderc_compile_into_spv(compiler, source, kind(stage), fileName, entry, options);
			if (result == 0) return new Result(null, List.of(Diagnostic.error(name, 0, "shaderc returned no result")));
			List<Diagnostic> diagnostics = parseMessages(shaderc_result_get_error_message(result), name, lineMap);
			if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
				if (diagnostics.stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR))
					diagnostics.add(Diagnostic.error(name, 0, "compilation failed: " + shaderc_result_get_error_message(result)));
				return new Result(null, diagnostics);
			}
			ByteBuffer bytes = shaderc_result_get_bytes(result);
			byte[] spv = new byte[bytes.remaining()];
			bytes.get(spv);
			return new Result(spv, diagnostics);
		} finally {
			org.lwjgl.system.MemoryUtil.memFree(source);
			org.lwjgl.system.MemoryUtil.memFree(fileName);
			org.lwjgl.system.MemoryUtil.memFree(entry);
			if (result != 0) shaderc_result_release(result);
			release(compiler);
			shaderc_compile_options_release(options);
		}
	}

	static int kind(StageType stage) {
		return switch (stage) {
			case VERTEX -> shaderc_glsl_vertex_shader;
			case GEOMETRY -> shaderc_glsl_geometry_shader;
			case FRAGMENT -> shaderc_glsl_fragment_shader;
			case COMPUTE -> shaderc_glsl_compute_shader;
		};
	}

	static List<Diagnostic> parseMessages(String text, String name, LineMap lineMap) {
		List<Diagnostic> out = new ArrayList<>();
		if (text == null) return out;
		for (String raw : text.split("\n")) {
			Matcher m = MESSAGE.matcher(raw.strip());
			if (!m.matches()) continue; // "N errors generated." and continuation lines
			int outLine = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
			String file = null;
			int line = outLine;
			if (!m.group(1).equals(name)) file = m.group(1); // a #line "file" directive already names the origin
			else if (outLine > 0) {
				file = lineMap.file(outLine);
				line = lineMap.line(outLine);
			}
			var severity = m.group(3).equals("error") ? Diagnostic.Severity.ERROR : Diagnostic.Severity.WARNING;
			out.add(new Diagnostic(severity, file == null ? name : file, line, null, null, m.group(4)));
		}
		return out;
	}

	private synchronized long acquire() {
		if (!pool.isEmpty()) return pool.pop();
		long c = shaderc_compiler_initialize();
		if (c == 0) throw new IllegalStateException("shaderc_compiler_initialize failed");
		all.add(c);
		return c;
	}

	private synchronized void release(long compiler) {
		pool.push(compiler);
	}

	@Override
	public synchronized void close() {
		all.forEach(c -> shaderc_compiler_release(c));
		all.clear();
		pool.clear();
	}
}
