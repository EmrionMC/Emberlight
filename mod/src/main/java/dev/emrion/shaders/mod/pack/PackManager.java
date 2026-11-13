package dev.emrion.shaders.mod.pack;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.converter.DefaultConverter;
import dev.emrion.shaders.converter.cache.CacheDir;
import dev.emrion.shaders.converter.options.IrisSettings;
import dev.emrion.shaders.converter.expr.CustomUniformParser;
import dev.emrion.shaders.converter.glsl.GlslTranspiler;
import dev.emrion.shaders.converter.pack.DefaultPackLoader;
import dev.emrion.shaders.converter.pack.PackSource;
import dev.emrion.shaders.converter.pack.StandardMacros;
import dev.emrion.shaders.converter.plan.IrisDimensionPlanner;
import dev.emrion.shaders.converter.plan.Std140LayoutBuilder;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.spirv.ShadercSpirvCompiler;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.ManifestJson;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.pbr.PbrAtlases;
import dev.emrion.shaders.mod.pipeline.GbufferMapping;
import dev.emrion.shaders.mod.runtime.FrameExecutor;
import dev.emrion.shaders.mod.runtime.PackPipelines;
import dev.emrion.shaders.mod.runtime.PackRuntime;
import dev.emrion.shaders.mod.uniforms.StubUniformSystem;
import dev.emrion.shaders.mod.uniforms.IrisUniformSystem;
import dev.emrion.shaders.mod.uniforms.UniformSystem;
import dev.emrion.shaders.mod.vertex.BlockIdTable;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Pack selection, async conversion and the active {@link PackRuntime} (ARCHITECTURE §7). Packs live in
 * {@code .minecraft/shaderpacks} (zips and folders, Iris settings in {@code <pack>.txt} next to them); a folder that already
 * holds a {@code manifest.json} is used as a converted pack directly. The choice is stored in
 * {@code config/emrionshaders.properties}; {@code -Demrion.pack=<name>} overrides it (autotests).
 * Any failure while loading or running disables the pack, reports it in chat and the log, and MC renders vanilla.
 */
public final class PackManager {
	private PackManager() {}

	/** Seam for agent (u): replace with the real uniform system. */
	public static java.util.function.BiFunction<Manifest, String, UniformSystem> uniformFactory =
			dev.emrion.shaders.mod.uniforms.IrisUniformSystem::forManifest;

