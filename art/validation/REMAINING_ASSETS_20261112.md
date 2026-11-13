# Combined asset and efficiency validation — 2026-11-12

The `codex/remaining-assets` branch integrates the completed material, celestial,
close-Earth and efficiency passes onto `main` at `054cd1e`. It preserves the
newer Jellyfin support and cabin environment. The original development checkout
is untouched. Visual inspection by the wearer is deferred at the user's request;
the evidence below is automated validation, including native Quest execution.

## Included work

- ISS materials from PR #18; chair and Roadster materials; NASA lunar color and
  normal maps; planet lighting and the 8K Milky Way. Editable Blender sources,
  export scripts, attribution and matched previews accompany the runtime files.
  Earlier Blender/Unreal evidence is linked from `ASSET_WORKFLOW.md`. Unreal is
  an asset review sandbox, not this app's renderer.
- Issue #13: partition the existing 260,096-triangle close-Earth overlay without
  changing indexed positions, normals, UVs, texture resolution or edge blending.
  The branch includes its regional imagery/terrain foundation and panel backing.
  It does not use PR #22's different cap implementation against the older base
  sphere. The two implementations should not be combined.
- Issue #14 / PR #21: release 56 MiB of CPU cloud source arrays on explicit
  disable or memory pressure, while retaining them during regional fallback.
  Integration adds serialized builds, cancellation checks and bitmap recycling
  across coroutine cancellation. Timestamp-only refreshes reuse the uploaded
  texture; changed geography, weather layers, wind or coverage invalidate it.
- Issue #15 / PR #20: distinct high-cloud morphology using complete low/mid/high
  coverage fields from Open-Meteo, with the previous shapes as fallback. These
  fields are model output, not a satellite mask. See [weather notes](../WEATHER.md)
  and [provider documentation](https://open-meteo.com/en/docs).
- Issue #16 / PR #19: remove four unused runtime assets while preserving art
  sources. `hull.glb` is retained because the current cabin consumes it.
- Issue #17: initialize the library panel's unit `Scale` component, preserving
  the saved pose and dimensions and avoiding its missing-component fallback.

PRs #18–21 are included as commits, with integration fixes on top. Related
issues remain open for review and any outstanding wearer acceptance.

## Automated results

`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug lintDebug`
passed: **270 tests, zero failures, errors or skips; zero lint errors and 62
warnings**. Existing full-resolution weather checksums still match for the
equator, date line and pole. Cancellation, cache release/reload, layer fallback,
Earth footprint selection and saved screen behavior have regression coverage.

An independent ZIP audit matched all **164 runtime assets** byte-for-byte with
their source files and found no Git LFS pointer payloads. The removed entries
are `earth_day.glb`, `earth_night.glb`, `earth_twilight.glb` and
`weather/cloud_patterns.png`: **30,583,266 compressed bytes (29.17 MiB)** in the
baseline APK. This is attributable package storage, not a total before/after
APK or GPU-memory reduction. The cabin hull remains present.

Desktop EGL parity on RTX 5080 reran the actual detail shader in **112 matched
views**, including poles, date line, cell boundaries and oblique views:
maximum absolute pixel error **0**, at most **8,192 triangles / four draws**,
and exact indexed triangle attributes. This preserves all source geometry;
it establishes reduced submission, not measured Quest speedup.

## Installed Quest smoke tests

The debug APK is **226,883,711 bytes**. Local and installed `base.apk` SHA-256:

`af5413d22b8e64981847e1872bc7e4f279fec78ddda88544d479fb2c15e478d2`

The native `metatheatre.test_asset_lifecycle` diagnostic passed on Quest 3:

- The panel has `Scale`; an 8K synthetic high-cloud fixture uploads successfully.
- Source retention is 58,720,256 bytes (56 MiB) after decoding.
- Advancing only reading timestamps does not upload another cloud map.
- Regional fallback retains sources; returning to live weather avoids decoding.
- Explicit disable releases sources; reenable decodes again.
- `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` releases sources; a changed forecast
  can reload them. Final retained source bytes are zero.

Carrollton's native close-Earth diagnostic also passed: geographic recenter,
safe zoom, unchanged panel pose/size and unchanged view origin. Cached imagery
progressed through 512, 2048 and 4096 pixels, with mountain normals ready. The
selected footprint used one part / **2,048 triangles**. The logged 4K texture
upload took **98 ms**; loading hitches remain. No fatal exception appeared in
the captured diagnostic log. The diagnostics do not persist fixture settings.

After testing, the app was relaunched without diagnostic extras. Android
reported `TheatreActivity` resumed; Dynamic Earth loaded and the saved screen
pose and dimensions were restored. No fatal exception appeared in that log.

## Limits and follow-up inspection

The 8K weather output still allocates/uploads a 128 MiB RGBA bitmap when its
appearance changes. Source release is a logical retention measurement, not a
guarantee that process RSS immediately falls by 56 MiB. Lifecycle testing
includes intentional repeated uploads and is not a steady-state benchmark.

Wearer review remains for cloud readability, ISS materials, stereo appearance,
seams during Earth navigation, panel grabbing/resizing and movie playback.
There is no controlled FPS, thermal or total GPU-memory improvement claim.
The original visual fidelity budgets are retained; extra material textures
have their costs documented in the individual asset reports.

Local raw evidence is under the original checkout's ignored
`art/validation/remaining-20261112/`: `final-build.log`, `package.json`,
`installed.json`, `lifecycle-runtime.log`, `earth-runtime.log` and
`partition-parity.log`, plus `normal-launch.txt`, `normal-runtime.log` and
`normal-state.txt`. The reproducible desktop check is
`art/scripts/check_earth_partitions.py`; debug launch extras are implemented in
`TheatreActivity.kt`.
