# Earth and weather quality — 2026-10-17

This supersedes the earlier 4K/2K Earth texture budget and tiled live-weather
rendering described in the initial weather validation.

## What changed

- Day and city-light maps are **8192×4096**. The day map is byte-identical to the
  original attributed 8K source, not an enlarged 4K image. City emission is
  extracted from the original 8K night source with channel-specific linear-space
  background removal, then encoded at JPEG quality 98 with no chroma subsampling.
- Offline/live clouds and the ocean mask are **4096×2048**, up from 2048×1024.
  The existing 4096×2048 terrain normal map is unchanged.
- Live weather uses continuous global morphology, warped fronts and multiscale
  erosion instead of repeating the original eight-tile atlas. A small spherical
  field interpolates the 49 current readings separately from high-resolution
  detail. Fully overcast regions retain optical-depth variation instead of
  clipping to a uniform white patch. Longitude wrapping and polar convergence
  avoid rectangular stamp borders and polar pinching.
- Cloud gradients provide directional relief; a density sample adds self-shadow.
  The surface shares the active cloud texture and receives directional cloud
  shadows. Ocean highlights use view-dependent Fresnel. Shared day/night timing,
  atmosphere and the three existing Earth meshes are preserved.
- All other scene/model assets remain unchanged, including ISS, Roadster and chair.

## Source and authoring checks

`prepare_detailed_earth.py` uses the existing attributed 8K sources.
`author_cloud_structure.py` authors the continuous field through Blender MCP.
`author_detailed_earth_preview.py` uses preserved dynamic Earth geometry to create
separate editable lighting references, including physical cloud shadows and
surface detail. It accepts `PREVIEW_SUN`, `PREVIEW_NAME` and `SAVE_SOURCE` overrides
for reproducible day/night references. `sanitize_earth_previews.py` strips private
render metadata and verifies that every image pixel is unchanged.

The interactive Blender bridge disconnected during field authoring. Subsequent
source generation and renders used the configured background Blender MCP tools.
Both new editable sources reopen; the Earth reference resolves all five texture
paths, and the continuous field resolves its authored image. The older Earth
and weather sources remain unchanged, with local pre-edit checkpoints preserved.

Day and night references were inspected, along with the generated overcast cloud
map. They are **authoring references, not headset captures or pixel-identical
previews of the native shader**. Relative paths and scene-only serialization
exclude workstation UI/file-browser history. Private paths were checked in
uncompressed Blender data, exports, image metadata and changed text files.

## Build and headset checks

JDK 17 `testDebugUnitTest assembleDebug lintDebug` passed: **165 tests, zero
failures/errors; lint zero errors and 42 existing warnings**. Custom shader
variants compile with the extra surface cloud sampler and cloud relief/shadow
samples. The existing clock, polar exploration, interaction, playback and weather
integration tests pass. Added/strengthened integration checks validate original
8K/4K asset bounds and optical-depth variation under full overcast, as well as
seam/pole handling and unchanged unsampled geography.

All **37 runtime asset files** match their APK entries. Thirty of the prior 36
assets are unchanged; the changes are four Earth textures and their two attribution
files, plus the new continuous cloud structure. Earth meshes are unchanged.

The final APK was installed with replacement install, preserving app data, and
the installed base APK hash matched the local build:

`1dbfefdeec9a410f3001ee0868aa0fa77cff59aa0613a3980c6ccb15ebfbb8d1`

On Quest the upgraded native renderer initialized and applied all 49 current
weather readings without a fatal exception. The weather diagnostic is in-memory
and does not overwrite the saved weather preference or Plex state. The normal
application was relaunched afterward.

Observed app memory before the upgrade was 727,106 KiB PSS / 842,566 KiB RSS;
after loading the upgraded renderer with live weather, it was 1,050,771 KiB PSS /
1,163,226 KiB RSS. Reported graphics memory changed from 497,686 KiB to 827,730 KiB.
These are representative process snapshots, not a controlled benchmark or GPU
frame-time measurement. The higher texture detail deliberately consumes more
memory; no geometry, source detail or video quality was reduced to offset it.
Static Earth RGBA8+mipmap allocation is estimated at 469.3 MiB; the active live
cloud texture adds an estimated 42.7 MiB. Earth remains three draws, with five
surface texture reads and six cloud texture reads instead of four and one.

Stereo appearance, perceived sharpness, night visibility, and sustained frame
rate while playing a high-resolution movie still require review in the headset.
Native initialization/upload logs and desktop reference renders do not establish
those perceptual or performance results. Raw logs, memory snapshots, API data,
APK files and pre-edit checkpoints remain local and ignored by Git.
