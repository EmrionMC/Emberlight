package dev.emrion.shaders.mod.pbr;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.mixin.AnimationStateAccessor;
import dev.emrion.shaders.mod.mixin.SpriteContentsAccessor;
import dev.emrion.shaders.mod.mixin.TextureAtlasAccessor;
import dev.emrion.shaders.mod.pack.PackManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * labPBR normals/specular atlases matching one MC atlas (Iris AtlasPBRLoader); sprites without maps keep Iris's defaults.
 * Decoding runs on MC's background executor, the upload reuses TextureAtlas.upload; rebuild descriptor sets when {@link #generation()} changes.
 */
public final class PbrAtlases {
	private static final boolean DUMP = Boolean.getBoolean("emrion.debug.pbrDump");
	/** -Demrion.pbr=false: never build the maps (the 1×1 defaults stay bound). */
	private static final boolean ENABLED = !"false".equals(System.getProperty("emrion.pbr"));

	enum Type {
		NORMAL("_n", 0xFF7F7FFF, new Vector4f(127 / 255F, 127 / 255F, 1, 1)),
		SPECULAR("_s", 0, new Vector4f());

		final String suffix;
		final int argb;
		final Vector4f clear;

		Type(String suffix, int argb, Vector4f clear) {
			this.suffix = suffix;
			this.argb = argb;
			this.clear = clear;
		}
	}

	/** A TextureAtlas that starts out filled with its type's default (TextureAtlasMixin clears it after createTexture). */
	public static final class PbrAtlas extends TextureAtlas {
		public final Vector4f clear;

		PbrAtlas(Identifier location, Type type) {
			super(location);
			clear = type.clear;
		}
	}

	private record Region(Identifier name, int x, int y, int padding, int width, int height) {}

	/** The stitched layout of one block atlas upload. */
	private record Layout(TextureAtlas base, int width, int height, int mipLevel, List<Region> regions) {}

	private record Loaded(Map<Identifier, TextureAtlasSprite> normals, Map<Identifier, TextureAtlasSprite> specular, long bytes) {}

	/** The current instance per atlas location (render thread). */
	private static final Map<Identifier, PbrAtlases> ATLASES = new HashMap<>();
	/** The resource packs declare {@code format=lab-pbr} in optifine/texture.properties (Iris's TextureFormatLoader). */
	private static boolean labPbr;
	private static int generation;

	private final Layout layout;
	private @Nullable PbrAtlas normals, specular;
	private boolean built, building;
	/** Base atlas animation ticks since its upload. */
	private int ticks;
	/** Bumped by release(): a build started before it must not upload (pack toggled off mid-build). */
	private int epoch;

	private PbrAtlases(Layout layout) {
		this.layout = layout;
	}

	/** Iris: with a declared labPBR format, specular uses labPBR mipmaps and nearest sampling; otherwise both maps are linear. */
	public static boolean labPbr() {
		return labPbr;
	}

	public static int generation() {
		return generation;
	}

	/** The instance of the atlas at {@code location}, null if that atlas was not uploaded while the mod was active. */
	public static @Nullable PbrAtlases of(Identifier location) {
		return ATLASES.get(location);
	}

	private static @Nullable PbrAtlases blocks() {
		return ATLASES.get(TextureAtlas.LOCATION_BLOCKS);
	}

	/** The block atlas's normal map, null until built or if no sprite has one. */
	public static @Nullable GpuTextureView normals() {
		PbrAtlases b = blocks();
		return b == null ? null : b.normalsView();
	}

	public static @Nullable GpuTextureView specular() {
		PbrAtlases b = blocks();
		return b == null ? null : b.specularView();
	}

	public static @Nullable GpuTextureView blockAtlas() {
		PbrAtlases b = blocks();
		return b == null ? null : b.layout.base().getTextureView();
	}

	/** Starts building the block atlas's maps unless built or under way. */
	public static void request() {
		PbrAtlases b = blocks();
		if (b != null) b.build();
	}

	public @Nullable GpuTextureView normalsView() {
		return normals == null ? null : normals.getTextureView();
	}

	public @Nullable GpuTextureView specularView() {
		return specular == null ? null : specular.getTextureView();
	}

