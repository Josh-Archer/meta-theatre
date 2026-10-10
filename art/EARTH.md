# Meta Theatre Earth — 2026-10-04

The Quest app uses a globe with continuous, headset-local lighting.
Sunlight, the twilight band, city lights, clouds and the atmospheric rim follow
the same moving sun. The initial view faces the nominal seat at **0° latitude, 100°W
longitude**: the equator beneath the central USA, with north up.
The surrounding planets, Moon, visible Sun and catalog sky are documented in
[`SOLAR_SYSTEM.md`](SOLAR_SYSTEM.md).

## Current app behavior

`EarthLighting.kt` calculates sun direction from `Instant.now()` and
`ZoneId.systemDefault()`. The activity updates material uniforms once per second
and immediately after session-state changes, sharing one time sample and point
Sun with `SolarRenderer.kt`. Time is resampled after wake,
clock adjustments and time-zone changes; no elapsed-time accumulator can drift.

| Headset local time | Centered hemisphere |
| --- | --- |
| 00:00 | Night, with city lights |
| 06:00 | Dawn: sunlight enters from the right/east |
| 12:00 | Day, centered on the equator at 100°W |
| 18:00 | Dusk: sunlight remains on the left/west |

Lighting moves continuously between these examples. Per-pixel smoothstep masks
blend daylight and city emission across the terminator. The app does not swap
day/night models or spin the globe with the clock. Manual spin and geographic
observer controls are described in [Earth exploration validation](validation/EARTH_EXPLORATION.md).

This is an artistic **24-hour civil-time cycle**, not seasonal astronomical
sunrise/sunset. It does not request location, fetch live weather, or animate cloud
cover. An explicit clock or time-zone change immediately selects the corresponding
lighting; it is not eased across a discontinuous clock change.

The canonical scene keeps its 90 m Earth radius and center `(18, -35, 150)`.
The initial orientation points the authored front toward the nominal seat eye
`(0, 1.6, 0)`. Exploring a different hemisphere rebases the environment while
keeping the viewer and chair upright. Earth has a separate sphere target for
spin gestures and no mesh collision; playback and panel interactions remain available.

## Editable source and runtime assets

- `blender/earth_dynamic.blend`: editable source, packed textures and the scene
  `EARTH | Local time authoring`. Change the scene custom property `local_hour`
  between 0 and 24, then refresh the frame/render to preview a time.
- `blender/earth_static_before_dynamic.blend`: source version saved before the
  new geometry and materials were authored.
- `textures/earth/source/`: original 8K day, night, cloud, normal and ocean-mask maps.
- `textures/earth/dynamic/`: runtime derivatives, including the extracted city
  emission map and its intermediate PNG files.
- `exports/earth_dynamic/`: three staged, neutral-material geometry exports.
- `../app/src/main/assets/earth_dynamic_{surface,clouds,atmosphere}.glb`: the
  three runtime meshes. Their materials are replaced by Meta Spatial SDK shaders.
- `../app/src/main/assets/earth/`: five runtime textures.
- `renders/earth_dynamic/`: dawn, noon, dusk and midnight Blender previews.
  These show authoring intent; they are not headset screenshots.

The GLBs contain one mesh and one neutral material each, with identity node
transforms, no baked vertex colors and no embedded textures. The surface front
is glTF `+Z`, north is `+Y`, east is `+X`; the front UV is approximately `(2/9, 1/2)`.
The Blender preview materials are an authoring reference for the app's equations.
They are not exported as the runtime materials.

## Runtime cost

| Property | Value |
| --- | --- |
| Surface / cloud / atmosphere triangles | 16,128 / 9,024 / 16,128 |
| Total triangles / mesh draws | 41,280 / 3 |
| Surface / cloud / atmosphere radii | 1 / 1.0035 / 1.012 |
| Day, city emission, terrain normal maps | 4096 × 2048 each |
| Ocean mask and cloud alpha maps | 2048 × 1024 each |
| Estimated RGBA8 allocation with full mip chains | 149.3 MiB |
| Geometry file sizes | 366,668 / 207,720 / 366,708 bytes |
| Texture samples | 4 for the surface, 1 for clouds, 0 for atmosphere |

The opaque surface writes depth; the two translucent shells have fixed render
order and do not write depth. CPU upload retains at most one decoded 4K bitmap
at a time (32 MiB), excluding native upload copies. Texture memory is an estimate,
not headset telemetry; mesh buffers, native copies and render targets add cost.

