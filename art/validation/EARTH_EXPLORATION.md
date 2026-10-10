# Earth exploration — 2026-10-09

The library's Earth menu adds geographic viewpoints and manual globe rotation.
Existing runtime geometry, textures, and shader detail are preserved.

## Controls

- **North Pole**, **South Pole**, **Equator**, and **Far side** select a viewpoint.
- **Orbit left/right** move longitude by 20 degrees per press; **Orbit north/south**
  move latitude by 15 degrees, clamped at each pole. A controller stick aimed at
  the Earth menu also steps longitude.
- **Spin left/right** turn Earth by 15 degrees in the visible screen direction.
- Point at exposed Earth, hold a pinch or controller trigger, and drag sideways
  to spin. Release keeps the rotation. A screen or nearer visitor takes priority.
- **Home seat** restores the original environment frame, viewpoint, and rotation.
- **Back to library** closes the menu without changing the selected view.

The library footer keeps the existing turn and return-model controls. Closing
the screen, resizing it, turning the view, and inspecting visitors remain available.
Navigation mode suspends Earth grabbing. Tracking loss or a view change cancels
a grab and requires a fresh press; its release cannot select a panel underneath.

## Coordinates and rendering

The canonical virtual observer stays at the original seat-to-Earth distance,
more than 50 metres above the surface. The authored north axis is local `+Y`.
A stable north tangent defines orientation at both poles. A rigid environment
transform maps this observer to the seated viewer while keeping Earth ahead;
the chair, screen, and physical floor remain upright. View changes are discrete.

Earth's three layers, planets, rings, Moon, Sun, and orbital visitors use the
same environment frame. Stars use its rotation. Parked inspection models remain
near the viewer; returns target the transformed orbital path. Quiet cinema still
pauses the visitor clock. Home restores the exact original frame.

Manual spin rotates Earth about its authored north axis without moving the Sun,
so daylight, city lights, clouds, and atmosphere remain aligned. Solar phases are
calculated in canonical coordinates before rebasing. The existing shader Z-axis
reflection and shared point-Sun lighting are preserved.

## Automated validation

Command:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

- Build, tests, and lint passed: **143 tests**, zero failures, errors, or skips.
- Ten added integration tests cover geographic observer frames and pole stability;
  viewer yaw/position anchoring; exact Home restoration; shared lighting and visitor
  bounds; pinch/trigger drag direction at both poles, equator, and far side; panel
  and visitor occlusion; tracking loss; release ownership; and actual Compose menu
  button bounds routed through the scene model.
- Lint reported zero errors/fatal issues and 42 existing warnings.
- All 34 runtime asset files matched the packaged APK byte for byte.
- APK v2 signature verification passed.

APK SHA-256:

```text
9a0448ab6ed554d66f286769e0d7bee1d93f95a19aac4d26cc0830f815f9609f
```

## Headset validation

The replacement APK was installed without clearing private app storage. The
installed APK hash matched the build above. The connected headset was asleep,
so this does not establish gesture behavior, visual appearance, or performance.

Remaining headset checks: polar and far-side appearance; spin direction and
comfort; hand/controller capture and release; stereo and translucent-shell
sorting; returning visitors from different viewpoints; playback and screen gestures;
frame timing and memory use.

Raw logs, device identifiers, credentials, and private app storage are excluded
from publication.
