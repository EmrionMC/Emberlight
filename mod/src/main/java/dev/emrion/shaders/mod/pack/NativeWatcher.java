package dev.emrion.shaders.mod.pack;

import dev.emrion.shaders.mod.EmrionShadersClient;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Hot reload of native packs (docs/NATIVE_FORMAT.md §19): watches the selected pack folder and reloads it on changes. */
final class NativeWatcher {
	private NativeWatcher() {}

	private static @Nullable WatchService service;

	/** Stops the previous watch; starts one when {@code dir} is a native pack folder. */
	static synchronized void watch(@Nullable Path dir) {
		if (service != null) {
			try {
				service.close(); // ends the thread (ClosedWatchServiceException)
			} catch (IOException ignored) {
			}
			service = null;
		}
		if (dir == null || !Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("emberlight.json"))) return;
		WatchService ws = null;
		try {
			ws = FileSystems.getDefault().newWatchService();
			register(ws, dir);
			service = ws;
			WatchService started = ws;
			Thread.ofPlatform().daemon().name("Emberlight pack watcher").start(() -> loop(started));
			EmrionShadersClient.LOG.info("Watching {} for changes (hot reload)", dir);
		} catch (IOException | UncheckedIOException e) {
			EmrionShadersClient.LOG.warn("Cannot watch {} for hot reload", dir, e);
			if (ws != null) try {
				ws.close();
			} catch (IOException ignored) {
			}
		}
	}

	/** Registers {@code dir} and every directory below it (a folder moved in arrives with its subfolders). */
	private static void register(WatchService ws, Path dir) throws IOException {
		try (Stream<Path> dirs = Files.walk(dir)) {
			for (Path d : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator)
				d.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
		}
	}

	private static void loop(WatchService ws) {
		try {
			while (true) {
				WatchKey key = ws.take();
				// an editor's save is several events (temp file, rename, modify): collect until 300 ms of quiet
				while (key != null) {
					for (var e : key.pollEvents())
						if (e.context() instanceof Path p && key.watchable() instanceof Path parent && Files.isDirectory(parent.resolve(p))) {
							try {
								register(ws, parent.resolve(p));
							} catch (IOException | UncheckedIOException gone) {
								// deleted again before it was walked: nothing to watch
							}
						}
					key.reset();
					key = ws.poll(300, TimeUnit.MILLISECONDS);
				}
				EmrionShadersClient.LOG.info("Native pack changed on disk: reloading");
				Minecraft.getInstance().execute(PackManager::reload);
			}
		} catch (InterruptedException | ClosedWatchServiceException e) {
			// watch stopped
		}
	}
}
