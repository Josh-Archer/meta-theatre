# Screen orientation, Plex recovery and vehicle flicker

The user confirmed that Recently watched → Scenes exposes Starship, then reported
intermittent screen inversion, a black player after missing-file errors, and
flickering patches/stripes on Starship and the Roadster.

## Screen orientation

`poseToJson` stores named quaternion fields. `poseFromJson` passed them as x/y/z/w
to the SDK constructor, whose actual bytecode assigns its arguments w/x/y/z.
Reloading settings therefore changed the screen orientation. Resizing or changing
the seat also reads those settings, which could persist the corrupted rotation.

The reader now uses named constructor arguments. A regression failed before the
fix and passes afterward, checking both facing and up vectors over repeated
reload, resize and seat-save cycles in both environments. Intentional screen
tilt, position, size, and seat settings are retained. The installed app restored
the same upright quaternion components already present in the device settings.

## Missing Plex files

Plex metadata now requests `checkFiles=1`. An explicit false `exists` or
`accessible` flag prevents opening the player. Absent availability flags remain
compatible with servers that do not report them. The original quality selection
is retained; a lower quality copy is not silently substituted.

Preflight failure, a later HTTP 404/410, or a missing file discovered during
Reconnect returns to the library, showing the server picker when multiple
servers are available. The saved position stays in history, while the active
player pointer is cleared so the next startup does not reopen the failed item.
The error remains visible and does not ask the user to relink a working account.
No server media files were changed.

## Flicker mitigation and limits

The scene near plane changes from 0.05 m to 0.15 m; the 120,000 m far plane and
all model/texture bytes, materials, lighting and trajectories remain unchanged.
This improves conventional depth separation for thin surfaces without trimming
the distant sky. The relationship is described in the
[Khronos depth precision reference](https://wikis.khronos.org/opengl/Depth_Buffer_Precision).

For a conventional 24-bit depth buffer, estimated depth steps at 24 m improve
from 0.687 mm to 0.229 mm; at 100 m from 11.921 mm to 3.974 mm. These are analytic
estimates, not measurements of the Quest's active depth format. The user-described
flicker is consistent with competing surfaces, but neither that diagnosis nor
the visual result is established by automated tests. Wearer comparison remains
required before claiming the flicker is resolved.

## Validation

- All **282 tests passed**, including real HTTP availability/404 checks,
  server-picker recovery, retained bookmarks, missing-file reconnect, and screen
  orientation persistence. Debug assembly passed; lint: zero errors, 62 warnings.
- In-place Quest installation succeeded. Normal launch restored the expected
  screen rotation. Installed and local APK SHA-256 match:

```
78af1aa684522c98e27d68bb8f15daabbe01c8d71fbbd9980ccaf2abd896cd43
```

At 21:47:29 device time, normal startup encountered the unavailable saved Plex
item and returned to the library without creating a video surface. A read-only
state check confirmed that the active player pointer was cleared and the prior
1,159,210 ms bookmark remained.

Ignored raw evidence is under `art/validation/playback-20261113/`: the failing
rotation regression, final build log, sanitized device log and capture. Actual
video/audio playback from the available Plex server and vehicle appearance are
separate wearer checks, pending at this checkpoint.
