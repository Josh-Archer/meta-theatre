# Cabin interaction regression fixes

The wearer reported hand resizing could not enlarge the wall screen, and movement targets flickered. Device logs showed paired pinch down/up events without a resize starting.

Two-hand stretch now allows a bounded angular margin around the panel (distance-scaled, capped at 1.5 m). Clicks and single-hand seeks still require their original on-panel start. Playback controls, hidden panels, navigation, and SDK manipulation retain ownership. Tracking loss still requires releasing before another stretch.

Teleport planes now sit 2.5 cm above the deck instead of coplanar with it. Their display bridges navigation tracking gaps of up to 200 ms; loss of immersive focus still hides them immediately.

Validation: 302 tests passed, including near-miss two-hand enlargement, no accidental click/seek, navigation visibility gaps, and existing gesture ownership tests. Debug APK rebuilt, installed successfully, cold-launched, and installed SHA256 matched the local build. Wearer verification remains pending.

APK SHA256: 7afb2f87c658981723217f7b116c31e51e71662a27c4d115a9101313f7867c93
