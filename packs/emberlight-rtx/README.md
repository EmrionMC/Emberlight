# Emberlight RTX

A shaderpack for Emberlight's native format (`docs/NATIVE_FORMAT.md`) that goes for the "Minecraft with RTX" look and
then some: ray-traced soft shadows, diffuse global illumination, glossy reflections, emissive blocks as real area
lights, coloured light through stained glass, god rays, heavy bloom and eye adaptation. Needs a GPU with ray queries
(`"requires": ["ray_query"]`). Status: alpha.

## Installing

Needs the Emberlight mod (Vulkan, a GPU with hardware ray tracing) and Minecraft Java 26.3.

1. **Shaderpack.** Either copy the folder `emberlight-rtx` (the one with `emberlight.json` and `shaders/` in it) into
   `.minecraft/shaderpacks/`, or zip it and drop the zip there. Both layouts work: `emberlight.json` at the root of the
   zip, or inside one top-level folder. From this repository:
   ```
   cd packs && zip -r emberlight-rtx.zip emberlight-rtx      # -> packs/emberlight-rtx.zip
   ```
   You can also drag the folder or zip onto Emberlight's pack screen; it copies it into `shaderpacks/`.
2. **Materials (recommended).** Build the companion resource pack *Emberlight PBR* (`packs/emberlight-pbr`, see its
   README: `python3 packs/emberlight-pbr/tools/generate.py` -> `build/emberlight-pbr.zip`), copy the zip into
   `.minecraft/resourcepacks/` and enable it in *Options -> Resource Packs*, above the default pack. Without it the pack
   uses its built-in material table (fewer glossy and metal blocks, no normal maps).
3. In game press **O** (or *Video Settings -> Shader Packs (Emberlight)...*), pick **Emberlight RTX**, then a
   profile (Low ... Insane). The *Materials* option (`PBR_SOURCE`) set to `auto` uses the resource pack when it is loaded.

## Frame

| Phase | Pass | What |
|---|---|---|
| opaque | `gbuffer`, `basic`, `sky` | albedo + AO, normal + lightmap, material (LabPBR-shaped); vanilla sky discarded |
| deferred | `rt_direct` (compute, full res) | sun/moon shadow ray to a random point on the light's disc, tinted by stained glass/water; one GGX (VNDF) reflection ray, its hit shaded with its own shadow ray |
| deferred | `restir` (compute, half res) | block lights (ReSTIR DI, `lib/restir.glsl`): `LIGHT_SAMPLES` candidates from Emberlight's RT light list, a visibility ray for the pick, last frame's reservoir merged at the reprojected pixel (light indices remapped with `el_lightFromPrev`) |
| deferred | `rt_gi` (compute, half res) | 1–2 bounce cosine-sampled diffuse path, sky on miss, hits that were on screen last frame take the accumulated GI there as their incoming light (multi-bounce); block lights: spatial reuse of 2–8 neighbouring reservoirs (pairwise MIS with visibility rays), the selected sample is visible by construction |
| deferred | `denoise` | joint-bilateral upsample of the GI, small bilateral gathers for sun and reflections, temporal accumulation with jitter-free reprojection, per-tap distance rejection, a sun-visibility clamp and reflection variance clipping (reprojected by the reflected image) while moving (up to 3x longer history at rest) |
| deferred | `atrous1..3` | variance-guided edge-aware a-trous on the GI (5x5 B3 kernel, spacing 1/2/4), firefly clamp in the first step |
| begin | `sky_trans`, `sky_ms`, `sky_view` (compute) | atmosphere LUTs: transmittance, multiple scattering, sky view (overworld only) |
| deferred | `lighting` | diffuse + GGX sun highlight + reflections (analytic env. BRDF) + emission; physically based sky, clouds, stars, sun and moon |
| translucent | `translucent`, `forward` | water/glass/ice into a translucent gbuffer; particles, rain, hand forward-lit into a premultiplied layer |
| composite | `volumetrics` (compute, half res) | participating media: air (sun in-scattering with a glass-tinted shadow ray per step) or, under water, water (sun shafts through the waves); temporally accumulated (`volHist` buffer, ping-pong) |
| composite | `resolve` | water (ray-traced reflection, refraction, absorption, waves, player ripples), thick glass/ice/slime/honey (traced refraction, Beer-Lambert absorption, total internal reflection, in-scattering), panes (tint + RT reflection), aerial perspective, fog, underwater medium, forward layer |
| composite | `bloom_prep`, `post`, `exposure` | bloom over the mip chain, eye adaptation (buffer), ACES fit, grading |

