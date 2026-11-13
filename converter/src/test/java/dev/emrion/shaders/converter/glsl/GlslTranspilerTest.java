package dev.emrion.shaders.converter.glsl;

import dev.emrion.shaders.format.Manifest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One small rule per test: input snippet → expected output text or property. Every output is also validated when glslangValidator exists. */
class GlslTranspilerTest {
	static final GlslTranspiler TR = new GlslTranspiler();
	static boolean validator;
	static Path tmp;

	@BeforeAll
	static void setup() throws Exception {
		validator = TranspilerTestSupport.validatorAvailable();
		tmp = Files.createTempDirectory("emr-golden");
	}

	static final String VS = """
			#version 130
			varying vec2 texCoord;
			void main() {
				texCoord = gl_MultiTexCoord0.xy;
				gl_Position = ftransform();
			}
			""";
	static final String FS = """
			#version 130
			uniform sampler2D tex;
			varying vec2 texCoord;
			void main() {
				gl_FragData[0] = texture2D(tex, texCoord);
			}
			""";

	record Out(Transpiler.ProgramAnalysis analysis, Map<StageType, String> glsl, List<String> diagnostics) {
		String vs() { return glsl.get(StageType.VERTEX); }

		String fs() { return glsl.get(StageType.FRAGMENT); }
	}

	static Out run(String program, ProgramGroup group, String variant, Manifest.AlphaTest alpha, Map<Integer, Integer> outputs,
	               String vs, String fs) throws Exception {
		List<PreprocessedStage> stages = new ArrayList<>();
		if (vs != null) stages.add(TranspilerTestSupport.stage(program, group, StageType.VERTEX, vs));
		if (fs != null) stages.add(TranspilerTestSupport.stage(program, group, fs.contains("local_size") ? StageType.COMPUTE : StageType.FRAGMENT, fs));
		Transpiler.ProgramAnalysis a = TR.analyze(stages);
		Transpiler.EmitContext c = TranspilerTestSupport.context(a, alpha);
		if (outputs != null) c = new Transpiler.EmitContext(c.uniformMembers(), c.resourceBinding(), outputs, alpha);
		Transpiler.EmitResult r = TR.emit(a, c, variant);
		if (validator) {
			for (var e : r.glsl().entrySet()) {
				String err = TranspilerTestSupport.validate(tmp, program + "_" + variant, e.getKey(), e.getValue());
				assertNull(err, () -> "validation failed:\n" + err + "\n" + e.getValue());
			}
		}
		return new Out(a, r.glsl(), r.diagnostics().stream().map(Object::toString).toList());
	}

	static Out gbuffers(String variant, String vs, String fs) throws Exception {
		return run("gbuffers_terrain", ProgramGroup.GBUFFERS, variant, null, null, vs, fs);
	}

	static Out composite(String vs, String fs) throws Exception {
		return run("composite", ProgramGroup.COMPOSITE, "fullscreen", null, null, vs, fs);
	}

	// ---- step 1: version, extensions, keywords ----

	@Test
	void versionBecomes450AndCoreExtensionsAreDropped() throws Exception {
		Out o = composite(VS, """
				#version 130
				#extension GL_ARB_gpu_shader5 : enable
				#extension GL_ARB_shader_draw_parameters : enable
				varying vec2 texCoord;
				void main() { gl_FragData[0] = vec4(texCoord, 0.0, 1.0); }
				""");
		assertTrue(o.fs().startsWith("#version 450\n"));
		assertFalse(o.fs().contains("GL_ARB_gpu_shader5"));
		assertTrue(o.fs().contains("#extension GL_ARB_shader_draw_parameters : enable"));
	}

	@Test
	void identifiersThatAreKeywordsIn450AreRenamed() throws Exception {
		Out o = composite(VS, """
				#version 130
				varying vec2 texCoord;
				float filter(float sample) { return sample * 2.0; }
				void main() { float texture = filter(texCoord.x); gl_FragData[0] = vec4(texture); }
				""");
		assertTrue(o.fs().contains("float emr_kw_filter(float emr_kw_sample)"));
		assertTrue(o.fs().contains("float emr_kw_texture = emr_kw_filter(texCoord.x)"));
	}

	@Test
	void sharedStaysAQualifierInCompute() throws Exception {
		Out o = run("composite", ProgramGroup.COMPOSITE, null, null, null, null, """
				#version 430
				layout(local_size_x = 64) in;
				shared vec3 cache[64][2];
				void main() { cache[gl_LocalInvocationIndex][0] = vec3(1.0); }
				""");
		assertTrue(o.glsl().get(StageType.COMPUTE).contains("\nshared vec3 cache[64][2];"));
	}

