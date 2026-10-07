# Side player and hand input — 2026-10-05

The movie controls now occupy a vertical rail to the viewer's right, separated
from the picture by 10 cm. The rail stays 0.56×1.64 m as the movie grows, and
aligns with the movie's lower edge. Its pose follows the movie's translation and
rotation. The video keeps its corrected compositor facing and 3840×2160 surface.

The Compose UI uses a rounded charcoal gradient, a warm gold accent, grouped
playback/screen-size/quality sections, and prominent Play/Pause and Larger buttons.
Its native panel is 560×1640 pixels at 160 dpi. Playing/Source dimensions and
Original/4K/1080p controls are retained. Physical size and video quality remain
independent. Scene assets and movie encoding/quality settings were not edited.

After five seconds without side-panel interaction, controls slide 20 cm outward
and fade over 280 ms, then the rendered entity becomes invisible. A held selection
or slider gesture prevents hiding until release. Time alone never reveals hidden
controls. Pointer movement over the side panel's area reveals it; stationary hover
does not reset the timer. Motion uses tracked source position/orientation rather
than the moving panel's hit point, preventing the hide animation from revealing
itself. Ten millimetres/one degree of motion filters tracking jitter.

An unrendered ISDK panel covers the rail's normal location and remains interactive
while the rail is hidden. Its priority is lower and it is behind the visible rail,
so the visible buttons receive selection. It has no Android panel, mesh or pixels;
its purpose is to deliver hover/selection to the visibility controller. It is
disabled in library mode. Source-only fallback can reveal it on a deliberate
selection in that side area as well.

VRFeature explicitly uses INTERACTION_SDK. Existing hand permissions and V2.0
metadata are retained. The player now has native panel collision and receives
Meta's shared controller-ray, hand-ray/pinch and touch input rather than a manual
five-column button mapper. The standard Compose Slider handles seeking. SDK
global pointer events control the inactivity/held-gesture state; observers are
unregistered on activity destruction. Pointer reach is 15 m to cover screens
moved to the existing 12 m limit.

Custom controller grip movement is retained for the movie. Raw hand button states
are excluded from that path so a pinch on the player cannot accidentally grab the
movie. Library movie cards and paging zones now route hand release/select events
through their existing screen-coordinate mapper; native header and link buttons
retain their existing Android click handlers. Library hints mention pinching.
This does not add a hand gesture for moving the entire movie screen.

During a rail animation, only rail state changes; its animation does not repeatedly
write the movie's compositor transform/scale. After hiding, the Compose content
stops collecting playback ticks. Existing still-scene update gates remain intact.

## Verified

- `testDebugUnitTest assembleDebug lintDebug` succeeded.
- 36 tests passed; lint XML has zero Error/Fatal entries.
- New tests cover the five-second deadline, no timed reappearance, interaction
  reset, held pinch/release, unrelated releases, two simultaneous hands, library
  transition cleanup, and rail separation/alignment across screen-size limits.
- `apksigner verify` succeeded. The APK hash is in `side-player-validation.json`.
- The APK was installed on Quest 3 and its installed hash matches. Both public
  hand tracking permissions report `granted=true` in package state.
- The desktop illustration `side-player-layout-preview.png` was generated and
  visually inspected. It demonstrates layout/colors using a synthetic picture
  and placeholder playback values. It is not an Android render or headset capture.

## Still required on Quest

The Quest reconnected after build validation. The APK was installed with
`adb install -r`, and the installed SHA-256 matches the build. A normal app launch
was requested. The headset reports Asleep at the latest check. Native pointer
targeting, the invisible reveal surface, fade/slide appearance, hand pinch/touch,
library hand selection, and controller compatibility still require awake
headset validation.

On headset wake, open TheatreActivity. Check native controller buttons first, then put
controllers down and verify hand-ray pinch for Larger/Smaller, quality, seeking and
library/movie selection. Wait five seconds with the pointer stationary, confirm
hiding, and move toward the right edge to confirm reveal. Keep a pinch held longer
than five seconds and verify no hiding until five seconds after release. Check
max-size and moved/rotated screens, rounded-edge alpha, video visibility, and CPU/GPU
frame timing. The local UHD fixture remains available separately from the APK.

Source backups: `before-side-player/`. Build log: `side-player-build.log`.

## References

- [Meta Interaction SDK overview](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-isdk-overview/)
  documents shared hand/controller pointer input and VRFeature's default.
- [ISDK panels](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-isdk-panels/)
  documents Transform/IsdkPanelDimensions surfaces, including custom panels.
- [ISDK input events](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-isdk-listen-to-input-events/)
  documents semantic pointer events and selection/scroll handling.
- The installed 0.14.0 SDK API/bytecode was inspected for observer lifecycle,
  collision priority, quaternion angle comparison, and panel configuration APIs.

## Follow-up headset feedback

The user confirmed the sidebar appearance improved, but reveal and hand motions failed. This supersedes the initial unverified interaction status. The input correction and new pinch/thumbstick controls are documented in [GESTURE_CONTROLS.md](GESTURE_CONTROLS.md).
