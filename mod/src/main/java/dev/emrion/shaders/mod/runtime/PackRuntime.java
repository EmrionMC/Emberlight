package dev.emrion.shaders.mod.runtime;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.pbr.PbrAtlases;
import dev.emrion.shaders.mod.rt.RtScene;
import dev.emrion.shaders.mod.uniforms.UniformSystem;
import dev.emrion.shaders.mod.vk.DescriptorPool;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.UboRing;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/**
 * GPU state of one pack dimension at one window size (ARCHITECTURE §5.3/§5.4): targets, custom textures, samplers and one
 * pre-built set-1 descriptor set per flip state. Created on the render thread from the background-built {@link PackPipelines};
 * {@link FrameExecutor} runs the plan on it. A window resize builds a new PackRuntime from the same pipelines.
 */
public final class PackRuntime {
	private static final Pattern COLORTEX = Pattern.compile("colortex(\\d+)");
	private static final Pattern SHADOWCOLOR = Pattern.compile("shadowcolor(\\d+)");
	private static final int MC_USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING
			| GpuTexture.USAGE_RENDER_ATTACHMENT;
	/** Atlases as Iris samples them: its terrain sampler (nearest, linear mips); labPBR specular nearest (no value blending). */
	private static final Manifest.SamplerDef ATLAS_SAMPLER = new Manifest.SamplerDef("nearest", "linear", "clamp", null);
	private static final Manifest.SamplerDef LABPBR_SPECULAR_SAMPLER = new Manifest.SamplerDef("nearest", "nearest", "clamp", null);

	/** One physical image: an MC texture (attachable by geometry passes later) or a raw VMA image. */
	public static final class Img extends Hazards.Tracked {
		final Manifest.Target target;
		GpuTexture tex;
		GpuTextureView sampleView, attachView;
		RawImage raw;
		long image, sampled, attach;
		GpuFormat format;
		int vkFormat, width, height, depth, mips;
		boolean isDepth;

		Img(String label, Manifest.Target target) {
			super(label);
			this.target = target;
		}
	}

	public static final class Buf extends Hazards.Tracked {
		final RawBuffer buffer;

		Buf(String label, RawBuffer buffer) {
			super(label);
			this.buffer = buffer;
		}
	}

	final VulkanContext ctx;
	final PackPipelines pipelines;
	final Manifest manifest;
	final Manifest.Dimension dim;
	final UniformSystem uniforms;
	public final int width, height;
	UboRing ring;

	final Map<String, Img[]> images = new HashMap<>();
	final Map<String, Buf> buffers = new HashMap<>();
	private final Map<String, Img> textures = new HashMap<>();
	private final Map<Manifest.SamplerDef, Long> samplers = new HashMap<>();
	private final List<GpuTexture> ownedTextures = new ArrayList<>();
	private final List<GpuTextureView> ownedViews = new ArrayList<>();
	private Img dummy;
	/** FORMAT builtin resources. */
	private final Map<String, Img> builtins = new HashMap<>();
	private DescriptorPool pool;
	/** {@link PbrAtlases#generation()} of the bound atlas views; the runtime must be rebuilt when it changes. */
	public final int atlasGeneration = PbrAtlases.generation();
	/** flip state id → descriptor set. */
	long[] sets;
	/** flip state id → binding → resource the binding reads (for barriers); null for UBOs and unresolved bindings. */
	Hazards.Tracked[][] bindingResource;
	String[] bindingKind;

	public PackRuntime(VulkanContext ctx, PackPipelines pipelines, Path cacheDir, Manifest manifest, Manifest.Dimension dim,
			UniformSystem uniforms, int width, int height) {
		this.ctx = ctx;
		this.pipelines = pipelines;
		this.manifest = manifest;
		this.dim = dim;
		this.uniforms = uniforms;
		this.width = width;
		this.height = height;
		try {
			init(cacheDir);
		} catch (RuntimeException | Error failed) { // e.g. out of GPU memory half-way: release what was allocated
			destroyLater();
			throw failed;
		}
	}

