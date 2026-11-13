# Earth zoom, clear mode and Horizon panel update — 2026-10-21

Current integration and automated Quest results are recorded in
[REMAINING_ASSETS_20261112.md](REMAINING_ASSETS_20261112.md).
The dated results below describe the original pass.

The app uses `com.meta.spatial:meta-spatial-sdk-uiset:0.14.0`, matching the
existing Spatial SDK. Library menus and player controls are wrapped in the
actual dark `SpatialTheme`, using its panel gradient and `shapes.large` token.
Square gold outer borders are replaced by subtle rounded white outlines.
The empty movie grab carrier keeps its alpha channel and existing resize/move
hit areas. The depth backing now clips to the same UI Set corner token in a
small opaque shader, so square black backing corners cannot leak outside the
rounded Compose panel. Compositor video resolution and the Plex playback path
are unchanged.

Official references checked:
- https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-ui-overview/
- https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-panel-tutorial/
- https://nasa-gibs.github.io/gibs-api-docs/python-usage/
- https://science.nasa.gov/earth/earth-observatory/blue-marble-next-generation/base-map/

Automated fixtures and source comparison are saved locally under the ignored
`art/validation/earth-detail-horizon/` folder. They include real NASA imagery
for an 18-degree public region around Georgia and a same-area source comparison
near Carrollton. This is a map-data comparison, not an in-headset screenshot.
UI fixtures use synthetic media and location data. The full suite initially
passed 203 tests and lint reported zero errors. Native GPU validation and final
APK identity are recorded below after final checks.

Final validation: **204 tests passed**, no test failures/errors, lint
zero errors (46 existing/reported warnings). `testDebugUnitTest assembleDebug
lintDebug` completed successfully. Native Quest 3 diagnostic exercised the
maximum safe Earth zoom, clear cloud mode and the actual NASA-region upload.
The loader applied the atlas successfully; no shader binding/pipeline or app
crash errors were found. Diagnostic app PSS was 1,703,521 KiB (about 1.62 GiB),
with 1,313,770 KiB graphics accounting. This is a settled diagnostic snapshot,
not a movie-playback peak or a perceived visual-quality assessment.

Final APK SHA-256: `178d15d0388d6584458c53b7f6a41f5fb282d9aad37df3d9e3e07e2be044c9bf`.
Installed base APK hash matched the build. The app was restarted normally after
the diagnostic; test camera/clear settings were never persisted.

The native check caught an incorrect normal-map asset extension in the new
loader, corrected before this final APK. A new complete session test loads the
packaged normal asset through atlas generation/upload and checks that background
views never fetch. This supplements network/cache and pixel/gutter tests.
