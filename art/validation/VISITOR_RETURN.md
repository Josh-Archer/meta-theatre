# Throwing visitors back to their flybys

Validated on 2026-10-09 after the view-turning and inspection update.

## Behavior

While inspecting the ISS or Roadster, give the pinching hand or held controller
a short outward push and release the pinch/trigger while it is moving. A normal
stationary or slow release leaves the model nearby. Both inputs use the same
ownership and motion logic, so a toss release cannot also press the screen.

The model flies back to the moving orbital pose and original display scale over
1.2 seconds. **Return models**, at the bottom of the library, returns both with
the same animation. Returning during a gap between flybys or near the end of a
pass restarts that model at the middle of a visible pass. This fixes the previous
button behavior that could hide a returned model until its next scheduled pass.

Throw detection uses recent hand motion relative to the viewer. It requires at
least 9 cm of travel, an outward distance increase of 5 cm, and 0.6 m/s average
speed over recent samples. Tracking loss, stale samples, large tracking jumps,
navigation blocking, head translation alone and release before the initial pull
finishes do not throw. Returning models cannot be recaptured during their flight.
Quiet cinema freezes and hides the return flight along with normal flybys.

## Turning the perspective

Open the library and use **Turn left** / **Turn right** along its bottom edge.
With controllers, point away from the screen/player rail and flick a stick left
or right. Either option turns the perspective by 45°.

With hand tracking, curl the fingers with the thumb beside the index finger;
tap the thumb down once to enter the SDK's movement mode, then swipe the thumb
left or right. Open the hand to exit. These controls turn the perspective from
the chair; the chair mesh has not changed.

## Validation

`testDebugUnitTest assembleDebug lintDebug` succeeded with Java 17:

- 133 tests, zero failures, errors or skipped tests.
- Five additional replay integration tests cover outward throws for both real
  visitor paths and their release ownership; slow/inward/stale/lost/blocked/jump,
  head-motion and initial-approach releases; visible return after an ended or
  ending pass; the button's zero-velocity return flight and preservation of an
  active flyby phase; and pause/resume using the production quiet-mode clock.
- Lint: zero Error/Fatal findings, 42 warnings.
- All 34 runtime assets match the APK byte for byte. The Roadster, Starman,
  ISS and chair assets retain their previous geometry, textures and shaders.
- APK signature verification passed (v2).
- Replacement installation succeeded; the installed APK hash matches the build.

APK SHA-256:
`08e85c8e08a2268185273a3a3e6a601c9fc946b460478c16612a3ffe4837fbdf`

The headset reported asleep and no active app process after the launch request.
The user reported that the previous interaction update worked. The new throw
motion and its comfort/frame timing have not been physically tested this run.
On an awake Quest, inspect each model, give a roughly 10–20 cm outward push and
release promptly, and confirm it visibly resumes flying. Also check slow release
keeps it parked, **Return models** works after a scheduled pass ends, and the
three perspective-turning methods above remain usable.

Generated outputs, raw logs, device identifiers, credentials and private app
storage are excluded from publication. No private Plex storage was read.
