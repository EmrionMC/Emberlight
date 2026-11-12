package dev.emrion.shaders.converter.spirv;

import dev.emrion.shaders.converter.glsl.LineMap;
import dev.emrion.shaders.converter.glsl.StageType;
import dev.emrion.shaders.converter.report.Diagnostic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ShadercSpirvCompilerTest {
	static final ShadercSpirvCompiler COMPILER = new ShadercSpirvCompiler();

	@AfterAll
	static void close() { COMPILER.close(); }

	@Test
	void compilesEveryStageKindToValidSpirv() throws Exception {
		for (var c : List.of(
				new Object[]{TestShaders.VERTEX, StageType.VERTEX}, new Object[]{TestShaders.GEOMETRY, StageType.GEOMETRY},
				new Object[]{TestShaders.FRAGMENT, StageType.FRAGMENT}, new Object[]{TestShaders.COMPUTE, StageType.COMPUTE})) {
			var r = COMPILER.compile("t", (String) c[0], (StageType) c[1]);
			assertNotNull(r.spv(), r.diagnostics()::toString);
			assertEquals(0x07230203, SpirvPatcher.words(r.spv()).get(0));
			assertEquals(0x00010500, SpirvPatcher.words(r.spv()).get(1), "SPIR-V 1.5");
			TestShaders.validateIfAvailable(r.spv());
		}
	}

	@Test
	void errorsCarryMappedLines() {
		String src = "#version 450\nlayout(location=0) out vec4 c;\nvoid main() {\n  c = undefinedThing;\n}\n";
		LineMap map = new LineMap() {
			public String file(int l) { return "shaders/program/x.glsl"; }
			public int line(int l) { return l + 100; }
		};
		var r = COMPILER.compile("x.fsh", src, StageType.FRAGMENT, map);
		assertNull(r.spv());
		Diagnostic d = r.diagnostics().getFirst();
		assertEquals(Diagnostic.Severity.ERROR, d.severity());
		assertEquals("shaders/program/x.glsl", d.file());
		assertEquals(104, d.line());
		assertTrue(d.message().contains("undefinedThing"), d.message());
	}

	@Test
	void lineDirectiveFileWinsOverLineMap() {
		var ds = ShadercSpirvCompiler.parseMessages("/world0/deferred1.fsh:12: error: 'x' : undeclared identifier\n1 error generated.\n",
				"deferred1.fsh", LineMap.IDENTITY_UNKNOWN);
		assertEquals(List.of(new Diagnostic(Diagnostic.Severity.ERROR, "/world0/deferred1.fsh", 12, null, null, "'x' : undeclared identifier")), ds);
	}

	@Test
	void missingLocationFailsInsteadOfAutoMapping() {
		var r = COMPILER.compile("noloc", "#version 450\nout vec4 c;\nvoid main() { c = vec4(1); }\n", StageType.FRAGMENT);
		assertNull(r.spv());
	}

	@Test
	void optimisationStripsNamesButKeepsDecorations() {
		var r = COMPILER.compile("t", TestShaders.VERTEX, StageType.VERTEX);
		var refl = SpirvReflection.reflect(r.spv());
		// -O without debug info runs strip-debug: no OpName survives, Binding/DescriptorSet/Location decorations do.
		assertTrue(refl.inputs().stream().allMatch(v -> v.name().isEmpty()), refl.toString());
		assertTrue(refl.descriptors().stream().noneMatch(d -> d.name().equals("EmrionUniforms")), refl.toString());
		assertEquals(1, SpirvPatcher.set0Bindings(SpirvPatcher.words(r.spv())).size());
	}

	@Test
	void compilesInParallelAndReportsMidSizeTiming() throws Exception {
		String src = midSizeFragment(150);
		var first = COMPILER.compile("mid", src, StageType.FRAGMENT);
		assertNotNull(first.spv(), first.diagnostics()::toString);
		long t0 = System.nanoTime();
		int n = 5;
		for (int i = 0; i < n; i++) COMPILER.compile("mid", src, StageType.FRAGMENT);
		double ms = (System.nanoTime() - t0) / 1e6 / n;
		System.out.printf("shaderc -O mid-size fragment (%d lines, %d B SPIR-V): %.1f ms%n", src.lines().count(), first.spv().length, ms);

		try (var pool = Executors.newFixedThreadPool(8)) {
			var futures = IntStream.range(0, 16).mapToObj(i -> pool.submit(() -> COMPILER.compile("p" + i, src, StageType.FRAGMENT))).toList();
			for (var f : futures) assertArrayEquals(first.spv(), f.get().spv(), "deterministic output across threads");
		}
	}

	/** ~1000 lines: many helper functions, loops and texture reads, roughly a Complementary composite after transpiling. */
	static String midSizeFragment(int functions) {
		StringBuilder sb = new StringBuilder("#version 450\n");
		sb.append("layout(set = 1, binding = 0) uniform EmrionUniforms { mat4 gbufferProjectionInverse; vec3 cameraPosition; float frameTimeCounter; };\n");
		sb.append("layout(set = 1, binding = 2) uniform sampler2D colortex0;\nlayout(set = 1, binding = 3) uniform sampler2D depthtex0;\n");
		sb.append("layout(location = 0) in vec2 texcoord;\nlayout(location = 0) out vec4 outColor0;\n");
		for (int i = 0; i < functions; i++) {
			sb.append("vec3 f").append(i).append("(vec3 p, float t) {\n");
			sb.append("  vec3 acc = vec3(0.0);\n");
			sb.append("  for (int k = 0; k < 4; k++) {\n");
			sb.append("    vec2 o = vec2(float(k) * 0.01, t * ").append(i % 7 + 1).append(".0 * 0.001);\n");
			sb.append("    acc += texture(colortex0, p.xy + o).rgb * exp2(-float(k));\n");
			sb.append("  }\n");
			sb.append("  return ").append(i == 0 ? "acc" : "mix(acc, f" + (i - 1) + "(p * 0.5, t), 0.5)").append(";\n}\n");
		}
		sb.append("void main() {\n  float d = texture(depthtex0, texcoord).r;\n");
		sb.append("  vec4 v = gbufferProjectionInverse * vec4(vec3(texcoord, d) * 2.0 - 1.0, 1.0);\n");
		sb.append("  vec3 p = v.xyz / v.w + cameraPosition;\n");
		sb.append("  outColor0 = vec4(f").append(functions - 1).append("(vec3(texcoord, p.z), frameTimeCounter), 1.0);\n}\n");
		return sb.toString();
	}
}
