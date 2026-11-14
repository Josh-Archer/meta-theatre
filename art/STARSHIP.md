# Starship flight exhibit

In **Scenes → Earth orbit**, choose **Launch / replay Starship**. The staged
116-second sequence includes liftoff, separation, a complete Earth orbit,
belly-first descent, a landing flip, landing burn and tower recovery. **End
flight** hides it. Playback starts only on request. Launch reframes Earth around
the current viewer without moving the seat or movie screen. Quiet cinema, loss
of focus and close-Earth zoom pause and hide the flight; leaving Earth orbit
ends it.

This is an original artistic model based on the early flight-test configuration:
a nominal 50 m ship, 71 m booster and 9 m diameter. It is not a reconstruction of
a current mission or live telemetry. The compact Earth, vehicle scale of 0.12,
time compression, simplified booster return and static tower arms make it a
viewable exhibit. Landing is staged tower recovery, not a simulated catch system.
The flaps are modeled in a fixed position; no articulated aerodynamics or
atmospheric physics are claimed.

Reference: [SpaceX Starship](https://www.spacex.com/vehicles/starship/), the
[2020 user guide](https://www.spacex.com/media/starship_users_guide_v1.pdf) and
[Starbase overview](https://www.spacex.com/vehicles/starship/assets/media/Starbase%20Overview.pdf).
No third-party mesh or SpaceX image is bundled.

## Sources and exports

- `blender/starship_20261113.blend`: separately named editable ship, booster,
  launch site and exhaust collections, with packed textures.
- `textures/starship/`: authored 1024-square repeating ceramic normal and
  roughness/metallic maps. Steel weld rings, open grid fins, six ship nozzles and
  33 booster nozzles are geometry.
- `scripts/build_starship.py`: deterministic Blender authoring and indexed GLB
  export. Run through the live bridge with `__file__` set to its absolute path.
- `exports/starship_20261113/`: reviewed exports. Four Starship files and the
  revised Roadster have byte-identical copies in `app/src/main/assets/`.
- `renders/starship_20261113/`: studio reviews, matched Roadster material
  comparisons and a labeled flight preview. These are Blender renders, not
  headset captures. The preview follows the actual Kotlin trajectory at 6×
  speed with a moving review camera; the native camera stays under user control.

The Blender vehicle axis is +Z, engine base at zero; exported glTF is +Y-up.
The runtime uses five cached entities and reuses the plume mesh. Meshes have no
collision, every material is opaque, and burns use an emissive mesh rather than
particles. No per-frame mesh or texture upload is added. Transform/visibility
updates are cached; phase labels update only on transitions. Ended flights retain
loaded meshes for replay and hide them, so their memory persists for the session.

| Asset | Triangles | Material primitives | GLB bytes |
| --- | ---: | ---: | ---: |
| Starship | 26,488 | 4 | 1,453,720 |
| Super Heavy | 86,144 | 3 | 2,047,744 |
| Tower and pad | 7,992 | 3 | 511,920 |
| Shared plume | 256 | 1 | 6,648 |

The conservative bound with both plume instances visible is 121,136 triangles and 12
material submissions before culling. Both ceramic maps total approximately
10.67 MiB as RGBA8 with mipmaps, before platform compression and overhead.
Lossless removal of unused attributes and welding of identical vertices reduced
the four files from 6,954,412 to 4,020,032 bytes (42.2%). Indexed retained vertex
values and embedded image bytes were verified unchanged. No decimation, texture
resizing or reduced source quality is used.

## Reproduction and validation

1. Run `scripts/build_starship.py` in live Blender.
2. Run `scripts/optimize_reviewed_glb.mjs` with the glTF Transform dependency
   directory and the four exported Starship GLBs; copy them to runtime assets.
3. Run `scripts/validate_starship_assets.cjs` with `gltf-validator` on `NODE_PATH`.
4. Run `./gradlew testDebugUnitTest assembleDebug lintDebug`. To export the
   tested native trajectory, set `META_THEATRE_FLIGHT_PREVIEW` to an absolute
   `art/validation/starship-20261113/flight-frames.json` path before the tests.
5. Run `scripts/render_starship_review.py` and `render_starship_flight.py` through
   Blender, then `python art/scripts/encode_starship_preview.py` for the MP4.
6. In the existing Unreal sandbox run `scripts/validate_starship_unreal.py` via
   `tools/launch-unreal-asset-lab.sh -nullrhi -unattended -nosplash
   -ExecutePythonScript=/absolute/path/to/script`. Run again in a fresh process
   to verify saved imports reload. NullRHI does not validate rendered appearance.

See [the dated validation record](validation/STARSHIP_20261113.md) for automated
results, installed build identity and outstanding headset inspection.