	@Test
	void ownFunctionNamedLikeALaterBuiltinIsRenamed() throws Exception {
		Out o = composite(VS, """
				#version 120
				varying vec2 texCoord;
				float tanh(float x) { return (exp(x) - exp(-x)) / (exp(x) + exp(-x)); }
				void main() { gl_FragData[0] = vec4(tanh(texCoord.x)); }
				""");
		assertTrue(o.fs().contains("float emr_kw_tanh(float x)"));
		assertTrue(o.fs().contains("vec4(emr_kw_tanh(texCoord.x))"));
	}

	// ---- step 2: texture functions ----

	@Test
	void legacyTextureFunctionsAreRenamed() throws Exception {
		Out o = composite(VS, """
				#version 130
				uniform sampler2D colortex0;
				uniform sampler3D noise3d;
				varying vec2 texCoord;
				void main() { gl_FragData[0] = texture2D(colortex0, texCoord) + texture2DLod(colortex0, texCoord, 2.0) + texture3D(noise3d, vec3(texCoord, 0.0)); }
				""");
		assertTrue(o.fs().contains("texture(colortex0, texCoord) + textureLod(colortex0, texCoord, 2.0) + texture(noise3d"));
	}

	@Test
	void texture2DOnUsamplerBecomesTexture() throws Exception {
		Out o = composite(VS, """
				#version 130
				uniform usampler2D puddle_sampler;
				varying vec2 texCoord;
				void main() { uint v = texture2D(puddle_sampler, texCoord).r; gl_FragData[0] = vec4(float(v)); }
				""");
		assertTrue(o.fs().contains("uint v = texture(puddle_sampler, texCoord).r"));
	}

	@Test
	void shadow2DIsWrappedInVec4() throws Exception {
		Out o = composite(VS, """
				#version 130
				uniform sampler2DShadow shadowtex0;
				varying vec2 texCoord;
				void main() { gl_FragData[0] = shadow2D(shadowtex0, vec3(texCoord, 0.5)); }
				""");
		assertTrue(o.fs().contains("vec4(texture(shadowtex0, vec3(texCoord, 0.5)))"));
		assertEquals("sampler2DShadow", o.analysis().samplers().getFirst().glslType());
	}

	// ---- step 3: stage I/O ----

	@Test
	void varyingsGetTheSameLocationByNameInEveryStage() throws Exception {
		Out o = composite("""
				#version 130
				varying vec2 a;
				varying mat4 m;
				varying vec3 b;
				void main() { a = vec2(0.0); m = mat4(1.0); b = vec3(1.0); gl_Position = ftransform(); }
				""", """
				#version 130
				varying vec3 b;
				varying vec2 a;
				varying mat4 m;
				void main() { gl_FragData[0] = vec4(a, b.x, m[0].x); }
				""");
		assertTrue(o.vs().contains("layout(location = 0) out vec2 a;"));
		assertTrue(o.vs().contains("layout(location = 1) out mat4 m;"));
		assertTrue(o.vs().contains("layout(location = 5) out vec3 b;"), "mat4 consumes four locations");
		assertTrue(o.fs().contains("layout(location = 5) in vec3 b;"));
		assertTrue(o.fs().contains("layout(location = 0) in vec2 a;"));
	}

	@Test
	void integerVaryingsAreFlat() throws Exception {
		Out o = composite("""
				#version 130
				out int mat;
				void main() { mat = 3; gl_Position = ftransform(); }
				""", """
				#version 130
				in int mat;
				void main() { gl_FragData[0] = vec4(float(mat)); }
				""");
		assertTrue(o.vs().contains("flat out int mat;"));
		assertTrue(o.fs().contains("flat in int mat;"));
	}

	@Test
	void fragmentInputWithoutProducerIsZero() throws Exception {
		Out o = composite(VS, """
				#version 130
				varying vec2 texCoord;
				varying vec3 orphan;
				void main() { gl_FragData[0] = vec4(orphan, texCoord.x); }
				""");
		assertTrue(o.fs().contains("vec3 orphan = vec3(0);"));
	}

