# Selectable Environments with Seating Presets

Validated on 2026-10-16. Editable Blender sources and all existing runtime assets
remain intact under `art/blender/` and `app/src/main/assets/`.

## Overview and Acceptance Criteria

1. **Selectable Environments in Headset**:
   - **Earth orbit (`earth_orbit`)**: Open space panoramic view with Earth below, the lounge chair at the center, and the cinema screen in front.
   - **Spaceship cabin (`spaceship_cabin`)**: An enclosed spacecraft interior bulkhead (`hull.glb`) with Earth visible through the starboard window and cinema screen mounted forward.
   - Accessible via the **Scenes** button along the library shelf footer.

2. **Per-Scene Seat and Screen Persistence with Reset-to-Default**:
   - Each environment independently preserves:
     - Screen dimensions (`width`, `height`).
     - Screen transform/pose (`center`, `quaternion`).
     - Seated view origin and yaw (`x`, `y`, `z`, `yaw`).
   - Settings are automatically debounced (750 ms) and saved on app pause, destruction, and session state change.
   - The scene picker menu includes a **Reset to default** action that restores the initial screen dimensions, position, and seating origin for the active scene.

3. **Playback Preservation During Scene Changes**:
   - Media playback (ExoPlayer), stream state, position (`positionMs`), playing status, and active audio/subtitle track overrides are completely preserved across environment switches.
   - The video surface panel overlays smoothly synchronize to the new screen pose without rebuilding the decoder pipeline.

4. **Runtime Cost, Frame Pacing, and Quest Hardware Validation**:
   - Existing runtime assets (`hull.glb` 4.8 MB, `lounge_chair.glb` 45 MB) are loaded as immutable runtime entities.
   - Environment transitions destroy previous entities via `Entity.destroy()`, ensuring geometry is not duplicated in memory.
   - Debounced pose updates avoid disk I/O on the main render thread.

## Automated Evidence

- `testDebugUnitTest` passed with 148 tests (including 5 comprehensive environment integration tests).
- `lintDebug` passed with 0 errors.
- `assembleDebug` successfully compiled and packaged the debug APK.
- New integration tests in `EnvironmentsIntegrationTest.kt` cover:
  - Distinct preset definitions and asset URI bindings.
  - Independent per-scene persistence in `TheatreState`.
  - Selective reset-to-default targeting only the active environment.
  - Scene picker hit testing and `TheatreModel` state transitions.
  - Playback position, playing state, and track selection preservation across location switches.

## Hardware Validation Checklist (Meta Quest 3)

- [ ] Connect headset via USB and install via `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- [ ] Launch Meta Theatre and verify initial spawn in Earth orbit.
- [ ] Select **Scenes** from the library shelf footer. Verify the environment selection modal opens.
- [ ] Select **Spaceship cabin**. Verify smooth transition without frame drops or stutters.
- [ ] Confirm the spaceship hull is rendered correctly with exterior space and Earth visible.
- [ ] Adjust screen size and position using grip controls.
- [ ] Turn view using thumbstick snap-turning or library footer turn buttons.
- [ ] Select **Reset to default** in the Scenes menu. Verify screen and view origin reset to initial presets.
- [ ] Play a video, select an alternate audio/subtitle track, switch environments, and confirm uninterrupted playback.
