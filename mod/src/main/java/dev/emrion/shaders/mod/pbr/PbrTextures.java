package dev.emrion.shaders.mod.pbr;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.mixin.AbstractTextureAccessor;
import dev.emrion.shaders.mod.mixin.TextureManagerAccessor;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/**
 * The labPBR maps of the texture a draw binds as Sampler0 (Iris's PBRTextureManager), pushed as the set-0 samplers
 * EmrionNormals/EmrionSpecular (FORMAT §7.1). The texture is found in MC's TextureManager by identity: a {@link TextureAtlas}
 * uses its {@link PbrAtlases} (built in the background on first use), a {@link SimpleTexture} (exact class, as Iris) loads
 * {@code <id>_n.png}/{@code <id>_s.png} at the next frame start, anything else (dynamic textures, skins, render targets) gets
 * the 1×1 defaults. Render thread only. Cleared, and the maps freed, on every atlas upload (resource reload) and when the
 * pack turns off.
 */
public final class PbrTextures {
	private PbrTextures() {}

	/** A texture's maps: its atlas's PBR atlases, or its own textures; all null = defaults. */
	private record Holder(@Nullable PbrAtlases atlas, @Nullable SimpleTexture normal, @Nullable SimpleTexture specular) {}

	private static final Holder NONE = new Holder(null, null, null);
	private static final NextFrameCache<GpuTexture, Holder> CACHE = new NextFrameCache<>();
	/** Iris's defaults: normal (127, 127, 255, 255), specular 0; freed at client shutdown ({@link #closeDefaults}). */
	private static long defaultNormal, defaultSpecular;
	private static final java.util.List<GpuTextureView> DEFAULT_VIEWS = new java.util.ArrayList<>();

	/** Writes the image views of {@code albedo}'s normal and specular map to {@code out[at]} and {@code out[at + 1]}. */
	public static void views(@Nullable GpuTexture albedo, long[] out, int at) {
		Holder h = albedo == null ? NONE : CACHE.get(albedo);
		if (h == null) h = NONE; // queued: loads at the next frame start
		GpuTextureView n = h.atlas != null ? h.atlas.normalsView() : h.normal != null ? h.normal.getTextureView() : null;
		GpuTextureView s = h.atlas != null ? h.atlas.specularView() : h.specular != null ? h.specular.getTextureView() : null;
		out[at] = n != null ? ((VulkanGpuTextureView) n).vkImageView() : defaultNormal;
		out[at + 1] = s != null ? ((VulkanGpuTextureView) s).vkImageView() : defaultSpecular;
	}

	/** Frame start (no render pass open; before any {@link #views}): loads the maps of the textures first bound last frame. */
	public static void onNewFrame() {
		if (defaultNormal == 0) {
			defaultNormal = solid("emrion default normals", 0xFFFF7F7F); // ABGR
			defaultSpecular = solid("emrion default specular", 0);
		}
		CACHE.resolve(PbrTextures::load);
	}

	/** Frees every loaded map and forgets every texture (the atlases free their own, see {@link PbrAtlases#releaseLazy}). */
	public static void clear() {
		CACHE.clear(h -> {
			if (h.normal != null) h.normal.close();
			if (h.specular != null) h.specular.close();
		});
	}

	private static Map<GpuTexture, Holder> load(Set<GpuTexture> pending) {
		Map<GpuTexture, Holder> out = new IdentityHashMap<>();
		Minecraft mc = Minecraft.getInstance();
		for (AbstractTexture t : ((TextureManagerAccessor) mc.getTextureManager()).emrion$byPath().values()) {
			GpuTexture g = ((AbstractTextureAccessor) t).emrion$texture();
			if (g != null && pending.contains(g)) out.put(g, holder(t, mc.getResourceManager()));
		}
		for (GpuTexture g : pending) out.putIfAbsent(g, NONE); // not a TextureManager texture
		return out;
	}

	private static Holder holder(AbstractTexture t, ResourceManager resources) {
		if (t.getClass() == TextureAtlas.class) {
			PbrAtlases atlas = PbrAtlases.of(((TextureAtlas) t).location());
			if (atlas == null) return NONE;
			atlas.build();
			return new Holder(atlas, null, null);
		}
		if (t.getClass() != SimpleTexture.class) return NONE; // Iris: SimplePBRLoader is registered for exactly SimpleTexture
		Identifier id = ((SimpleTexture) t).resourceId();
		SimpleTexture n = map(id, "_n", resources), s = map(id, "_s", resources);
		if (n == null && s == null) return NONE;
		EmrionShadersClient.LOG.info("PBR maps for {}: normal {}, specular {}", id, size(n), size(s));
		return new Holder(null, n, s);
	}

	private static @Nullable SimpleTexture map(Identifier id, String suffix, ResourceManager resources) {
		Identifier location = id.withPath(p -> withSuffix(p, suffix));
		if (resources.getResource(location).isEmpty()) return null;
		SimpleTexture texture = new SimpleTexture(location);
		try {
			texture.apply(texture.loadContents(resources));
			return texture;
		} catch (Exception e) {
			EmrionShadersClient.LOG.warn("Could not load PBR map {}: {}", location, e.toString());
			texture.close();
			return null;
		}
	}

	/** {@code a/b.png} → {@code a/b_n.png} (Iris's PBRType.appendSuffix). */
	static String withSuffix(String path, String suffix) {
		int dot = path.lastIndexOf('.');
		return dot <= path.lastIndexOf('/') ? path + suffix : path.substring(0, dot) + suffix + path.substring(dot);
	}

	private static String size(@Nullable SimpleTexture t) {
		return t == null ? "none" : t.getTexture().getWidth(0) + "x" + t.getTexture().getHeight(0);
	}

	private static long solid(String label, int abgr) {
		try (NativeImage image = new NativeImage(1, 1, false)) {
			image.setPixelABGR(0, 0, abgr);
			var device = RenderSystem.getDevice();
			GpuTexture tex = device.createTexture(label, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
			device.createCommandEncoder().writeToTexture(tex, image);
			GpuTextureView view = device.createTextureView(tex);
			DEFAULT_VIEWS.add(view);
			return ((VulkanGpuTextureView) view).vkImageView();
		}
	}

	/** Client shutdown (render thread): frees the default maps (their views and textures). */
	public static void closeDefaults() {
		for (GpuTextureView v : DEFAULT_VIEWS) {
			v.close();
			v.texture().close();
		}
		DEFAULT_VIEWS.clear();
		defaultNormal = defaultSpecular = 0;
	}
}