	@Test
	void varyingTypeMismatchIsPatchedLikeIris() throws Exception {
		Out o = composite("""
				#version 130
				varying vec2 texCoord;
				varying vec4 col;
				void main() { texCoord = vec2(0.0); col = vec4(1.0); gl_Position = ftransform(); }
				""", """
				#version 130
				varying vec2 texCoord;
				varying vec3 col;
				void main() { gl_FragData[0] = vec4(col, texCoord.x); }
				""");
		assertTrue(o.vs().contains("out vec3 col;"));
		assertTrue(o.vs().contains("vec4 emr_mm_col;"));
		assertTrue(o.vs().contains("emr_mm_col = vec4(1.0);"));
		assertTrue(o.vs().contains("col = vec3((emr_mm_col).xyz);"));
	}

	@Test
	void unassignedProducerOutputIsZeroInitialised() throws Exception {
		Out o = composite("""
				#version 130
				varying vec2 texCoord;
				varying float never;
				void main() { texCoord = vec2(0.0); gl_Position = ftransform(); }
				""", """
				#version 130
				varying vec2 texCoord;
				varying float never;
				void main() { gl_FragData[0] = vec4(never, texCoord, 1.0); }
				""");
		assertTrue(o.vs().contains("never = float(0);"));
	}

	// ---- step 4: fragment outputs and alpha test ----

	@Test
	void fragDataUsesDrawBufferPositionAsLocationAndUnlistedWritesAreDead() throws Exception {
		Out o = run("composite", ProgramGroup.COMPOSITE, "fullscreen", null, Map.of(0, 0, 2, 1), VS, """
				#version 130
				varying vec2 texCoord;
				void main() { gl_FragData[0] = vec4(1.0); gl_FragData[1] = vec4(2.0); gl_FragData[2] = vec4(texCoord, 0.0, 1.0); }
				""");
		assertTrue(o.fs().contains("layout(location = 0) out vec4 emr_FragData0;"));
		assertTrue(o.fs().contains("\nvec4 emr_FragData1;"));
		assertTrue(o.fs().contains("layout(location = 1) out vec4 emr_FragData2;"));
		assertEquals(List.of(0, 1, 2), o.analysis().fragmentOutputs());
	}

	@Test
	void fragColorIsOutputZero() throws Exception {
		Out o = composite(VS, """
				#version 130
				varying vec2 texCoord;
				void main() { gl_FragColor = vec4(texCoord, 0.0, 1.0); }
				""");
		assertTrue(o.fs().contains("emr_FragData0 = vec4(texCoord"));
		assertEquals(List.of(0), o.analysis().fragmentOutputs());
	}

	@Test
	void modernOutputsKeepTheirNames() throws Exception {
		Out o = run("composite", ProgramGroup.COMPOSITE, "fullscreen", null, Map.of(0, 3, 1, 4), VS, """
				#version 330
				in vec2 texCoord;
				out vec4 outA;
				out vec4 outB;
				void main() { outA = vec4(1.0); outB = vec4(texCoord, 0.0, 1.0); }
				""");
		assertTrue(o.fs().contains("layout(location = 3) out vec4 outA;"));
		assertTrue(o.fs().contains("layout(location = 4) out vec4 outB;"));
	}

	@Test
	void alphaTestIsAppendedToGbuffersWithPushConstantReference() throws Exception {
		Out o = run("gbuffers_textured", ProgramGroup.GBUFFERS, "particle", new Manifest.AlphaTest("greater", 0.1f), null, VS, FS);
		assertTrue(o.fs().contains("if (!(emr_FragData0.a > emr_draw.emr_alphaTestRef)) discard;"));
		assertTrue(o.fs().contains("layout(offset = 28) float emr_alphaTestRef;"));
	}

	@Test
	void noAlphaTestInCompositeOrForAlways() throws Exception {
		assertFalse(run("composite", ProgramGroup.COMPOSITE, "fullscreen", new Manifest.AlphaTest("greater", 0.1f), null, VS, FS.replace("tex,", "colortex0,").replace("sampler2D tex;", "sampler2D colortex0;")).fs().contains("discard"));
		assertFalse(run("gbuffers_textured", ProgramGroup.GBUFFERS, "particle", new Manifest.AlphaTest("always", 0f), null, VS, FS).fs().contains("discard"));
	}

	// ---- step 5: vertex inputs ----

	@Test
	void terrainMdiReconstructsChunkOffsetFromInstancedInput() throws Exception {
		Out o = gbuffers("terrain_mdi", VS, FS);
		assertTrue(o.vs().contains("vec4((emr_in_Position + (vec3(emr_in_ChunkPosition - emr_CameraBlockPos) + emr_CameraOffset)), 1.0)"));
		assertTrue(o.vs().contains("layout(location = 10) in ivec3 emr_in_ChunkPosition;"));
		assertTrue(o.vs().contains("emr_terrain.ModelViewMat"));
		assertTrue(o.vs().contains("layout(offset = "), "camera members live in EmrionUniforms");
	}