Materials: with a LabPBR resource pack (for example the companion *Emberlight PBR*), its `_s`/`_n` maps are used; without
one, a built-in table by block id (`materials.blocks` in `emberlight.json`, read by `shaders/lib/materials.glsl`) makes
polished stone, quartz, concrete, glazed terracotta, obsidian, metals (LabPBR hardcoded metal ids), ice, water and glass
glossy and marks emitters. Ray hits read the same LabPBR maps (`el_hitSpecular`, header version 2) and fall back to the
table where a texel has none, so reflections and GI see the resource pack's materials and emission.

Mirrors and neon (the Bedrock RTX showpieces, with *Emberlight PBR*'s default `rtx` style): a perfect albedo-as-F0 metal
(smoothness 1, metal id 255: its concrete) is a mirror tinted by the albedo's hue (bright enough that black concrete is
a dark-glass mirror, `matF0`); a perfect silver one (metal id 237: its iron block) is an untinted bathroom mirror
that shows none of the block's texture. Both are traced crisp (one fixed ray, no temporal history to ghost) and reflecting other mirrors up
to `MIRROR_BOUNCES` times (Low 1, Medium 2, High 3, Ultra 4: facing mirrors become a corridor of images). Glowing
surfaces that are no light source (*Emberlight PBR*'s plain terracotta, solid neon tubes) light their surroundings through the GI rays that hit them, show in
reflections, and get an amplified bloom halo (`NEON_GLOW`, default 2), in a mirror too (the reflection ray flags a
glowing hit, `lighting` writes the glowing share of each pixel as the bloom gain into `lit`'s alpha); their colour is saturated so neon does not wash
out to white, and a dark albedo glows at a lamp's brightness (`emissiveColor`): a muddy red block is a bright red tube.

Water caustics (`CAUSTICS`, 0 = off, default 1, up to 3): the sun ray of `rt_direct` that crosses a water surface
evaluates the waves there (`lib/water.glsl`: the analytic curvature of the same sine trains that shape the rendered
water, without its bilinear noise, whose gradient jumps at every noise texel) and scales the light by the irradiance
change of the refracted beam, 1 / |det J| of the map x -> x + depth x 0.25 x slope (the focal distance shortened by
`CAUSTIC_FOCUS` so shallow pools show the web), divided by that factor's mean over the water plane at the same focus
distance (`CAUSTIC_MEAN`): the web redistributes the light instead of adding to it (undivided, a pool floor got about
twice the sunlight). Walls and overhangs beside water get the sunlight the water reflects:
one ray along the mirrored sun direction to the water, its own shadow rays, Fresnel (a few percent at noon, a third
and more at sunset) and the same caustic factor for a mirror bounce. Both respect the RT shadows, work for the moon too
(dimly) and go through `sunRaw.a` straight into `lighting` without the denoiser, so the pattern stays crisp and moves.

Subsurface scattering (`SSS`, 0 = off): the LabPBR SSS value of the resource pack (`_s` blue 65-255; built-in 0.75 for
the plants of material id 10) travels in the gbuffer's material kind byte. Everything with SSS gets wrap lighting
(light reaches past the terminator, tinted by the albedo); thin plants (leaves, grass, flowers) also let the sun
through when it is behind them, with a forward-scattering peak when the camera looks toward the sun.

Parallax occlusion mapping (`POM`: off / low / high / ultra, by profile): solid terrain with a LabPBR height map (`_n`
alpha; *Emberlight PBR* writes one) marches the view ray through the height field (16 / 64 / 128 steps at most, bisected at the crossing, fewer
when looking straight at the face, at most a quarter block deep) and fades out by 16 / 32 / 48 blocks. The sprite's
extent comes from the screen derivatives (one block face = one sprite in vanilla models), the uv wraps inside the sprite,
and every texture read after it uses explicit gradients (`el_*Grad`, header version 3). The depth buffer keeps the flat
face (writing `gl_FragDepth` would cost early-Z for all terrain), so ray-traced shadows start from the face and there is
no self-shadowing inside the relief; normals, albedo and materials follow the displaced texel.

Thick translucent blocks (`REFRACTION` high, `lib/medium.glsl`): glass blocks, stained glass, ice, slime and honey refract
the view ray (IOR 1.5 glass, 1.31 ice, 1.42 slime/honey), which is traced to the block's back face with
`el_traceSurface` (no alpha test, so the clear centre of a glass block is still its face; three blocks of glass are three
blocks deep), absorbed on the way (Beer-Lambert: one block passes about the saturated surface colour; clear glass a faint
green), totally internally reflected up to twice, and refracted out. What lies behind is the lit screen pixel where that
is visible, else a traced shade; water under a glass floor is refracted into and absorbed too, and a block standing in a
pool passes its bottom face straight into the water (the 1/9 block air gap Minecraft draws there is no total-reflection
mirror). Ice, slime and honey also
scatter: one sample per segment gathers the sunlight that entered the block toward the sun (its own shadow ray and the
absorption on the way in, Henyey-Greenstein g = 0.55) and the sky light, so a backlit ice wall glows blue. Panes stay
thin tinted sheets; the frame texels of a glass block are lit as a surface; clear and stained glass add no diffuse light
of their own (that made glass walls milky). Reflection rays see one layer of glass, ice, slime or honey on their way
(one extra ray against the translucent geometry only): its frame lit, a Fresnel sheen of the sky, the rest tinted. Opaque packed and blue ice and snow get wrap lighting (`SSS`).

Entities and items: `materials.items` and `materials.entities` reuse the block material numbers: worn armour (with the
item id Emberlight now passes for armour layers, as Iris does), held and dropped tools are iron, gold, copper, netherite
metal or diamond gem (an iron sword's brown handle stays wood), chainmail rougher metal, turtle shells, tridents, slimes,
fish and other wet creatures glossy, armour stands, item frames, boats and chests varnished wood; other mobs stay rough.

Written strictly against `docs/NATIVE_FORMAT.md` format 1 and the `el_*` header API: no Iris program names, no Iris
uniforms in GLSL, no `gl_*` compatibility built-ins. (Custom-uniform expressions in `emberlight.json` use the frame members'
long names, as §9 specifies.) LabPBR normal maps use a cotangent frame from screen-space derivatives, since format 1 has no
view/world tangent helper.

Noise: primary samples (sun disc, first GI bounce, reflection lobe) use interleaved gradient noise shifted per frame,
which the spatial filters average much better than white noise. Its two dimensions come from the pixel grid and the
transposed grid: IGN of a shifted pixel is almost a constant offset of the first value, and such pairs only cover a few
lines of the unit square, which showed up as rings of fireflies around torches. The gbuffer stores the geometric normal
next to the shading normal (`lib/materials.glsl`, `encodeGNormal`): filters, shadow-ray offsets and GI sampling use the
geometric one, reflections a 12 % blend toward the normal map (more cut mirrors into a per-texel mosaic), so normal maps
shade without breaking the denoiser.

Roughness is LabPBR's `(1 - smoothness)^2`, used directly as the GGX alpha of the reflection lobe; toward the
reflection cut-off of the quality profile the traced reflection fades into an ambient estimate instead of switching off
per texel. Glossy reflections are gathered over the reflection lobe's screen footprint (hit distance x GGX alpha / view distance,
8 taps on a rotated spiral, edge-stopped by plane, normal and smoothness) before the temporal accumulation. Metals use
the LabPBR hardcoded F0 of ids 230-237 tinted by the texture (geometric mean of both), no diffuse.

Block lights are boxes that emit from the face turned toward the shaded point (sampled at a random point of it), so
emitters flush with a floor or wall light their surroundings; a lightmap fill adds the many-bounce light near emitters.
Two independent RIS picks (two shadow rays) per GI pixel. GI rays that hit an emitter of the light list do not add its
glow again (it is already sampled directly): that double count was the sparkle around lanterns and flames. Only a
listed source in the hit block counts, so neon terracotta next to a torch still lights the room. Reflection and GI hits
that the screen did not see last frame take their block light from the light list too (`hitBlockLight`: the cell's
sources, unshadowed, gated by the lightmap), at the scale the screen is lit with.

Atmosphere (`lib/atmosphere.glsl`, `sky_lut.comp.glsl`): an Earth-like atmosphere with Rayleigh and Mie scattering
and ozone absorption, evaluated through three small LUTs that three compute passes rebuild at the start of every frame
(about 82 000 texels in all): transmittance (256x64, Bruneton's horizon-dense parameterisation), Hillaire's
multiple-scattering term (32x32: second-order light from 16-64 directions with an isotropic phase, summed as a
geometric series) and the sky around the camera (256x256, sun and moon light together, single scattering plus the
multiple-scattering term, 16-48 steps by `SKY_QUALITY`). Everything that needs sky light reads the same LUTs: the sky
itself, the sun and moon colour (the transmittance toward them: white at noon, golden and red at sunset; ozone keeps
the twilight sky blue: the blue hour), GI miss rays, ambient and reflection fallbacks, fog tint and the aerial
perspective on distant terrain (the atmosphere's extinction over the view distance at a compressed 20 m per block, the
sky radiance of that direction as in-scattered light). Rain thickens the Mie haze. The sun disc has limb darkening
(darker and redder toward the edge), the moon is a lit sphere whose terminator follows the moon phase (and moonlight
follows its illumination: full moon brightest, new moon a faint glow), stars turn with the sky and dim near the
horizon. Options: `SUN_INTENSITY`, `MOON_INTENSITY`, `GOLDEN_HOUR` (saturation of the sun's reddening, 1 = physical),
`BLUE_HOUR` (ozone amount), `AERIAL_PERSPECTIVE`, `SKY_QUALITY`.

Participating media (`lib/fog.glsl`, the model of Bedrock's volumetric fog: per-medium scattering and absorption and
a Henyey-Greenstein anisotropy): **air** has a density with height falloff, more at dusk, in rain and thunder, in caves,
low morning mist on clear mornings (thickest in humid biomes), and a biome tint of its scattering albedo from the
biome's temperature and downfall (custom uniforms `biomeHumid`, `biomeDry`, `biomeCold`: green-grey swamp and jungle
haze, warm desert dust, pale snowy air); in the Nether the biome fog colour, in the End purple. Phase: HG with
`ANISOTROPY` (default 0.75, Bedrock's air default) plus an isotropic share. **Water** absorbs red quickly and scatters
blue-green (under water tinted by the biome's water fog colour), HG g = 0.6. With the camera under water the volumetric
pass marches the water instead of the air: each step traces one ray to the wave surface above (the refraction caustic
of the waves there gives the shafts their moving pattern, the path below the surface its absorption) and one RT
shadow ray toward the sun, so shafts appear only where the sky is open (`UNDERWATER_RAYS`). The temporal accumulation
and per-frame jitter of the volumetric pass are shared by both media.

Hidden option `DEBUG_VIEW` (settings file only): 1 filtered GI, 2 accumulated GI, 3 sun visibility, 4 reflections,
5 lighting without albedo.

## Options

Profiles Low / Medium / High / Ultra / Insane; RTX intensity (one slider for "more"), GI bounces, reflections quality,
mirror bounces, parallax depth, glass and ice refraction, emissive glow,
emissive and block light strength, fog density and anisotropy, volumetrics, underwater sun shafts, aerial perspective,
sky quality, sun and moon brightness, golden and blue hour, bloom, exposure, waves, puddles, clouds. English and German.

## References (ideas and algorithms only; all code is original)

- E. Heitz, "Sampling the GGX Distribution of Visible Normals", JCGT 2018 (reflection rays).
- B. Karis, "Physically Based Shading on Mobile", Unreal Engine blog 2014 (analytic environment BRDF).
- J. Talbot et al., "Importance Resampling for Global Illumination", EGSR 2005; B. Bitterli et al., "Spatiotemporal
  reservoir resampling for real-time ray tracing with dynamic direct lighting", SIGGRAPH 2020 (block light sampling).
- C. Schied et al., "Spatiotemporal Variance-Guided Filtering", HPG 2017 (denoiser structure, simplified).
- J. Jimenez, "Next Generation Post Processing in Call of Duty: Advanced Warfare", SIGGRAPH 2014 (interleaved gradient noise).
- T. Stachowiak, "Stochastic Screen-Space Reflections", SIGGRAPH 2015 (filter radius from the reflection lobe footprint).
- E. Veach, "Robust Monte Carlo Methods for Light Transport Simulation", PhD thesis 1997 (next-event estimation without
  counting emitters twice).
- C. Schüler, "Followup: Normal Mapping Without Precomputed Tangents", 2013 (cotangent frame).
- L. G. Henyey, J. L. Greenstein, "Diffuse radiation in the galaxy", 1941 (fog, water and ice/slime phase function).
- E. Bruneton, F. Neyret, "Precomputed Atmospheric Scattering", EGSR 2008; E. Bruneton, "A Qualitative and
  Quantitative Evaluation of 8 Clear Sky Models", IEEE TVCG 2017 (atmosphere model, transmittance LUT parameterisation).
- S. Hillaire, "A Scalable and Production Ready Sky and Atmosphere Rendering Technique", EGSR 2020 (multiple-scattering
  LUT, sky-view LUT, aerial perspective from the same atmosphere).
- H. Neckel, D. Labs, "Solar limb darkening 1986-1990", Solar Physics 1994 (wavelength-dependent limb darkening,
  simplified to a linear law).
- T. Welsh, "Parallax Mapping with Offset Limiting", 2004; N. Tatarchuk, "Dynamic Parallax Occlusion Mapping with
  Approximate Soft Shadows", I3D 2006 (parallax occlusion mapping).
- S. Hill, ACES filmic fit (RRT + ODT matrices), public domain (tonemapper).
- LabPBR 1.3 material standard (specular map encoding, hardcoded metals).

No code from Complementary, BSL, SEUS, Bedrock RTX or NVIDIA RTXDI is used.

## Licence

MIT, see `LICENSE`.
