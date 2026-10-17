# Viewpoints, nighttime, and video — 2026-10-17

Changing Earth viewpoints previously rotated the entire exhibit around a new
virtual observer. Physical body bounds remained separate, but their silhouettes
could overlap Earth's disk. It also moved the Sun away from the civil-time cycle
defined for the original equatorial view, leaving the far side bright at local
midnight or a pole at the terminator throughout the day.

## Environment correction

`EarthExploration` now keeps a geographic Earth frame and a seated sky frame.
Earth's three layers use the geographic frame; distant planet slots, stars, Moon,
Sun, and flybys use the seated sky frame. The same sky-frame point Sun reaches
Earth's three shaders, planetary shaders, and scene lighting. Midnight remains
dark and noon lit at the visible hemisphere for every viewpoint and manual spin.
The Moon retains its date-based phase calculation. Home restores both frames.

Opaque Earth and planetary materials explicitly test and write depth; translucent
shells and rings retain depth testing without depth writes. Geometry, textures,
shaders, relative body sizes, and runtime assets are unchanged. This remains an
artistic exhibit with a local civil-time lighting cycle and compressed distances.

## Video correction

The video compositor layer now uses `PanelShapeLayerBlendType.OPAQUE` and copies
source color/alpha with destination factors zero. The scene cutout retains its
hole-punch shader so the full-resolution underlay remains visible. Video pixels
cannot blend space through their interior. Transparent UI and native frame handles
remain separate from movie pixels.

The scene declares Rec.709 instead of leaving its gamut implicit. Video stays on
the native decoder surface; it is not converted through a bitmap or UI texture.
Media3 decoder fallback is enabled. The side menu reports selected frame metadata
as **Input: Dolby Vision**, **HDR10 · PQ**, **HDR · HLG**, SDR, or unspecified.
This is input-format reporting, not a claim that every output profile is certified.
Queued metadata is invalidated across source changes, closure, and release.

Original playback preserves the file. Plex HLS requests can transcode the source,
including its HDR metadata; 4K resolution alone does not imply Dolby Vision.

## Automated validation

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

- **157 tests passed**, with zero failures, errors, or skips.
- Six added integration tests cover local midnight/noon/dawn/dusk across poles,
  far side, equator, manual spin, turned/transferred viewers, and two time zones;
  planet silhouette and sphere clearance through 234 orbit positions per viewer;
  shared Sun and Home restoration; SDK opaque-layer configuration; video-format
  discrimination; actual player metadata callbacks and stale callback rejection.
- Existing frame, hand, visitor, Plex, subtitle, playback-intent, and UI tests pass.
  Native-font Compose checks include the Dolby Vision input label.
- Lint: zero errors/fatal issues, 42 existing warnings. Build succeeded.
- All 34 runtime assets matched their APK entries byte for byte; none were edited.
- APK signature verification and installed-file hash checks passed.

APK SHA-256:

```text
6caae0dfeeaf04ff6662cec27b931930ec34058fb950bad5f53664f2816ed80a
```

## Headset evidence

The replacement APK was installed without clearing private app storage. An ADB
codec probe found hardware Dolby Vision profiles 5, 8, and 20 and HEVC HDR profiles.
This is a codec capability report; it does not establish playback of every profile.

Two local, public AndroidX Media test fixtures were then exercised in the actual
app on the connected Quest:

| Fixture | Selected input | Decoder | Rendered size | First frame | Playback errors |
| --- | --- | --- | --- | --- | --- |
| `dolbyVision-hdr.MOV` | Dolby Vision | `c2.dolby.decoder` | 720 × 1280 | Yes | None |
| `hdr10-720p.mp4` | HDR10/PQ | `c2.qti.hevc.decoder` | 720 × 1280 | Yes | None |

The debug fixture path skips account restoration and Plex requests. Temporary
headset fixtures were removed and the normal app was relaunched afterward.
These checks establish native decoding and a rendered first frame. They do not
establish perceptual color accuracy, absence of visible transparency, sustained
playback, 4K HDR performance, every Dolby Vision profile, or real Plex behavior.
Viewpoint appearance, night lighting, and native handle visibility need the user's
in-headset check. Synthetic geometry tests cannot establish those visual results.

## Publication

Only source and validation notes are published to the private repository. Tokens,
private paths, personal email addresses, LAN addresses, raw logs, device identifiers,
downloaded test fixtures, captures, APKs, and private app storage stay excluded.

## References

- [Meta compositor blending](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-blend-modes/)
  documents opaque layers and their depth behavior.
- [Meta media playback](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-media-playback/)
  documents direct decoder-to-surface composition.
- [Android HDR playback](https://developer.android.com/media/grow/hdr-playback)
  explains device, decoder, and surface requirements.
- [AndroidX public test fixtures](https://github.com/androidx/media/tree/main/libraries/test_data/src/test/assets/media/mp4)
  supplies the local Dolby Vision and HDR10 files used for decoder checks.
