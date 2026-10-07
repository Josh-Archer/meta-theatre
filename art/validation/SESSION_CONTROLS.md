# Saved screen size, watch history and open-hand controls

Updated October 5, 2026. The preceding pinch resize correction was confirmed working by the user on the headset.

## Result

- Physical screen width and height are saved in device-local `theatre_state` preferences after resizing settles, at the end of a pinch resize, and during pause/destruction. Restoration uses the working parent Scale path; SDK-owned panel dimensions remain the base geometry.
- Each movie/episode has a bookmark keyed by stable Plex server identity and rating key. Progress checkpoints are saved every five seconds during ready playback, immediately on leaving the video, and during app/session pause. Completed videos start from the beginning.
- Relaunch automatically resumes the last active unfinished video. Deliberately returning to Recently Watched clears the automatic resume marker and keeps the individual bookmark.
- Recently Watched is the home menu, ordered by most recent playback start. Initial history includes recent Plex movies/episodes. Playback and poster URLs are reconstructed from fresh server connections; the new preference store contains no Plex credentials or playback URLs.
- Library items also prioritize recently played entries. Libraries remain accessible from the home header.
- A fully open hand aimed at the screen can swipe left, right, up or down. A deliberate stroke of at least 16 cm moves the screen 35 cm in the same direction, with a short smooth transition. Stop briefly before another stroke. Finger extension, direction dominance and tracking validity are checked.
- With a movie open, bring two open palms toward each other like a gentle clap, from roughly 30 cm or more apart to within 14 cm. Both hands must move inward and palms must face each other. This saves the bookmark, stops/clears active media, and returns to Recently Watched. Physical contact is unnecessary.
- Existing two-hand pinch resize, pinch-and-swipe seeking, rail controls, controller movement/seek and five-second rail hiding are retained. Open gestures are suppressed during pinches/controller grips and briefly after release.

## Implementation and compatibility

Hand joints are read through a small arm64 JNI library using the SDK-owned OpenXR instance/session. It creates and destroys only its own hand trackers/reference space, and uses SpatialInterface's predicted OpenXR frame time. It does not own the renderer frame loop, instance or session, and adds no body-tracking permission. Invalid or lost tracking produces no open-hand action. Tracking data is neither stored nor logged; diagnostics expose counts and action names only.

The existing Original/4K/1080p routes, UHD video surface, hole punching, bitrate settings, scene assets and materials were preserved. New hand sampling is capped near 60 Hz. This has not yet been measured in a live performance capture.

Primary API references:
- [Meta frame timing callbacks](https://developers.meta.com/vr/reference/spatial-sdk/v0.13.2/com_meta_spatial_runtime_framephasecontroller/): SDK OpenXR predicted-display timing snapshot.
- [OpenXR hand-joint locations](https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrHandJointLocationsEXT.html): joint validity/activity behavior.
- [Plex Media Server API](https://developer.plex.tv/pms/): recent sort and view-offset metadata.
- Vendored Khronos headers and license are in `app/src/main/cpp/vendor/openxr`, pinned to commit f2448a8797c85814aa892efc1ab8707900fbcc78.

## Verification

`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain` passed. All 63 JVM tests passed, including 16 new watch-history/open-hand tests. Lint has zero Error/Fatal findings. The APK signature verifies, contains the arm64 native reader, and the installed base APK checksum matches the local artifact.

Installed APK SHA-256: `8f44550079294949567ea09bcc607399f266964eead52b54bed26533f57d9534`.

The USB-connected Quest accepted the in-place update and launch request. At the last check it was not running the app's awake VR session: no process, scene, hand-joint or initialized new preference markers were available. Consequently, real open-palm recognition, the four movement directions, the clap, size restoration after a process restart and movie resume are **not yet confirmed on the headset**. The user has been asked to wear it and test. Unit tests are bounded proof of the state/geometry rules, not physical gesture proof.

Safe current evidence: `session-controls-validation.json` and `session-controls-device-markers.txt`. `check-session-controls.py` refreshes installed checksum, test/lint counts, allowlisted runtime markers and numeric/count-only summaries from the new preference store. It never prints Plex preferences, titles, server identities, tokens or URLs.

## Headset check

1. Open a movie, resize with the working two-hand pinch, then stop interacting for at least one second.
2. With all fingers open, aim toward the screen and swipe left/right/up/down. Keep pinch-and-swipe for seeking.
3. Bring both open palms together gently. Verify Recently Watched appears with the just-played item first; reopen it and check its position.
4. While a movie is active, leave/reopen the app and confirm the saved size and automatic movie resume. After deliberately closing to Recently Watched, reopen the app and confirm it stays at that menu.

Progress is checkpointed every five seconds; an abrupt process loss can lose up to about five seconds since the last checkpoint. Normal pause/close saves immediately.
