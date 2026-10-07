# Hand locomotion

The app enables Meta Spatial SDK 0.14's standard hand microgesture system.
The SDK recognizes the curled-finger pose, thumb taps and swipes; the app does
not estimate these gestures from custom finger-distance thresholds.

## Behavior

- Either hand: tap to enter, aim at a pad and tap again to teleport.
- Left/right thumb swipe: 45 degree snap turn.
- Forward/back thumb swipe: 0.5 metre step along the horizontal head direction.
- Open the active hand: cancel. Tap with the other hand: switch hands.
- Six 1.4 metre square SDK plane primitives are valid teleport surfaces, with
  `MeshCollision.LineTest` and `SupportsLocomotion`. Their tops are at floor
  height. They are visible only during hand navigation. They require no textures
  or new GLB assets and total 72 triangles (six SDK boxes).
- Hands cannot simultaneously navigate and operate custom movie controls,
  pinch resize, or open-palm screen gestures. The SDK similarly gates ISDK
  interaction while navigating. Controller browse/seek bindings remain intact.
- Losing immersive focus or pausing disables navigation and clears its active
  hand. Focus restoration enables it again; a new tap is required.
- Open-palm joints are aligned to scene space through the predicted OpenXR
  head pose and the SDK viewer pose. This includes turns, teleports and physical
  recenter offsets; palm normals receive rotation only.

These pads are destinations inside Earth orbit, not a scene-selection feature.
Only marked pads accept teleports; SDK forward/back steps are not collision
constrained in this scene.

## Automated checks

Run with JDK 17:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

`HandTrackingSpaceTest` checks translation without normal translation, a 90 degree
turn, and head alignment with a physical offset and head rotation. Existing
movie/open-hand gesture tests cover taps, seek, resize, screen movement and clap.
These checks establish source, math and APK validity, not gesture recognition
or comfort on a device.

The validation run passed all 71 unit tests (including the three new tracking
space tests), built `app-debug.apk` with the updated ARM64 hand reader, and
completed Android lint. `apksigner verify` also passed for the debug APK.

## Quest checks still required

1. Enter with each hand separately; confirm pads and SDK arc/reticle appear.
2. Teleport to the side/back pads and return to the chair. Verify viewer floor
   height, reticle validity, scene scale and screen orientation.
3. Swipe in both directions; verify one 45 degree turn per gesture and no movie
   seeking. Test forward/back 0.5 metre steps and switching active hands.
4. Open the hand and verify pads disappear. Resume pinch taps, resize, open-hand
   screen movement and clap after teleporting and turning.
5. Aim at the movie/player/library while navigating; verify there are no stray
   clicks, seeks, resizes, grabs or open-hand actions.
6. Lose hand tracking, open the system menu and sleep/wake. Require a fresh tap
   with no stale teleport on restoration.
7. Measure frame timing while pads and arcs are visible; inspect both eyes and
   confirm the targets do not obstruct normal movie viewing.

No Quest was connected through ADB when this change was implemented. Installation,
physical gesture recognition, runtime frame timing and comfort remain unverified.

## Meta references

- [HandMicrogestureLocomotionSystem](https://developers.meta.com/vr/reference/spatial-sdk/v0.14.0/com_meta_spatial_vr_handmicrogesturelocomotionsystem/)
- [Locomotion input mappings](https://developers.meta.com/vr/design/locomotion-input-maps/)
