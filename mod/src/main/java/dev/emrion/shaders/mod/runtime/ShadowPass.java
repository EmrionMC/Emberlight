package dev.emrion.shaders.mod.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.mixin.LevelRendererAccessor;
import dev.emrion.shaders.mod.pipeline.GbufferPipelines.Context;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.EntityIds;
import dev.emrion.shaders.mod.uniforms.UniformSystem;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Iris's ShadowRenderer.renderShadows (iris-spec §2.4) on MC's own draw code, run where Iris runs it (before the main pass):
 * opaque terrain (TERRAIN_SOLID) → entities (ENTITIES) → shadowtex0→shadowtex1 copy (plan) → translucent terrain
 * (TERRAIN_TRANSLUCENT), each a {@code geometry} command of the plan drawn with the SHADOW pipelines (no backface culling)
 * into shadowcolor0/1 + shadowtex0. Terrain reuses MC's section buffers: the visible-section list is swapped for the
 * shadow-culled one while MC prepares the draws with the shadow model-view. Entities are extracted and submitted again into
 * our own SubmitNodeStorage and FeatureRenderDispatcher (as Iris, and as the hand). Block entities are not drawn: the target
 * pack sets {@code shadowBlockEntities=false} (shortcut: the property is not honoured when true, add it when a pack needs it).
 */
final class ShadowPass {
	private ShadowPass() {}

	/** Iris config {@code maxShadowRenderDistance} (the user's value): the shadow distance in chunks when the pack's mul is negative. */
	private static final int MAX_SHADOW_RENDER_DISTANCE = 24;
	/** Iris AdvancedShadowCullingFrustum.SECTION_HALF_SIZE − 8: model extent + epsilon around a section. */
	private static final float SECTION_MARGIN = 1.125f;

	private static final ShadowCuller TERRAIN = new ShadowCuller(), ENTITIES = new ShadowCuller();
	private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> SECTIONS = new ObjectArrayList<>(10000), SAVED = new ObjectArrayList<>(10000);
	private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
	private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
	private static final Vector3f LIGHT = new Vector3f();
	private static SubmitNodeStorage submits;
	private static FeatureRenderDispatcher dispatcher;
	private static RenderBuffers buffers;
	/** Sections drawn last frame (debug overlay / log). */
	static int sectionCount, entityCount;

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

		ChunkSectionsToRender chunks = prop(e.manifest(), "shadowTerrain") || prop(e.manifest(), "shadowTranslucent")
				? prepareTerrain(level, cam, shadowView) : null;
		FeatureRenderDispatcher.PreparedFrame entities = prepareEntities(e, level, camera, shadowView);

		var atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true); // Iris: nearest, mipmapped
		try (RenderPass pass = e.beginPhase(opaque, Context.SHADOW, UniformSystem.Phase.SHADOW, GeometryHooks.TERRAIN_SOLID)) {
			RenderSystem.bindDefaultUniforms(pass);
			if (chunks != null && prop(e.manifest(), "shadowTerrain")) chunks.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, atlas, false);
		}
		e.endPhase();
		try {
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
		if (translucent != null && chunks != null && prop(e.manifest(), "shadowTranslucent")) {
			try (RenderPass pass = e.beginPhase(translucent, Context.SHADOW, UniformSystem.Phase.SHADOW, GeometryHooks.TERRAIN_TRANSLUCENT)) {
				RenderSystem.bindDefaultUniforms(pass);
				chunks.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, pass, sampler, atlas, false);
			}
			e.endPhase();
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
		boolean voxelizes = !e.dimension().programs().get("shadow").writesStorage().isEmpty(); // Iris: image stores = voxelization
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

	/** MC's draws for the shadow-visible sections, prepared with the shadow model-view (the main list is restored after). */
	private static ChunkSectionsToRender prepareTerrain(LevelRenderer level, Vec3 cam, Matrix4fc shadowView) {
		var area = level.viewArea();
		SECTIONS.clear();
		if (area != null) {
			var center = area.getCameraSectionPos();
			int r = area.getViewDistance();
			for (int sx = center.x() - r; sx <= center.x() + r; sx++) {
				for (int sz = center.z() - r; sz <= center.z() + r; sz++) {
					float minX = (float) ((sx << 4) - cam.x), minZ = (float) ((sz << 4) - cam.z);
					for (int sy = area.minSectionY(); sy < area.maxSectionY(); sy++) {
						float minY = (float) ((sy << 4) - cam.y);
						if (!TERRAIN.visible(minX - SECTION_MARGIN, minY - SECTION_MARGIN, minZ - SECTION_MARGIN, minX + 16 + SECTION_MARGIN,
								minY + 16 + SECTION_MARGIN, minZ + 16 + SECTION_MARGIN)) continue;
						var section = area.getRenderSectionAt(POS.set(sx << 4, sy << 4, sz << 4));
						// empty sections draw nothing, but MC's draw preparation still costs ~0.1 us and an allocation each (most of the volume is air)
						if (section != null && section.getSectionMesh().hasRenderableLayers()) SECTIONS.add(section);
					}
				}
			}
		}
		sectionCount = SECTIONS.size();
		var visible = level.visibleSections();
		SAVED.clear();
		SAVED.addAll(visible);
		visible.clear();
		visible.addAll(SECTIONS);
		try {
			return ((LevelRendererAccessor) level).emrion$usingMultiDrawIndirect()
					? level.prepareChunkRendersIndirect(shadowView, false) : level.prepareChunkRenders(shadowView, false);
		} finally {
			visible.clear();
			visible.addAll(SAVED);
			SAVED.clear();
		}
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

	/** Iris extractVisibleEntities + renderEntities into our own storage, prepared with the shadow model-view; null if none. */
	private static FeatureRenderDispatcher.PreparedFrame prepareEntities(FrameExecutor e, LevelRenderer level, CameraRenderState camera,
			Matrix4fc shadowView) {
		var mc = Minecraft.getInstance();
		var props = e.manifest();
		boolean all = prop(props, "shadowEntities"), player = prop(props, "shadowPlayer");
		entityCount = 0;
		if (mc.level == null || mc.player == null || !all && !player) return null;
		if (dispatcher == null) {
			submits = new SubmitNodeStorage();
			dispatcher = new FeatureRenderDispatcher(buffers = new RenderBuffers(0), mc.getModelManager(), mc.getAtlasManager(), mc.font, mc.gameRenderer.gameRenderState());
		}
		Vec3 cam = camera.pos;
		ENTITY_FRUSTUM.x = cam.x;
		ENTITY_FRUSTUM.y = cam.y;
		ENTITY_FRUSTUM.z = cam.z;
		var entityDispatcher = level.entityRenderDispatcher();
		var tickRate = mc.level.tickRateManager();
		long fade = Util.toMillis(mc.options.chunkSectionFadeInTime().get());
		PoseStack pose = new PoseStack();
		for (Entity entity : mc.level.entitiesForRendering()) {
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
			entityCount++;
		}
		// RenderType.prepare captures the model-view stack: the shadow camera instead of the player's; ENTITY draws are written
		// as EMRION_ENTITY, as in the world and hand frames
		var modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix().set(shadowView);
		EntityIds.extending = EmrionVertexFormats.entitiesActive();
		try {
			return dispatcher.prepareFrame(submits);
		} finally {
			EntityIds.extending = false;
			modelView.popMatrix();
		}
	}
}
