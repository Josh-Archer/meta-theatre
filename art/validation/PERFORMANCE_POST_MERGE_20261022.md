# Merged performance change: validation on Quest 3

PR [#3](https://github.com/Josh-Archer/meta-theatre/pull/3) was squash-merged as `fb9a0499315ddfe8d65d5ded6c325e3a05b60404`. Validation used an isolated checkout of that exact commit. The original workspace's unrelated edits were retained.

## Automated validation

- `testDebugUnitTest assembleDebug lintDebug`: successful on the merged tree.
- A fresh `:app:testDebugUnitTest --rerun` executed 210 tests: zero failures, errors, or skips.
- Lint: zero errors, 45 warnings.
- Merged tree matches the previously tested PR tree; working tree is clean.

## Installed build and device smoke tests

The merged APK was installed with `adb install -r`, preserving app data. Its on-device SHA-256 matched the local build: `abe82894bc3c26d9b85b25243bdc24859ba2abd3f361d3514896519ac229c4fd`.

The Quest was awake. Normal cold launch completed successfully, the activity was resumed, and the runtime reported Dynamic Earth ready. A 30-second VrApi capture after startup produced 30 samples: **90–91 FPS against a 90 Hz target**, median reported App time **7.08 ms**, and **zero stale frames**. Initial scene loading had slower samples, so these figures describe the steady scene only. This is not a before/after benchmark.

The built-in diagnostic confirmed geographic recentering, safe zoom, and preservation of the screen and viewing origin. It then generated and applied the full 8K storm cloud field. The cloud-ready marker followed the controls-passed marker by about 7.9 seconds. Thirteen subsequent frame-rate samples had a median of 90 FPS; one sample shortly after the update was **76 FPS with 15 stale frames**, followed by 89–91 FPS with no further stale frames in the captured window. The sampled app PSS after this test was approximately 1.27 GiB, including approximately 1.03 GiB attributed to graphics. No pre-change device memory measurement was made.

The normal startup and filtered diagnostic captures contained no fatal-exception markers. After testing, the app was restarted without diagnostic extras and again reported Dynamic Earth ready. The final observed PID was 27718 and the headset remained awake.

## Findings and limits

- **Repeated missing-component errors:** the SDK repeatedly logs missing attribute 175, mapped by `spatial_ids.json` to `Scale.scale`. `ScreenGrip.logicalScale()` reads the optional component every frame and catches failure to use unit scale; the library panel is created without an explicit Scale component. This unchanged path predates PR #3 and is the likely source, but no pre-merge device reproduction was performed. The errors did not terminate the process, but the log traffic remains a follow-up issue.
- **Cloud-update hitch:** the transient 76 FPS sample means the test does not establish hitch-free 8K weather updates.
- The Android display screenshot was effectively black and did not provide usable visual confirmation of the headset compositor. Stereo appearance, physical hand/controller interaction, and Plex playback were not verified by this smoke test.

Temporary device captures and the previous installed APK are under `/tmp/meta-theatre-merged-smoke/`. Build logs are `/tmp/meta-theatre-merged-validation.log` and `/tmp/meta-theatre-merged-tests-fresh.log`. Raw logs and APKs were not committed or uploaded.
