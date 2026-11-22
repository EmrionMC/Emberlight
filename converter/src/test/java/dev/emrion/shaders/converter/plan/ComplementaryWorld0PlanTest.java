package dev.emrion.shaders.converter.plan;

import dev.emrion.shaders.format.Manifest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static dev.emrion.shaders.converter.plan.P.p;
import static dev.emrion.shaders.converter.plan.PlannerTestSupport.plan;
import static dev.emrion.shaders.converter.plan.PlannerTestSupport.props;
import static org.junit.jupiter.api.Assertions.*;

/** Complementary Unbound r5.9.3 + EP 1.10.5, world0, target settings. */
class ComplementaryWorld0PlanTest {

	/** lib/pipelineSettings.glsl + lib/common.glsl consts, included by every program. */
	private static final Map<String, String> COMMON = Map.ofEntries(
			Map.entry("colortex0Format", "R11F_G11F_B10F"), Map.entry("colortex1Format", "RGBA8_SNORM"),
			Map.entry("colortex2Format", "RGBA16F"), Map.entry("colortex3Format", "RGBA8"), Map.entry("colortex4Format", "RGBA8_SNORM"),
			Map.entry("colortex5Format", "RGBA8"), Map.entry("colortex6Format", "RGBA8"), Map.entry("colortex7Format", "RGBA16F"),
			Map.entry("colortex8Format", "RGBA16F"), Map.entry("colortex12Format", "RGBA8"), Map.entry("colortex13Format", "RGBA8"),
			Map.entry("colortex0Clear", "true"), Map.entry("colortex1Clear", "false"), Map.entry("colortex2Clear", "false"),
			Map.entry("colortex3Clear", "true"), Map.entry("colortex4Clear", "false"), Map.entry("colortex5Clear", "false"),
			Map.entry("colortex6Clear", "true"), Map.entry("colortex7Clear", "false"), Map.entry("colortex12Clear", "true"),
			Map.entry("colortex13Clear", "true"), Map.entry("shadowHardwareFiltering", "true"),
			Map.entry("shadowMapResolution", "2048"), Map.entry("shadowDistance", "256.0"), Map.entry("sunPathRotation", "-40.0"),
			Map.entry("noiseTextureResolution", "128"), Map.entry("shadowDistanceRenderMul", "1.0"),
			Map.entry("entityShadowDistanceMul", "0.125"));

	private static final String[] LIT = {"tex", "noisetex", "gaux4", "shadowcolor0", "floodfill_sampler",
			"floodfill_sampler_copy"};

	private static P lit(String name, Integer... draw) {
		return common(p(name).draw(draw).samplers(LIT).sampler("shadowtex0", "sampler2DShadow").sampler("shadowtex1", "sampler2DShadow")
				.sampler("voxel_sampler", "usampler3D"));
	}

	private static P common(P p) {
		COMMON.forEach(p::c);
		return p;
	}

