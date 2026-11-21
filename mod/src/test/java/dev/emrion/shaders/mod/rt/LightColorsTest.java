package dev.emrion.shaders.mod.rt;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LightColorsTest {
	@Test
	void tableOrderAndDyes() {
		assertArrayEquals(new float[]{0.25f, 0.75f, 1.0f}, LightColors.rgb(LightColors.vanilla("soul_lantern")));
		assertArrayEquals(new float[]{1.0f, 0.12f, 0.05f}, LightColors.rgb(LightColors.vanilla("redstone_wall_torch")));
		assertArrayEquals(new float[]{1.0f, 0.58f, 0.20f}, LightColors.rgb(LightColors.vanilla("jack_o_lantern")));
		assertArrayEquals(new float[]{1.0f, 0.40f, 0.08f}, LightColors.rgb(LightColors.vanilla("lava")));
		assertArrayEquals(new float[]{0.35f, 0.7f, 1.0f}, LightColors.rgb(LightColors.vanilla("light_blue_candle")));
		assertArrayEquals(new float[]{0.2f, 0.3f, 1.0f}, LightColors.rgb(LightColors.vanilla("blue_candle")));
		assertArrayEquals(new float[]{1.0f, 0.65f, 0.30f}, LightColors.rgb(LightColors.vanilla("candle")));
		assertArrayEquals(new float[]{1.0f, 0.82f, 0.62f}, LightColors.rgb(LightColors.vanilla("some_modded_lamp")));
		// copper torches and lanterns are r5.10's green, not the torch or lantern orange; the other "*torch*" keep their own
		for (String n : new String[]{"copper_torch", "copper_wall_torch", "copper_lantern", "oxidized_copper_lantern", "waxed_weathered_copper_lantern"})
			assertArrayEquals(new float[]{0.52f, 1.0f, 0.59f}, LightColors.rgb(LightColors.vanilla(n)), n);
		assertArrayEquals(new float[]{1.0f, 0.60f, 0.28f}, LightColors.rgb(LightColors.vanilla("wall_torch")));
		assertArrayEquals(new float[]{0.25f, 0.75f, 1.0f}, LightColors.rgb(LightColors.vanilla("soul_wall_torch")));
		assertArrayEquals(new float[]{1.0f, 0.12f, 0.05f}, LightColors.rgb(LightColors.vanilla("redstone_torch")));
		assertEquals(31 << 10 | 12 << 5 | 2, LightColors.rgb555(LightColors.rgb(LightColors.vanilla("lava"))));
	}

	@Test
	void packOnlySources() {
		int iron = LightColors.packType("deepslate_iron_ore"), wire = LightColors.packType("redstone_wire");
		int diamond = LightColors.packType("diamond_ore"), diode = LightColors.packType("repeater");
		assertEquals(LightColors.Kind.ORE, LightColors.kind(iron));
		assertEquals(LightColors.Kind.NETHER_TREE, LightColors.kind(LightColors.packType("stripped_crimson_hyphae")));
		assertEquals(LightColors.Kind.IPBR, LightColors.kind(LightColors.packType("potted_warped_fungus")));
		assertEquals(LightColors.Kind.DIODE, LightColors.kind(LightColors.packType("comparator")));
		// an unpowered subtract comparator glows like wire, not like a powered diode (substring order)
		assertEquals(LightColors.Kind.WIRE, LightColors.kind(LightColors.packType("subtract_comparator")));
		assertEquals(LightColors.level(wire), LightColors.level(LightColors.packType("subtract_comparator")));
		assertEquals(LightColors.packType("gold_ore") + 0, LightColors.packType("deepslate_gold_ore"));
		assertNotEquals(LightColors.packType("gold_ore"), LightColors.packType("nether_gold_ore"));
		assertEquals(-1, LightColors.packType("trial_spawner"));
		assertEquals(-1, LightColors.packType("stone"));
		assertEquals(LightColors.Kind.IPBR, LightColors.kind(LightColors.packType("potted_torchflower")));
		assertEquals(-1, LightColors.packType("torchflower_crop"));
		assertEquals(LightColors.Kind.VANILLA, LightColors.kind(LightColors.vanilla("torch")));
		assertEquals(1f, LightColors.power(LightColors.vanilla("torch"), 2f));
		// r5.10: redstone torches (wall ones too) at redstone * 0.4 instead of 0.25; lit lamps unchanged
		assertEquals(1.6f, LightColors.power(LightColors.vanilla("redstone_torch"), 1f), 1e-6);
		assertEquals(1.6f, LightColors.power(LightColors.vanilla("redstone_wall_torch"), 1f), 1e-6);
		assertEquals(1f, LightColors.power(LightColors.vanilla("redstone_lamp"), 1f));
		assertEquals(1f, LightColors.power(LightColors.vanilla("copper_torch"), 1f));
		// stronger in the pack (alpha): longer range, more power; ore brightness scales ores only
		assertEquals(LightColors.LEVEL_AT_ORE, LightColors.level(iron));
		assertTrue(LightColors.level(diamond) > LightColors.level(iron) && LightColors.level(iron) > LightColors.level(diode));
		assertTrue(LightColors.level(diode) > LightColors.level(wire) && LightColors.level(wire) >= 3);
		assertTrue(LightColors.power(diamond, 1f) > LightColors.power(iron, 1f) && LightColors.power(diode, 1f) > LightColors.power(wire, 1f));
		assertEquals(2 * LightColors.power(iron, 1f), LightColors.power(iron, 4f), 1e-6);
		assertEquals(LightColors.power(diode, 1f), LightColors.power(diode, 4f));
	}

}