	// ---- hooks (render thread) -----------------------------------------------------------------------------------------

	/** TextureAtlas.upload RETURN (base atlases only): drop the old maps, remember the new layout. */
	public static void onAtlasUpload(TextureAtlas atlas, SpriteLoader.Preparations prep) {
		List<Region> regions = prep.regions().values().stream().map(s -> new Region(s.contents().name(), s.getX(), s.getY(),
				Math.round(s.getU0() * prep.width()) - s.getX(), // padding: u0 = (x + padding) / width
				s.contents().width(), s.contents().height())).toList();
		PbrAtlases fresh = new PbrAtlases(new Layout(atlas, prep.width(), prep.height(), prep.mipLevel(), regions));
		PbrAtlases old = ATLASES.put(atlas.location(), fresh);
		if (old != null) old.release();
		if (!atlas.location().equals(TextureAtlas.LOCATION_BLOCKS)) return; // the others are built on first use
		generation++;
		dev.emrion.shaders.mod.rt.RtOmm.onAtlasUpload(prep);
		if (PackManager.selected() != null) fresh.build();
	}

	/** TextureAtlas.cycleAnimationFrames TAIL: PBR frames advance in the same tick. */
	public static void onAtlasTick(TextureAtlas atlas) {
		PbrAtlases a = ATLASES.get(atlas.location());
		if (a == null || a.layout.base() != atlas) return;
		a.ticks++;
		if (a.normals != null) a.normals.cycleAnimationFrames();
		if (a.specular != null) a.specular.cycleAnimationFrames();
	}

	/** The shader pack turned off: free every atlas's maps but the block atlas's (those are rebuilt lazily). */
	public static void releaseLazy() {
		ATLASES.forEach((location, a) -> {
			if (!location.equals(TextureAtlas.LOCATION_BLOCKS)) a.release();
		});
	}

	/** Starts building the maps unless built or under way. */
	public void build() {
		if (built || building || !ENABLED) return;
		building = true;
		ResourceManager resources = Minecraft.getInstance().getResourceManager();
		labPbr = labPbr(resources);
		long start = System.nanoTime();
		Layout l = layout;
		int e = epoch;
		CompletableFuture.supplyAsync(() -> load(l, resources), Util.backgroundExecutor())
				.whenCompleteAsync((loaded, error) -> finish(loaded, error, start, e), Minecraft.getInstance());
	}

	private void release() {
		if (normals != null) normals.close();
		if (specular != null) specular.close();
		normals = specular = null;
		built = building = false; // an in-flight build is stale now; a new request starts over
		epoch++;
	}

	// ---- background: decode, resize, mipmaps ---------------------------------------------------------------------------

	private static Loaded load(Layout l, ResourceManager resources) {
		Map<Identifier, TextureAtlasSprite> n = new ConcurrentHashMap<>(), s = new ConcurrentHashMap<>();
		AtomicLong bytes = new AtomicLong();
		l.regions().parallelStream().forEach(r -> {
			TextureAtlasSprite sprite = sprite(l, r, Type.NORMAL, resources, bytes);
			if (sprite != null) n.put(r.name(), sprite);
			sprite = sprite(l, r, Type.SPECULAR, resources, bytes);
			if (sprite != null) s.put(r.name(), sprite);
		});
		return new Loaded(n, s, bytes.get());
	}

	/** Iris's AtlasPBRLoader rules: the map's own .mcmeta animates it; a different frame size is resized to the sprite's. */
	private static @Nullable TextureAtlasSprite sprite(Layout l, Region r, Type type, ResourceManager resources, AtomicLong bytes) {
		Identifier location = location(r.name(), type);
		Optional<Resource> resource = resources.getResource(location);
		if (resource.isEmpty() && r.name().getPath().startsWith("optifine/")) // CTM/CIT sprites live outside textures/
			resource = resources.getResource(location = r.name().withPath(p -> p + type.suffix + ".png"));
		if (resource.isEmpty()) return null;
		NativeImage image = null;
		try {
			Optional<AnimationMetadataSection> animation = resource.get().metadata().getSection(AnimationMetadataSection.TYPE);
			try (InputStream in = resource.get().open()) {
				image = NativeImage.read(in);
			}
			int w = image.getWidth(), h = image.getHeight();
			FrameSize frame = animation.map(a -> a.calculateFrameSize(w, h)).orElse(new FrameSize(w, h));
			if (w % frame.width() != 0 || h % frame.height() != 0) {
				EmrionShadersClient.LOG.warn("PBR map {} ({}x{}) is not a multiple of its frame size {}x{}", location, w, h, frame.width(), frame.height());
				image.close();
				return null;
			}
			if (frame.width() != r.width() || frame.height() != r.height()) {
				NativeImage scaled = PbrImages.scale(image, w / frame.width() * r.width(), h / frame.height() * r.height(), w / frame.width(), h / frame.height());
				image.close();
				image = scaled;
			}
			return sprite(l, r, type, image, animation, bytes);
		} catch (IOException | RuntimeException e) {
			EmrionShadersClient.LOG.warn("Could not load PBR map {}: {}", location, e.toString());
			if (image != null) image.close();
			return null;
		}
	}

