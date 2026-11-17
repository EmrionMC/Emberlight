"""Emberlight PBR material table: vanilla block texture name -> LabPBR 1.3 parameters.

Rules are matched in order against the texture name without ".png" (fnmatch globs); the first match wins.
The last rule ("*") is the rough-dielectric default that covers every block nobody tuned.

Parameters (all optional, see DEFAULT):
  smooth      perceptual smoothness 0..1 (LabPBR _s red)
  svar        how much darker/brighter texels lose/gain smoothness (cracks and grout get rougher)
  f0          dielectric reflectance 0..0.898 (stored linearly in _s green, 0..229)
  metal       LabPBR metal id 230..255 instead of f0 (230 iron, 231 gold, 234 copper, 255 albedo-as-F0)
  por / sss   porosity 0..1 (rain darkening) / subsurface scattering 0..1 (_s blue; sss wins when > 0)
  emit        emission 0..1 on texels whose value (max channel) lies above elo, full at ehi (_s alpha)
  normal      normal strength from the luminance height field
  depth       POM depth 0..1 of LabPBR's maximum (25 % of a block), _n alpha
  hsrc        height source: 'lum' (bright = raised, default) or 'sat' (grey = low: light mortar between red bricks)
  ao          baked cavity occlusion strength (_n blue)
  mode        per-texel classification:
                copper  orange texels are copper metal, green/teal patina texels are rough dielectric
                ore     texels that differ from the host stone get the 'gem' parameters
                trim    grey low-saturation texels become iron (230), yellow ones gold (231); the rest keep the rule
  gem         parameter overrides for ore crystal texels (mode ore)
"""

DEFAULT = dict(smooth=0.22, svar=0.25, f0=0.04, metal=None, por=0.35, sss=0.0, emit=0.0, elo=0.0, ehi=1.0,
               normal=1.0, depth=0.30, ao=0.35, mode=None, gem=None, hsrc='lum')

# Shorthands
POLISHED = dict(smooth=0.90, svar=0.10, f0=0.045, por=0.0, normal=0.35, depth=0.06, ao=0.12)
GLOSSY = dict(smooth=0.80, svar=0.15, f0=0.04, por=0.02, normal=0.5, depth=0.10, ao=0.18)
SATIN = dict(smooth=0.55, svar=0.25, f0=0.04, por=0.08, normal=0.8, depth=0.20, ao=0.25)
ROUGH_STONE = dict(smooth=0.25, svar=0.35, por=0.30, normal=1.2, depth=0.40, ao=0.40)
SOIL = dict(smooth=0.06, svar=0.10, por=0.85, normal=1.1, depth=0.35, ao=0.40)
FOLIAGE = dict(smooth=0.38, svar=0.15, por=0.0, sss=0.75, normal=0.6, depth=0.0, ao=0.25)
WOOD = dict(smooth=0.42, svar=0.30, por=0.25, normal=0.9, depth=0.35, ao=0.30)  # varnished planks, RTX-style
BARK = dict(smooth=0.12, svar=0.20, por=0.45, normal=1.4, depth=0.45, ao=0.45)
FABRIC = dict(smooth=0.04, svar=0.05, por=0.60, sss=0.0, normal=0.7, depth=0.15, ao=0.25)
IRON = dict(metal=230, smooth=0.86, svar=0.20, por=0.0, normal=0.6, depth=0.10, ao=0.20)
GOLD = dict(metal=231, smooth=0.92, svar=0.15, por=0.0, normal=0.5, depth=0.08, ao=0.15)
GEM = dict(smooth=0.96, svar=0.05, f0=0.17, por=0.0, normal=0.4, depth=0.05, ao=0.10)
ICE = dict(smooth=0.97, svar=0.03, f0=0.02, por=0.0, sss=0.45, normal=0.25, depth=0.0, ao=0.05)
GLASS = dict(smooth=0.98, svar=0.0, f0=0.04, por=0.0, normal=0.15, depth=0.0, ao=0.0)
LIGHT = dict(smooth=0.70, svar=0.10, por=0.0, normal=0.5, depth=0.05, ao=0.10)


