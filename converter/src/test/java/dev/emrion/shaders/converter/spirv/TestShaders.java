package dev.emrion.shaders.converter.spirv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Small explicit-layout GLSL 450 stages, shaped like the transpiler's output. */
public final class TestShaders {
	private TestShaders() {}

	public static final String VERTEX = """
			#version 450
			layout(set = 0, binding = 0) uniform DynamicTransforms { mat4 ModelViewMat; };
			layout(set = 1, binding = 0) uniform EmrionUniforms { mat4 gbufferProjection; float frameTimeCounter; };
			layout(push_constant) uniform EmrionDraw { vec4 entityColor; int entityId; };
			layout(location = 0) in vec3 Position;
			layout(location = 2) in vec2 UV0;
			layout(location = 0) out vec2 texcoord;
			layout(location = 1) out vec4 tint;
			void main() {
			    texcoord = UV0;
			    tint = entityColor * frameTimeCounter;
			    gl_Position = gbufferProjection * ModelViewMat * vec4(Position, 1.0);
			}
			""";

	public static final String FRAGMENT = """
			#version 450
			layout(set = 0, binding = 3) uniform sampler2D Sampler0;
			layout(set = 1, binding = 2) uniform sampler2D colortex0;
			layout(location = 0) in vec2 texcoord;
			layout(location = 1) in vec4 tint;
			layout(location = 0) out vec4 outColor0;
			void main() {
			    outColor0 = texture(Sampler0, texcoord) * texture(colortex0, texcoord) * tint;
			}
			""";

	public static final String GEOMETRY = """
			#version 450
			layout(triangles) in;
			layout(triangle_strip, max_vertices = 3) out;
			layout(location = 0) in vec2 texcoordIn[];
			layout(location = 0) out vec2 texcoord;
			void main() {
			    for (int i = 0; i < 3; i++) { texcoord = texcoordIn[i]; gl_Position = gl_in[i].gl_Position; EmitVertex(); }
			    EndPrimitive();
			}
			""";

	public static final String COMPUTE = """
			#version 450
			layout(local_size_x = 8, local_size_y = 4, local_size_z = 2) in;
			layout(set = 1, binding = 30, r16ui) uniform uimage3D voxel_img;
			layout(set = 1, binding = 40) buffer SSBO0 { uint data[]; };
			void main() {
			    ivec3 p = ivec3(gl_GlobalInvocationID);
			    imageStore(voxel_img, p, uvec4(data[p.x]));
			}
			""";

	/** Fails spirv-val's verdict loudly when the tool is installed; silently passes otherwise. */
	public static void validateIfAvailable(Path spv) throws IOException, InterruptedException {
		ProcessBuilder pb;
		try {
			pb = new ProcessBuilder("spirv-val", "--target-env", "vulkan1.2", spv.toString()).redirectErrorStream(true);
			Process p = pb.start();
			String out = new String(p.getInputStream().readAllBytes());
			p.waitFor(30, TimeUnit.SECONDS);
			assertEquals(0, p.exitValue(), "spirv-val " + spv + ": " + out);
		} catch (IOException notInstalled) {
			if (!notInstalled.getMessage().contains("spirv-val")) throw notInstalled;
		}
	}

	public static void validateIfAvailable(byte[] spv) throws IOException, InterruptedException {
		Path f = Files.createTempFile("emrion", ".spv");
		try {
			Files.write(f, spv);
			validateIfAvailable(f);
		} finally {
			Files.delete(f);
		}
	}
}
