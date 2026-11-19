package dev.emrion.shaders.converter.options;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OptionsTest {
	private final List<String> warnings = new ArrayList<>();

	private OptionSet discover(Map<String, String> files) {
		Map<String, List<String>> m = new LinkedHashMap<>();
		files.forEach((k, v) -> m.put(k, OptionDiscovery.splitLines(v)));
		return OptionDiscovery.discover(m, warnings::add);
	}

	@Test
	void booleanNeedsIfdefReferenceAnywhere() {
		OptionSet s = discover(Map.of(
				"/lib/settings.glsl", "#define A\n//#define B\n#define C\nconst bool shadowHardwareFiltering = true;\n",
				"/program/x.glsl", "#ifdef A\n#endif\n#ifndef B\n#endif\n#if defined C\n#endif\n"));
		assertTrue(s.booleans().containsKey("A"));
		assertEquals("false", s.booleans().get("B").decl().defaultValue());
		assertFalse(s.booleans().containsKey("C"));
		assertFalse(s.booleans().containsKey("shadowHardwareFiltering"));
	}

	@Test
	void conflictingDefaultsDropOption() {
		OptionSet s = discover(Map.of("/a.glsl", "#define Q 1 //[1 2]\n#define Q 1 //[1 2] comment\n#define R 1 //[1 2]\n", "/b.glsl", "#define R 2 //[1 2]\n"));
		assertTrue(s.strings().containsKey("Q"));
		assertEquals("comment", s.strings().get("Q").decl().comment());
		assertEquals(2, s.strings().get("Q").locations().size());
		assertFalse(s.strings().containsKey("R"));
		assertEquals(1, warnings.size());
	}

	@Test
	void valuesKeepOnlyValidChanges() {
		OptionSet s = discover(Map.of("/a.glsl", "#define A\n#ifdef A\n#endif\n#define Q 1 //[1 2]\n"));
		OptionValues v = new OptionValues(s, Map.of("A", "no", "Q", "7", "UNKNOWN", "1"));
		assertNull(v.changedBoolean("A"));
		assertEquals("7", v.changedString("Q"));
		assertEquals(Map.of("Q", "7"), v.changed());
		assertEquals(List.of("UNKNOWN"), OptionValues.unknownKeys(s, Map.of("UNKNOWN", "1", "A", "x")));
		assertEquals(Map.of("A", "true", "Q", "7"), v.resolved());
		assertTrue(v.booleanOrDefault("NOT_AN_OPTION"));
		assertFalse(v.booleanOrDefault("0"));
	}

	@Test
	void applierRewritesLikeIris() {
		String src = "#define A\n//#define B // b\n#define Q 1 //[1 2]\nconst float shadowDistance = 128.0; //[128.0 256.0]\nconst bool generateShadowMipmap = false;\n#ifdef A\n#ifdef B\n#ifdef generateShadowMipmap\n";
		OptionSet s = discover(Map.of("/a.glsl", src));
		OptionValues v = new OptionValues(s, Map.of("A", "false", "B", "true", "Q", "2", "shadowDistance", "256.0", "generateShadowMipmap", "true"));
		List<String> out = OptionApplier.apply(OptionDiscovery.splitLines(src), s.lines().get("/a.glsl"), v);
		assertEquals("//#define A", out.get(0));
		assertEquals("#define B // b", out.get(1));
		assertEquals("#define Q 2 // OptionAnnotatedSource: Changed option", out.get(2));
		assertEquals("const float shadowDistance = 256.0; //[128.0 256.0]", out.get(3));
		assertEquals("const bool generateShadowMipmap = true;", out.get(4));
		assertEquals(8, out.size());
	}

	@Test
	void unchangedLinesStayEquivalent() {
		String src = "#define A\n//#define B\n#ifdef A\n#ifdef B\n";
		OptionSet s = discover(Map.of("/a.glsl", src));
		List<String> out = OptionApplier.apply(OptionDiscovery.splitLines(src), s.lines().get("/a.glsl"), new OptionValues(s, Map.of()));
		assertEquals("#define A", out.get(0));
		assertTrue(out.get(1).startsWith("//"));
	}

	@Test
	void settingsRoundTrip() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("shadowDistance", "256.0");
		m.put("SHADOW_QUALITY", "3");
		m.put("A B", "x=y:z");
		m.put("ÄÖ", "ü");
		String text = IrisSettings.format(m);
		assertTrue(text.startsWith("#"));
		assertTrue(text.indexOf("SHADOW_QUALITY=3") < text.indexOf("shadowDistance=256.0"), "Properties.store sorts keys");
		assertEquals(m, IrisSettings.parse(text));
	}

	@Test
	void settingsReadKeepsFileOrder() {
		Map<String, String> m = IrisSettings.parse("#Thu Nov 12 21:23:21 CET 2026\nB=1\nA=true\nB=2\n");
		assertEquals(List.of("B", "A"), List.copyOf(m.keySet()));
		assertEquals("2", m.get("B"));
	}

	@Test
	void profilesResolveAndDetect() {
		OptionSet s = discover(Map.of("/a.glsl", "#define A\n#ifdef A\n#define Q 1 //[1 2 3]\n"));
		Map<String, List<String>> tree = new LinkedHashMap<>();
		tree.put("LOW", List.of("Q=1", "!A", "!program.world0/composite7"));
		tree.put("HIGH", List.of("profile.LOW", "Q:3", "A"));
		tree.put("CYCLE", List.of("profile.CYCLE"));
		Profiles p = Profiles.parse(tree, s, warnings::add);
		assertEquals(Map.of("Q", "3", "A", "true"), p.profiles().get("HIGH"));
		assertEquals(List.of("world0/composite7"), p.disabled().get("HIGH"));
		assertEquals("HIGH", p.detect(new OptionValues(s, Map.of("Q", "3"))));
		assertEquals("LOW", p.detect(new OptionValues(s, Map.of("A", "false"))));
		assertEquals("CYCLE", p.detect(new OptionValues(s, Map.of("Q", "2"))), "a profile without constraints always matches");
		assertFalse(warnings.isEmpty());
	}

	@Test
	void sharedBaseProfileIsNotACycle() {
		OptionSet s = discover(Map.of("/a.glsl", "#define A\n#define Q 1 //[1 2 3]\n"));
		Map<String, List<String>> tree = new LinkedHashMap<>();
		tree.put("BASE", List.of("Q=2"));
		tree.put("B", List.of("profile.BASE"));
		tree.put("C", List.of("profile.BASE", "!A"));
		tree.put("TOP", List.of("profile.B", "profile.C"));
		List<String> w = new java.util.ArrayList<>();
		Profiles p = Profiles.parse(tree, s, w::add);
		assertEquals(List.of(), w);
		assertEquals(Map.of("Q", "2", "A", "false"), p.profiles().get("TOP"));
		// a chain of 40 diamonds: 2^40 expansions without the visited set
		Map<String, List<String>> chain = new LinkedHashMap<>();
		chain.put("D0", List.of("Q=1"));
		for (int i = 1; i <= 40; i++) {
			chain.put("L" + i, List.of("profile.D" + (i - 1)));
			chain.put("R" + i, List.of("profile.D" + (i - 1)));
			chain.put("D" + i, List.of("profile.L" + i, "profile.R" + i));
		}
		assertEquals(Map.of("Q", "1"), Profiles.parse(chain, s, w::add).profiles().get("D40"));
		assertEquals(List.of(), w);
	}
}
