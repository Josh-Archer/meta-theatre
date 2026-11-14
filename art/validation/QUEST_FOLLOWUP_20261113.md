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

## Follow-up: selected server and Roadster replay

The next wearer report was that the car was absent and playback still selected
homenas instead of homelab0. Recently watched previously combined server-local
bookmarks without a visible source label, and choosing a library did not scope
that list. Selecting a server now persists its ID, filters and refreshes recent
cards from that source, and updates cached Home pages used by Back. Cards also
show their server name. Restart cannot restore a bookmark from a different
server. An unavailable selected server offers a choice rather than silently
falling back. Failed selection leaves the previous preference intact; signing
out clears it. Server-local rating keys and bookmarks are not remapped.

This headset's preference was set to homelab0 with the app stopped; the exact
history JSON was preserved and the app was launched normally afterward. A fresh
authenticated request verified Hijack S2E1 on homelab0: exists/accessibility true,
3840×1606, 7,021,569,977 bytes, HTTP 206 and 1,024 bytes read. This establishes
source availability, not Quest decoder or audio/video success.

The Roadster normally has a 55-second hidden interval between 95-second passes.
The device log showed it hiding just after the user launched Starship. Scenes now
includes **Show / replay Roadster**, which frames the exhibit around the current
viewer and places the car at the middle of its pass. The remaining pass lasts
47.5 seconds. Replay clears only the car's grabbed/parked/return-flight state;
the ISS is retained. Quiet cinema remains respected. Model geometry, materials,
textures and the current near-plane mitigation are unchanged in this follow-up.

Validation of the latest build:

- **285 tests passed**, zero failures/errors/skips; debug assembly passed; lint
  zero errors and 62 warnings. Tests cover source-specific recent cards, restart
  with an old active bookmark, colliding server-local keys, unavailable servers,
  failed selection, retained progress and navigation cancellation.
- Compose exercises the visible Roadster control with both native pointer
  activation and measured hand-ray coordinates. Flight tests check that replay
  starts in front of the seat even during the normal hidden interval.
- Installed in place on the Quest; installed/local APK SHA-256 both equal
  `df1b8086c5f040fda10a9043f292108711f70e9883981d39a09d861e6425d00d`.

Actual Roadster visibility/flicker and Plex video/audio are pending the next
wearer check. The earlier missing-file recovery and quaternion fixes remain in
this build. Raw local evidence is ignored under `playback-20261113/`.

## Follow-up: discovery cutoff and shared pointer

The user next confirmed the Roadster was visible but still striped, reported a
jumping pointer, and saw only homenas in the server list. Discovery stopped after
four advertised connection addresses. homelab0's first four were unreachable
from the headset, while its public addresses occurred later in the list. The
client now tries all distinct advertised addresses in the existing priority
order. It retains TLS validation, authentication and per-request timeouts. A
real HTTP regression reaches the fifth distinct connection after four failures,
without retrying a duplicated address. At 22:03:51 device time, the installed
app completed discovery with homelab0 selected; it no longer entered the
unavailable-preferred-server branch. The wearer subsequently confirmed both
video and audio play from homelab0. After a normal restart at 22:14:26, the
native log again selected homelab0, restored the saved position and rendered
a 1920×1080 video frame. This verifies playback beyond the earlier HTTP probe.

The shared panel cursor formerly selected the closest ray anew every frame.
It now keeps the current input until another hand/controller explicitly presses
or the current ray leaves for 150 ms. This prevents tiny depth differences and
brief tracking loss from repeatedly swapping the cursor between hands. Input
gestures remain independent; replay tests cover distance oscillation, explicit
selection, tracking loss and handoff. Panel intersections are reused within a
frame. Physical tracking jitter and wearer comfort still need confirmation.

All **288 tests passed**, debug assembly passed, lint zero errors/62 warnings.
The installed/local APK SHA-256 was
`26978093b499c315e5f578d20297030d6b1899eac0d12eb83aa62f996a684981`.

