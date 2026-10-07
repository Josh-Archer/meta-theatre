# Movie screen size and quality controls — 2026-10-05

The user confirmed that the previous compositor-facing correction made the video
picture visible. They then reported that the screen could not be enlarged and
that the movie decoded at approximately 720×404 without a video quality control.

The library panel's SDK resize handles are disabled and hidden during playback.
The player now supplies Smaller/Larger controls which update the shared panel
physical dimensions directly. Each step scales by 1.2 or its reciprocal, preserves
the current aspect ratio, and bounds width to 1.2–8 metres. The lower edge stays
in place as the screen grows upward, keeping the controls reachable. Video and
controls use the existing overlay synchronization and scale together. This does
not change the compositor surface's 3840×2160 pixel dimensions.

The player also supplies Original, 4K, and 1080p choices. Original remains the
initial default and requests the selected source file directly. 4K uses the Plex
HLS UHD request (up to 3840×2160, 80,000 kbps, video direct streaming allowed).
1080p uses the Plex HLS HD request (up to 1920×1080, 20,000 kbps, video transcode).
The track selector follows the selected maximum dimensions. Selection keeps the
current player position and playWhenReady state when replacing the media item.
Compatibility retries still proceed from Original to UHD to HD on decoder/stream
failure. The playback route remains visible separately from the selected quality.

The information line now reports both source metadata dimensions and actual decoded
video dimensions. A 4K request with a smaller source says that a 4K source file is
needed. A 4K source delivered at a smaller resolution identifies the server-side
reduction and suggests Original/server limits. No assertion has been made that the
reported 720×404 source was itself 4K: the previous build did not retain source
metadata, and fresh playback metadata is needed to determine that. Diagnostic
logs include only numeric source dimensions, bitrate, version count/index, requested
quality, and screen dimensions; playback URLs and credentials are not logged.

The controller ray and Compose controls share the same row boundaries and control
lists. Watching mode is checked before the sign-in view so the local diagnostic
player can be operated without a Plex login.

## Validation

- `testDebugUnitTest assembleDebug lintDebug` succeeded; 31 tests passed.
- Lint XML contains no Error/Fatal entries; APK signature verification succeeded.
- The debug APK was installed with `adb install -r` on the connected Quest 3.
  Installed and built SHA-256 values match; see `player-controls-install.json`.
- A normal app launch was requested without the local test-video extra.
- The headset reported `mWakefulness=Asleep`; the new physical controls, real Plex
  quality transitions, HLS seek continuity, and UHD decoder playback remain unverified.
- The local 3840×2160 diagnostic fixture is available in the app cache, separately
  from the APK. It tests the decoder/compositor, not Plex's transcode decisions.
- Scene shaders, runtime models/textures, the Sun correction, and the compositor
  facing/hole-punch fix were not edited for this change.

Build log: `player-controls-build.log`. Source rollback snapshots:
`before-player-controls/`. Regression tests cover size/aspect/bounds, controller
hit regions, explicit route choices, and distinguishing smaller source files from
server downscaling, in addition to existing playback/scene checks.

## Headset check

1. Open a movie and use Larger/Smaller while it plays. Check that the video, control
   panel, and trigger hit regions scale together. Grip movement should still work.
2. Read both Playing and Source in the information line. If both say 720×404,
   choose a higher-resolution file/version on Plex to obtain real 4K detail.
3. On a known 3840×2160 SDR movie, compare Original, 4K, and 1080p. Check actual
   decoded dimensions and Plex server decisions, not just the highlighted button.
4. Change quality while playing and while paused, at a nonzero movie position.
   Check position continuity, pause intent, audio sync, and any buffering/fallback.
5. Measure decoder dropped frames and Quest CPU/GPU frame timing after warm-up.

[Plex's transcode API](https://developer.plex.tv/pms/) defines videoResolution as
maximum target dimensions and videoBitrate in kbps. Server bandwidth constraints
can also limit output; see [Plex server limits](https://support.plex.tv/articles/227715247-server-settings-bandwidth-and-transcoding-limits/).


The horizontal layout described here was superseded by the 2026-10-05
[side player and hand input update](SIDE_PLAYER.md). That build has 36 passing
tests and is installed; awake native input checks remain pending.
