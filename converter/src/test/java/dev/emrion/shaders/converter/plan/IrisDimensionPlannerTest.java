package dev.emrion.shaders.converter.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.emrion.shaders.converter.plan.P.p;
import static dev.emrion.shaders.converter.plan.PlannerTestSupport.plan;
import static dev.emrion.shaders.converter.plan.PlannerTestSupport.props;
import static org.junit.jupiter.api.Assertions.*;

class IrisDimensionPlannerTest {

	// ---- flip schedule ----

	@Test
	void compositeReadsMainWritesAltThenFlips() {
		var r = plan(props(), p("composite").draw(0).samplers("colortex0"), p("composite1").draw(0).samplers("colortex0"));
		assertEquals("alt", r.side("composite", "colortex0"));
		assertEquals(List.of(), r.flipState(r.command("fullscreen", "composite").flipState()));
		assertEquals("main", r.side("composite1", "colortex0"));
		assertEquals(List.of(0), r.flipState(r.command("fullscreen", "composite1").flipState()));
		assertTrue(r.target("colortex0").hasAlt());
		// two flips → F_final = ∅ → no end-of-frame copy
		assertFalse(r.frame().contains("copy colortex0"));
	}

	@Test
	void endOfFrameCopyOnlyForFlippedNonClearedBuffers() {
		var r = plan(props(), p("composite").draw(2, 3).c("colortex2Clear", "false"));
		assertTrue(r.frame().contains("copy colortex2"));
		assertFalse(r.frame().contains("copy colortex3"));
	}

	@Test
	void explicitFlipFalseSuppressesAndTrueTogglesUndrawnBuffer() {
		var r = plan(props("flip.composite.colortex0", "false", "flip.composite.colortex5", "true"),
				p("composite").draw(0).samplers("colortex5"), p("composite1").draw(1).samplers("colortex0", "colortex5"));
		assertEquals(List.of(5), r.flipState(r.command("fullscreen", "composite1").flipState()));
		assertEquals("alt", r.side("composite1", "colortex1"));
		assertTrue(r.target("colortex5").hasAlt());
	}

	@Test
	void prePassFlip() {
		var r = plan(props("flip.deferred_pre.colortex4", "true"), p("deferred").draw(1).samplers("colortex4"));
		assertEquals(List.of(4), r.flipState(r.command("fullscreen", "deferred").flipState()));
	}

	@Test
	void gbufferSnapshotsAfterPrepareAndAfterDeferred() {
		var r = plan(props(), p("gbuffers_terrain").draw(0), p("gbuffers_water").draw(0),
				p("prepare").draw(4), p("deferred").draw(5), p("begin").draw(6));
		int opaque = r.dim().frame().stream().filter(c -> "opaque".equals(c.phase())).findFirst().orElseThrow().flipState();
		int translucent = r.dim().frame().stream().filter(c -> "translucent".equals(c.phase())).findFirst().orElseThrow().flipState();
		assertEquals(List.of(4, 6), r.flipState(opaque));
		assertEquals(List.of(4, 5, 6), r.flipState(translucent));
	}

	@Test
	void computeOnlyPassDoesNotFlipAndRunsBeforeFullscreen() {
		var r = plan(props(), p("composite_a").compute(new int[]{8, 8, 1}, null), p("composite1_a").compute(new int[]{8, 8, 1}, null),
				p("composite1").draw(0));
		assertEquals(List.of("clear", "clear", "geometry sky", "geometry opaque", "geometry hand_solid", "geometry translucent",
				"geometry hand_translucent", "compute composite_a", "compute composite1_a", "fullscreen composite1", "final null"), r.frame());
		assertEquals(List.of(), r.flipState(r.command("fullscreen", "composite1").flipState()));
		assertArrayEquals(new int[]{8, 8, 1}, r.dim().programs().get("composite_a").localSize());
	}

