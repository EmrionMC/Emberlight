package dev.emrion.shaders.mod.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.mixin.GameRendererAccessor;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.pipeline.GbufferPipelines.Context;
import dev.emrion.shaders.mod.uniforms.UniformSystem;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * What the geometry mixins do while a pack renders a level: open our gbuffers passes in place of MC's, split MC's main pass
 * into opaque and translucent with the hand and the deferred passes in between (iris-spec §0 steps 4-7), and set Iris's
 * renderStage per draw group. Every method is a no-op (returns null/false) when no pack is rendering the level.
 */
public final class GeometryHooks {
	private GeometryHooks() {}

	/** Iris WorldRenderingPhase ordinals (iris-spec §1.4); the pack hard-codes some of them. */
	public static final int SKY = 1, SUNSET = 2, CUSTOM_SKY = 3, SUN = 4, MOON = 5, STARS = 6, VOID = 7, TERRAIN_SOLID = 8, ENTITIES = 11, BLOCK_ENTITIES = 12,
			HAND_SOLID = 16, TERRAIN_TRANSLUCENT = 17, PARTICLES = 19, RAIN_SNOW = 21, WORLD_BORDER = 22, HAND_TRANSLUCENT = 23;

	private static final Set<RenderPipeline> LOGGED = Collections.newSetFromMap(new IdentityHashMap<>());
	private static HandDraw hand;

	private record HandDraw(dev.emrion.shaders.format.Manifest.Command command, Context context, int stage) {}

	private static @Nullable FrameExecutor exec() {
		return PackManager.levelExecutor();
	}

	/** True while a pack renders levels (MC's own hand rendering after the level is then skipped: we drew it). */
	public static boolean packRendering() {
		return PackManager.running();
	}

	/** RenderSystem.getCompiledPipelineNullable: our pipeline while a phase pass is open, else null (vanilla). */
	public static @Nullable CompiledRenderPipeline swap(RenderPipeline p) {
		FrameExecutor e = exec();
		Context c = e == null ? null : e.context();
		if (c == null) return null;
		if (c == Context.WORLD && blockEntityDraw) c = Context.BLOCK_ENTITY;
		CompiledRenderPipeline ours = e.pipelines().gbuffers.get(c, p);
		if (ours == null && LOGGED.add(p)) EmrionShadersClient.LOG.warn("Pipeline {} draws in a gbuffers phase but has no pack pipeline; it renders vanilla", p.getLocation());
		return ours;
	}

	/** Opens the pass of {@code phaseName} (after running the raw commands before it); null if no pack phase applies. */
	public static @Nullable RenderPass openPhase(String phaseName, Context ctx, UniformSystem.Phase phase, int stage) {
		FrameExecutor e = exec();
		if (e == null) return null;
		try {
			var cmd = e.advanceTo(phaseName);
			return cmd == null ? null : e.beginPhase(cmd, ctx, phase, stage);
		} catch (Throwable t) { // MC opens its own pass instead
			PackManager.frameFailed(t);
			return null;
		}
	}

	public static void closePhase() {
		FrameExecutor e = exec();
		if (e != null) e.endPhase();
	}

	public static void stage(int stage) {
		FrameExecutor e = exec();
		if (e != null) setStage(e, stage);
	}

	/** Every hook inside MC's draw code goes through here: a failure disables the pack instead of crashing the frame. */
	private static void setStage(FrameExecutor e, int stage) {
		try {
			e.setStage(stage);
		} catch (Throwable t) {
			PackManager.frameFailed(t);
		}
	}

	/** Runs {@code body} with renderStage {@code stage}, then restores the previous one. */
	public static void withStage(int stage, Runnable body) {
		FrameExecutor e = exec();
		if (e == null || e.context() == null) {
			body.run();
			return;
		}
		int before = e.stage();
		setStage(e, stage);
		body.run();
		setStage(e, before);
	}

	public static boolean inPhase() {
		FrameExecutor e = exec();
		return e != null && e.context() != null;
	}

