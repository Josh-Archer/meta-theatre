# Performance pass — 22 October 2026

This pass changes four runtime Kotlin files, adds seven regression tests, and preserves the existing scene work. No models, textures, shaders, poster decode sizing, or video settings were changed.

## Changes and evidence

- **Cloud-map loading:** recycle the 8192 × 4096 RGBA source before decoding the structure map. The large live buffers previously overlapped at approximately 216 MiB (128 + 32 MiB decoded bitmaps, 32 MiB source alpha, 24 MiB structure channels). The new peak is approximately 160 MiB, a **56 MiB reduction in these buffers**, excluding decoder overhead, the rest of the app, and allocator retention. This is an allocation-size calculation, not a measured headset RSS reduction. Failure during the second decode also no longer leaves the first bitmap unrecycled.
- **Cloud-map generation:** skip weather interpolation on rows outside the model footprint, retaining the original optical depth. Full 8192 × 4096 output checksums match the pre-change implementation: equator `3068430721`, date line `1611490886`, north pole `538384725` (CRC32 of ARGB integers in big-endian order). Native graphics tests also retain storm/fog separation, wind response, source resolution, and far-side coverage.
- **Posters:** serialize work for the same URL before acquiring a download slot. Twelve simultaneous requests share one HTTP download and the same decoded image. Distinct URLs retain three concurrent download slots and the existing 16 MiB cache. Tests cover cancellation of a waiting request and retry after a failed response. URL gates are removed after the last caller exits.
- **Pointer targeting:** replace boxed float lists used to validate every pose with direct finite checks, avoid intermediate target/hit lists when selecting the nearest visitor, and reuse the ray-intersection square root. Tests cover invalid tracking components, target ordering, screen occlusion, capture, and rays starting inside a model; existing interaction replay tests pass.

The small desktop timing sample for cloud generation was mixed (before: 389/319/311 ms; after: 423/302/298 ms for equator/date line/pole). It does **not** establish a latency improvement. The demonstrated benefits are reduced live buffer overlap, shared downloads/decodes, fewer unnecessary calculations/allocations, and preserved output.

## Validation

`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain`

- Validated in an isolated worktree based on `main` at `bb9cb0f`, including the environment-selection feature.
- 210 tests passed; zero failures, errors, or skipped tests.
- Debug APK built at `app/build/outputs/apk/debug/app-debug.apk`.
- Lint: zero errors, 45 warnings, including a `UseKtx` suggestion on the unchanged bitmap creation line in `WeatherCloudMap.kt`.
- `git diff --check` passed.

ADB detected a Quest 3, but it remained asleep and `casa.archer.metatheatre` had no running process. This pass did not install or launch the APK. FPS, CPU/GPU frame times, thermals, resident memory, and awake-headset visual quality remain unmeasured.
