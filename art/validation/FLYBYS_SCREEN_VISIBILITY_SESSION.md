# Flybys, temporary screen hiding, and Plex sessions

The ISS and Roadster now cross the forward viewing area within 42 degrees of
the seat's forward direction, at 16–25 degrees elevation. The first passes start
at 3 and 12 seconds, run for 85 and 95 seconds, and repeat every 120 and 150
seconds. Models, textures, geometry, and movie quality settings are unchanged.

Two open palms moving together at the screen hide the movie or library.
Spreading both palms restores the screen without needing to aim at an invisible
target; a controller trigger also restores it. The movie, position, screen pose,
and size are retained. Movies pause while hidden, resume their previous intent,
and remain paused through an open system menu. Hiding also suspends panel grabs,
resizing, controls, captions, and cursor interactions. Quiet cinema's lighting
and flyby suppression end while the screen is hidden and return on restoration.

Plex sign-out now uses a separate confirmation view with Keep signed in and
Sign out choices. Ray presses use the rendered prompt's normalized button row.
Canceling a pending link does not clear saved credentials or watch history.
Client identity and token writes complete before authentication continues.
A token already absent from device storage requires linking Plex again.

## Verification

- `testDebugUnitTest assembleDebug lintDebug`: successful; 113 tests pass, with
  zero failures, errors, or skipped tests. Lint has zero Error/Fatal findings.
- Gesture tests cover restore without a ray target, tracking loss, one-handed
  motion, slow motion, and pinch conflicts.
- Media3 integration covers pending surfaces, buffering, menu/hidden overlap,
  retained movie position, and already paused playback.
- Compose/Robolectric integration verifies prompt rendering, actual ray-target
  coordinates, cancellation, confirmed sign-out, and durable synthetic sessions.
- The signed debug APK was installed with `adb install -r`; the device's installed
  APK checksum matches the build. Existing Plex preferences remained identical
  across the update. All 34 runtime asset files match the APK byte for byte.
- The headset was asleep after installation. A launch request alone does not
  establish rendering or physical gesture recognition; in-headset confirmation
  of both flybys, hide/restore, and real Plex linking/playback remains required.

Raw device logs and captures remain local and are excluded from Git.
