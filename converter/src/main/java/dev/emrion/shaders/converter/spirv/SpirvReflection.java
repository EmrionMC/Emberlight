package dev.emrion.shaders.converter.spirv;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.lwjgl.util.spvc.Spvc.*;

/**
 * spvc reflection of one SPIR-V module. Names come from OpName and are empty when the optimiser stripped them.
 *
 * @param localSize compute only, else null
 */
public record SpirvReflection(List<Descriptor> descriptors, int pushConstantSize,
                              List<Variable> inputs, List<Variable> outputs, int[] localSize) {

	/** type: uniform_buffer | storage_buffer | storage_image | sampled_image | separate_image | separate_sampler. */
	public record Descriptor(int set, int binding, String type, String name) {}

	public record Variable(String name, int location) {}

	private static final int DECORATION_LOCATION = 30, DECORATION_BINDING = 33, DECORATION_DESCRIPTOR_SET = 34;
	private static final int EXECUTION_MODE_LOCAL_SIZE = 17, EXECUTION_MODEL_GL_COMPUTE = 5;

	public static SpirvReflection reflect(byte[] spv) {
		IntBuffer code = MemoryUtil.memAllocInt(spv.length / 4);
		code.put(SpirvPatcher.words(spv)).flip();
		long context = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer ptr = stack.callocPointer(1);
			check(spvc_context_create(ptr), 0, "context");
			context = ptr.get(0);
			check(spvc_context_parse_spirv(context, code, code.remaining(), ptr), context, "parse");
			long ir = ptr.get(0);
			check(spvc_context_create_compiler(context, SPVC_BACKEND_NONE, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ptr), context, "compiler");
			long compiler = ptr.get(0);
			check(spvc_compiler_create_shader_resources(compiler, ptr), context, "resources");
			long resources = ptr.get(0);

			List<Descriptor> descriptors = new ArrayList<>();
			addDescriptors(descriptors, compiler, resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, "uniform_buffer", context);
			addDescriptors(descriptors, compiler, resources, SPVC_RESOURCE_TYPE_STORAGE_BUFFER, "storage_buffer", context);
			addDescriptors(descriptors, compiler, resources, SPVC_RESOURCE_TYPE_STORAGE_IMAGE, "storage_image", context);
			addDescriptors(descriptors, compiler, resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, "sampled_image", context);
			addDescriptors(descriptors, compiler, resources, SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, "separate_image", context);
			addDescriptors(descriptors, compiler, resources, SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS, "separate_sampler", context);
			addDescriptors(descriptors, compiler, resources, SPVC_RESOURCE_TYPE_ACCELERATION_STRUCTURE, "acceleration_structure", context);
			descriptors.sort(Comparator.comparingInt(Descriptor::set).thenComparingInt(Descriptor::binding));

			int pushConstantSize = 0;
			for (SpvcReflectedResource r : list(resources, SPVC_RESOURCE_TYPE_PUSH_CONSTANT, context)) {
				PointerBuffer size = stack.callocPointer(1);
				check(spvc_compiler_get_declared_struct_size(compiler, spvc_compiler_get_type_handle(compiler, r.base_type_id()), size), context, "struct size");
				pushConstantSize = Math.max(pushConstantSize, (int) size.get(0));
			}

			int[] localSize = null;
			if (spvc_compiler_get_execution_model(compiler) == EXECUTION_MODEL_GL_COMPUTE) {
				localSize = new int[3];
				for (int i = 0; i < 3; i++) localSize[i] = spvc_compiler_get_execution_mode_argument_by_index(compiler, EXECUTION_MODE_LOCAL_SIZE, i);
			}
			return new SpirvReflection(List.copyOf(descriptors), pushConstantSize,
					variables(compiler, resources, SPVC_RESOURCE_TYPE_STAGE_INPUT, context),
					variables(compiler, resources, SPVC_RESOURCE_TYPE_STAGE_OUTPUT, context), localSize);
		} finally {
			if (context != 0) spvc_context_destroy(context);
			MemoryUtil.memFree(code);
		}
	}

	private static void addDescriptors(List<Descriptor> out, long compiler, long resources, int type, String typeName, long context) {
		for (SpvcReflectedResource r : list(resources, type, context))
			out.add(new Descriptor(spvc_compiler_get_decoration(compiler, r.id(), DECORATION_DESCRIPTOR_SET),
					spvc_compiler_get_decoration(compiler, r.id(), DECORATION_BINDING), typeName, r.nameString()));
	}

	private static List<Variable> variables(long compiler, long resources, int type, long context) {
		List<Variable> out = new ArrayList<>();
		for (SpvcReflectedResource r : list(resources, type, context))
			out.add(new Variable(r.nameString(), spvc_compiler_get_decoration(compiler, r.id(), DECORATION_LOCATION)));
		out.sort(Comparator.comparingInt(Variable::location));
		return List.copyOf(out);
	}

	private static SpvcReflectedResource.Buffer list(long resources, int type, long context) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer list = stack.callocPointer(1), count = stack.callocPointer(1);
			check(spvc_resources_get_resource_list_for_type(resources, type, list, count), context, "resource list");
			return SpvcReflectedResource.create(list.get(0), (int) count.get(0));
		}
	}

	private static void check(int status, long context, String what) {
		if (status != SPVC_SUCCESS)
			throw new IllegalArgumentException("spvc " + what + " failed: " + (context == 0 ? status : spvc_context_get_last_error_string(context)));
	}
}
