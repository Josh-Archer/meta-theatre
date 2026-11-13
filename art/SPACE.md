# Milky Way and space — 2026-10-19

The [November 11 celestial pass](validation/CELESTIAL_20261111.md) supersedes
the grade, runtime byte count and shader details below. It keeps the same 8K
NASA sources, refines star highlights and makes the sky follow camera translation.
Its versioned Blender source contains the current matched previews.

The native app now uses a reviewed 8192 × 4096 celestial sky. NASA's separate
Milky Way background and Hipparcos/Tycho bright-star layers let the diffuse
galactic structure be exposed independently of the foreground stars. The
previous 4096 × 2048 map applied a power of 1.4 to all catalog light together,
which suppressed faint structure. The new luminance curve reveals the rifts
and stellar density while keeping sparse regions dark and retaining source
star colors. Exposure is an artistic treatment, not calibrated naked-eye vision.

Source: [NASA SVS Deep Star Maps 2020](https://svs.gsfc.nasa.gov/4851/),
Ernie Wright; NASA/Goddard Space Flight Center Scientific Visualization Studio;
Gaia DR2: ESA/Gaia/DPAC. NASA's catalog imagery records stellar density and the
dark gaps between stars; this work does not add a volumetric dust simulation.

## Editable source and review

- `blender/before_milky_way_20261019.blend`: separate source checkpoint.
- `blender/milky_way.blend`: new review scene, central camera, editable dome,
  material and regeneration script. Earlier solar scenes remain available.
- `textures/space/source/`: unchanged 8K linear EXR layers, with local hashes
  recorded in `validation/milky_way_manifest.json`.
- `textures/space/runtime/stars.png`: lossless graded source; `stars.jpg` is
  quality 98, 4:4:4 RGB. Encoding PSNR against the PNG is 45.43 dB.
- `textures/space/before/stars_4k.jpg`: preserved previous runtime map.
- `renders/space/before_after.jpg`: matched camera, exposure and shader gain.
  `milky_way_core.png` and `cygnus_rift.png` are full review renders of the
  actual JPEG output. All are Blender authoring previews, not headset captures.

The final image retains the existing celestial-coordinate UV registration.
There is no added twinkle or translation parallax. Camera movement should not
make distant stars behave like nearby particles. The existing world-frame
rotation, sunlight, foreground planets and movie rendering remain in charge.

## Native output and cost

`app/src/main/assets/solar/stars.jpg` retains its runtime filename. The existing
`solar_stars.glb`, star shader, Kotlin renderer and sampler are unchanged:
3,968 triangles, one draw, one texture lookup, U repeat / V clamp, no depth
writes, background render order. The runtime JPEG is 32,069,784 bytes.

An uncompressed RGBA8 8K texture is 128 MiB at its base level, or approximately
170.67 MiB with a full mip chain. The old sky was approximately 42.67 MiB with
mips: **128 MiB additional estimated graphics memory**. A fresh inventory of
the current runtime files gives approximately **864.01 MiB for Earth plus
solar textures** with full mip chains: Earth 597.33 MiB and solar 266.68 MiB.
The earlier 416.01 MiB combined estimate reused an outdated Earth budget.
Earth now includes three 8K maps (day, night, clouds) and two 4K maps (normal,
ocean). Its separate older documentation does not reflect the current 8K
cloud map. Before this sky upgrade the same current Earth/solar set would
have used approximately 736.01 MiB under these assumptions.
These are allocation estimates, not measured Quest GPU memory. Decode buffers,
movie surfaces, the chair and other resources are additional. No source or
runtime texture was reduced below its previous resolution.

## Unreal asset sandbox

The existing MetaTheatreAssetLab project contains the reusable dome, 8K
texture and unlit `M_MilkyWay` material under
`/Game/MetaTheatre/Space/MilkyWay20261019`. `SkyExposure` defaults to 0.8,
matching the native star shader's multiplier. Texture sRGB is enabled,
maximum size is 8192, LOD bias is zero, and the mesh uses the saved material.
The imported geometry is `exports/space/milky_way_dome.fbx`.

Import, material assignment, saving and reloading are verified with NullRHI.
This does not establish Unreal rendered appearance, tone-map parity or Quest
performance. The open Unreal level was preserved. The native Android app
continues to consume its own sky texture; Unreal packages are sandbox assets.

## Regeneration

1. Preserve a separate Blender source before broad edits. Download the two
   original `milkyway_2020_8k.exr` and `hiptyc_2020_8k.exr` celestial layers
   from the NASA page into `textures/space/source/`.
2. From the solar asset library in live Blender, run
   `scripts/build_milky_way.py` through MCP with `__file__` set. Version or
   remove only the prior generated review scene first; the script refuses to
   overwrite it. Curve parameters are in the script and recorded on the scene.
3. Encode `stars.png` to RGB JPEG, quality 98, subsampling 0. Render matched
   before/after core views and a Cygnus view at 1800 × 1100 through live MCP.
4. Run `scripts/publish_milky_way.py` with NumPy and Pillow. It verifies
   dimensions, finite pixels, clipping, JPEG fidelity and review artifacts,
   preserves the previous native map, then copies the reviewed output.
5. For a new Unreal version, update the dated folder in
   `scripts/author_milky_way_unreal.py`, run it using the existing project
   launcher and `-ExecutePythonScript`, then reload and check using
   `scripts/validate_milky_way_unreal.py` with the same folder.
6. Build/test/lint with JDK 17. Check the embedded APK bytes against the
   published texture. Check startup, stereo, appearance and frame timing on Quest.

Local machine/device evidence stays in ignored `art/validation/` files. See
`validation/SPACE_VALIDATION.md` for this run's completed checks and limits.