	@Test
	void terrainUboReadsChunkPositionFromChunkSection() throws Exception {
		Out o = gbuffers("terrain_ubo", VS, FS);
		assertTrue(o.vs().contains("emr_chunk.ChunkPosition - emr_CameraBlockPos"));
		assertTrue(o.vs().contains("layout(std140, set = 0, binding = 2) uniform ChunkSection"));
	}

	@Test
	void entityModelViewIncludesModelOffset() throws Exception {
		Out o = run("gbuffers_entities", ProgramGroup.GBUFFERS, "entity", null, null, VS, FS);
		assertTrue(o.vs().contains("(emr_dt.ModelViewMat * emr_translate(emr_dt.ModelOffset))"));
		assertTrue(o.vs().contains("vec4(emr_in_Position, 1.0)"));
	}

	@Test
	void canonicalInputsHaveFixedLocations() throws Exception {
		Out o = gbuffers("terrain_mdi", """
				#version 130
				attribute vec4 mc_Entity;
				attribute vec4 at_tangent;
				varying vec4 v;
				void main() { v = mc_Entity + at_tangent + vec4(gl_Normal, 0.0) + gl_Color; gl_Position = ftransform(); }
				""", "#version 130\nvarying vec4 v;\nvoid main() { gl_FragData[0] = v; }\n");
		assertTrue(o.vs().contains("layout(location = 6) in ivec2 emr_in_mc_Entity;"));
		assertTrue(o.vs().contains("(vec4(emr_in_mc_Entity, 0.0, 1.0))"), "mc_Entity converted to the declared vec4");
		assertTrue(o.vs().contains("layout(location = 8) in vec4 emr_in_at_tangent;"));
		assertTrue(o.vs().contains("layout(location = 5) in vec3 emr_in_Normal;"));
		assertTrue(o.analysis().vertexInputs().containsAll(List.of("Position", "Color", "Normal", "mc_Entity", "at_tangent")));
	}

	@Test
	void missingAttributesUseIrisDefaults() throws Exception {
		Out o = run("gbuffers_basic", ProgramGroup.GBUFFERS, "position", null, null, """
				#version 130
				attribute vec4 at_tangent;
				varying vec4 v;
				void main() { v = gl_Color + gl_MultiTexCoord0 + gl_MultiTexCoord1 + at_tangent + vec4(gl_Normal, 0.0); gl_Position = ftransform(); }
				""", "#version 130\nvarying vec4 v;\nvoid main() { gl_FragData[0] = v; }\n");
		assertTrue(o.vs().contains("emr_dt.ColorModulator + vec4(0.5, 0.5, 0.0, 1.0) + vec4(240.0, 240.0, 0.0, 1.0) + (vec4(0.0, 0.0, 0.0, 1.0)) + vec4(vec3(0.0, 0.0, 1.0), 0.0)"));
		assertFalse(o.vs().contains("emr_in_Color"));
	}

	@Test
	void chunkFadeComesFromChunkVisibilityInGbuffersTerrain() throws Exception {
		String vs = "#version 130\nvarying float f;\nvoid main() { f = mc_chunkFade; gl_Position = ftransform(); }\n";
		String fs = "#version 130\nvarying float f;\nvoid main() { gl_FragData[0] = vec4(f); }\n";
		assertTrue(gbuffers("terrain_mdi", vs, fs).vs().contains("f = emr_in_ChunkVisibility;"));
		assertTrue(run("shadow", ProgramGroup.SHADOW, "terrain_mdi", null, null, vs, fs).vs().contains("f = -1.0;"));
	}

	// ---- step 6: matrices and compat built-ins ----

	@Test
	void textureMatricesFollowVanillaTransformer() throws Exception {
		Out o = run("gbuffers_entities", ProgramGroup.GBUFFERS, "entity", null, null, """
				#version 130
				varying vec2 lm;
				varying vec2 tc;
				void main() { tc = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy; lm = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy; gl_Position = ftransform(); }
				""", "#version 130\nvarying vec2 lm;\nvarying vec2 tc;\nvoid main() { gl_FragData[0] = vec4(lm, tc); }\n");
		assertTrue(o.vs().contains("tc = (emr_dt.TextureMat * vec4(emr_in_UV0, 0.0, 1.0)).xy"));
		assertTrue(o.vs().contains("lm = (mat4(vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0), vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0)) * vec4(emr_in_UV2, 0.0, 1.0)).xy"));
	}

