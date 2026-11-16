#!/usr/bin/env python3
"""Console commands that build the Emberlight test world (seed 2026, normal terrain) on a fresh server.

usage: build_commands.py > build.txt   (build.sh feeds it to the server console; '#wait N' lines are pauses)
       build_commands.py --datapack WORLD_DIR   writes the teleport functions (/function emberlight:<spot>) and README.txt
The pad is next to the river at 468 64 468; the torch hall and the shaft sit where the harness scenes have them.
The torch hall is built from torch_hall.schem (a Sponge v3 schematic you supply next to this script, not in the repo);
without it the hall is skipped.
"""
import gzip, os, struct

HERE = os.path.dirname(os.path.abspath(__file__))
OW, NE, END = 'minecraft:overworld', 'minecraft:the_nether', 'minecraft:the_end'

# name: (dimension, x y z yaw pitch, sign text) - the teleports (functions, README and the harness use the same list)
SPOTS = {
    'hall':     (OW, '332.5 -39 324.5 180 20', 'Torch hall (underground, optional schematic)'),
    'shaft':    (OW, '402.5 -38.4 405.3 180 52', 'White concrete shaft: glowstone + fence post (face-direction shadows)'),
    'overhang': (OW, '512.5 66 528.5 180 10', 'Overhang: white and red concrete in sunlight'),
    'river':    (OW, '552.5 68 540.5 180 25', 'Stepped riverbed: squid/cod, gold block 5 deep, planks, stained glass, boats'),
    'clock':    (OW, '599.5 66 520.5 180 15', 'Redstone clock driving a crafter, torches'),
    'armour':   (OW, '620.5 66 520.5 180 5', 'Wall with an enchanted-armour stand behind it'),
    'armour_front': (OW, '620.5 66 502.5 0 5', 'The armour stand from the front'),
    'lava':     (OW, '640.5 69 523.5 180 35', 'Lava pit: sources and flowing lava'),
    'portals':  (OW, '622.5 67 572.5 180 10', 'Nether portal, End portal, End gateway'),
    'nether':   (NE, '0.5 64 -10.5 0 20', 'Nether room: lava, glowstone, shroomlight, lanterns'),
    'end':      (END, '100.5 49 0.5 90 0', 'End: platform facing the main island (starts the dragon fight)'),
    'spawn':    (OW, '580.5 65 590.5 180 0', 'Spawn of the test pad'),
}


def nbt(path):
    """Minimal NBT reader for the Sponge schematic."""
    b = gzip.open(path).read()

    def rd(i, t):
        if t == 1: return struct.unpack_from('>b', b, i)[0], i + 1
        if t == 2: return struct.unpack_from('>h', b, i)[0], i + 2
        if t == 3: return struct.unpack_from('>i', b, i)[0], i + 4
        if t == 4: return struct.unpack_from('>q', b, i)[0], i + 8
        if t == 5: return struct.unpack_from('>f', b, i)[0], i + 4
        if t == 6: return struct.unpack_from('>d', b, i)[0], i + 8
        if t == 7:
            n = struct.unpack_from('>i', b, i)[0]; return b[i + 4:i + 4 + n], i + 4 + n
        if t == 8:
            n = struct.unpack_from('>H', b, i)[0]; return b[i + 2:i + 2 + n].decode(), i + 2 + n
        if t == 9:
            et, n = b[i], struct.unpack_from('>i', b, i + 1)[0]; i += 5; out = []
            for _ in range(n): v, i = rd(i, et); out.append(v)
            return out, i
        if t == 10:
            d = {}
            while True:
                tt = b[i]; i += 1
                if tt == 0: return d, i
                n = struct.unpack_from('>H', b, i)[0]; name = b[i + 2:i + 2 + n].decode(); i += 2 + n
                d[name], i = rd(i, tt)
        if t == 11:
            n = struct.unpack_from('>i', b, i)[0]; return list(struct.unpack_from('>%di' % n, b, i + 4)), i + 4 + 4 * n
        if t == 12:
            n = struct.unpack_from('>i', b, i)[0]; return list(struct.unpack_from('>%dq' % n, b, i + 4)), i + 4 + 8 * n
        raise ValueError(t)
    n = struct.unpack_from('>H', b, 1)[0]
    return rd(3 + n, 10)[0]