	/** shaders.properties sun/moon/stars/clouds (FORMAT §5): false if the pack turns this vanilla sky part off. */
	public static boolean draws(String property) {
		FrameExecutor e = exec();
		if (e == null) return true;
		Object v = e.manifest().properties() == null ? null : e.manifest().properties().get(property);
		return !(Boolean.FALSE.equals(v) || "off".equals(v));
	}

	public static boolean inShadowPass() {
		return ShadowPass.rendering;
	}

	public static float sunPathRotation() {
		FrameExecutor e = exec();
		return e == null || e.dimension().shadow() == null ? 0 : e.dimension().shadow().sunPathRotation();
	}

	/**
	 * Replaces the rest of MC's main pass after the opaque draws: closes the opaque pass, draws the solid hand, runs the plan
	 * up to the translucent phase (depthtex1 copy, deferred), lets MC draw translucents into a new pass, then the
	 * translucent hand. Returns false (MC continues normally) when no pack phase is open.
	 */
	public static boolean splitMainPass(RenderPass opaque, java.util.function.Consumer<RenderPass> translucents) {
		FrameExecutor e = exec();
		if (e == null || e.context() == null) return false;
		opaque.close();
		e.endPhase();
		guarded(() -> renderHand("hand_solid", Context.HAND_SOLID, HAND_SOLID));
		RenderPass pass = openPhase("translucent", Context.WORLD, UniformSystem.Phase.WORLD, ENTITIES);
		if (pass == null) { // no translucent phase in the plan: MC's own pass, vanilla pipelines
			var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Main (translucent)", main.getColorTextureView(),
					java.util.Optional.empty(), main.getDepthTextureView(), java.util.OptionalDouble.empty());
		}
		try (RenderPass p = pass) {
			RenderSystem.bindDefaultUniforms(p);
			translucents.accept(p);
		}
		e.endPhase();
		guarded(() -> renderHand("hand_translucent", Context.HAND_TRANSLUCENT, HAND_TRANSLUCENT));
		return true;
	}

	private static void guarded(Runnable hook) {
		try {
			hook.run();
		} catch (Throwable t) {
			PackManager.frameFailed(t);
		}
	}

	/** Renders MC's first-person hand into the gbuffers (Iris HandRenderer): our pass, hand projection, no depth clear. */
	private static void renderHand(String phaseName, Context ctx, int stage) {
		FrameExecutor e = exec();
		if (e == null) return; // failed earlier in this frame
		var cmd = e.advanceTo(phaseName);
		if (cmd == null) return;
		var mc = Minecraft.getInstance();
		var level = mc.gameRenderer.gameRenderState().levelRenderState;
		hand = new HandDraw(cmd, ctx, stage);
		// inside LevelRenderer.render the model-view stack still holds the camera rotation; renderItemInHand adds its own
		var modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix().identity();
		try {
			((GameRendererAccessor) mc.gameRenderer).emrion$renderItemInHand(level.cameraRenderState, level.playerRenderState,
					mc.gameRenderer.mainRenderTarget().getDepthTextureView());
		} finally {
			modelView.popMatrix();
			hand = null;
			e.endPhase();
		}
	}

	private static net.minecraft.client.renderer.feature.@Nullable FeatureRenderDispatcher handDispatcher;
	private static net.minecraft.client.renderer.@Nullable RenderBuffers handBuffers;

	/** GameRenderer.render TAIL: our dispatchers' vertex buffer pools recycle per frame, as MC's own RenderBuffers. */
	public static void endFrame() {
		if (handBuffers != null) handBuffers.endFrame();
		ShadowPass.endFrame();
	}

	/**
	 * GameRenderer.renderItemInHand's feature dispatcher while {@link #renderHand} runs: our own (with its own staged vertex
	 * buffer, as Iris's HandRenderer), because MC's single dispatcher is still in use by the level frame. Else null.
	 */
	public static net.minecraft.client.renderer.feature.@Nullable FeatureRenderDispatcher handDispatcher() {
		if (hand == null) return null;
		if (handDispatcher == null) {
			var mc = Minecraft.getInstance();
			handDispatcher = new net.minecraft.client.renderer.feature.FeatureRenderDispatcher(handBuffers = new net.minecraft.client.renderer.RenderBuffers(0),
					mc.getModelManager(), mc.getAtlasManager(), mc.font, mc.gameRenderer.gameRenderState());
		}
		return handDispatcher;
	}

	/** GameRenderer.renderItemInHand's render pass: ours while {@link #renderHand} runs, else null (vanilla). */
	public static @Nullable RenderPass handPass() {
		HandDraw h = hand;
		FrameExecutor e = exec();
		if (h == null || e == null) return null;
		hand = null;
		try {
			return e.beginPhase(h.command(), h.context(), UniformSystem.Phase.HAND, h.stage());
		} catch (Throwable t) {
			PackManager.frameFailed(t);
			return null;
		}
	}

	// ---- block entities (Iris BLOCK_ENTITIES phase) ----

	/** Name prefix of the block-entity copies of entity render types. */
	private static final String BLOCK_ENTITY_PREFIX = "emrion_block_entity:";
	private static final java.util.Map<net.minecraft.client.renderer.rendertype.RenderType, net.minecraft.client.renderer.rendertype.RenderType> BLOCK_ENTITY_TYPES =
			new IdentityHashMap<>();
	/** True while BlockEntityRenderDispatcher.submit runs with a pack active. */
	public static boolean submittingBlockEntity;
	private static boolean blockEntityDraw;

	/**
	 * While a block entity submits: its entity-program render types become a distinct copy (as Iris's OuterWrappedRenderType),
	 * so MC batches them apart from entities and {@link #blockEntityDraw} can switch the program when they draw.
	 */
	public static net.minecraft.client.renderer.rendertype.RenderType blockEntityType(net.minecraft.client.renderer.rendertype.RenderType t) {
		return submittingBlockEntity ? blockEntityCopy(t) : t;
	}

	/** The block-entity copy of an entity-program render type (the type itself for others and outlines). */
	public static net.minecraft.client.renderer.rendertype.RenderType blockEntityCopy(net.minecraft.client.renderer.rendertype.RenderType t) {
		if (t.isOutline()) return t;
		var e = dev.emrion.shaders.mod.pipeline.GbufferMapping.get(t.pipeline());
		if (e == null || !e.program().startsWith("gbuffers_entities")) return t;
		return BLOCK_ENTITY_TYPES.computeIfAbsent(t, x -> net.minecraft.client.renderer.rendertype.RenderType.create(
				BLOCK_ENTITY_PREFIX + ((dev.emrion.shaders.mod.mixin.RenderTypeAccessor) x).emrion$name(), ((dev.emrion.shaders.mod.mixin.RenderTypeAccessor) x).emrion$state()));
	}

	/** PreparedRenderType.draw: a block-entity render type draws with the BLOCK_ENTITY programs and renderStage BLOCK_ENTITIES. */
	public static void drawRenderType(String name, Runnable draw) {
		if (!name.startsWith(BLOCK_ENTITY_PREFIX) || !inPhase()) {
			draw.run();
			return;
		}
		blockEntityDraw = true;
		try {
			withStage(BLOCK_ENTITIES, draw);
		} finally {
			blockEntityDraw = false;
		}
	}

	/** Before MC adds its main pass (Iris renderShadows): the shadow pass, if the plan has one. */
	public static void renderShadows(net.minecraft.client.renderer.LevelRenderer level) {
		FrameExecutor e = exec();
		if (e != null) guarded(() -> e.renderShadows(level));
	}

	/** Iris turns vanilla entity (blob) shadows off when the pack renders a shadow map. */
	public static boolean vanillaEntityShadows() {
		FrameExecutor e = exec();
		return e == null || !e.hasShadowPass();
	}

	/** After the level's frame graph ran: composites and final. */
	public static void levelEnd() {
		FrameExecutor e = exec();
		if (e != null) e.finish();
	}
}