The wearer then held the Roadster in view for a Meta Cam capture. The resulting
`roadster-wearer-visible.jpg` clearly shows red/black bands across its body. The
near-plane change did not resolve the defect. ADB display capture stayed black;
the Meta Cam image is actual headset evidence and stays local. Geometry/native
material investigation continues; no visual fix is claimed here.


## Follow-up: rejected culling test and SDK rendering fix

A temporary material-only test disabled double-sided rendering on the red body.
The Quest capture still showed stripes, and the wearer reported missing surfaces.
That experiment was rejected. The runtime GLB was restored byte-for-byte to
`43a1b830fd334d748e47f2e6460503379318e791304f38dd442e1aba5426280a`,
and the restored build was installed and verified before further testing. The
culling experiment is not included in source control.

Read-only inspection in the live Blender session found no closely overlapping
surface at the sampled hood and flank locations. The sampled runtime hood
triangles have consistent face/vertex normals. These checks narrow the
investigation; they do not establish a complete geometry or renderer diagnosis.

[Meta Spatial SDK 0.15.0 release notes](https://developers.meta.com/vr/downloads/package/meta-spatial-sdk/0.15.0/)
identify a fix for PBR specular banding/flickering. The app now uses 0.15.0 for
all Spatial SDK modules and the Gradle plugin, including UI Set through the
shared version catalog. Inspection of the shipped shader sources confirms
changes to the specular distribution calculation and eye-position precision.
This is a relevant upstream fix, not proof that it resolves this car's defect.

The app has no authored entity-parent scale hierarchy or physics bodies to
migrate. Its VRFeature already installs ISDK without the removed IsdkFeature.
All 288 tests pass against 0.15.0, with zero failures/errors/skips. Debug
assembly and all custom shader variants compile; lint reports zero errors and
54 warnings. All 168 packaged runtime assets match the source bytes, including
the complete two-sided Roadster. The candidate APK SHA-256 is
`d7f70c849307a46bc81e9e62f5febf7c4619304da84fe3c39939a3d4ff4c5b8e`.
The in-place install succeeded and the device APK hash matches. At 22:17:17,
the native Starship diagnostic passed launch, separation, orbit, descent, flip,
landing and recovery, with unchanged view origin, screen pose and screen size.
The app was then restarted normally without diagnostic extras. The automated
capture was black. In the subsequent wearer comparison, the body still had
stripes or missing surfaces: **the SDK update did not resolve this defect**.
The existing Plex and shared-pointer fixes are retained; normal launches on
0.15.0 continued selecting homelab0 and rendering video.


Additional local-only rendering probes:

- Disabling lighting on the red paint with `KHR_materials_unlit` still produced
  holes/stripes according to the wearer. The capture was black, so the exact
  unlit appearance was not independently visible in that capture.
- Deinterleaving vertex attributes preserved every accessor value and image
  byte and passed Khronos validation, but did not fix the defect. The wearer
  confirmed it, and `roadster-layout-2.jpg` through `roadster-layout-7.jpg`
  contain actual Quest captures showing the remaining pattern.
- Source and exported-geometry ray checks found only shared triangle edges at
  the sampled duplicate hits, not competing body layers. The dense body panels
  and their second triangles are distributed across the large paint index list.
- A smaller-draw probe partitions the paint/carbon index lists into at most
  65,532 entries per draw, preserving the complete binary payload and material
  values. This changes the Roadster from 10 to 12 primitives; Khronos validation
  reports zero errors/warnings. Its appearance result remains pending. Installed
  test APK SHA-256:
  `8163c7b93a21f08d2a70e8d8770fb2cf176d0523871fc64d0c07743524f02aac`.
- A separate, **uninstalled** 32-bit-index probe preserves every index value,
  vertex, image and material. The original Roadster contains 92,379 vertices
  across primitives with 16-bit local indices. That is valid glTF; an importer
  must handle any combined vertex offset without overflow. This is an import
  hypothesis, not an established root cause. The wider-index variant also has
  zero Khronos validation errors/warnings.

All probe scripts, images and variants remain ignored under
`playback-20261113/`. The committed runtime GLB and editable Blender source are
unchanged. No vehicle visual fix, pointer comfort result or sustained frame-rate
improvement is claimed. The wearer has confirmed Plex video and audio.
