package dev.emrion.shaders.converter.expr;

import com.google.gson.JsonObject;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import static org.junit.jupiter.api.Assertions.*;

class ExprTest {
	private final MapHost host = new MapHost();

	private static List<Manifest.CustomUniform> parse(Diagnostics d, String... kv) {
		SequencedMap<String, String> props = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) props.put(kv[i], kv[i + 1]);
		return CustomUniformParser.parse(props, d);
	}

	private CustomUniformRuntime run(String... kv) {
		Diagnostics d = new Diagnostics();
		List<Manifest.CustomUniform> list = parse(d, kv);
		assertEquals(List.of(), d.all(), "diagnostics");
		CustomUniformRuntime rt = new CustomUniformRuntime(list, host);
		rt.update(host, 0.05);
		return rt;
	}

	private float f(String expr) { return run("uniform.float.v", expr).getFloat("v"); }

	private int i(String expr) { return run("uniform.int.v", expr).getInt("v"); }

	private boolean b(String expr) { return run("uniform.bool.v", expr).getBool("v"); }

	private static JsonObject ast(String type, String expr) {
		Diagnostics d = new Diagnostics();
		List<Manifest.CustomUniform> list = parse(d, "uniform." + type + ".v", expr);
		assertEquals(List.of(), d.all());
		return list.getFirst().expr();
	}

	/** Returns the error message, failing if the expression was accepted. */
	private static String error(String type, String expr) {
		Diagnostics d = new Diagnostics();
		List<Manifest.CustomUniform> list = parse(d, "uniform." + type + ".v", expr);
		assertTrue(list.isEmpty(), () -> expr + " should not resolve");
		assertTrue(d.hasErrors());
		return d.all().getFirst().message();
	}

	// ---- parser ------------------------------------------------------------------------------------------------

	@Test
	void arithmeticPrecedenceAndAssociativity() {
		assertEquals(7, i("1 + 2 * 3"));
		assertEquals(7, i("2 * 3 + 1"));
		assertEquals(5, i("10 - 2 - 3"));
		assertEquals(2f, f("8 / 2 / 2"));
		assertEquals(9, i("(1 + 2) * 3"));
		assertEquals(-6, i("-2 * 3"));
		assertEquals(1, i("-2 - -3"));
		assertEquals(3, i("abs (-3)"), "an identifier followed by '(' is a call even with a space");
	}

	@Test
	void andOrShareOnePrecedenceLevel() {
		// Iris quirk: && and || are both priority 3 and left-associative: (T || T) && F
		assertFalse(b("1 == 1 || 1 == 1 && 1 == 0"));
		assertTrue(b("1 == 0 && 1 == 0 || 1 == 1"));
		assertTrue(b("!(1 == 0) && 2 > 1"));
		assertTrue(b("1 < 2 && 3 >= 3 && 2 <= 2 && 3 != 4 && 5 ≥ 5 && 4 ≤ 4 && 1 ≠ 2"));
	}

	@Test
	void numberTokens() {
		assertEquals(8, i("010"), "leading zero = octal");
		assertEquals(16, i("0x10"));
		assertEquals(5, i("0b101"));
		assertEquals(8f, f("08"), "not octal -> float");
		assertEquals(1.5f, f("1.5f"));
		assertEquals(100000f, f("1e5"));
		assertEquals(0.5f, f(".5"));
		assertEquals("float", ast("float", "08").get("type").getAsString());
		assertTrue(error("float", "1e-5").contains("Illegal number"));
	}

	@Test
	void syntaxQuirks() {
		assertEquals(2, i("max(1, 2,)"), "calls accept a trailing comma");
		assertTrue(error("int", "(1,)").contains("trailing comma"));
		assertTrue(error("int", "+1").contains("Unexpected character"));
		assertTrue(error("int", "1 = 1").contains("Expected '='"));
		assertTrue(error("float", "vec2(1, 2).x").length() > 0, "access only applies to identifiers");
		assertTrue(error("float", "cameraPosition.xy").contains("No such function"), "no swizzles");
		assertTrue(error("int", "(1, 2)").contains("too many"));
		assertTrue(error("int", "()").contains("empty brackets"));
		assertTrue(error("int", "1 +").contains("right side"));
		assertTrue(error("int", "max(1, 2").contains("closing bracket"));
		assertTrue(error("int", "").contains("empty"));
	}

	// ---- typing ------------------------------------------------------------------------------------------------

	@Test
	void typeResolutionFollowsIris() {
		host.ints.put("frameCounter", 7);
		assertEquals(1f, f("frameCounter % 2"));
		JsonObject a = ast("float", "frameCounter % 2");
		assertEquals("cast", a.get("op").getAsString(), "int remainder, then cast to float");
		assertEquals("int", a.getAsJsonObject("arg").get("type").getAsString());
		assertEquals(0.5f, f("1 / 2"), "there is no int division");
		assertTrue(error("int", "4 / 2").contains("Couldn't resolve"));
		assertEquals(2, i("floor(2.7)"), "float->int overload of floor");
		assertEquals(2f, f("floor(2.7)"));
		assertEquals(3, i("toInt(3.9)"));
		assertEquals(3f, f("toFloat(3)"));
		assertTrue(error("float", "is_sneaking + 1").contains("Couldn't resolve"), "bool never converts");
		assertTrue(error("int", "1.5").contains("Couldn't resolve"), "no implicit float->int");
		assertTrue(error("float", "fogColor.r").contains("Unknown variable"), "dynamic built-ins are not inputs");
		assertTrue(error("float", "nope + 1").contains("Unknown variable"));
		assertTrue(error("float", "nope(1)").contains("No such function"));
	}

	@Test
	void constants() {
		host.ints.put("biome", 7);
		host.biomes.put("BIOME_PLAINS", 7);
		JsonObject a = ast("float", "if(in(biome, BIOME_PLAINS), 1, 0)");
		assertTrue(a.toString().contains("{\"op\":\"const\",\"type\":\"int\",\"name\":\"BIOME_PLAINS\"}"), a::toString);
		assertEquals(1f, f("if(in(biome, BIOME_PLAINS), 1, 0)"));
		assertEquals(12, i("CAT_DESERT"));
		assertEquals(18, i("CAT_UNDERGROUND"));
		assertEquals(2, i("PPT_SNOW"));
		assertEquals((float) Math.PI, f("pi"));
		assertEquals(-1, i("blockEntityId"));
		// unknown biome at runtime: the uniform is broken and stays 0
		Diagnostics d = new Diagnostics();
		List<Manifest.CustomUniform> list = parse(d, "uniform.int.v", "BIOME_MODDED_THING", "uniform.int.w", "v + 1", "uniform.int.x", "3");
		assertFalse(d.hasErrors());
		assertTrue(d.all().getFirst().message().contains("not a vanilla biome"));
		CustomUniformRuntime rt = new CustomUniformRuntime(list, host);
		rt.update(host, 0.05);
		assertEquals(List.of("v", "w"), rt.broken());
		assertEquals(3, rt.getInt("x"));
	}

	@Test
	void declarations() {
		Diagnostics d = new Diagnostics();
		List<Manifest.CustomUniform> list = parse(d,
				"variable.float.a", "b + 1",          // forward reference
				"variable.float.b", "2",
				"uniform.float.a", "100",             // duplicate name: first wins
				"uniform.float.frameTime", "1.0",     // shadows a built-in
				"uniform.mat4.m", "1",                // invalid type
				"uniform.float.c1", "c2", "uniform.float.c2", "c1",   // cycle
				"uniform.float.bad", "unknownThing", "uniform.float.dep", "bad * 2",
				"uniform.vec3.v", "vec3(1, 2, 3)",
				"uniform.float.z", "v.z + v.b + v.p + v.2",
				"uniform.float.x.y", "1");             // malformed key
		assertEquals(List.of("b", "a", "v", "z"), list.stream().map(Manifest.CustomUniform::name).toList());
		assertFalse(list.get(1).uniform());
		long errors = d.all().stream().filter(x -> x.severity() == Diagnostic.Severity.ERROR).count();
		assertEquals(5, errors, d.all()::toString); // frameTime, c1, c2, bad, dep; the rest are warnings
		CustomUniformRuntime rt = new CustomUniformRuntime(list, host);
		rt.update(host, 0.05);
		assertEquals(3f, rt.getFloat("a"));
		assertEquals(12f, rt.getFloat("z"));
		assertArrayEquals(new float[]{1, 2, 3}, rt.getVector("v"));
		assertEquals("vec3", rt.type(rt.indexOf("v")));
	}

	// ---- functions ---------------------------------------------------------------------------------------------

	@Test
	void mathFunctions() {
		float e = 1e-6f;
		assertEquals((float) Math.sin(1), f("sin(1)"), e);
		assertEquals((float) Math.cos(1), f("cos(1)"), e);
		assertEquals((float) Math.tan(1), f("tan(1)"), e);
		assertEquals((float) Math.asin(0.5), f("asin(0.5)"), e);
		assertEquals((float) Math.acos(0.5), f("acos(0.5)"), e);
		assertEquals((float) Math.atan(2), f("atan(2)"), e);
		assertEquals((float) Math.atan2(1, -1), f("atan(1, -1)"), e);
		assertEquals((float) Math.atan2(1, -1), f("atan2(1, -1)"), e);
		assertEquals((float) Math.PI, f("torad(180)"), e);
		assertEquals((float) Math.PI, f("radians(180)"), e);
		assertEquals(180f, f("todeg(pi)"), 1e-4f);
		assertEquals(180f, f("degrees(pi)"), 1e-4f);
		assertEquals(8f, f("pow(2, 3)"));
		assertEquals((float) Math.E, f("exp(1)"), e);
		assertEquals(8f, f("exp2(3)"));
		assertEquals(1000f, f("exp10(3)"));
		assertEquals(1f, f("log(" + Math.E + ")"), e);
		assertEquals(3f, f("log(2, 8)"), e);
		assertEquals(3f, f("log2(8)"), e);
		assertEquals(2f, f("log10(100)"), e);
		assertEquals(3f, f("sqrt(9)"));
		assertEquals(3, i("abs(-3)"));
		assertEquals(2.5f, f("abs(-2.5)"));
		assertEquals(-1f, f("sign(-4)"));
		assertEquals(1f, f("signum(4)"));
		assertEquals(-3f, f("floor(-2.5)"));
		assertEquals(-2f, f("ceil(-2.5)"));
		assertEquals(3, i("ceil(2.1)"));
		assertEquals(0.75f, f("frac(-1.25)"));
		assertEquals(1f, f("min(1.0, 2)"));
		assertEquals(2, i("max(1, 2)"));
		assertEquals(5f, f("clamp(7, 0, 5)"));
		assertEquals(0, i("clamp(-7, 0, 5)"));
		assertEquals(2.5f, f("mix(0, 10, 0.25)"));
		assertEquals(0f, f("edge(0.5, 0.4)"));
		assertEquals(1, i("edge(1, 1)"));
		assertEquals(-1, i("-7 % 3"), "Java remainder");
		assertEquals(2, i("fmod(-7, 3)"), "floor modulo");
		assertEquals(0.5f, f("fmod(-7.5, 2.0)"));
		assertEquals(0, i("7 % 0"), "Iris would throw; we return 0");
	}

	@Test
	void naryMinMaxOnlyLookAtTheFirstTwoArguments() {
		// Iris bug reproduced: the vararg loop evaluates params[1] instead of params[i]
		assertEquals(3f, f("min(5.0, 3.0, 1.0)"));
		assertEquals(2, i("max(1, 2, 9)"));
		assertEquals(2f, f("max(1.0, 2.0, 9.0, 12.0)"));
	}

	@Test
	void selectionAndBooleanFunctions() {
		assertEquals(2f, f("if(1 > 2, 1, 2)"));
		assertEquals(2, i("if(1 > 2, 1, 2 > 1, 2, 3)"));
		assertEquals(3, i("if(1 > 2, 1, 2 > 3, 2, 3)"));
		assertEquals(1, i("if(1 < 2, 1, 2 > 3, 2, 3)"));
		assertTrue(b("between(5, 1, 5)"));
		assertFalse(b("between(5.5, 1, 5)"));
		assertTrue(b("equals(1.0, 1.005, 0.01)"));
		assertFalse(b("equals(1.0, 1.05, 0.01)"));
		assertTrue(b("in(3, 1, 2, 3)"));
		assertFalse(b("in(4, 1, 2, 3)"));
		float r = f("random(2, 3)");
		assertTrue(r >= 2 && r < 3);
		int ri = i("randomInt(5, 7)");
		assertTrue(ri == 5 || ri == 6);
		assertTrue(f("random()") < 1f);
		assertTrue(i("randomInt(3)") < 3);
		assertEquals(5, i("add(2, 3)"), "operators are callable by their stareval names");
	}

	@Test
	void ifChainEvaluatesElseAfterEveryFalseCondition() {
		// the else-branch smooth advances once per false condition, i.e. three times per frame here
		host.floats.put("rainStrength", 0f);
		Diagnostics d = new Diagnostics();
		List<Manifest.CustomUniform> list = parse(d, "uniform.float.v", "if(1 > 2, 1.0, 2 > 3, 2.0, 3 > 4, 3.0, smooth(rainStrength, 1, 1))");
		CustomUniformRuntime rt = new CustomUniformRuntime(list, host);
		rt.update(host, 0.05);
		host.floats.put("rainStrength", 1f);
		rt.update(host, 0.05);
		MapHost.RefSmooth ref = new MapHost.RefSmooth();
		for (int k = 0; k < 3; k++) ref.next(0, 1, 1, 0.05f);
		for (int k = 0; k < 3; k++) ref.next(1, 1, 1, 0.05f);
		assertEquals(ref.acc, rt.getFloat("v"), 1e-6f);
	}

	@Test
	void vectors() {
		host.ivecs.put("eyeBrightness", new int[]{48, 120});
		host.vecs.put("cameraPosition", new float[]{1, 2, 3});
		float[] m = new float[16];
		for (int k = 0; k < 16; k++) m[k] = k;
		host.vecs.put("gbufferModelView", m);
		assertEquals(0.5f, f("eyeBrightness.y / 240.0"));
		assertEquals(48, i("eyeBrightness.r"));
		assertEquals(6f, f("cameraPosition.x + cameraPosition.g + cameraPosition.p"));
		assertEquals(13f, f("gbufferModelView.3.y"), "matrix columns are vec4");
		CustomUniformRuntime rt = run(
				"uniform.vec2.a", "vec2(1, 2) + vec2(3, 4)",
				"uniform.vec3.b", "-vec3(1, -2, 3) * vec3(2, 2, 2)",
				"uniform.vec4.c", "clamp(vec4(-1, 0.5, 2, 9), vec4(0, 0, 0, 0), vec4(1, 1, 1, 1))",
				"uniform.vec2.d", "if(1 > 2, vec2(1, 1), abs(vec2(-3, 4)))",
				"uniform.vec2.e", "min(vec2(1, 5), vec2(3, 2)) / vec2(2, 2)",
				"uniform.vec2.alias", "(vec2(1, 1) + vec2(2, 2)) + (vec2(10, 10) + vec2(20, 20))",
				"uniform.bool.ieq", "ivec2(1, 2) == ivec2(1, 2)",
				"uniform.bool.ine", "ivec2(1, 2) != ivec2(3, 2)",
				"uniform.vec3.cp", "cameraPosition");
		assertArrayEquals(new float[]{4, 6}, rt.getVector("a"));
		assertArrayEquals(new float[]{-2, 4, -6}, rt.getVector("b"));
		assertArrayEquals(new float[]{0, 0.5f, 1, 1}, rt.getVector("c"));
		assertArrayEquals(new float[]{3, 4}, rt.getVector("d"));
		assertArrayEquals(new float[]{0.5f, 1}, rt.getVector("e"));
		// Iris quirk: JOML ops share one result vector per overload, so both inner sums alias: 30+30
		assertArrayEquals(new float[]{60, 60}, rt.getVector("alias"));
		assertTrue(rt.getBool("ieq"));
		assertFalse(rt.getBool("ine"), "!= on int vectors is true only if every component differs");
		assertArrayEquals(new float[]{1, 2, 3}, rt.getVector("cp"));
	}

	// ---- smooth ------------------------------------------------------------------------------------------------

	@Test
	void smoothMatchesIrisFormula() {
		Diagnostics d = new Diagnostics();
		List<Manifest.CustomUniform> list = parse(d,
				"uniform.float.s", "smooth(4, rainStrength, 5, 10)",
				"uniform.float.t", "smooth(4, rainStrength * 2, 5, 10)",   // same user id: still its own state
				"uniform.float.one", "smooth(rainStrength)",
				"uniform.float.two", "smooth(rainStrength, 2)",
				"uniform.float.latch", "smooth(2, rainStrength, 0, 100000000000000.0)");
		assertEquals(List.of(), d.all());
		assertEquals(4, list.getFirst().expr().get("id").getAsInt());
		assertEquals(0, list.get(0).expr().get("stateId").getAsInt());
		assertEquals(1, list.get(1).expr().get("stateId").getAsInt());
		assertEquals(3, list.get(0).expr().getAsJsonArray("args").size(), "id dropped");
		CustomUniformRuntime rt = new CustomUniformRuntime(list, host);

		float[] targets = {0.3f, 1, 1, 1, 0, 0, 0.5f, 0.5f, 0, 1};
		float[] dts = {0.016f, 0.016f, 0.05f, 0.1f, 0.016f, 0.25f, 0.016f, 0.016f, 1f, 0.033f};
		MapHost.RefSmooth s = new MapHost.RefSmooth(), t = new MapHost.RefSmooth(), one = new MapHost.RefSmooth(),
				two = new MapHost.RefSmooth(), latch = new MapHost.RefSmooth();
		for (int k = 0; k < targets.length; k++) {
			host.floats.put("rainStrength", targets[k]);
			rt.update(host, dts[k]);
			assertEquals(s.next(targets[k], 5, 10, dts[k]), rt.getFloat("s"), 1e-6f, "frame " + k);
			assertEquals(t.next(targets[k] * 2, 5, 10, dts[k]), rt.getFloat("t"), 1e-6f);
			assertEquals(one.next(targets[k], 1, 1, dts[k]), rt.getFloat("one"), 1e-6f);
			assertEquals(two.next(targets[k], 2, 2, dts[k]), rt.getFloat("two"), 1e-6f);
			assertEquals(latch.next(targets[k], 0, 1e14f, dts[k]), rt.getFloat("latch"), 1e-6f);
		}
		assertEquals(1f, rt.getFloat("latch"), "fade-up 0 jumps, fade-down 1e14 never decays");
	}

	@Test
	void smoothHandComputed() {
		// first frame takes the value as is; then half-life = 0.1 * fade: fade 1, dt 0.1 s -> alpha = 1/2 exactly
		CustomUniformRuntime rt = new CustomUniformRuntime(parse(new Diagnostics(), "uniform.float.s", "smooth(rainStrength, 1, 1)"), host);
		float[] expected = {0f, 0.5f, 0.75f, 0.875f};
		for (int k = 0; k < 4; k++) {
			host.floats.put("rainStrength", k == 0 ? 0f : 1f);
			rt.update(host, 0.1);
			assertEquals(expected[k], rt.getFloat("s"), 1e-6f);
		}
	}

	@Test
	void smoothInsideUntakenBranchDoesNotAdvance() {
		host.floats.put("rainStrength", 0f);
		host.ints.put("worldTime", 0);
		CustomUniformRuntime rt = new CustomUniformRuntime(
				parse(new Diagnostics(), "uniform.float.s", "if(worldTime > 5, smooth(rainStrength, 1, 1), -1.0)"), host);
		rt.update(host, 0.1);
		assertEquals(-1f, rt.getFloat("s"));
		host.floats.put("rainStrength", 1f);
		rt.update(host, 0.1);
		host.ints.put("worldTime", 10);
		rt.update(host, 0.1);
		assertEquals(1f, rt.getFloat("s"), "first evaluation of the smooth: no smoothing yet");
	}

	@Test
	void catalog() {
		assertEquals(new UniformCatalog.Builtin("gbufferProjection", "mat4", "per_frame", "block"), UniformCatalog.lookup("gbufferProjection").orElseThrow());
		assertEquals("phase", UniformCatalog.lookup("renderStage").orElseThrow().location());
		for (String n : List.of("entityId", "blockEntityId", "currentRenderedItemId", "entityColor"))
			assertEquals("push", UniformCatalog.lookup(n).orElseThrow().location(), n);
		assertEquals("ivec3", UniformCatalog.lookup("cameraPositionInt").orElseThrow().glslType());
		assertEquals("per_tick", UniformCatalog.lookup("biome").orElseThrow().frequency());
		assertTrue(UniformCatalog.lookup("timeAngle").isEmpty(), "HardcodedCustomUniforms are never registered by Iris");
		assertTrue(UniformCatalog.expressionInputs().containsAll(List.of("biome", "is_sneaking", "heldItemId", "eyeBrightnessSmooth", "previousCameraPosition")));
		for (String n : List.of("fogColor", "renderStage", "atlasSize", "entityId", "centerDepthSmooth"))
			assertFalse(UniformCatalog.expressionInputs().contains(n), n);
	}
}
