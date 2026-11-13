# Quest asset smoke test — 2026-11-12

The Quest 3 was unlocked and the installed local build launched successfully.
The normal cold launch returned `Status: ok` in 2,508 ms. Its installed APK
matched the local SHA-256:
`73637f6253b07f9092774fbeaabc7943cf77219ccacf1a9732b8d4927000f8c4`.
This is the broader development checkout, including ISS, celestial, chair,
Roadster, regional Earth and interaction changes. It is not the isolated
ISS PR's APK.

## Visual and importer evidence

`quest-20261112/normal-display.png` is an actual stereo Quest display capture.
It shows the ISS flyby, Earth with night lighting, the star background, nearby
planets, chair and Roadster. The ISS is distant: this verifies that the asset
renders, not close inspection of both array faces, fine material quality,
selection/return interaction or wearer comfort.

After the wearer confirmed Meta Theatre was visible, the Meta Cam capture
`partition-visible.jpg` showed the close-Earth controls and nighttime surface
at 116.9x zoom. The greatly magnified city-light pixels are visible; regional
satellite imagery does not increase the global night map's resolution.
Other captures were black, including the later daytime Tokyo attempt. Those
captures do not establish visual correctness or the cause of the black output.
The user confirmed visibility during the earlier capture, not throughout every
later timing interval.

The partitioned build's SDK introspection reported 146 successful GLTF loads,
zero failed loads and 172 live entities. The comparison build reported 19
successful loads, zero failures and 45 entities. The extra 127 entities are
the expected tradeoff of replacing one detail sphere with 128 sections.
Filtered app error captures and runtime logs contained no fatal-exception
markers during these checks.

## Regional detail on the device

Both Carrollton and Tokyo diagnostic launches passed geographic recentering,
safe zoom, screen preservation and view-origin preservation. Both logged
**one selected section, 2,048 triangles**, versus the original 260,096-triangle
overlay. These are selector counts from the running app, not a GPU capture of
the draw stream. Every source triangle remains in the assets.

With the Carrollton imagery already cached, the first run decoded its 512px
preview in 172 ms and uploaded it in 3 ms. The 4096px stage decoded in 476 ms
from request start and uploaded in 112 ms. Tokyo's uncached run decoded the
preview in 2,011 ms and uploaded it in 3 ms; its 4096px stage decoded in
13,243 ms and uploaded in 113 ms. These are individual observations, not
general latency guarantees. The Tokyo runtime proof covers loading and
selection; the black capture leaves daytime visual acceptance pending.

## Timing evidence and limits

An isolated temporary build copied the current app and replaced only
`EarthRenderer.kt` with the preserved pre-partition file. A source-hash check
confirmed that was the only source difference. This avoids comparing against
an unrelated older release. Its APK SHA-256 was
`671e351d93f3954bc75094bcef8b77ce4ecc44f803e322e0e5a90408fdc2629a`,
also verified after installation. App data was preserved throughout.

Both builds used the same Carrollton fixture, clear-Earth setting and 116.9x
zoom. The table uses app-PID-only VrApi samples from seconds 20 through 45
after the screen-spawn marker, with 25 samples per build.

| Observation | Full sphere | Partitioned repeat |
| --- | ---: | ---: |
| FPS median | 91 | 91 |
| FPS range | 89–91 | 86–91 |
| Reported App time median | 8.20 ms | 6.11 ms |
| Reported CPU&GPU median | 11.32 ms | 21.23 ms |
| Stale frames in window | 0 | 18 |
| Reported temperature | 50 C | 43 C |
| Earth-ready after screen marker | 3.289 s | 3.398 s |

**This is not a controlled performance improvement result.** The runs were
separated by about 94 minutes, lighting followed real time, headset focus and
pose were not held fixed, and screenshot/introspection work overlapped part
of the repeated run. The lower App value cannot be attributed solely to
partitioning, and the higher combined timing and stale frames must not be
omitted. These are runtime-reported values, not separate CPU/GPU profiler
measurements. Repeat under stable visibility, pose, clock and capture-free
measurement windows before claiming a speedup or a regression.

The earlier current-build capture, after the wearer confirmed visibility,
had a final 20-sample window at 90–91 FPS, median App 7.76 ms and zero stale
frames. Startup and imagery uploads still produced stalls: the initial
Carrollton loading run included 76–77 FPS samples, and Tokyo reported
88 FPS with 11 stale frames shortly after its final upload. More work is
needed on upload/startup hitches; this pass does not establish hitch-free
performance. A sampled close-Earth PSS was 1,453 MiB, including 1,164 MiB
classified as graphics; memory samples were not controlled comparisons.

## Restored state

The updated local APK was reinstalled after the comparison, its installed
hash was rechecked against `73637f...00f8c4`, and the app was restarted without
diagnostic extras. The final cold launch completed in 972 ms, Dynamic Earth
became ready, both flybys became visible in the logs, and the activity remained
resumed as PID 20649 with user 0 `RUNNING_UNLOCKED`. Its final 18-sample window
reported 90–91 FPS, median App 6.375 ms and zero stale frames; this log result
does not add visual evidence to the earlier capture. No app data, saved settings, Guardian settings or proximity
settings were cleared or changed by this validation pass.

Raw logs, captures, APK comparison metadata and the summarizer are local,
ignored files in `art/validation/quest-20261112/`. The source and geometry
checks remain in [EARTH_PARTITIONS_20261112.md](EARTH_PARTITIONS_20261112.md).
Issue #13 remains local and unpushed; ISS publication is tracked separately
in [ISS_20261112.md](ISS_20261112.md).
