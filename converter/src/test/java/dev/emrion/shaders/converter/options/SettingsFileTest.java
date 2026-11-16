package dev.emrion.shaders.converter.options;

import dev.emrion.shaders.converter.pack.DefaultPackLoader;
import dev.emrion.shaders.converter.pack.PackSource;
import dev.emrion.shaders.converter.pack.StandardMacros;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The options GUI's save path against the target pack's real settings file. Skipped when testpacks/ is absent. */
class SettingsFileTest {
	private static final Path TESTPACKS = testpacks();
	private static final Path USER_FILE = TESTPACKS.resolve("ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5.txt");
	private static Manifest.Options options;

	private static Path testpacks() {
		Path p = Path.of(System.getProperty("emrion.testpacks", "testpacks"));
		return Files.isDirectory(p.resolve("unbound-euphoria")) ? p : Path.of(System.getProperty("emrion.testpacks", "../testpacks")); // other checkouts
	}

	@TempDir
	Path tmp;

	@BeforeAll
	static void load() throws Exception {
		if (!Files.isDirectory(TESTPACKS.resolve("unbound-euphoria")) || !Files.isRegularFile(USER_FILE)) return;
		options = new DefaultPackLoader().load(PackSource.open(TESTPACKS.resolve("unbound-euphoria")), USER_FILE,
				StandardMacros.build(StandardMacros.Environment.DEFAULT), new Diagnostics()).options();
	}

	private Path copy() throws Exception {
		assumeTrue(options != null, "testpacks/unbound-euphoria not present");
		Path f = tmp.resolve(USER_FILE.getFileName());
		Files.copy(USER_FILE, f);
		return f;
	}

	@Test
	void unchangedValuesKeepTheFileByteIdentical() throws Exception {
		Path f = copy();
		Map<String, String> settings = IrisSettings.nonDefault(options.declared(), options.values(), IrisSettings.read(f));
		assertEquals(IrisSettings.read(USER_FILE), settings);
		assertTrue(settings.containsKey("AAA_THIS_IS_A_EUPHORIA_PATCHES_SETTINGS_FILE"));
		assertFalse(IrisSettings.update(f, settings));
		assertArrayEquals(Files.readAllBytes(USER_FILE), Files.readAllBytes(f));
	}

	@Test
	void oneChangedValueChangesExactlyThatLine() throws Exception {
		Path f = copy();
		Map<String, String> values = new LinkedHashMap<>(options.values());
		assertEquals("3", values.put("CLOUD_QUALITY", "1"));
		assertTrue(IrisSettings.update(f, IrisSettings.nonDefault(options.declared(), values, IrisSettings.read(f))));
		List<String> before = Files.readAllLines(USER_FILE), after = Files.readAllLines(f);
		assertEquals(before.size(), after.size());
		List<String> diff = new ArrayList<>();
		// line 0 is the "#<date>" comment Properties.store (and so Iris) writes on every save
		for (int i = 1; i < before.size(); i++) if (!before.get(i).equals(after.get(i))) diff.add(before.get(i) + " -> " + after.get(i));
		assertEquals(List.of("CLOUD_QUALITY=3 -> CLOUD_QUALITY=1"), diff);
		assertTrue(after.getFirst().startsWith("#"));
	}

	@Test
	void allDefaultsDeleteTheFileButUnknownKeysSurvive() throws Exception {
		Path f = copy();
		Map<String, String> defaults = new LinkedHashMap<>();
		options.declared().forEach(d -> defaults.put(d.name(), d.defaultValue()));
		assertTrue(IrisSettings.update(f, IrisSettings.nonDefault(options.declared(), defaults, Map.of())));
		assertFalse(Files.exists(f));
		Map<String, String> kept = IrisSettings.nonDefault(options.declared(), defaults, Map.of("NOT_AN_OPTION", "x"));
		assertEquals(Map.of("NOT_AN_OPTION", "x"), kept);
	}

	@Test
	void invalidBooleanCountsAsDefault() {
		var d = new Manifest.OptionDecl("B", "define_bool", "bool", "false", List.of(), "f", 1, null);
		assertEquals(Map.of(), IrisSettings.nonDefault(List.of(d), Map.of("B", "yes"), Map.of()));
		assertEquals(Map.of("B", "true"), IrisSettings.nonDefault(List.of(d), Map.of("B", "true"), Map.of()));
	}

	@Test
	void profileDetection() {
		assumeTrue(options != null, "testpacks/unbound-euphoria not present");
		Map<String, String> values = new LinkedHashMap<>(options.values());
		String current = Profiles.detect(options.profiles(), values);
		System.out.println("detected profile for the user's settings: " + current);
		for (String name : options.profiles().keySet()) {
			values.putAll(options.profiles().get(name));
			assertEquals(name, Profiles.detect(options.profiles(), values), "selecting " + name + " detects it");
		}
		values.put(options.profiles().values().iterator().next().keySet().iterator().next(), "not-a-profile-value");
		assertNull(Profiles.detect(options.profiles(), values), "Custom");
	}
}
