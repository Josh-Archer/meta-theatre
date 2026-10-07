# Sidebar reveal and movie gestures

Updated October 5, 2026 after headset feedback that the sidebar looked better but did not reveal again and hand selection did not work.

The input loop now polls local, active HAND and CONTROLLER entities. Spatial SDK 0.14 maps index pinches to A/X (1/4); controller triggers remain 16/32. The old loop explicitly skipped HAND input. See [Meta's input documentation](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-inputs-controllers/) and the installed 0.14 ButtonBits bytecode.

Sidebar reveal now intersects the tracked pointer ray with the rail's world-space rectangle, including its slide-out position. It runs while the rendered panel is hidden and does not depend on the removed blank reveal entity. Pointer entry or movement of at least 1.5 cm on that region restarts the five-second inactivity deadline. A stationary pointer does not automatically reveal again. A held rail selection delays hiding; release or tracking loss clears the hold.

The rendered Compose buttons publish their actual normalized bounds to the ray router. Trigger/pinch press captures a target; release over that same button invokes it once. The timeline previews while held and commits on release. The rail buttons retain accessibility actions; native pointer clicks cannot duplicate the custom ray selection.

## Controls

- Point a controller or hand toward the right side of the video to bring back the rail. Move away and stop interacting for five seconds to hide it.
- Point at a sidebar button, pinch/press, then release to select it.
- Point both hands at the movie and pinch both within half a second. Move your hands apart/together to grow/shrink the screen, then release. Resizing preserves the current aspect ratio and clamps the width to 1.2–8 metres. Updates are capped at 30 Hz with a 1 cm width deadband. The initial size/span anchor avoids compounding the scale every frame.
- Point at the movie, pinch one hand and move it sideways at least 12 cm: right seeks forward 10 seconds; left seeks back 10 seconds. Each pinch seeks once. Release and pinch again to repeat. Predominantly vertical/depth motion does not seek.
- A short stationary pinch on the movie toggles playback. A resize or swipe consumes its pinch so release does not also toggle playback.
- Either controller's horizontal thumbstick seeks ±10 seconds immediately. Holding it repeats after 750 ms, then every 500 ms. Centering it stops repetition. Grip-to-move still uses the stick's vertical direction for depth and suppresses horizontal seeking while gripping.
- Losing hand tracking cancels the gesture. A hand reacquired while already pinching must open before starting another gesture.

The library's existing coordinate router now accepts hand pinches too. Header pointer clicks use the same router, avoiding duplicate navigation.

Original, 4K, and 1080p playback routing and the video surface are unchanged by this input update. Screen resizing changes physical display size, not source resolution or encoding quality. Environment assets are unchanged.

## Validation

- `testDebugUnitTest assembleDebug lintDebug` passed: 47 JVM tests; zero lint Error/Fatal issues.
- New tests cover hidden-rail reentry/movement, stationary-pointer hiding, hand loss/reacquisition, gesture conflict suppression, swipe direction and dominance, anchored aspect-preserving resize/bounds, measured button target routing, timeline clamping, and bounded stick repetition after delayed frames.
- APK signature verified. `adb install -r` succeeded on Quest 3 `[device identifier omitted]`; app data was retained.
- Installed APK SHA256 matches local build: `b35d83613979aa09b888f7f4d081dc029efe282ac2c6f3f8b39e779b99b9571a`.
- Normal launch requested without the local-video fixture extra.
- Live device markers now show two tracked hands, pinch down/up transitions, library selections, a hand rail selection invoking UHD, and hiding followed by revealing again. These establish actual input delivery and application actions. Later live markers show two-hand resize start/end, short-pinch playback toggles, and forward/back hand swipe seeks followed by rendered video frames. Visual/ergonomic confirmation and controller thumbstick seeking remain pending headset feedback. Markers establish application actions, not the user's visual assessment.

Sanitized device markers are in `gesture-fix-device-markers.txt`. Logging is limited to device counts, pinch transitions, gesture/action names, panel startup, and numeric seek direction; playback URLs and authentication values are excluded.

## Resize persistence correction

The user reported that the first resize gesture did not visibly resize the movie. Gesture start/end markers alone did not establish successful resizing.

Inspection of the installed 0.14 SDK's `IsdkComponentExtensionsKt.updateIsdkComponentProperties` shows that panel `IsdkPanelDimensions` is derived from `PanelSceneObject.panelShapeConfig` and overwritten when it differs. The first implementation wrote the dimensions component directly, which was undone on the next SDK refresh.

The corrected implementation stores size in the parent panel's `Scale` and computes physical width/height as base shape dimensions multiplied by scale. `ScreenGripSystem.screenSize()` is shared by video sizing, rail placement, ray intersections, pinch anchors, and Larger/Smaller actions. This follows the SDK's [Simple resize semantics](https://developers.meta.com/vr/reference/spatial-sdk/v0.14.0/com_meta_spatial_isdk_resizemode/). The video panel retains its UHD pixel allocation and its own proportional scale; library scaling survives switching views. Lower-edge positioning uses the previous physical height so it moves once per size change.

The new regression check simulates repeated SDK refreshes of the same base dimensions and verifies that physical size and rail placement retain the desired scale. Numeric resize start/applied/end width markers support the next live test. The corrected APK is installed and matches the hash above; physical resize confirmation remains pending.
