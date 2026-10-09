# View turning, visitor inspection and screen folding

Validated on 2026-10-09. Editable Blender sources and all existing runtime assets
remain intact, including the open-roof Roadster and visible Starman.

## Controls

- Library footer **Turn left** / **Turn right** rotates the perspective by 45°.
  A controller stick does the same when pointing away from the screen/player
  rail or when the screen is hidden. Existing SDK hand navigation remains available.
- Point at the ISS or Roadster until the gold cursor appears. Hold a pinch or
  controller trigger and pull back at least 6 cm. The model approaches as a
  miniature with a 1.1 m bounding diameter, initially centred 1.5 m from the viewer.
  Keep holding to move it and rotate the wrist to turn it. Release keeps it in
  place; **Return models** restores the normal flybys.
- With open palms facing each other in front of the screen, move both from
  roughly 40–50 cm apart to 20 cm apart to hide it. Physical contact is unnecessary.
  Spread both palms from close together to restore it, or press a controller
  trigger. The screen folds horizontally over 240 ms and restores in the
  current view while keeping the user's size.

## Regression fixes

The previous clap required both SDK hand rays to intersect the screen while
the palms faced each other. Native palm positions now frame the pair independently
of those rays. Hidden-screen restoration also reads native palms when the SDK
has no active hand-controller entity. The thumb can relax during an open-hand
clap, and the close fires before touching palms can occlude tracking.

An armed two-hand close owns its stroke, preventing an individual screen swipe
from moving the screen during the first part of the clap. Model capture owns
its release too, preventing a model inspection from selecting a title. Hidden
and folding screens reject custom presses; presentation width does not become
the saved screen width.

## Automated evidence

`testDebugUnitTest assembleDebug lintDebug` succeeded with Java 17:

- 128 tests, zero failures, errors or skipped tests.
- Nine new replay/Compose integration tests cover both real flyby paths,
  capture/pull/wrist rotation/release, screen occlusion, tracking loss,
  navigation/quiet-mode blocking, early release and recapture, palm projection
  after a turn/recenter without SDK ray targets, close/open presentation,
  restored placement with vertical-gaze fallback, continuous animation reversal,
  and the rendered footer's actual normalized hit regions. An additional unit
  regression covers a one-second clap with a relaxed thumb.
- Lint: zero Error/Fatal findings, 42 warnings.
- All 34 runtime asset files match the APK byte for byte.
- APK signature verification passed (v2).

APK SHA-256:
`ef427bd247b32b894668d9f6c17233d704f5a7fe8d2cb5f8380aa4eb27400be2`

## Headset evidence and remaining checks

Replacement installation succeeded and the installed APK hash matches the build.
The connected headset reported asleep; the launch request did not leave an active
app process. No physical hand/controller interaction, view-turn comfort, model
appearance at inspection scale, or headset frame timing was observed this run.

On an awake Quest, check turning while seated, pulling/turning each model and
returning it, clapping and spreading with relaxed thumbs, hiding/restoring after
a view turn, and two-pinch screen resizing after reopening. Verify library/movie
width persists, an already paused movie remains paused, quiet cinema restores
inspected models, and pointing at a visible screen still selects/seeks normally.

Raw logs and headset identifiers remain excluded from publication. No Plex
credentials or private app storage were read or included.