def m(*bases, **kw):
    out = {}
    for b in bases:
        out.update(b)
    out.update(kw)
    return out


def copper(stage_smooth):
    return m(IRON, metal=234, smooth=stage_smooth, mode='copper')


COPPER_STAGES = [('', 0.90), ('exposed_', 0.72), ('weathered_', 0.58), ('oxidized_', 0.50)]

RULES = []
# ---- overlays and helpers: flat, nothing to shade ----
RULES += [
    (['cobweb', 'tripwire', 'destroy_stage_*', 'destroy_oit_stage_*', 'debug*'], dict(smooth=0.0, normal=0.0, depth=0.0, ao=0.0, por=0.0)),
]
# ---- emissives (brightest texels glow; the dim rest keeps a plausible surface) ----
RULES += [
    (['glowstone'], m(LIGHT, smooth=0.45, emit=1.0, elo=0.45, ehi=0.85)),
    (['sea_lantern'], m(LIGHT, smooth=0.92, f0=0.05, emit=1.0, elo=0.55, ehi=0.90)),
    (['shroomlight'], m(LIGHT, smooth=0.35, sss=0.5, emit=1.0, elo=0.55, ehi=0.95)),
    (['*_froglight_*'], m(LIGHT, smooth=0.60, sss=0.6, emit=0.95, elo=0.45, ehi=0.85)),
    (['redstone_lamp_on'], m(LIGHT, smooth=0.75, emit=1.0, elo=0.50, ehi=0.85)),
    (['redstone_lamp'], m(GLOSSY, smooth=0.70)),
    (['lava_still', 'lava_flow'], m(LIGHT, smooth=0.55, normal=0.8, emit=1.0, elo=0.30, ehi=0.75)),
    (['magma'], m(ROUGH_STONE, smooth=0.35, emit=1.0, elo=0.55, ehi=0.85)),
    (['end_rod'], m(GLASS, smooth=0.90, emit=1.0, elo=0.75, ehi=0.95)),
    (['beacon'], m(GLASS, emit=1.0, elo=0.80, ehi=0.97)),
    (['conduit'], m(GLOSSY, emit=0.8, elo=0.70, ehi=0.95)),
    (['torch', 'soul_torch', 'copper_torch', 'redstone_torch'], m(WOOD, smooth=0.2, emit=1.0, elo=0.78, ehi=0.95)),
    (['redstone_torch_off'], m(WOOD, smooth=0.2)),
    (['jack_o_lantern'], m(WOOD, smooth=0.35, sss=0.3, emit=1.0, elo=0.78, ehi=0.95)),
    (['*lantern'], m(IRON, mode='trim', smooth=0.75, emit=1.0, elo=0.62, ehi=0.88)),
    (['*candle_lit'], m(SATIN, smooth=0.5, sss=0.4, emit=1.0, elo=0.85, ehi=0.98)),
    (['*candle'], m(SATIN, smooth=0.5, sss=0.4)),
    (['fire_*', 'soul_fire_*', 'campfire_fire', 'soul_campfire_fire'], m(LIGHT, emit=1.0, elo=0.2, ehi=0.7)),
    (['campfire_log_lit', 'soul_campfire_log_lit'], m(BARK, emit=1.0, elo=0.65, ehi=0.9)),
    (['furnace_front_on', 'smoker_front_on', 'blast_furnace_front_on'], m(ROUGH_STONE, emit=1.0, elo=0.72, ehi=0.92)),
    (['respawn_anchor_side[1-4]', 'respawn_anchor_top'], m(GLOSSY, smooth=0.85, f0=0.05, emit=1.0, elo=0.55, ehi=0.85)),
    (['crying_obsidian'], m(POLISHED, smooth=0.94, f0=0.05, emit=0.8, elo=0.55, ehi=0.85)),
    (['cave_vines_lit', 'cave_vines_plant_lit'], m(FOLIAGE, emit=1.0, elo=0.75, ehi=0.95)),
    (['glow_lichen'], m(FOLIAGE, emit=0.5, elo=0.6, ehi=0.9)),
    (['sea_pickle'], m(FOLIAGE, emit=0.8, elo=0.6, ehi=0.9)),
    (['*copper_bulb_lit*'], copper(0.85) | dict(emit=1.0, elo=0.80, ehi=0.97)),
    (['repeater_on', 'comparator_on', 'redstone_dust_*'], m(SATIN, smooth=0.6, emit=0.7, elo=0.75, ehi=0.95)),
    (['powered_rail_on', 'detector_rail_on', 'activator_rail_on'], m(WOOD, mode='trim', emit=0.8, elo=0.85, ehi=0.98)),
    (['lightning_rod_on'], copper(0.9) | dict(emit=1.0, elo=0.85, ehi=0.98)),
    (['sculk_sensor_tendril_active', 'calibrated_sculk_sensor_amethyst'], m(SATIN, emit=0.8, elo=0.55, ehi=0.9)),
    (['sculk_catalyst_*_bloom', 'sculk*'], m(SATIN, smooth=0.45, sss=0.3, emit=0.35, elo=0.70, ehi=0.95)),
    (['nether_portal'], m(GLASS, emit=0.9, elo=0.3, ehi=0.8)),
    (['end_portal_frame_eye'], m(GEM, emit=0.6, elo=0.55, ehi=0.9)),
    (['open_eyeblossom_emissive', 'firefly_bush_emissive'], m(FOLIAGE, emit=1.0, elo=0.3, ehi=0.7)),
    (['creaking_heart_*awake'], m(BARK, emit=0.9, elo=0.75, ehi=0.95)),
    (['trial_spawner_*_active*', 'vault_*_on*', 'vault_*_ejecting*'], m(IRON, smooth=0.6, metal=255, emit=1.0, elo=0.80, ehi=0.97)),
    (['enchanting_table_top'], m(GEM, smooth=0.9, f0=0.06, emit=0.15, elo=0.85, ehi=1.0)),
]
# ---- metals ----
for pfx, s in COPPER_STAGES:
    RULES.append(([f'{pfx}copper', f'{pfx}copper_block', f'{pfx}cut_copper', f'{pfx}chiseled_copper',
                   f'{pfx}copper_grate', f'{pfx}copper_bars', f'{pfx}copper_chain', f'{pfx}copper_door_*',
                   f'{pfx}copper_trapdoor', f'{pfx}copper_bulb*', f'{pfx}lightning_rod'], copper(s)))