	private static PlannerTestSupport.DimensionPlannerResult run() {
		var props = props(
				"shadow.culling", "reversed",
				"alphaTest.gbuffers_water", "GREATER 0.0001", "alphaTest.gbuffers_damagedblock", "GREATER 0.004",
				"size.buffer.colortex1", "1.0 1.0", "size.buffer.colortex7", "1.0 1.0",
				"blend.gbuffers_water", "SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA",
				"blend.gbuffers_water.colortex6", "off", "blend.gbuffers_water.colortex4", "off", "blend.gbuffers_water.colortex8", "off",
				"image.voxel_img", "voxel_sampler red_integer r16ui unsigned_int true false 512 256 512",
				"image.floodfill_img", "floodfill_sampler rgba rgba16f half_float false false 512 256 512",
				"image.floodfill_img_copy", "floodfill_sampler_copy rgba rgba16f half_float false false 512 256 512",
				"image.puddle_img", "puddle_sampler red_integer r8ui unsigned_int true false 128 128",
				"image.wsr_img", "wsr_sampler red_integer r16ui unsigned_int true false 512 64 512",
				"bufferObject.0", "810549248",
				"image.wsr_lod_img", "wsr_lod_sampler red_integer r8ui unsigned_int true false 128 16 128",
				"image.leaves_img", "leaves_sampler red_integer r8ui unsigned_int true false 128 128 128",
				"texture.noise", "lib/textures/noise.png",
				"texture.deferred.colortex3", "lib/textures/cloud-water.png",
				"texture.gbuffers.gaux4", "lib/textures/cloud-water.png",
				"customTexture.textureAtlas", "minecraft:textures/atlas/blocks.png",
				"customTexture.cloudWaterTex", "lib/textures/cloud-water.png",
				"customTexture.epWatermark", "lib/textures/euphoria_patches.png");
		return plan(props,
				common(p("shadow").draw(0, 1).samplers("tex", "noisetex", "gaux4").sampler("voxel_sampler", "usampler3D")
						.sampler("wsr_sampler", "usampler3D")
						.image("voxel_img", "uimage3D", "writeonly").image("puddle_img", "uimage2D", "writeonly")
						.image("wsr_img", "uimage3D", "writeonly").image("wsr_lod_img", "uimage3D", "writeonly")
						.buffer("blockDataBuffer", 0, false)),
				p("shadowcomp").compute(new int[]{8, 8, 8}, new int[]{64, 32, 64}).sampler("voxel_sampler", "usampler3D")
						.sampler("floodfill_sampler", "sampler3D").sampler("floodfill_sampler_copy", "sampler3D")
						.image("floodfill_img", "image3D", "writeonly").image("floodfill_img_copy", "image3D", "writeonly"),
				common(p("gbuffers_skybasic").draw(0).samplers("noisetex")),
				common(p("gbuffers_skytextured").draw(0).samplers("tex")),
				lit("gbuffers_terrain", 0, 6, 4).sampler("puddle_sampler", "usampler2D"),
				lit("gbuffers_block", 0, 3, 6, 4), lit("gbuffers_block_translucent", 0, 3, 6, 4),
				lit("gbuffers_entities", 0, 3, 6, 4), lit("gbuffers_entities_glowing", 0, 3, 6, 4),
				lit("gbuffers_entities_translucent", 0, 3, 6, 4, 13),
				lit("gbuffers_hand", 0, 3, 6, 4), lit("gbuffers_hand_water", 0, 3, 6, 4),
				lit("gbuffers_water", 0, 3, 6, 4, 8).samplers("gaux2", "depthtex1"),
				lit("gbuffers_textured", 0, 6, 3, 4).samplers("gaux2"), lit("gbuffers_basic", 0, 6, 4),
				common(p("gbuffers_beaconbeam").draw(0, 6).samplers("tex")), common(p("gbuffers_spidereyes").draw(0, 6).samplers("tex")),
				common(p("gbuffers_armor_glint").draw(0, 6).samplers("tex", "colortex6")),
				common(p("gbuffers_line").draw(0, 6, 4)), common(p("gbuffers_lightning").draw(0, 6, 4).samplers("tex")),
				common(p("gbuffers_damagedblock").draw(0).samplers("tex")), common(p("gbuffers_weather").draw(12).samplers("tex")),
				common(p("gbuffers_clouds").draw(0)),
				common(p("deferred1").draw(0, 5, 4).samplers("colortex0", "colortex4", "colortex5", "colortex6", "depthtex0", "noisetex")
						.sampler("shadowtex0", "sampler2DShadow").c("colortex0MipmapEnabled", "true")),
				common(p("composite").draw(7, 1).samplers("colortex0", "colortex1", "colortex4", "colortex5", "colortex6", "colortex7",
								"depthtex0", "depthtex1", "noisetex", "shadowcolor0", "floodfill_sampler", "floodfill_sampler_copy",
								"textureAtlas").sampler("shadowtex0", "sampler2DShadow").sampler("shadowtex1", "sampler2DShadow")
						.sampler("wsr_sampler", "usampler3D").sampler("wsr_lod_sampler", "usampler3D")
						.buffer("blockDataBuffer", 0, true).c("colortex5MipmapEnabled", "true")),
				common(p("composite1").draw(0, 5).samplers("colortex0", "colortex1", "colortex3", "colortex4", "colortex5", "colortex6",
								"colortex7", "colortex8", "colortex12", "colortex13", "depthtex0", "depthtex1", "noisetex", "shadowtex0",
								"shadowcolor1", "floodfill_sampler", "floodfill_sampler_copy")
						.sampler("shadowtex1", "sampler2DShadow")),
				common(p("composite2").draw(0).samplers("colortex0")),
				common(p("composite3").draw(0).samplers("colortex0")),
				common(p("composite4").draw(3).samplers("colortex0").c("colortex0MipmapEnabled", "true")),
				common(p("composite5").draw(3, 5).samplers("colortex0", "colortex3", "colortex5", "depthtex0", "noisetex")),
				common(p("composite6").draw(3, 2).samplers("colortex2", "colortex3", "colortex5", "colortex6", "depthtex0", "depthtex1")
						.c("colortex3MipmapEnabled", "true")),
				common(p("composite7").draw(3).samplers("colortex2", "colortex3", "depthtex0", "depthtex1")),
				common(p("final").samplers("colortex3", "noisetex")));
	}

