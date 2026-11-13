# Regional Earth geometry — issue #13

The implementation submits only the sections of the existing detail
sphere intersecting the displayed imagery footprint. It is included with the
close-Earth foundation on `codex/remaining-assets`. Current combined-build and
installed Quest evidence is in [REMAINING_ASSETS_20261112.md](REMAINING_ASSETS_20261112.md).
[Issue #13](https://github.com/Josh-Archer/meta-theatre/issues/13) remains open
for Quest visual review and frame-time measurements.

## Geometry and selection

`art/scripts/partition_earth_detail.py` partitions all 260,096 original triangles
into a 16×8 geographic grid. Each triangle belongs to exactly one part. Every
indexed position, normal and UV is preserved; there is no tessellation, normal,
texture, shader or silhouette reduction. Each part uses 16-bit indices because
its vertex count is below 65,536. The editable source remains
`art/blender/earth_close_detail.blend`; the original full GLB is retained.

Runtime files and bounds are in `app/src/main/assets/earth/detail/`. Bounds come
from actual triangles, including faces crossing nominal cell boundaries. Some
original UVs exceed 1 at the repeating seam. Only selection bounds are
canonicalized; exported UVs remain bit-identical.

`EarthDetailTiles.kt` intersects those bounds with the shader's geographic
footprint, including wrapped longitude and a conservative precision margin.
`EarthRenderer` shares one detail material across the parts. Entities are
created hidden once; region changes update visibility and newly visible poses.
Subsequent Earth transforms update visible parts only. The debug registration
flag still uses the full original hit-test mesh.

The fragment shader and edge blend are unchanged. Below the existing 1.3 request
threshold, retained imagery can still contribute visible detail, so no new zoom
cutoff is introduced. Parts disjoint from the displayed footprint are hidden.

## Submission budget and tradeoffs

| Metric | Full overlay | Partitioned overlay |
| --- | ---: | ---: |
| Total preserved triangles | 260,096 | 260,096 |
| Triangles selected in tested views | 260,096 | at most 8,192 |
| Detail draws in tested views | 1 | 1–4 |
| Model files, excluding manifest | 7,325,616 bytes | 6,240,712 bytes |
| Detail entities in normal mode | 1 | 128, with selected parts visible |

The tested maximum is a 96.85% reduction in submitted detail triangles, not a
measured FPS improvement. More entities and up to four draws may add CPU and
startup cost; Quest timings must establish the net result. No textures or
render passes were added. The retained full mesh loads only in debug
registration mode, but remains packaged. The 129 new APK entries add 4,345,303
compressed bytes (4.14 MiB). Removing debug-only duplication from release
packaging is a separate decision.

## Initial validation

- All 128 generated GLBs pass Khronos validation with zero errors and warnings.
- An independent read of every emitted GLB compares indexed position/normal/UV
  triangle hashes with the original: exact multiset match, including winding
  and duplicate multiplicities.
- `check_earth_partitions.py` runs the actual `earth_detail.frag` with an SDK
  transform shim on RTX 5080 EGL. All 112 nonempty matched views have exactly
  identical float pixels, with at most four draws and 8,192 selected triangles.
  Views cover the date line, partition boundaries, polar caps and oblique
  horizon views. Narrow polar footprints whose center is outside the original
  UV fan use selection tests rather than empty image comparisons.
- Four new JVM tests exercise the shipped manifest, poles, longitude wrapping,
  conservative edges and submission budgets across 243 place/zoom combinations.
- `testDebugUnitTest assembleDebug lintDebug` passed: 223 tests, zero failures,
  errors or skips; zero lint errors and 57 warnings. `git diff --check` passed.
- All 129 packaged partition entries match the reviewed files exactly.

The local build installed successfully. The installed `base.apk` and local APK
both have SHA-256
`73637f6253b07f9092774fbeaabc7943cf77219ccacf1a9732b8d4927000f8c4`.
At installation, Android user 0 was still `RUNNING_LOCKED`. It was subsequently
unlocked and the build launched successfully. Carrollton and Tokyo both loaded
regional imagery and selected one section (2,048 triangles) on the device.
See [QUEST_ASSETS_20261112.md](QUEST_ASSETS_20261112.md) for captures and measured
timings, including loading hitches and the limitations of the attempted
before/after comparison. Daytime visual acceptance, visibility changes while
navigating and a controlled CPU/GPU comparison remain pending. This build
includes the other existing local changes.

Raw reports are in ignored `art/validation/earth-partitions-20261112/`:
`partition.json`, `gltf-validation.json`, `render-parity.json`, `build.json`,
the build log and preserved pre-change files. Rebuild with the art NumPy
environment and `art/scripts/partition_earth_detail.py`; run desktop parity
with `art/scripts/check_earth_partitions.py` (NumPy and ModernGL).