	private void init(Path cacheDir) {
		long align = ctx.limits().minUniformBufferOffsetAlignment();
		ring = new UboRing(ctx, manifest.uniforms().size() + FrameExecutor.PHASE_BLOCKS * (manifest.uniforms().phase().size() + align) + align);

		java.util.Set<String> storage = new java.util.HashSet<>();
		for (var r : dim.resources()) if (r.kind().equals("storage_image")) storage.add(r.target());
		for (var t : dim.targets()) createTarget(t, storage.contains(t.name()));
		dummy = mcTexture("emrion white", solid(0xFFFFFFFF));
		builtins.put("white", dummy);
		PbrAtlases.request();
		var atlas = PbrAtlases.blockAtlas();
		builtins.put("block_atlas", atlas != null ? view("block atlas", atlas) : dummy);
		builtins.put("white_depth", whiteDepth());
		for (var tex : manifest.textures()) textures.put(tex.name(), loadTexture(cacheDir, tex));

		int maxBinding = dim.resources().stream().mapToInt(Manifest.Resource::binding).max().orElse(0);
		bindingKind = new String[maxBinding + 1];
		dim.resources().forEach(r -> bindingKind[r.binding()] = r.kind());
		int maxFlip = dim.flipStates().stream().mapToInt(Manifest.FlipState::id).max().orElse(0);
		sets = new long[maxFlip + 1];
		bindingResource = new Hazards.Tracked[maxFlip + 1][maxBinding + 1];
		int n = dim.flipStates().size();
		int[] sizes = {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 2 * n, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, Math.max(1, count("sampled_image") * n),
				VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, Math.max(1, count("storage_image") * n), VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, Math.max(1, count("storage_buffer") * n)};
		int as = count("acceleration_structure"); // RT variants only: the type needs the RT device extensions
		if (as > 0) {
			if (RtScene.get() == null) throw new IllegalStateException("RT runtime without an RtScene");
			sizes = java.util.Arrays.copyOf(sizes, 10);
			sizes[8] = org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
			sizes[9] = as * n;
		}
		pool = new DescriptorPool(ctx, n, sizes);
		for (var fs : dim.flipStates()) sets[fs.id()] = buildSet(fs);
	}

	private int count(String kind) {
		return (int) dim.resources().stream().filter(r -> r.kind().equals(kind)).count();
	}

	// ---- targets -----------------------------------------------------------------------------------------------------

	private int[] size(Manifest.Target t) {
		int[] s = {1, 1, 1};
		for (int i = 0; i < 3; i++) {
			if (t.absoluteSize() != null && i < t.absoluteSize().length && t.absoluteSize()[i] != 0) s[i] = t.absoluteSize()[i];
			else if (i < 2 && t.relativeSize() != null) s[i] = Math.max(1, (int) ((i == 0 ? width : height) * t.relativeSize()[i]));
		}
		return s;
	}

	private void createTarget(Manifest.Target t, boolean storage) {
		int[] s = size(t);
		if (t.kind().equals("buffer")) {
			long bytes = t.relativeSize() != null ? t.bytes() * s[0] * s[1] : t.bytes();
			buffers.put(t.name(), new Buf(t.name(), new RawBuffer(ctx, bytes,
					VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false, "emrion " + t.name())));
			return;
		}
		Img[] sides = new Img[2];
		images.put(t.name(), sides); // before allocating, so a failure releases a created main side
		for (int side = 0; side < (t.hasAlt() ? 2 : 1); side++) {
			String label = "emrion " + t.name() + (side == 1 ? " alt" : "");
			Img img = new Img(label, t);
			img.format = Formats.gpu(t.format());
			img.vkFormat = VulkanConst.toVk(img.format);
			img.width = s[0];
			img.height = s[1];
			img.depth = s[2];
			img.isDepth = t.kind().equals("depth");
			img.mips = t.mipLevels() == 0 ? Formats.fullMipChain(s[0], s[1], 1) : Math.max(1, t.mipLevels());
			if (t.kind().equals("image")) {
				// 3D and storage-only images: MC textures are 2D only
				img.raw = new RawImage(ctx, img.vkFormat, s[0], s[1], s[2], 1,
						VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, label);
				img.mips = 1;
				img.image = img.raw.image;
				img.sampled = img.attach = img.raw.view;
			} else {
				img.tex = RenderSystem.getDevice().createTexture(label, MC_USAGE | (storage ? Formats.USAGE_STORAGE : 0), img.format, s[0], s[1], 1, img.mips);
				img.sampleView = RenderSystem.getDevice().createTextureView(img.tex);
				img.attachView = img.mips == 1 ? img.sampleView : RenderSystem.getDevice().createTextureView(img.tex, 0, 1);
				img.image = ((VulkanGpuTexture) img.tex).vkImage();
				img.sampled = ((VulkanGpuTextureView) img.sampleView).vkImageView();
				img.attach = ((VulkanGpuTextureView) img.attachView).vkImageView();
			}
			sides[side] = img;
		}
	}

	/** The physical image of {@code target} on {@code side} ("main"/"alt"); null if the target does not exist. */
	Img image(String target, String side) {
		Img[] s = images.get(target);
		if (s == null) return null;
		return "alt".equals(side) && s[1] != null ? s[1] : s[0];
	}

