package dev.emrion.shaders.mod.autotest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutoTestScenesTest {
	@Test
	void parsesLine() {
		var s = AutoTest.parseScenes(List.of("# c", "", "a | 1,2,3 4 5 | 12500 | rain | /setblock 0 0 0 stone ; say x;")).getFirst();
		assertEquals(new AutoTest.Scene("a", "1 2 3 4 5", 12500, "rain", List.of("setblock 0 0 0 stone", "say x")), s);
		assertNull(AutoTest.parseScenes(List.of("b | | 6000 | clear")).getFirst().pos());
	}

	@Test
	void sharedSceneListIsValid() throws Exception {
		var scenes = AutoTest.parseScenes(Files.readAllLines(Path.of("emrion-scenes.txt")));
		assertEquals(List.of("overview", "forest", "water", "cave", "sunset", "night", "rain", "entities"), scenes.stream().map(AutoTest.Scene::name).toList());
	}

	@Test
	void expandsRepeat() {
		assertEquals(List.of("a", "b", "c", "b", "c", "d"), AutoTest.expand(List.of("a", "repeat 2 { b", "c }", "d")));
		assertEquals(List.of("x", "x"), AutoTest.expand(List.of("repeat 2 {", "x", "}")));
	}

	@Test
	void parsesConfigs() {
		assertEquals(List.of(new AutoTest.Config("off", List.of("rtset enabled=false")), new AutoTest.Config("gi", List.of("rtset enabled=true,gi=medium", "upscale fsr")),
				new AutoTest.Config("c3", List.of("rt full"))), AutoTest.parseConfigs("off:rtset enabled=false|gi:rtset enabled=true,gi=medium;upscale fsr|rt full"));
	}

	@Test
	void convergesOnceStableAcrossTheLag() {
		var c = new Convergence();
		long ms = 1_000_000L;
		int[] a = new int[30], b = new int[30];
		java.util.Arrays.fill(b, 10);
		c.add(0, 0, a);
		c.add(100, 300 * ms, b); // changed: diff 10
		org.junit.jupiter.api.Assertions.assertFalse(c.converged());
		for (int i = 2; i < 2 + Convergence.STABLE; i++) {
			org.junit.jupiter.api.Assertions.assertFalse(c.converged());
			c.add(100L * i, 300L * i * ms, b);
		}
		org.junit.jupiter.api.Assertions.assertTrue(c.converged());
		c.add(10_000, 10_000 * ms, a);
		org.junit.jupiter.api.Assertions.assertFalse(c.converged());
	}
}
