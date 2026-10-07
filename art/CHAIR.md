# Lounge chair — October 5, 2026

The runtime chair was rebuilt in the live Blender MCP session as a saddle-leather
lounge chair with walnut seat/back shells, padded arm caps, graphite metal sled
rails, rubber feet, and champagne-metal mounting hardware. Upholstery has shaped
cushions, shallow edge creases, rolled piping, individual double-row stitches,
and embedded leather color, normal, and roughness maps. Walnut uses color and
normal maps. All five authored maps are 1024 × 1024.

## Editable source and runtime asset

- `blender/lounge_chair.blend`: final standalone source, 47 editable chair
  components, studio, camera, and five packed textures; open in Blender.
- `textures/chair/`: reusable authored PNG maps.
- `scripts/build_lounge_chair.py`: deterministic procedural authoring script,
  intended to run via Blender MCP. Creates a new scene and stages GLB/FBX exports.
- `exports/chair/lounge_chair.glb`: visually reviewed export.
- `exports/chair/lounge_chair.fbx`: Unreal interchange export.
- `../app/src/main/assets/lounge_chair.glb`: identical runtime copy.
- `renders/chair/hero.png` and `rear.png`: Blender renders of the reimported GLB,
  using the exported materials, under studio lighting. These are not headset images.
- `blender/lounge_chair_original.blend` and
  `validation/chair/lounge_chair.before.glb`: preserved original chair.
- `blender/lounge_chair_v001.blend` through `v003.blend`: intermediate snapshots.

The app keeps its existing filename, scale, and position in `Lounge.kt`. The
new asset's floor is glTF Y = -0.39 m, matching the app's +0.39 m translation.
It faces +Z in glTF, with the back toward -Z. Dimensions are approximately
0.907 m wide × 0.849 m high × 0.912 m deep. No interaction or playback code changed.

## Cost and validation

| Measurement | Original | Replacement |
| --- | ---: | ---: |
| Triangles | 979,090 | 48,436 |
| GLB bytes | 45,347,440 | 6,716,004 |
| Material primitives | 1 | 6 |

Geometry is reduced by about 95%, file size by about 85%. Six opaque material
primitives increase the chair's material submission count compared with the
original; this tradeoff preserves distinct leather, walnut, stitching, metal,
fittings, and rubber. The five 1K maps require approximately 26.7 MiB if decoded
to RGBA8 with full mip chains, before driver-specific compression/overheads.
Lower polygon count is not a measured FPS improvement.

- Khronos glTF validator: zero errors, zero warnings. Eight informational
  messages concern unused UV/tangent attributes on untextured materials.
- One scene, one mesh, six material primitives, embedded images, standard glTF
  metallic-roughness materials, no required extensions. Normals and tangents included.
- Front and rear GLB round-trip renders visually inspected. Fixed the wood UV
  channel mismatch, stitch lengths, frame support connections, and back shell fit.
- `./gradlew assembleDebug testDebugUnitTest`: successful; all 26 existing tests
  pass. The APK contains the exact reviewed runtime GLB.
- `adb install -r`: successful. The installed APK SHA-256 matches the build.
  The headset was asleep; launch was requested but no new application process was
  observed. Startup, actual headset materials, close-up aliasing, seating alignment,
  and sustained CPU/GPU frame times remain unverified.

Evidence is under `validation/chair/`: `runtime-validation.json`,
`gltf-validator.json`, `android-build.log`, and `device-install.json`.
To roll back the chair, copy `validation/chair/lounge_chair.before.glb` to
`../app/src/main/assets/lounge_chair.glb`, then rebuild and reinstall.

## Unreal

The existing MetaTheatreAssetLab sandbox contains the final imported mesh at
`/Game/Chairs/AtelierFinal/SM_LoungeChair`, with six material slots and imported
textures. Unreal generated and saved three LODs: 48,436 / 24,218 / 12,108 triangles,
with screen-size thresholds 1.0 / 0.45 / 0.20. These LODs belong to the Unreal
asset; the native Android app uses the full-detail GLB.

The normal Unreal editor session exited with `VK_ERROR_DEVICE_LOST` during
startup. Import, LOD generation, bounds inspection, and saving succeeded using
the existing editor with `-nullrhi -unattended` and its local MCP endpoint.
Unreal rendered visual quality was not verified. The app remains on Meta Spatial
SDK; this asset work does not migrate it to Unreal. See
`validation/chair/unreal-validation-final.json` for queried Unreal mesh data.
