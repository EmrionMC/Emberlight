# Modrinth gallery plan

Images for the Modrinth gallery and the page body. All existing shots are 1920×1080 WebP in `modrinth-assets/`.
Project icon: `modrinth-assets/icon.png` (upload in the project settings). Logo and banner: `docs/branding/`.

## Already there

| File | Shows | Used on the page |
|---|---|---|
| `hero.webp` | Title shot | Hero image |
| `forest.webp` | Complementary Unbound on Vulkan, forest | "Your shader packs, on Vulkan" |
| `rt-on-off.webp` | Ray tracing on / off split | "Hardware ray tracing" |
| `blocklight.webp` | Torch-lit scene at night | "Light from every block" |
| `copper.webp` | Copper room, green copper light | "Light from every block" |
| `water.webp` | Water reflections and refraction | "Water and reflections" |
| `emberlight-rtx.webp` | Emberlight RTX, sunlight through stained glass | "Emberlight RTX" |
| `emberlight-rtx-neon.webp` | Emberlight RTX + Emberlight PBR, neon terracotta | "Emberlight RTX" |

All eight should go into the gallery too, with `hero.webp` as the featured image.

## Worth adding

Same resolution and format (1920×1080 WebP), Complementary Unbound r5.10 + Euphoria Patches 1.11.2 unless noted, photo
mode (64 or 256 samples) for clean stills, HUD hidden.

1. **RT reflections off screen**: a polished floor or calm lake that mirrors a build behind or above the camera, which
   screen-space reflections cannot show. Best as an on/off pair.
2. **Stained glass at sunset**: coloured light falling through a stained glass window into a dark room (RTX style).
3. **Global illumination**: a room lit only by sunlight through a doorway, bounced light on the walls; GI on/off pair.
4. **Cave with block light**: lanterns and glow lichen in a cave, soft overlapping shadows.
5. **Nether**: lava light and shroomlight, to show RT block light outside the Overworld.
6. **Better Block Entities scene**: a storage room with chests, signs and banners, RT on.
7. **Faithful vs RTX style**: same view in both styles.
8. **Reimagined r5.10**: one shot with plain Reimagined, so both Complementary flavours are shown.
9. **Another pack** (BSL or Photon): proves it is not Complementary only.
10. **Settings screen**: the Emberlight RT and Upscaling pages, so people see what they can tune.
11. **Emberlight RTX daylight**: a village or mirror build with concrete mirrors and the iron block mirror (needs
    Emberlight PBR).
12. **Profiler overlay (F7)** over a normal scene, for the performance-minded.

Gallery captions should name the pack and settings (e.g. "Complementary Unbound r5.10, RT High, DLSS Quality").
