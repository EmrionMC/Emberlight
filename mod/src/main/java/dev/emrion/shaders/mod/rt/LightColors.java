package dev.emrion.shaders.mod.rt;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ComparatorMode;

/**
 * Light source types for RT block light. A type is an index into one fixed table: the vanilla emitters by name (colour for
 * RT style RTX, RGB555 with the brightest channel at full; first matching name fragment wins, dyed candles by dye, unknown
 * blocks warm white), then the sources only the pack's coloured light knows (Complementary's floodfill, {@code COLORED_LIGHTING},
 * with the pack's colours from {@code GetSpecialBlocklightColor}): they emit no vanilla light, the pack gives them "extra light"
 * (the colour table's alpha), so RT gives them a range and a power of their own ({@link #level}, {@link #power}). Called from
 * section compile workers; results are cached per block state.
 */
public final class LightColors {
	private LightColors() {}

	/** The pack option group that makes a source glow ({@link RtLights#enable}); VANILLA always does. */
	public enum Kind { VANILLA, DIODE, WIRE, ORE, NETHER_TREE, IPBR, REDSTONE_BLOCK, LAPIS_BLOCK }

	/** Powered repeaters and comparators: redstone red, a touch less green than the torches. */
	private static final float[] DIODE_RGB = {1.0f, 0.09f, 0.05f};
	/** Copper torches and lanterns: r5.10's green (light id 84, vec3(0.7, 1.35, 0.8)), before "torch" and "lantern" match them. */
	private static final float[] COPPER_RGB = {0.52f, 1.0f, 0.59f};

	private static final String[] NAMES = {
			"soul", "copper_torch", "copper_wall_torch", "copper_lantern", "redstone_torch", "redstone_wall_torch", "repeater", "comparator", "redstone_ore", "redstone_lamp", "lava", "magma", "campfire", "fire",
			"jack_o_lantern", "torch", "lantern", "glowstone", "shroomlight", "sea_lantern", "conduit", "beacon", "end_rod",
			"amethyst", "ochre_froglight", "verdant_froglight", "pearlescent_froglight", "crying_obsidian", "respawn_anchor",
			"glow_lichen", "sculk", "copper_bulb", "furnace", "smoker", "nether_portal", "end_portal", "enchanting",
			"brewing_stand", "light", "candle", "cave_vines", "glow_berries", "firefly", "creaking_heart",
	};
	private static final float[][] RGB = {
			{0.25f, 0.75f, 1.0f}, COPPER_RGB, COPPER_RGB, COPPER_RGB, {1.0f, 0.12f, 0.05f}, {1.0f, 0.12f, 0.05f}, DIODE_RGB, DIODE_RGB, {1.0f, 0.15f, 0.08f}, {1.0f, 0.62f, 0.35f},
			{1.0f, 0.40f, 0.08f}, {1.0f, 0.35f, 0.10f}, {1.0f, 0.50f, 0.15f}, {1.0f, 0.50f, 0.15f},
			{1.0f, 0.58f, 0.20f}, {1.0f, 0.60f, 0.28f}, {1.0f, 0.66f, 0.34f}, {1.0f, 0.80f, 0.50f}, {1.0f, 0.55f, 0.25f},
			{0.65f, 0.90f, 1.0f}, {0.65f, 0.90f, 1.0f}, {0.75f, 0.95f, 1.0f}, {0.95f, 0.90f, 1.0f},
			{0.70f, 0.40f, 1.0f}, {1.0f, 0.85f, 0.45f}, {0.60f, 1.0f, 0.50f}, {1.0f, 0.60f, 0.90f}, {0.60f, 0.20f, 1.0f}, {0.70f, 0.30f, 1.0f},
			{0.60f, 0.95f, 0.80f}, {0.20f, 0.80f, 0.90f}, {1.0f, 0.75f, 0.50f}, {1.0f, 0.55f, 0.20f}, {1.0f, 0.55f, 0.20f},
			{0.65f, 0.25f, 1.0f}, {0.45f, 0.55f, 1.0f}, {0.70f, 0.50f, 1.0f},
			{1.0f, 0.75f, 0.45f}, {1.0f, 0.95f, 0.90f}, {1.0f, 0.65f, 0.30f}, {1.0f, 0.70f, 0.30f}, {1.0f, 0.70f, 0.30f}, {0.80f, 1.0f, 0.40f}, {1.0f, 0.55f, 0.20f},
	};
	private static final String[] DYES = {"white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime", "green", "cyan",
			"light_blue", "blue", "purple", "magenta", "pink"};
	private static final float[][] DYE_RGB = {{1.0f, 0.95f, 0.9f}, {0.8f, 0.8f, 0.8f}, {0.6f, 0.6f, 0.65f}, {0.4f, 0.35f, 0.5f}, {1.0f, 0.6f, 0.3f},
			{1.0f, 0.15f, 0.1f}, {1.0f, 0.45f, 0.08f}, {1.0f, 0.9f, 0.2f}, {0.55f, 1.0f, 0.15f}, {0.3f, 0.8f, 0.1f}, {0.1f, 0.9f, 0.9f},
			{0.35f, 0.7f, 1.0f}, {0.2f, 0.3f, 1.0f}, {0.6f, 0.2f, 1.0f}, {1.0f, 0.25f, 0.9f}, {1.0f, 0.5f, 0.7f}};
	private static final float[] WARM_WHITE = {1.0f, 0.82f, 0.62f};

