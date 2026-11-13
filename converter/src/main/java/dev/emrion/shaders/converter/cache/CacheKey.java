package dev.emrion.shaders.converter.cache;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.format.Manifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** cacheKey of FORMAT.md §1: first 24 hex chars of sha256(converterVersion ‖ formatVersion ‖ pack hash ‖ settings ‖ environment). */
public final class CacheKey {
	private CacheKey() {}

	/**
	 * @param settingsFile Iris settings .txt or null
	 * @param dimensions   requested dimension filter, null/empty = all; part of "environment" because it changes the output
	 */
	public static String compute(String packHash, Path settingsFile, String mcVersion, Map<String, String> macros,
	                             List<String> dimensions) throws IOException {
		return compute(packHash, settingsFile, mcVersion, macros, dimensions, java.util.Set.of());
	}

	/** @param rt RT effects of the request; only a non-empty set adds to the key, so RT off keeps today's key and directory */
	public static String compute(String packHash, Path settingsFile, String mcVersion, Map<String, String> macros,
	                             List<String> dimensions, java.util.Set<String> rt) throws IOException {
		StringBuilder env = new StringBuilder("mc=").append(mcVersion).append('\n');
		new TreeMap<>(macros).forEach((k, v) -> env.append(k).append('=').append(v).append('\n'));
		if (dimensions != null && !dimensions.isEmpty())
			env.append("dimensions=").append(String.join(",", dimensions.stream().sorted().toList())).append('\n');
		if (!rt.isEmpty()) env.append("rt=").append(String.join(",", rt.stream().sorted().toList())).append('\n')
				.append("rtTables=").append(dev.emrion.shaders.converter.rt.RtPatchTable.fingerprint()).append('\n'); // RT bodies change without a VERSION bump
		return sha256(String.join("\0", Converter.VERSION, Integer.toString(Manifest.FORMAT_VERSION), packHash,
				normaliseSettings(settingsFile), env)).substring(0, 24);
	}

	/** key=value lines trimmed and sorted by key; blank and '#' lines dropped, so reordering or comments do not invalidate. */
	static String normaliseSettings(Path settingsFile) throws IOException {
		if (settingsFile == null) return "";
		// ISO-8859-1 never fails to decode; Iris reads the file the same way.
		TreeMap<String, String> entries = new TreeMap<>();
		for (String raw : Files.readAllLines(settingsFile, StandardCharsets.ISO_8859_1)) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) continue;
			int eq = line.indexOf('=');
			if (eq < 0) entries.put(line, "");
			else entries.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
		}
		StringBuilder sb = new StringBuilder();
		entries.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
		return sb.toString();
	}

	private static String sha256(String s) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}
}
