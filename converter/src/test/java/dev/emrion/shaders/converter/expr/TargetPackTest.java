package dev.emrion.shaders.converter.expr;

import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Parses every custom uniform/variable of the real target shaders.properties (Unbound r5.9.3 + Euphoria 1.10.5). */
class TargetPackTest {
	/** custom uniforms live in Overworld programs. */
	static final List<String> OVERWORLD_LIVE = List.of("framemod2", "framemod8", "eyeBrightnessM", "eyeBrightnessM2",
			"frameTimeSmooth", "rainFactor", "thunderFactor", "inDry", "inRainy", "inSnowy", "inNetherWastes", "inCrimsonForest",
			"inWarpedForest", "inBasaltDeltas", "inSoulValley", "inPaleGarden", "inSulfurCaves", "inVanillaEnd",
			"maxBlindnessDarkness", "starter", "worldAgeSmooth");

	/** Environment for the throwaway preprocessing below. */

	static final Map<String, String> DEFINES = Map.of("IRIS_VERSION", "11200", "MC_VERSION", "260300", "IS_IRIS", "",
			"MC_OS_LINUX", "", "SMOOTH_WORLD_ANIMATIONS", "", "RAIN_FADE_TIMER", "3");

	static SequencedMap<String, String> props;
	static List<Manifest.CustomUniform> uniforms;
	static Diagnostics diagnostics = new Diagnostics();

	@BeforeAll
	static void load() throws IOException {
		Path base = Path.of(System.getProperty("emrion.testpacks", "testpacks"));
		Path file = base.resolve("unbound-euphoria/shaders/shaders.properties");
		if (!Files.exists(file)) file = Path.of(System.getProperty("emrion.testpacks", "../testpacks"), "unbound-euphoria/shaders/shaders.properties");
		assumeTrue(Files.exists(file), "target pack not available");
		props = preprocess(Files.readAllLines(file));
		uniforms = CustomUniformParser.parse(props, diagnostics);
	}

	/** Minimal #if/#ifdef/#elif/#else/#endif + object-like macro substitution, only for custom uniform lines. */
	static SequencedMap<String, String> preprocess(List<String> lines) {
		SequencedMap<String, String> out = new LinkedHashMap<>();
		Deque<boolean[]> stack = new ArrayDeque<>(); // {parentActive, active, taken}
		boolean active = true;
		Pattern kv = Pattern.compile("^\\s*((?:uniform|variable)\\.\\S+?)\\s*=\\s*(.*)$");
		for (String raw : lines) {
			String t = raw.strip();
			if (t.startsWith("#ifdef ") || t.startsWith("#ifndef ") || t.startsWith("#if ")) {
				boolean c = t.startsWith("#if ") ? cond(t.substring(4)) : DEFINES.containsKey(t.substring(t.indexOf(' ') + 1).strip()) == t.startsWith("#ifdef");
				stack.push(new boolean[]{active, active && c, c});
				active = active && c;
			} else if (t.startsWith("#elif ")) {
				boolean[] top = stack.peek();
				boolean c = !top[2] && cond(t.substring(6));
				top[1] = top[0] && c;
				top[2] |= c;
				active = top[1];
			} else if (t.startsWith("#else")) {
				boolean[] top = stack.peek();
				top[1] = top[0] && !top[2];
				top[2] = true;
				active = top[1];
			} else if (t.startsWith("#endif")) {
				active = stack.pop()[0];
			} else if (active) {
				Matcher m = kv.matcher(raw);
				if (m.matches()) out.put(m.group(1), substitute(m.group(2), false));
			}
		}
		assertTrue(stack.isEmpty());
		return out;
	}

