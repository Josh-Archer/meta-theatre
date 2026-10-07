# Quest installation — October 4, 2026

The latest debug APK was installed successfully on the connected Quest 3 with
`adb install -r`. The replacement preserves existing app storage, including Plex
sign-in. The installed APK's SHA-256 matches the local build byte for byte.

The first launch exposed a real device startup failure:

`No mesh creator found for mesh://earth_atmosphere. Please call registerMeshCreator before.`

Earth and solar mesh factories were registered with bare names, but their Mesh
components used complete `mesh://` URIs. Inspection of the installed Spatial SDK
0.14.0 implementation confirmed that the creator API takes a mesh URL and lookup
uses the complete URI. Both renderers now use the same URI for registration,
instantiation and unregistration. Geometry, textures and materials are unchanged.
Pre-fix source copies are saved under `before-mesh-registration-fix/`.

The corrected APK was rebuilt and installed as an update. All 24 unit tests pass,
lint reports zero errors, and `apksigner verify` succeeds. See
`quest-install-build.log` and `quest-install.json` for the validation record.

The corrected build subsequently launched on the headset. Its new process
reported `Dynamic Earth ready` and `theatre screen spawned`, with no Java crash.
The prior crashing process is distinct from this healthy launch, so old crash
entries must not be reported as a new crash of the corrected build. The JSON
record also captures a bounded process-stability check.

Test a real Plex movie and the 4K resolution indicator next. This installation
and startup check do not establish movie playback, visual quality or sustained
frame-time results.

Current APK: `app/build/outputs/apk/debug/app-debug.apk`.