def hall(ox, oy, oz):
    """The schematic in a stone shell, every block (air too) as run-length fills, without block updates."""
    path = os.path.join(HERE, 'torch_hall.schem')
    if not os.path.isfile(path): return []
    s = nbt(path); s = s.get('Schematic', s); bl = s['Blocks']
    w, h, l = s['Width'], s['Height'], s['Length']
    pal = {v: k for k, v in bl['Palette'].items()}
    ids, v, sh = [], 0, 0
    for x in bl['Data']:
        v |= (x & 0x7f) << sh
        if x & 0x80: sh += 7
        else: ids.append(v); v, sh = 0, 0
    assert len(ids) == w * h * l
    out = ['fill %d %d %d %d %d %d minecraft:stone' % (ox - 2, oy - 2, oz - 2, ox + w + 1, oy + h + 1, oz + l + 1)]
    for y in range(h):
        for z in range(l):
            x = 0
            while x < w:
                b = ids[(y * l + z) * w + x]; x2 = x
                while x2 + 1 < w and ids[(y * l + z) * w + x2 + 1] == b: x2 += 1
                out.append('fill %d %d %d %d %d %d %s strict' % (ox + x, oy + y, oz + z, ox + x2, oy + y, oz + z, pal[b]))
                x = x2 + 1
    return out


def sign(x, y, z, lines, rot=0):
    msgs = ','.join('"%s"' % t.replace('"', "'") for t in (lines + ['', '', '', ''])[:4])
    return 'setblock %d %d %d minecraft:oak_sign[rotation=%d]{front_text:{messages:[%s]}}' % (x, y, z, rot, msgs)


def mob(kind, x, y, z, extra=''):
    return 'summon minecraft:%s %s %s %s {NoAI:1b,PersistenceRequired:1b,Silent:1b,Tags:["emrion"]%s}' % (kind, x, y, z, extra)


def ench(item, e, lvl):
    return '{id:"minecraft:%s",count:1,components:{"minecraft:enchantments":{"minecraft:%s":%d}}}' % (item, e, lvl)