	private static String substitute(String s, boolean undefinedIsZero) {
		Matcher m = Pattern.compile("\\b[A-Za-z_]\\w*\\b").matcher(s);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String v = DEFINES.get(m.group());
			if (v == null || v.isEmpty()) v = undefinedIsZero && (v == null) ? "0" : m.group();
			m.appendReplacement(sb, v);
		}
		return m.appendTail(sb).toString();
	}

	/** Evaluates a #if condition with our own expression engine; anything it cannot handle counts as false. */
	private static boolean cond(String expr) {
		String e = Pattern.compile("defined\\s*\\(?\\s*(\\w+)\\s*\\)?").matcher(expr)
				.replaceAll(r -> DEFINES.containsKey(r.group(1)) ? "(1 == 1)" : "(1 == 0)");
		e = substitute(e, true);
		for (String candidate : new String[]{e, "(" + e + ") != 0"}) {
			Diagnostics d = new Diagnostics();
			List<Manifest.CustomUniform> c = CustomUniformParser.parse(new LinkedHashMap<>(Map.of("uniform.bool.c", candidate)), d);
			if (!c.isEmpty()) {
				CustomUniformRuntime rt = new CustomUniformRuntime(c, new MapHost());
				rt.update(new MapHost(), 0);
				return rt.getBool("c");
			}
		}
		return false;
	}

	@Test
	void parsesAllCustomUniformsWithoutErrors() {
		assertEquals(List.of(), diagnostics.all().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		assertEquals(props.size(), uniforms.size(), "every declaration resolves");
		assertEquals(58, props.size(), "68 declarations minus #else alternatives and the PHOTONICS block");
		assertEquals("smooth(1, rainStrength, 3, 3)", props.get("uniform.float.rainFactor"));
		assertTrue(props.get("variable.float.shaderStartV").contains("4.5"));
		assertFalse(props.containsKey("uniform.float.photonicsMasterTimer"));
		for (String name : OVERWORLD_LIVE) {
			Manifest.CustomUniform u = uniforms.stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
			assertNotNull(u, name);
			assertTrue(u.uniform(), name);
			assertEquals("float", u.type(), name);
		}
	}

	@Test
	void runtimeBuildsWithEveryBiomeKnown() {
		MapHost host = host();
		CustomUniformRuntime rt = new CustomUniformRuntime(uniforms, host);
		assertEquals(List.of(), rt.broken());
	}

	private static MapHost host() {
		MapHost host = new MapHost();
		int id = 0;
		for (String b : UniformCatalog.VANILLA_BIOMES.stream().sorted().toList()) host.biomes.put("BIOME_" + b.toUpperCase(), id++);
		return host;
	}

	@Test
	void simulatedFrames() {
		MapHost host = host();
		CustomUniformRuntime rt = new CustomUniformRuntime(uniforms, host);
		float dt = 0.05f;
		float ftc = 0;
		MapHost.RefSmooth worldAge = new MapHost.RefSmooth(), eye = new MapHost.RefSmooth(), rain = new MapHost.RefSmooth(),
				startV = new MapHost.RefSmooth(), moved = new MapHost.RefSmooth(), starter = new MapHost.RefSmooth();
		float[] cam = {100, 70, -20}, prevCam = cam.clone();
		boolean startedMoving = false;
		for (int frame = 0; frame < 400; frame++) {
			ftc += dt;
			int worldTime = 1000 + frame; // one tick per frame
			host.ints.put("frameCounter", frame);
			host.floats.put("frameTime", dt);
			host.floats.put("frameTimeCounter", ftc);
			host.ints.put("worldTime", worldTime);
			host.ints.put("worldDay", 2);
			host.ivecs.put("eyeBrightness", new int[]{0, frame < 100 ? 240 : 60});
			float rainStrength = frame < 50 ? 0 : Math.min(1, (frame - 50) / 100f);
			host.floats.put("rainStrength", rainStrength);
			prevCam = cam.clone();
			if (frame >= 200) cam[0] += 0.1f; // starts walking at 10 s
			host.vecs.put("previousCameraPosition", prevCam);
			host.vecs.put("cameraPosition", cam.clone());

			rt.update(host, dt);

			assertEquals(frame % 2, rt.getFloat("framemod2"));
			assertEquals(frame % 8, rt.getFloat("framemod8"));
			float worldAgeV = worldTime + 2 * 24000f;
			assertEquals(worldAge.next(worldAgeV, 1, 1, dt), rt.getFloat("worldAgeSmooth"), 0.01f, "worldAgeSmooth " + frame);
			assertEquals(eye.next((frame < 100 ? 240 : 60) / 240f, 5, 5, dt), rt.getFloat("eyeBrightnessM"), 1e-6f);
			assertEquals(rain.next(rainStrength, 3, 3, dt), rt.getFloat("rainFactor"), 1e-6f);
			float v = startV.next(ftc > 4.5f ? 1 : 0, 0, 1e14f, dt);
			float diff = Math.abs(cam[0] - prevCam[0]);
			float moving = diff > 0 && diff < 1 ? 1 : 0;
			float m = moved.next(v > 0.999f ? moving : 0, 0, 1e14f, dt);
			assertEquals(starter.next(v > 0.999f ? m : 0, 20, 20, dt), rt.getFloat("starter"), 1e-6f, "starter " + frame);
			if (frame == 199) assertEquals(0f, rt.getFloat("starter"));
			startedMoving |= rt.getFloat("starter") > 0.5f;
		}
		assertTrue(startedMoving);
		assertEquals(2 * 24000 + 1000 + 399, rt.getFloat("worldAgeSmooth"), 30f, "smoothed world age trails the real one");
		assertEquals(1f, rt.getFloat("inDry"), "biome_precipitation 0 = none");
		assertEquals(0f, rt.getFloat("inRainy"));
	}
}
