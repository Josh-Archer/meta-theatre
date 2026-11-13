# Meta Theatre solar environment — 2026-10-04

The [November 11 celestial pass](validation/CELESTIAL_20261111.md) adds NASA
LROC lunar color, LOLA crater normals, differentiated surface lighting and the
current sky grade. Its versioned Blender source and review steps supersede the
older Moon material and regeneration steps below.

The 2026-10-19 sky upgrade supersedes the 4K map and sky-memory figures below.
See [SPACE.md](SPACE.md) for the 8K source, publishing steps and current checks.
Run its final sky publishing step after regenerating the legacy solar assets.

The native Quest app now shows all eight planets, the Moon, a visible moving
Sun, Saturn's main rings, and a NASA catalog star panorama. Earth remains the
foreground globe at the equator beneath the USA (100°W). The other bodies
surround the seated viewer; turn to see the outer planets.

## Physical size and exhibit distances

Every body uses **90 metres / 6371.0084 kilometres**. Diameter ratios are preserved,
including the Moon and Sun. These are mean-radius spheres without oblateness or
terrain displacement. Viewing distances change their apparent sizes.

| Body | Reference mean radius, km |
| --- | ---: |
| Mercury | 2439.4 |
| Venus | 6051.8 |
| Earth | 6371.0084 |
| Mars | 3389.5 |
| Jupiter | 69911 |
| Saturn | 58232 |
| Uranus | 25362 |
| Neptune | 24622 |
| Moon | 1737.4 |
| Sun | 695700 |