	@Test
	void projectionAndNormalMatrix() throws Exception {
		Out o = gbuffers("terrain_mdi", """
				#version 130
				varying vec3 n;
				void main() { n = gl_NormalMatrix * gl_Normal; gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex; }
				""", "#version 130\nvarying vec3 n;\nvoid main() { gl_FragData[0] = vec4(n, 1.0); }\n");
		assertTrue(o.vs().contains("mat3(transpose(inverse(mat3(emr_terrain.ModelViewMat)))) * emr_in_Normal"));
		assertTrue(o.vs().contains("gl_Position = emr_phase.emr_ProjMat * emr_terrain.ModelViewMat *"));
		assertTrue(o.vs().contains("uniform EmrionPhase { layout(offset = 0) mat4 emr_ProjMat; layout(offset = 64) mat4 emr_ProjMatInverse; layout(offset = 128) int renderStage; } emr_phase;"));
	}

	@Test
	void depthFixIsAppendedAfterMain() throws Exception {
		Out o = composite("""
				#version 130
				varying vec2 texCoord;
				void main() { texCoord = gl_MultiTexCoord0.xy; gl_Position = ftransform(); if (texCoord.x > 2.0) return; }
				""", "#version 130\nvarying vec2 texCoord;\nvoid main() { gl_FragData[0] = vec4(texCoord, 0.0, 1.0); }\n");
		assertTrue(o.vs().contains("emr_main();\n\tgl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}"));
	}

	@Test
	void linesProvideUndeclaredCoreProfileNames() throws Exception {
		Out o = run("gbuffers_line", ProgramGroup.GBUFFERS, "lines", new Manifest.AlphaTest("greater", 0.1f), null, """
				#version 130
				flat out vec4 glColor;
				void main() {
					vec4 a = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
					vec4 b = projectionMatrix * modelViewMatrix * vec4(vaPosition + vaNormal, 1.0);
					gl_Position = gl_VertexID % 2 == 0 ? a : b;
					glColor = gl_Color;
				}
				""", "#version 130\nflat in vec4 glColor;\nvoid main() { gl_FragData[0] = glColor; }\n");
		assertTrue(o.vs().contains("emr_phase.emr_ProjMat * emr_dt.ModelViewMat * vec4((emr_in_Position), 1.0)"));
		assertTrue(o.vs().contains("(emr_in_Position) + (emr_in_Normal)"));
		assertTrue(o.vs().contains("gl_VertexIndex % 2"));
		assertTrue(o.vs().contains("layout(location = 5) in vec3 emr_in_Normal;"));
		assertFalse(o.fs().contains("discard"), "Iris core path (lines) has no alpha test");
	}

	@Test
	void fullscreenVariantHasNoVertexInputs() throws Exception {
		Out o = composite(VS, FS.replace("tex,", "colortex0,").replace("sampler2D tex;", "sampler2D colortex0;"));
		assertFalse(o.vs().contains(" in "), o.vs());
		assertTrue(o.vs().contains("vec2 emr_fsUV() { return vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2)); }"));
		assertTrue(o.vs().contains("texCoord = vec4(emr_fsUV(), 0.0, 1.0).xy;"));
		assertTrue(o.vs().contains("mat4(vec4(2.0, 0.0, 0.0, 0.0), vec4(0.0, 2.0, 0.0, 0.0), vec4(0.0), vec4(-1.0, -1.0, 0.0, 1.0)) * vec4(emr_fsUV(), 0.0, 1.0)"));
	}

	@Test
	void fogBecomesStructFromEmrionUniforms() throws Exception {
		Out o = composite(VS, """
				#version 130
				varying vec2 texCoord;
				void main() { gl_FragData[0] = vec4((texCoord.x - gl_Fog.start) * gl_Fog.scale); }
				""");
		assertTrue(o.fs().contains("struct emr_FogParameters"));
		assertTrue(o.fs().contains("emr_FogParameters(emr_FogColor, emr_FogDensity, emr_FogStart, emr_FogEnd, 1.0 / (emr_FogEnd - emr_FogStart)).start"));
		assertTrue(o.analysis().uniforms().stream().anyMatch(u -> u.name().equals("emr_FogStart")));
	}

	// ---- step 7: uniforms and resources ----

