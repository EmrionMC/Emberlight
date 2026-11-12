package dev.emrion.shaders.converter.glsl;

import dev.emrion.shaders.format.Manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Test helpers: a trivial EmitContext and glslangValidator runs. */
final class TranspilerTestSupport {
	private TranspilerTestSupport() {}

	static PreprocessedStage stage(String program, ProgramGroup group, StageType type, String text) {
		return new PreprocessedStage(program, group, type, "shaders/" + program + "." + type.extension, text, LineMap.IDENTITY_UNKNOWN,
				new Directives(null, false, Map.of(), null, null, null));
	}

	/** Sequential std140 layout of all referenced uniforms, sequential set-1 bindings, identity outputs. */
	static Transpiler.EmitContext context(Transpiler.ProgramAnalysis a, Manifest.AlphaTest alpha) {
		Map<String, Manifest.UniformMember> members = new LinkedHashMap<>();
		int offset = 0;
		for (Transpiler.UniformDecl u : a.uniforms()) {
			int[] sa = sizeAlign(u.glslType());
			int size = sa[0], align = sa[1], stride = 0;
			if (u.arraySize() > 0) {
				align = 16;
				stride = (size + 15) / 16 * 16;
				size = stride * u.arraySize();
			}
			offset = (offset + align - 1) / align * align;
			members.put(u.name(), new Manifest.UniformMember(u.name(), u.glslType(), offset, u.arraySize(), stride, "builtin"));
			offset += size;
		}
		Map<String, Integer> bindings = new HashMap<>();
		Map<Integer, Integer> outputs = new HashMap<>();
		for (int i = 0; i < 16; i++) outputs.put(i, i);
		return new Transpiler.EmitContext(members, n -> bindings.computeIfAbsent(n, k -> 2 + bindings.size()), outputs, alpha);
	}

	static int[] sizeAlign(String type) {
		return switch (type) {
			case "float", "int", "uint", "bool" -> new int[] {4, 4};
			case "vec2", "ivec2", "uvec2", "bvec2" -> new int[] {8, 8};
			case "vec3", "ivec3", "uvec3", "bvec3" -> new int[] {12, 16};
			case "mat2" -> new int[] {32, 16};
			case "mat3" -> new int[] {48, 16};
			case "mat4" -> new int[] {64, 16};
			default -> new int[] {16, 16};
		};
	}

	/** Runs glslangValidator for Vulkan 1.2 on the source; returns null when it validates, else the error output. */
	static String validate(Path dir, String name, StageType stage, String glsl) throws IOException, InterruptedException {
		String ext = switch (stage) {
			case VERTEX -> "vert";
			case GEOMETRY -> "geom";
			case FRAGMENT -> "frag";
			case COMPUTE -> "comp";
		};
		Path f = dir.resolve(name + "." + ext);
		Files.writeString(f, glsl);
		Process p = new ProcessBuilder("glslangValidator", "-V", "--target-env", "vulkan1.2", "-o", dir.resolve(name + ".spv").toString(), f.toString())
				.redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes());
		if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly();
		return p.exitValue() == 0 ? null : out;
	}

	static boolean validatorAvailable() {
		try {
			Process p = new ProcessBuilder("glslangValidator", "--version").redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();
			return p.waitFor() == 0;
		} catch (IOException | InterruptedException e) {
			return false;
		}
	}

	static List<StageType> ORDER = List.of(StageType.VERTEX, StageType.GEOMETRY, StageType.FRAGMENT, StageType.COMPUTE);
}
