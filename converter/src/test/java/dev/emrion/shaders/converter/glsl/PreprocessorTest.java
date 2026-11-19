package dev.emrion.shaders.converter.glsl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PreprocessorTest {
	private static Preprocessor.Result run(String src, Map<String, String> env) {
		return Preprocessor.run(src, env, Preprocessor.Mode.GLSL);
	}

	private static String pp(String src) {
		return norm(run(src, Map.of()).text());
	}

	private static String norm(String s) {
		return s.replaceAll("\\s+", " ").trim();
	}

	@Test
	void objectAndFunctionMacros() {
		assertEquals("int a = 3 ; vec2 b = (1 + 2) ;", pp("#define A 3\n#define ADD(x, y) (x + y)\nint a = A ;\nvec2 b = ADD(1, 2) ;\n"));
	}

	@Test
	void expansionsDoNotMergeWithNeighbouringTokens() {
		assertEquals("float b = 1.0- -0.1;", pp("#define BIAS -0.1\nfloat b = 1.0-BIAS;\n"));
		assertEquals("x = - -1;", pp("#define NEG(v) -v\nx = NEG(-1);\n"));
		assertEquals("i--; texCoord.y; ++i", pp("#define DEC(v) v--\n#define TC texCoord\n#define INC ++i\nDEC(i); TC.y; INC\n"));
	}

	@Test
	void exponentialMacroChainStopsAtTheBudget() {
		StringBuilder src = new StringBuilder("#define M0 x\n");
		for (int i = 1; i <= 40; i++) src.append("#define M").append(i).append(" M").append(i - 1).append(" M").append(i - 1).append('\n');
		var r = run(src.append("M40\n").toString(), Map.of());
		assertTrue(r.warnings().stream().anyMatch(w -> w.text().startsWith("Macro expansion exceeds")), r.warnings().toString());
		assertTrue(r.text().length() < 50_000_000, "output stays bounded");
	}

	@Test
	void deeplyNestedMacroArgumentsDoNotOverflowTheStack() {
		int n = 20_000;
		var r = run("#define I(x) x\n" + "I(".repeat(n) + "a" + ")".repeat(n) + "\n", Map.of());
		assertTrue(r.warnings().stream().anyMatch(w -> w.text().startsWith("Macro arguments nested deeper")), r.warnings().toString());
		assertEquals("a", pp("#define I(x) x\nI(I(I(a)))\n"), "shallow nesting still expands");
	}

	@Test
	void nestedArgumentsAndParens() {
		assertEquals("f((a, b), c)", pp("#define F(x, y) f(x, y)\nF((a, b), c)\n"));
	}

	@Test
	void selfReferenceDoesNotRecurse() {
		assertEquals("X + 1 A", pp("#define X X + 1\n#define A B\n#define B A\nX A\n"));
	}

	@Test
	void functionMacroNameWithoutParensStaysIdentifier() {
		assertEquals("F ; [2]", pp("#define F(x) [x]\nF ; F(2)\n"));
	}

	@Test
	void callSpanningLinesKeepsLineAlignment() {
		String out = run("#define F(a, b) a + b\nF(1,\n2)\nnext\n", Map.of()).text();
		assertEquals("next", out.split("\n")[3].trim());
	}

	@Test
	void ifArithmeticAndDefined() {
		String src = """
				#define Q 3
				#if Q * 2 == 6 && defined(Q) && !defined R && (5 / 2 == 2) && 0x10 == 16 && (1 ? 2 : 3) == 2 && (1 << 3) == 8
				yes
				#else
				no
				#endif
				""";
		assertEquals("yes", pp(src));
	}

	@Test
	void undefinedIdentifierIsZero() {
		assertEquals("b", pp("#if UNKNOWN\na\n#else\nb\n#endif\n"));
	}

	@Test
	void floatLiteralsTruncateLikeJcpp() {
		assertEquals("t2 t3", pp("#define P 1.00\n#if 1.5 > 1\nt1\n#endif\n#if P > 0.5\nt2\n#endif\n#if P == 1\nt3\n#endif\n"));
	}

	@Test
	void elifChain() {
		assertEquals("two", pp("#define V 2\n#if V == 1\none\n#elif V == 2\ntwo\n#elif V == 2\nagain\n#else\nother\n#endif\n"));
	}

	@Test
	void nestedInactiveBlocksAreNotEvaluated() {
		Preprocessor.Result r = run("#if 0\n#if 1/0\nx\n#endif\n#else\ny\n#endif\n", Map.of());
		assertEquals("y", norm(r.text()));
		assertTrue(r.warnings().isEmpty());
	}

	@Test
	void undefRemovesMacro() {
		assertEquals("A", pp("#define A 1\n#undef A\nA\n"));
	}

	@Test
	void ifdefAndIfndef() {
		assertEquals("a d", pp("#define X\n#ifdef X\na\n#endif\n#ifdef Y\nb\n#endif\n#ifndef X\nc\n#endif\n#ifndef Y\nd\n#endif\n"));
	}

	@Test
	void environmentMacrosArePredefinedAndEmptyIsEmpty() {
		Preprocessor.Result r = run("#ifdef IS_IRIS\nv = MC_VERSION;\n#endif\n#if IS_IRIS\nbad\n#endif\n", Map.of("IS_IRIS", "", "MC_VERSION", "260300"));
		assertEquals("v = 260300;", norm(r.text()));
		assertEquals(1, r.warnings().size(), "#if on an empty macro is an error in JCPP and evaluates false");
	}

	@Test
	void versionAndExtensionAreHoistedInOrder() {
		Preprocessor.Result r = run("// c\n#version 130\nint a;\n#extension GL_X : enable\n#if 0\n#extension GL_Y : enable\n#endif\n", Map.of());
		assertEquals(List.of("#version 130", "#extension GL_X : enable"), r.hoisted());
		assertEquals(List.of(2, 4), r.hoistedLines());
		assertFalse(r.text().contains("#"));
		assertEquals("int a;", r.text().split("\n")[2]);
	}

	@Test
	void spacedExtensionIsDroppedLikeIris() {
		Preprocessor.Result r = run("#  extension GL_X : enable\n", Map.of());
		assertTrue(r.hoisted().isEmpty());
		assertEquals(1, r.warnings().size());
	}

	@Test
	void pragmaAndLineAreDropped() {
		Preprocessor.Result r = run("#pragma optimize(on)\n#line 50\nx = __LINE__;\n", Map.of());
		assertEquals("x = 3;", norm(r.text()));
		assertTrue(r.warnings().isEmpty());
	}

	@Test
	void errorIsReportedAndProcessingContinues() {
		Preprocessor.Result r = run("#error boom here\nafter\n", Map.of());
		assertEquals("after", norm(r.text()));
		assertEquals("#error boom here", r.warnings().getFirst().text());
		assertEquals(1, r.warnings().getFirst().line());
	}

	@Test
	void fileAndCounter() {
		assertEquals("\"<no file>\" 0 1", pp("__FILE__ __COUNTER__ __COUNTER__\n"));
	}

	@Test
	void commentsKeptOnlyInCommentedOutput() {
		Preprocessor.Result r = run("a /* DRAWBUFFERS:01 */ b // tail\n/*\nmulti\n*/ c\n", Map.of());
		assertEquals("a b c", norm(r.text()));
		assertTrue(r.textWithComments().contains("/* DRAWBUFFERS:01 */"));
		assertEquals(r.text().split("\n", -1).length, r.textWithComments().split("\n", -1).length);
		assertEquals("c", r.text().split("\n")[3].trim());
	}

	@Test
	void commentsInMacroBodiesAreDropped() {
		assertEquals("int a = 3; int b = 4 + 1;", pp("#define Q 3 // c\n#define R 4 /* c */ + 1\nint a = Q; int b = R;\n"));
	}

	@Test
	void directivesInsideBlockCommentsAreText() {
		Preprocessor.Result r = run("/*\n#ifdef NOPE\nconst int colortex0Format = RGBA8;\n#endif\n*/\nx\n", Map.of());
		assertEquals("x", norm(r.text()));
		assertTrue(r.textWithComments().contains("#ifdef NOPE"));
	}

	@Test
	void lineContinuationsAreSpliced() {
		String out = run("#define UP(a, b) \\\n  a = min(a, b); \\\n  b = max(a, b)\nUP(x, y);\nint q = 1 + \\\n 2;\nlast\n", Map.of()).text();
		String[] lines = out.split("\n");
		assertEquals("x = min(x, y); y = max(x, y);", lines[3].replaceAll("\\s+", " ").trim());
		assertEquals("int q = 1 + 2;", lines[4].replaceAll("\\s+", " ").trim());
		assertEquals("last", lines[6].trim());
	}

	@Test
	void pasteAndStringify() {
		assertEquals("gl_FragData[0] = 1.5; \"a \\\"b\\\" c\"", pp("#define CAT(a,b) a ## b\n#define S(x) #x\nCAT(gl_, FragData)[0] = CAT(1, .5); S(a  \"b\"  c)\n"));
	}

	@Test
	void argumentsAreExpandedBeforeSubstitution() {
		assertEquals("(2 * 2)", pp("#define TWO 2\n#define SQ(x) (x * x)\nSQ(TWO)\n"));
	}

	@Test
	void unknownDirectiveWarnsInGlslButNotInProperties() {
		assertEquals(1, run("#foo bar\n", Map.of()).warnings().size());
		assertTrue(Preprocessor.run("#foo bar\n", Map.of(), Preprocessor.Mode.PROPERTIES).warnings().isEmpty());
	}

	@Test
	void divisionByZeroIsFalse() {
		Preprocessor.Result r = run("#if 1/0\na\n#else\nb\n#endif\n", Map.of());
		assertEquals("b", norm(r.text()));
		assertEquals(1, r.warnings().size());
	}

	@Test
	void nulCharactersAreRemoved() {
		assertEquals("ab", pp("a\0b\n"));
	}
}
