# Modern Roadster and Starman — October 9, 2026

The runtime Roadster now follows the modern red design in the user's supplied
photograph. It was authored, inspected and rendered through the live Blender MCP
session, then imported and reviewed in the existing Unreal asset lab. The low
nose, swept slit headlights, compound glass canopy, deep door sweep, dark split
spoke wheels and broad rear shoulders replace the previous first-generation body.
The central roof panel and side rails have been removed. Starman sits higher in
the exposed cockpit, with a white suit, helmet, curved black visor, gloves and
restraints. The windshield and rear canopy remain, with finished rim edges.
Starman remains an artistic passenger; this modern car is not a historical
reconstruction of the Falcon Heavy payload.

Proportions are visually estimated from the reference, not certified factory
dimensions: nominal body length 4.60 m and wheelbase 2.88 m. Fittings extend the
model to approximately 2.186 × 4.645 × 1.307 m. Geometry and the carbon weave
texture were authored for this project. The supplied photograph is not bundled
or published.

## Sources and runtime

- `blender/space_roadster.blend`: standalone source, 581 editable components,
  studio setup and one packed texture.
- `blender/roadster_modern_closed_roof.blend`: preserved previous roofed source.
- `blender/roadster_first_generation.blend`: preserved previous standalone source.
- `blender/orbital_visitors.blend`: preserved earlier combined visitor source.
- `textures/roadster/carbon_twill.png`: authored 512 × 512 repeating weave.
- `scripts/build_roadster_v3.py`, `export_roadster_v3.py`, `render_roadster_v3.py`:
  authoring, indexed GLB/FBX export and front/rear/side studio rendering. Run the
  builder through Blender MCP in an empty scene named
  `ROADSTER v3 | Open cockpit`, with `__file__` set to the script path.
- `scripts/sanitize_roadster_metadata.py`: removes workstation paths after export,
  asserting unchanged packed pixels, geometry and FBX numeric properties. It
  restores the FBX writer's timestamp helper after sanitizing, so later exports
  remain readable by Unreal in the same live session.
- `exports/flybys/space_roadster.glb` and `.fbx`: runtime and Unreal interchange.
- `../app/src/main/assets/space_roadster.glb`: byte-identical runtime copy.
- `renders/roadster/front.png`, `rear.png`, `side.png` and `cockpit.png`: reviewed renders of the
  reimported GLB, with PNG text/path metadata removed without changing pixels.
- `renders/roadster/unreal.png`: actual Unreal asset thumbnail.

Blender front is -Y, up is +Z and tire contact is near Z=0. glTF exports Y-up.
The app retains the asset filename, staged flyby, placement and display scale.
ISS, Earth, planet and chair assets are unchanged.

## Runtime cost and validation

| Measurement | Closed roof version | Open cockpit version |
| --- | ---: | ---: |
| Triangles | 139,996 | 136,900 |
| GLB bytes | 4,278,732 | 4,195,072 |
| Material submissions | 10 | 10 |
| Embedded images | 1 | 1 |

Removing the roof panel and rails reduces the triangle count by 3,096; the body,
wheels, interior and suit retain their detail.
All full-detail geometry is retained; Unreal's reduced LODs do not replace the
Android GLB. The explicit Roadster cap is 145,000 triangles/ten primitives; the
ISS cap remains 45,000/eight. The single 512-square weave uses roughly 1.33 MiB
as RGBA8 with mipmaps before platform compression and overheads. All ten
materials are opaque; the windshield and rear canopy use dark reflective glass. The roof
opening and side windows expose the cabin. Standard metallic/roughness and optional clearcoat
need no required glTF extensions.

Khronos validation passes with zero errors/warnings; 19 informational messages
concern unused UV channels. The sanitized Blender source reloads with its image
packed. GLB front/rear/side/cockpit round-trip renders were visually reviewed. A ray from
above the exported occupant hits the white helmet without roof obstruction. All 34
runtime asset files in the APK match the current files exactly.

Run the visitor checks with `gltf-validator` 2.0.0-dev.3.10 visible to Node:

```sh
npm install --prefix /tmp/meta-theatre-gltf-validation gltf-validator@2.0.0-dev.3.10
NODE_PATH=/tmp/meta-theatre-gltf-validation/node_modules node art/scripts/validate_space_visitors.cjs
```

## Unreal review

The sanitized FBX was imported into
`/Game/Flybys/RoadsterOpen/SM_SpaceRoadster`, with all ten materials assigned.
Twelve assets were saved and confirmed clean. The interactive editor captured
the asset thumbnail and generated three LODs: 136,836 / 68,417 / 34,209 triangles,
with screen thresholds 1.0 / 0.45 / 0.20. FBX import accounts for the small
triangle-count difference; the Android app uses Blender's GLB. Unreal remains a
separate asset sandbox; Meta Theatre continues to use Meta Spatial SDK.

## Android and headset evidence

The full suite passes 118 tests, including five synthetic integration tests of
the shared library/movie gesture routing. Debug assembly and lint pass with
zero lint errors. Existing two-hand resize fixes remain included: aim both
hands at the screen, pinch both index fingers/thumbs, hold and spread/contract,
then release to retain the size. The first held pinch can wait for the second;
a consumed resize cannot select a library item or seek.

The new build was installed as an in-place APK replacement and its installed
SHA-256 matches the build. The headset was asleep; the launch request succeeded
but no Theatre process was observed. Real headset appearance, physical hand
tracking and sustained frame times remain unverified. The Roadster first passes
after 12 seconds of active scene time; Quiet cinema pauses movie-time flybys.
