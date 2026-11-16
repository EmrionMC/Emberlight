package dev.emrion.shaders.mod.uniforms;

import dev.emrion.shaders.converter.expr.UniformCatalog;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.upscale.Upscaling;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.EndFlashState;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/** Every Iris built-in uniform as a provider over per-frame captured state; {@link #capture} advances it. Render thread only. */
final class Builtins {
	/** Writes one value in std140 word form into out[at..]. */
	interface Provider { void write(int[] out, int at); }

	interface F { float get(); }
	interface I { int get(); }
	interface B { boolean get(); }

	record Def(String type, Provider provider) {}

	final Map<String, Def> defs = new HashMap<>();

	// ---- configuration ----
	private final IrisUniformSystem.Directives dir;
	private final float sunPathRotation, shadowDistance, shadowIntervalSize, shadowNear, shadowFar;
	private final Float shadowFov;
	private final Object2IntOpenHashMap<Identifier> itemIds = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<Identifier> entityIds = new Object2IntOpenHashMap<>();
	/** BlockState → block.properties id, set by the pack runtime (currentSelectedBlockId); 0 until then. */
	ToIntFunction<BlockState> blockIds = s -> 0;

	// ---- per-frame state ----
	final Minecraft mc = Minecraft.getInstance();
	ClientLevel level;
	LocalPlayer player;
	Entity camEntity;
	Camera camera;
	CameraRenderState cs;
	float pt;
	long gameTime = Long.MIN_VALUE;
	boolean tickChanged;
	int renderStage;

	final IrisTimer timer = new IrisTimer();
	final CameraTracker tracker = new CameraTracker();
	final Matrix4f modelView = new Matrix4f(), modelViewInv = new Matrix4f(), prevModelView = new Matrix4f();
	final Matrix4f projection = new Matrix4f(), projectionInv = new Matrix4f(), prevProjection = new Matrix4f();
	final Matrix3f normalMat = new Matrix3f();
	final Matrix4f handProjection = new Matrix4f(), handProjectionInv = new Matrix4f();
	final Matrix4f shadowModelView = new Matrix4f(), shadowModelViewInv = new Matrix4f();
	/** shadowProjection uniform: always Iris's ortho (MatrixUniforms ignores shadowMapFov). */
	final Matrix4f shadowOrtho = new Matrix4f(), shadowOrthoInv = new Matrix4f();
	/** Projection the shadow pass renders with: perspective when shadowMapFov is set (ShadowRenderer), else the ortho. */
	final Matrix4f shadowRender = new Matrix4f(), shadowRenderInv = new Matrix4f();
	float sunRaw, moonRaw, sunC, moonC, shadowAngle;
	boolean isDay;
	final Vector3f sunPos = new Vector3f(), moonPos = new Vector3f(), upPos = new Vector3f(), endFlashPos = new Vector3f();
	boolean endFlashLight;
	final Vector4f fogColor = new Vector4f();
	float fogStart, fogEnd, fogDensity = -1;
	int eyeBlock, eyeSky;
	private final SmoothedFloat eyeSmoothX, eyeSmoothY, wetness;
	float endFlash, prevEndFlash;
	int heldId = -1, heldId2 = -1, heldLight, heldLight2;
	private final BlockPos.MutableBlockPos eyePos = new BlockPos.MutableBlockPos();
	private LocalDateTime now = LocalDateTime.now();

	private final Reference2IntOpenHashMap<Biome> categoryCache = new Reference2IntOpenHashMap<>();
	private final Reference2FloatOpenHashMap<Biome> downfallCache = new Reference2FloatOpenHashMap<>();
	static final Object2IntOpenHashMap<ResourceKey<Biome>> BIOME_IDS = biomeIds();

	Builtins(Manifest.Shadow shadow, Manifest.IdMaps idMaps, IrisUniformSystem.Directives dir) {
		this.dir = dir;
		sunPathRotation = shadow == null ? 0 : shadow.sunPathRotation();
		shadowDistance = shadow == null ? 160 : shadow.distance();
		shadowIntervalSize = shadow == null ? 2 : shadow.intervalSize();
		shadowNear = shadow == null ? -100.05f : shadow.nearPlane();
		shadowFar = shadow == null ? 156 : shadow.farPlane();
		shadowFov = shadow == null ? null : shadow.fov();
		eyeSmoothX = new SmoothedFloat(dir.eyeBrightnessHalfLife(), dir.eyeBrightnessHalfLife());
		eyeSmoothY = new SmoothedFloat(dir.eyeBrightnessHalfLife(), dir.eyeBrightnessHalfLife());
		wetness = new SmoothedFloat(dir.wetnessHalfLife(), dir.drynessHalfLife());
		itemIds.defaultReturnValue(-1);
		entityIds.defaultReturnValue(-1);
		categoryCache.defaultReturnValue(-1);
		downfallCache.defaultReturnValue(Float.NaN);
		if (idMaps != null) {
			fill(itemIds, idMaps.items());
			fill(entityIds, idMaps.entities());
		}
		register();
	}

