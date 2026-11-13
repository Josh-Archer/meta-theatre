# Meta Theatre Earth — 2026-10-04

## Native longitude correction — 2026-10-20

The source glTF globe has +X toward geographic east, but the Spatial SDK's
imported runtime globe uses native -X toward east. Native surface ray hits
revealed the mismatch: requesting the public Carrollton, GA city-center
fixture (about 33.58 N, 85.08 W) sampled 33.58 N, 114.92 W before correction.
This is the California placement reported by the user. The headset's network
provider separately returned a fix near Carrollton, so replacing Wi-Fi location
with a fixed city would hide the renderer bug.

Geographic recentering and the inverse viewed-location calculation now use
the imported runtime longitude direction. UTC/civil Sun directions and sidereal
rotation use that same convention, preserving actual sunrise/rotation direction
and weather-region sampling. No location is hardcoded in normal app behavior.

The `metatheatre.test_earth_registration` debug-only launch mode loads the
actual surface with temporary line-test collision, compares its native hit UVs
against five public geographic fixtures, then repeats with manual spin. This
independent check supplements the older inverse-math tests, which could pass
while forward and inverse formulas shared the same wrong assumption. Normal
app launches retain no Earth mesh collision. Raw headset coordinates are not
included in logs or evidence; only public fixture UV results are recorded.

The source assets, Milky Way update, user preferences and playback state are
preserved. The rejected California-specific default, button and detail atlas
remain reverted. At that validation, zoom detail still required a separate regional/tile
solution; correcting longitude alone did not add source-image resolution.
The 2026-10-21 regional update is documented below.

On the awake Quest, the corrected Carrollton public fixture sampled approximately
33.576 N / 85.085 W in the actual imported texture, and all ten native UV checks
passed, including the repeated fixtures after 47° manual spin. The complete
200-test suite and lint passed with zero errors. Final installed APK SHA-256:
`9f416e71491c88ef451f95d758ddb1cfb4eca5d8283289c703b8a0036aad51a2`.
The installed APK was compared against the local build byte for byte.
An explicit fresh precise-Wi-Fi request also returned a usable fix and passed
the native centering diagnostic. The app was then restarted normally for use.

## Earth scene

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
sunrise/sunset. It does not request GPS location. Optional [orbital weather](WEATHER.md) uses
viewed coordinates and current model data to update a continuous cloud field. An explicit clock or time-zone change immediately selects the corresponding
lighting; it is not eased across a discontinuous clock change.

The canonical scene keeps its 90 m Earth radius and center `(18, -35, 150)`.
The initial orientation points the authored front toward the nominal seat eye
`(0, 1.6, 0)`. Exploring a different hemisphere rebases the environment while
keeping the viewer and chair upright. Earth has a separate sphere target for
spin gestures and no mesh collision; playback and panel interactions remain available.

## Editable source and runtime assets

- `blender/earth_detailed.blend`: separate 8K/4K lighting reference, directional
  cloud relief, cloud shadows and ocean roughness. External image paths are relative.
  `renders/earth_detailed/` contains daylight/night references, not headset captures.
- `blender/earth_dynamic.blend`: preserved original source, packed textures and the scene
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
| Day and city emission maps | 8192 × 4096 each |
| Terrain normal, ocean mask and cloud alpha maps | 4096 × 2048 each |
| Estimated static RGBA8 allocation with full mip chains | 469.3 MiB |
| Additional live cloud texture, when enabled | 42.7 MiB with mip chains |
| Geometry file sizes | 366,668 / 207,720 / 366,708 bytes |
| Texture samples | 5 for the surface, 6 for cloud relief/shadow, 0 for atmosphere |

The opaque surface writes depth; the two translucent shells have fixed render
order and do not write depth. CPU upload retains at most one decoded bitmap
at a time (128 MiB for an 8K map), excluding native upload copies. Texture memory is an estimate,
not headset telemetry; mesh buffers, native copies and render targets add cost.

SDK 0.14.0's native Bitmap texture path was inspected and uses
`VK_FORMAT_R8G8B8A8_SRGB`. Day/night samples arrive in linear space; shader conversion
restores numeric normal/ocean data after hardware decoding. Cloud alpha is
unaffected. Native-symbol evidence is in `validation/earth_dynamic_build.json`.

## Detailed rendering update

The 2026-10-17 quality pass uses the existing native 8K day/city-light source detail,
4K offline/live cloud density and a 4K ocean mask. The original 4K normal map stays
unchanged. The surface samples the same current cloud map as the cloud shell to
cast directional shadows. Cloud gradients add relief; a directional density sample
adds self-shadow, and optical depth retains thin wisps and dense areas. Ocean
highlights use view-dependent Fresnel. Geometry, the three-layer structure and
shared day/night timing are unchanged.

`python3 art/scripts/prepare_detailed_earth.py` prepares the upgraded runtime maps
from the existing attributed source files. `author_cloud_structure.py` bakes the
continuous weather field through Blender MCP; `author_detailed_earth_preview.py`
creates a separate lighting reference using preserved dynamic Earth geometry.
The older regeneration procedure below reconstructs the earlier 4K/2K version;
run the detailed preparation afterward to retain the current resolution.
See [quality validation](validation/EARTH_QUALITY.md) for measured Quest cost and
remaining visual/performance checks.

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
`renders/earth/`, and `earth_{day,twilight,night}.glb` assets remain under `art/`.
They contain baked lighting states, are no longer selected by the app, and are
not packaged into the APK.
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

## Regional zoom detail and Clear Earth — 2026-10-21