	/** The side a pass in {@code flip} reads (FORMAT §7.2): alt for colortex (shadowcolor) in the flip state's alt (shadowAlt) list. */
	Img read(String target, Manifest.FlipState flip) {
		Matcher m = COLORTEX.matcher(target), s = SHADOWCOLOR.matcher(target);
		boolean alt = m.matches() && flip.alt().contains(Integer.parseInt(m.group(1)))
				|| s.matches() && flip.shadowAlt() != null && flip.shadowAlt().contains(Integer.parseInt(s.group(1)));
		return image(target, alt ? "alt" : "main");
	}

	/** 1×1 white RGBA8 view (unbound albedo). */
	long whiteView() {
		return dummy.sampled;
	}

	Manifest.FlipState flipState(int id) {
		for (var f : dim.flipStates()) if (f.id() == id) return f;
		throw new IllegalArgumentException("unknown flip state " + id);
	}

	// ---- custom textures -----------------------------------------------------------------------------------------------

	private Img loadTexture(Path cacheDir, Manifest.TextureDef t) {
		try {
			if (t.file() != null) return mcTexture("emrion " + t.name(), NativeImage.read(Files.readAllBytes(cacheDir.resolve(t.file()))));
			if ("emrion:noise".equals(t.resource())) return mcTexture("emrion noisetex", noise(t.size()[0], t.size()[1]));
		} catch (IOException e) {
			throw new IllegalStateException("custom texture " + t.name() + ": " + e.getMessage(), e);
		}
		return dummy; // shortcut: other MC resources resolve in M3 with the geometry passes; composites do not use them
	}

	/** Iris's generated noise texture: random RGB, opaque. */
	private static NativeImage noise(int w, int h) {
		NativeImage img = new NativeImage(w, h, false);
		Random r = new Random(0);
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setPixelABGR(x, y, 0xFF000000 | (r.nextInt() & 0xFFFFFF));
		return img;
	}

	private static NativeImage solid(int abgr) {
		NativeImage img = new NativeImage(1, 1, false);
		img.setPixelABGR(0, 0, abgr);
		return img;
	}

	/** 1×1 D32 = 1.0: what unbound shadow samplers (compare) read. */
	private Img whiteDepth() {
		var tex = RenderSystem.getDevice().createTexture("emrion white depth", MC_USAGE,
				GpuFormat.D32_FLOAT, 1, 1, 1, 1);
		RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(tex, 1.0);
		var view = RenderSystem.getDevice().createTextureView(tex);
		ownedTextures.add(tex);
		ownedViews.add(view);
		Img img = new Img("emrion white depth", null);
		img.sampled = ((VulkanGpuTextureView) view).vkImageView();
		return img;
	}

	/** A view MC owns (the atlases). */
	private static Img view(String label, GpuTextureView view) {
		Img img = new Img("emrion " + label, null);
		img.sampled = ((VulkanGpuTextureView) view).vkImageView();
		return img;
	}

	private Img mcTexture(String label, NativeImage source) {
		try (source) {
			var tex = RenderSystem.getDevice().createTexture(label, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
					GpuFormat.RGBA8_UNORM, source.getWidth(), source.getHeight(), 1, 1);
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(tex, source);
			var view = RenderSystem.getDevice().createTextureView(tex);
			ownedTextures.add(tex);
			ownedViews.add(view);
			Img img = new Img(label, null);
			img.sampled = ((VulkanGpuTextureView) view).vkImageView();
			return img;
		}
	}

	// ---- descriptor sets -----------------------------------------------------------------------------------------------