	/**
	 * Sources only the pack lights (Complementary r5.10 + Euphoria 1.11.2, lightVoxelization.glsl / blocklightColorsACT.glsl):
	 * block name fragment, the pack's colour (normalised to its brightest channel), its extra-light alpha, the option group.
	 * Not listed: sculk (alpha 0.0005, practically no light, and thousands of blocks in the Deep Dark), emitters with vanilla
	 * light (lava, magma, amethyst, ... are vanilla sources above).
	 */
	private record Pack(String name, float[] rgb, float alpha, Kind kind) {}

	private static final Pack[] PACK = {
			// first matching fragment wins: subtract_comparator before comparator
			new Pack("subtract_comparator", DIODE_RGB, 0.002f, Kind.WIRE), new Pack("repeater", DIODE_RGB, 0.0125f, Kind.DIODE),
			new Pack("comparator", DIODE_RGB, 0.0125f, Kind.DIODE), new Pack("redstone_wire", DIODE_RGB, 0.002f, Kind.WIRE),
			new Pack("redstone_ore", new float[]{1.0f, 0.025f, 0.025f}, 0.1f, Kind.ORE),
			new Pack("iron_ore", new float[]{1.0f, 0.53f, 0.24f}, 0.05f, Kind.ORE), new Pack("nether_gold_ore", new float[]{1.0f, 0.65f, 0.12f}, 0.05f, Kind.ORE),
			new Pack("gold_ore", new float[]{1.0f, 0.65f, 0.12f}, 0.1f, Kind.ORE), new Pack("copper_ore", new float[]{1.0f, 0.47f, 0.24f}, 0.05f, Kind.ORE),
			new Pack("lapis_ore", new float[]{0.25f, 0.25f, 1.0f}, 0.1f, Kind.ORE), new Pack("emerald_ore", new float[]{0.14f, 1.0f, 0.14f}, 0.1f, Kind.ORE),
			new Pack("diamond_ore", new float[]{0.25f, 1.0f, 1.0f}, 0.15f, Kind.ORE), new Pack("nether_quartz_ore", new float[]{1.0f, 1.0f, 1.0f}, 0.05f, Kind.ORE),
			new Pack("gilded_blackstone", new float[]{1.0f, 0.65f, 0.12f}, 0.05f, Kind.ORE), new Pack("ancient_debris", new float[]{1.0f, 0.44f, 0.22f}, 0.15f, Kind.ORE),
			new Pack("crimson_stem", new float[]{1.0f, 0.17f, 0.11f}, 0.1f, Kind.NETHER_TREE), new Pack("crimson_hyphae", new float[]{1.0f, 0.17f, 0.11f}, 0.1f, Kind.NETHER_TREE),
			new Pack("warped_stem", new float[]{0.16f, 1.0f, 0.79f}, 0.1f, Kind.NETHER_TREE), new Pack("warped_hyphae", new float[]{0.16f, 1.0f, 0.79f}, 0.1f, Kind.NETHER_TREE),
			new Pack("command_block", new float[]{1.0f, 0.35f, 0.1f}, 0.1f, Kind.IPBR), new Pack("structure_block", new float[]{1.0f, 1.0f, 1.0f}, 0.15f, Kind.IPBR),
			new Pack("jigsaw", new float[]{1.0f, 1.0f, 1.0f}, 0.15f, Kind.IPBR), new Pack("test_block", new float[]{1.0f, 1.0f, 1.0f}, 0.15f, Kind.IPBR),
			new Pack("test_instance_block", new float[]{1.0f, 1.0f, 1.0f}, 0.15f, Kind.IPBR), new Pack("spawner", new float[]{1.0f, 0.14f, 1.0f}, 0.15f, Kind.IPBR),
			new Pack("warped_fungus", new float[]{1.0f, 0.3f, 0.067f}, 0.0125f, Kind.IPBR), new Pack("crimson_fungus", new float[]{1.0f, 0.3f, 0.067f}, 0.0125f, Kind.IPBR),
			new Pack("weeping_vines_plant", new float[]{1.0f, 0.3f, 0.067f}, 0.0125f, Kind.IPBR), new Pack("chorus_flower", new float[]{1.0f, 0.5f, 0.75f}, 0.1f, Kind.IPBR),
			new Pack("redstone_block", new float[]{1.0f, 0.025f, 0.025f}, 0.15f, Kind.REDSTONE_BLOCK), new Pack("lapis_block", new float[]{0.25f, 0.25f, 1.0f}, 0.15f, Kind.LAPIS_BLOCK),
			// new in r5.10 (light id 40, as the brewing stand); r5.10's other new block light, ice_crystal, is a 26.4 block
			new Pack("torchflower", new float[]{1.0f, 0.48f, 0.16f}, 0.05f, Kind.IPBR),
	};