Coverage correction: close detail now uses the global `s2cloudless` WMS layer
(2016/2017, existing CC BY 4.0 attribution). The similarly named
`s2cloudless-2017` layer returned solid-white no-data imagery over Carrollton.
The decoder rejects and evicts exactly all-white responses before presentation,
including cached ones; the global surface or last valid detail stays visible.
Textured snow and elevation normals are preserved. See
`validation/EARTH_WHITE_FIX.md` for the Quest reproduction and validation.

Close views (surface zoom >= 2.5x) now request a snapped 18-degree region from
NASA GIBS BlueMarble_NextGeneration. This is historical, cloud-free 500 m
imagery; it adds source detail rather than sharpening the 8K global image.
The geographic observer and Wi-Fi fix are unchanged. Regions follow the viewed
surface, with date-line splitting, pole-clamped latitude bounds, a 900 ms settle
delay, cancellation, serialized decoding and an offline global fallback.
No headset location provider or Plex credentials are sent to NASA. Broad viewed
bounding boxes are transmitted, disclosed in Earth settings, and cached locally
(up to six lossless PNG regions, at most 96 MiB). No provider URLs, response
bodies, exact device coordinates or exceptions containing them are logged.

The five surface samplers are preserved for the Quest Adreno binding layout.
An on-demand 4096x6208 atlas replaces the normal-map texture: the original
4096x2048 normals occupy rows 16–2063, and 4096x4096 regional RGB occupies
2096–6191. Sixteen-row repeated gutters isolate both bands. Original normals,
city lights, ocean mask, global day image, clouds and geometry retain their
resolution. Regional edges blend into the global day image. City lights remain
8K; close detail improves daytime land imagery, not street-level terrain or
nighttime resolution. Polar caps at the exact pole retain the global fallback
where the regional blend reaches its northern/southern edge.

The atlas costs approximately 129.33 MiB with RGBA8 mipmaps, replacing the
42.67 MiB normal texture: +86.67 MiB steady GPU/shared RAM once detail is loaded.
It holds one region on the GPU. Replacing regions temporarily requires both
atlases plus CPU decode buffers; no movie-playback peak measurement is implied.

Weather settings now has a persistent **Clear Earth** toggle independent of
Live weather. It hides the cloud shell and sets surface cloud shadow and
city-light attenuation to zero. Atmosphere, terrain lighting and the day/night
cycle remain. Live-weather requests pause while Clear Earth is enabled and
resume according to the existing weather preference when it is disabled.

## Mountain inspection and progressive streaming — 2026-10-21, latest update

Close inspection now reaches approximately 117x surface magnification (previous
limit about 9x), equivalent to roughly 40 km nominal altitude at the existing
mean-radius scale. Magnification uses an expanded globe and a farther center,
retaining physical head/screen clearance. The observer's latitude/longitude,
rotation, chair, screen and tracking origin are preserved. Collision/planet
clearance receives the expanded radius. The camera stays outside the 1.0035 R
cloud shell with a 1.004 R safety boundary; the translucent atmosphere is not a
solid obstacle. Reset zoom returns the exact original scale. Saved zoom values
remain compatible.

The earlier NASA-only regional atlas described above is superseded. The
original 8K day/night/cloud maps and 4K ocean/normal maps remain unchanged.
Regional satellite imagery uses EOX's CC BY 4.0 global `s2cloudless` service
(contains modified Copernicus Sentinel data 2016 & 2017). Coverage adapts from
18 degrees to 4 degrees at 12x and 1 degree at 40x surface zoom. A final 4096px
one-degree image has about 27 m north/south sample spacing, instead of enlarging
the previous 500 m dataset. This is historical imagery, not current conditions.
Provider attribution is visible in Earth settings and bundled in earth_credits.txt.

A separate regional surface draw progressively displays 512px, 2048px and 4096px
textures. It never rebuilds or reuploads global normals. Full detail is assembled
from sixteen independent 1024px requests at the same final pixel spacing, with
four image requests in flight. Elevation-derived normal tiles from Mapzen/AWS
load independently (two requests in flight) and are sampled in their native Web
Mercator projection. Alpha is height metadata and is removed without changing
RGB normal values. The shader restores normal slope scale and follows the real
Sun; cloud shadows, night lights and ocean reflections use the existing maps.
Mountain relief here is elevation-based shading, not displaced mountain meshes
or walkable terrain.

Prefetch begins at 1.3x zoom. A 150 ms settle interval replaces the old 900 ms
wait. Raw provider bytes are cached directly (192 MiB cap) instead of waiting
for a 4K PNG recompression. Cancelling a view cancels HTTP calls immediately,
and a full-call timeout bounds each request. Preview, intermediate and full
stages cannot regress to a lower resolution if requests finish out of order.
Offline/error cases keep the last displayed image. Background use stops normal
app downloads; the explicit debug benchmark can finish without a worn headset.
No provider URLs or headset coordinates are logged, only aggregate request/cache
counts and timing.

Blender MCP authored `art/blender/earth_close_detail.blend` after saving a
separate source backup. `earth_dynamic_surface_detail.glb` contains one mesh,
no baked textures, 130,050 vertices and 260,096 triangles, retaining the source
UV layout with denser spherical curvature. The original surface asset remains.
The overlay has five samplers per draw, preserving the working Quest binding
layout. Typical added graphics resources are around 100 MiB for a midlatitude
close region, including full color mipmaps, regional normals and denser geometry;
normal coverage is bounded to 96 source tiles. Texture replacement temporarily
holds old and new regional images. Hardware timing/PSS snapshots are recorded
in validation/MOUNTAIN_ZOOM.md; they are not movie-playback peak or FPS proof.
