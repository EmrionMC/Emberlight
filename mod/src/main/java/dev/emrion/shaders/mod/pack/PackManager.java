package dev.emrion.shaders.mod.pack;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.converter.DefaultConverter;
import dev.emrion.shaders.converter.cache.CacheDir;
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
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.runtime.FrameExecutor;
import dev.emrion.shaders.mod.runtime.PackPipelines;
import dev.emrion.shaders.mod.runtime.PackRuntime;
import dev.emrion.shaders.mod.uniforms.StubUniformSystem;
import dev.emrion.shaders.mod.uniforms.UniformSystem;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

	private record Loaded(Path dir, Manifest manifest) {}
	private record Prepared(int generation, String folder, PackPipelines pipelines) {}

	private static volatile @Nullable Loaded loaded;
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
		if (Files.isRegularFile(source.resolve(CacheDir.MANIFEST))) return new Loaded(source, ManifestJson.readManifest(source.resolve(CacheDir.MANIFEST)));

		Path settings = SHADERPACKS.resolve(pack + ".txt");
		if (!Files.isRegularFile(settings)) settings = SHADERPACKS.resolve(pack.replaceFirst("\\.zip$", "") + ".txt");
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
		return new Loaded(result.outputDir(), result.manifest());
	}

	// ---- render-thread hooks ------------------------------------------------------------------------------------------

	/** GameRenderer.renderLevel HEAD. */
	public static void onFrameStart(float partialTick) {
		try {
			var mc = Minecraft.getInstance();
			Loaded l = loaded;
			String folder = l == null || mc.level == null ? null : Dimensions.folder(l.manifest(), mc.level.dimension().identifier().toString());
			if (folder == null || current != null && !folder.equals(current.folder())) deactivate();
			if (folder != null && (current == null || !folder.equals(current.folder()))) {
				Prepared p = prepared;
				if (p != null && p.generation() == generation && p.folder().equals(folder)) install(l, p, mc);
				else if (!preparing) prepare(l, folder);
			}
			if (runtime != null) {
				var main = mc.gameRenderer.mainRenderTarget();
				if (main.width != runtime.width || main.height != runtime.height) resize(l, main.width, main.height);
				executor.frameStart(partialTick);
			}
		} catch (Throwable t) {
			fail("Shader pack " + selected + " failed", t);
		}
	}

	/** Before render3dHud clears MC's depth. */
	public static void onBeforeHud() {
		if (executor == null) return;
		try {
			executor.beforeHud();
		} catch (Throwable t) {
			fail("Shader pack " + selected + " failed", t);
		}
	}

	/** GameRenderer.renderLevel TAIL. */
	public static void onAfterLevel() {
		if (executor == null) return;
		try {
			executor.afterLevel();
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
		WORKER.execute(() -> {
			try {
				long start = System.nanoTime();
				var pipelines = new PackPipelines(ctx, l.dir(), l.manifest(), l.manifest().dimensions().get(folder));
				EmrionShadersClient.LOG.info("Built {} pipelines for {}/{} in {} ms", pipelines.pipelines.size(), selected, folder, (System.nanoTime() - start) / 1_000_000);
				if (gen == generation) prepared = new Prepared(gen, folder, pipelines);
				else pipelines.destroyLater();
			} catch (Throwable t) {
				if (gen == generation) fail("Could not build pipelines for " + selected, t);
			} finally {
				Minecraft.getInstance().execute(() -> preparing = false);
			}
		});
	}

	private static void install(Loaded l, Prepared p, Minecraft mc) {
		prepared = null;
		current = p;
		uniforms = uniformFactory.apply(l.manifest(), p.folder());
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
