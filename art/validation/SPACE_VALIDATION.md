# Space validation — 2026-10-19

Current integration and automated Quest results are recorded in
[REMAINING_ASSETS_20261112.md](REMAINING_ASSETS_20261112.md).
The dated results below describe the original pass.

The 8K Milky Way is published to the native app's existing `solar/stars.jpg`.
Matched Blender core renders and a separate Cygnus render were inspected.
The review uses the actual runtime JPEG and the native shader's 0.8 multiplier.
These are authoring previews; native color handling and stereo require Quest.

Completed:

- Live Blender MCP inspection, source checkpoint and authoring. Missing-file
  inspection found no missing referenced files in the saved scene.
- Original NASA 8K linear EXR layers preserved, with source SHA-256 records.
- Runtime texture 8192 × 4096, JPEG quality 98 / 4:4:4; 45.43 dB encoding
  PSNR against the graded lossless source; clipped fraction approximately
  0.000194 percent. The prior 4K map is preserved.
- Existing runtime sky geometry, draw count, shader and renderer preserved.
- Unreal imported dome, texture and unlit material into the existing asset lab.
  A separate editor invocation reloaded all three saved packages and verified
  8192 × 4096 dimensions, 8192 size cap, zero LOD bias, sRGB, unlit shading and
  material assignment. NullRHI was used; no Unreal rendered image was validated.
- JDK 17 `testDebugUnitTest assembleDebug lintDebug`: BUILD SUCCESSFUL,
  200 tests, zero failures/errors/skips. Lint: zero errors, 43 warnings.
- Repackaged after the credit update. APK texture, unchanged sky GLB and
  credits match the workspace outputs byte for byte.
- `git diff --check` passed. Pre-existing Earth/navigation source edits were
  retained and are included in this workspace's successful build.

The initial Unreal import saved its packages, then hit a Python JSON error
while serializing Unreal's Array type. The authoring script now converts it to
a list; a fresh verification run reloaded the saved packages successfully.
Unreal also logs Meta XR property-initialization and GameFeatureData settings
errors at startup; the sky asset checks above passed despite those sandbox
issues. This is not an overall clean Unreal project validation.

Quest at inspection: USB-authorized and awake; LinuxQuest was the foreground
application. The previously installed Theatre APK was saved locally before
any proposed install. Switching the foreground app awaits the user's answer.
No headset-render, stereo, frame-time or measured GPU-memory claim is made.

The new sky adds approximately 128 MiB of estimated RGBA8 texture memory with
full mips (170.67 MiB versus 42.67 MiB). Actual upload format, transient decode
cost, frame pacing, texture shimmer and visual appearance remain device checks.

Budget correction after a fresh runtime-image inventory: Earth has three 8K
maps and two 4K maps, totaling 597.33 MiB with full RGBA8 mips. Solar adds
266.68 MiB, giving 864.01 MiB combined, excluding other scene assets and video.
The initially stated 416.01 MiB combined estimate used an outdated Earth figure.
The sky's own 170.67 MiB allocation and 128 MiB increase are unchanged.

Raw machine/device logs, APK backups and JSON manifests stay local under
ignored `art/validation/` paths. Nothing was committed or pushed.

## Installation — 2026-10-20

At the user's request, rebuilt the current workspace with JDK 17
(`assembleDebug`: BUILD SUCCESSFUL). Verified the Android v2 signature and
exact packaged sky-map, GLB and credits bytes. Installed with `adb install -r`
on the authorized Quest 3; the installer returned Success. The installed base
APK's SHA-256 matches the local APK:
`2471d87a7bd4e7d04839c82c101c580fd5ef8c82e1839db4575e18e2374ed253`.
App data was retained. The headset was asleep, so no launch or rendered-view
verification was attempted. The build is ready for the user's headset test.