	// shortcut: tag and block-state matchers are ignored for held items and vehicles, resolve them when a pack needs it
	private static void fill(Object2IntOpenHashMap<Identifier> map, List<Manifest.IdEntry> entries) {
		if (entries == null) return;
		for (var e : entries)
			for (String m : e.match()) {
				if (m.startsWith("#")) continue;
				Identifier id = Identifier.tryParse(m);
				if (id != null && !map.containsKey(id)) map.put(id, e.id());
			}
	}

	/** Iris MixinBiomes: BIOME_* ids are the order of Biomes.register calls, i.e. Biomes' static field order. */
	private static Object2IntOpenHashMap<ResourceKey<Biome>> biomeIds() {
		var map = new Object2IntOpenHashMap<ResourceKey<Biome>>();
		int id = 0;
		for (Field f : Biomes.class.getDeclaredFields()) {
			if (!Modifier.isStatic(f.getModifiers()) || f.getType() != ResourceKey.class) continue;
			try {
				@SuppressWarnings("unchecked") var key = (ResourceKey<Biome>) f.get(null);
				map.put(key, id++);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
		return map;
	}

	/** Id Iris gives BIOME_&lt;NAME&gt;, or -1. */
	static int biomeConstant(String constantName) {
		for (var e : BIOME_IDS.object2IntEntrySet())
			if (("BIOME_" + e.getKey().identifier().getPath().toUpperCase(Locale.ROOT)).equals(constantName)) return e.getIntValue();
		return -1;
	}

	// ---- per frame -------------------------------------------------------------------------------------------------

	/** Iris beginLevelRendering + FrameUpdateNotifier: advance time, camera, matrices, smoothing. False if no world. */
	boolean capture(float partialTick) {
		timer.beginFrame(System.nanoTime());
		level = mc.level;
		player = mc.player;
		camEntity = mc.getCameraEntity();
		if (level == null || player == null || camEntity == null) return false;
		pt = partialTick;
		GameRenderer gr = mc.gameRenderer;
		camera = gr.mainCamera();
		cs = gr.gameRenderState().levelRenderState.cameraRenderState;
		long t = level.getGameTime();
		tickChanged = t != gameTime;
		gameTime = t;
		if (tickChanged) now = LocalDateTime.now();

		Vec3 cam = camera.position();
		tracker.update(cam.x, cam.y, cam.z);

		prevModelView.set(modelView);
		prevProjection.set(projection);
		modelView.set(cs.viewRotationMatrix);
		IrisMath.reverseZToGl(CapturedProjection.valid ? CapturedProjection.LEVEL : cs.projectionMatrix, projection);
		modelView.invert(modelViewInv);
		projection.invert(projectionInv);
		modelViewInv.transpose3x3(normalMat);
		IrisMath.handProjection(cs.hudFov, gr.gameRenderState().windowRenderState.width, gr.gameRenderState().windowRenderState.height,
				cs.depthFar, CapturedProjection.BOB, handProjection).invert(handProjectionInv);
		if (Upscaling.jitter(projection, handProjection)) { // sub-pixel jitter while an upscaler runs
			projection.invert(projectionInv);
			handProjection.invert(handProjectionInv);
		}

		// Celestial (CelestialUniforms / ShadowRenderer.getShadowAngle)
		sunRaw = camera.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, pt);
		moonRaw = camera.attributeProbe().getValue(EnvironmentAttributes.MOON_ANGLE, pt);
		sunC = IrisMath.celestialAngle(sunRaw);
		moonC = IrisMath.celestialAngle(moonRaw);
		isDay = sunC < 180;
		shadowAngle = (isDay ? sunC : moonC) / 360.0f;
		IrisMath.celestialPosition(modelView, sunPathRotation, sunRaw, sunPos);
		IrisMath.celestialPosition(modelView, sunPathRotation, moonRaw, moonPos);
		IrisMath.upPosition(modelView, upPos);
		EndFlashState flash = level.endFlashState();
		boolean end = level.dimension() == Level.END;
		if (end && flash != null) IrisMath.endFlashPosition(modelView, flash.getXAngle(), flash.getYAngle(), endFlashPos);
		else endFlashPos.zero();
		endFlashLight = end && dir.endFlashShadows() && flash != null;

		// Shadow camera (MatrixUniforms / ShadowRenderer): relative to the unshifted camera, i.e. player space.
		Vector3d c = tracker.currentUnshifted;
		if (endFlashLight) IrisMath.endFlashShadowModelView(flash.getXAngle(), flash.getYAngle(), shadowIntervalSize, c.x, c.y, c.z, shadowModelView);
		else IrisMath.shadowModelView(shadowAngle, sunPathRotation, shadowIntervalSize, c.x, c.y, c.z, shadowModelView);
		shadowModelView.invert(shadowModelViewInv);
		int rd = mc.options.getEffectiveRenderDistance() * 16; // DH absent: DHCompat.getRenderDistance() = effective render distance
		float near = shadowNear == -1.0f ? -rd : shadowNear, far = shadowFar == -1.0f ? rd : shadowFar;
		IrisMath.shadowOrtho(shadowDistance, near, far, shadowOrtho).invert(shadowOrthoInv);
		if (shadowFov != null) IrisMath.shadowPerspective(shadowFov, shadowRender);
		else shadowRender.set(shadowOrtho);
		shadowRender.invert(shadowRenderInv);

		// Fog (MixinFogRenderer): captured colour, legacy water density, environmental start/end
		fogColor.set(cs.fogData.color);
		fogStart = cs.fogData.environmentalStart;
		fogEnd = cs.fogData.environmentalEnd;
		fogDensity = -1.0f;
		if (camera.getFluidInCamera() == FogType.WATER) {
			fogDensity = 0.05f;
			if (camera.entity() instanceof LocalPlayer lp) fogDensity -= lp.getWaterVision() * lp.getWaterVision() * 0.03f;
		}

		// Eye brightness (+ smoothing with the frame time)
		eyePos.set(Mth.floor(camEntity.getX()), Mth.floor(camEntity.getEyeY()), Mth.floor(camEntity.getZ()));
		eyeBlock = level.getBrightness(LightLayer.BLOCK, eyePos) * 16;
		eyeSky = level.getBrightness(LightLayer.SKY, eyePos) * 16;
		eyeSmoothX.update(eyeBlock, timer.frameTime());
		eyeSmoothY.update(eyeSky, timer.frameTime());
		wetness.update(rainStrength(), timer.frameTime());

		prevEndFlash = endFlash;
		endFlash = flash == null ? 0 : flash.getIntensity(pt);

		captureHeldItems();
		return true;
	}

	private void captureHeldItems() {
		ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND), off = player.getItemInHand(InteractionHand.OFF_HAND);
		heldId = itemId(main);
		heldId2 = itemId(off);
		heldLight = lightEmission(main);
		heldLight2 = lightEmission(off);
		if (dir.oldHandLight()) heldLight = Math.max(heldLight, heldLight2); // Iris: main hand takes the brighter of both
	}

