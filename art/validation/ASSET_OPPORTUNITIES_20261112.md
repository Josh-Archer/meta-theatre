# Remaining asset and efficiency opportunities — 2026-11-12

Implementation update: all six findings below are addressed on
`codex/remaining-assets`; see [combined validation](REMAINING_ASSETS_20261112.md).
This initial audit predates upstream cabin support: `hull.glb` is now consumed
and retained, so actual removed APK entries total **29.17 MiB**, not 32.48 MiB.
The Quest has since been unlocked and automated native diagnostics passed.
The original findings below are retained as the historical rationale.

Review of the current dirty checkout following the November 11 material and
celestial passes. No runtime assets or application code were changed by this
review. The only optimization experiment used an ignored scratch copy of ISS.
The attached Quest 3 still reports `RUNNING_LOCKED`; no fresh FPS measurements
were obtained.

## Recommended next pass

1. Improve ISS materials and apply its verified lossless export cleanup.
2. Exclude unused legacy assets from app packaging while retaining art sources;
   release weather source buffers on explicit disable or memory pressure; give
   the library panel an explicit unit Scale component.
3. Replace the full-sphere regional detail submission with a bounded patch,
   preserving current visible tessellation, UVs, normals and edge blending.
4. Add distinct high-cloud morphology when supported by real weather-layer data.

## Findings and evidence

### ISS: strongest remaining prop improvement

The native ISS has 37,865 triangles, four primitives/materials and one 1024-square
color texture. Thermal blankets, aluminium and gold insulation use constant
base color/metalness/roughness; only photovoltaic cells use a texture. There are
no normal or roughness textures. Authoring is in
[`build_space_visitors.py`](../scripts/build_space_visitors.py), especially the
material definitions at line 27. The editable source is
`art/blender/orbital_visitors.blend`.

Bake restrained foil wrinkles, blanket seams, brushed surface variation and
cell-coating response into material maps. Keep close-inspection geometry and
the four existing material slots. Budget added texture memory explicitly.

The existing lossless optimizer was run on
`art/validation/asset-audit-20261112/iss.glb`, copied from the runtime file:
1,262,484 → 1,045,220 bytes, **217,264 bytes / 17.21% smaller**. Indexed values
of retained vertex attributes and embedded texture bytes survived fingerprint
checks and export round trip. Unused UVs were removed from untextured materials.
No triangles were removed. glTF validation: zero errors/warnings; the pre-existing
53 degenerate triangles remain an informational item. This is file/geometry
storage evidence, not a measured frame-time improvement.

### Close Earth: highest-priority geometry investigation

`earth_dynamic_surface_detail.glb` is a 260,096-triangle complete sphere.
[`EarthRenderer.kt`](../../app/src/main/java/casa/archer/metatheatre/EarthRenderer.kt)
loads it at lines 145–154 and makes it visible at line 171.
[`earth_detail.frag`](../../app/src/shaders/earth_detail.frag) rejects pixels
outside the current geographic region at line 37. The vertices have already
been submitted at that point.

Use a geographic patch containing the same relevant triangles (including an
edge margin), or partition the existing mesh for selective submission. Preserve
horizon coverage, wraparound, polar cases and transition blending. Do not replace
the close-view mesh with a globally coarser sphere. More draw calls versus fewer
submitted vertices is a device-measured tradeoff.

`EarthDetailSession.view()` stops requests below zoom 1.3 but does not send a hide
callback, and the renderer retains the last detail texture and visible overlay.
Audit this lifecycle together with patch visibility; hiding must be based on
whether the detail contributes, not an unreviewed quality cutoff.

This targets geometry and transparent overdraw concerns described in
[Android's rendering guidance](https://developer.android.com/games/optimize/optimization-tips).
No percentage FPS improvement is established.

### Weather: release 56 MiB of inactive CPU source arrays

[`WeatherCloudMap.kt`](../../app/src/main/java/casa/archer/metatheatre/WeatherCloudMap.kt)
stores one 8192×4096 alpha ByteArray and three 4096×2048 structure ByteArrays:
**56 MiB**, excluding small object overhead. `EarthRenderer.setWeather(null)`
releases the live GPU replacement and restores the original map, but leaves
`weatherMap` retained until renderer teardown.

Release this CPU cache on explicit weather disable or memory pressure. Distinguish
disable from temporary regional fallback: both currently call `render(null)`.
Re-enabling incurs a source reload, so avoid repeatedly evicting during ordinary
navigation. Preserve all map dimensions and content.

The live update still creates and uploads a complete 128 MiB RGBA bitmap. This is
a separate profiling target; the older October 22 device report recorded an update
hitch, but it is not a benchmark of the current build. Reusing identical snapshots
and avoiding needless rebuilds is preferable to reducing resolution. Moving work
to the shader or adding tiled texture updates requires an explicit GPU/API cost
comparison, not an assumption that it is faster.

### Packaging: 32.48 MiB of compressed APK entries have no current app references

The current app source scan found no consumers for these legacy assets. Earth
explicitly loads the dynamic layers; `Lounge.kt` creates only the chair; weather
loads `cloud_structure.png`, not the previous pattern atlas.

| Asset | File MiB | Compressed APK MiB |
| --- | ---: | ---: |
| earth_day.glb | 9.34 | 8.97 |
| earth_night.glb | 8.90 | 8.55 |
| earth_twilight.glb | 9.15 | 8.79 |
| hull.glb | 4.78 | 3.32 |
| weather/cloud_patterns.png | 2.85 | 2.85 |
| Total | 35.03 | 32.48 |

Exclude these from runtime packaging after a final dynamic-reference check and
APK content test; retain editable/reference copies under `art/`. These are
installation/storage savings, not currently resident GPU-memory or FPS savings.

### Panel: eliminate the missing-Scale fallback path

The library panel is created without `Scale` in `TheatreActivity.kt:298`.
`ScreenGrip.logicalScale()` at line 578 repeatedly attempts `getComponent<Scale>()`
and catches failure to use unit scale. The October 22 device report recorded
matching missing-component log traffic. The code path is still present; its
current frequency is not measured. Initialize unit Scale and verify saved screen
size, grab/resize, hide/show animation and video alignment.

### High clouds: a concrete visual gap

`CloudPattern` declares CIRRUS, but `CloudPattern.forCode()` never returns it.
`WeatherCloudMap` has special signals for fronts, storms and fog, but no separate
high-cloud signal. The current provider request includes only total cloud cover,
weather code, temperature and wind direction, so it does not establish cloud
layer heights. A future weather pass can consume appropriate high/middle/low
coverage data and author distinct wispy versus billowing morphology, retaining
the 8K density map and current draw budget. Provider availability must be checked
before implementation; do not infer cirrus from a generic partly-cloudy code.

## Scope and validation limits

The chair, Roadster, lunar maps and sky have just been upgraded; this review does
not propose reducing them. Existing poster caching, request coalescing, bounded
Earth-tile decoding, stationary placement caching and once-per-second Sun updates
are already implemented and were not counted again as new opportunities.

The cloud shell currently performs up to eleven cloud-map lookups. 9.58% of the
offline source texels have exactly zero alpha. This is only a candidate for an
early-out experiment: screen coverage and mip filtering differ from texel counts,
and fragment discard/derivative behavior can offset savings. It is not a promised
9.58% performance win and is lower priority than the changes above.