	@Test
	void commandOrder() {
		assertEquals(List.of(
				"clear", "clear",
				"geometry shadow_opaque", "geometry shadow_entities", "copy_depth shadowtex0 shadowtex1", "geometry shadow_translucent",
				"compute shadowcomp",
				"geometry sky", "geometry opaque", "geometry hand_solid", "copy_depth depthtex0 depthtex1",
				"mipmaps colortex0 main", "fullscreen deferred1",
				"geometry translucent", "geometry hand_translucent",
				"mipmaps colortex5 alt", "fullscreen composite", "fullscreen composite1", "fullscreen composite2", "fullscreen composite3",
				"mipmaps colortex0 main", "fullscreen composite4", "fullscreen composite5",
				"mipmaps colortex3 main", "fullscreen composite6", "fullscreen composite7",
				"final final",
				"copy colortex1", "copy colortex2", "copy colortex4", "copy colortex5", "copy colortex7"), run().frame());
	}

	@Test
	void flipSchedule() {
		var r = run();
		// Iris toggles every draw buffer of every composite-like pass, so every written buffer needs an alt
		// image; the buffers read and written within one pass (0, 2, 3, 5, plus 1, 4, 7) all ping-pong.
		Set<Integer> alt = new TreeSet<>();
		for (var t : r.dim().targets()) if (t.hasAlt()) alt.add(Integer.parseInt(t.name().substring(8)));
		assertEquals(Set.of(0, 1, 2, 3, 4, 5, 7), alt);
		assertEquals(List.of(1, 2, 4, 5, 7), r.flipState(r.command("final", "final").flipState()));
		assertEquals(List.of(0, 1, 4, 5, 7), r.flipState(r.command("fullscreen", "composite1").flipState()));
		assertEquals("main", r.side("composite1", "colortex0")); // reads alt, writes main
		assertEquals("alt", r.side("composite2", "colortex0"));
		assertEquals("alt", r.side("composite6", "colortex2"));
	}

	@Test
	void clearsAndFormats() {
		var r = run();
		for (int n : List.of(1, 2, 4, 5, 7)) assertFalse(r.target("colortex" + n).clear(), "colortex" + n);
		for (int n : List.of(0, 3, 6, 8, 12, 13)) assertTrue(r.target("colortex" + n).clear(), "colortex" + n);
		assertTrue(r.target("colortex0").clearFog());
		assertEquals("R11F_G11F_B10F", r.target("colortex0").format());
		assertEquals("RGBA8_SNORM", r.target("colortex1").format());
		List<String> colortex = new ArrayList<>();
		for (var t : r.dim().targets()) if (t.kind().equals("color")) colortex.add(t.name());
		assertEquals(List.of("colortex0", "colortex1", "colortex2", "colortex3", "colortex4", "colortex5", "colortex6", "colortex7",
				"colortex8", "colortex12", "colortex13"), colortex);
		assertNull(r.target("depthtex2"));
		for (String n : List.of("shadowtex0", "shadowtex1", "shadowcolor0", "shadowcolor1"))
			assertArrayEquals(new int[]{2048, 2048}, r.target(n).absoluteSize(), n);
		assertArrayEquals(new float[]{1, 1, 1, 1}, r.target("shadowcolor0").clearColor());
		assertEquals(0, r.target("colortex0").mipLevels());
		assertEquals(1, r.target("colortex6").mipLevels());
	}

