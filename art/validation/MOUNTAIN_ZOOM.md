# Mountain zoom and streaming validation — 2026-10-21

Current integration and automated Quest results are recorded in
[REMAINING_ASSETS_20261112.md](REMAINING_ASSETS_20261112.md).
The dated results below describe the original pass.

Follow-up: the successful native geometry/timing checks below missed a provider
coverage defect over Carrollton: valid-sized images from `s2cloudless-2017` were
entirely white. See [EARTH_WHITE_FIX.md](EARTH_WHITE_FIX.md) for the reproduction,
correct global layer, blank-response guard and actual Quest compositor checks.

Changes: expanded-globe close zoom with screen/head clearance; adaptive
18/4/1-degree satellite regions; progressive 512/2048/4096px presentation;
parallel 1K exports; cancellable pooled HTTP; independently loaded geographic
mountain normals; dense Blender-authored close surface.

Sources checked:
- https://cloudless.eox.at/license-non-commercial (2016/2017 mosaic CC BY 4.0)
- https://new.eox.at/2017/08/sentinel-2-global-cloudless-mosaic/
- https://cloudless.eox.at/documentation/usage
- https://registry.opendata.aws/terrain-tiles/
- https://github.com/tilezen/joerd/blob/master/docs/formats.md
- https://github.com/tilezen/joerd/blob/master/docs/attribution.md

Blender source was saved separately before editing. The final GLB was parsed
and has exactly one mesh, one node, no image payloads, and normalized radius
within 1.4e-7 of one. The added geometry is 260,096 triangles; this is a smooth
carrier for real satellite textures and elevation normals, not a mountain DEM
mesh. Source authoring script: art/scripts/build_earth_close_surface.py.

An initial single 4096px EOX WMS export for an Alps fixture took 44.81 seconds
on the host. A quarter-size geographic request at the identical source pixel
spacing took 3.17 seconds. These are request observations, not an apples-to-apples
hardware benchmark. The first split-request Quest run delivered full imagery
in 13.3 seconds (105 ms final texture creation/bind call), with a preview shown
first. Cancellation and an independent intermediate stage were then added.
Final hardware checks are recorded below. The screenshots returned by Android
screencap for immersive content are black, so source/UI fixtures and native
geographic-hit diagnostics are used for automated evidence; perceived headset
visual quality remains a hands-on assessment.

Final automated checks: **206 tests passed**, zero failures/errors,
lint zero errors (49 warnings, primarily existing dependency/style advisories).
Tests cover adaptive/date-line bounds, ordered progressive presentation, exact
placement of all sixteen full-detail image quadrants, raw-byte caching, invalid
service responses, prompt cancellation of four hung requests, geographic
centering at expanded scale, head/screen clearance, close visitor inspection,
reset and the existing playback/weather/location/interaction paths.

Final cold-cache Quest run used a public Patagonia fixture: first preview
1.94 s, intermediate 2.946 s, final 4096px 5.679 s, mountain normals 6.985 s.
Network/cache counters showed real downloads and zero disk-cache hits. A repeat
with the same local cache showed preview 0.174 s, intermediate 0.372 s, final
0.600 s and normals 0.731 s, with zero network requests. Timing includes the
150 ms settle interval. Final 4K texture creation/bind took approximately
111–118 ms on the main thread, so a brief final-upload hitch remains possible.
These are single-region measurements; network/provider performance varies.
Settled cold-run PSS was about 1.31 GiB; this is not a movie-playback peak.

The new close surface passed ten native imported-mesh UV ray tests at maximum
zoom, including Carrollton, California, equatorial and northern fixtures plus
47 degrees of manual spin. Sampling differences were within the existing
0.1-degree tolerance; do not interpret float-based zero error logs as exact
coordinate precision. Native pipeline/binding and crash scans showed no errors.

Final APK SHA-256: `5f917d1896d3c926172f0ac9aa3721e6716ea6723e3263aa660833c88be77f92`.
Installed base APK hash matched. The app is returned to a normal launch after
the diagnostics; fixture locations, zoom and clear mode are not persisted.

The cold timing run preceded the final visitor collision-guard adjustment;
streaming and rendering code were identical. The cached benchmark and native
maximum-zoom UV checks were repeated on the final installed APK. Cold-run APK
identity and final installed identity are recorded separately in proof.json.
