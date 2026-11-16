# RT patch tables: adding ray tracing to your shaderpack

Emberlight runs Iris/OptiFine packs unchanged. On a GPU with ray tracing it can also *patch* a pack so that some of its
functions trace rays (inline ray queries against a scene of the loaded terrain and entities) instead of, say, sampling the
shadow map. What to patch and with what is described by an **RT patch table**: a JSON file plus GLSL snippets.

You ship the table in your own pack. Iris and OptiFine ignore it, and so does Emberlight with RT off.

The reference is the bundled Complementary table, which uses every feature described here:
[`converter/src/main/resources/rt/complementary-r5.json`](../converter/src/main/resources/rt/complementary-r5.json) and its
snippets in [`rt/complementary/`](../converter/src/main/resources/rt/complementary).

## Layout

```
shaders/
  emberlight/
    rt.json              the table
    shadows.glsl         snippets: any names, subfolders allowed
    shadow_body.glsl
  gbuffers_terrain.fsh   your pack, unchanged
  ...
```

This works in zip and folder packs. When the pack has `shaders/emberlight/rt.json`, Emberlight uses it and ignores its
bundled tables for that pack.

**Safety.** A table is data plus GLSL. It cannot run code, and it can only name files inside `shaders/emberlight/`.
Every path must be relative, may use only letters, digits and `_-./`, and may not contain `.` or `..` segments.
Emberlight checks every path before it reads any file. If any check fails, the whole table is rejected. The snippets are
compiled exactly like your own shaders.

## The table

```json
{
  "schema": 1,
  "id": "mypack-rt",
  "pack": "My Pack",
  "version": "2.3",
  "detect": ["lib/shadows.glsl"],
  "effects": { "shadows": { ... }, "reflections": { ... } }
}
```

| key | |
|---|---|
| `schema` | required. The table format version. This converter reads schema `1`. A newer schema is rejected with "update Emberlight". Compatible additions keep schema 1. Only a breaking change bumps it. |
| `id`, `pack`, `version` | required. Shown in the log and the RT menu ("RT shadows applied (mypack-rt (My Pack 2.3, from the pack))"). |
| `detect` | optional. Files (relative to `shaders/`) that must exist. A safety net when one table ships with several pack versions. |
| `effects` | effect name → effect. Unknown effect names are ignored. |

### Effects

The mod requests these effects by name (RT menu). Each one is applied independently, and **all or nothing**: if anything
fails to match, that effect is "unavailable" and the pack runs without it.

| effect | typical hook | options the mod sends |
|---|---|---|
| `shadows` | the pack's shadow-map filter function | `full` (1 = no shadow-map fallback), `rays=N`, `entities` |
| `blocklight` | where block light is applied | `rays=N` |
| `gi` | scene lighting, plus compute passes | `rays=N`, `res=F`, `entities`, `lightRays=N` |
| `ao` | the SSAO function | |
| `reflections` | the screen-space or voxel reflection trace | |
| `refraction` | the water/glass refraction function | `entities`, `glass=0` |
| `caustics` | the shadow sample under water | |
| `shafts` | volumetric light (RTX style only) | |
| `materials` | material/specular lookup (RTX style only) | |

In the RTX style the mod also sends `rtx` for `shadows`, `blocklight` and `gi`, and `noblocklight` for `gi`. Pack tables
can **ignore options they don't declare**: an undeclared option or variant is dropped and an INFO line is logged. A minimal
table keeps working in both styles.

Effect keys (all optional except `patches`):

