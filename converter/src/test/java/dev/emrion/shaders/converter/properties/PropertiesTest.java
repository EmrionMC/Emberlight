package dev.emrion.shaders.converter.properties;

import dev.emrion.shaders.format.Manifest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesTest {
	private final List<String> warnings = new ArrayList<>();

	private static Map<String, String> load(String text, Map<String, String> macros) {
		return OrderedProperties.load(PropertiesPreprocessor.process(text, macros));
	}

	@Test
	void preprocessorBranchesAndMacros() {
		String src = """
				# a comment line
				#ifdef SHADOWS
				shadow.enabled = true
				#else
				shadow.enabled = false
				#endif
				  sunPathRotation = ANGLE   
				iffy = 1
				""";
		Map<String, String> p = load(src, Map.of("SHADOWS", "1", "ANGLE", "-40.0"));
		assertEquals("true", p.get("shadow.enabled"));
		assertEquals("-40.0", p.get("sunPathRotation").trim());
	}

	@Test
	void hashLinesStartingWithDirectiveNamesAreDirectives() {
		// "#iffy" survives Iris's prefix filter but is an unknown directive to the preprocessor, so it is ignored.
		assertEquals(Map.of("a", "1", "b", "2"), load("#iffy comment\na = 1\n#endif\nb = 2\n", Map.of()));
	}

	@Test
	void continuationLinesJoin() {
		Map<String, String> p = load("block.1 = stone \\\n\\\n   dirt \\\n grass\nblock.2 = sand\n", Map.of());
		assertEquals("stone dirt grass", p.get("block.1"));
		assertEquals("sand", p.get("block.2"));
	}

	@Test
	void inlineHashIsDeleted() {
		assertEquals("ab", load("x = a#b\n", Map.of()).get("x"));
	}

	@Test
	void orderAndDuplicates() {
		Map<String, String> p = OrderedProperties.load("b=1\na=2\nb=3\n");
		assertEquals(List.of("b", "a"), List.copyOf(p.keySet()));
		assertEquals("3", p.get("b"));
	}

	@Test
	void booleanExpressions() {
		Map<String, Boolean> opts = Map.of("A", true, "B", false);
		assertTrue(BooleanExpression.evaluate("A", opts::get));
		assertFalse(BooleanExpression.evaluate("!A", opts::get));
		assertTrue(BooleanExpression.evaluate("B || A && !B", opts::get));
		assertFalse(BooleanExpression.evaluate("(B || A) && B", opts::get));
		assertFalse(BooleanExpression.evaluate("!(A && true)", opts::get));
		assertTrue(BooleanExpression.evaluate("0 || 1", opts::get));
		assertTrue(BooleanExpression.evaluate("(A && ", opts::get), "parse errors evaluate to true");
		assertFalse(BooleanExpression.evaluate(" false ", opts::get));
	}

	@Test
	void rawPropertiesScreensProfilesColumns() {
		RawProperties r = RawProperties.of(OrderedProperties.load(
				"screen = <profile> [A] B\nscreen.A = X Y\nscreen.columns = 2\nscreen.A.columns = 1\nsliders = X  Y\nprofile.LOW = X=1 !Y\niris.features.optional = SSBO CUSTOM_IMAGES\n"));
		assertEquals(List.of("<profile>", "[A]", "B"), r.screens().get(""));
		assertEquals(List.of("X", "Y"), r.screens().get("A"));
		assertEquals(Map.of("", 2, "A", 1), r.columns());
		assertEquals(List.of("X", "Y"), r.sliders());
		assertEquals(List.of("X=1", "!Y"), r.profiles().get("LOW"));
		assertEquals(List.of("SSBO", "CUSTOM_IMAGES"), r.optionalFeatures());
	}

	@Test
	void blockEntriesBeforeTagsAndCanonicalNames() {
		Manifest.IdMaps m = IdMapParser.parse("""
				block.10 = %leaves stone minecraft:wheat:age=7
				block.20 = lantern:hanging=false mod:thing %mod:tag:axis=y
				layer.translucent = glass mod:pane
				layer.bogus = x
				""", null, null, warnings::add);
		assertEquals(List.of(
				new Manifest.IdEntry(10, List.of("minecraft:stone", "minecraft:wheat:age=7")),
				new Manifest.IdEntry(20, List.of("minecraft:lantern:hanging=false", "mod:thing")),
				new Manifest.IdEntry(10, List.of("#minecraft:leaves")),
				new Manifest.IdEntry(20, List.of("#mod:tag:axis=y"))), m.blocks());
		assertEquals(Map.of("translucent", List.of("minecraft:glass", "mod:pane")), m.layers());
		assertEquals(1, warnings.size());
	}

	@Test
	void itemsKeepLastIdPerName() {
		Manifest.IdMaps m = IdMapParser.parse(null, "item.1 = a b\nitem.2 = b c:d x:y=1\n", "entity.5 = zombie\n", warnings::add);
		assertEquals(List.of(new Manifest.IdEntry(1, List.of("minecraft:a")), new Manifest.IdEntry(2, List.of("minecraft:b", "c:d"))), m.items());
		assertEquals(List.of(new Manifest.IdEntry(5, List.of("minecraft:zombie"))), m.entities());
		assertEquals(1, warnings.size());
	}

	@Test
	void dimensionMapping() {
		var props = OrderedProperties.load("dimension.world0 = *\ndimension.world-1 = the_nether *nether* mod:x\n");
		assertEquals(List.of("world0", "world-1"), DimensionMap.folders(props));
		assertEquals(List.of(
				new Manifest.DimensionMapping("*:*", "world0"),
				new Manifest.DimensionMapping("minecraft:*", "world0"),
				new Manifest.DimensionMapping("minecraft:the_nether", "world-1"),
				new Manifest.DimensionMapping("minecraft:*nether*", "world-1"),
				new Manifest.DimensionMapping("mod:x", "world-1")), DimensionMap.parse(props));
	}
}
