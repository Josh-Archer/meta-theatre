# Orbital weather verification — 2026-10-17

## Build and integration checks

`testDebugUnitTest assembleDebug lintDebug` passed with JDK 17: **164 tests,
zero failures/errors; lint zero errors and 42 existing warnings**. Seven new
integration tests cover:

- Native Earth meridian, manual spin, both poles, far-side exploration and
  dateline-safe sampling of 49 locations.
- The real HTTP client against MockWebServer: batched coordinates, expected
  fields, absence of account authentication, and non-success responses.
- Incomplete grids, null/invalid fields, mismatched locations, stale/future
  timestamps and weather-code mapping.
- Default-off/background behavior, debounce, request spacing, memory cache and
  cancellation during view changes or disable.
- Offline labels, old-region map removal and cancelled in-flight results.
- The actual authored atlas and full-resolution map generator: changed cloud
  coverage, preserved unsampled geography, seam and pole handling.
- The rendered Compose panel's Quest ray coordinates: open weather settings,
  opt in/out, persist the preference, refresh, prevent hidden Earth-menu actions,
  return to Earth and then the original movie without linking or signing out Plex.

All **34 pre-existing runtime assets** are byte-identical to the previous commit
and to their packaged APK entries. The two added assets are the cloud atlas and
weather attribution. No existing texture, model, geometry, shader, poster or
video quality was reduced.

## Asset checks

The live Blender MCP session authored seven editable cloud patterns. The final
scene-only `.blend` reopens successfully and resolves its relative atlas path.
Workstation file-browser/UI metadata is omitted. FBX string metadata was sanitized
and every numeric property was compared with its original value.

The seven exported GLBs pass Khronos glTF Validator with **zero errors/warnings**;
each contains exactly its intended model. They total 8,720 reference triangles.
The 4096×2048 authored atlas and runtime atlas are byte-identical. Runtime uses the
existing cloud shell and its day/night shader; it does not render these seven
reference meshes or add cloud draw calls.

In the existing Unreal asset sandbox, live MCP imported seven models and the
atlas, authored one translucent material plus eight tile instances, and saved
17 assets in the weather folder. The thunderhead material thumbnail was inspected
after verifying its UV offset connections. The open level was preserved.
The portable authoring script and source exports reproduce those assets; local
Unreal import paths/packages are not published.

## Live service and Quest checks

A real Open-Meteo request returned the expected current-condition fields at
multiple public test points, including a pole. No captured responses or viewed
coordinates are published.

The APK was installed with replacement install, preserving app data. SHA-256 of
the installed base APK matched the built file:

`4fb26ff33368a724dfc773ab905f8112306d364b09488d59786cfb89efe0d51a`

On the connected, awake Quest, a debug-only diagnostic opened the real settings
panel and enabled weather in memory. The final installed process logged dynamic
Earth readiness and successful application of all 49 live weather readings,
with no fatal exception. The diagnostic does not change the saved weather
preference or start Plex account restoration. Logs omit request URLs, locations,
account data and provider exception messages.

Afterward the normal app was relaunched. Its saved weather preference was unset
(default off), Earth initialized, and the normal process made no weather fetch.
Raw device logs, screenshots, API responses, build outputs and metadata checkpoints
remain local and ignored by Git.

## Remaining headset checks

Automated rendering/upload evidence does not establish stereo appearance, cloud
realism or sustained GPU/frame timing. Review the cloud density and texture seam
from equatorial and polar views in the headset, check twilight/night visibility,
and try the weather buttons with hands and controllers while playing media.
The desktop Compose fixture and Blender/Unreal previews are not headset captures.