	/** Type ids: [0, NAMES) by name, then the dyes, warm white, then {@link #PACK}. */
	private static final int DYE0 = NAMES.length, WHITE = DYE0 + DYES.length, PACK0 = WHITE + 1;
	public static final int TYPES = PACK0 + PACK.length;

	private static final Map<BlockState, Integer> CACHE = new ConcurrentHashMap<>();
	private static volatile Set<Block> packBlocks;

	/** Cheap test for every block of a compiled section: whether it can be a source at all (vanilla light or a pack-only block). */
	public static boolean candidate(BlockState state) {
		if (state.getLightEmission() > 0) return true;
		Set<Block> p = packBlocks;
		if (p == null) packBlocks = p = packBlocks();
		return p.contains(state.getBlock());
	}

	private static Set<Block> packBlocks() {
		Set<Block> s = new ReferenceOpenHashSet<>();
		for (Block b : BuiltInRegistries.BLOCK) if (packIndex(BuiltInRegistries.BLOCK.getKey(b).getPath()) >= 0) s.add(b);
		return s;
	}

	/** The source type of a state, -1 if it is none (an unpowered repeater, a lit redstone ore is vanilla, unlit wire). */
	public static int type(BlockState state) {
		return CACHE.computeIfAbsent(state, LightColors::resolve);
	}

	private static int resolve(BlockState state) {
		String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		// End portals and gateways: their light leaves through the portal surface and the vanilla floodfill wraps it round the
		// bedrock; as a point source RT left every bedrock face turned away from the block centre at the ambient floor (black
		// gateway sides in the End, where nothing else fills them). The pack lights them alone, as with RT off.
		if (name.equals("end_portal") || name.equals("end_gateway")) return -1;
		if (state.getLightEmission() > 0) return vanilla(name);
		Block b = state.getBlock();
		if (b instanceof RedstoneWireBlock && state.getValue(RedstoneWireBlock.POWER) == 0) return -1;
		if (b instanceof ComparatorBlock && !state.getValue(DiodeBlock.POWERED))
			return state.getValue(ComparatorBlock.MODE) == ComparatorMode.SUBTRACT ? PACK0 + packIndex("subtract_comparator") : -1;
		if (b instanceof DiodeBlock && !state.getValue(DiodeBlock.POWERED)) return -1;
		if (b instanceof RedStoneOreBlock && state.getValue(RedStoneOreBlock.LIT)) return vanilla(name); // lit: vanilla light 9
		int i = packIndex(name);
		return i < 0 ? -1 : PACK0 + i;
	}

