# Emberlight PBR

A LabPBR 1.3 resource pack for Minecraft Java 26.3 that aims for a "Minecraft with RTX" look: every concrete colour a
tinted perfect mirror, the iron block an untinted silver bathroom mirror and plain terracotta glowing like neon tubes (the
Bedrock RTX showpieces; build style `rtx`, the default),
mirror-like polished stone and quartz, real metals (iron, gold, copper that loses its shine as it oxidises, netherite),
clear glass and ice, gems, and strong emissive lights (glowstone, sea lanterns, froglights, lanterns, torches, lava,
lit redstone lamps, jack o'lanterns...). Dirt, grass, wool, leaves and bark stay rough.

It works with any shader that reads LabPBR 1.3 (`_n` normal/AO/height and `_s` smoothness/F0-metal/porosity-SSS/emission
maps): Emberlight's native shaderpacks, and Iris/OptiFine packs with LabPBR enabled (Complementary: *RP Mode* /
`RP_MODE=3`, "labPBR").

## Legal: what this pack contains

The pack ships **only our own data**:

* `assets/minecraft/textures/block/*_n.png` and `*_s.png` - LabPBR maps computed by `tools/generate.py`,
* their `.png.mcmeta` (the animation timing of animated blocks, so the maps stay in step),
* `pack.mcmeta`, a procedurally drawn `pack.png`, this README and the LICENSE.

It contains **no Mojang textures**. The generator reads the vanilla block textures from the Minecraft client jar that
is already on the developer's machine (Loom's Gradle cache) and derives the maps algorithmically (luminance -> height
-> normals and cavity AO; a hand-written material table -> smoothness, F0/metal ids, porosity/SSS, emission masks).
The game supplies its own base textures at runtime. `tools/check.py` fails if any PNG other than `_n`/`_s` maps and
`pack.png` ends up in the zip. No code or assets from other PBR packs were used.

Licenses (see [LICENSE](LICENSE)): the generated maps are CC BY 4.0, the tools are MIT.

## Building

Needs Python 3 with Pillow and numpy, and the 26.3 client jar (any Gradle build of this repo downloads it):

```
python3 packs/emberlight-pbr/tools/generate.py        # -> build/emberlight-pbr.zip (+ build/emberlight-pbr/)
python3 packs/emberlight-pbr/tools/check.py [--list]  # coverage + LabPBR 1.3 validation, exit 1 on errors
```

`--style` picks the build style (`tools/materials.py`, `STYLES`):

| Style | Output | Concrete | Terracotta | Iron block |
|---|---|---|---|---|
| `rtx` (default) | `build/emberlight-pbr.zip` | tinted perfect mirror: smoothness 255, metal id 255 (the albedo is the F0, so red concrete is a red mirror), flat normal | plain terracotta (uncoloured and all 16 colours) glows over its whole face like a solid neon tube; glazed terracotta is a glossy ceramic without emission (its patterns look odd lit). Villages with terracotta walls glow too in this style: use `subtle` for normal play | silver mirror: smoothness 255, metal id 237 (silver), flat normal |
| `subtle` | `build/emberlight-pbr-subtle.zip` | glossy (smoothness ~0.72) | satin / polished glazed, no emission | polished iron (smoothness ~0.9, id 230) |

Pass the same `--style` to `check.py`; for `rtx` it also checks the mirror and neon maps (plain terracotta glows, glazed terracotta does not). Shaders decide how a perfect
metal mirror and the glow look: Emberlight RTX reflects mirrors in mirrors (up to 4 bounces), lights the room with the
neon through its GI rays and adds a coloured halo; Complementary (labPBR) shows the mirrors through its reflections.

`--jar PATH` picks another client jar. The pack format comes from the jar's `version.json` (26.3: resource format 97).

## How the maps are made

* **`_n`** (DirectX / "Y down", as LabPBR specifies): height = blurred luminance (saturation for `bricks`, whose mortar is lighter than the bricks), normalised per animation frame;
  normals from its wrapped gradient times the material's `normal` strength; AO (blue) darkens low texels by `ao`;
  alpha = height (255 flat, at most `depth` x 25 % deep, never below 1). Only stone, brick, wood and raw ore blocks
  get depth: POM shaders such as Complementary light every height step as a sideways wall, so smooth or noisy faces
  (metals, soil, concrete...) stay flat instead of breaking into triangle facets.
* **`_s`**: red = perceptual smoothness (bright texels a little smoother, cracks rougher); green = F0 (0..229) or a
  LabPBR metal id (230 iron, 231 gold, 234 copper, 255 albedo-as-F0 for dark netherite/machinery); blue = porosity
  (0..64) or SSS (65..255) for foliage, snow, ice, slime, honey; alpha = emission 0..254 (0 = none, 255 never used).
* Per-texel modes in `tools/materials.py`: `copper` (orange texels metal, green patina rough dielectric), `ore`
  (texels brighter or more saturated than the host stone become the crystal material, gem ores glow faintly),
  `trim` (grey texels iron, yellow texels gold: rails, lanterns, pistons, levers).
* Transparent texels get neutral maps. Animated textures are processed frame by frame.

## Tuning

Everything lives in `tools/materials.py`: an ordered list of `(globs, parameters)` rules over vanilla texture names,
first match wins, `*` is the rough-dielectric default. Shorthands (`POLISHED`, `GLOSSY`, `IRON`, `GEM`, `FOLIAGE`...)
keep the table short. Rebuild and run `check.py`.

Not covered: entity, item and particle textures (chests, signs' text, mobs) keep the shader's defaults.
