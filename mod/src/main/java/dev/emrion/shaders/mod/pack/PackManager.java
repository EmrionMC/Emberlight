package dev.emrion.shaders.mod.pack;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.converter.DefaultConverter;
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
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.pbr.PbrAtlases;
import dev.emrion.shaders.mod.pbr.PbrTextures;
import dev.emrion.shaders.mod.pipeline.GbufferMapping;
import dev.emrion.shaders.mod.pipeline.GeometryPipelines;
import dev.emrion.shaders.mod.rt.RtCapability;
import dev.emrion.shaders.mod.rt.RtConfig;
import dev.emrion.shaders.mod.rt.RtScene;
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

/** Pack selection, async conversion and the active {@link PackRuntime}. Any failure disables the pack and MC renders vanilla. */
public final class PackManager {
	private PackManager() {}

	public static java.util.function.BiFunction<Manifest, String, UniformSystem> uniformFactory =
			dev.emrion.shaders.mod.uniforms.IrisUniformSystem::forManifest;

	private static final Path GAME = FabricLoader.getInstance().getGameDir();
	public static final Path SHADERPACKS = GAME.resolve("shaderpacks");
	private static final Path CACHE = GAME.resolve("emberlight").resolve("cache");
	private static final Path CONFIG = EmrionShadersClient.configFile();
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Emberlight pack loader");
		t.setDaemon(true);
		return t;
	});

	/** MC's missing vanilla pipelines compile here, never on the chunk worker pool (see PipelineCacheMixin). */
	public static final ExecutorService PIPELINE_COMPILER = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
			new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
		Thread t = new Thread(r, "Emberlight pipeline compiler");
		t.setDaemon(true);
		return t;
	}) {
		private long start;

		@Override
		protected void beforeExecute(Thread t, Runnable r) {
			start = System.nanoTime();
		}

		@Override
		protected void afterExecute(Runnable r, Throwable t) {
			dev.emrion.shaders.mod.debug.HitchLog.add(dev.emrion.shaders.mod.debug.HitchLog.COMPILE, start);
		}
	};
	/** Vanilla pipelines being compiled for {@link #prepare}, and the MC cache they go into. */
	private static @Nullable Map<RenderPipeline, java.util.concurrent.CompletableFuture<CompiledRenderPipeline.Pending>> compiling;
	private static com.mojang.blaze3d.pipeline.@Nullable PipelineCache compilingFor;

	/** Bumped on every selection; background results of an older generation are dropped. */
	private static volatile int generation;
	/** The newest load task; a newer selection cancels it (queued: never runs, running: interrupted). */
	private static java.util.concurrent.@Nullable Future<?> loading;
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
	/** Pipelines the worker built whose render-thread hand-off has not run yet (freed by {@link #shutdown} if it never will). */
	private static volatile @Nullable PackPipelines unclaimed;
	private static boolean preparing;
	/** The active pack's ambientOcclusionLevel (Iris: scales MC's baked AO, 1 = vanilla); read on chunk workers. */
	public static volatile float aoLevel = 1f;
	/** Toggle key (Iris K): false renders vanilla while the pack stays selected; its pipelines are kept for a fast switch back. */
	private static volatile boolean enabled = true;
	/** Iris's debug mode (Ctrl+D in the pack screen): every shader error in chat, profiler overlay on; enables wireframe. */
	private static volatile boolean debug;

	// render-thread state
	private static @Nullable Prepared current;
	private static @Nullable PackRuntime runtime;
	private static @Nullable FrameExecutor executor;
	private static @Nullable UniformSystem uniforms;

	// ---- selection --------------------------------------------------------------------------------------------------

	public static List<String> packs() {
		try (Stream<Path> s = Files.list(SHADERPACKS)) {
			return s.map(p -> p.getFileName().toString()).filter(n -> !n.startsWith(PackInstaller.TMP_PREFIX))
					.filter(n -> Files.isDirectory(SHADERPACKS.resolve(n)) || n.endsWith(".zip")).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	public static @Nullable String selected() {
		return selected;
	}

	/** Called once on the render thread at client start. */
	public static void init() {
		Properties p = readConfig();
		RtConfig.load(p);
		dev.emrion.shaders.mod.upscale.UpscaleConfig.load(p);
		dev.emrion.shaders.mod.framegen.FrameGenConfig.load(p);
		dev.emrion.shaders.mod.framegen.Reflex.load(p);
		dev.emrion.shaders.mod.hdr.Hdr.load(p);
		if (Boolean.parseBoolean(p.getProperty("debug"))) {
			debug = true;
			dev.emrion.shaders.mod.debug.Profiler.visible = true;
		}
		dev.emrion.shaders.mod.debug.HitchLog.enabled = Boolean.parseBoolean(System.getProperty("emrion.profiler.hitchLog", p.getProperty("profiler.hitchLog", "true")).trim());
		String culling = System.getProperty("emrion.perf.shadowReceivers", p.getProperty("perf.shadowReceivers", "all"));
		if (dev.emrion.shaders.mod.runtime.ReceiverGrid.MODES.contains(culling)) dev.emrion.shaders.mod.runtime.ReceiverGrid.mode = culling;
		String pack = System.getProperty("emrion.pack", p.getProperty("pack", ""));
		if (!pack.isBlank()) select(pack, false);
	}

	private static Properties readConfig() {
		return EmrionShadersClient.readProperties(CONFIG);
	}

	/** {@code null} = off. */
	public static void select(@Nullable String pack, boolean save) {
		int gen = ++generation;
		selected = pack;
		enabled = true;
		loaded = null;
		next = null;
		Prepared stale = prepared;
		if (stale != null) stale.pipelines().destroyLater();
		prepared = null;
		synchronized (errors) {
			errors.clear();
		}
		if (save) saveConfig("pack", pack == null ? "" : pack);
		if (pack == null) {
			submit(null);
			status = "Off";
			progress = -1;
			dev.emrion.shaders.mod.gui.LoadingHud.clear();
			return;
		}
		status = "Loading " + pack;
		progress = 0;
		dev.emrion.shaders.mod.gui.LoadingHud.begin(pack);
		submit(() -> {
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

	/** Queues a load, cancelling the superseded one so a slow (or hostile) pack does not hold up the newer selection. */
	private static synchronized void submit(@Nullable Runnable task) {
		if (loading != null) loading.cancel(true);
		loading = task == null ? null : WORKER.submit(task);
	}

	/** Sets one key, keeping the others. */
	public static void saveConfig(String key, String value) {
		Properties p = readConfig();
		p.setProperty(key, value);
		writeConfig(p);
	}

	private static void writeConfig(Properties p) {
		try {
			Files.createDirectories(CONFIG.getParent());
			try (Writer w = Files.newBufferedWriter(CONFIG)) {
				p.store(w, "Emberlight");
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
		// every pack is converted: a manifest.json in a downloaded folder is never trusted (its paths and SPIR-V are unchecked)

		Path settings = settingsFile(pack);
		if (!Files.isRegularFile(settings)) settings = null;
		long start = System.nanoTime();
		Converter.Result result;
		PackSource[] snapshot = new PackSource[1]; // the pack as converted, reused for its lang files (in memory: readable after close)
		try (var compiler = new ShadercSpirvCompiler()) {
			var env = StandardMacros.Environment.DEFAULT; // shortcut: this machine's GPU/OS macros; read them from the device for other machines
			var converter = new DefaultConverter(p -> snapshot[0] = PackSource.open(p), new DefaultPackLoader(), CustomUniformParser::parse, new GlslTranspiler(),
					new Std140LayoutBuilder(), new IrisDimensionPlanner(), compiler,
					new Manifest.Environment(env.mcVersion(), StandardMacros.build(env)), Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
			// RT off: an empty set, the exact Iris-equivalent request (same cache key and output)
			result = converter.convert(new Converter.Request(source, settings, CACHE, null, (stage, done, total) -> {
				if (gen != generation) return;
				status = "Converting " + pack + ": " + stage + " " + done + "/" + total;
				progress = total == 0 ? 0 : (float) done / total;
				dev.emrion.shaders.mod.gui.LoadingHud.stage(pack, stage, done, total);
			}, true, request(), net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")));
		}
		if (gen != generation) throw new java.util.concurrent.CancellationException(); // superseded: its errors are not the new pack's
		List<Diagnostic> errs = result.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).distinct().toList(); // distinct: one per dimension otherwise
		synchronized (errors) {
			errs.forEach(d -> errors.add(d.toString()));
		}
		errs.forEach(d -> EmrionShadersClient.LOG.error("[{}] {}", pack, d));
		EmrionShadersClient.LOG.info("Converted {} in {} ms{}: {} stages ok, {} failed, {} errors, cache {}", pack, (System.nanoTime() - start) / 1_000_000,
				result.cacheHit() ? " (cache hit)" : "", result.stagesCompiled(), result.stagesFailed(), errs.size(), result.outputDir());
		if (result.manifest() == null) throw new IOException(errs.isEmpty() ? "conversion failed" : where(errs.getFirst()));
		if (result.manifest().rt() != null) // also on a cache hit: pack authors iterating on their RT table read this
			result.manifest().rt().unavailable().forEach((e, why) -> EmrionShadersClient.LOG.warn("[{}] RT {} unavailable: {}", pack, e, why));
		if (result.manifest().dimensions().isEmpty()) throw new IOException(errs.isEmpty() ? "it has no shader programs" : where(errs.getFirst()));
		if (debug) errs.stream().limit(20).forEach(d -> chat(Component.literal("[" + pack + "] " + where(d)).withStyle(ChatFormatting.RED)));
		dev.emrion.shaders.mod.gui.LoadingHud.shaderErrors(errs.size()); // the failing programs are skipped; the panel says so, the log has them

		return new Loaded(result.outputDir(), result.manifest(), readLang(snapshot[0]));
	}

	/** "file:line [program.stage]: message" for chat. */
	private static String where(Diagnostic d) {
		return (d.file() == null ? "" : d.file() + (d.line() > 0 ? ":" + d.line() : "") + " ")
				+ (d.program() == null ? "" : "[" + d.program() + (d.stage() == null ? "" : "." + d.stage()) + "] ") + d.message();
	}

	/** Iris LanguageMap: every shaders/lang/*.lang, UTF-8 properties, keyed by the lowercased file name. */
	private static Map<String, Map<String, String>> readLang(PackSource src) {
		Map<String, Map<String, String>> out = new java.util.HashMap<>();
		try {
			for (String path : src.list()) {
				if (!path.startsWith("shaders/lang/") || !path.endsWith(".lang") || path.indexOf('/', 13) >= 0) continue;
				Properties p = new Properties();
				p.load(new java.io.StringReader(new String(src.read(path).orElseThrow(), java.nio.charset.StandardCharsets.UTF_8)));
				Map<String, String> m = new java.util.HashMap<>();
				p.forEach((k, v) -> m.put((String) k, (String) v));
				out.put(path.substring(13, path.length() - 5).toLowerCase(java.util.Locale.ROOT), m);
			}
		} catch (Exception e) {
			EmrionShadersClient.LOG.warn("Could not read the lang files of {}", src.name(), e);
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
	public enum Applied { FAILED, UNCHANGED, RELOADING }

	public static Applied applyOptions(Map<String, String> values) {
		Loaded l = next != null ? next : loaded;
		String pack = selected;
		if (l == null || pack == null) return Applied.FAILED;
		Path file = settingsFile(pack);
		try {
			var settings = IrisSettings.nonDefault(l.manifest().options().declared(), values, IrisSettings.read(file));
			if (!IrisSettings.update(file, settings)) return Applied.UNCHANGED;
			EmrionShadersClient.LOG.info("Wrote {} ({} non-default values)", file, settings.size());
		} catch (IOException e) {
			EmrionShadersClient.LOG.error("Could not write {}", file, e);
			chat(Component.literal("Emberlight: could not write " + file.getFileName() + ": " + e.getMessage()).withStyle(ChatFormatting.RED));
			return Applied.FAILED;
		}
		reload();
		return Applied.RELOADING;
	}

	/** Reconverts the selected pack; the current one keeps rendering until the new pipelines are built, and stays on failure. */
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
		dev.emrion.shaders.mod.gui.LoadingHud.begin(pack);
		long start = System.nanoTime();
		submit(() -> {
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
		dev.emrion.shaders.mod.gui.LoadingHud.failed(pack, t.getMessage() + (loaded != null ? " (previous version kept)" : ""));
	}

	// ---- RT -----------------------------------------------------------------------------------------------------------

	/** The converter's effect set: the RT effects plus "upscale" while an upscaler is selected (empty = plain conversion). */
	private static java.util.Set<String> request() {
		java.util.Set<String> s = new java.util.TreeSet<>(RtConfig.request());
		dev.emrion.shaders.mod.upscale.UpscaleConfig.addRequest(s);
		return s;
	}

	/** Saves the upscaling options: a reload when the converter request changes (Off <-> on), else a runtime rebuild. */
	public static boolean applyUpscale() {
		dev.emrion.shaders.mod.upscale.Upscaling.settingsChanged();
		return applyRt();
	}

	/** Saves the frame-generation mode; it takes effect at the next frame, no reload. */
	public static void applyFrameGen() {
		Properties p = readConfig();
		dev.emrion.shaders.mod.framegen.FrameGenConfig.store(p);
		dev.emrion.shaders.mod.framegen.Reflex.store(p);
		dev.emrion.shaders.mod.hdr.Hdr.store(p);
		writeConfig(p);
		dev.emrion.shaders.mod.framegen.FrameGen.requestReset();
	}

	/** Saves the HDR options; the swapchain is recreated at the next frame, no reload. */
	public static void applyHdr() {
		Properties p = readConfig();
		dev.emrion.shaders.mod.hdr.Hdr.store(p);
		writeConfig(p);
		dev.emrion.shaders.mod.hdr.Hdr.settingsChanged();
	}

	public static boolean applyRt() {
		Properties p = readConfig();
		RtConfig.store(p);
		dev.emrion.shaders.mod.upscale.UpscaleConfig.store(p);
		dev.emrion.shaders.mod.framegen.FrameGenConfig.store(p);
		dev.emrion.shaders.mod.framegen.Reflex.store(p);
		dev.emrion.shaders.mod.hdr.Hdr.store(p);
		writeConfig(p);
		Loaded l = next != null ? next : loaded;
		java.util.Set<String> want = request();
		java.util.Set<String> have = l == null || l.manifest().rt() == null ? java.util.Set.of() : rtRequestOf(l.manifest());
		if (want.equals(have) || selected == null) return false;
		reload();
		return true;
	}

	/** The effect set a manifest was converted with: applied plus unavailable effects (both were requested). */
	private static java.util.Set<String> rtRequestOf(Manifest m) {
		// shortcut: the request is not stored in the manifest; applied entries keep their options, unavailable ones only the name
		java.util.Set<String> s = new java.util.TreeSet<>(m.rt().effects());
		s.addAll(m.rt().unavailable().keySet());
		return s;
	}

	/** The RT section of the newest loaded version (null: converted without RT). */
	public static Manifest.@Nullable Rt rtState() {
		Loaded l = next != null ? next : loaded;
		return l == null ? null : l.manifest().rt();
	}

	// ---- render-thread hooks ------------------------------------------------------------------------------------------

	/** The executor while it renders a level (between LevelRenderer.render HEAD and the end of the plan), else null. */
	public static @Nullable FrameExecutor levelExecutor() {
		FrameExecutor e = executor;
		return e != null && e.inLevel() && selected != null ? e : null; // a failure clears selected: vanilla from the next hook on
	}

	/** True while a pack is installed and renders the world. */
	public static boolean running() {
		return executor != null && selected != null;
	}

	public static boolean enabled() {
		return enabled;
	}

	/** Takes effect at the next level frame start ({@link #onLevelStart}). */
	public static void setEnabled(boolean on) {
		enabled = on;
		if (selected != null && progress < 0) status = selected + (on ? ": on" : ": off (toggle key)");
	}

	public static boolean debugMode() {
		return debug;
	}

	public static void setDebugMode(boolean on) {
		debug = on;
		dev.emrion.shaders.mod.debug.Profiler.visible = on;
		if (!on) setWireframe(false);
		saveConfig("debug", Boolean.toString(on));
	}

	/** Pack geometry as lines (Iris wireframe key; the caller checks debug mode and singleplayer): rebuilds the pipelines. */
	public static void setWireframe(boolean on) {
		if (GeometryPipelines.wireframe == on) return;
		GeometryPipelines.wireframe = on;
		reload();
	}

	/** The running pack's sunPathRotation (Iris API), 0 without one. */
	public static float sunPathRotation() {
		FrameExecutor e = executor;
		return e == null || selected == null || e.dimension().shadow() == null ? 0 : e.dimension().shadow().sunPathRotation();
	}

	/** A geometry hook failed in the middle of a frame: disable the pack; the rest of the frame renders vanilla. */
	public static void frameFailed(Throwable t) {
		if (selected != null) fail("Shader pack " + selected + " failed", t);
	}

	/** LevelRenderer.render HEAD (the level projection is known; Iris: beginLevelRendering). */
	public static void onLevelStart(float partialTick) {
		try {
			if (!enabled) {
				park();
				return;
			}
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
				if (main.width != runtime.displayWidth || main.height != runtime.displayHeight || runtime.atlasGeneration != PbrAtlases.generation()
						|| runtime.upscaleGeneration != dev.emrion.shaders.mod.upscale.Upscaling.generation()) resize(loaded, main.width, main.height);
				else residencyRetry(main.width, main.height);
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
		status = selected + ": building pipelines (" + folder + ")";
		dev.emrion.shaders.mod.gui.LoadingHud.phase(selected, "Building pipelines");
		// the gbuffers copies need the vanilla pipelines' formats and bindings; frames go on (old pack or vanilla) until MC has them
		Map<RenderPipeline, FrontendRenderPipeline> vanilla = vanillaPipelines();
		if (vanilla == null) return;
		preparing = true;
		int gen = generation;
		VulkanContext ctx = VulkanContext.get();
		WORKER.execute(() -> {
			try {
				long start = System.nanoTime();
				var pipelines = new PackPipelines(ctx, l.dir(), l.manifest(), l.manifest().dimensions().get(folder), vanilla);
				EmrionShadersClient.LOG.info("Built {} pipelines for {}/{} in {} ms", pipelines.pipelines.size(), selected, folder, (System.nanoTime() - start) / 1_000_000);
				unclaimed = pipelines;
				Minecraft.getInstance().execute(() -> {
					unclaimed = null;
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

	/** Compiled vanilla world pipelines, or null while compiling: never block, a blocking compile can deadlock against chunk workers. */
	private static @Nullable Map<RenderPipeline, FrontendRenderPipeline> vanillaPipelines() {
		List<RenderPipeline> world = new java.util.ArrayList<>(GbufferMapping.worldPipelines());
		if (dev.emrion.shaders.mod.compat.SodiumTerrain.LOADED) world.addAll(dev.emrion.shaders.mod.compat.SodiumTerrain.pipelines());
		Map<RenderPipeline, FrontendRenderPipeline> vanilla = new IdentityHashMap<>();
		var cache = dev.emrion.shaders.mod.mixin.RenderSystemAccessor.emrion$currentPipelineCache();
		if (cache == null) { // no resources loaded yet: MC's fallback path, compiled on our thread (PipelineCacheMixin)
			for (RenderPipeline p : world) if (RenderSystem.getCompiledPipelineNullable(p) instanceof FrontendRenderPipeline f) vanilla.put(p, f);
			return vanilla;
		}
		var have = ((dev.emrion.shaders.mod.mixin.PipelineCacheAccessor) cache).emrion$cache();
		if (compiling != null && compilingFor != cache) { // another resource reload: its compiles used the old shader sources
			compiling.values().forEach(f -> f.thenAccept(pending -> Minecraft.getInstance().execute(() -> close(pending))));
			compiling = null;
		}
		if (compiling == null) {
			compiling = new IdentityHashMap<>();
			compilingFor = cache;
			var source = ((dev.emrion.shaders.mod.mixin.PipelineCacheAccessor) cache).emrion$shaderSource();
			for (RenderPipeline p : world) if (!have.containsKey(p)) compiling.put(p, RenderSystem.getDevice().compilePipeline(p, source, PIPELINE_COMPILER));
		}
		if (!compiling.values().stream().allMatch(java.util.concurrent.CompletableFuture::isDone)) return null;
		compiling.forEach((p, f) -> {
			try {
				CompiledRenderPipeline c = f.join().finishCompile();
				if (c == null) return;
				if (have.containsKey(p)) c.close(); // MC's fallback PipelineCache.get compiled it meanwhile
				else cache.insert(p, c);
			} catch (RuntimeException e) {
				EmrionShadersClient.LOG.error("Vanilla pipeline {} did not compile", p.getLocation(), e);
			}
		});
		compiling = null;
		for (RenderPipeline p : world) if (have.get(p) instanceof FrontendRenderPipeline f) vanilla.put(p, f);
		return vanilla;
	}

	private static void close(CompiledRenderPipeline.Pending pending) {
		try {
			CompiledRenderPipeline c = pending.finishCompile();
			if (c != null) c.close();
		} catch (RuntimeException ignored) {}
	}

	/** Replaces the running version with the reloaded one between two frames; chunks rebuild only if their meshes change. */
	private static void swap(Loaded n, Prepared p, Minecraft mc) {
		long start = System.nanoTime();
		Loaded o = loaded;
		boolean sameMeshes = o != null && java.util.Objects.equals(o.manifest().idMaps(), n.manifest().idMaps())
				&& meshFlags(o).equals(meshFlags(n));
		if (sameMeshes) deactivateKeepingFormats();
		else deactivate();
		loaded = n;
		next = null;
		install(n, p, mc, !sameMeshes);
		swaps++;
		dev.emrion.shaders.mod.debug.HitchLog.add(dev.emrion.shaders.mod.debug.HitchLog.PACK_SWAP, start);
		EmrionShadersClient.LOG.info("Reload swapped in {} (chunk rebuild: {})", n.dir().getFileName(), !sameMeshes);
	}

	private static List<Object> meshFlags(Loaded l) {
		var props = l.manifest().properties() == null ? Map.<String, Object>of() : l.manifest().properties();
		return java.util.Arrays.asList(props.get("separateAo"), props.get("oldLighting"), props.get("ambientOcclusionLevel"));
	}

	private static void install(Loaded l, Prepared p, Minecraft mc, boolean meshes) {
		prepared = null;
		current = p;
		uniforms = uniformFactory.apply(l.manifest(), p.folder());
		var ids = meshes ? BlockIdTable.build(l.manifest().idMaps()) : EmrionVertexFormats.ids();
		if (meshes) {
			var props = l.manifest().properties() == null ? Map.<String, Object>of() : l.manifest().properties();
			EmrionVertexFormats.setLighting(Boolean.TRUE.equals(props.get("separateAo")), Boolean.TRUE.equals(props.get("oldLighting")));
			aoLevel = props.get("ambientOcclusionLevel") instanceof Number n ? Math.clamp(n.floatValue(), 0f, 1f) : 1f;
			EmrionVertexFormats.setIds(ids);
			EmrionVertexFormats.setActive(true); // rebuilds chunks in the extended terrain format
		}
		if (uniforms instanceof IrisUniformSystem u) u.setBlockIdLookup(ids::block);
		// the RT runtime owns the scene: created before its descriptor sets (TLAS, section table), gone with a non-RT runtime
		var rt = l.manifest().rt();
		if (rt != null && rt.effects().stream().anyMatch(e -> !e.equals("upscale")) && RtCapability.enabled) {
			dev.emrion.shaders.mod.rt.RtLights.configure(l.manifest().options() == null ? Map.of() : l.manifest().options().values());
			RtScene.activate().setEntities(RtConfig.wantsEntities(rt.effects())).setAnyHitIds(rt.anyHitIds());
		}
		else if (!RtCapability.DEBUG) RtScene.shutdown();
		var main = mc.gameRenderer.mainRenderTarget();
		resize(l, main.width, main.height);
		status = selected + ": active (" + p.folder() + ")";
		dev.emrion.shaders.mod.gui.LoadingHud.ready(selected);
		EmrionShadersClient.LOG.info("Shader pack {} active in {}", selected, p.folder());
	}

	private static String offDeviceLabel;
	private static int offDeviceFrames, offDeviceRetries;

	/** A target landed in system memory (VRAM full mid reload): recreate the targets after ~2 s, at most 3 tries. */
	private static void residencyRetry(int width, int height) {
		String l = dev.emrion.shaders.mod.vk.VramResidency.takeOffDevice();
		if (l != null) {
			offDeviceLabel = l;
			offDeviceFrames = 0;
		}
		if (offDeviceLabel == null || ++offDeviceFrames < 120) return;
		if (offDeviceRetries >= 3) {
			EmrionShadersClient.LOG.warn("Pack target {} is still in system memory after 3 tries: VRAM is full, expect very low FPS", offDeviceLabel);
			offDeviceLabel = null;
			return;
		}
		offDeviceRetries++;
		dev.emrion.shaders.mod.debug.HitchLog.count(dev.emrion.shaders.mod.debug.HitchLog.VRAM_RETRY, 1);
		EmrionShadersClient.LOG.warn("Pack target {} landed in system memory (VRAM was full); recreating the targets (try {})", offDeviceLabel, offDeviceRetries);
		offDeviceLabel = null;
		resize(loaded, width, height);
		if (dev.emrion.shaders.mod.vk.VramResidency.takeOffDevice() == null) offDeviceRetries = 0;
		else offDeviceLabel = "(still)";
	}

	private static void resize(Loaded l, int width, int height) {
		long start = System.nanoTime();
		try {
			resizeTimed(l, width, height);
		} finally {
			dev.emrion.shaders.mod.debug.HitchLog.add(dev.emrion.shaders.mod.debug.HitchLog.TARGETS, start);
		}
	}

	private static void resizeTimed(Loaded l, int width, int height) {
		if (runtime != null) {
			executor.close();
			runtime.destroyLater();
		}
		runtime = null;
		executor = null;
		var dim = l.manifest().dimensions().get(current.folder());
		// upscale only once the running version was converted for it (requested: applied, or unavailable for this pack)
		var mrt = l.manifest().rt();
		boolean up = mrt != null && (mrt.effects().contains("upscale") || mrt.unavailable().containsKey("upscale"))
				&& dev.emrion.shaders.mod.upscale.UpscaleConfig.effective() != dev.emrion.shaders.mod.upscale.UpscaleConfig.Mode.OFF;
		int[] render = up ? dev.emrion.shaders.mod.upscale.Upscaling.renderSize(width, height) : new int[] {width, height};
		dev.emrion.shaders.mod.upscale.Upscaling.setSizes(render[0], render[1], width, height, up);
		try {
			runtime = new PackRuntime(VulkanContext.get(), current.pipelines(), l.dir(), l.manifest(), dim, uniforms, render[0], render[1], width, height);
		} catch (RuntimeException e) {
			if (!dev.emrion.shaders.mod.upscale.Upscaling.active()) throw e;
			if (dev.emrion.shaders.mod.upscale.UpscaleConfig.rayReconstruction && dev.emrion.shaders.mod.upscale.UpscaleConfig.effective()
					== dev.emrion.shaders.mod.upscale.UpscaleConfig.Mode.DLSS && String.valueOf(e.getMessage()).contains("Ray Reconstruction")) {
				// RR just recorded why it cannot run: DLSS SR now, and a reconversion that turns our denoisers back on
				EmrionShadersClient.LOG.error("DLSS Ray Reconstruction unavailable ({}), using DLSS Super Resolution", dev.emrion.shaders.mod.upscale.Dlss.rrUnavailable());
				resize(l, width, height);
				applyRt();
				return;
			}
			if (dev.emrion.shaders.mod.upscale.UpscaleConfig.mode != dev.emrion.shaders.mod.upscale.UpscaleConfig.Mode.FSR
					&& dev.emrion.shaders.mod.upscale.UpscaleConfig.effective() == dev.emrion.shaders.mod.upscale.UpscaleConfig.Mode.FSR) {
				// DLSS or XeSS just recorded why it cannot run: same render size and conversion with FSR (no per-frame retry, see Dlss)
				EmrionShadersClient.LOG.error("{} unavailable ({}), using FSR", dev.emrion.shaders.mod.upscale.UpscaleConfig.mode.label,
						dev.emrion.shaders.mod.upscale.UpscaleConfig.mode == dev.emrion.shaders.mod.upscale.UpscaleConfig.Mode.XESS
								? dev.emrion.shaders.mod.upscale.Xess.unavailable() : dev.emrion.shaders.mod.upscale.Dlss.unavailable());
				resize(l, width, height);
				return;
			}
			EmrionShadersClient.LOG.error("Upscaler failed, rendering without it", e);
			dev.emrion.shaders.mod.upscale.Upscaling.setSizes(width, height, width, height, false);
			runtime = new PackRuntime(VulkanContext.get(), current.pipelines(), l.dir(), l.manifest(), dim, uniforms, width, height, width, height);
		}
		executor = new FrameExecutor(runtime);
	}

	/** Waits for a running build, then queues the pack's GPU objects for destruction before MC destroys the device. */
	public static boolean shutdown() {
		generation++;
		WORKER.shutdown();
		boolean done;
		try {
			done = WORKER.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			done = false;
		}
		PIPELINE_COMPILER.shutdown(); // drain: its native shader-module/pipeline creation must not outlive the device
		try {
			done &= PIPELINE_COMPILER.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			done = false;
		}
		if (!done) EmrionShadersClient.LOG.warn("Shader pack build still running at exit; not waiting for it any longer");
		var pending = compiling; // finished vanilla compiles nobody will claim now
		compiling = null;
		if (pending != null) pending.values().forEach(f -> {
			if (f.isDone() && !f.isCompletedExceptionally()) close(f.join());
		});
		PackPipelines u = unclaimed; // built, but its hand-off to the render thread will never run now
		unclaimed = null;
		if (u != null) u.destroyLater();
		Prepared p = prepared;
		prepared = null;
		if (p != null) p.pipelines().destroyLater();
		deactivateKeepingFormats(); // switching the vertex formats back would rebuild every chunk while MC shuts down
		return done;
	}

	private static void deactivate() {
		if (current != null) EmrionVertexFormats.setActive(false);
		aoLevel = 1f;
		deactivateKeepingFormats();
		PbrTextures.clear(); // entity/item maps load lazily again when a pack runs
		PbrAtlases.releaseLazy();
		if (!RtCapability.DEBUG) RtScene.shutdown();
		dev.emrion.shaders.mod.runtime.ForcedSections.clear();
	}

	/** Toggled off: vanilla rendering, but the pipelines stay as {@link #prepared}, so switching back only re-installs them. */
	private static void park() {
		Prepared p = current;
		if (p == null) return;
		aoLevel = 1f;
		EmrionVertexFormats.setActive(false);
		current = null; // deactivate() then keeps p's pipelines
		deactivate();
		Prepared old = prepared;
		if (old != null) old.pipelines().destroyLater();
		prepared = p;
		EmrionShadersClient.LOG.info("Shader pack {} toggled off (pipelines kept)", selected);
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

	/** Disables the pack (until selected again), reports in chat, logs the stack trace; MC renders vanilla from the next frame. */
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
		dev.emrion.shaders.mod.gui.LoadingHud.failed(pack != null ? pack : "Emberlight", what + ": " + t.getMessage() + ", back to vanilla rendering");
		Minecraft.getInstance().execute(() -> {
			try {
				deactivate();
			} catch (Throwable inner) {
				EmrionShadersClient.LOG.error("Tearing down {} failed too", pack, inner);
			}
		});
	}

	/** Messages from before a world was joined (MC drops chat on the title screen); render thread only. */
	private static final List<Component> pendingChat = new ArrayList<>();

	private static void chat(Component message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			pendingChat.add(message);
			flushChat(mc);
		});
	}

	/** Client tick: shows the queued messages once a world is joined. */
	public static void flushChat(Minecraft mc) {
		if (mc.player == null || pendingChat.isEmpty()) return;
		pendingChat.forEach(m -> mc.gui.chatListener().handleSystemMessage(m, false));
		pendingChat.clear();
	}
}
