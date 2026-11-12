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
}