	@Test
	void imagesAndBuffers() {
		var r = run();
		record Img(String format, int[] size, boolean clear) {}
		Map<String, Img> expected = Map.of(
				"voxel_img", new Img("R16UI", new int[]{512, 256, 512}, true),
				"floodfill_img", new Img("RGBA16F", new int[]{512, 256, 512}, false),
				"floodfill_img_copy", new Img("RGBA16F", new int[]{512, 256, 512}, false),
				"puddle_img", new Img("R8UI", new int[]{128, 128}, true),
				"wsr_img", new Img("R16UI", new int[]{512, 64, 512}, true),
				"wsr_lod_img", new Img("R8UI", new int[]{128, 16, 128}, true),
				"leaves_img", new Img("R8UI", new int[]{128, 128, 128}, true));
		expected.forEach((n, e) -> {
			var t = r.target(n);
			assertEquals(e.format(), t.format(), n);
			assertArrayEquals(e.size(), t.absoluteSize(), n);
			assertEquals(e.size().length, t.dims(), n);
			assertEquals(e.clear(), t.clear(), n);
		});
		assertEquals(810549248L, r.target("bufferObject0").bytes());
		var shadow = r.dim().programs().get("shadow");
		assertTrue(shadow.writesStorage().contains(r.binding("shadow", "blockDataBuffer").binding()));
		assertFalse(r.dim().programs().get("composite").writesStorage().contains(r.binding("composite", "blockDataBuffer").binding()));
		assertEquals(List.of(64, 32, 64), java.util.Arrays.stream(r.dim().programs().get("shadowcomp").workGroups()).boxed().toList());
		// clear=true images are cleared at frame start, all images once
		var first = r.dim().frame().getFirst().targets().stream().map(Manifest.ClearOp::name).toList();
		assertTrue(first.containsAll(List.of("voxel_img", "puddle_img", "wsr_img", "wsr_lod_img", "leaves_img", "shadowtex0")));
		assertFalse(first.contains("floodfill_img"));
	}

	@Test
	void resources() {
		var r = run();
		var cmp = r.binding("composite", "shadowtex0");
		var plain = r.binding("composite1", "shadowtex0");
		assertEquals("shadowtex0", cmp.target());
		assertEquals("shadowtex0", plain.target());
		assertEquals("lequal", cmp.sampler().compare());
		assertNull(plain.sampler().compare());
		assertEquals("cloud-water", r.binding("gbuffers_water", "gaux4").texture());
		assertEquals("cloud-water", r.binding("shadow", "gaux4").texture());
		assertEquals("colortex7", r.binding("composite", "colortex7").target());
		assertEquals("colortex5", r.binding("gbuffers_water", "gaux2").target());
		assertEquals("block_atlas", r.binding("composite", "textureAtlas").builtin());
		assertEquals("noise", r.binding("final", "noisetex").texture());
		assertEquals("linear", r.binding("composite", "colortex5").sampler().mipmap());
		assertEquals("linear", r.binding("composite5", "colortex0").sampler().mipmap());
		assertEquals("none", r.binding("composite1", "colortex0").sampler().mipmap());
		assertEquals("nearest", r.binding("gbuffers_terrain", "voxel_sampler").sampler().filter());
		assertEquals("linear", r.binding("gbuffers_terrain", "floodfill_sampler").sampler().filter());
		assertTrue(r.dim().resources().stream().allMatch(x -> x.binding() >= 0));
		assertEquals(r.dim().resources().size(), r.dim().resources().stream().map(Manifest.Resource::binding).distinct().count());
		assertTrue(r.diags().all().stream().noneMatch(d -> d.severity() == dev.emrion.shaders.converter.report.Diagnostic.Severity.WARNING),
				() -> r.diags().all().toString());
	}

	@Test
	void programsAndShadow() {
		var r = run();
		var water = r.dim().programs().get("gbuffers_water");
		assertEquals("custom", water.blend().defaultMode().mode());
		assertEquals(Set.of("colortex6", "colortex4", "colortex8"), water.blend().perTarget().keySet());
		assertEquals(0.0001f, water.alphaTest().ref());
		assertTrue(r.dim().programs().get("gbuffers_textured").fallbackFor().contains("gbuffers_particles"));
		assertNull(r.dim().programs().get("gbuffers_entities_glowing"), "never drawn by any ShaderKey");
		var s = r.dim().shadow();
		assertTrue(s.enabled());
		assertEquals(2048, s.resolution());
		assertEquals(256f, s.distance());
		assertEquals(-40f, s.sunPathRotation());
		assertEquals(0.125f, s.entityDistanceMul());
		assertEquals("reversed", s.culling());
		assertArrayEquals(new boolean[]{true, true}, s.hardwareFiltering());
	}
}
