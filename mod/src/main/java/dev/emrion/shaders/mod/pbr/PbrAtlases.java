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
 * labPBR {@code normals}/{@code specular} atlases with exactly the block atlas's size, mip count and sprite placement (as Iris
 * builds them). Every block-atlas sprite {@code ns:path} takes {@code ns:textures/path_n.png} / {@code _s.png} from the active
 * resource packs (by sprite id, so CTM sprites work if Continuity stitches them into the atlas). Sprites without a map keep
 * Iris's default fill (normals (127,127,255,255), specular 0).
 *
 * <p>Built only while a shader pack is selected: requested at the block atlas upload (resource reload) and by every
 * {@code PackRuntime}. Decoding, resizing and mip generation run in parallel on MC's background executor; the GPU part reuses
 * MC's own atlas upload ({@link TextureAtlas#upload}: sprite blits with padding, animation states) on the render thread.
 * Animated PBR sprites tick with the block atlas and are fast-forwarded to its tick count when they arrive late.
 * {@link #generation()} changes whenever a view changes; descriptor sets that bind the views must be rebuilt then.
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

	private static @Nullable Layout layout;
	private static @Nullable PbrAtlas normals, specular;
	private static boolean built, building;
	/** The resource packs declare {@code format=lab-pbr} in optifine/texture.properties (Iris's TextureFormatLoader). */
	private static boolean labPbr;
	private static int generation;
	/** Block atlas animation ticks since its upload. */
	private static int ticks;

	private PbrAtlases() {}

	/** Iris: with a declared labPBR format, specular uses labPBR mipmaps and nearest sampling; otherwise both maps are linear. */
	public static boolean labPbr() {
		return labPbr;
	}

	public static int generation() {
		return generation;
	}

	public static @Nullable GpuTextureView normals() {
		return normals == null ? null : normals.getTextureView();
	}

	public static @Nullable GpuTextureView specular() {
		return specular == null ? null : specular.getTextureView();
	}

	public static @Nullable GpuTextureView blockAtlas() {
		return layout == null ? null : layout.base().getTextureView();
	}

	// ---- hooks (render thread) -----------------------------------------------------------------------------------------

	/** TextureAtlas.upload RETURN for the block atlas: drop the old maps, remember the new layout. */
	public static void onBlockAtlasUpload(TextureAtlas atlas, SpriteLoader.Preparations prep) {
		release();
		List<Region> regions = prep.regions().values().stream().map(s -> new Region(s.contents().name(), s.getX(), s.getY(),
				Math.round(s.getU0() * prep.width()) - s.getX(), // padding: u0 = (x + padding) / width
				s.contents().width(), s.contents().height())).toList();
		layout = new Layout(atlas, prep.width(), prep.height(), prep.mipLevel(), regions);
		ticks = 0;
		generation++;
		if (PackManager.selected() != null) request();
	}

	/** TextureAtlas.cycleAnimationFrames TAIL for the block atlas: PBR frames advance in the same tick. */
	public static void onBlockAtlasTick() {
		ticks++;
		if (normals != null) normals.cycleAnimationFrames();
		if (specular != null) specular.cycleAnimationFrames();
	}

	/** Starts building the maps for the current layout unless built or under way. */
	public static void request() {
		Layout l = layout;
		if (l == null || built || building || !ENABLED) return;
		building = true;
		ResourceManager resources = Minecraft.getInstance().getResourceManager();
		labPbr = labPbr(resources);
		long start = System.nanoTime();
		CompletableFuture.supplyAsync(() -> load(l, resources), Util.backgroundExecutor())
				.whenCompleteAsync((loaded, error) -> finish(l, loaded, error, start), Minecraft.getInstance());
	}

	private static void release() {
		if (normals != null) normals.close();
		if (specular != null) specular.close();
		normals = specular = null;
		built = false;
		layout = null;
		generation++;
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
				NativeImage scaled = PbrImages.scale(image, w / frame.width() * r.width(), h / frame.height() * r.height());
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
		return new Sprite(location(type), contents, l, r);
	}

	private static boolean labPbr(ResourceManager resources) {
		var resource = resources.getResource(Identifier.withDefaultNamespace("optifine/texture.properties"));
		if (resource.isEmpty()) return false;
		try (InputStream in = resource.get().open()) {
			var properties = new Properties();
			properties.load(in);
			return properties.getProperty("format", "").split("/")[0].equals("lab-pbr");
		} catch (IOException e) {
			return false;
		}
	}

	static Identifier location(Identifier sprite, Type type) {
		return sprite.withPath(p -> "textures/" + p + type.suffix + ".png");
	}

	private static Identifier location(Type type) {
		return TextureAtlas.LOCATION_BLOCKS.withPath(p -> p.replace(".png", type.suffix + ".png"));
	}

	/** TextureAtlasSprite's constructor is protected. */
	private static final class Sprite extends TextureAtlasSprite {
		Sprite(Identifier atlas, SpriteContents contents, Layout l, Region r) {
			super(atlas, contents, l.width(), l.height(), r.x(), r.y(), r.padding());
		}
	}

	// ---- render thread: upload -----------------------------------------------------------------------------------------

	private static void finish(Layout l, @Nullable Loaded loaded, @Nullable Throwable error, long start) {
		building = false;
		if (error != null || l != layout) {
			if (error != null) EmrionShadersClient.LOG.error("Building the PBR atlases failed", error);
			if (error != null && l == layout) built = true; // no retry loop: the flat defaults stay bound until the next reload
			if (loaded != null) close(loaded.normals());
			if (loaded != null) close(loaded.specular());
			if (l != layout && PackManager.selected() != null) request(); // a reload replaced the layout meanwhile
			return;
		}
		long cpu = System.nanoTime();
		normals = upload(l, Type.NORMAL, loaded.normals());
		specular = upload(l, Type.SPECULAR, loaded.specular());
		built = true;
		generation++;
		long bytesPerAtlas = 0;
		for (int level = 0; level <= l.mipLevel(); level++) bytesPerAtlas += 4L * (l.width() >> level) * (l.height() >> level);
		EmrionShadersClient.LOG.info("PBR atlases {}x{} ({} mips, {} sprites): {} normal maps, {} specular maps; load {} ms (background), "
						+ "upload {} ms (render thread), {} MB decoded, {} MB VRAM", l.width(), l.height(), l.mipLevel() + 1, l.regions().size(),
				loaded.normals().size(), loaded.specular().size(), (cpu - start) / 1_000_000, (System.nanoTime() - cpu) / 1_000_000,
				loaded.bytes() >> 20, ((normals != null ? bytesPerAtlas : 0) + (specular != null ? bytesPerAtlas : 0)) >> 20);
		if (DUMP) dump(l);
	}

	private static @Nullable PbrAtlas upload(Layout l, Type type, Map<Identifier, TextureAtlasSprite> sprites) {
		if (sprites.isEmpty()) return null; // no map of this type at all: PackRuntime binds Iris's 1×1 default
		Identifier missing = MissingTextureAtlasSprite.getLocation();
		if (!sprites.containsKey(missing)) { // TextureAtlas.upload requires the missing sprite
			Region r = l.regions().stream().filter(x -> x.name().equals(missing)).findFirst().orElseThrow();
			NativeImage image = new NativeImage(r.width(), r.height(), false);
			image.fillRect(0, 0, r.width(), r.height(), type.argb);
			sprites.put(missing, sprite(l, r, type, image, Optional.empty(), new AtomicLong()));
		}
		PbrAtlas atlas = new PbrAtlas(location(type), type);
		atlas.upload(new SpriteLoader.Preparations(l.width(), l.height(), l.mipLevel(), sprites.get(missing), sprites, CompletableFuture.completedFuture(null)));
		// Catch up with the block atlas's animations (both started at frame 0 at the block atlas upload).
		var states = ((TextureAtlasAccessor) (Object) atlas).emrion$animationStates();
		if (ticks > 0 && !states.isEmpty()) {
			for (var state : states) {
				for (int i = 0; i < ticks; i++) state.tick();
				((AnimationStateAccessor) state).emrion$setDirty(true);
			}
			((TextureAtlasAccessor) (Object) atlas).emrion$uploadAnimationFrames();
		}
		// The GPU holds everything now (animation frames have their own textures): free the CPU images early.
		sprites.values().forEach(s -> s.contents().close());
		return atlas;
	}

	private static void close(Map<Identifier, TextureAtlasSprite> sprites) {
		sprites.values().forEach(s -> s.contents().close());
	}

	private static void dump(Layout l) {
		Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/pbr");
		try {
			Files.createDirectories(dir);
			l.base().dumpContents(TextureAtlas.LOCATION_BLOCKS, dir);
			if (normals != null) normals.dumpContents(normals.location(), dir);
			if (specular != null) specular.dumpContents(specular.location(), dir);
			EmrionShadersClient.LOG.info("PBR atlases dumped to {}", dir);
		} catch (IOException e) {
			EmrionShadersClient.LOG.warn("PBR atlas dump failed", e);
		}
	}
}