	private static TextureAtlasSprite sprite(Layout l, Region r, Type type, NativeImage image, Optional<AnimationMetadataSection> animation,
			AtomicLong bytes) {
		var contents = new SpriteContents(r.name().withSuffix(type.suffix), new FrameSize(r.width(), r.height()), image, animation, List.of(),
				Optional.empty());
		NativeImage[] mips = PbrImages.mips(image, l.mipLevel(), type == Type.SPECULAR && labPbr);
		((SpriteContentsAccessor) contents).emrion$setByMipLevel(mips);
		for (NativeImage mip : mips) bytes.addAndGet(4L * mip.getWidth() * mip.getHeight());
		return new Sprite(location(l, type), contents, l, r);
	}

	private static boolean labPbr(ResourceManager resources) {
		var resource = resources.getResource(Identifier.withDefaultNamespace("optifine/texture.properties"));
		if (resource.isEmpty()) return false;
		try (InputStream in = resource.get().open()) {
			var properties = new Properties();
			properties.load(in);
			return properties.getProperty("format", "").split("/", -1)[0].equals("lab-pbr");
		} catch (IOException | IllegalArgumentException e) {
			return false;
		}
	}

	static Identifier location(Identifier sprite, Type type) {
		return sprite.withPath(p -> "textures/" + p + type.suffix + ".png");
	}

	private static Identifier location(Layout l, Type type) {
		return l.base().location().withPath(p -> p.replace(".png", type.suffix + ".png"));
	}

	/** TextureAtlasSprite's constructor is protected. */
	private static final class Sprite extends TextureAtlasSprite {
		Sprite(Identifier atlas, SpriteContents contents, Layout l, Region r) {
			super(atlas, contents, l.width(), l.height(), r.x(), r.y(), r.padding());
		}
	}

	// ---- render thread: upload -----------------------------------------------------------------------------------------

	private void finish(@Nullable Loaded loaded, @Nullable Throwable error, long start, int buildEpoch) {
		if (buildEpoch == epoch) building = false; // else a newer build owns the flag
		Layout l = layout;
		Identifier location = l.base().location();
		// replaced by a reload, or the pack turned off meanwhile: drop the result (the current instance builds on demand)
		boolean stale = ATLASES.get(location) != this || PackManager.selected() == null || buildEpoch != epoch;
		if (error != null || stale) {
			if (error != null) EmrionShadersClient.LOG.error("Building the PBR atlases of {} failed", location, error);
			if (error != null && !stale) built = true; // no retry loop: the flat defaults stay bound until the next reload
			if (loaded != null) close(loaded.normals());
			if (loaded != null) close(loaded.specular());
			return;
		}
		long cpu = System.nanoTime();
		normals = upload(l, Type.NORMAL, loaded.normals());
		specular = upload(l, Type.SPECULAR, loaded.specular());
		built = true;
		// a runtime created while this built bound the flat defaults: the bump makes PackManager rebuild it with these views
		if (location.equals(TextureAtlas.LOCATION_BLOCKS) && (normals != null || specular != null)) generation++;
		long bytesPerAtlas = 0;
		for (int level = 0; level <= l.mipLevel(); level++) bytesPerAtlas += 4L * (l.width() >> level) * (l.height() >> level);
		EmrionShadersClient.LOG.info("PBR atlases of {} {}x{} ({} mips, {} sprites): {} normal maps, {} specular maps; load {} ms (background), "
						+ "upload {} ms (render thread), {} MB decoded, {} MB VRAM", location, l.width(), l.height(), l.mipLevel() + 1, l.regions().size(),
				loaded.normals().size(), loaded.specular().size(), (cpu - start) / 1_000_000, (System.nanoTime() - cpu) / 1_000_000,
				loaded.bytes() >> 20, ((normals != null ? bytesPerAtlas : 0) + (specular != null ? bytesPerAtlas : 0)) >> 20);
		EmrionShadersClient.LOG.info("PBR atlases of {}: sprites cover {}% of the atlas, normal maps {}%, specular maps {}%", location,
				coverage(l, null), coverage(l, loaded.normals()), coverage(l, loaded.specular()));
		if (DUMP) dump(l);
	}

