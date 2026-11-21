package dev.emrion.shaders.mod.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.mixin.BlockEntityRenderStateAccessor;
import dev.emrion.shaders.mod.pipeline.GbufferPipelines.Context;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.EntityIds;
import dev.emrion.shaders.mod.uniforms.UniformSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Iris's ShadowRenderer.renderShadows on MC's own draw code, before the main pass: opaque terrain → entities → shadowtex1
 * copy → translucent terrain. Terrain reuses Sodium's region buffers with the shadow-culled section list; entities and block
 * entities are extracted and submitted again into our own dispatcher, as Iris does.
 */
final class ShadowPass {
	private ShadowPass() {}

	/** Iris config {@code maxShadowRenderDistance}: the shadow distance in chunks when the pack's mul is negative. */
	private static final int MAX_SHADOW_RENDER_DISTANCE = 24;
	/** Iris AdvancedShadowCullingFrustum.SECTION_HALF_SIZE − 8: model extent + epsilon around a section. */
	private static final float SECTION_MARGIN = 1.125f;
	private static final ReceiverGrid GRID = new ReceiverGrid();
	private static final org.joml.FrustumIntersection CAMERA_FRUSTUM = new org.joml.FrustumIntersection();

	private static final ShadowCuller TERRAIN = new ShadowCuller(), ENTITIES = new ShadowCuller();
	private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
	private static final Vector3f LIGHT = new Vector3f();
	private static SubmitNodeStorage submits;
	private static FeatureRenderDispatcher dispatcher;
	private static RenderBuffers buffers;

	/** True while the shadow phases render (Iris API isRenderingShadowPass). */
	static volatile boolean rendering;

	static void render(FrameExecutor e, LevelRenderer level) {
		rendering = true;
		try {
			renderPhases(e, level);
		} finally {
			rendering = false;
		}
	}