	@Test
	void computesOfAPassRunBeforeItsFullscreenProgram() {
		// composite.csh next to composite.fsh: the loader names it composite_csh (Iris: composite.csh, composite_a.csh, then composite)
		var r = plan(props(), p("composite_csh").compute(new int[]{8, 8, 1}, null), p("composite_a").compute(new int[]{8, 8, 1}, null),
				p("composite").draw(0), p("composite1").draw(0));
		assertEquals(List.of("clear", "clear", "geometry sky", "geometry opaque", "geometry hand_solid", "geometry translucent",
				"geometry hand_translucent", "compute composite_csh", "compute composite_a", "fullscreen composite", "fullscreen composite1",
				"final null"), r.frame());
		int fs = r.command("fullscreen", "composite").flipState();
		assertEquals(fs, r.command("compute", "composite_csh").flipState(), "computes see the pass's flip state");
		assertEquals(List.of(0), r.flipState(r.command("fullscreen", "composite1").flipState()), "only the fullscreen program flips");
		assertTrue(r.diags().all().stream().noneMatch(d -> d.message().contains("composite_csh")), r.diags().all().toString());
	}

	@Test
	void shadowcompFlipsShadowcolorLikeIris() {
		var one = plan(props(), p("shadowcomp").draw(0), p("composite").draw(0).samplers("shadowcolor0"));
		var w = one.command("fullscreen", "shadowcomp").writes().getFirst();
		assertEquals("alt", w.side(), "a shadowcomp pass starts reading main and writing alt");
		assertNull(one.dim().flipStates().get(one.command("fullscreen", "shadowcomp").flipState()).shadowAlt());
		assertEquals(List.of(0), one.dim().flipStates().get(one.command("fullscreen", "composite").flipState()).shadowAlt(),
				"later passes read the side shadowcomp wrote");
		assertTrue(one.target("shadowcolor0").hasAlt());
		assertTrue(one.diags().all().stream().noneMatch(d -> d.message().contains("shadowcolor flipping")));

		var two = plan(props("flip.shadowcomp_pre.colortex1", "true"), p("shadowcomp").draw(0), p("shadowcomp1").draw(0),
				p("composite").draw(0).samplers("shadowcolor0", "shadowcolor1"));
		assertEquals("main", two.command("fullscreen", "shadowcomp1").writes().getFirst().side());
		assertEquals(List.of(0, 1), two.dim().flipStates().get(two.command("fullscreen", "shadowcomp1").flipState()).shadowAlt());
		assertEquals(List.of(1), two.dim().flipStates().get(two.command("fullscreen", "composite").flipState()).shadowAlt(),
				"shadowcolor0 flipped twice, shadowcolor1 by shadowcomp_pre");
	}

	// ---- draw buffers ----

	@Test
	void irisDefaultDrawBuffers() {
		var r = plan(props(), p("gbuffers_basic"), p("shadow"), p("composite").samplers("shadowtex0"));
		assertEquals(List.of("colortex0"), r.dim().programs().get("gbuffers_basic").outputs().stream().map(o -> o.target()).toList());
		assertEquals(List.of("shadowcolor0", "shadowcolor1"), r.dim().programs().get("shadow").outputs().stream().map(o -> o.target()).toList());
		assertEquals(List.of("colortex0"), r.dim().programs().get("composite").outputs().stream().map(o -> o.target()).toList());
	}

	@Test
	void outputLocationsAreDrawBufferPositions() {
		var r = plan(props(), p("composite").draw(7, 1));
		assertEquals(java.util.Map.of(0, 0, 1, 1), r.plan().emits().get("composite").context().outputLocations());
		assertEquals("colortex7", r.dim().programs().get("composite").outputs().getFirst().target());
	}

	// ---- program table ----

