# UHD movie playback — October 4, 2026

The app now presents movies on a 3840 × 2160 direct-to-surface video panel. The
library and control panels keep their existing resolution. Scene models, image
textures, and shaders were not edited for this change.

ExoPlayer uses a UHD viewport instead of the Android display dimensions and
prefers the highest supported bitrate. This prevents a smaller Android display
from restricting UHD track selection. It prioritizes quality; it may buffer on
a network that cannot sustain the selected track. The player retains Media3's
default decoder-capability and fallback behavior.

When Plex provides multiple media versions, selection prefers the largest version
within 3840 × 2160, including cinema-aspect-ratio versions such as 3840 × 1600.
Equal-sized versions prefer the higher bitrate and then the earliest media index.
If no version is within UHD, the largest available version is attempted. Both
fallback requests use the same selected media index as the original file.

Playback attempts:

1. Original file. This avoids a server video transcode and preserves the source.
2. Plex HLS with a maximum 3840 × 2160 request and 80,000 kbps bitrate budget.
   Direct streaming remains allowed so Plex can preserve compatible video.
3. After another failure, Plex HLS with a maximum 1920 × 1080 request and 20,000 kbps.
   Video direct streaming is disabled for this compatibility retry, so an unsupported
   UHD stream is not simply repackaged again. The bar explicitly says `1080p fallback`.
4. Stop retrying and display the error after the final failure.

Requests include matching client-profile dimension augmentations and the API's
maximum documented quality value, 99. URLs remain authenticated and are not logged.
The retry carries the current position and playback intent into the replacement
media item; its behavior across Plex HLS timelines still needs a device test.

The UI reports dimensions from `onVideoSizeChanged`, rather than calling every
source 4K based on the panel size. A 1080p source still reports 1920 × 1080. The
buffering label and movie subtitle share the existing secondary text line with
the resolution and playback mode; controls and physical screen size are unchanged.

This adds UHD resolution support. It does not implement HDR tone mapping,
Dolby Vision, stereo movie formats, subtitle selection, or audio-track selection.
Those features should not be inferred from 4K support. A Plex transcode can change
video quality even when its output resolution is 4K; original playback remains first.

## Validation

- Eight new regression tests cover UHD/version selection, cinema aspect ratio,
  8K avoidance when a UHD copy exists, bitrate preference, authenticated URL
  encoding, selected media index, profile dimensions, UHD/HD budgets, bounded
  retries, and actual-resolution labelling.
- The full workspace has 24 passing unit tests.
- `testDebugUnitTest assembleDebug lintDebug` succeeds; lint XML is checked for
  errors because this project currently does not abort the build on lint errors.
- The debug APK signature is checked with `apksigner verify`.
- At initial build validation, ADB reported no connected headset. No 4K decoder, Plex stream, audio/video sync,
  fallback, seeking, or sustained frame-time test has been performed on Quest.

The APK was subsequently installed on Quest 3, and a scene mesh-registration bug
found on its first launch was corrected. The rebuilt app now starts and reports
the Earth and library screen ready. See [installation validation](QUEST_INSTALL.md).
Actual 4K movie playback remains untested.

Current APK: `app/build/outputs/apk/debug/app-debug.apk`.
Pre-change APK and source snapshots: `art/validation/before-4k/`.
Build log: `art/validation/4k-build.log`.

The device check should play known SDR 4K H.264 and HEVC sources, verify the bar's
decoded dimensions, check the Plex dashboard's original/transcode status, then
exercise seeking, pause/resume, panel movement, and a lower-resolution source.
Measure dropped frames, CPU/GPU frame time and memory after warm-up. Test both
fallback stages and verify seek continuity and paused state through a retry.

## References

- [Meta media panels](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-media-playback/)
  documents the direct-to-surface path and panel pixel dimensions.
- [Media3 track selection](https://developer.android.com/media/media3/exoplayer/track-selection)
  documents display-size constraints and selection parameters.
- [Plex Media Server API](https://developer.plex.tv/pms/)
  documents transcode resolution, bitrate and client-profile augmentations.
- [Plex streaming overview](https://support.plex.tv/articles/200430303-streaming-overview/)
  explains original playback, remuxing and transcoding.


The 2026-10-04 headset report exposed a black video rectangle despite decoder
frames. The corrected compositor facing, hole-punch setup and Sun lighting are
covered in `VISUAL_FIXES.md`. A local 3840×2160 diagnostic fixture was installed
on 2026-10-05; the headset is asleep at the latest check, so this still does not
establish visible UHD playback. The UHD surface and stream settings are retained.


On 2026-10-05 the user confirmed that movie pictures are visible. Physical size and
explicit Original/4K/1080p controls were added and installed; the information line
now distinguishes source dimensions from decoded dimensions. The current HD route
label is `Plex · up to 1080p`. See [player controls validation](PLAYER_CONTROLS.md)
for the current 31-test result and remaining headset checks.