	private static final Path GAME = FabricLoader.getInstance().getGameDir();
	public static final Path SHADERPACKS = GAME.resolve("shaderpacks");
	private static final Path CACHE = GAME.resolve("emrionshaders").resolve("cache");
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("emrionshaders.properties");
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Emrion pack loader");
		t.setDaemon(true);
		return t;
	});

	/** Bumped on every selection; background results of an older generation are dropped. */
	private static volatile int generation;
	private static volatile @Nullable String selected;
	public static volatile String status = "Off";
	public static volatile float progress = -1;
	public static final List<String> errors = new ArrayList<>();

	/** @param lang the pack's shaders/lang/*.lang, language code (lowercase) → entries */
	private record Loaded(Path dir, Manifest manifest, Map<String, Map<String, String>> lang) {}
	private record Prepared(int generation, Loaded source, String folder, PackPipelines pipelines) {}

	private static volatile @Nullable Loaded loaded;
	/** A reload's result: replaces {@link #loaded} once its pipelines are built, so the old pack renders until then. */
	private static volatile @Nullable Loaded next;
	/** Completed reload swaps (autotest). */
	public static volatile int swaps;
	private static volatile @Nullable Prepared prepared;
	private static boolean preparing;

	// render-thread state
	private static @Nullable Prepared current;
	private static @Nullable PackRuntime runtime;
	private static @Nullable FrameExecutor executor;
	private static @Nullable UniformSystem uniforms;

	// ---- selection --------------------------------------------------------------------------------------------------

	public static List<String> packs() {
		try (Stream<Path> s = Files.list(SHADERPACKS)) {
			return s.filter(p -> Files.isDirectory(p) || p.toString().endsWith(".zip")).map(p -> p.getFileName().toString()).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	public static @Nullable String selected() {
		return selected;
	}

	/** Called once on the render thread at client start. */
	public static void init() {
		String pack = System.getProperty("emrion.pack");
		if (pack == null) {
			Properties p = new Properties();
			try (Reader r = Files.newBufferedReader(CONFIG)) {
				p.load(r);
			} catch (IOException ignored) {
				// no config yet: off
			}
			pack = p.getProperty("pack", "");
		}
		if (!pack.isBlank()) select(pack, false);
	}

	/** {@code null} = off. */
	public static void select(@Nullable String pack, boolean save) {
		int gen = ++generation;
		selected = pack;
		loaded = null;
		next = null;
		Prepared stale = prepared;
		if (stale != null) stale.pipelines().destroyLater();
		prepared = null;
		synchronized (errors) {
			errors.clear();
		}
		if (save) saveConfig(pack);
		if (pack == null) {
			status = "Off";
			progress = -1;
			return;
		}
		status = "Loading " + pack;
		progress = 0;
		WORKER.execute(() -> {
			try {
				Loaded l = load(pack, gen);
				if (gen != generation) return;
				loaded = l;
				status = pack + ": loaded";
				progress = -1;
			} catch (Throwable t) {
				if (gen == generation) fail("Could not load shader pack " + pack, t);
			}
		});
	}

	private static void saveConfig(@Nullable String pack) {
		Properties p = new Properties();
		p.setProperty("pack", pack == null ? "" : pack);
		try {
			Files.createDirectories(CONFIG.getParent());
			try (Writer w = Files.newBufferedWriter(CONFIG)) {
				p.store(w, "Emrion Shaders");
			}
		} catch (IOException e) {
			EmrionShadersClient.LOG.warn("Could not save {}", CONFIG, e);
		}
	}

	// ---- conversion (background) --------------------------------------------------------------------------------------

	private static Loaded load(String pack, int gen) throws IOException {
		Path source = SHADERPACKS.resolve(pack);
		if (!Files.exists(source)) throw new IOException("no such pack in " + SHADERPACKS);
		source = source.toRealPath(); // the converter's PackSource does not follow a symlinked pack folder
		if (Files.isRegularFile(source.resolve(CacheDir.MANIFEST))) return new Loaded(source, ManifestJson.readManifest(source.resolve(CacheDir.MANIFEST)), Map.of());

		Path settings = settingsFile(pack);
		if (!Files.isRegularFile(settings)) settings = null;
		long start = System.nanoTime();
		Converter.Result result;
		try (var compiler = new ShadercSpirvCompiler()) {
			var env = StandardMacros.Environment.DEFAULT; // shortcut: this machine's GPU/OS macros; read them from the device for other machines
			var converter = new DefaultConverter(PackSource::open, new DefaultPackLoader(), CustomUniformParser::parse, new GlslTranspiler(),
					new Std140LayoutBuilder(), new IrisDimensionPlanner(), compiler,
					new Manifest.Environment(env.mcVersion(), StandardMacros.build(env)), Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
			result = converter.convert(new Converter.Request(source, settings, CACHE, null, (stage, done, total) -> {
				if (gen != generation) return;
				status = "Converting " + pack + ": " + stage + " " + done + "/" + total;
				progress = total == 0 ? 0 : (float) done / total;
			}));
		}
		List<Diagnostic> errs = result.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList();
		synchronized (errors) {
			errs.forEach(d -> errors.add(d.toString()));
		}
		errs.forEach(d -> EmrionShadersClient.LOG.error("[{}] {}", pack, d));
		EmrionShadersClient.LOG.info("Converted {} in {} ms{}: {} stages ok, {} failed, {} errors, cache {}", pack, (System.nanoTime() - start) / 1_000_000,
				result.cacheHit() ? " (cache hit)" : "", result.stagesCompiled(), result.stagesFailed(), errs.size(), result.outputDir());
		if (result.manifest() == null) throw new IOException(errs.isEmpty() ? "conversion failed" : errs.getFirst().message());
		if (result.manifest().dimensions().isEmpty()) throw new IOException("the converted pack has no programs (is it a shader pack?)");
		if (!errs.isEmpty()) chat(Component.literal("Emrion Shaders: " + pack + " converted with " + errs.size()
				+ " errors (failed programs are skipped, see report.json): " + errs.getFirst().message()).withStyle(ChatFormatting.YELLOW));
		return new Loaded(result.outputDir(), result.manifest(), readLang(source));
	}

	/** Iris LanguageMap: every shaders/lang/*.lang, UTF-8 properties, keyed by the lowercased file name. */
	private static Map<String, Map<String, String>> readLang(Path source) {
		Map<String, Map<String, String>> out = new java.util.HashMap<>();
		try (PackSource src = PackSource.open(source)) {
			for (String path : src.list()) {
				if (!path.startsWith("shaders/lang/") || !path.endsWith(".lang") || path.indexOf('/', 13) >= 0) continue;
				Properties p = new Properties();
				p.load(new java.io.StringReader(new String(src.read(path).orElseThrow(), java.nio.charset.StandardCharsets.UTF_8)));
				Map<String, String> m = new java.util.HashMap<>();
				p.forEach((k, v) -> m.put((String) k, (String) v));
				out.put(path.substring(13, path.length() - 5).toLowerCase(java.util.Locale.ROOT), m);
			}
		} catch (Exception e) {
			EmrionShadersClient.LOG.warn("Could not read the lang files of {}", source, e);
		}
		return out;
	}

	/** The Iris settings file of a pack: {@code <pack>.txt} (Iris's name, ".zip" included), or an existing one without ".zip". */
	public static Path settingsFile(String pack) {
		Path f = SHADERPACKS.resolve(pack + ".txt");
		Path alt = SHADERPACKS.resolve(pack.replaceFirst("\\.zip$", "") + ".txt");
		return !Files.exists(f) && Files.exists(alt) ? alt : f;
	}

	// ---- options and reload -------------------------------------------------------------------------------------------

	/** Options GUI state for the active pack (the newest loaded version), or null while nothing is loaded. */
	public static dev.emrion.shaders.mod.gui.@Nullable OptionSession optionSession() {
		Loaded l = next != null ? next : loaded;
		if (l == null || l.manifest().options() == null) return null;
		return new dev.emrion.shaders.mod.gui.OptionSession(l.manifest().options(), l.lang(),
				Minecraft.getInstance().getLanguageManager().getSelected().toLowerCase(java.util.Locale.ROOT));
	}

	/** Writes the Iris settings file for these option values (as Iris would) and, if it changed, reloads the pack. */
	public static void applyOptions(Map<String, String> values) {
		Loaded l = next != null ? next : loaded;
		String pack = selected;
		if (l == null || pack == null) return;
		Path file = settingsFile(pack);
		try {
			var settings = IrisSettings.nonDefault(l.manifest().options().declared(), values, IrisSettings.read(file));
			if (!IrisSettings.update(file, settings)) return;
			EmrionShadersClient.LOG.info("Wrote {} ({} non-default values)", file, settings.size());
		} catch (IOException e) {
			EmrionShadersClient.LOG.error("Could not write {}", file, e);
			chat(Component.literal("Emrion Shaders: could not write " + file.getFileName() + ": " + e.getMessage()).withStyle(ChatFormatting.RED));
			return;
		}
		reload();
	}

	/**
	 * Reconverts the selected pack from disk (pack files or settings changed; the cache key covers both, so any change
	 * misses the cache). The current version keeps rendering until the new one's pipelines are built; on failure it stays.
	 */
	public static void reload() {
		String pack = selected;
		if (pack == null) return;
		int gen = ++generation;
		next = null;
		synchronized (errors) {
			errors.clear();
		}
		status = "Reloading " + pack;
		progress = 0;
		long start = System.nanoTime();
		WORKER.execute(() -> {
			try {
				Loaded l = load(pack, gen);
				Minecraft.getInstance().execute(() -> {
					if (gen != generation) return;
					if (current == null) loaded = l;
					else next = l;
					status = pack + ": converted in " + (System.nanoTime() - start) / 1_000_000 + " ms";
					progress = -1;
				});
			} catch (Throwable t) {
				if (gen == generation) reloadFailed(pack, t);
			}
		});
	}

	private static void reloadFailed(String pack, Throwable t) {
		EmrionShadersClient.LOG.error("Reloading {} failed", pack, t);
		next = null;
		status = "Reload of " + pack + " failed: " + t.getMessage();
		progress = -1;
		synchronized (errors) {
			errors.add(String.valueOf(t));
		}
		chat(Component.literal("Emrion Shaders: reloading " + pack + " failed (" + t.getMessage() + "). "
				+ (loaded != null ? "The previous version keeps running; d" : "D") + "etails in the log.").withStyle(ChatFormatting.RED));
	}

	// ---- render-thread hooks ------------------------------------------------------------------------------------------

	/** The executor while it renders a level (between LevelRenderer.render HEAD and the end of the plan), else null. */
	public static @Nullable FrameExecutor levelExecutor() {
		FrameExecutor e = executor;
		return e != null && e.inLevel() ? e : null;
	}

	/** True while a pack is installed and renders the world. */
	public static boolean running() {
		return executor != null;
	}

	/** LevelRenderer.render HEAD (the level projection is known; Iris: beginLevelRendering). */
	public static void onLevelStart(float partialTick) {
		try {
			var mc = Minecraft.getInstance();
			String dim = mc.level == null ? null : mc.level.dimension().identifier().toString();
			Loaded l = loaded;
			String folder = l == null || dim == null ? null : Dimensions.folder(l.manifest(), dim);
			if (folder == null || current != null && !folder.equals(current.folder())) deactivate();
			Loaded n = next;
			if (n != null && current == null) { // nothing to keep showing: the reload result simply becomes the pack
				loaded = l = n;
				next = n = null;
				folder = dim == null ? null : Dimensions.folder(l.manifest(), dim);
			}
			Prepared p = prepared;
			if (n != null) { // reload: keep rendering `current` until n's pipelines are ready, then swap within one frame
				String nf = Dimensions.folder(n.manifest(), dim);
				if (nf == null) {
					deactivate();
					loaded = n;
					next = null;
				} else if (p != null && p.generation() == generation && p.source() == n && p.folder().equals(nf)) swap(n, p, mc);
				else if (!preparing) prepare(n, nf);
			} else if (folder != null && (current == null || !folder.equals(current.folder()))) {
				if (p != null && p.generation() == generation && p.source() == l && p.folder().equals(folder)) install(l, p, mc, true);
				else if (!preparing) prepare(l, folder);
			}
			if (runtime != null) {
				var main = mc.gameRenderer.mainRenderTarget();
				if (main.width != runtime.width || main.height != runtime.height || runtime.atlasGeneration != PbrAtlases.generation()) resize(loaded, main.width, main.height);
				executor.levelStart(partialTick);
			}
		} catch (Throwable t) {
			fail("Shader pack " + selected + " failed", t);
		}
	}

	/** After the level's frame graph: composites and final. */
	public static void onLevelEnd() {
		FrameExecutor e = levelExecutor();
		if (e == null) return;
		try {
			e.finish();
		} catch (Throwable t) {
			fail("Shader pack " + selected + " failed", t);
		}
	}

	/** Builds the size-independent part (pipelines) in the background; the swap happens at a later frame start. */
	private static void prepare(Loaded l, String folder) {
		preparing = true;
		int gen = generation;
		VulkanContext ctx = VulkanContext.get();
		status = selected + ": building pipelines (" + folder + ")";
		// render thread: MC compiles a vanilla pipeline here if it has not yet (the gbuffers copy needs its formats and bindings)
		Map<RenderPipeline, FrontendRenderPipeline> vanilla = new IdentityHashMap<>();
		for (RenderPipeline p : GbufferMapping.worldPipelines()) {
			if (RenderSystem.getCompiledPipelineNullable(p) instanceof FrontendRenderPipeline f) vanilla.put(p, f);
		}
		WORKER.execute(() -> {
			try {
				long start = System.nanoTime();
				var pipelines = new PackPipelines(ctx, l.dir(), l.manifest(), l.manifest().dimensions().get(folder), vanilla);
				EmrionShadersClient.LOG.info("Built {} pipelines for {}/{} in {} ms", pipelines.pipelines.size(), selected, folder, (System.nanoTime() - start) / 1_000_000);
				Minecraft.getInstance().execute(() -> {
					Prepared old = prepared;
					if (gen != generation) {
						pipelines.destroyLater();
						return;
					}
					if (old != null) old.pipelines().destroyLater();
					prepared = new Prepared(gen, l, folder, pipelines);
				});
			} catch (Throwable t) {
				if (gen != generation) return;
				if (l == next) reloadFailed(selected, t);
				else fail("Could not build pipelines for " + selected, t);
			} finally {
				Minecraft.getInstance().execute(() -> preparing = false);
			}
		});
	}

	/** Replaces the running version with the reloaded one between two frames; chunks rebuild only if their meshes change. */
	private static void swap(Loaded n, Prepared p, Minecraft mc) {
		Loaded o = loaded;
		boolean sameMeshes = o != null && java.util.Objects.equals(o.manifest().idMaps(), n.manifest().idMaps())
				&& meshFlags(o).equals(meshFlags(n));
		if (sameMeshes) deactivateKeepingFormats();
		else deactivate();
		loaded = n;
		next = null;
		install(n, p, mc, !sameMeshes);
		swaps++;
		EmrionShadersClient.LOG.info("Reload swapped in {} (chunk rebuild: {})", n.dir().getFileName(), !sameMeshes);
	}

	private static List<Object> meshFlags(Loaded l) {
		var props = l.manifest().properties() == null ? Map.<String, Object>of() : l.manifest().properties();
		return java.util.Arrays.asList(props.get("separateAo"), props.get("oldLighting"));
	}

	private static void install(Loaded l, Prepared p, Minecraft mc, boolean meshes) {
		prepared = null;
		current = p;
		uniforms = uniformFactory.apply(l.manifest(), p.folder());
		var ids = meshes ? BlockIdTable.build(l.manifest().idMaps()) : EmrionVertexFormats.ids();
		if (meshes) {
			var props = l.manifest().properties() == null ? Map.<String, Object>of() : l.manifest().properties();
			EmrionVertexFormats.setLighting(Boolean.TRUE.equals(props.get("separateAo")), Boolean.TRUE.equals(props.get("oldLighting")));
			EmrionVertexFormats.setIds(ids);
			EmrionVertexFormats.setActive(true); // rebuilds chunks in the extended terrain format
		}
		if (uniforms instanceof IrisUniformSystem u) u.setBlockIdLookup(ids::block);
		var main = mc.gameRenderer.mainRenderTarget();
		resize(l, main.width, main.height);
		status = selected + ": active (" + p.folder() + ")";
		EmrionShadersClient.LOG.info("Shader pack {} active in {}", selected, p.folder());
	}

	private static void resize(Loaded l, int width, int height) {
		if (runtime != null) {
			executor.close();
			runtime.destroyLater();
		}
		runtime = null;
		executor = null;
		var dim = l.manifest().dimensions().get(current.folder());
		runtime = new PackRuntime(VulkanContext.get(), current.pipelines(), l.dir(), l.manifest(), dim, uniforms, width, height);
		executor = new FrameExecutor(runtime);
	}

	private static void deactivate() {
		if (current != null) EmrionVertexFormats.setActive(false);
		deactivateKeepingFormats();
	}

	private static void deactivateKeepingFormats() {
		if (executor != null) executor.close();
		if (runtime != null) runtime.destroyLater();
		if (current != null) current.pipelines().destroyLater();
		executor = null;
		runtime = null;
		current = null;
		uniforms = null;
	}

	/** Disables the pack (until selected again), tells the user, logs the stack trace; MC renders vanilla from the next frame. */
	private static void fail(String what, Throwable t) {
		EmrionShadersClient.LOG.error(what, t);
		String pack = selected;
		generation++;
		loaded = null;
		next = null;
		prepared = null;
		selected = null;
		status = what + ": " + t;
		progress = -1;
		synchronized (errors) {
			errors.add(String.valueOf(t));
		}
		chat(Component.literal("Emrion Shaders: " + what + " (" + t.getMessage() + "). Back to vanilla rendering; details in the log.")
				.withStyle(ChatFormatting.RED));
		Minecraft.getInstance().execute(() -> {
			try {
				deactivate();
			} catch (Throwable inner) {
				EmrionShadersClient.LOG.error("Tearing down {} failed too", pack, inner);
			}
		});
	}

	private static void chat(Component message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.chatListener().handleSystemMessage(message, false));
	}
}
