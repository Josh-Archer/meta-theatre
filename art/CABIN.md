# Observation cabin

The November 14 cabin replaces the spaceship interior's flat finishes with
satin ceramic liners, brushed metal frames, a matte composite deck, upholstered
side benches, recessed light strips, fitted consoles, ventilation grilles and a
split pressure door. The cushions retain a nearly-new appearance, with shallow
sitting impressions, fine seam compression and slight contact-area sheen.
The panoramic openings, original shell geometry, seat
position and default film-panel clearance are preserved.

The native app loads the finished `app/src/main/assets/hull.glb` using its existing
identity transform. No application code or scene-selection changes are required.

## Editable assets

- `blender/cabin_realism_20261114.blend`: packed textures and editable geometry.
  `Observation_Cabin_20261114` is the authoring scene;
  `Cabin_Final_Runtime_Review` reimports the final GLB;
  `Cabin_Original_Inspection` preserves the previous shell for comparison.
- `textures/cabin_20261114/`: original, procedurally authored texture sources.
- `exports/cabin_20261114/hull.glb`: reviewed native export.
- `exports/cabin_20261114/hull_before.glb`: unchanged original for geometry audit.
- `renders/cabin_20261114/cabin-overview.png` and `cabin-detail.png`: Cycles renders
  of the final exported GLB. `cabin-upholstery.png` shows the restrained fabric wear
  in close-up. `cabin-unreal.png` is the Unreal editor preview.

`scripts/preview_cabin.py` prepares a review scene from the exported GLB with
overview, bench and upholstery cameras.

The previews use authoring lights. They do not establish the appearance or frame
rate of the native Quest renderer. The GLB exports PBR materials and emissive
diffusers; Cycles and Unreal area lights remain in their authoring scenes.
The older `before.png` uses different preview lighting and is not a matched
lighting comparison.

## Rebuild

Use the live Blender MCP bridge. Execute `scripts/build_cabin.py` with `__file__`
set to that script's absolute path. It creates a new source scene from the
preserved original shell. Activate that scene, then execute
`scripts/export_cabin.py` the same way:

```python
from pathlib import Path
script = Path('/absolute/path/to/meta-theatre/art/scripts/export_cabin.py')
exec(compile(script.read_text(), str(script), 'exec'), {'__file__': str(script)})
```

The export evaluates bevels and text, preserves exact positions and custom
normals, and welds only identical vertex attributes. It writes one primitive per
mesh with a uniform vertex layout and 16-bit indices, avoiding the SDK's earlier
mixed-primitive layout issue. It uses core glTF metallic/roughness materials,
embedded PNGs, and no extensions. Exactly zero-area added bevel caps are omitted;
all 25,402 original shell triangles remain.

Run `scripts/validate_cabin.py` with NumPy and Pillow, and run the Khronos glTF
Validator. After visual review, copy the reviewed export to the native runtime
`hull.glb`, then build:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug testDebugUnitTest
```

## Unreal review

The existing `MetaTheatreAssetLab` contains
`/Game/MetaTheatre/Cabin20261114/ObservationCabin` and the imported meshes,
materials and textures under the same content folder. The map uses the full
geometry, no Nanite, and no generated mesh collision.

`scripts/review_cabin_unreal.py` recreates only this cabin folder/map in the lab.
Run the full Unreal editor with `-ExecutePythonScript=<absolute script path>`,
`-nullrhi`, and
`-EnablePlugins=PythonScriptPlugin,EditorScriptingUtilities` for import validation.
Run `scripts/capture_cabin_unreal.py` with `-vulkan -RenderOffscreen` instead of
`-nullrhi` for an actual rendered preview. Both scripts were used with UE 5.8.3.
Unreal remains the asset review sandbox; the built APK is the native Quest app.

See [validation and runtime costs](validation/CABIN_20261114.md).