	private static void renderPhases(FrameExecutor e, LevelRenderer level) {
		Manifest.Command opaque = e.advanceTo("shadow_opaque");
		if (opaque == null) return;
		var mc = Minecraft.getInstance();
		Manifest.Shadow s = e.dimension().shadow();
		UniformSystem u = e.uniforms();
		Matrix4fc shadowView = u.shadowModelView();
		CameraRenderState camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		Vec3 cam = camera.pos;
		setupCullers(e, s, u, mc.options.getEffectiveRenderDistance() * 16f);

		long t0 = System.nanoTime();
		boolean terrain = prop(e.manifest(), "shadowTerrain") || prop(e.manifest(), "shadowTranslucent");
		// block entity casters come from the section walk too; entities have their own culler
		if (terrain || prop(e.manifest(), "shadowBlockEntities") || prop(e.manifest(), "shadowLightBlockEntities")) collectSodium(cam, shadowView);
		long t1 = System.nanoTime();
		FeatureRenderDispatcher.PreparedFrame entities = prepareEntities(e, level, camera, shadowView);
		long t3 = System.nanoTime();

		var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true); // Iris: nearest, mipmapped
		try {
			try (RenderPass pass = e.beginPhase(opaque, Context.SHADOW, UniformSystem.Phase.SHADOW, GeometryHooks.TERRAIN_SOLID)) {
				RenderSystem.bindDefaultUniforms(pass);
				if (prop(e.manifest(), "shadowTerrain")) drawSodium(pass, false, u, shadowView, cam, sampler);
			}
			e.endPhase();
			if (ShadowStats.ENABLED) ShadowStats.cpu(t1 - t0, t3 - t1, System.nanoTime() - t3, cam);
			Manifest.Command g = e.advanceTo("shadow_entities");
			if (g != null && entities != null) {
				try (RenderPass pass = e.beginPhase(g, Context.SHADOW, UniformSystem.Phase.SHADOW, GeometryHooks.ENTITIES)) {
					RenderSystem.bindDefaultUniforms(pass);
					FeatureRenderDispatcher.renderAllFeatures(pass, entities);
				}
				e.endPhase();
			}
		} finally {
			if (entities != null) entities.close();
		}
		Manifest.Command translucent = e.advanceTo("shadow_translucent"); // runs the shadowtex1 copy before it
		if (translucent != null && terrain && prop(e.manifest(), "shadowTranslucent")) {
			try (RenderPass pass = e.beginPhase(translucent, Context.SHADOW, UniformSystem.Phase.SHADOW, GeometryHooks.TERRAIN_TRANSLUCENT)) {
				RenderSystem.bindDefaultUniforms(pass);
				drawSodium(pass, true, u, shadowView, cam, sampler);
			}
			e.endPhase();
		}
	}

	/**
	 * Iris extractVisibleBlockEntities + renderBlockEntities: MC's block-entity extraction (LevelExtractor) over the shadow-visible
	 * sections plus the globally rendered ones, submitted relative to the camera; {@code lightsOnly} keeps light emitters.
	 */
	private static void submitBlockEntities(LevelRenderer level, CameraRenderState camera, PoseStack pose, boolean lightsOnly) {
		var mc = Minecraft.getInstance();
		var dispatcher = level.blockEntityRenderDispatcher();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 cam = camera.pos;
		java.util.List<BlockEntityRenderState> states = new java.util.ArrayList<>();
		for (BlockEntity be : dev.emrion.shaders.mod.compat.sodium.SodiumShadow.BLOCK_ENTITIES) {
			if (be.isRemoved()) continue;
			var state = dispatcher.tryExtractRenderState(be, partial, null, false);
			if (state != null) states.add(state);
		}
		for (var state : states) {
			if (lightsOnly && ((BlockEntityRenderStateAccessor) state).emrion$blockState().getLightEmission() == 0) continue;
			BlockPos p = state.blockPos;
			pose.pushPose();
			pose.translate(p.getX() - cam.x, p.getY() - cam.y, p.getZ() - cam.z);
			dispatcher.submit(state, pose, submits, camera);
			pose.popPose();
		}
	}

	/** End of every frame, as MC's own RenderBuffers: recycles the vertex buffers of the shadow entity draws (else a new GPU buffer per frame). */
	static void endFrame() {
		if (buffers != null) buffers.endFrame();
	}

	/** A boolean shaders.properties value (Iris defaults: shadowTerrain/Translucent/Entities true, shadowPlayer false). */
	private static boolean prop(Manifest m, String key) {
		Object v = m.properties() == null ? null : m.properties().get(key);
		return v == null ? !key.equals("shadowPlayer") : Boolean.TRUE.equals(v) || "true".equals(v);
	}

	// ---- culling (Iris ShadowRenderer.createShadowFrustum) --------------------------------------------------------------

	private static void setupCullers(FrameExecutor e, Manifest.Shadow s, UniformSystem u, float renderDistance) {
		u.gbufferProjection().mul(u.gbufferModelView(), VIEW_PROJECTION);
		Matrix4fc sv = u.shadowModelView();
		LIGHT.set(sv.m02(), sv.m12(), sv.m22()).normalize(); // shadow view +Z (towards the light) in camera-relative space
		var shadow = e.dimension().programs().values().stream().filter(p -> p.kind().equals("shadow") && p.fallbackFor().contains("shadow")).findFirst()
				.orElse(e.dimension().programs().get("shadow")); // the program serving the "shadow" slot (native packs: <pass>.shadow)
		boolean voxelizes = !shadow.writesStorage().isEmpty() || shadow.stages().geometry() != null; // Iris: image stores or a shadow.gsh = voxelization
		float mul = s.distanceRenderMul(), entityMul = s.entityDistanceMul();
		configure(TERRAIN, s, voxelizes, mul, renderDistance);
		if (entityMul == 1f || entityMul < 0f) configure(ENTITIES, s, voxelizes, mul, renderDistance);
		else configure(ENTITIES, s, voxelizes, mul * entityMul, renderDistance);
	}

	private static void configure(ShadowCuller c, Manifest.Shadow s, boolean voxelizes, float mul, float renderDistance) {
		String mode = s.culling();
		if (mode.equals("distance") || mode.equals("default") && voxelizes) {
			float d = s.distance() * mul;
			c.set(-1, d <= 0 || d > renderDistance ? ShadowCuller.UNLIMITED : d, false, VIEW_PROJECTION, LIGHT);
			return;
		}
		boolean safeZone = mode.equals("reversed");
		if (safeZone && mul < 0) mul = 1f;
		float d = (safeZone ? s.voxelDistance() : s.distance()) * mul;
		if (mul < 0) d = MAX_SHADOW_RENDER_DISTANCE * 16f;
		if (safeZone) c.set(d, s.distance() * mul, true, VIEW_PROJECTION, LIGHT);
		else c.set(-1, d >= renderDistance ? ShadowCuller.UNLIMITED : d, true, VIEW_PROJECTION, LIGHT);
	}

	// ---- terrain ---------------------------------------------------------------------------------------------------

	/** Receiver test of a candidate section (camera-relative minimum corner). */
	private static boolean casts(String mode, float x, float y, float z) {
		return TERRAIN.inSafe(x, y, z, x + 16, y + 16, z + 16)
				|| mode.equals("outside") && y + 16 >= -128 && y <= 128 && x + 16 >= -256 && x <= 256 && z + 16 >= -256 && z <= 256
				|| GRID.casts(x - SECTION_MARGIN, y - SECTION_MARGIN, z - SECTION_MARGIN, x + 16 + SECTION_MARGIN, y + 16 + SECTION_MARGIN,
						z + 16 + SECTION_MARGIN);
	}

	/** Starts the receiver grid unless the option is off: the camera and hand, plus the voxel volume in "outside" mode. */
	private static boolean beginReceivers(String mode, Matrix4fc shadowView) {
		if (mode.equals("off")) return false;
		GRID.begin(shadowView);
		CAMERA_FRUSTUM.set(VIEW_PROJECTION, false);
		GRID.addReceiver(-2, -2, -2, 2, 2, 2); // the camera and the hand
		// shortcut: hard-coded Complementary voxel volume (512x256x512) as receivers; read the pack's voxel size if another pack needs it
		if (mode.equals("outside")) GRID.addReceiver(-256, -128, -256, 256, 128, 256);
		return true;
	}

	/** The shadow-visible sections: the culler and receiver tests over Sodium's built sections (compat.sodium.SodiumShadow). */
	private static void collectSodium(Vec3 cam, Matrix4fc shadowView) {
		String mode = ReceiverGrid.mode;
		dev.emrion.shaders.mod.compat.sodium.SodiumShadow.collect(new SodiumCuller(mode, beginReceivers(mode, shadowView), shadowView), cam.x, cam.y, cam.z);
	}

	private record SodiumCuller(String mode, boolean receivers, Matrix4fc view) implements dev.emrion.shaders.mod.compat.sodium.SodiumShadow.Culler {
		@Override
		public float depth(float x, float y, float z) { // view space looks down -z: the light is at large z
			return -(view.m02() * (x + 8) + view.m12() * (y + 8) + view.m22() * (z + 8));
		}

		@Override
		public boolean visible(float x, float y, float z, float sizeX, float sizeY, float sizeZ) {
			return TERRAIN.visible(x - SECTION_MARGIN, y - SECTION_MARGIN, z - SECTION_MARGIN, x + sizeX + SECTION_MARGIN, y + sizeY + SECTION_MARGIN,
					z + sizeZ + SECTION_MARGIN);
		}

		@Override
		public void receiver(float x, float y, float z) {
			if (receivers && CAMERA_FRUSTUM.testAab(x - 1, y - 1, z - 1, x + 17, y + 17, z + 17)) GRID.addReceiver(x, y, z, x + 16, y + 16, z + 16);
		}

		@Override
		public boolean keep(float x, float y, float z) {
			return !receivers || casts(mode, x, y, z);
		}
	}

	private static void drawSodium(RenderPass pass, boolean translucent, UniformSystem u, Matrix4fc shadowView, Vec3 cam,
			com.mojang.renderpearl.api.textures.GpuSampler sampler) {
		dev.emrion.shaders.mod.compat.sodium.SodiumShadow.draw(pass, translucent, u.shadowProjection(), shadowView, cam.x, cam.y, cam.z, sampler);
	}

	// ---- entities --------------------------------------------------------------------------------------------------

	/** Culls MC's entity test against the shadow entity culler (Iris: a constrained shadow frustum, entityShadowDistanceMul). */
	private static final class EntityFrustum extends Frustum {
		double x, y, z;

		EntityFrustum() {
			super(new Matrix4f(), new Matrix4f());
		}

		@Override
		public boolean isVisible(AABB bb) {
			return ENTITIES.visible((float) (bb.minX - x), (float) (bb.minY - y), (float) (bb.minZ - z), (float) (bb.maxX - x), (float) (bb.maxY - y),
					(float) (bb.maxZ - z));
		}
	}

	private static final EntityFrustum ENTITY_FRUSTUM = new EntityFrustum();

	private static void ensureDispatcher() {
		if (dispatcher != null) return;
		var mc = Minecraft.getInstance();
		submits = new SubmitNodeStorage();
		dispatcher = new FeatureRenderDispatcher(buffers = new RenderBuffers(0), mc.getModelManager(), mc.getAtlasManager(), mc.font, mc.gameRenderer.gameRenderState());
	}

	/**
	 * First person without a shadow pass (native packs): the main frame never draws the local player, so its body would be
	 * missing from the RT scene (mirrors, its own shadow). Prepares just the player into the running main RT capture; the
	 * frame is never drawn. Its vehicle and a sleeping player are in the main frame already (vanilla draws them).
	 */
	static void captureRtPlayer(LevelRenderer level, CameraRenderState camera) {
		var mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.player.isSpectator() || mc.player.isSleeping() || !mc.options.getCameraType().isFirstPerson()
				|| mc.getCameraEntity() != mc.player || !dev.emrion.shaders.mod.rt.RtEntities.capturingMainFrame()) return;
		ensureDispatcher();
		var entityDispatcher = level.entityRenderDispatcher();
		PoseStack pose = new PoseStack();
		Vec3 cam = camera.pos;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(!mc.level.tickRateManager().isEntityFrozen(mc.player));
		var state = entityDispatcher.extractEntity(mc.player, partial);
		entityDispatcher.submit(state, camera, state.x - cam.x, state.y - cam.y, state.z - cam.z, pose, submits);
		dispatcher.prepareFrame(submits).close();
	}

	/** Iris extractVisibleEntities + renderEntities into our own storage, prepared with the shadow model-view; null if none. */
	private static FeatureRenderDispatcher.PreparedFrame prepareEntities(FrameExecutor e, LevelRenderer level, CameraRenderState camera,
			Matrix4fc shadowView) {
		var mc = Minecraft.getInstance();
		var props = e.manifest();
		boolean all = prop(props, "shadowEntities"), player = prop(props, "shadowPlayer");
		boolean blockEntities = prop(props, "shadowBlockEntities"), lights = prop(props, "shadowLightBlockEntities");
		if (mc.level == null || mc.player == null || !all && !player && !blockEntities && !lights) return null;
		ensureDispatcher();
		Vec3 cam = camera.pos;
		ENTITY_FRUSTUM.x = cam.x;
		ENTITY_FRUSTUM.y = cam.y;
		ENTITY_FRUSTUM.z = cam.z;
		var entityDispatcher = level.entityRenderDispatcher();
		var tickRate = mc.level.tickRateManager();
		long fade = Util.toMillis(mc.options.chunkSectionFadeInTime().get());
		PoseStack pose = new PoseStack();
		for (Entity entity : all || player ? mc.level.entitiesForRendering() : java.util.List.<Entity>of()) {
			if (entity instanceof AbstractClientPlayer p && p.isSpectator()) continue;
			if (!all && entity != mc.player && entity != mc.player.getVehicle()) continue;
			float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(!tickRate.isEntityFrozen(entity));
			if (!entityDispatcher.shouldRender(entity, ENTITY_FRUSTUM, cam.x, cam.y, cam.z, partial) && !entity.hasIndirectPassenger(mc.player)) continue;
			BlockPos bp = entity.blockPosition();
			if (!mc.level.isOutsideBuildHeight(bp.getY()) && !level.isSectionCompiledAndVisible(bp, fade)) continue;
			if (entity.tickCount == 0) {
				entity.xOld = entity.getX();
				entity.yOld = entity.getY();
				entity.zOld = entity.getZ();
			}
			var state = entityDispatcher.extractEntity(entity, partial);
			entityDispatcher.submit(state, camera, state.x - cam.x, state.y - cam.y, state.z - cam.z, pose, submits);
		}
		if (blockEntities || lights) submitBlockEntities(level, camera, pose, !blockEntities);
		// RenderType.prepare captures the model-view stack: the shadow camera instead of the player's; ENTITY draws are written
		// as EMRION_ENTITY, as in the world and hand frames
		var modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix().set(shadowView);
		EntityIds.extending = EmrionVertexFormats.entitiesActive();
		dev.emrion.shaders.mod.rt.RtEntities.begin(true, cam, all, blockEntities); // every caster in shadow range, also behind the camera
		try {
			return dispatcher.prepareFrame(submits);
		} finally {
			EntityIds.extending = false;
			dev.emrion.shaders.mod.rt.RtEntities.end();
			modelView.popMatrix();
		}
	}
}