SDK 0.14.0's native Bitmap texture path was inspected and uses
`VK_FORMAT_R8G8B8A8_SRGB`. Day/night samples arrive in linear space; shader conversion
restores numeric normal/ocean data after hardware decoding. Cloud alpha is
unaffected. Native-symbol evidence is in `validation/earth_dynamic_build.json`.

## Regeneration

Preserve a separate source version before regeneration. The creation scripts
build new datablocks; do not run them over edited final materials expecting an
in-place update. Blender scripts resolve the project from their script path or a saved source in `art/blender/`. When executing through MCP, supply the script path as `__file__`.

1. Run `python3 art/scripts/prepare_dynamic_earth_textures.py` (Pillow required).
2. Open the preserved static `art/blender/earth.blend` in the live Blender MCP
   session and execute `art/scripts/build_dynamic_earth_geometry.py`.
3. Execute `art/scripts/finish_dynamic_earth_textures.py` in Blender. It extracts
   city-only emission in linear space and removes stale phase metadata before
   re-exporting neutral geometry. Run this before authoring preview materials.
4. Convert `art/textures/earth/dynamic/night.png` to RGB `night.jpg` with Pillow,
   using JPEG quality 97, subsampling 0 and optimization enabled.
5. Execute `art/scripts/author_dynamic_earth_preview.py` through Blender MCP to
   create the editable time-driven preview, pack images and save the source.
6. Run `node art/scripts/validate_dynamic_earth.cjs`. It expects `gltf-validator`
   under `/tmp/meta-theatre-gltf-validation/node_modules/`.
7. Inspect the renders, then copy the validated geometry and five runtime maps
   into the app asset paths above. Keep the intermediate/source files in `art/`.

Do not directly export the final Blender preview materials over the neutral
runtime GLBs: the app supplies its own materials and shader bindings.

## Build and validation

Build with JDK 17:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug testDebugUnitTest
```

The shader build uses Meta Spatial SDK/plugin 0.14.0, Kotlin/Compose plugin 2.2.0
and Android NDK 27.2.12479018. The debug APK is
`app/build/outputs/apk/debug/app-debug.apk`.

- Android build passed. The integrated Earth and solar-system shaders compile
  84 variants across seven material families.
- Six Earth lighting tests, two cadence tests and eight solar-system tests pass
  (16 total), covering time, lighting, body scale, orientation and scene bounds.
- All three GLBs passed Khronos validation with zero errors and zero warnings.
  Informational unused-UV notices are expected because materials arrive at runtime.
  Additional assertions check the exact center UV, north, geometry and materials.
- APK contents include all three meshes, five textures and matching shader names.
- Blender source previews were inspected at dawn, noon, dusk and midnight.
- Earth-only evidence: `validation/earth_dynamic_gltf_report.json` and
  `validation/earth_dynamic_build.json`. The latest integrated APK SHA-256 and
  package checks are in `validation/solar_system_build.json`.

The original 2026-10-04 validation did not include a Quest installation. The
later exploration build was installed; see [its validation record](validation/EARTH_EXPLORATION.md).
Native importer orientation, new polar views, stereo appearance, alpha sorting,
frame timing and actual memory use still need direct headset checks. Desktop
renders, shader compilation and JVM tests do not establish those results.

## Preserved static variants and Unreal sandbox

The earlier `blender/earth.blend`, source versions, `exports/earth/`,
`renders/earth/`, and `earth_{day,twilight,night}.glb` assets remain available.
They contain baked lighting states and are no longer selected by the app.
`validation/earth_gltf_report.json` records their earlier validation.

The existing Unreal asset sandbox retains the earlier native static assets under
`~/Documents/Unreal Projects/MetaTheatreAssetLab/Content/MetaTheatre/Earth/`,
with `/Game/MetaTheatre/Earth/{Day,Twilight,Night}` content paths. This dynamic
integration targets the native Meta Spatial SDK app. It does not update those
Unreal materials or migrate the Android app to Unreal.

## Attribution

Original texture maps: **Solar System Scope / INOVE**, under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
Source: [Solar System Scope textures](https://www.solarsystemscope.com/textures/).
The provider describes the Earth maps as adaptations of NASA imagery and geographic data.

Changes include resizing, city-light extraction, terrain-normal shading, ocean
highlights, cloud opacity, atmospheric color, geometry and time-dependent
illumination. These are artistic composites, not live observational products.
Full attribution ships in `app/src/main/assets/earth_credits.txt` and must travel
with redistributed sources and derivatives.