	/** Percentage of the atlas area covered by the regions (with padding) of all sprites, or of those in {@code with}. */
	private static long coverage(Layout l, @Nullable Map<Identifier, TextureAtlasSprite> with) {
		long area = 0;
		for (Region r : l.regions())
			if (with == null || with.containsKey(r.name())) area += (long) (r.width() + 2 * r.padding()) * (r.height() + 2 * r.padding());
		return area * 100 / ((long) l.width() * l.height());
	}

	private @Nullable PbrAtlas upload(Layout l, Type type, Map<Identifier, TextureAtlasSprite> sprites) {
		if (sprites.isEmpty()) return null; // no map of this type at all: PackRuntime binds Iris's 1×1 default
		Identifier missing = MissingTextureAtlasSprite.getLocation();
		if (!sprites.containsKey(missing)) { // TextureAtlas.upload requires the missing sprite
			Region r = l.regions().stream().filter(x -> x.name().equals(missing)).findFirst().orElseThrow();
			NativeImage image = new NativeImage(r.width(), r.height(), false);
			image.fillRect(0, 0, r.width(), r.height(), type.argb);
			sprites.put(missing, sprite(l, r, type, image, Optional.empty(), new AtomicLong()));
		}
		PbrAtlas atlas = new PbrAtlas(location(l, type), type);
		atlas.upload(new SpriteLoader.Preparations(l.width(), l.height(), l.mipLevel(), sprites.get(missing), sprites, CompletableFuture.completedFuture(null)));
		// Catch up with the block atlas's animations (both started at frame 0 at the block atlas upload).
		var states = ((TextureAtlasAccessor) (Object) atlas).emrion$animationStates();
		if (ticks > 0 && !states.isEmpty()) {
			for (var state : states) {
				fastForward(state, ticks);
				((AnimationStateAccessor) state).emrion$setDirty(true);
			}
			((TextureAtlasAccessor) (Object) atlas).emrion$uploadAnimationFrames();
		}
		// The GPU holds everything now (animation frames have their own textures): free the CPU images early.
		sprites.values().forEach(s -> s.contents().close());
		return atlas;
	}

	/** {@code ticks} ticks from frame 0, but at most two cycles of work (a late build after hours of play would replay them all). */
	private static void fastForward(net.minecraft.client.renderer.texture.SpriteContents.AnimationState state, int ticks) {
		var a = (AnimationStateAccessor) state;
		int done = 0;
		do {
			state.tick();
			done++;
		} while (done < ticks && (a.emrion$frame() != 0 || a.emrion$subFrame() != 0));
		if (done < ticks) for (int i = 0, rest = ticks % done; i < rest; i++) state.tick(); // done = cycle length
	}

	private static void close(Map<Identifier, TextureAtlasSprite> sprites) {
		sprites.values().forEach(s -> s.contents().close());
	}

	private void dump(Layout l) {
		Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/pbr");
		try {
			Files.createDirectories(dir);
			l.base().dumpContents(l.base().location(), dir);
			if (normals != null) normals.dumpContents(normals.location(), dir);
			if (specular != null) specular.dumpContents(specular.location(), dir);
			EmrionShadersClient.LOG.info("PBR atlases dumped to {}", dir);
		} catch (IOException e) {
			EmrionShadersClient.LOG.warn("PBR atlas dump failed", e);
		}
	}
}
