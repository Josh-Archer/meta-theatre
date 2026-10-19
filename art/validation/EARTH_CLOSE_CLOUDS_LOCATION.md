# Above me input and close cloud detail

Validated 2026-10-18. This follows EARTH_LOCATION_ZOOM_ROTATION.md.

## Changes

- Above me now accepts native pointer clicks as well as the app's hand ray.
  The former button exposed an accessibility action but did not handle pointer
  taps. Recenter is idempotent; existing toggle input routing stays unchanged.
- Explicit Above me input enables the location session even after a diagnostic
  launch disabled automatic lookup. Waiting for immersive focus and cancellation
  by orbit controls now have visible messages. Zoom no longer cancels a pending
  geographic lookup.
- Above me requires a fresh approximate Wi-Fi fix: elapsed age at most 30
  seconds, with ten seconds of future-clock tolerance. It always requests network
  updates and has no last-known fallback. Old/unusable updates are ignored while
  waiting up to 45 seconds. Success, backgrounding, disabling and timeout remove
  the subscription. Timeout gives a visible message and leaves Earth in place.
  No coordinates are logged or automatically written to storage.
- Cloud optical depth retains the original 8192×4096 source, replacing the
  downsampled 4096×2048 output. Live weather retains that 8K density too; its
  separate 4K morphology controls remain at their authored resolution. Building
  rows directly avoids an additional full 128 MiB RGBA pixel array.
- Native cloud lighting combines fine and broad density slopes with a sampling
  footprint that follows screen derivatives, retaining relief across mip levels.
  The three Earth meshes, day/night maps, terrain maps and video quality remain
  unchanged. 8K cloud maps consume more texture memory than the prior 4K maps.

## Automated and source checks

- `testDebugUnitTest assembleDebug lintDebug`: 198 tests, zero failures/errors;
  zero lint errors, 43 warnings. The added pointer-click test reproduced the
  missing callback before the button fix. Location tests cover stale-cache
  updates, late callbacks/cancellation, visible focus-waiting feedback, strict
  freshness, no movement on stale-only timeout, permissions and accuracy limits.
- Native 8K alpha byte-matches the attributed source after the documented optical
  depth conversion; no enlargement of the former 4K cloud output was used.
- All 37 packaged runtime assets byte-match the APK.
- Blender MCP inspected the existing source and produced the separate editable
  `art/blender/earth_close_clouds.blend` and a 1200×1200 close reference render.
  The render was visually inspected. It is a Cycles lighting reference, not a
  native shader screenshot or proof of headset appearance. Runtime geometry was
  not exported or replaced. The interactive bridge was unavailable, so the MCP
  background Blender tool was used with the existing project source.
- New Blender source has relative asset paths. Render metadata was removed
  without changing pixels. Raw headset logs, preference hashes, captures and
  APKs remain local and ignored by Git.

## Headset checks

The preserving update retained the existing Plex and theatre-settings files.
The real-device Above me diagnostic exercised the button's model callback with
existing user opt-in and permission. On the final installed build, the network
provider delivered a fresh fix within the 30-second age limit, the native
geographic centering diagnostic passed, and the success message was published.
The stale-only timeout/no-movement path is covered by the Android-source/session
integration test. Native location output contains only fixed status categories,
not coordinates or provider payloads. Future fix availability still depends on
the headset's Wi-Fi location service.

A native diagnostic passed with a synthetic region and synthetic storm readings
at maximum safe orbital zoom, exercising the 8K weather texture without a live
weather dependency. The local 3840×2160 fixture rendered its first frame. In a
short run, app-process counters showed about 90 FPS at a 90 Hz target, zero
stale/tear counts, and approximately 1.45 GiB total PSS. The ADB capture did not
frame Earth usefully, so these samples do not prove sustained performance while
looking closely at its clouds. The normal app is relaunched afterward. Perceived cloud
relief, stereo comfort, extended thermals, and current Plex media playback still
need an in-headset check; a successful build or Blender render does not prove
those properties.

## References

- [Meta Location API](https://developers.meta.com/vr/documentation/native/android/move-location/): foreground network location, cached fixes and supported update subscriptions.
- Source texture attribution remains in `app/src/main/assets/earth_credits.txt`.

Final APK SHA-256: `9fc363fbb14ad09a91984c604ef387d5142113744382111f653cb4ee491caed0`. The installed APK hash matches this build.