	static int vanilla(String name) {
		if (name.endsWith("candle") || name.endsWith("candle_cake"))
			for (int i = DYES.length - 1; i >= 0; i--) if (name.startsWith(DYES[i] + "_")) return DYE0 + i;
		for (int i = 0; i < NAMES.length; i++) if (name.contains(NAMES[i])) return i;
		return WHITE;
	}

	/** Type id of a pack-only source by block name, -1 if none (tests). */
	static int packType(String name) {
		int i = packIndex(name);
		return i < 0 ? -1 : PACK0 + i;
	}

	private static int packIndex(String name) {
		if (name.contains("trial_spawner")) return -1; // only active trial spawners glow, and those have vanilla light
		if (name.contains("torchflower_crop")) return -1; // the pack lights only the grown flower
		if (name.startsWith("potted_")) name = name.substring(7);
		for (int i = 0; i < PACK.length; i++) if (name.contains(PACK[i].name)) return i;
		return -1;
	}

	public static float[] rgb(int type) {
		return type < DYE0 ? RGB[type] : type < WHITE ? DYE_RGB[type - DYE0] : type == WHITE ? WARM_WHITE : PACK[type - PACK0].rgb;
	}

	public static Kind kind(int type) {
		return type < PACK0 ? Kind.VANILLA : PACK[type - PACK0].kind;
	}

	/**
	 * Range in blocks of a pack-only source (a vanilla source's is its light level). The pack's extra light spreads through its
	 * floodfill, which loses about a third per block, so a source twice as strong (alpha) reaches about two blocks further.
	 * Measured against RT off (RAYTRACING.md §32).
	 */
	public static int level(int type) {
		if (type < PACK0) return 0;
		double a = PACK[type - PACK0].alpha;
		return (int) Math.clamp(Math.round(LEVEL_AT_ORE + 2.0 * Math.log(a / 0.05) / Math.log(2.0)), 3, 15);
	}

	/**
	 * A vanilla emitter the pack also gives extra coloured light (blocklightColorsACT.glsl: lava LAVA_ACT_ALPHA 0.8, magma 0.1;
	 * glowstone, sea lanterns, lamps, torches have 0). The pack's floodfill sums that light over every block, so a lava pool adds
	 * up where a glowstone wall lights like its brightest block (RTX GI's cluster rule, rtx/gi_common.glsl). RAYTRACING.md §37.
	 */
	public static boolean packSums(int type) {
		return type == LAVA || type == MAGMA;
	}

	static final int LAVA = vanilla("lava"), MAGMA = vanilla("magma");
	private static final int REDSTONE_TORCH = vanilla("redstone_torch"), REDSTONE_WALL_TORCH = vanilla("redstone_wall_torch");
	/** r5.10 raised the redstone torch's light colour from redstoneSpecialLightColor * 0.25 to * 0.4 (light id 35, wall torch too). */
	static final float REDSTONE_TORCH_POWER = 1.6f;

	/** Brightness of a source relative to a vanilla source of the same level: 1 for vanilla (but redstone torches), from the pack's alpha otherwise. */
	public static float power(int type, float oreMult) {
		if (type < PACK0) return type == REDSTONE_TORCH || type == REDSTONE_WALL_TORCH ? REDSTONE_TORCH_POWER : 1f;
		Pack p = PACK[type - PACK0];
		float a = p.alpha * (p.kind == Kind.ORE ? oreMult : 1f);
		float k = p.kind == Kind.WIRE || p.kind == Kind.DIODE ? REDSTONE_MULT : 1f;
		return (float) (k * POWER_AT_ORE * Math.sqrt(a / 0.05)); // the pack takes the square root of the floodfill alpha
	}

	/** Calibration (RAYTRACING.md §32): range and brightness of a source with the pack's ore alpha 0.05 (iron, copper). */
	static final int LEVEL_AT_ORE = 9;
	/** Redstone wire and diodes against the other sources in reach: the pack sums their many weak floodfills (repeater rows -14 % -> -9 %). */
	static final float REDSTONE_MULT = 5f;
	static final float POWER_AT_ORE = 0.25f; // with the source in the air in front (bl_common.glsl emrBlSourceAt): single-block cells within 5 % of RT off

	public static int rgb555(float[] c) {
		return Math.round(c[0] * 31) << 10 | Math.round(c[1] * 31) << 5 | Math.round(c[2] * 31);
	}
}
