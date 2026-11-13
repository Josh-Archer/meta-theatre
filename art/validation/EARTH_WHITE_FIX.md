# Close Earth white imagery fix — 2026-10-21

Current integration and automated Quest results are recorded in
[REMAINING_ASSETS_20261112.md](REMAINING_ASSETS_20261112.md).
The dated results below describe the original pass.

The white close view was reproduced on Quest over the public Carrollton, GA
fixture with Clear Earth enabled. A Meta Cam compositor capture shows the
regional surface as a white rectangle with terrain-normal shading; the global
surface remains green outside it. Cached provider responses confirmed that the
2017 WMS layer returned exactly uniform RGB (255,255,255), including the preview
and all 1K tiles, with successful HTTP status and correct image dimensions.

The provider's `s2cloudless` layer returns textured imagery for the same bounds,
as well as the Alps and Tokyo fixtures. The app now requests that global
2016/2017 mosaic. Pixel resolution, progressive stages, concurrency, terrain
normals, original global maps, geometry and shaders are unchanged. Layer-specific
cache keys prevent reuse of the old white images. Decoding also rejects and
evicts exactly all-white no-data imagery, whether downloaded or already cached.
If coverage fails, the existing surface stays visible. Textured snow passes the
check; numerical terrain-normal tiles bypass it.

Primary provider references:

- https://tiles.maps.eox.at/wms?SERVICE=WMS&REQUEST=GetCapabilities
- https://cloudless.eox.at/license-non-commercial
- https://maps.eox.at/

The global 2016/2017 layer retains the existing CC BY 4.0 attribution. Newer
2018–2025 layers have different noncommercial/share-alike terms and are not
introduced by this fix.

Automated validation: 208 tests, zero failures/errors; lint zero errors
(50 warnings). New tests exercise fresh and cached white no-data rejection,
cache recovery with valid imagery, correct global layer selection, and acceptance
of bright snow with texture. APK SHA-256:
`70ac69321fc83748104ce1bbe57a42c16cfa725263f7f11775097197e0477ec9`.
The installed base APK hash matches the build.

Compositor captures, provider image samples, raw capability metadata and logs
are local ignored artifacts under `art/validation/earth-white-fix/`. Captures
with the headset asleep or the boundary dialog visible do not establish a
rendering pass. See the final view verification below.

Final view verification: `carrollton-after.jpg` is an actual Quest compositor
capture from the installed APK at 116.9× surface zoom, showing textured land,
roads and fields where `carrollton-awake.jpg` showed the white patch. The
diagnostic also passed geographic recentering, head/screen clearance and view
origin preservation. This verifies the reported daytime coverage failure;
night city-light resolution and subjective headset quality are separate limits.
The first corrected request showed its preview at 1.913 s, 2048px at 7.549 s and
4096px at 21.055 s (145 ms final upload). These are one network observation,
not a general speed guarantee; the small preview remains visible while refining.

On resuming the session, the installed hash was checked again and still matched.
The headset was locked, preventing an additional Alps capture and normal app
startup verification. Diagnostic launch requests were stopped, a normal launch
without extras was requested, and the proximity sensor and Guardian were
explicitly restored and verified enabled. Open Meta Theatre after unlocking.