	private int itemId(ItemStack stack) {
		Identifier id = stack.get(DataComponents.ITEM_MODEL);
		if (id == null) id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return itemIds.getInt(id);
	}

	private static int lightEmission(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem item)) return 0;
		BlockState state = item.getBlock().defaultBlockState();
		BlockItemStateProperties props = stack.get(DataComponents.BLOCK_STATE);
		if (props != null) state = props.apply(state);
		return state.getLightEmission();
	}

	// ---- value helpers ---------------------------------------------------------------------------------------------

	float rainStrength() { return Mth.clamp(level.getRainLevel(pt), 0.0f, 1.0f); }

	private Holder<Biome> biomeHolder() { return player.level().getBiome(player.blockPosition()); }

	private boolean survival() { return mc.gameMode != null && mc.gameMode.getPlayerMode().isSurvival(); }

	private int isEyeInWater() {
		FogType t = camera.getFluidInCamera();
		if (t == FogType.WATER) return 1;
		if (t == FogType.LAVA && !player.isSpectator()) return 2;
		return t == FogType.POWDER_SNOW ? 3 : 0;
	}

	private float blindness() {
		if (camEntity instanceof LivingEntity le) {
			MobEffectInstance e = le.getEffect(MobEffects.BLINDNESS);
			if (e != null) return e.isInfiniteDuration() ? 1.0f : Mth.clamp(e.getDuration() / 20.0f, 0.0f, 1.0f);
		}
		return 0.0f;
	}

	private float darknessFactor() {
		if (camEntity instanceof LivingEntity le) {
			MobEffectInstance e = le.getEffect(MobEffects.DARKNESS);
			if (e != null) return e.getBlendFactor(le, pt);
		}
		return 0.0f;
	}

	private float nightVision() {
		if (camEntity instanceof LivingEntity le && le.hasEffect(MobEffects.NIGHT_VISION)) {
			float s = GameRenderer.nightVisionScale(le, pt);
			if (s > 0) return Mth.clamp(s, 0.0f, 1.0f);
		}
		if (player.hasEffect(MobEffects.CONDUIT_POWER)) {
			float w = player.getWaterVision();
			if (w > 0.0f) return Mth.clamp(w, 0.0f, 1.0f);
		}
		return 0.0f;
	}

	private int worldTime() {
		long time = level.getDefaultClockTime();
		boolean netherOrEnd = level.dimension() == Level.NETHER || level.dimension() == Level.END;
		return (int) (!netherOrEnd && level.dimensionType().hasFixedTime() ? 0 : time % 24000L);
	}

	/** Iris BiomeUniforms.getBiomeCategory: first matching tag wins; cached per biome like Iris's ExtendedBiome. */
	private int biomeCategory() {
		Holder<Biome> h = biomeHolder();
		int c = categoryCache.getInt(h.value());
		if (c >= 0) return c;
		// Ordinals of Iris BiomeCategories: NONE TAIGA EXTREME_HILLS JUNGLE MESA PLAINS SAVANNA ICY THE_END BEACH FOREST
		// OCEAN DESERT RIVER SWAMP MUSHROOM NETHER MOUNTAIN UNDERGROUND
		if (h.is(BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS)) c = 0;
		else if (h.is(BiomeTags.HAS_VILLAGE_SNOWY)) c = 7;
		else if (h.is(BiomeTags.IS_HILL)) c = 2;
		else if (h.is(BiomeTags.IS_TAIGA)) c = 1;
		else if (h.is(BiomeTags.IS_OCEAN)) c = 11;
		else if (h.is(BiomeTags.IS_JUNGLE)) c = 3;
		else if (h.is(BiomeTags.IS_FOREST)) c = 10;
		else if (h.is(BiomeTags.IS_BADLANDS)) c = 4;
		else if (h.is(BiomeTags.IS_NETHER)) c = 16;
		else if (h.is(BiomeTags.IS_END)) c = 8;
		else if (h.is(BiomeTags.IS_BEACH)) c = 9;
		else if (h.is(BiomeTags.HAS_DESERT_PYRAMID)) c = 12;
		else if (h.is(BiomeTags.IS_RIVER)) c = 13;
		else if (h.is(BiomeTags.ALLOWS_SURFACE_SLIME_SPAWNS)) c = 14;
		else if (h.is(BiomeTags.WITHOUT_ZOMBIE_SIEGES)) c = 15;
		else if (h.is(BiomeTags.IS_MOUNTAIN)) c = 17;
		else c = 5;
		categoryCache.put(h.value(), c);
		return c;
	}

	/** Biome.climateSettings.downfall is private (private record); read it reflectively once per biome. */
	private float rainfall() {
		Biome b = biomeHolder().value();
		float v = downfallCache.getFloat(b);
		if (!Float.isNaN(v)) return v;
		try {
			for (Field f : Biome.class.getDeclaredFields()) {
				if (!f.getType().isRecord()) continue;
				RecordComponent[] rc = f.getType().getRecordComponents();
				if (rc.length != 4) continue; // (hasPrecipitation, temperature, temperatureModifier, downfall)
				f.setAccessible(true);
				Method m = rc[3].getAccessor();
				m.setAccessible(true);
				v = (float) m.invoke(f.get(b));
				break;
			}
		} catch (ReflectiveOperationException e) {
			v = 0;
		}
		if (Float.isNaN(v)) v = 0;
		downfallCache.put(b, v);
		return v;
	}

	private boolean blockOutline() {
		// shortcut: no adventure-mode canBreak/canPlace or spectator branches of shouldRenderBlockOutline, add if a pack needs them
		return camEntity instanceof Player p && !mc.gui.hud.isHidden() && p.getAbilities().mayBuild;
	}

	private BlockPos selectedBlock() {
		HitResult hit = mc.hitResult;
		if (blockOutline() && hit != null && hit.getType() == HitResult.Type.BLOCK) return ((BlockHitResult) hit).getBlockPos();
		return null;
	}

	private int selectedBlockId() {
		BlockPos p = selectedBlock();
		if (p == null) return 0;
		BlockState s = level.getBlockState(p);
		return !s.isAir() && level.getWorldBorder().isWithinBounds(p) ? blockIds.applyAsInt(s) : 0;
	}

	// ---- registration ----------------------------------------------------------------------------------------------

	private void f(String n, F f) { put(n, "float", (o, i) -> o[i] = Float.floatToRawIntBits(f.get())); }
	private void i(String n, I f) { put(n, "int", (o, i) -> o[i] = f.get()); }
	private void b(String n, B f) { put(n, "bool", (o, i) -> o[i] = f.get() ? 1 : 0); }
	private void put(String n, String type, Provider p) { defs.put(n, new Def(type, p)); }

	static void put3(int[] o, int i, float x, float y, float z) {
		o[i] = Float.floatToRawIntBits(x);
		o[i + 1] = Float.floatToRawIntBits(y);
		o[i + 2] = Float.floatToRawIntBits(z);
	}

	static void put3(int[] o, int i, Vector3d v) { put3(o, i, (float) v.x, (float) v.y, (float) v.z); }

	static void put3(int[] o, int i, Vec3 v) { put3(o, i, (float) v.x, (float) v.y, (float) v.z); }

	static void put3(int[] o, int i, Vector3fc v) { put3(o, i, v.x(), v.y(), v.z()); }

	static void put4(int[] o, int i, float x, float y, float z, float w) {
		put3(o, i, x, y, z);
		o[i + 3] = Float.floatToRawIntBits(w);
	}

	static void mat4(int[] o, int i, Matrix4fc m) {
		for (int c = 0; c < 4; c++)
			for (int r = 0; r < 4; r++) o[i + c * 4 + r] = Float.floatToRawIntBits(m.getRowColumn(r, c));
	}

	static void mat3(int[] o, int i, Matrix3f m) {
		for (int c = 0; c < 3; c++) {
			for (int r = 0; r < 3; r++) o[i + c * 4 + r] = Float.floatToRawIntBits(m.getRowColumn(r, c));
			o[i + c * 4 + 3] = 0;
		}
	}

	private void vec3(String n, Provider p) { put(n, "vec3", p); }

	private void register() {
		// Camera / viewport / time (CameraUniforms, ViewportUniforms, WorldTimeUniforms, SystemTimeUniforms)
		f("near", () -> 0.05f);
		f("far", () -> mc.options.getEffectiveRenderDistance() * 16);
		vec3("cameraPosition", (o, i) -> put3(o, i, tracker.current));
		f("eyeAltitude", () -> (float) tracker.current.y);
		vec3("previousCameraPosition", (o, i) -> put3(o, i, tracker.previous));
		put("cameraPositionInt", "ivec3", (o, i) -> floor3(o, i, tracker.currentUnshifted));
		vec3("cameraPositionFract", (o, i) -> fract3(o, i, tracker.currentUnshifted));
		put("previousCameraPositionInt", "ivec3", (o, i) -> floor3(o, i, tracker.previousUnshifted));
		vec3("previousCameraPositionFract", (o, i) -> fract3(o, i, tracker.previousUnshifted));
		// the render size while an upscaler runs
		f("viewHeight", () -> Upscaling.viewHeight(mc.gameRenderer.mainRenderTarget().height));
		f("viewWidth", () -> Upscaling.viewWidth(mc.gameRenderer.mainRenderTarget().width));
		f("aspectRatio", () -> (float) mc.gameRenderer.mainRenderTarget().width / (float) mc.gameRenderer.mainRenderTarget().height);
		i("worldTime", this::worldTime);
		i("worldDay", () -> (int) (level.getDefaultClockTime() / 24000L));
		i("moonPhase", () -> camera.attributeProbe().getValue(EnvironmentAttributes.MOON_PHASE, pt).index());
		i("frameCounter", timer::frameCounter);
		f("frameTime", timer::frameTime);
		f("frameTimeCounter", timer::frameTimeCounter);
		put("currentDate", "ivec3", (o, i) -> { o[i] = now.getYear(); o[i + 1] = now.getMonthValue(); o[i + 2] = now.getDayOfMonth(); });
		put("currentTime", "ivec3", (o, i) -> { o[i] = now.getHour(); o[i + 1] = now.getMinute(); o[i + 2] = now.getSecond(); });
		put("currentYearTime", "ivec2", (o, i) -> {
			int elapsed = (now.getDayOfYear() - 1) * 86400 + now.getHour() * 3600 + now.getMinute() * 60 + now.getSecond();
			o[i] = elapsed;
			o[i + 1] = now.toLocalDate().lengthOfYear() * 86400 - elapsed;
		});

		// Biome (BiomeUniforms)
		i("biome", () -> BIOME_IDS.getInt(biomeHolder().unwrapKey().orElse(null)));
		i("biome_category", this::biomeCategory);
		i("biome_precipitation", () -> switch (biomeHolder().value().getPrecipitationAt(player.blockPosition(), level.getSeaLevel())) {
			case NONE -> 0;
			case RAIN -> 1;
			case SNOW -> 2;
		});
		f("rainfall", this::rainfall);
		f("temperature", () -> biomeHolder().value().getBaseTemperature());

		// Celestial (CelestialUniforms)
		f("sunAngle", () -> sunC / 360.0f);
		vec3("sunPosition", (o, i) -> put3(o, i, sunPos));
		vec3("moonPosition", (o, i) -> put3(o, i, moonPos));
		f("shadowAngle", () -> shadowAngle);
		vec3("shadowLightPosition", (o, i) -> put3(o, i, endFlashLight ? endFlashPos : isDay ? sunPos : moonPos));
		vec3("endFlashPosition", (o, i) -> put3(o, i, endFlashPos));
		vec3("upPosition", (o, i) -> put3(o, i, upPos));

		// Iris-exclusive (IrisExclusiveUniforms + WorldInfoUniforms)
		i("currentColorSpace", () -> 0); // ColorSpace.SRGB: we have no colour-space option
		f("chunkFadeTimeInv", () -> (float) (1.0 / (mc.options.chunkSectionFadeInTime().get() * 1000.0)));
		i("textureFilteringMode", () -> switch (mc.options.textureFiltering().get()) {
			case NONE -> 0;
			case RGSS -> 1;
			case ANISOTROPIC -> 2;
		});
		i("bedrockLevel", () -> level.dimensionType().minY());
		f("cloudHeight", () -> camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_HEIGHT, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)));
		i("heightLimit", () -> level.dimensionType().height());
		i("logicalHeightLimit", () -> level.dimensionType().logicalHeight());
		b("hasCeiling", () -> level.dimensionType().hasCeiling());
		b("hasSkylight", () -> level.dimensionType().hasSkyLight());
		f("ambientLight", () -> level.dimensionType().ambientLight());
		b("feetInWater", () -> player.isInShallowWater());
		b("inSwimmingAnimation", () -> player.isSwimming());
		b("isRiding", () -> player.isPassenger());
		b("isElytraFlying", () -> player.isFallFlying());
		b("vehicleInWater", () -> player.getVehicle() != null && player.getVehicle().isInShallowWater());
		i("vehicleId", () -> player.getVehicle() == null ? 0 : entityIds.getInt(BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType())));
		vec3("vehicleLookVector", (o, i) -> {
			if (player.getVehicle() == null) put3(o, i, 0, 0, 0);
			else put3(o, i, player.getVehicle().getForward());
		});
		vec3("relativeVehiclePosition", (o, i) -> {
			if (player.getVehicle() == null) { put3(o, i, 0, 0, 0); return; }
			Vec3 v = player.getVehicle().getPosition(pt);
			Vector3d c = tracker.currentUnshifted;
			put3(o, i, (float) (c.x - v.x), (float) (c.y - v.y), (float) (c.z - v.z));
		});
		f("thunderStrength", () -> Mth.clamp(level.getThunderLevel(pt), 0.0f, 1.0f));
		f("currentPlayerHealth", () -> survival() ? player.getHealth() / player.getMaxHealth() : -1);
		b("heavyFog", () -> mc.gui.hud.getBossOverlay().shouldCreateWorldFog());
		f("maxPlayerHealth", () -> survival() ? player.getMaxHealth() : -1);
		f("currentPlayerHunger", () -> survival() ? player.getFoodData().getFoodLevel() / 20f : -1);
		f("maxPlayerHunger", () -> 20);
		f("endFlashIntensity", () -> endFlash);
		f("previousEndFlashIntensity", () -> prevEndFlash);
		f("currentPlayerArmor", () -> survival() ? player.getArmorValue() / 50.0f : -1);
		f("maxPlayerArmor", () -> 50);
		f("currentPlayerAir", () -> survival() ? (float) player.getAirSupply() / (float) player.getMaxAirSupply() : -1);
		f("maxPlayerAir", () -> survival() ? player.getMaxAirSupply() : -1);
		b("firstPersonCamera", () -> switch (mc.options.getCameraType()) {
			case THIRD_PERSON_BACK, THIRD_PERSON_FRONT -> false;
			default -> true;
		});
		b("isSpectator", () -> mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR);
		i("currentSelectedBlockId", this::selectedBlockId);
		i("seaLevel", () -> level.getSeaLevel());
		vec3("currentSelectedBlockPos", (o, i) -> {
			BlockPos p = selectedBlock();
			if (p == null) { put3(o, i, -256, -256, -256); return; }
			Vec3 cam = camera.position();
			put3(o, i, (float) (p.getX() + 0.5 - cam.x), (float) (p.getY() + 0.5 - cam.y), (float) (p.getZ() + 0.5 - cam.z));
		});
		vec3("eyePosition", (o, i) -> put3(o, i, camEntity.getEyePosition(pt)));
		// shortcut: assumes vanilla clouds.png (256 wide, Iris: width * 400 ticks), read the real texture if a pack needs it
		f("cloudTime", () -> ((level.getGameTime() % (256 * 400)) + pt) * 0.03f);
		vec3("relativeEyePosition", (o, i) -> {
			Vec3 e = camEntity.getEyePosition(pt);
			Vector3d c = tracker.currentUnshifted;
			put3(o, i, (float) (c.x - e.x), (float) (c.y - e.y), (float) (c.z - e.z));
		});
		vec3("playerLookVector", (o, i) -> {
			if (camEntity instanceof LivingEntity le) put3(o, i, le.getViewVector(pt));
			else put3(o, i, 0, 0, 0);
		});
		vec3("playerBodyVector", (o, i) -> put3(o, i, camEntity.getForward()));
		put("lightningBoltPosition", "vec4", (o, i) -> {
			for (Entity e : level.entitiesForRendering()) {
				if (!(e instanceof LightningBolt)) continue;
				Vec3 v = e.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
				Vector3d c = tracker.currentUnshifted;
				put4(o, i, (float) (v.x - c.x), (float) (v.y - c.y), (float) (v.z - c.z), 1);
				return;
			}
			put4(o, i, 0, 0, 0, 0);
		});

		// Matrices (MatrixUniforms, IrisInternalUniforms); Distant Horizons absent → dh* follow Iris's DHCompat fallbacks
		put("gbufferModelView", "mat4", (o, i) -> mat4(o, i, modelView));
		put("gbufferModelViewInverse", "mat4", (o, i) -> mat4(o, i, modelViewInv));
		put("gbufferPreviousModelView", "mat4", (o, i) -> mat4(o, i, prevModelView));
		put("gbufferProjection", "mat4", (o, i) -> mat4(o, i, projection));
		put("gbufferProjectionInverse", "mat4", (o, i) -> mat4(o, i, projectionInv));
		put("gbufferPreviousProjection", "mat4", (o, i) -> mat4(o, i, prevProjection));
		put("dhProjection", "mat4", (o, i) -> mat4(o, i, projection));
		put("dhProjectionInverse", "mat4", (o, i) -> mat4(o, i, projectionInv));
		put("dhPreviousProjection", "mat4", (o, i) -> mat4(o, i, prevProjection));
		put("shadowModelView", "mat4", (o, i) -> mat4(o, i, shadowModelView));
		put("shadowModelViewInverse", "mat4", (o, i) -> mat4(o, i, shadowModelViewInv));
		put("shadowProjection", "mat4", (o, i) -> mat4(o, i, shadowOrtho));
		put("shadowProjectionInverse", "mat4", (o, i) -> mat4(o, i, shadowOrthoInv));
		put("iris_DefaultNormalMat", "mat3", (o, i) -> mat3(o, i, normalMat));
		put("iris_DefaultProjectionMatrixInverse", "mat4", (o, i) -> mat4(o, i, projectionInv));
		put("iris_DefaultModelViewMatrixInverse", "mat4", (o, i) -> mat4(o, i, modelViewInv));
		put("iris_ShadowModelViewMatrixInverse", "mat4", (o, i) -> mat4(o, i, shadowModelViewInv));
		put("iris_ShadowProjectionMatrixInverse", "mat4", (o, i) -> mat4(o, i, shadowOrthoInv));
		put("iris_LightmapTextureMatrix", "mat4", (o, i) -> mat4(o, i, IrisMath.LIGHTMAP_TEXTURE_MATRIX));
		f("dhFarPlane", () -> 0.01f);
		f("dhNearPlane", () -> 0.01f);
		i("dhRenderDistance", () -> mc.options.getEffectiveRenderDistance());

		// ID maps (IdMapUniforms)
		i("heldItemId", () -> heldId);
		i("heldItemId2", () -> heldId2);
		i("heldBlockLightValue", () -> heldLight);
		i("heldBlockLightValue2", () -> heldLight2);
		vec3("heldBlockLightColor", (o, i) -> put3(o, i, 1, 1, 1)); // IrisItemLightProvider.DEFAULT_LIGHT_COLOR
		vec3("heldBlockLightColor2", (o, i) -> put3(o, i, 1, 1, 1));

		// Player / world state (CommonUniforms.generalCommonUniforms)
		b("hideGUI", () -> mc.gui.hud.isHidden());
		b("isRightHanded", () -> mc.options.mainHand().get() == HumanoidArm.RIGHT);
		i("isEyeInWater", this::isEyeInWater);
		f("blindness", this::blindness);
		f("darknessFactor", this::darknessFactor);
		f("darknessLightFactor", () -> mc.gameRenderer.gameRenderState().lightmapRenderState.darknessEffectScale);
		f("nightVision", this::nightVision);
		b("is_sneaking", () -> player.isCrouching());
		b("is_sprinting", () -> player.isSprinting());
		b("is_hurt", () -> player.hurtTime > 0);
		b("is_invisible", () -> player.isInvisible());
		b("is_burning", () -> player.isOnFire());
		b("is_on_ground", () -> player.onGround());
		f("screenBrightness", () -> mc.options.gamma().get().floatValue());
		put("entityColor", "vec4", (o, i) -> put4(o, i, 0, 0, 0, 0));
		i("blockEntityId", () -> -1);
		i("currentRenderedItemId", () -> -1);
		i("anisotropicFiltering", () -> mc.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC ? mc.options.maxAnisotropyValue() : 0);
		f("pi", () -> (float) Math.PI);
		f("playerMood", () -> camEntity instanceof LocalPlayer lp ? Mth.clamp(lp.getCurrentMood(), 0.0f, 1.0f) : 0.0f);
		// shortcut: constantMood stays 0 (Iris tracks it with a sound-handler mixin), add when a pack needs it
		f("constantMood", () -> 0.0f);
		put("eyeBrightness", "ivec2", (o, i) -> { o[i] = eyeBlock; o[i + 1] = eyeSky; });
		put("eyeBrightnessSmooth", "ivec2", (o, i) -> { o[i] = (int) eyeSmoothX.get(); o[i + 1] = (int) eyeSmoothY.get(); });
		f("rainStrength", this::rainStrength);
		f("wetness", wetness::get);
		vec3("skyColor", (o, i) -> put3(o, i, camera.attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR, pt)));

		// Dynamic / composite-only built-ins that end up in the per-frame block: gbuffers semantics, draw-independent defaults
		// centerDepthSmooth: composites read it from a texture, the block copy stays 0
		f("centerDepthSmooth", () -> 0.0f);
		put("iris_ScreenSize", "vec2", (o, i) -> {
			o[i] = Float.floatToRawIntBits(Upscaling.viewWidth(mc.gameRenderer.mainRenderTarget().width));
			o[i + 1] = Float.floatToRawIntBits(Upscaling.viewHeight(mc.gameRenderer.mainRenderTarget().height));
		});
		vec3("fogColor", (o, i) -> put3(o, i, fogColor.x, fogColor.y, fogColor.z));
		i("entityId", () -> -1);
		put("atlasSize", "ivec2", (o, i) -> { o[i] = 0; o[i + 1] = 0; });
		i("gtextureId", () -> 0);
		put("gtextureSize", "ivec2", (o, i) -> { o[i] = 0; o[i + 1] = 0; });
		i("textureReloadCount", () -> 0);
		put("blendFunc", "ivec4", (o, i) -> { o[i] = 0; o[i + 1] = 0; o[i + 2] = 0; o[i + 3] = 0; });
		f("alphaTestRef", () -> 0.0f);
		f("iris_currentAlphaTest", () -> 0.0f);
		i("renderStage", () -> renderStage);
		i("fogMode", () -> fogDensity < 0.0f ? 0x2601 /* GL_LINEAR */ : 0x0801 /* GL_EXP2 */);
		i("fogShape", () -> 1);
		f("fogDensity", () -> Math.max(0.0f, fogDensity));
		f("fogStart", () -> fogStart);
		f("fogEnd", () -> fogEnd);
		put("iris_FogColor", "vec4", (o, i) -> put4(o, i, fogColor.x, fogColor.y, fogColor.z, fogColor.w));
		f("iris_FogStart", () -> fogStart);
		f("iris_FogEnd", () -> fogEnd);
		f("iris_FogDensity", () -> Math.max(0.0f, fogDensity));

		// Members the transpiler injects (Std140LayoutBuilder.INJECTED)
		put("emr_CameraBlockPos", "ivec3", (o, i) -> { // GlobalSettingsUniform.CameraBlockPos
			Vec3 p = cs.pos;
			o[i] = Mth.floor(p.x); o[i + 1] = Mth.floor(p.y); o[i + 2] = Mth.floor(p.z);
		});
		vec3("emr_CameraOffset", (o, i) -> { // GlobalSettingsUniform.CameraOffset = floor(pos) − pos
			Vec3 p = cs.pos;
			put3(o, i, (float) (Mth.floor(p.x) - p.x), (float) (Mth.floor(p.y) - p.y), (float) (Mth.floor(p.z) - p.z));
		});
		put("emr_FogColor", "vec4", (o, i) -> put4(o, i, fogColor.x, fogColor.y, fogColor.z, fogColor.w));
		f("emr_FogStart", () -> fogStart);
		f("emr_FogEnd", () -> fogEnd);
		f("emr_FogDensity", () -> Math.max(0.0f, fogDensity));
		put("emr_LightmapTextureMatrix", "mat4", (o, i) -> mat4(o, i, IrisMath.LIGHTMAP_TEXTURE_MATRIX));

		for (var b : UniformCatalog.all())
			if (!defs.containsKey(b.name())) throw new IllegalStateException("no provider for built-in " + b.name());
	}

	private static void floor3(int[] o, int i, Vector3d v) {
		o[i] = (int) Math.floor(v.x);
		o[i + 1] = (int) Math.floor(v.y);
		o[i + 2] = (int) Math.floor(v.z);
	}

	private static void fract3(int[] o, int i, Vector3d v) {
		put3(o, i, (float) (v.x - Math.floor(v.x)), (float) (v.y - Math.floor(v.y)), (float) (v.z - Math.floor(v.z)));
	}
}
