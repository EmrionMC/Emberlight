package dev.emrion.shaders.converter.glsl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IncludeExpanderTest {
	private static final Map<String, List<String>> FILES = Map.of(
			"/world0/a.fsh", List.of("#version 130", "#include \"/lib/x.glsl\"", "#include \"../lib/x.glsl\"", "end"),
			"/lib/x.glsl", List.of("x1", "  #include \"util/y.glsl\""),
			"/lib/util/y.glsl", List.of("y1"),
			"/cyc.glsl", List.of("#include \"cyc.glsl\""),
			"/bad.glsl", List.of("#include \"/missing.glsl\"", "ok"),
			"/comment.glsl", List.of("/*", "#include \"/lib/util/y.glsl\"", "*/"));

	@Test
	void absoluteRelativeAndNoIncludeOnce() {
		IncludeExpander.Expanded e = IncludeExpander.expand("/world0/a.fsh", FILES::get);
		assertEquals("#version 130\nx1\ny1\nx1\ny1\nend\n", e.text());
		assertTrue(e.errors().isEmpty());
		assertEquals("/lib/util/y.glsl", e.files().get(e.fileOfLine()[2]));
		assertEquals(1, e.lineOfLine()[2]);
		assertEquals(4, e.lineOfLine()[5]);
	}

	@Test
	void includeInsideCommentIsStillExpanded() {
		assertEquals("/*\ny1\n*/\n", IncludeExpander.expand("/comment.glsl", FILES::get).text());
	}

	@Test
	void cycleAndMissingAreErrors() {
		assertEquals(1, IncludeExpander.expand("/cyc.glsl", FILES::get).errors().size());
		IncludeExpander.Expanded e = IncludeExpander.expand("/bad.glsl", FILES::get);
		assertEquals("ok\n", e.text());
		assertEquals(new IncludeExpander.Error("/bad.glsl", 1, "#include file not found: /missing.glsl"), e.errors().getFirst());
	}

	@Test
	void resolve() {
		assertEquals("/lib/a.glsl", IncludeExpander.resolve("/world0", "../lib/./a.glsl"));
		assertEquals("/a.glsl", IncludeExpander.resolve("/x", "/../a.glsl"));
		assertNull(IncludeExpander.includeTarget("// #include \"x\""));
		assertEquals("x.glsl", IncludeExpander.includeTarget("  #include \"x.glsl\"  "));
	}

	@Test
	void lineMapThroughHoisting() {
		IncludeExpander.Expanded e = IncludeExpander.expand("/world0/a.fsh", FILES::get);
		Preprocessor.Result r = Preprocessor.run(e.text(), Map.of(), Preprocessor.Mode.GLSL);
		SourceLineMap map = new SourceLineMap(e, r.hoistedLines(), "shaders");
		assertEquals("shaders/world0/a.fsh", map.file(1));
		assertEquals(1, map.line(1));
		assertEquals("shaders/lib/x.glsl", map.file(3));
		assertEquals("shaders/lib/util/y.glsl", map.file(4));
		assertEquals(4, map.line(7));
	}
}
