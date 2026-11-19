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
import dev.emrion.shaders.mod.vk.RawCommands;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/**
 * GPU state of one pack dimension at one window size: targets, custom textures, samplers and one pre-built set-1 descriptor
 * set per flip state. A window resize builds a new PackRuntime from the same {@link PackPipelines}.
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
		/** Single-level views for mip generation, made on first use. */
		GpuTextureView[] levelViews;

		/** A view of mip {@code level} only (null for raw images). */
		long levelView(int level) {
			if (tex == null) return 0;
			if (levelViews == null) levelViews = new GpuTextureView[mips];
			if (levelViews[level] == null) levelViews[level] = RenderSystem.getDevice().createTextureView(tex, level, 1);
			return ((VulkanGpuTextureView) levelViews[level]).vkImageView();
		}

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
	/** Render size (= display size unless an upscaler runs) and the window size final draws at. */
	public final int width, height, displayWidth, displayHeight;
	UboRing ring;
	/** The upscaler before final, its colour input and output, and final's descriptor set reading the output; null when off. */
	dev.emrion.shaders.mod.upscale.Upscaler upscaler;
	Img upscaleInput, upscaled;
	long finalSet;
	/** Reactive and transparency/composition masks for the upscaler (views, 0 = none), see {@link #initMasks}. */
	Img masks;
	long compositionMask;
	/** {@link #masks} and its view after the next {@link #swapFlippedTargets} (the same if colortex2 is not swapped). */
	private Img otherMasks;
	private long otherCompositionMask;
	/** DLSS Ray Reconstruction: final's flip state, to look up Complementary's normal and smoothness targets (null: from depth only). */
	Manifest.FlipState rrFlip;
	/** A native pack's RR guides (diffuse albedo, specular albedo, normal + roughness), or null. */
	Img @org.jspecify.annotations.Nullable [] rrNative;
	private final Map<String, Img> setOverride = new HashMap<>();
	/** {@link dev.emrion.shaders.mod.upscale.Upscaling#generation()} this runtime was built for; a settings change rebuilds it. */
	public final int upscaleGeneration = dev.emrion.shaders.mod.upscale.Upscaling.generation();

	final Map<String, Img[]> images = new HashMap<>();
	final Map<String, Buf> buffers = new HashMap<>();
	private final Map<String, Img> textures = new HashMap<>();
	private final Map<Manifest.SamplerDef, Long> samplers = new HashMap<>();
	private final List<GpuTexture> ownedTextures = new ArrayList<>();
	private final List<GpuTextureView> ownedViews = new ArrayList<>();
	private final List<RawImage> rawTextures = new ArrayList<>();
	private Img dummy;
	/** Builtin resources. */
	private final Map<String, Img> builtins = new HashMap<>();
	private DescriptorPool pool;
	/** RT AO pass (RT variants with the "ao" effect only), run before the pass that samples its output. */
	@org.jspecify.annotations.Nullable RtAoPass rtAo;
	/** {@link PbrAtlases#generation()} of the bound atlas views; the runtime must be rebuilt when it changes. */
	public final int atlasGeneration = PbrAtlases.generation();
	/** flip state id → descriptor set. */
	long[] sets;
	/** flip state id → binding → resource the binding reads (for barriers); null for UBOs and unresolved bindings. */
	Hazards.Tracked[][] bindingResource;
	String[] bindingKind;

	public PackRuntime(VulkanContext ctx, PackPipelines pipelines, Path cacheDir, Manifest manifest, Manifest.Dimension dim,
			UniformSystem uniforms, int width, int height, int displayWidth, int displayHeight) {
		this.ctx = ctx;
		this.pipelines = pipelines;
		this.manifest = manifest;
		this.dim = dim;
		this.uniforms = uniforms;
		this.width = width;
		this.height = height;
		this.displayWidth = displayWidth;
		this.displayHeight = displayHeight;
		try {
			init(cacheDir);
		} catch (RuntimeException | Error failed) { // e.g. out of GPU memory half-way: release what was allocated
			destroyLater();
			throw failed;
		}
	}

	private void init(Path cacheDir) {
		long align = ctx.limits().minUniformBufferOffsetAlignment();
		boolean upscale = dev.emrion.shaders.mod.upscale.Upscaling.active();
		// + final's own frame block (window-size viewWidth/viewHeight) while upscaling
		ring = new UboRing(ctx, manifest.uniforms().size() + FrameExecutor.PHASE_BLOCKS * (manifest.uniforms().phase().size() + align) + align
				+ (upscale ? manifest.uniforms().size() + align : 0));

		java.util.Set<String> storage = new java.util.HashSet<>();
		for (var r : dim.resources()) if (r.kind().equals("storage_image")) storage.add(r.target());
		for (var t : dim.targets()) createTarget(t, storage.contains(t.name()));
		dummy = mcTexture("emrion white", solid(0xFFFFFFFF));
		builtins.put("white", dummy);
		PbrAtlases.request();
		var atlas = PbrAtlases.blockAtlas();
		builtins.put("block_atlas", atlas != null ? view("block atlas", atlas) : dummy);
		builtins.put("white_depth", whiteDepth());
		// LabPBR maps of the block atlas for ray hits (native el_hitSpecular); without a resource pack the LabPBR defaults
		if (dim.resources().stream().anyMatch(r -> "block_atlas_specular".equals(r.builtin()) || "block_atlas_normals".equals(r.builtin()))) {
			var n = PbrAtlases.normals();
			var s = PbrAtlases.specular();
			builtins.put("block_atlas_normals", n != null ? view("block atlas normals", n) : mcTexture("emrion default normals", solid(0xFFFF7F7F)));
			builtins.put("block_atlas_specular", s != null ? view("block atlas specular", s) : mcTexture("emrion default specular", solid(0)));
		}
		if (dim.resources().stream().anyMatch(r -> RtAoPass.BUILTIN.equals(r.builtin()))) {
			rtAo = new RtAoPass(ctx, width, height);
			builtins.put(RtAoPass.BUILTIN, rtAo.output);
		}
		long entityAtlas = RtScene.get() == null ? 0 : RtScene.get().entityAtlasView(); // RT entities (RtEntities), else unbound -> white
		if (entityAtlas != 0) {
			Img img = new Img("emrion rt entity atlas", null);
			img.sampled = entityAtlas;
			builtins.put("emrion_rt_entity_atlas", img);
		}
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
		if (upscale) for (int i = 1; i < sizes.length; i += 2) sizes[i] += sizes[i] / n; // final's extra set
		java.util.function.Function<String, byte[]> fragment = prog -> spv(cacheDir, dim.programs().get(prog).stages().fragment());
		// not proven to write every pixel: the fragment stage may discard, or the vertex stage moves the quad (bloom tiles)
		Map<String, Boolean> partial = new HashMap<>();
		java.util.function.Predicate<String> discards = prog -> partial.computeIfAbsent(prog, q -> FlipSwap.discards(fragment.apply(q))
				|| !FullscreenCoverage.covers(spv(cacheDir, fullscreenVertex(dim.programs().get(q)))));
		skipped = IDENTITY_SKIP ? IdentityCopies.skippable(dim, fragment, discards) : Set.of();
		if (!skipped.isEmpty()) dev.emrion.shaders.mod.EmrionShadersClient.LOG.info("Skipping identity copy passes {}",
				skipped.stream().map(i -> dim.frame().get(i).program()).toList());
		skip = new boolean[dim.frame().size()];
		for (int i : skipped) skip[i] = true;
		var kept = new ArrayList<Manifest.Command>();
		for (int i = 0; i < dim.frame().size(); i++) if (!skipped.contains(i)) kept.add(dim.frame().get(i));
		var withoutSkipped = new Manifest.Dimension(dim.programs(), dim.resources(), dim.targets(), dim.flipStates(), kept, dim.once(), dim.shadow());
		swapped = FLIP_SWAP ? FlipSwap.swappable(withoutSkipped, discards, upscale ? cutTarget(dim) : null) : Set.of();
		int parities = swapped.isEmpty() ? 1 : 2; // one set of descriptor sets per image assignment
		for (int i = 1; i < sizes.length; i += 2) sizes[i] *= parities;
		pool = new DescriptorPool(ctx, (upscale ? n + 1 : n) * parities, sizes);
		for (var fs : dim.flipStates()) sets[fs.id()] = buildSet(fs);
		if (upscale) initUpscaler();
		if (!swapped.isEmpty()) {
			long[] sets0 = sets;
			Hazards.Tracked[][] res0 = bindingResource;
			long final0 = finalSet;
			swapSides();
			sets = new long[sets0.length];
			bindingResource = new Hazards.Tracked[res0.length][res0[0].length];
			for (var fs : dim.flipStates()) sets[fs.id()] = buildSet(fs);
			if (upscaler != null) finalSet = buildFinalSet();
			otherSets = sets;
			otherResources = bindingResource;
			otherFinalSet = finalSet;
			swapSides();
			sets = sets0;
			bindingResource = res0;
			finalSet = final0;
			dev.emrion.shaders.mod.EmrionShadersClient.LOG.info("End-of-frame copies replaced by image swaps: {}", swapped);
		}
	}

	/** -Demrion.perf.flipSwap=false: keep Iris's end-of-frame alt → main copies instead of swapping (A/B switch). */
	private static final boolean FLIP_SWAP = !"false".equals(System.getProperty("emrion.perf.flipSwap"));
	/** -Demrion.perf.identitySkip=false: run fullscreen passes that copy a colortex to alt and straight back (A/B switch). */
	private static final boolean IDENTITY_SKIP = !"false".equals(System.getProperty("emrion.perf.identitySkip"));
	/** Frame command indices not run ({@link IdentityCopies}). */
	private Set<Integer> skipped = Set.of();
	/** Per frame command: not run. */
	boolean[] skip = new boolean[0];
	/** Targets whose end-of-frame copy is a swap of their two images ({@link FlipSwap}). */
	Set<String> swapped = Set.of();
	private long[] otherSets;
	private Hazards.Tracked[][] otherResources;
	private long otherFinalSet;

	private static byte[] spv(Path cacheDir, Manifest.SpvRef ref) {
		if (ref == null || ref.spv() == null) return new byte[0];
		try {
			return Files.readAllBytes(cacheDir.resolve(ref.spv()));
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	private static Manifest.SpvRef fullscreenVertex(Manifest.Program p) {
		var v = p.stages().vertex();
		return new Manifest.SpvRef(v == null ? null : v.variants().get("fullscreen"));
	}

	private void swapSides() {
		for (String t : swapped) {
			Img[] s = images.get(t);
			Img main = s[0];
			s[0] = s[1];
			s[1] = main;
		}
	}

	/** End of frame, instead of the alt → main copies: the alt images become main (and the other way round). */
	void swapFlippedTargets() {
		if (swapped.isEmpty()) return;
		swapSides();
		long[] s = sets;
		sets = otherSets;
		otherSets = s;
		Hazards.Tracked[][] r = bindingResource;
		bindingResource = otherResources;
		otherResources = r;
		long f = finalSet;
		finalSet = otherFinalSet;
		otherFinalSet = f;
		Img m = masks;
		masks = otherMasks;
		otherMasks = m;
		long cm = compositionMask;
		compositionMask = otherCompositionMask;
		otherCompositionMask = cm;
	}

	/** The cut target, the upscaler and final's set that samples its output instead of the target. */
	private void initUpscaler() {
		Manifest.Command fin = dim.frame().stream().filter(c -> c.cmd().equals("final")).findFirst().orElse(null);
		if (fin == null) return;
		var flip = flipState(fin.flipState());
		String target = cutTarget(dim, fin);
		upscaleInput = read(target, flip);
		if (upscaleInput == null) throw new IllegalStateException("upscaling: no " + target + " to upscale");
		var mrt = manifest.rt();
		// a native pack compiled without RR guides (NATIVE_FORMAT.md §14.4) gets DLSS SR: its own denoisers ran
		upscaler = dev.emrion.shaders.mod.upscale.Upscaler.create(ctx, width, height, displayWidth, displayHeight,
				dev.emrion.shaders.mod.upscale.UpscaleConfig.rrActive() && (mrt == null || !mrt.unavailable().containsKey("rr")));
		dev.emrion.shaders.mod.EmrionShadersClient.LOG.info("Upscaling {} {}x{} -> {}x{} (input {})", upscaler.getClass().getSimpleName() + (upscaler.rayReconstruction() ? " (Ray Reconstruction)" : ""), width, height, displayWidth, displayHeight, target);
		upscaled = new Img("emrion upscaled", null);
		upscaled.image = upscaler.output.image;
		upscaled.sampled = upscaled.attach = upscaler.output.view;
		upscaled.width = displayWidth;
		upscaled.height = displayHeight;
		upscaled.vkFormat = upscaler.output.format;
		finalSet = buildFinalSet();
		initMasks(flip);
		// Complementary (its upscale patch ran): colortex4 world normal, colortex6.r smoothness
		if (upscaler.rayReconstruction() && masks != null && read("colortex4", flip) != null && read("colortex6", flip) != null) rrFlip = flip;
		if (upscaler.rayReconstruction() && image("el_rrDiffuse", "main") != null) // a native pack's own guides
			rrNative = new Img[] {image("el_rrDiffuse", "main"), image("el_rrSpecular", "main"), image("el_rrNormal", "main")};
	}

	/**
	 * docs/UPSCALING.md §2: the Complementary upscale patch writes the masks into the pack's TAA history target (colortex2: r =
	 * reactive, g = transparency/composition) in place of its TAA; the g channel gets its own swizzled view.
	 */
	private void initMasks(Manifest.FlipState flip) {
		var rt = manifest.rt();
		String folder = manifest.dimensions().entrySet().stream().filter(e -> e.getValue() == dim).map(java.util.Map.Entry::getKey).findFirst().orElse("");
		// only Complementary's patch writes masks (Bliss/Eclipse's patch empties their TAA in composite6 too, colortex2 is theirs)
		if (rt == null || "chocapic-taa".equals(rt.table()) || !rt.patched().getOrDefault("upscale", List.of()).contains(folder + "/composite6.fsh")) return;
		Img masks = read("colortex2", flip);
		if (masks == null) return;
		this.masks = otherMasks = masks;
		compositionMask = otherCompositionMask = compositionView(masks);
		if (swapped.contains("colortex2")) { // composite6 writes the other physical image every other frame
			swapSides();
			otherMasks = read("colortex2", flip);
			swapSides();
			otherCompositionMask = compositionView(otherMasks);
		}
		dev.emrion.shaders.mod.EmrionShadersClient.LOG.info("Upscaling masks from colortex2 (reactive r, transparency/composition g)");
	}

	private long compositionView(Img masks) {
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			var info = org.lwjgl.vulkan.VkImageViewCreateInfo.calloc(stack).sType$Default().image(masks.image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(masks.vkFormat);
			info.components().r(VK_COMPONENT_SWIZZLE_G).g(VK_COMPONENT_SWIZZLE_G).b(VK_COMPONENT_SWIZZLE_G).a(VK_COMPONENT_SWIZZLE_G);
			info.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
			var p = stack.mallocLong(1);
			if (vkCreateImageView(ctx.vk, info, null, p) != VK_SUCCESS) throw new IllegalStateException("vkCreateImageView composition mask");
			return p.get(0);
		}
	}

	private long buildFinalSet() {
		Manifest.Command fin = dim.frame().stream().filter(c -> c.cmd().equals("final")).findFirst().orElseThrow();
		setOverride.put(cutTarget(dim, fin), upscaled);
		long set = buildSet(flipState(fin.flipState()));
		setOverride.clear();
		return set;
	}

	private static String cutTarget(Manifest.Dimension dim) {
		Manifest.Command fin = dim.frame().stream().filter(c -> c.cmd().equals("final")).findFirst().orElse(null);
		return fin == null ? null : cutTarget(dim, fin);
	}

	/** The target final samples that the last pass before it wrote (Complementary: colortex3); colortex0 without a final program. */
	static String cutTarget(Manifest.Dimension dim, Manifest.Command fin) {
		if (fin.program() == null) return "colortex0";
		java.util.Set<String> reads = new java.util.HashSet<>();
		java.util.Set<Integer> bindings = new java.util.HashSet<>(dim.programs().get(fin.program()).set1());
		for (var r : dim.resources()) if (r.target() != null && "sampled_image".equals(r.kind()) && bindings.contains(r.binding())) reads.add(r.target());
		for (int i = dim.frame().indexOf(fin) - 1; i >= 0; i--) {
			var c = dim.frame().get(i);
			if (c.writes() == null) continue;
			for (var w : c.writes()) if (reads.contains(w.target())) return w.target();
		}
		return "colortex0";
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
			sides[side] = img; // before allocating, so a failure releases what was created
			img.format = Formats.gpu(t.format());
			img.vkFormat = VulkanConst.toVk(img.format);
			img.width = s[0];
			img.height = s[1];
			img.depth = s[2];
			img.isDepth = t.kind().equals("depth");
			img.mips = t.mipLevels() == 0 ? Formats.fullMipChain(s[0], s[1], 1) : Math.max(1, t.mipLevels());
			if (t.kind().equals("image")) {
				// 3D and storage-only images: MC textures are 2D only
				img.raw = new RawImage(ctx, img.vkFormat, s[0], s[1], s[2], t.dims(), 1,
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
		}
	}

	/** The physical image of {@code target} on {@code side} ("main"/"alt"); null if the target does not exist. */
	Img image(String target, String side) {
		Img[] s = images.get(target);
		if (s == null) return null;
		return "alt".equals(side) && s[1] != null ? s[1] : s[0];
	}

	/** The side a pass in {@code flip} reads: alt for colortex (shadowcolor) in the flip state's alt (shadowAlt) list. */
	Img read(String target, Manifest.FlipState flip) {
		Matcher m = COLORTEX.matcher(target), s = SHADOWCOLOR.matcher(target);
		boolean alt = m.matches() && flip.alt().contains(Integer.parseInt(m.group(1)))
				|| s.matches() && flip.shadowAlt() != null && flip.shadowAlt().contains(Integer.parseInt(s.group(1)));
		return image(target, alt ? "alt" : "main");
	}

	/** 1×1 white RGBA8 view (unbound albedo). */
	long atlasView() {
		return builtins.get("block_atlas").sampled;
	}

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
			if (t.rawPixel() != null) return rawTexture(t, Files.readAllBytes(cacheDir.resolve(t.file())));
			if (t.resource() != null && !t.resource().startsWith("emrion:")) return resourceTexture(t);
			if (t.file() != null) return mcTexture("emrion " + t.name(), NativeImage.read(Files.readAllBytes(cacheDir.resolve(t.file()))));
			if ("emrion:noise".equals(t.resource())) return mcTexture("emrion noisetex", noise(t.size()[0], t.size()[1]));
		} catch (IOException e) {
			throw new IllegalStateException("custom texture " + t.name() + ": " + e.getMessage(), e);
		}
		return dummy; // shortcut: other emrion: resources are not resolved; add them if a pack samples one
	}

	/** A raw texture (2D or 3D): the converter already packed the file as texels of {@code t.format()}. */
	private Img rawTexture(Manifest.TextureDef t, byte[] data) {
		int[] s = {1, 1, 1};
		System.arraycopy(t.size(), 0, s, 0, t.size().length);
		Img img = new Img("emrion " + t.name(), null);
		img.raw = new RawImage(ctx, VulkanConst.toVk(Formats.gpu(t.format())), s[0], s[1], s[2], t.dims(), 1,
				VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, img.label);
		rawTextures.add(img.raw);
		img.image = img.raw.image;
		img.sampled = img.raw.view;
		var staging = new RawBuffer(ctx, data.length, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true, img.label + " upload");
		MemoryUtil.memByteBuffer(staging.mapped, data.length).put(data);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var region = VkBufferImageCopy.calloc(1, stack);
			region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.imageExtent().set(s[0], s[1], s[2]);
			var cb = RawCommands.begin(ctx);
			vkCmdCopyBufferToImage(cb, staging.handle, img.image, VK_IMAGE_LAYOUT_GENERAL, region);
			RawCommands.submit(ctx, cb);
		}
		staging.destroyLater();
		return img;
	}

	/** A {@code namespace:path} texture from MC's resources (Iris binds the TextureManager's texture), read once. */
	private Img resourceTexture(Manifest.TextureDef t) {
		try (var in = Minecraft.getInstance().getResourceManager().open(Identifier.parse(t.resource()))) {
			return mcTexture("emrion " + t.name(), NativeImage.read(in));
		} catch (IOException | RuntimeException e) {
			return dummy; // shortcut: atlases other than blocks, lightmap and _n/_s PBR resources are not resolved
		}
	}

	/** Iris's generated noise texture: random RGB, opaque; same seed, column-major order and ARGB packing as Iris. */
	private static NativeImage noise(int w, int h) {
		NativeImage img = new NativeImage(w, h, false);
		Random r = new Random(0);
		for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) img.setPixel(x, y, r.nextInt() | 0xFF000000);
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
		ownedTextures.add(tex); // owned before anything below can throw
		RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(tex, 1.0);
		var view = RenderSystem.getDevice().createTextureView(tex);
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
			ownedTextures.add(tex); // owned before the upload can throw
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(tex, source);
			var view = RenderSystem.getDevice().createTextureView(tex);
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
					if ("emrion_rt_lights".equals(r.builtin())) { // RT block-light grid, copied in by RtScene at levelStart
						Descriptors.writeBuffer(ctx, set, r.binding(), VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, RtScene.get().lightBuffer().handle, 0, VK_WHOLE_SIZE);
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
					Img img = r.target() != null ? setOverride.getOrDefault(r.target(), read(r.target(), flip)) : r.texture() != null ? textures.getOrDefault(r.texture(), dummy)
							: builtins.getOrDefault(r.builtin(), dummy);
					if (img == null) img = dummy;
					if (img.target != null || rtAo != null && img == rtAo.output) res[r.binding()] = img;
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
		if (rtAo != null) rtAo.destroyLater();
		if (upscaler != null) upscaler.destroyLater();
		long cm = compositionMask, ocm = otherCompositionMask;
		if (cm != 0) ctx.destroyLater(() -> vkDestroyImageView(ctx.vk, cm, null));
		if (ocm != 0 && ocm != cm) ctx.destroyLater(() -> vkDestroyImageView(ctx.vk, ocm, null));
		for (Img[] sides : images.values()) for (Img img : sides) if (img != null) release(img);
		buffers.values().forEach(b -> b.buffer.destroyLater());
		ownedViews.forEach(GpuTextureView::close);
		ownedTextures.forEach(GpuTexture::close);
		rawTextures.forEach(RawImage::destroyLater);
		List<Long> s = List.copyOf(samplers.values());
		ctx.destroyLater(() -> s.forEach(h -> vkDestroySampler(ctx.vk, h, null)));
	}

	private static void release(Img img) {
		if (img.raw != null) img.raw.destroyLater();
		if (img.levelViews != null) for (var v : img.levelViews) if (v != null) v.close();
		if (img.attachView != null && img.attachView != img.sampleView) img.attachView.close();
		if (img.sampleView != null) img.sampleView.close();
		if (img.tex != null) img.tex.close();
	}
}