Planet values come from [NASA/JPL](https://ssd.jpl.nasa.gov/planets/phys_par.html).
The [Moon](https://nssdc.gsfc.nasa.gov/planetary/factsheet/moonfact.html) and
[Sun](https://nssdc.gsfc.nasa.gov/planetary/factsheet/sunfact.html) use NASA fact sheets.
Saturn's C-through-A main rings span 74,500–136,780 km from its center, using
[NASA's ring boundaries](https://nssdc.gsfc.nasa.gov/planetary/factsheet/satringfact.html).
The ring mesh divides those values by Saturn's mean radius (58,232 km).

Distances are compressed and arranged for the theatre. This is not a map of
current planetary positions or an orbital simulation. The physical Sun light is one astronomical unit from Earth at the shared scale.
Its visible sphere is projected onto the same viewing ray 80,000 scene metres
from the seat, with its radius reduced by the same projection factor. This
preserves its approximately 0.533 degree apparent diameter without extending
the scene far plane. The sky radius is 110,000 m. Planet silhouettes are separated
from the nominal seat. Saturn uses a deliberate viewing pitch of 26.7° and roll
of −18° to show its rings. The exhibit does not model astronomical pole orientations.

## Lighting and time

`SolarSystem.sunPosition()` transforms the existing Earth civil-time Sun into
world space. That point drives per-fragment planet and Moon shading, Earth's three layers,
and chair lighting. The visible Sun uses a collinear projection of that point.
Custom shader uniforms reflect the API world Z axis into GraphicsHSR world
space; each shader transforms that world point with the actual imported mesh
`objectFromWorld` matrix. This includes native glTF basis and node transforms,
which a CPU entity-only inverse transform does not capture. A shared headset-local time
sample updates once per second and immediately on session transitions. The
solar movement is only about 0.0042° per second.

At the centered Earth meridian, 06:00 is dawn, 12:00 day, 18:00 dusk and 00:00
night. City lights fade in through twilight. Geography stays fixed as light moves.
This is an artistic 24-hour civil-time cycle, not seasonal sunrise/sunset.

The Moon's illumination follows the shared Sun/view geometry, rather than a
calendar lunar phase. Saturn casts an analytic shadow on its rings; ring alpha
casts a shadow onto Saturn. Analytic globe occlusion rejects back-ring fragments
behind Saturn, reducing dependence on distant depth precision. Other inter-body
eclipses are not simulated. Exposure, night fill, twilight and solar glow are
artistic, without astronomical inverse-square irradiance or physical exposure.

The Sun is emissive with limb darkening and a subtle halo. Stars are self-lit,
stable catalog imagery, independent of sunlight. Their preprocessing pass has
no depth writes and always sits behind foreground bodies. Scene depth parameters
are 0.05–120,000 m.

## Assets and sources

- `blender/solar_system.blend`: packed, editable source with neutral asset library,
  true-size gallery and seated theatre reference. The latter has a `local_hour`
  custom property. Earlier Earth scenes remain available in this source.
- `blender/earth_dynamic_before_solar.blend`: separate source saved before edits.
- `textures/solar/source/`: original maps and NASA linear EXR. Some provider files
  named `8k` actually contain 4K images; the source manifest records actual sizes.
- `textures/solar/runtime/`: 2K body maps, 2048×1 ring profile and 4K star panorama.
  Stars have a fixed contrast/exposure curve to retain a dark background.
- `exports/solar/`: neutral GLBs with one mesh/material each, identity node
  transforms, no embedded textures and verified UV orientation.
- `../app/src/main/assets/solar_{body,rings,corona,stars}.glb` and `solar/`:
  runtime geometry/maps. Kotlin assigns custom materials.
- `renders/solar/`: size gallery, morning/dusk/night theatre references, Saturn
  and star panorama. These are Blender renders, not headset screenshots. The
  theatre reference omits the movie panel and chair to show the assets.
- `../app/src/main/assets/solar_credits.txt`: redistribution credits and changes.

Planet, Moon, Sun and ring maps are [Solar System Scope / INOVE](https://www.solarsystemscope.com/textures/),
CC BY 4.0. They include artistic color treatment and filled mapping gaps. Venus
uses cloud-cover imagery. Stars use [NASA Deep Star Maps 2020](https://svs.gsfc.nasa.gov/4851/),
Ernie Wright, NASA/Goddard Scientific Visualization Studio; Gaia DR2: ESA/Gaia/DPAC.
Constellation illustrations are not used.

The new runtime targets the native Meta Spatial SDK app. Earlier Unreal sandbox
Earth assets remain separate. The solar shaders have not been ported to Unreal;
their GLBs and source maps remain reusable there.

## Runtime cost and validation

| Item | Cost |
| --- | ---: |
| Reusable body sphere | 9,024 triangles |
| Eight added body spheres plus Sun | 81,216 triangles |
| Two-sided Saturn annulus | 1,024 triangles |
| Sun halo / inward sky | 3,968 / 3,968 triangles |
| Existing three-layer Earth | 41,280 triangles |
| Total celestial geometry | 131,456 triangles, 15 draws |
| Solar RGBA8 textures with full mip chains | 138.68 MiB estimated |
| Earth plus solar textures | 288.01 MiB estimated |

Textures upload one at a time. Estimates exclude transient native copies, chair,
movie surfaces and render targets. Actual Quest memory and timing are unmeasured.

Build with JDK 17:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug
```

All 16 tests pass: six Earth lighting tests, two update-cadence tests and eight
solar-system tests covering radius ratios, shared Sun transforms, orientation,
ring visibility, separation and clipping bounds. Seven shader families compile
84 variants. Packaged assets and credits were compared with source bytes.
All four solar GLBs pass Khronos validation with zero errors/warnings, plus
checks for radius, UVs, normals, ring bounds and two-sided faces.

Evidence: `validation/solar_system_build.json`, `validation/solar_gltf_report.json`
and `validation/solar_source_manifest.json`. APK, from the project root:
`app/build/outputs/apk/debug/app-debug.apk`.

No APK was installed or launched on Quest. Native import handedness, stereo,
far clipping, depth precision, alpha ordering, appearance and GPU frame timing
remain device checks. Desktop renders and compilation do not verify them.

## Regeneration

Preserve a source version first. Scripts create new datablocks and should not be
rerun over edited final materials expecting in-place updates.

1. Run `python3 art/scripts/prepare_solar_textures.py` (Requests and Pillow).
2. With the dynamic Earth source open, execute `art/scripts/build_solar_assets.py`
   through Blender MCP. It saves a source copy, builds meshes/materials and stages GLBs.
3. Convert `textures/solar/runtime/stars.png` to RGB `stars.jpg` with Pillow,
   JPEG quality 96 and subsampling 0.
4. Execute `art/scripts/author_solar_preview.py` through Blender MCP; inspect renders.
5. Run `node art/scripts/validate_solar_assets.cjs`. Set `GLTF_VALIDATOR_MODULE`
   to an installed `gltf-validator` module if the temporary default path is absent.
6. Copy ready GLBs/maps to runtime asset paths and rebuild. Inspect on Quest before release.

## Headset feedback corrections — 2026-10-04

The initial installed build decoded a movie and reported its first video frame,
but the user saw a black movie rectangle. The movie is now a direct compositor
underlay with a hole-punch mesh; the scene clears the movie rectangle, while
mesh controls stay in front. Panel initialization reasserts its transform after
the SDK registers the asynchronous scene object. The UHD 3840×2160 surface is
retained.

The oversized roughly 14-degree Sun was corrected using the one-AU light and
collinear sky projection described above. Blender source and the preview
regeneration script follow the same world position and angular-size projection.
A separate source copy is `blender/before_sun_alignment_20261004.blend`. Runtime
GLBs, textures and planet radii were preserved.

The correction build passes 26 unit tests with zero failures and lint with zero
errors. Installation on Quest succeeded. See `validation/VISUAL_FIXES.md` for
current device and hands-on verification, which is separate from these checks.
