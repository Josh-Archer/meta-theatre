# Quest video and solar corrections

The user reported a black movie rectangle while audio and controls worked, an oversized or misplaced Sun, and light that did not match the visible Sun.

## Video evidence and correction

The original live process reported a 720×404 AVC frame and `video frame rendered`. This established decoder output, but the user still saw black. The first correction used a compositor underlay, a hole-punch mesh, and transform reapplication after asynchronous panel creation. The user reported that this version still displayed black.

The subsequent correction gives the movie a separate orientation from the UI meshes. OpenXR quads have a +Z front normal in native right-handed coordinates; this corresponds to -Z in the SDK API. The library and player meshes face API +Z. Sharing the library quaternion therefore pointed the movie layer away from the seated viewer. The movie now adds a local 180-degree yaw. Its hole-punch material is double-sided; the player controls retain their original facing direction. Grip movement and resizing still use the shared panel pose and dimensions.

The direct-to-surface compositor path and 3840×2160 surface remain. Track selection, native stream bitrate and textures were not downgraded. Decoder-first-frame logging does not by itself prove visible headset video, and actual UHD playback remains unverified.

References: [OpenXR quad composition](https://registry.khronos.org/OpenXR/specs/1.1-khr/html/xrspec.html#XrCompositionLayerQuad), [Meta layer rendering](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-2dpanel-layers/), [Meta media playback](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-media-playback/).

## Sun geometry and lighting

The old 9.8-km sphere at 80 km subtended approximately 14 degrees. The physical Sun light now uses one astronomical unit at the common mean-radius scale. A smaller visible proxy stays at 80 km on the same viewing ray and scales its radius by the same distance ratio, preserving an approximately 0.533-degree angular diameter without extending the scene far plane. Body radius ratios and the artistic civil-time cycle are retained.

Custom material uniforms bypass native API coordinate conversion. SDK 0.14.0 GraphicsHSR `setLightingEnvironment` reflects world Z before passing the light to its renderer; custom Vector4 attributes do not. Custom Sun uniforms now explicitly use shader world coordinates, and shaders transform that point using the actual imported mesh `objectFromWorld`. This incorporates glTF basis and node transforms instead of assuming the API entity quaternion describes raw shader mesh coordinates. Planet, Moon, Earth and chair lighting all derive from the same physical Sun; the visible proxy is collinear with it from the nominal seat.

The live Blender source was saved as a separate copy before editing. Only the seated preview Sun position, projection and scale drivers were changed, plus the stored preview script. The runtime GLBs and textures were preserved. Blender measured 0.532906 degrees for the updated preview Sun; this is an authoring check, not headset visual proof.

## Validation

- Correction build: 26 unit tests, zero failures, zero errors.
- Android lint: zero Error/Fatal issues.
- APK signature verified before the first correction installation.
- Both correction installations returned `Success`, preserving existing application data.
- The underlay-only installation matched the local APK SHA-256 and launched successfully. It attached the movie surface and rendered decoder frames; the user confirmed that its picture remained black.
- The facing correction has been installed and launch requested. Live picture and Sun appearance await a fresh headset check; see the latest `visual-fixes-install.json` for current captured evidence.

Build logs: `visual-fixes-build.log` and `visual-fixes-facing-build.log`. Source snapshots: `before-visual-fixes/`. Blender snapshot: `../blender/before_sun_alignment_20261004.blend`.

## Local UHD fixture — 2026-10-05

A 20-second 3840×2160, 24-fps H.264 test pattern was generated locally with FFmpeg. Its copied private-cache bytes were verified by SHA-256. The clip is outside runtime assets and is not included in the APK.

The debug app accepts `metatheatre.test_video=true` only when the Android application is debuggable. It reads the fixed filename `cache/video_surface_test.mp4`, then uses the same ScreenPlayer and registered compositor panel as normal movies. DefaultDataSource wraps the existing HTTP factory so local files work; network headers, HTTP behavior and track-selection settings are retained. No Plex credentials or media selection are needed for this test.

The diagnostic build passes all 26 tests and lint with zero errors, its APK signature verifies, installation returned Success, and installed/local APK SHA-256 values match. A launch with the local UHD fixture was requested. At the latest check the headset reported `mWakefulness=Asleep`, and the app process had not started. No UHD frame or visible test pattern is claimed yet. The user has been asked to wake/wear the headset; the fixture will start automatically when the app launches.

Original visual assets checked against the pre-performance manifest and all 15 solar runtime files checked against their staged source remain byte-identical. See `visual-fixes-assets.json`.

To return to normal Plex startup after testing, restart the activity without the debug extra. The fixture can then be removed from private cache. The debug entry point does not run in release builds or normal launches.
