# Regional Earth surface submission

The packaged surface is `earth_dynamic_surface.glb`: 16,128 triangles, 8,387 vertices, 366,668 bytes, SHA-256 `6b4b883781dbe73c0552682ea8bc083e08f52164c3fd3e433bd2ec740789f1c3`. This checkout has no `earth_dynamic_surface_detail.glb`, no `earth_detail.frag` region discard, and no zoom gate in an `EarthDetailSession`. A 260,096-triangle sphere was not added. Close-up triangles stay on the authored vertices.

`EarthDetailSession` submits the authored triangles that face the viewer, plus a margin of two mesh edges (7.95° on this asset). That margin covers the horizon, a triangle that straddles it, and a small stereo/head shift. Vertices inside the horizon keep blend weight 1. Vertices in the margin feather from 1 to 0. The feather sits past the horizon, so the visible cap does not fade out and the single opaque surface is not punched open. Dateline vertices stay on their exported UVs. Poles use the same spherical test. A retained cap is reused only while it still covers the new horizon. Zoom is not a cutoff. Reachable zoom is about 0.75 to 1.28, so a 1.3 cutoff would sit above every legal view.

When the eye is inside the globe, the patch would contribute no exterior surface. The session hides it and submits the full authored mesh with the same day, night, normal, ocean and cloud maps. The next exterior view rebuilds from those same vertices.

Surface draw calls stay at 1. Clouds and atmosphere are unchanged, so Earth remains 3 draws. Counts below are CPU submissions from `EarthDetailSubmissionTest`, not GPU times.

| View | Submitted triangles | Triangles that meet the horizon | Draw |
| --- | ---: | ---: | ---: |
| Full authored sphere | 16,128 | 16,128 | 1 |
| Seat, zoom 1 | 3,474 | 2,620 | 1 |
| Close zoom | 2,184 | 1,544 | 1 |
| Wide zoom | 4,744 | 3,670 | 1 |
| North pole | 5,760 | 4,992 | 1 |
| South pole | 5,760 | 4,992 | 1 |
| Far side | 3,474 | 2,620 | 1 |
| Dateline seam | 3,605 | 2,724 | 1 |

Interior edges within 8° of the view center match the authored mesh exactly, including at close zoom. Quest 3 GPU frame time was not measured in this run.
