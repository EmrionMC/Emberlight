package dev.emrion.shaders.converter.spirv;

import dev.emrion.shaders.converter.glsl.StageType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpirvReflectionAndPatcherTest {
	static final ShadercSpirvCompiler COMPILER = new ShadercSpirvCompiler();

	@AfterAll
	static void close() { COMPILER.close(); }

	static byte[] spv(String src, StageType stage) {
		var r = COMPILER.compile("t", src, stage);
		assertNotNull(r.spv(), r.diagnostics()::toString);
		return r.spv();
	}

	/** shaderc -O strips OpName, so names are spvc's synthetic ones ("" or "_42_44"); set/binding identify resources. */
	static List<SpirvReflection.Descriptor> withoutNames(List<SpirvReflection.Descriptor> ds) {
		return ds.stream().map(d -> new SpirvReflection.Descriptor(d.set(), d.binding(), d.type(), null)).toList();
	}

	@Test
	void reflectsVertexInterface() {
		var r = SpirvReflection.reflect(spv(TestShaders.VERTEX, StageType.VERTEX));
		assertEquals(List.of(
				new SpirvReflection.Descriptor(0, 0, "uniform_buffer", null),
				new SpirvReflection.Descriptor(1, 0, "uniform_buffer", null)), withoutNames(r.descriptors()));
		assertEquals(20, r.pushConstantSize());
		assertEquals(List.of(0, 2), r.inputs().stream().map(SpirvReflection.Variable::location).toList());
		assertEquals(List.of(0, 1), r.outputs().stream().map(SpirvReflection.Variable::location).toList());
		assertNull(r.localSize());
	}

	@Test
	void reflectsFragmentSamplers() {
		var r = SpirvReflection.reflect(spv(TestShaders.FRAGMENT, StageType.FRAGMENT));
		assertEquals(List.of(
				new SpirvReflection.Descriptor(0, 3, "sampled_image", null),
				new SpirvReflection.Descriptor(1, 2, "sampled_image", null)), withoutNames(r.descriptors()));
		assertEquals(0, r.pushConstantSize());
	}

	@Test
	void reflectsComputeLocalSizeAndStorage() {
		var r = SpirvReflection.reflect(spv(TestShaders.COMPUTE, StageType.COMPUTE));
		assertArrayEquals(new int[]{8, 4, 2}, r.localSize());
		assertEquals(List.of("storage_image@30", "storage_buffer@40"),
				r.descriptors().stream().map(d -> d.type() + "@" + d.binding()).toList());
	}

	@Test
	void patcherFindsAndRewritesOnlySet0Bindings() {
		byte[] module = spv(TestShaders.FRAGMENT, StageType.FRAGMENT);
		var words = SpirvPatcher.words(module);
		var set0 = SpirvPatcher.set0Bindings(words);
		assertEquals(List.of(3), set0.stream().map(SpirvPatcher.Set0Binding::binding).toList());

		SpirvPatcher.patchSet0(words, placeholder -> placeholder == 3 ? 1 : -1);
		var r = SpirvReflection.reflect(module);
		assertEquals(List.of(
				new SpirvReflection.Descriptor(0, 1, "sampled_image", null),
				new SpirvReflection.Descriptor(1, 2, "sampled_image", null)), withoutNames(r.descriptors()));
	}

	@Test
	void patcherRejectsGarbage() {
		assertThrows(IllegalArgumentException.class, () -> SpirvPatcher.set0Bindings(SpirvPatcher.words(new byte[20])));
	}
}