	@Test
	void fallbackChainsAndVariants() {
		var r = plan(props(), p("gbuffers_textured"), p("gbuffers_terrain"), p("shadow"), p("composite").samplers("shadowtex1"));
		var terrain = r.dim().programs().get("gbuffers_terrain");
		assertTrue(terrain.fallbackFor().containsAll(List.of("gbuffers_terrain_solid", "gbuffers_terrain_cutout", "gbuffers_water",
				"gbuffers_block", "gbuffers_damagedblock")));
		assertTrue(r.dim().programs().get("gbuffers_textured").fallbackFor().contains("gbuffers_particles"));
		var shadow = r.dim().programs().get("shadow");
		assertTrue(shadow.fallbackFor().containsAll(List.of("shadow_cutout", "shadow_water", "shadow_entities", "shadow_lightning")));
		assertTrue(r.plan().emits().get("shadow").vertexVariants().containsAll(List.of("terrain_mdi", "terrain_ubo", "entity")));
		assertEquals(List.of("fullscreen"), r.plan().emits().get("composite").vertexVariants());
		assertEquals("off", shadow.blend().defaultMode().mode());
	}

	@Test
	void shadowPassOnlyWhenANonShadowProgramSamplesShadows() {
		var none = plan(props(), p("shadow"), p("gbuffers_terrain"), p("composite"));
		assertFalse(none.dim().shadow().enabled());
		assertNull(none.dim().programs().get("shadow"));
		assertNull(none.target("shadowtex0"));
		// MakeUp UltraFast: only gbuffers sample the shadow map, Iris still renders it
		var gbuffers = plan(props(), p("shadow"), p("gbuffers_terrain").samplers("shadowtex0"), p("composite"));
		assertTrue(gbuffers.dim().shadow().enabled());
		assertNotNull(gbuffers.target("shadowtex0"));
		var forced = plan(props("shadow.enabled", "true"), p("shadow"), p("composite"));
		assertTrue(forced.dim().shadow().enabled());
		var off = plan(props("shadow.enabled", "false"), p("shadow"), p("composite").samplers("shadowtex0"));
		assertFalse(off.dim().shadow().enabled());
		assertNotNull(off.target("shadowtex0"));
	}

	@Test
	void blendAlphaTestAndScaleOverrides() {
		var r = plan(props("blend.gbuffers_water", "SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA",
						"blend.gbuffers_water.colortex6", "off", "alphaTest.gbuffers_water", "GREATER 0.0001",
						"alphaTest.gbuffers_terrain", "off", "scale.composite", "0.5 0 0"),
				p("gbuffers_water").draw(0, 6), p("gbuffers_terrain"), p("gbuffers_spidereyes"), p("composite"));
		var water = r.dim().programs().get("gbuffers_water");
		assertEquals("custom", water.blend().defaultMode().mode());
		assertEquals("one_minus_src_alpha", water.blend().defaultMode().dst());
		assertEquals("off", water.blend().perTarget().get("colortex6").mode());
		assertEquals("greater", water.alphaTest().func());
		assertEquals(0.0001f, water.alphaTest().ref());
		assertEquals("always", r.dim().programs().get("gbuffers_terrain").alphaTest().func());
		assertEquals("one", r.dim().programs().get("gbuffers_spidereyes").blend().defaultMode().dst());
		assertEquals(0.5f, r.dim().programs().get("composite").viewportScale());
		assertNull(r.dim().programs().get("composite").viewportOffset());
		assertEquals("off", r.dim().programs().get("composite").blend().defaultMode().mode());
	}

	@Test
	void scaleOffsetIsKept() {
		var r = plan(props("scale.composite1", "0.25 0.5 0.75"), p("composite1"));
		assertEquals(0.25f, r.dim().programs().get("composite1").viewportScale());
		assertArrayEquals(new float[]{0.5f, 0.75f}, r.dim().programs().get("composite1").viewportOffset());
	}

	// ---- targets ----

