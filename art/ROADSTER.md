# Roadster and Starman — October 8, 2026

The replacement was authored and rendered through the live Blender MCP session,
then imported, rendered and saved in the existing Unreal asset lab. It depicts
the original-generation open-top Roadster used for the Falcon Heavy payload.
The previous combined ISS/Roadster source remains in `blender/orbital_visitors.blend`.

Reference proportions come from [Tesla's Roadster service manual](https://service.tesla.com/docs/Public/Roadster/ServiceManual/en-us/GUID-4E037ADB-D0F4-48A0-9261-1083193D4C1B.html):
3.946 m body length and 2.351 m wheelbase. The nominal body width is about 1.728 m;
including mirrors the modeled width is 1.848 m. Starman and the open cockpit were
referenced against [SpaceX's Falcon Heavy/Starman video](https://www.youtube.com/watch?v=A0FZIwabctw)
and [NASA's SpaceX-credited payload image](https://science.nasa.gov/image-article/apod-2018-february-10-roadster-starman-planet-earth/).
All model geometry and the carbon weave texture were authored for this project.
Reference photographs are not bundled in the app.

The body has compound curved bonnet/deck surfaces, actual wheel openings and
liners, shaped cockpit shoulders, swept twin-projector lamp housings with
curved covers, bonnet outlets, recessed grille, side intakes, four round rear
lamps and a diffuser. The wheels have directional spokes, visible brake discs,
calipers and tire channels. The interior includes bolstered seats, stitching,
roll hoops, a steering wheel and a DON'T PANIC inscription. Starman has a seated
pose, helmet shell and curved visor, articulated sleeves, gloves and restraints.

## Sources and runtime

- `blender/space_roadster.blend`: standalone source, 594 editable components,
  studio setup and one packed texture. The previous live scene was preserved.
- `textures/roadster/carbon_twill.png`: authored 512 × 512 repeating weave.
- `scripts/build_roadster_v2.py`, `export_roadster_v2.py`, `render_roadster_v2.py`:
  authoring, indexed GLB/FBX export, and front/rear/side studio rendering.
  Run the builder in a new, empty scene named `ROADSTER v2 | Authored source`
  via Blender MCP, with `__file__` set to the script path.
- `scripts/sanitize_roadster_metadata.py`: execute after export in Blender to
  remove workstation paths from the source and FBX. It asserts that packed
  pixels, geometry and every FBX numeric property are preserved.
- `exports/flybys/space_roadster.glb` and `.fbx`: runtime and Unreal interchange.
- `../app/src/main/assets/space_roadster.glb`: byte-identical runtime copy.
- `renders/roadster/front.png`, `rear.png`, `side.png`: renders of the reimported
  GLB, visually reviewed. PNG text/path metadata was removed without changing pixels.
- `renders/roadster/unreal.png`: the actual Unreal asset thumbnail.

Blender front is -Y, up is +Z, and tire contact is near Z=0. glTF exports Y-up.
The app keeps the same filename, staged flyby, placement and display scale.
The source has a 3.946 m body; fittings extend the full model to about
1.848 × 3.968 × 1.273 m including Starman. No ISS, Earth, planet or chair asset changed.

## Runtime cost and checks

| Measurement | Previous Roadster | Replacement |
| --- | ---: | ---: |
| Triangles | 19,124 | 120,202 |
| GLB bytes | 646,752 | 3,820,604 |
| Material submissions | 7 | 10 |
| Embedded images | 0 | 1 |

The increase buys silhouette, cockpit, wheel and suit detail. This is a higher
runtime cost, not a claimed performance improvement. The full-detail GLB is
retained; Unreal's reduced LODs do not replace it. The explicit validation cap
is now 125,000 triangles and ten primitives for this asset only. The ISS cap
remains 45,000 triangles/eight primitives. The 512-square weave uses roughly
1.33 MiB as RGBA8 with mipmaps before platform compression/overheads. Glass and
lamp covers share one translucent material; the other nine materials are opaque.

Khronos validation returned zero errors and warnings; 19 informational messages
concern unused UV channels. One mesh, ten primitives, one embedded texture,
standard metallic/roughness materials and optional clearcoat; no required
extensions. The sanitized Blender source reloads with its image packed. The
sanitized FBX was reimported into Unreal successfully. All 34 runtime asset files
in the built APK match the source files exactly.

Run the visitor checks with `gltf-validator` 2.0.0-dev.3.10 installed and visible
to Node, for example:

```sh
npm install --prefix /tmp/meta-theatre-gltf-validation gltf-validator@2.0.0-dev.3.10
NODE_PATH=/tmp/meta-theatre-gltf-validation/node_modules node art/scripts/validate_space_visitors.cjs
```

## Unreal review

The existing asset lab contains `/Game/Flybys/RoadsterReviewed/SM_SpaceRoadster`,
imported from the sanitized FBX with ten material slots and the embedded weave.
The interactive editor rendered the asset thumbnail. Unreal generated and saved
three LODs: 120,074 / 60,036 / 30,019 triangles, with screen thresholds
1.0 / 0.45 / 0.20. The small triangle-count difference reflects the FBX import;
the Android app uses Blender's GLB. This is asset review in the existing sandbox.
The app remains on Meta Spatial SDK.

## Two-hand screen resizing

The old custom gesture ran only during movie playback, and required pinch
starts within 500 ms. The shared routing now runs on the library/login panel
and movie screen. The first pinch may wait for the second while held. Both
pinches must start on the screen face; rail-owned gestures retain ownership.
Once a stretch starts, rays may leave the face while the hands expand/contract.
Release retains the size, and consumed pinches cannot select an item or seek.
Single library selections occur on quick pinch release, leaving a second hand
free to join without selecting a title. Movie single-hand swipe seeking remains.
Hidden screens, hand navigation, tracking loss and mode transitions cancel the
stretch and require released hands before a new gesture.

Five new integration tests exercise the shared router used by ScreenGrip across
library/movie modes, delayed joining, expansion/contraction, off-face movement,
click/seek ownership, tracking context changes and rail exclusion. The full
suite passes 118 tests; debug assembly and lint pass with zero lint errors.
These are synthetic input sequences, not physical hand-tracking measurements.

The update was installed using an in-place APK replacement; installed APK SHA-256
matches the build. The headset was asleep and no new Theatre process was observed
after launch. Real two-hand motion, flyby appearance, transparency and sustained
Quest frame times remain unverified. To check, launch Theatre, aim both hands at
the library or movie screen, pinch both index fingers/thumbs and hold while
spreading/contracting. Release and confirm that the size persists. The Roadster
first passes after 12 seconds of active scene time; Quiet cinema pauses flybys.