def commands():
    c = ['gamerule max_block_modifications 10000000',
         'forceload add 496 496 663 603', 'forceload add 296 296 361 339', 'forceload add 392 392 414 414',
         'execute in %s run forceload add -12 -12 12 12' % NE, 'execute in %s run forceload add 96 -4 104 4' % END,
         '#wait 40']
    # the pad: flat grass at y 64, sky cleared
    c += ['fill 496 65 496 663 200 603 minecraft:air', 'fill 496 50 496 663 60 603 minecraft:stone',
          'fill 496 61 496 663 63 603 minecraft:dirt', 'fill 496 64 496 663 64 603 minecraft:grass_block']
    # torch hall (the harness hall scene's place) and the shaft (seam scene "shaftday": open to the sky)
    c += hall(300, -50, 300)
    c += ['fill 394 -52 394 412 -28 412 minecraft:stone', 'fill 396 -40 396 410 -32 410 minecraft:air',
          'fill 399 -48 400 405 -41 404 minecraft:white_concrete', 'fill 401 -47 402 403 -41 402 minecraft:air',
          'setblock 401 -47 402 minecraft:glowstone', 'setblock 402 -47 402 minecraft:oak_fence',
          'fill 396 -31 396 410 100 410 minecraft:air']
    # overhang
    c += ['fill %d 65 %d %d 69 %d minecraft:white_concrete' % (x, z, x, z) for x in (506, 518) for z in (506, 516)]
    c += ['fill 505 70 505 519 70 517 minecraft:white_concrete', 'fill 512 70 505 519 70 517 minecraft:red_concrete',
          'fill 508 65 509 516 65 513 minecraft:white_concrete']
    # stepped riverbed, surface at 64: depth 1..5 towards the north... south
    c += ['fill 530 58 505 575 64 535 minecraft:sand']
    for d, (z1, z2) in enumerate([(507, 510), (511, 514), (515, 518), (519, 522), (523, 533)], 1):
        c.append('fill 532 %d %d 573 64 %d minecraft:water' % (65 - d, z1, z2))
    c += ['setblock 552 59 528 minecraft:gold_block',
          'fill 540 65 515 547 65 522 minecraft:oak_planks']
    c += ['fill %d 61 %d %d 64 %d minecraft:oak_fence[waterlogged=true]' % (x, z, x, z) for x in (540, 547) for z in (515, 522)]
    c += ['fill 560 65 512 566 67 512 minecraft:light_blue_stained_glass', 'fill 556 60 525 558 62 525 minecraft:lime_stained_glass',
          'fill 535 65 530 537 66 530 minecraft:orange_stained_glass',
          mob('squid', 550.5, 61.5, 528.5), mob('squid', 562.5, 62, 530.5), mob('glow_squid', 545.5, 61, 531.5),
          mob('cod', 545.5, 62.5, 520.5), mob('cod', 548.5, 63, 524.5), mob('cod', 553.5, 62, 526.5),
          'summon minecraft:oak_boat 568.5 64.6 509.5 {Tags:["emrion"]}', 'summon minecraft:oak_boat 580.5 65 515.5 {Tags:["emrion"]}',
          'summon minecraft:birch_chest_boat 583.5 65 519.5 {Tags:["emrion"],Rotation:[45f,0f]}']
    # redstone clock (two observers) driving a crafter, a lamp, torches
    c += ['fill 596 65 507 602 67 507 minecraft:stone_bricks', 'setblock 598 65 510 minecraft:crafter',
          'setblock 601 65 510 minecraft:redstone_lamp', 'setblock 599 65 510 minecraft:observer[facing=east]',
          'setblock 600 65 510 minecraft:observer[facing=west]', 'setblock 597 65 512 minecraft:torch',
          'setblock 601 65 512 minecraft:torch', 'setblock 597 66 508 minecraft:wall_torch[facing=south]',
          'setblock 601 66 508 minecraft:wall_torch[facing=south]']
    # armour stand behind a wall
    c += ['fill 615 65 512 625 68 512 minecraft:stone_bricks',
          'summon minecraft:armor_stand 620.5 65 508.5 {Tags:["emrion"],Rotation:[180f,0f],ShowArms:1b,equipment:{head:%s,chest:%s,legs:%s,feet:%s,mainhand:%s}}'
          % (ench('diamond_helmet', 'protection', 4), ench('diamond_chestplate', 'protection', 4), ench('netherite_leggings', 'protection', 4),
             ench('golden_boots', 'feather_falling', 4), ench('diamond_sword', 'sharpness', 5))]
    # lava pit
    c += ['fill 634 58 504 646 64 516 minecraft:stone', 'fill 636 61 506 644 64 514 minecraft:air',
          'fill 638 61 508 642 61 512 minecraft:lava', 'fill 643 61 513 644 63 514 minecraft:stone', 'setblock 644 64 514 minecraft:lava']
    # portals: Nether portal (axis z), End portal with a full frame, End gateway on a bedrock post
    c += ['fill 610 64 559 610 70 564 minecraft:obsidian', 'fill 610 65 560 610 69 563 minecraft:nether_portal[axis=z] strict',
          'fill 630 65 559 632 65 559 minecraft:end_portal_frame[eye=true,facing=south]',
          'fill 630 65 563 632 65 563 minecraft:end_portal_frame[eye=true,facing=north]',
          'fill 629 65 560 629 65 562 minecraft:end_portal_frame[eye=true,facing=east]',
          'fill 633 65 560 633 65 562 minecraft:end_portal_frame[eye=true,facing=west]',
          'fill 630 64 560 632 64 562 minecraft:obsidian', 'fill 630 65 560 632 65 562 minecraft:end_portal strict',
          'fill 650 65 560 650 69 560 minecraft:bedrock', 'setblock 650 70 560 minecraft:end_gateway strict']
    # Nether room (the harness nether scene) and an End platform
    n = 'execute in %s run ' % NE
    c += [n + x for x in ['fill -12 58 -12 12 74 12 minecraft:netherrack hollow', 'fill -6 58 2 6 58 9 minecraft:lava',
                          'fill -10 59 10 -8 62 10 minecraft:glowstone', 'setblock 9 59 9 minecraft:shroomlight', 'setblock 8 59 4 minecraft:lantern',
                          'setblock -9 59 3 minecraft:soul_lantern', 'fill 2 59 11 6 59 11 minecraft:magma_block', 'setblock 0 61 11 minecraft:crying_obsidian']]
    c += ['execute in %s run fill 98 48 -2 102 48 2 minecraft:obsidian' % END, 'execute in %s run fill 98 49 -2 102 52 2 minecraft:air' % END]
    # signs (text faces south, towards the camera spots)
    c += [sign(512, 65, 521, ['Overhang', 'white/red concrete', '/function', 'emberlight:overhang']),
          sign(552, 65, 537, ['Riverbed', 'gold block 5 deep', '/function', 'emberlight:river']),
          sign(599, 65, 517, ['Redstone clock', 'crafter + torches', '/function', 'emberlight:clock']),
          sign(620, 65, 515, ['Armour stand', 'behind the wall', '/function', 'emberlight:armour']),
          sign(640, 65, 521, ['Lava pit', 'source + flowing', '/function', 'emberlight:lava']),
          sign(622, 65, 568, ['Nether portal,', 'End portal, gateway', '/function', 'emberlight:portals']),
          sign(578, 65, 586, ['Emberlight test', 'world: all spots', '/function', 'emberlight:help']),
          sign(582, 65, 586, ['Torch hall, shaft', 'Nether, End:', '/function emberlight:', 'hall|shaft|nether|end'])]
    c += ['setworldspawn 580 65 590', 'forceload remove all', 'execute in %s run forceload remove all' % NE,
          'execute in %s run forceload remove all' % END, 'kill @e[type=item]', 'time set 6000', 'weather clear', 'save-all flush']
    return c


