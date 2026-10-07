# Performance pass — October 4, 2026

This pass removes repeated CPU work and bounds poster-cache memory while retaining
the current geometry, texture resolution, poster decode sizing, video resolution,
and per-frame controller input. It does not introduce reduced-quality rendering.

| Path | Before | After |
| --- | --- | --- |
| Earth lighting | Civil-time conversion, solar calculation, and three material uploads every frame | One update per second, plus immediate session-state refresh |
| Video/player overlays | Four ECS transform/scale writes every frame | Writes only after panel movement, resize, watching change, or overlay replacement |
| Cursor visibility | A visibility component write every frame | Writes on visibility transitions; pointer positioning still runs every frame |
| Library UI | Observes playback position at 4 Hz and remaps the entire shelf | Observes movie availability changes; shelf tiles remembered per browse state |
| Shelf input | Maps every library item just to obtain its count | Reads list size without creating tiles |
| Playback ticker | Wakes every 250 ms even while idle | Runs only while the player is playing; event callbacks still publish state changes |
| Poster cache | 48 entries regardless of decoded allocation size | 16 MiB of decoded bitmap allocations in the cache, with at most three concurrent fetch/decode jobs |
| Browse lifecycle | Independent coroutine scope survives activity destruction | Cancels outstanding browse/link jobs and clears activity callbacks |

At 72 Hz, ordinary Earth material updates fall from 216 per second to three.
Stationary overlays skip 288 ECS writes per second once synchronized. These are
operation counts from the code, not measured frame-time or FPS gains. The cache
budget excludes active Compose image references, GPU allocations, and transient
decode/download memory. Cache eviction never recycles bitmaps still used by the UI.

## Quality and concurrent workspace edits

All 21 original model/image asset files and drawable files retain their pre-pass
SHA-256 hashes. This pass did not edit shaders or reduce any rendering resolution.
Earth's once-per-second civil-time refresh changes its solar angle by approximately
0.0042 degrees per update and still resamples wall time after session transitions.

Another edit added a solar environment during this pass and changed four existing
Earth shader files. Those edits were preserved, including the new `sunPosition`
material parameter. The overall current app therefore differs visually from the
saved baseline for reasons outside this performance pass. A valid performance
comparison must use the same completed environment in both builds.

The runtime chair has 979,090 triangles and occupies 45,347,440 bytes. It was inspected
through Blender MCP, then the temporary imported inspection object was removed.
The chair GLB was not changed. Its size is a reason to measure GPU cost; it is not
proof that the chair is the frame-time bottleneck.

## Validation

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug lintDebug`: successful.
- All 14 tests in the current workspace passed: six Earth-lighting tests, two new
  cadence tests, and six solar-system tests supplied by the concurrent scene work.
- Cadence regression coverage includes 72, 80, 90, and 120 Hz plus forced refresh
  after session changes and a long suspension.
- Lint reports zero errors and 25 warnings, primarily dependency/version and
  unused-resource advice. The project currently configures lint not to abort on errors;
  the XML report was inspected directly for the zero-error result.
- ADB and Linux USB inventory showed no Quest at the time of validation. No APK was
  installed, and no headset FPS, CPU/GPU time, memory, playback, or visual comparison
  has been recorded by this pass.

## Artifacts and device comparison

- Current APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Pre-pass APK and source snapshots: `art/validation/performance-before/`.
- Original visual hashes: `art/validation/performance-before/visual-assets-sha256.json`.
- Asset counts, hash comparison, and validation summary: `art/validation/performance-assets.json`.
- Build log: `art/validation/performance-build.log`.
- Blender scene snapshot before inspection: `art/blender/before_performance_20261004.blend`.

Once USB debugging is available, compare builds with identical scene assets, headset
refresh rate, display resolution, gaze direction, and thermal conditions. Measure
both browsing and the same direct-play video after warm-up. Compare application
CPU/GPU frame times, dropped frames, and process memory, then check panel dragging,
resizing, shelf selection, menu pause/resume, and headset wake. The original pre-pass
APK is useful for rollback, but includes the older environment and should not be
used as an isolated performance baseline against the new solar scene.

Avoid restoring whole source snapshots over the concurrent scene changes; apply
only the performance-related differences if reverting this work.

The library state reduction follows Android's guidance to avoid repeated calculations
and to observe state at the frequency needed by the UI:
[Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices).
