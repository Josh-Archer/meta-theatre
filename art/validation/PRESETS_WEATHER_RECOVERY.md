# Viewing presets, Earth shading, and Plex recovery

Validated on 2026-10-18.

## Delivered behavior

- Explore Earth → Viewing presets has three persistent device-local slots. A
  setup contains screen pose relative to an upright viewer, screen size, virtual
  chair yaw, Earth latitude/longitude, and globe spin. Restore rebases around the
  current seat and uses a snap change. It does not restore a physical tracking
  position. Reset view restores the home view and default screen size.
- Cloud profiles distinguish low cloud and convective height. Live wind changes
  frontal structure. The native shaders add a second self-shadow sample, soft
  surface shadows, forward scattering, and thin-shell atmospheric optical depth.
  Night terrain has a small ambient contribution and cities are attenuated by
  cloud density. Weather is still a modeled representation of current conditions,
  not satellite imagery.
- Connection errors retry the same stream three times with 1/2/4-second delays.
  Network failures do not trigger quality fallback. Codec/container failures
  retain compatibility fallback. The last known position survives idle/error
  callbacks from a decoder that has reset its position to zero. Pause/menu/hidden
  screen intent survives reconnects; closing or replacing media cancels retries.
- Reconnect refreshes the Plex server endpoint/access and reopens the movie at
  its bookmark. Account authorization failures offer Relink Plex; server access
  failures offer a refresh. Neither automatically clears credentials/history.
  Error UI omits response bodies, URLs, and raw network exception messages.
- The player separates source Input metadata from its configured SDR/Rec.709
  compositor output. A Dolby Vision input label is not display-output proof.

## Automated and authoring evidence

`testDebugUnitTest assembleDebug lintDebug` passed with 183 tests, zero test
failures, zero lint errors, and 43 lint warnings. Fourteen tests were added across
preset persistence/layout, bookmark/retry behavior, model URL refresh, actual
mock-server HTTP errors, and weather-profile/wind data. Existing Compose sidebar
tests now check the Reconnect label and non-overlapping layout. Native-rendered
Compose fixtures were visually inspected; a clipped Reconnect button was fixed.

All 37 runtime assets byte-match the APK. No runtime mesh, texture resolution,
poster, or video quality was reduced. Earth remains three draws with the same
41,280 triangles. Surface sampling rises from five to seven texture reads;
cloud sampling rises from six to seven. Profiles occupy the existing weather
texture's RGB channels; no extra full-size GPU map is introduced. Bitmap RGB
data is explicitly unpremultiplied so density alpha cannot alter cloud height.

The configured live Blender bridge was unavailable. Background Blender MCP
inspected the existing source and generated a separate editable
`art/blender/earth_scattering.blend`, plus daylight/twilight/night references in
`art/renders/earth_scattering/`. The original source remains intact. The new
source was reopened through Blender MCP after metadata sanitization; all five
objects, mesh counts, and relative image paths were intact. These Cycles renders
are authoring references, not native shader output or headset captures. No Unreal
MCP tool was exposed in this session; the Android project remains native.

## Headset evidence and limits

APK SHA-256:
`83c080b49c00ad9d642be47589af3ec732a871ef241aa7d2be4ab0894f17932f`

The APK installed successfully using a preserving update. Hashes of the Plex
and theatre-settings preference files were identical before/after installation
and after the diagnostic launches. Native logs confirmed the theatre screen and
new Earth renderer initialized. The existing local video fixture decoded at
3840×2160 and rendered its first frame, with no observed fatal exception.

One diagnostic process snapshot showed 1,021,132 KiB PSS, 1,134,110 KiB RSS, and
825,518 KiB graphics memory. This is not a controlled comparison or frame-time
benchmark. The weather diagnostic initialized Earth but did not report applying
a live snapshot during this run. Native live-profile upload, perceived lighting,
stereo appearance, preset restoration with real tracking, and sustained movie
frame rate still need an awake/in-headset acceptance check. Plex failure/recovery
was tested with synthetic credentials and local HTTP fixtures; no authenticated
Plex movie reconnection was claimed.

Raw device logs, preference hashes, test captures, API data, and APKs remain local
and ignored by Git. Only source, sanitized authoring references, tests, and this
summary are published. No device identity, network address, Plex token, watch
history, or actual viewing preset is included.