	@Test
	void uniformsBecomeBlockMembersWithExplicitOffsets() throws Exception {
		Out o = composite(VS, """
				#version 130
				uniform float viewWidth;
				uniform mat4 gbufferModelView;
				uniform vec3 cameraPosition;
				varying vec2 texCoord;
				void main() { gl_FragData[0] = gbufferModelView * vec4(cameraPosition, viewWidth + texCoord.x); }
				""");
		assertTrue(o.fs().contains("layout(std140, set = 1, binding = 0) uniform EmrionUniforms {\n\tlayout(offset = 0) float viewWidth;\n\tlayout(offset = 16) mat4 gbufferModelView;\n\tlayout(offset = 80) vec3 cameraPosition;\n};"));
	}

	@Test
	void unprovidedUniformsBecomeConstants() throws Exception {
		List<PreprocessedStage> st = List.of(TranspilerTestSupport.stage("composite", ProgramGroup.COMPOSITE, StageType.FRAGMENT, """
				#version 130
				uniform float cloudHeight = 192.0;
				uniform int unknownThing;
				uniform float unused;
				void main() { gl_FragData[0] = vec4(cloudHeight, float(unknownThing), 0.0, 1.0); }
				"""));
		Transpiler.ProgramAnalysis a = TR.analyze(st);
		assertEquals(List.of("cloudHeight", "unknownThing"), a.uniforms().stream().map(Transpiler.UniformDecl::name).toList());
		assertEquals("192.0", a.uniforms().getFirst().initializer());
		String fs = TR.emit(a, new Transpiler.EmitContext(Map.of(), n -> 2, Map.of(0, 0), null), "fullscreen").glsl().get(StageType.FRAGMENT);
		assertTrue(fs.contains("const float cloudHeight = 192.0;"));
		assertTrue(fs.contains("const int unknownThing = int(0);"));
		assertFalse(fs.contains("unused"));
	}

	@Test
	void perDrawUniformsComeFromPushConstantsAndRenderStageFromPhase() throws Exception {
		String fs = """
				#version 130
				uniform int entityId;
				uniform int blockEntityId;
				uniform int renderStage;
				varying vec2 texCoord;
				void main() { gl_FragData[0] = vec4(float(entityId + blockEntityId + renderStage), texCoord, 1.0); }
				""";
		Out o = run("gbuffers_entities", ProgramGroup.GBUFFERS, "entity", null, null, VS, fs);
		// ids travel VS → FS in a flat varying; the entity variant fills it from iris_Entity, others from the push constants
		assertTrue(o.fs().contains("float(emr_entityInfo.x + emr_entityInfo.y + emr_phase.renderStage)"));
		assertTrue(o.fs().contains("flat in ivec3 emr_entityInfo;"));
		assertFalse(o.fs().contains("uniform int entityId"));
		assertTrue(o.vs().contains("layout(location = 13) in uvec3 emr_in_iris_Entity;"));
		assertTrue(o.vs().contains("emr_entityInfo = ivec3(emr_in_iris_Entity);"));
		assertFalse(o.vs().contains("emr_draw"));
		assertTrue(o.analysis().vertexInputs().contains("iris_Entity"));
		Out p = run("gbuffers_entities", ProgramGroup.GBUFFERS, "position_color", null, null, VS, fs);
		assertTrue(p.vs().contains("emr_entityInfo = ivec3(emr_draw.entityId, emr_draw.blockEntityId, emr_draw.currentRenderedItemId);"));
		assertTrue(p.vs().contains("layout(push_constant) uniform EmrionDraw { layout(offset = 0) vec4 entityColor; layout(offset = 16) int entityId;"));
		assertEquals(o.fs(), p.fs(), "fragment stage must not depend on the vertex variant");
	}

	@Test
	void packLocalNamedLikeAnEntityIdShadowsTheId() throws Exception {
		Out o = gbuffers("terrain", VS, """
				#version 130
				uniform int blockEntityId;
				varying vec2 texCoord;
				int outer() { return blockEntityId; }
				void main() { int blockEntityId = int(texCoord.x); gl_FragData[0] = vec4(float(blockEntityId + outer())); }
				""");
		assertTrue(o.fs().contains("int blockEntityId = int(texCoord.x);"));
		assertTrue(o.fs().contains("\nint blockEntityId;"));
		assertTrue(o.fs().contains("blockEntityId = emr_entityInfo.y;"));
		assertFalse(o.fs().contains("uniform int blockEntityId"));
	}

