# Junior tasks and validation

The global junior agent now uses `gpt-6-luna` with `model_reasoning_effort = "high"`.
The project copy is `.codex/agents/junior.toml`; the global definition and existing
`junior.agent.md` definition was updated to match. One junior agent was launched
for each task. The parent reviewed the changes, connected the UI and scene
callbacks, and ran the combined validation.

| Task | Junior agent | Implemented behavior |
| --- | --- | --- |
| Buffering intent | `junior_buffering` | Pause and menu resume use playback intent, including repeated menu-open notifications and a pending video surface. Actual playback state remains separate for the progress ticker. |
| Back navigation | `junior_navigation` | Back cancels pending browsing, clears loading, and invalidates stale results and errors. Root Back remains a no-op. |
| Large libraries | `junior_pagination` | Pagination retrieves more than 1,000 entries, handles missing totals, and reports stalled or incomplete responses instead of silently returning a partial library. |
| Audio and subtitles | `junior_tracks` | Supported Media3 audio/text tracks can be selected. Plex HLS requests select metadata stream IDs and burn selected subtitles, preserving requested quality, seek position, and play intent. Native text and bitmap captions render through a separate transparent screen overlay. |
| Search and filters | `junior_search` | Search operates on the current shelf, with Movie, TV, and Continue filters where applicable. The on-panel keyboard includes digits, uses shared layout/hit geometry, and filtered callbacks select the correct item. Navigation and Clear reset search. |
| Quiet cinema | `junior_quiet` | A saved, initially disabled setting dims scene lighting and hides/pauses flybys while watching. Leaving the movie restores normal lighting and resumes the same logical flyby phase. |

## Automated verification

Command:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain
```

- 99 tests passed: the previous 71 plus 28 new cases. No failures or skipped tests.
- Robolectric and local MockWebServer tests cover actual Android state, Plex HTTP/JSON pagination, stream metadata, canceled navigation, and search-to-playback callbacks.
- Compose integration tests check the rendered player buttons and search keyboard against the measured coordinates used by Quest rays, then dispatch through the actual model.
- Native graphics testing verifies text and bitmap cues paint through the app's SubtitleView helpers.
- Lint has zero Error/Fatal findings and 42 Warning findings.
- The debug APK builds and verifies with Android APK Signature Scheme v2.
- All 14 runtime GLBs in the APK match the current source assets byte for byte.

Evidence summary: [validation JSON](junior-tasks-validation.json). Raw build logs,
test/lint HTML reports, APKs, and source checkpoints remain local and are excluded
from Git to avoid publishing machine or device information.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Editable Kotlin/test sources and build configuration were backed up under
`art/validation/before-junior-tasks-20261007-035500/` before changes.

## Remaining device validation

No Quest was connected during final validation. The changes were not installed
or exercised in-headset. Real Plex direct/HLS playback, server subtitle burn-in,
track switching, caption placement during screen movement/resizing, physical
keyboard and player-ray interaction, and quiet-mode lighting/performance remain
device checks. Local integration tests do not establish those results.