	@Test
	void formatsClearsAndSizes() {
		var r = plan(props("size.buffer.colortex7", "0.5 256"), p("composite").draw(0, 1, 2, 7)
				.c("gnormalFormat", "rgba16").c("colortex7Clear", "false")
				.c("colortex1ClearColor", "vec4(0.5)"));
		var c0 = r.target("colortex0");
		assertTrue(c0.clearFog());
		assertEquals("RGBA8", c0.format());
		var c2 = r.target("colortex2");
		assertEquals("RGBA16", c2.format(), "gnormal alias");
		var c1 = r.target("colortex1");
		assertArrayEquals(new float[]{0.5f, 0.5f, 0.5f, 0.5f}, c1.clearColor());
		var c7 = r.target("colortex7");
		assertFalse(c7.clear());
		assertArrayEquals(new float[]{0.5f, 1f}, c7.relativeSize());
		assertArrayEquals(new int[]{0, 256}, c7.absoluteSize());
		assertNull(r.target("colortex3"));
		assertNull(r.target("depthtex1"));
	}

	@Test
	void rgbFormatsArePromoted() {
		var r = plan(props(), p("composite").draw(0, 1).c("colortex0Format", "RGB16F").c("colortex1Format", "R11F_G11F_B10F"));
		assertEquals("RGBA16F", r.target("colortex0").format());
		assertEquals("RGB16F", r.target("colortex0").declaredFormat());
		assertEquals("R11F_G11F_B10F", r.target("colortex1").format());
	}

	@Test
	void customImagesAndSsbos() {
		var r = plan(props("image.vox", "vox_sampler red_integer r16ui unsigned_int true false 512 256 512",
						"image.rel", "none rgba rgba16f half_float false true 0.5 0.5", "bufferObject.0", "1024"),
				p("composite").image("vox", "uimage3D", "writeonly").sampler("vox_sampler", "usampler3D").buffer("ssbo", 0, true));
		var vox = r.target("vox");
		assertEquals(3, vox.dims());
		assertArrayEquals(new int[]{512, 256, 512}, vox.absoluteSize());
		assertEquals("R16UI", vox.format());
		assertTrue(vox.clear());
		assertArrayEquals(new float[]{0.5f, 0.5f}, r.target("rel").relativeSize());
		assertEquals(1024, r.target("bufferObject0").bytes());
		assertEquals("storage_image", r.binding("composite", "vox").kind());
		assertEquals("nearest", r.binding("composite", "vox_sampler").sampler().filter());
		assertEquals("storage_buffer", r.binding("composite", "ssbo").kind());
		var prog = r.dim().programs().get("composite");
		assertEquals(List.of(r.binding("composite", "vox").binding()), prog.writesStorage());
	}

	// ---- resources ----

	@Test
	void aliasesResolvePerGroup() {
		var r = plan(props("texture.gbuffers.gaux4", "lib/textures/cloud-water.png"),
				p("gbuffers_water").samplers("gaux4", "colortex7", "tex", "depthtex0", "noisetex"),
				p("composite").samplers("gaux4", "gdepthtex", "gcolor").draw(7));
		assertEquals("cloud-water", r.binding("gbuffers_water", "gaux4").texture());
		assertEquals(r.binding("gbuffers_water", "gaux4"), r.binding("gbuffers_water", "colortex7"));
		assertEquals("shaders/lib/textures/cloud-water.png", r.plan().textures().getFirst().file());
		assertNull(r.binding("gbuffers_water", "tex"), "set 0 (MC) sampler");
		assertEquals("nearest", r.binding("gbuffers_water", "depthtex0").sampler().filter());
		assertEquals("repeat", r.binding("gbuffers_water", "noisetex").sampler().wrap());
		assertEquals("colortex7", r.binding("composite", "gaux4").target());
		assertEquals("depthtex0", r.binding("composite", "gdepthtex").target());
		assertEquals("colortex0", r.binding("composite", "gcolor").target());
	}