	@Test
	void programsWithAGeometryStageKeepIdsInPushConstants() throws Exception {
		List<PreprocessedStage> st = List.of(
				TranspilerTestSupport.stage("gbuffers_entities", ProgramGroup.GBUFFERS, StageType.VERTEX, VS),
				TranspilerTestSupport.stage("gbuffers_entities", ProgramGroup.GBUFFERS, StageType.GEOMETRY, """
						#version 150
						layout(triangles) in;
						layout(triangle_strip, max_vertices = 3) out;
						in vec2 texCoord[];
						out vec2 tc;
						void main() { for (int i = 0; i < 3; i++) { tc = texCoord[i]; gl_Position = gl_in[i].gl_Position; EmitVertex(); } }
						"""),
				TranspilerTestSupport.stage("gbuffers_entities", ProgramGroup.GBUFFERS, StageType.FRAGMENT, """
						#version 150
						uniform int entityId;
						in vec2 tc;
						void main() { gl_FragData[0] = vec4(float(entityId), tc, 1.0); }
						"""));
		Transpiler.ProgramAnalysis a = TR.analyze(st);
		String fs = TR.emit(a, new Transpiler.EmitContext(Map.of(), n -> 2, Map.of(0, 0), null), "entity").glsl().get(StageType.FRAGMENT);
		assertTrue(fs.contains("float(emr_draw.entityId)"), fs);
		assertFalse(a.vertexInputs().contains("iris_Entity"));
	}

	@Test
	void entityColorComesFromTheOverlayInTheEntityVariant() throws Exception {
		String fs = "#version 130\nuniform vec4 entityColor;\nvarying vec2 texCoord;\nvoid main() { gl_FragData[0] = entityColor + vec4(texCoord, 0.0, 0.0); }\n";
		Out o = run("gbuffers_entities", ProgramGroup.GBUFFERS, "entity", null, null, VS, fs);
		assertTrue(o.vs().contains("texelFetch(emr_Sampler1, emr_in_UV1, 0)"));
		assertTrue(o.vs().contains("layout(set = 0, binding = 4) uniform sampler2D emr_Sampler1;"));
		assertTrue(o.fs().contains("in vec4 emr_entityColor;"));
		Out p = run("gbuffers_entities", ProgramGroup.GBUFFERS, "position_color", null, null, VS, fs);
		assertTrue(p.vs().contains("emr_entityColor = emr_draw.entityColor;"));
		assertEquals(o.fs(), p.fs(), "fragment stage must not depend on the vertex variant");
	}

	@Test
	void albedoAndLightmapSamplersGoToSet0InGbuffers() throws Exception {
		Out o = gbuffers("terrain_mdi", VS, """
				#version 130
				uniform sampler2D texture;
				uniform sampler2D lightmap;
				uniform sampler2D noisetex;
				varying vec2 texCoord;
				void main() { gl_FragData[0] = texture2D(texture, texCoord) * texture2D(lightmap, texCoord) * texture2D(noisetex, texCoord); }
				""");
		assertTrue(o.fs().contains("layout(set = 0, binding = 3) uniform sampler2D emr_kw_texture;"));
		assertTrue(o.fs().contains("layout(set = 0, binding = 5) uniform sampler2D lightmap;"));
		assertTrue(o.fs().contains("texture(emr_kw_texture, texCoord)"));
		assertTrue(o.fs().matches("(?s).*layout\\(set = 1, binding = \\d+\\) uniform sampler2D noisetex;.*"));
		assertEquals(List.of("noisetex"), o.analysis().samplers().stream().map(Transpiler.SamplerDecl::name).toList());
		assertTrue(o.analysis().set0().containsAll(List.of("Sampler0", "Sampler2")));
	}

	@Test
	void pbrSamplersFollowSampler0InEveryGbuffersProgram() throws Exception {
		String fs = """
				#version 130
				uniform sampler2D normals;
				uniform sampler2D specular;
				varying vec2 texCoord;
				void main() { gl_FragData[0] = texture2D(normals, texCoord) + texture2D(specular, texCoord); }
				""";
		for (String variant : List.of("terrain_mdi", "entity")) {
			Out o = gbuffers(variant, VS, fs);
			assertTrue(o.fs().contains("layout(set = 0, binding = 8) uniform sampler2D normals;"), o.fs());
			assertTrue(o.fs().contains("layout(set = 0, binding = 9) uniform sampler2D specular;"));
			assertTrue(o.analysis().samplers().isEmpty());
			assertTrue(o.analysis().set0().containsAll(List.of("EmrionNormals", "EmrionSpecular")));
		}
	}