RULES += [
    (['copper_ore', 'deepslate_copper_ore'], m(ROUGH_STONE, mode='ore', gem=m(IRON, metal=234, smooth=0.6))),
    (['raw_copper_block'], copper(0.45) | dict(normal=1.1, depth=0.3)),
    (['iron_block'], m(IRON, smooth=0.90)),
    (['iron_bars', 'iron_chain', 'iron_door_*', 'iron_trapdoor'], m(IRON, smooth=0.80)),
    (['anvil', 'anvil_top', 'chipped_anvil_top', 'damaged_anvil_top'], m(IRON, smooth=0.72, normal=0.8)),
    (['cauldron_*', 'hopper_*'], m(IRON, smooth=0.65, normal=0.8)),
    (['heavy_core'], m(IRON, smooth=0.75)),
    (['raw_iron_block'], m(IRON, smooth=0.40, normal=1.1, depth=0.3)),
    (['gold_block'], m(GOLD)),
    (['raw_gold_block'], m(GOLD, smooth=0.45, normal=1.1, depth=0.3)),
    (['gilded_blackstone'], m(ROUGH_STONE, mode='trim', smooth=0.35)),
    (['netherite_block'], m(IRON, metal=255, smooth=0.88)),
    (['ancient_debris_*'], m(ROUGH_STONE, smooth=0.3)),
    (['rail', 'rail_corner', 'powered_rail', 'detector_rail', 'activator_rail'], m(WOOD, mode='trim', smooth=0.45)),
    (['bell_*'], m(GOLD, smooth=0.88)),
    (['chain_command_block_*', 'command_block_*', 'repeating_command_block_*', 'structure_block*', 'jigsaw_*'], m(IRON, metal=255, smooth=0.7)),
    (['piston_top', 'piston_top_sticky', 'piston_bottom', 'piston_side', 'piston_inner'], m(WOOD, mode='trim', smooth=0.45)),
    (['lodestone_*'], m(IRON, metal=255, smooth=0.6)),
    (['tripwire_hook', 'lever'], m(WOOD, mode='trim')),
    (['spawner', 'trial_spawner_*', 'vault_*'], m(IRON, metal=255, smooth=0.6)),
    (['blast_furnace_*'], m(IRON, metal=255, smooth=0.55, mode='trim')),
    (['stonecutter_saw'], m(IRON, smooth=0.8)),
    (['brewing_stand'], m(GOLD, smooth=0.7)),
    (['daylight_detector_*'], m(GLOSSY, smooth=0.75)),
    (['observer_*', 'dispenser_*', 'dropper_*', 'crafter_*'], m(ROUGH_STONE, smooth=0.35)),
]
# ---- gems, crystals, glass, ice ----
RULES += [
    (['diamond_block'], m(GEM)),
    (['emerald_block'], m(GEM, f0=0.07)),
    (['lapis_block'], m(GLOSSY, smooth=0.82)),
    (['redstone_block'], m(GLOSSY, smooth=0.78, emit=0.10, elo=0.6, ehi=1.0)),
    (['coal_block'], m(GLOSSY, smooth=0.6, f0=0.05)),
    (['amethyst_block', 'budding_amethyst'], m(GEM, smooth=0.90, f0=0.05, sss=0.35, normal=0.5)),
    (['*amethyst_bud', 'amethyst_cluster'], m(GEM, smooth=0.9, f0=0.05, sss=0.5, emit=0.25, elo=0.75, ehi=1.0)),
    (['diamond_ore', 'deepslate_diamond_ore'], m(ROUGH_STONE, mode='ore', gem=m(GEM, emit=0.18, elo=0.0, ehi=0.01))),
    (['emerald_ore', 'deepslate_emerald_ore'], m(ROUGH_STONE, mode='ore', gem=m(GEM, f0=0.07, emit=0.15, elo=0.0, ehi=0.01))),
    (['lapis_ore', 'deepslate_lapis_ore'], m(ROUGH_STONE, mode='ore', gem=m(GLOSSY, emit=0.10, elo=0.0, ehi=0.01))),
    (['redstone_ore', 'deepslate_redstone_ore'], m(ROUGH_STONE, mode='ore', gem=m(GLOSSY, emit=0.30, elo=0.0, ehi=0.01))),
    (['nether_quartz_ore'], m(ROUGH_STONE, mode='ore', gem=m(POLISHED))),
    (['gold_ore', 'deepslate_gold_ore', 'nether_gold_ore'], m(ROUGH_STONE, mode='ore', gem=m(GOLD, smooth=0.7))),
    (['iron_ore', 'deepslate_iron_ore'], m(ROUGH_STONE, mode='ore', gem=m(IRON, smooth=0.5))),
    (['coal_ore', 'deepslate_coal_ore'], m(ROUGH_STONE, mode='ore', gem=m(GLOSSY, smooth=0.6))),
    (['glass', 'glass_pane_top', '*_stained_glass', '*_stained_glass_pane_top', 'tinted_glass'], m(GLASS)),
    (['ice', 'frosted_ice_*'], m(ICE)),
    (['packed_ice'], m(ICE, smooth=0.92, sss=0.3)),
    (['blue_ice'], m(ICE, smooth=0.96, sss=0.5)),
    (['slime_block'], m(ICE, smooth=0.9, f0=0.03, sss=0.8)),
    (['honey_block_*'], m(ICE, smooth=0.92, f0=0.04, sss=0.9)),
    (['obsidian'], m(POLISHED, smooth=0.95, f0=0.05)),
    (['water_still', 'water_flow', 'water_overlay'], m(ICE, smooth=1.0, sss=0.0, normal=0.0)),
]
# ---- polished / processed stone ----
RULES += [
    (['polished_andesite', 'polished_diorite', 'polished_granite', 'polished_deepslate', 'polished_blackstone',
      'polished_basalt_*', 'polished_tuff', 'polished_cinnabar', 'polished_sulfur'], m(POLISHED)),
    (['polished_blackstone_bricks', 'chiseled_polished_blackstone', 'deepslate_tiles', 'chiseled_deepslate',
      'deepslate_bricks', 'tuff_bricks', 'chiseled_tuff*', 'resin_bricks', 'chiseled_resin_bricks',
      'cinnabar_bricks', 'chiseled_cinnabar', 'sulfur_bricks', 'chiseled_sulfur'], m(GLOSSY, smooth=0.70, normal=0.9, depth=0.25, ao=0.35)),
    (['quartz_block_*', 'quartz_pillar_*', 'chiseled_quartz_block*', 'quartz_bricks'], m(POLISHED, smooth=0.93)),
    (['smooth_stone', 'smooth_stone_slab_side'], m(GLOSSY, smooth=0.75)),
    (['smooth_basalt'], m(GLOSSY, smooth=0.72)),
    (['calcite'], m(SATIN, smooth=0.6, sss=0.2)),
    (['*_concrete'], m(GLOSSY, smooth=0.72, svar=0.05, normal=0.25, depth=0.03)),
    (['*_concrete_powder'], m(SOIL, smooth=0.1)),
    (['*_glazed_terracotta'], m(POLISHED, smooth=0.92, f0=0.05, normal=0.3)),
    (['terracotta', '*_terracotta'], m(SATIN, smooth=0.50)),
    (['prismarine'], m(GLOSSY, smooth=0.75, normal=0.9)),
    (['prismarine_bricks'], m(GLOSSY, smooth=0.82)),
    (['dark_prismarine'], m(GLOSSY, smooth=0.86)),
    (['purpur_*'], m(GLOSSY, smooth=0.72)),
    (['end_stone_bricks'], m(SATIN, smooth=0.45)),
    (['stone_bricks', 'chiseled_stone_bricks'], m(ROUGH_STONE, smooth=0.40, depth=0.35)),
    (['cracked_*'], m(ROUGH_STONE, smooth=0.30)),
    (['mossy_*'], m(ROUGH_STONE, smooth=0.20, por=0.5)),
    (['bricks'], m(ROUGH_STONE, smooth=0.32, hsrc='sat', depth=0.5)),
    (['mud_bricks'], m(ROUGH_STONE, smooth=0.32)),
    (['nether_bricks', 'red_nether_bricks', 'chiseled_nether_bricks'], m(ROUGH_STONE, smooth=0.45)),
    (['cut_sandstone', 'cut_red_sandstone', 'chiseled_sandstone', 'chiseled_red_sandstone'], m(SATIN, smooth=0.40, por=0.3)),
    (['sandstone*', 'red_sandstone*'], m(ROUGH_STONE, smooth=0.28)),
    (['stone', 'andesite', 'diorite', 'granite', 'deepslate', 'deepslate_top', 'tuff', 'blackstone', 'blackstone_top',
      'basalt_*', 'cinnabar', 'sulfur', 'potent_sulfur', 'end_stone', 'dripstone_block', 'pointed_dripstone_*'], m(ROUGH_STONE)),
    (['cobblestone', 'cobbled_deepslate', 'bedrock', 'netherrack', 'gravel', 'reinforced_deepslate_*'], m(ROUGH_STONE, smooth=0.15, depth=0.6)),
    (['bone_block_*'], m(SATIN, smooth=0.5, sss=0.2)),
    (['shulker_box', '*_shulker_box'], m(GLOSSY, smooth=0.78)),
    (['*_bed_*', 'bed_*'], m(FABRIC)),
    (['furnace_*', 'smoker_*', 'stonecutter_*', 'grindstone_*'], m(ROUGH_STONE, smooth=0.35)),
    (['respawn_anchor_*'], m(GLOSSY, smooth=0.85, f0=0.05)),
    (['end_portal_frame_*'], m(GLOSSY, smooth=0.6)),
    (['repeater', 'comparator', 'item_frame', 'glow_item_frame'], m(SATIN, smooth=0.5)),
    (['bee_nest_*', 'straw_bed'], m(WOOD, smooth=0.25)),
    (['creaking_heart*'], m(BARK)),
    (['sulfur_spike_*'], m(ROUGH_STONE, smooth=0.45)),
    (['brewing_stand_base'], m(ROUGH_STONE, smooth=0.5)),
    (['calibrated_sculk_sensor_*'], m(SATIN, smooth=0.45, sss=0.3)),
    (['potted_*', 'red_shrub', 'shelf_mushroom_*'], m(FOLIAGE)),
]
# ---- wood, plants, soil (stay rough / waxy) ----
RULES += [
    (['*_planks', 'bamboo_mosaic', '*_shelf', 'bookshelf', 'chiseled_bookshelf_*', 'crafting_table_*',
      'barrel_*', 'composter_*', 'note_block', 'jukebox_*', 'lectern_*', 'cartography_table_*', 'fletching_table_*',
      'smithing_table_*', 'loom_*', 'beehive_*', 'scaffolding_*'], m(WOOD)),
    (['*_door_*', '*_trapdoor', '*_sign', '*_hanging_sign', 'ladder'], m(WOOD, smooth=0.48)),
    (['stripped_*'], m(WOOD, smooth=0.30)),
    (['*_log', '*_log_top', '*_stem', '*_stem_top', 'bamboo_block*', 'mushroom_stem'], m(BARK)),
    # before the foliage rule, whose '*grass*' matched them (grass blocks were waxy leaves: a glossy sky sheen)
    (['grass_block_top', 'grass_block_side', 'grass_block_side_overlay'], m(SOIL, smooth=0.12, sss=0.0)),
    (['snow', 'powder_snow', 'grass_block_snow'], m(SOIL, smooth=0.35, por=0.0, sss=0.7)),
    (['*leaves', 'vine', '*grass*', 'fern', 'large_fern_*', 'seagrass', 'tall_seagrass_*', 'kelp*', '*_sapling',
      'bush', '*roots*', 'azalea_*', 'flowering_azalea_*', 'moss_block', 'pale_moss_*', '*_vines*', 'lily_pad',
      'sugar_cane', 'bamboo_*', 'cactus_*', '*tulip', 'poppy', 'dandelion', 'allium', 'azure_bluet', 'cornflower',
      'oxeye_daisy', 'lily_of_the_valley', 'wither_rose', 'blue_orchid', 'pink_petals*', 'wildflowers*', 'leaf_litter',
      'big_dripleaf_*', 'small_dripleaf_*', 'spore_blossom*', 'hanging_roots', 'pale_hanging_moss*', 'melon_*',
      'pumpkin_*', 'carved_pumpkin', '*_stage*', 'sweet_berry_bush_*', 'nether_wart_block', 'warped_wart_block',
      '*_mushroom', '*_mushroom_block', 'mushroom_block_inside', 'chorus_*', 'torchflower*', 'pitcher_*', 'firefly_bush',
      '*eyeblossom', 'sunflower_*', 'lilac_*', 'peony_*', 'rose_bush_*', 'dead_bush', 'cactus_flower', 'golden_dandelion',
      'crimson_fungus', 'warped_fungus', 'nether_sprouts', '*_poplar_leaves', 'mangrove_propagule*'], m(FOLIAGE)),
    (['*coral*'], m(SATIN, smooth=0.35, sss=0.4)),
    (['dirt', 'coarse_dirt', 'rooted_dirt', 'farmland', 'podzol_*', 'mycelium_*', 'dirt_path_*', 'soul_soil',
      'soul_sand', 'sand', 'red_sand', 'clay', 'suspicious_*', '*_nylium*', 'packed_mud'], m(SOIL)),
    (['mud', 'farmland_moist', 'muddy_mangrove_roots_*'], m(SOIL, smooth=0.55, por=0.2)),  # wet by nature
    (['*_wool', '*_carpet', 'hay_block_*', 'sponge', 'target_*', 'dried_kelp_*'], m(FABRIC)),
    (['wet_sponge'], m(FABRIC, smooth=0.5, por=0.1)),
    (['tnt_*'], m(SATIN, smooth=0.35)),
    (['cake_*'], m(SATIN, smooth=0.4, sss=0.4)),
    (['resin_block', 'resin_clump'], m(GLOSSY, smooth=0.85, sss=0.5)),
    (['honeycomb_block'], m(SATIN, smooth=0.65, sss=0.4)),
    (['flower_pot', 'decorated_pot*'], m(SATIN, smooth=0.55)),
    (['enchanting_table_*'], m(GLOSSY, smooth=0.8)),
    (['frogspawn', 'turtle_egg*', 'sniffer_egg_*', 'dragon_egg'], m(SATIN, smooth=0.6, sss=0.3)),
]
RULES.append((['*'], {}))