	private long buildSet(Manifest.FlipState flip) {
		long set = pool.allocate(pipelines.setLayout);
		var res = bindingResource[flip.id()];
		for (var r : dim.resources()) {
			switch (r.kind()) {
				case "uniform_buffer_dynamic" -> {
					int size = r.binding() == 0 ? manifest.uniforms().size() : manifest.uniforms().phase().size();
					Descriptors.writeBuffer(ctx, set, r.binding(), VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, ring.buffer.handle, 0, Math.max(16, size));
				}
				case "acceleration_structure" -> Descriptors.writeAccelerationStructure(ctx, set, r.binding(), RtScene.get().tlasHandle());
				case "storage_buffer" -> {
					if ("emrion_rt_sections".equals(r.builtin())) { // host-written per frame by RtScene, read only by ray queries
						Descriptors.writeBuffer(ctx, set, r.binding(), VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, RtScene.get().sectionTable().handle, 0, VK_WHOLE_SIZE);
						continue;
					}
					Buf b = buffers.get(r.target());
					if (b == null) continue;
					res[r.binding()] = b;
					Descriptors.writeBuffer(ctx, set, r.binding(), VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, b.buffer.handle, 0, VK_WHOLE_SIZE);
				}
				case "storage_image" -> {
					// shortcut: colorimgN binds the flip state's read side, as sampled colortex do (Iris semantics unverified)
					Img img = read(r.target(), flip);
					if (img == null) continue;
					res[r.binding()] = img;
					Descriptors.writeImage(ctx, set, r.binding(), VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, img.attach, VK_NULL_HANDLE);
				}
				case "sampled_image" -> {
					Img img = r.target() != null ? read(r.target(), flip) : r.texture() != null ? textures.getOrDefault(r.texture(), dummy)
							: builtins.getOrDefault(r.builtin(), dummy);
					if (img == null) img = dummy;
					if (img.target != null) res[r.binding()] = img;
					Manifest.SamplerDef def = r.sampler();
					if (def == null && "block_atlas".equals(r.builtin())) def = ATLAS_SAMPLER;
					if (def == null) {
						var tex = r.texture() == null ? null : manifest.textures().stream().filter(t -> t.name().equals(r.texture())).findFirst().orElse(null);
						def = new Manifest.SamplerDef(tex == null ? "nearest" : tex.filter(), "none", tex == null ? "repeat" : tex.wrap(), null);
					}
					Descriptors.writeImage(ctx, set, r.binding(), VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, img.sampled, sampler(def));
				}
				default -> throw new IllegalArgumentException("unknown resource kind " + r.kind());
			}
		}
		return set;
	}

	private long sampler(Manifest.SamplerDef def) {
		return samplers.computeIfAbsent(def, d -> {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				int filter = "linear".equals(d.filter()) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
				int wrap = "repeat".equals(d.wrap()) ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
				boolean mip = d.mipmap() != null && !d.mipmap().equals("none");
				var info = VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(filter).minFilter(filter)
						.mipmapMode("linear".equals(d.mipmap()) ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
						.addressModeU(wrap).addressModeV(wrap).addressModeW(wrap).maxAnisotropy(1).minLod(0).maxLod(mip ? VK_LOD_CLAMP_NONE : 0);
				if (d.compare() != null) info.compareEnable(true).compareOp(compareOp(d.compare()));
				var p = stack.mallocLong(1);
				if (vkCreateSampler(ctx.vk, info, null, p) != VK_SUCCESS) throw new IllegalStateException("vkCreateSampler failed");
				return p.get(0);
			}
		});
	}

	/** The sampler of the per-draw PBR maps (Iris: labPBR specular is not interpolated). shortcut: no anisotropy (Iris's terrain sampler has it). */
	long pbrSampler(boolean specular) {
		return sampler(specular && PbrAtlases.labPbr() ? LABPBR_SPECULAR_SAMPLER : ATLAS_SAMPLER);
	}

	long nearestSampler() {
		return sampler(new Manifest.SamplerDef("nearest", "none", "clamp", null));
	}

	static int compareOp(String f) {
		return switch (f) {
			case "never" -> VK_COMPARE_OP_NEVER;
			case "less" -> VK_COMPARE_OP_LESS;
			case "equal" -> VK_COMPARE_OP_EQUAL;
			case "lequal" -> VK_COMPARE_OP_LESS_OR_EQUAL;
			case "greater" -> VK_COMPARE_OP_GREATER;
			case "notequal" -> VK_COMPARE_OP_NOT_EQUAL;
			case "gequal" -> VK_COMPARE_OP_GREATER_OR_EQUAL;
			case "always" -> VK_COMPARE_OP_ALWAYS;
			default -> throw new IllegalArgumentException("unknown compare func " + f);
		};
	}

	/** Releases everything after the frames in flight retired. The pipelines are shared and released by the owner. */
	public void destroyLater() {
		if (pool != null) pool.destroyLater();
		if (ring != null) ring.destroyLater();
		for (Img[] sides : images.values()) for (Img img : sides) if (img != null) release(img);
		buffers.values().forEach(b -> b.buffer.destroyLater());
		ownedViews.forEach(GpuTextureView::close);
		ownedTextures.forEach(GpuTexture::close);
		List<Long> s = List.copyOf(samplers.values());
		ctx.destroyLater(() -> s.forEach(h -> vkDestroySampler(ctx.vk, h, null)));
	}

	private static void release(Img img) {
		if (img.raw != null) img.raw.destroyLater();
		if (img.attachView != null && img.attachView != img.sampleView) img.attachView.close();
		if (img.sampleView != null) img.sampleView.close();
		if (img.tex != null) img.tex.close();
	}
}
