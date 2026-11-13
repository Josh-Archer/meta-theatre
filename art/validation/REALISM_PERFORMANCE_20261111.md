# Meta Theatre material and performance pass — 2026-11-11

Current integration and automated Quest results are recorded in
[REMAINING_ASSETS_20261112.md](REMAINING_ASSETS_20261112.md).
The dated results below describe the original pass.

The chair and Roadster runtime assets now have refined PBR materials. Their
geometry counts, dimensions, material slots, and interaction placement are
preserved. Earth and visitor rendering avoid redundant work. Existing unfinished
work in the checkout was retained; the pre-change patch, sources, asset hashes,
and APK are saved locally in `realism-20261111/baseline/`.

## Visual changes

- Roadster: dielectric red paint with a less polished finish, shared normal and
  roughness maps for tire rubber and interior leather, and carbon-fiber relief.
  Smooth quad normals survive triangulation. All 136,900 runtime triangles remain.
- Chair: restrained leather grain, varied satin upholstery roughness, and a softer
  oiled-walnut finish. Stitching, piping, fittings, and all 48,436 triangles remain.
- Existing texture resolutions, Earth maps, sky imagery, video quality, and panel
  interaction behavior are preserved. Four new Roadster data maps are 512 × 512.
- Matched 64-sample Cycles renders use the actual exported/reimported GLBs with the
  same lighting, camera, exposure, and resolution. These are authoring previews,
  not Quest screenshots. This is a material refinement, not a full automotive
  topology rebuild; sharp body-panel transitions remain in the source model.

Sources: `../blender/space_roadster_realism_20261111.blend` and
`../blender/lounge_chair_realism_20261111.blend`, with packed textures.
The prior canonical Blender sources remain as the input versions for this pass.
Reviewed canonical exports are in `../exports/flybys/` and `../exports/chair/`;
runtime copies remain `app/src/main/assets/space_roadster.glb` and `lounge_chair.glb`.

Previews: [Roadster before](../renders/realism_20261111/space_roadster_before.png),
[Roadster after](../renders/realism_20261111/space_roadster_after.png),
[chair before](../renders/realism_20261111/lounge_chair_before.png),
[chair after](../renders/realism_20261111/lounge_chair_after.png).

## Asset cost

| Asset | GLB bytes before → after | Geometry buffer bytes before → after | Triangles | Material primitives |
| --- | ---: | ---: | ---: | ---: |
| Roadster | 4,195,072 → 4,717,048 | 4,166,240 → 4,061,184 | 136,900 | 10 |
| Chair | 6,716,004 → 5,575,576 | 3,447,336 → 2,278,104 | 48,436 | 6 |

Combined files are 5.7% smaller and geometry buffers 16.7% smaller. The cleanup
removes unused attributes and merges only bitwise-identical vertices. It does
not quantize, simplify, resize, or recompress textures. The optimizer verifies
indexed attribute values and embedded image hashes through its write/read cycle.

The Roadster gains four 512² data textures: approximately 5.33 MiB of additional
RGBA8 texture storage with full mip chains before driver compression/overhead.
This visual-quality tradeoff is separate from the geometry savings; total GPU
memory has not been measured. Desktop before/after optimization renders have
small stochastic differences (mean below 0.009/255 per channel), not a claim of
bit-identical Cycles renders.

The audit also found an existing 260,096-triangle regional Earth overlay, 8K
Earth/sky maps, a 37,865-triangle ISS, and preserved legacy Earth/hull assets.
Their geometry and resolution remain intact. Future reductions should be driven
by Quest profiling, especially overlay vertex processing and texture residency.

## Runtime work removed

- Clear-Earth mode skips all three cloud texture samples and their attenuation
  math in both the global and regional surface shaders. Cloud-enabled shading
  is unchanged. Both shaders reuse one derivative tangent frame.
- Satellite composition holds at most four concurrent 1K tile builders, including
  cached decodes; terrain composition holds at most two. Permits span decode,
  copy, and recycling, not only network fetches.