def datapack(world):
    d = os.path.join(world, 'datapacks', 'emberlight_test')
    os.makedirs(os.path.join(d, 'data', 'emberlight', 'function'), exist_ok=True)
    with open(os.path.join(d, 'pack.mcmeta'), 'w') as f:
        f.write('{"pack": {"description": "Emberlight test world teleports", "min_format": 121, "max_format": 121}}\n')
    readme = ['Emberlight test world (seed 2026, built by mod/tools/testworld/build_commands.py)', '',
              'Teleports: /function emberlight:<spot>   or the /execute line below', '']
    help_lines = []
    for name, (dim, pos, text) in SPOTS.items():
        tp = 'execute in %s run tp @s %s' % (dim, pos)
        with open(os.path.join(d, 'data', 'emberlight', 'function', name + '.mcfunction'), 'w') as f:
            f.write(tp + '\n')
        readme += ['%-13s %s' % (name, text), '              /' + tp]
        help_lines.append('tellraw @s {"text":"/function emberlight:%s  - %s","click_event":{"action":"suggest_command","command":"/function emberlight:%s"}}' % (name, text, name))
    with open(os.path.join(d, 'data', 'emberlight', 'function', 'help.mcfunction'), 'w') as f:
        f.write('\n'.join(help_lines) + '\n')
    with open(os.path.join(world, 'README.txt'), 'w') as f:
        f.write('\n'.join(readme) + '\n')


if __name__ == '__main__':
    import sys
    if len(sys.argv) > 2 and sys.argv[1] == '--datapack': datapack(sys.argv[2])
    else: print('\n'.join(commands()))