	@Test
	void ssboKeepsStd430AndUsesBufferObjectBinding() throws Exception {
		Out o = composite(VS, """
				#version 430 compatibility
				layout(std430, binding = 3) readonly buffer blockData { uvec4 data[]; } ssbo;
				varying vec2 texCoord;
				void main() { gl_FragData[0] = vec4(ssbo.data[0]) + vec4(texCoord, 0.0, 0.0); }
				""");
		assertTrue(o.fs().matches("(?s).*layout\\(std430, set = 1, binding = \\d+\\) readonly buffer blockData .*"));
		assertEquals(new Transpiler.BufferDecl("blockData", 3, true), o.analysis().buffers().getFirst());
	}

	@Test
	void imagesKeepFormatAndAccess() throws Exception {
		Out o = run("shadowcomp", ProgramGroup.SHADOWCOMP, null, null, null, null, """
				#version 430 compatibility
				layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
				layout(r16ui) uniform uimage2D voxel_img;
				writeonly uniform image2D outImg;
				void main() { uint v = imageLoad(voxel_img, ivec2(gl_GlobalInvocationID.xy)).r; imageStore(outImg, ivec2(0), vec4(float(v))); }
				""");
		String cs = o.glsl().get(StageType.COMPUTE);
		assertTrue(cs.matches("(?s).*layout\\(set = 1, binding = \\d+, r16ui\\) uniform uimage2D voxel_img;.*"));
		assertTrue(cs.matches("(?s).*layout\\(set = 1, binding = \\d+\\) uniform writeonly image2D outImg;.*"));
		assertTrue(cs.contains("layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;"));
		assertFalse(cs.contains("emr_main"));
		assertEquals(List.of(new Transpiler.ImageDecl("voxel_img", "uimage2D", "r16ui", "readwrite"),
				new Transpiler.ImageDecl("outImg", "image2D", null, "writeonly")), o.analysis().images());
	}

	@Test
	void structVaryingTakesOneLocationPerMember() throws Exception {
		String common = """
				struct Fog { vec3 color; mat2 m; float d[2]; };
				""";
		Out o = composite("#version 450\n" + common + """
				flat out Fog fog;
				flat out vec3 after;
				void main() { fog = Fog(vec3(0.0), mat2(1.0), float[2](0.0, 1.0)); after = vec3(1.0); gl_Position = vec4(0.0); }
				""", "#version 450\n" + common + """
				flat in Fog fog;
				flat in vec3 after;
				layout(location = 0) out vec4 c;
				void main() { c = vec4(fog.color + after, fog.d[1]); }
				""");
		assertTrue(o.vs().contains("layout(location = 0) flat out Fog fog;"));
		assertTrue(o.vs().contains("layout(location = 5) flat out vec3 after;")); // 1 + 2 + 2
	}

	// ---- step 9 and misc ----

	@Test
	void unusedFunctionsAndTheirUniformsAreDropped() throws Exception {
		Out o = composite(VS, """
				#version 130
				uniform float rainStrength;
				varying vec2 texCoord;
				float unusedHelper() { return rainStrength; }
				void main() { gl_FragData[0] = vec4(texCoord, 0.0, 1.0); }
				""");
		assertFalse(o.fs().contains("unusedHelper"));
		assertTrue(o.analysis().uniforms().stream().noneMatch(u -> u.name().equals("rainStrength")));
	}

	@Test
	void lineDirectivesMapBackToPreprocessedLines() throws Exception {
		Out o = composite(VS, "#version 130\nvarying vec2 texCoord;\n\n\nvoid main() {\n\tgl_FragData[0] = vec4(texCoord, 0.0, 1.0);\n}\n");
		assertTrue(o.fs().contains("#line 5\nvoid emr_main() {\n\temr_FragData0 = vec4(texCoord, 0.0, 1.0);"));
	}

	@Test
	void analysisReportsSet0AndInputsForGeometry() throws Exception {
		Out o = gbuffers("terrain_mdi", VS, FS);
		assertEquals(List.of("DynamicTransforms", "TerrainUniform", "ChunkSection", "Sampler0"), o.analysis().set0());
		assertEquals(List.of("Position", "UV0", "ChunkPosition"), o.analysis().vertexInputs());
		assertTrue(o.analysis().uniforms().stream().anyMatch(u -> u.name().equals("emr_CameraBlockPos") && u.glslType().equals("ivec3")));
		assertTrue(o.diagnostics().isEmpty(), o.diagnostics().toString());
	}
}