- Terrain normal RGB data is copied directly into the destination bitmap after
  discarding alpha metadata, removing one temporary 256² bitmap per tile
  (256 KiB per tile; up to 24 MiB of allocation over a 96-tile request).
- Visitor placement caches seven pose scalars and scale. Moving models retain
  every exact pose update; unchanged scales and stationary inspection poses
  avoid ECS writes. The cache adds no per-frame pose-copy or callback allocations.

These are verified reductions in work and allocation. No Quest FPS gain is claimed.

## Verification

- Android build, shader compilation, 219 JVM/Robolectric tests: passed; zero failed
  or skipped tests. Lint: zero errors, 57 warnings in the full checkout.
- New regression tests cover cached satellite quadrant pixels, terrain RGB/alpha
  preservation, continuous flyby motion, scale restoration, stationary inspection,
  and in-place mutable pose changes.
- Desktop EGL shader comparison: 64 scenarios covering day/night, poles, cloud
  visibility, static/live cloud styles, and regional normal boundaries. All output
  floats were finite and every before/after pixel was identical on the RTX 5080.
  Actual application shader sources were used with a shared SDK transform shim;
  this does not establish Quest-driver behavior.
- Khronos glTF validation: zero errors or warnings for both assets. No required
  extensions. Standard PBR normal/roughness maps follow the
  [Blender glTF material conventions](https://docs.blender.org/manual/en/5.2/addons/scene_gltf2.html).
- APK contains the exact reviewed GLB bytes; original ISS validation still passes.
- Unreal saved both assets, materials, and textures under
  `/Game/MetaTheatre/Realism20261111/`. Nanite was disabled for full-detail
  conventional-mesh validation. Unreal removes 64 exactly zero-area Roadster pole
  triangles (independently checked from the GLB), leaving 136,836 rendered triangles;
  the chair remains 48,436. All 10/6 material slots and texture dimensions match.
  This used NullRHI: import/material/save evidence only, not an Unreal render.
- `git diff --check`: passed.

Raw evidence is in the local ignored `realism-20261111/` folder, including
`asset-comparison.json`, `gltf-validation.json`, `unreal-validation.json`,
`shader-parity.json`, `test-summary.json`, and build logs.

## Device verification

ADB sees the authorized Quest 3. During launch attempts it was awake, but Android
reported user 0 as `RUNNING_LOCKED` with the keyguard showing. The package and
activity are installed; the activity resolves with direct-boot match flags but
normal launch returns error type 3. Other credential-protected services are also
unavailable. A normal headset unlock is required; no lock-screen bypass or
application direct-boot changes were made. It subsequently returned to sleep.

The updated APK was installed successfully, and its on-device SHA-256 matches
the local build (`6352a918469c5102c611398047bf9a636be4b544108cfa37f76b0ba3f4f2e14d`).
No app process is running while the user remains locked. The baseline APK is
preserved for a comparable run after unlock. Actual headset
materials, stereo appearance, aliasing, gameplay/gesture behavior, CPU/GPU timings,
texture residency, thermal behavior, and movie-playback performance remain pending.

## Reproduction

1. Run `art/scripts/refine_runtime_materials.py` in the live Blender MCP session,
   with `__file__` set to its path. It stages exports and writes separate packed
   source versions, preserving the input scenes.
2. Run `art/scripts/optimize_reviewed_glb.mjs NODE_MODULES asset.glb ...` using
   glTF Transform core/functions/extensions 4.5.1.
   The locally tested package versions are recorded in the validation tool lockfile.
3. Run `art/scripts/render_material_comparison.py` through Blender and inspect the
   matched GLB renders. Publish reviewed files to canonical exports and runtime.
4. Run `art/scripts/validate_materials_unreal.py` using the existing launcher and
   `-nullrhi -unattended -ExecutePythonScript=...`. Its versioned folder is task-owned.
5. Run `check_earth_shader_parity.py BASELINE_SHADER_DIR` with ModernGL and NumPy,
   and `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug lintDebug`.

This work is local; no commit, push, PR, or remote publication was performed.