	@Test
	void colortex0To3NotSamplableInGbuffers() {
		var r = plan(props(), p("gbuffers_terrain").samplers("colortex2"));
		// not a colortex binding: falls back to the unit-0 equivalent (white), and no colortex2 is allocated
		assertEquals("white", r.binding("gbuffers_terrain", "colortex2").builtin());
		assertNull(r.target("colortex2"));
	}

	@Test
	void stageCustomTextureOverridesUntilBufferFlipped() {
		var r = plan(props("texture.composite.colortex3", "a.png"),
				p("composite").samplers("colortex3"), p("composite1").draw(3), p("composite2").samplers("colortex3"));
		assertEquals("a", r.binding("composite", "colortex3").texture());
		assertEquals("colortex3", r.binding("composite2", "colortex3").target());
	}

	@Test
	void rawStageTextureOnlyReplacesSamplerOfItsType() {
		String raw = "lut.dat TEXTURE_3D R8 8 8 8 RED UNSIGNED_BYTE";
		var r = plan(props("texture.deferred.depthtex0", raw, "texture.composite.colortex0", "lut.dat TEXTURE_2D R8 8 8 RED UNSIGNED_BYTE"),
				p("deferred").draw(1).samplers("depthtex0"), p("deferred1").draw(1).sampler("depthtex0", "sampler3D"),
				p("composite").draw(1).samplers("colortex0"));
		assertEquals("depthtex0", r.binding("deferred", "depthtex0").target()); // a sampler2D keeps the depth buffer
		// raw textures are unsupported: the typed sampler falls back instead of crashing, even when colortex0 is overridden
		assertNotNull(r.binding("deferred1", "depthtex0"));
		assertNotNull(r.binding("composite", "colortex0"));
	}

	@Test
	void shadowCompareAndPlainBindings() {
		var r = plan(props(), p("composite").sampler("shadowtex0", "sampler2DShadow").c("shadowHardwareFiltering", "true"),
				p("composite1").sampler("shadowtex0", "sampler2D").sampler("shadow", "sampler2DShadow"));
		var cmp = r.binding("composite", "shadowtex0");
		var plain = r.binding("composite1", "shadowtex0");
		assertEquals("lequal", cmp.sampler().compare());
		assertNull(plain.sampler().compare());
		assertNotEquals(cmp.binding(), plain.binding());
		assertEquals(cmp, r.binding("composite1", "shadow"));
		assertTrue(r.dim().shadow().hardwareFiltering()[1]);
	}

	@Test
	void watershadowSwapsShadowToShadowtex1() {
		var r = plan(props(), p("composite").samplers("watershadow", "shadow"));
		assertEquals("shadowtex0", r.binding("composite", "watershadow").target());
		assertEquals("shadowtex1", r.binding("composite", "shadow").target());
	}

	@Test
	void mipmappedSamplerAfterGeneration() {
		var r = plan(props(), p("composite").draw(1).samplers("colortex0").c("colortex0MipmapEnabled", "true"),
				p("composite1").draw(2).samplers("colortex0"), p("composite2").draw(0), p("composite3").draw(3).samplers("colortex0"));
		assertTrue(r.frame().contains("mipmaps colortex0 main"));
		assertEquals("linear", r.binding("composite", "colortex0").sampler().mipmap());
		assertEquals("linear", r.binding("composite1", "colortex0").sampler().mipmap());
		assertEquals("none", r.binding("composite3", "colortex0").sampler().mipmap(), "alt side has no mips");
		assertEquals(0, r.target("colortex0").mipLevels());
		assertEquals(List.of("colortex0"), r.dim().programs().get("composite").mipmapBefore());
	}

	@Test
	void depthCopiesOnlyWhenReferenced() {
		var r = plan(props(), p("composite").samplers("depthtex1"));
		assertTrue(r.frame().contains("copy_depth depthtex0 depthtex1"));
		assertFalse(r.frame().contains("copy_depth depthtex0 depthtex2"));
		assertNotNull(r.target("depthtex1"));
	}
}
