# Selector opacity and side-menu pointer — 2026-10-17

The library and player rail retain Compose alpha for the empty movie grab carrier,
rounded edges and fade animation. Their native panels now disable glass and use
unlit colors. Separate opaque, non-hittable unit quads provide solid backgrounds
and write depth, preventing distant translucent scene layers from showing through.
The selector backing exists before any movie surface is created, follows resize,
movement and the inward fold, and hides for movie playback. The rail backing
follows the sliding menu and hides with it. These add at most two small quad draws;
no texture, video, Earth or model quality was reduced.

The side menu and video selector draw a white ring with a black outline and gold
center directly over their UI at the same normalized coordinates used for ray
selection. A second hand pointing at the movie cannot replace a valid side-menu
reticle. Reticles clear on tracking loss, hidden screens and closed menus. The
existing world-space cursor is biased toward its ray origin, so its visibility
does not depend on panel normal conventions. The movie's transparent grab carrier,
ISDK resize handles, native opaque video layer and subtitle presentation remain.

JDK 17 `testDebugUnitTest assembleDebug lintDebug` passes: **169 tests, zero
failures/errors; lint zero errors and 42 existing warnings**. Four added integration
tests cover actual SDK panel options, backing placement/size and movie/fold states,
ray-origin cursor placement, and rendered Compose selector/side-menu reticles.
The side-menu fixture was inspected with the ring over the Pause button. Existing
frame, resize, playback, weather and Earth checks pass. UI fixtures are desktop
rendering checks, not headset captures or hand-tracking proof.

The APK was replacement-installed on the connected Quest, preserving app data.
Its installed base APK matched this local build:

`f5ecdbcb951e7359744474a311290491d95ec0b0e1d5e2c0d9118ea1c691a1b7`

Native logs show both Quad scene objects created, the selector backing hidden
for movie playback, and the side backing shown. An account-free local H.264 test
clip decoded at 3840×2160 and rendered its first frame without a fatal exception.
The normal app was relaunched afterward; that process had not yet reported scene
readiness in the subsequent check, so the selector appearance remains a headset
check rather than a claimed live visual result. All 37 existing runtime assets match
the APK and are unchanged by this fix. No Plex credentials or sessions were reset.
Raw logs, test video, APK and UI captures remain local and ignored by Git.

The headset's actual opacity, reticle visibility with hands/controllers, and frame
manipulation still need visual/interaction review. This test verifies native setup
and video rendering; it does not substitute for those in-headset checks.