| key | |
|---|---|
| `patches` | list of [Patch](#patches) entries |
| `requires` | programs that must get a patch in every dimension the effect applies to (e.g. `["gbuffers_terrain"]`) |
| `helpers` | comma-separated snippet files inserted once per patched stage, just before the first patched function |
| `options` | name → default (`"1"` int, `"1.0"` float, `"@PACK_OPTION"` = default to that pack option's value). Becomes `const int/float emrRt_<effect>_<name>` |
| `uniforms` | name → type. Built-in Iris uniforms your helpers read. Declared for you when the stage doesn't declare them |
| `needs` | pack symbols (functions, globals, uniforms) the helpers use. They must be declared before the first patched function, otherwise the effect is unavailable with a clear message |
| `dimensions` | dimension folders the effect applies to (`["world0"]`). Absent = all |
| `variants` | name → partial effect merged in when the mod sends that name (see [Styles](#rtx-vs-faithful)) |
| `library` | `false`: do not insert the common library (for patches that only use pack code) |
| `skipIds`, `skipIdsWhen` | block ids whose hits shadow rays ignore (`emrRt_<effect>_skip(id)`), only when the shadow `.fsh` contains the token `skipIdsWhen` |
| `computes` | `{"program": "deferred_a", "helpers": [...], "body": "x.csh"}`: whole compute stages added as Iris pass computes. `$<option>` is substituted |
| `images` | Iris `image.<name>` directives added to the properties (for compute outputs). `$<option>` is substituted |
| `frees` | `shaders.properties` keys (`bufferObject.N`, `image.X`) the patched pack no longer reads. They are shrunk if no live code still uses them |
| `lifts` | name → [Lift](#lifts): `@name` in the effect's helpers and bodies becomes those statements of the pack being converted |

### Patches

Patches apply to the *preprocessed* stage (after `#include`, `#define`, `#if` and the user's settings). A patch only sees
code that is live with the current settings.

```json
{"function": "vec3 SampleShadow(vec3,float)", "body": "shadow_body.glsl",
 "programs": ["gbuffers_terrain"], "needs": ["shadowtex0"], "bare": false,
 "after": "volumetricLight.rgb *= vlMult;", "find": null}
```

| key | |
|---|---|
| `function` | `returnType name(paramType,...)`, without parameter names. `in` is dropped, other qualifiers (`inout`, `out`) are kept. Whitespace doesn't matter |
| `body` | snippet file. By default it **replaces the function body**, and it can use the parameters by name |
| `after` | a statement in the function (GLSL tokens, whitespace ignored, must occur exactly once). The body is inserted after it |
| `find` | a token sequence in the function (exactly once). It is replaced by the body |
| `programs` | only patch these programs. A stage that matches some of a program's patches must match all of them |
| `needs` | pack symbols only this body uses, which must be declared before this function |
| `bare` | the body uses no helpers. A stage whose matches are all bare gets no helper library |

Snippets may not contain preprocessor directives, except compute `body` files. Patches are inserted on one line, so the
pack's line numbers in error messages stay valid.

### Lifts

A snippet can reuse the pack's own statements instead of repeating them: `@name` in a helper or body is replaced, at
conversion time, by statements taken from the user's copy of the pack (before any effect patched it). The bundled
Complementary table uses this so that Emberlight ships only its own glue, never pack code.

```json
"lifts": {"terrainAoCurve": {"program": "gbuffers_terrain", "function": "void DoLighting(...)",
  "at": "vanillaAO = min1(vanillaAO + 0.08);", "statements": 2, "otherwise": ""}}
```

| key | |
|---|---|
| `function` | the function holding the statements, as in a patch |
| `program` | the program it is in (same stage type as the patched stage, same dimension). Absent = the patched program |
| `at` | tokens that start the first statement, exactly once in the function |
| `statements` | how many whole statements to take (default 1). An `if` counts with its `else` chain, a `{...}` block as one |
| `otherwise` | text used when the statements can't be taken (an INFO line says so). Absent = the effect is unavailable then |

Every name the statements use must be declared in front of the marker (by the snippet, the patched function or the stage's
globals) or be a GLSL built-in, so a lift never inserts code that would not compile. A snippet can declare locals with the
pack's names in a nested `{ }` scope to feed its own values into the lifted statements.

## The helper API (`rt/emrion_rt.glsl`)

Emberlight inserts the [common library](../converter/src/main/resources/rt/emrion_rt.glsl) once in every patched stage,
and in front of every added compute. Stages that use it are compiled as GLSL 460 with `GL_EXT_ray_query`.

**Stable (schema 1). These keep their names and meaning:**

| | |
|---|---|
| `accelerationStructureEXT emrRtTlas` | the scene. Use `rayQueryInitializeEXT(rq, emrRtTlas, flags, mask, origin, tMin, dir, tMax)` |
| coordinates | TLAS space = Iris **player space** (world position minus `cameraPosition`). Instances are pure translations, so directions are world directions |
| instance masks | `0x01` solid + cutout terrain, `0x02` translucent terrain, `0x04` entities (only when an applied effect has the `entities` option), `0x08` the local player in first person |
| `EmrRtVerts emrRtVerts(int customIndex)` | the hit section's vertices (customIndex = `rayQueryGetIntersectionInstanceCustomIndexEXT`) |
| `uvec3 emrRtTri(uint primitiveIndex)` | vertex indices of the hit triangle |
| `emrRtPos / emrRtUv / emrRtId / emrRtColor / emrRtLight / emrRtNormal / emrRtMidUv (EmrRtVerts, uint vertex)` | vertex attributes: position, UV0, `mc_Entity` id, colour (tint+AO), UV2 lightmap (0-240), normal, `mc_midTexCoord` |
| `vec2 emrRtBaryUv(EmrRtVerts, uvec3 tri, vec2 bary)` | interpolated UV at a hit |
| `bool emrRtIsEntity(int customIndex)` | hit is an entity |
| `float emrRtHitAlpha(int customIndex, EmrRtVerts, uvec3 tri, vec2 bary)` | texture alpha at a hit (mip 0, right atlas). Use it to alpha-test cutout candidates |
| `sampler2D emrRtAtlas, emrRtEntityAtlas` | block and entity atlases |
| `const int emrRtDim` | in computes only: Iris dimension number (0, -1, 1) |

Anything else in that file (`EmrRtSections`, the raw vertex layout, `emrRtWord`) is internal and may change. Read vertices
only through the accessors. Symbols you define should not start with `emrRt`. The Complementary helpers
(`rt/complementary/*.glsl`) are examples to copy, not API.

## Dimensions

- `dimensions` limits an effect to some dimension folders.
- Inside patches, branch on your pack's own defines (`#ifdef NETHER` is already resolved by the time patches apply, so
  only the live branch is there). Computes get `emrRtDim`.
- An effect that matches nothing in one dimension is skipped there. It is unavailable only when it matches nowhere.

## RTX vs Faithful

The RT menu has two styles. **Faithful** makes the pack's own effects more accurate, e.g. RT shadows that look like the
shadow map, without its range or blur limits. **RTX** is allowed to light the scene differently. In RTX the mod adds
`<effect>:rtx` and asks for `shafts` and `materials`. Put the RTX look in a variant:

```json
"shadows": {
  "helpers": "shadows.glsl", "patches": [ ... ],
  "variants": { "rtx": { "helpers": "rtx/shadows.glsl", "options": {"sun": "0.03"}, "patches": [ ... ] } }
}
```

A variant adds `options`, `uniforms`, `needs` and `images`, appends `helpers`, and replaces `patches` and `computes`. Without
a variant both styles look the same.

## Testing

1. **The golden rule: with RT off the output must be byte-identical** to the same pack without `shaders/emberlight/`. The
   converter never reads the table when RT is off, so this holds as long as you don't change your own shaders for RT.
   Keep RT logic in snippets, not in `#ifdef`s in your pack.
2. Convert from the command line and read the RT lines:
   ```
   ./gradlew :converter:run --args="/abs/path/MyPack.zip - /tmp/out --rt shadows --rt shadows:rtx --no-cache --verbose"
   ```
   You get `RT shadows applied (...): N stages patched: world0/gbuffers_terrain.fsh, ...`, or the reason it isn't. The
   patched GLSL is in the `src/` folder of the printed cache directory. Shader compile errors point at your pack's lines.
3. In game: Emberlight RT… shows `unavailable: <reason>` per effect, and the log has `[pack] RT <effect> unavailable:
   <reason>`. Press **R** to reload the pack. The table is part of the pack's content hash, so edits always reconvert.

Typical messages:

```
shaders/emberlight/rt.json: schema 2 is not supported (this Emberlight reads schema 1 to 1; update Emberlight?)
shaders/emberlight/rt.json: effect shadows: path '../x.glsl' is not allowed (...)
shaders/emberlight/rt.json: effect shadows: shaders/emberlight/body.glsl not found
world0: patch 'vec3 SampleShadow(vec3,float)' matched no function in gbuffers_water (the pack's version or settings differ)
no patch matched in world0, world-1 (looked for 'float getShadow(vec3)' in any program, required in gbuffers_terrain; ...)
world0/gbuffers_terrain.fsh: 'shadowtex0' is not declared before getShadow
world0/composite1.fsh: 'blockLighting = ...;' occurs more than once in DoLighting
```

## Worked example: RT sun shadows

A pack whose `gbuffers_terrain.fsh` computes shadows in one function:

```glsl
float getShadow(vec3 pos) {          // pos: player space
    return shadowMapLookup(pos);
}
```

`shaders/emberlight/rt.json`:

```json
{
  "schema": 1, "id": "tiny-rt", "pack": "Tiny", "version": "1.0",
  "effects": {
    "shadows": {
      "requires": ["gbuffers_terrain"],
      "helpers": "shadows.glsl",
      "uniforms": {"gbufferModelViewInverse": "mat4", "shadowLightPosition": "vec3"},
      "patches": [{"function": "float getShadow(vec3)", "body": "shadow_body.glsl"}]
    }
  }
}
```

`shaders/emberlight/shadows.glsl` (helper, inserted before `getShadow`):

```glsl
float tinyRtShadow(vec3 pos) {
    vec3 dir = normalize(mat3(gbufferModelViewInverse) * shadowLightPosition);
    rayQueryEXT rq;
    rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT, 0x01u, pos, 0.05, dir, 256.0);
    rayQueryProceedEXT(rq);
    return rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
}
```

`shaders/emberlight/shadow_body.glsl` (the new body of `getShadow`):

```glsl
return tinyRtShadow(pos);
```

`gl_RayFlagsOpaqueEXT` treats leaves and glass as solid. To alpha-test them, loop `rayQueryProceedEXT` and confirm
candidates with `emrRtHitAlpha` (see `emrRtVisibilityMask` in `rt/complementary/shadows.glsl`). Then add a `rays` option for
soft shadows, and an `rtx` variant if you want a different look in that style.

This exact pack is the converter test
[`PackRtTableTest`](../converter/src/test/java/dev/emrion/shaders/converter/cli/PackRtTableTest.java). It is loaded from a
zip and from a folder and compiled to SPIR-V.