# Build styles (generate.py --style): rules checked before RULES.
#   rtx     the Bedrock "Minecraft with RTX" showpieces: every concrete colour is a tinted perfect mirror (smoothness 1,
#           metal id 255 = the albedo is the F0, so a red block is a red mirror) and plain terracotta (uncoloured and
#           all 16 colours) glows over its whole face like a solid neon tube (terracotta village walls glow too: style
#           subtle is for normal play); glazed terracotta stays a glossy ceramic without emission (its patterns looked
#           odd lit); the iron block is an untinted silver bathroom
#           mirror (metal id 237 silver, flat: the Bedrock RTX docs' own mirror example)
#   subtle  RULES only: glossy concrete, satin terracotta, no extra emitters
STYLES = {
    'rtx': [
        (['iron_block'], m(IRON, metal=237, smooth=1.0, svar=0.0, normal=0.0, depth=0.0, ao=0.0)),
        (['*_concrete'], m(POLISHED, smooth=1.0, svar=0.0, metal=255, normal=0.0, depth=0.0, ao=0.0)),
        (['*_glazed_terracotta'], m(POLISHED, smooth=0.92, f0=0.05, normal=0.3)),
        # elo < 0: every texel at full emission, even the darkest ones of black terracotta (a uniform tube)
        (['terracotta', '*_terracotta'], m(GLOSSY, smooth=0.80, normal=0.2, depth=0.0, emit=1.0, elo=-1.0, ehi=0.0)),
    ],
    'subtle': [],
}


def lookup(name, style='rtx'):
    """(params, matched globs) for a texture name; the globs of the default rule are ['*']."""
    from fnmatch import fnmatchcase
    for pats, p in STYLES[style] + RULES:
        if any(fnmatchcase(name, pat) for pat in pats):
            return m(DEFAULT, p), pats
    raise AssertionError('unreachable: "*" matches everything')


def is_default(pats):
    return pats == ['*']
