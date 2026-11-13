# Celestial asset and lighting pass — 2026-11-11

Current integration and automated Quest results are recorded in
[REMAINING_ASSETS_20261112.md](REMAINING_ASSETS_20261112.md).
The dated results below describe the original pass.

The native app now uses an updated Moon, distinct rocky/cloud-planet lighting,
and a refined 8K Milky Way. The build is installed on Quest 3 and its installed
APK hash matches the local build. Android user 0 is still `RUNNING_LOCKED`, so
appearance, stereo comfort, startup memory and frame timing remain unverified
on the headset. Blender previews and desktop shader tests are separate evidence.

## Visual changes

- **Moon:** the December 2025 LROC color map replaces the previous color map.
  LOLA elevation supplies tangent-space normals, with physical slopes at 1×
  strength. Crater relief follows the moving Sun; no shadows are baked into the
  new normal map. The sphere silhouette and 1,737.4 km mean-radius scale remain
  unchanged. Runtime color and normal maps are both 2048 × 1024.
- **Planets:** Mercury, Mars and the Moon use a bounded Lunar-Lambert response.
  Venus and the gas/ice giants use restrained Minnaert limb shading and a small
  sunlit haze contribution within the existing opaque pass. These coefficients
  are artistic choices, not measured or fitted planetary photometry. Earth
  retains its existing separate terrain/cloud/atmosphere system.
- **Milky Way:** the existing NASA 8192 × 4096 layers retain their celestial
  registration. The diffuse contrast curve is adjusted, and a peak-channel
  shoulder preserves stellar color without clipping individual RGB channels.
  The sky vertex shader now follows eye translation while retaining world-frame
  rotation, so exploration cannot introduce finite-distance stellar parallax.
- Saturn's ring gaps and directional shadows remain active. Sun limb shading
  is pixel-identical in the before/after desktop test. Body sizes, placement,
  lunar phase geometry, shared sunlight and runtime meshes are retained.

Sources: [NASA CGI Moon Kit](https://svs.gsfc.nasa.gov/4720/),
[NASA Deep Star Maps 2020](https://svs.gsfc.nasa.gov/4851/), and
[USGS photometric models](https://isis.astrogeology.usgs.gov/8.1.0/Application/presentation/PrinterFriendly/photemplate/photemplate.html).
The other planet textures remain the credited Solar System Scope maps.
NASA's lunar color product includes aesthetic exposure/white-balance and polar
gap filling; it is not an unprocessed scientific reflectance measurement.

## Editable assets and matched previews

The versioned source is [celestial_20261111.blend](../blender/celestial_20261111.blend).
It contains the earlier solar library, packed maps, editable material nodes,
matched comparison scenes and a neutral export scene. Earlier `.blend` sources
are preserved. Original NASA TIFFs are under `../textures/solar/nasa/`; original
star EXRs remain under `../textures/space/source/`.

Authored PNG masters and JPEG outputs are in `../textures/celestial_20261111/`.
The canonical solar/space texture folders and native runtime files contain the
reviewed outputs. JPEGs use quality 98 with 4:4:4 sampling. Normal data is PNG.

Matched Blender authoring previews, **not headset captures**:

| Asset | Before | After |
| --- | --- | --- |
| Moon | [Before](../renders/celestial_20261111/moon_before.png) | [After](../renders/celestial_20261111/moon_after.png) |
| Jupiter | [Before](../renders/celestial_20261111/jupiter_before.png) | [After](../renders/celestial_20261111/jupiter_after.png) |
| Mars | [Before](../renders/celestial_20261111/mars_before.png) | [After](../renders/celestial_20261111/mars_after.png) |
| Galactic center | [Before](../renders/celestial_20261111/sky_core_before.png) | [After](../renders/celestial_20261111/sky_core_after.png) |
| Cygnus | [Before](../renders/celestial_20261111/sky_cygnus_before.png) | [After](../renders/celestial_20261111/sky_cygnus_after.png) |

## Runtime cost and validation

- No extra draw calls or triangles. Shared planet mesh: 9,024 triangles; sky:
  3,968. No texture resolution reductions or mesh simplification.
- One new lunar normal texture: **8 MiB base RGBA8, approximately 10.67 MiB
  with a full mip chain**. One additional texture sample plus a derivative
  tangent frame on the Moon only. Other bodies skip the normal branch; rocky
  profiles skip the Minnaert power operation. Planet lighting adds arithmetic;
  this pass does not establish a frame-rate gain.
- Sky still uses one draw and one fragment texture lookup. No additional sky
  texture, bloom, particles or transparent atmospheric shell.
- Solar runtime files total 41,129,188 bytes, up from 37,527,230 (+3,601,958).
  The new sky JPEG is 32,313,294 bytes. JPEG encoding fidelity: Moon 46.41 dB,
  sky 45.45 dB PSNR versus the lossless masters. Authored sky clipping: zero.
- `testDebugUnitTest assembleDebug lintDebug`: **219 tests passed**, no failed,
  errored or skipped tests. Lint: zero errors, 57 warnings. The final shader
  optimization and credit update were followed by another successful assembly.
- Actual GLSL executed with an SDK transform/color shim on RTX 5080 EGL:
  **80 body/phase/polar cases**, finite bounded output, monotonic phase brightness,
  active normal shading, preserved Saturn shadows and exact Sun-output parity.
  Three translated-camera transform-feedback cases verify the actual sky vertex
  shader. Linear mip filtering and sRGB normal-data recovery are exercised.
- APK entries match the three published textures and attribution file byte for
  byte. APK and installed package SHA-256:
  `6f343d80513051eac51eec30b5c8aeeb2cc65c6c1ac11980facf8a98a659009f`.
  Installation succeeded; normal app launch awaits user unlock.

The existing Unreal 5.8.3 asset lab contains `/Game/MetaTheatre/Celestial20261111`:
10 textures, 9 materials and two meshes. Import, material assignments, texture
sizes, zero LOD bias, disabled Nanite, saving and fresh-process reload were
verified. The open level was preserved. Moon normals use Unreal's normal-map
compression and green-channel conversion; the sky is unlit at exposure 0.8.
The other materials are editable PBR review references, not ports of the native
photometric GLSL. NullRHI validation does not prove Unreal rendered appearance.
The editor logs retain unrelated Oculus passthrough/GameFeature configuration
warnings; both task scripts completed and their assertions passed.

## Reproduction

1. Preserve prior sources. Start live Blender in online mode and confirm its
   object summary. Run `art/scripts/refine_celestial_assets.py` through the bridge.
2. Run `art/scripts/encode_celestial_maps.py` to encode the authored PNGs without
   resizing or changing pixels beyond JPEG encoding.
3. Run `art/scripts/render_celestial_comparison.py` through live Blender. Inspect
   the matched previews before publishing. This also exports the neutral FBX.
4. Run `art/scripts/check_celestial_shaders.py` with NumPy, Pillow and ModernGL.
   It exercises the actual GLSL and checks source/output encoding fidelity.
5. Copy reviewed `moon.jpg`, `moon_normal.png` and `stars.jpg` into
   `app/src/main/assets/solar/`, and synchronize the canonical art texture copies.
6. Run `art/scripts/author_celestial_unreal.py` with the existing asset-lab
   launcher. Its scope is the versioned folder above; it preserves the level.
7. Build with JDK 17, verify APK entries, install and verify the installed hash.
   After normal Quest unlock, verify crater orientation, both eyes, star stability,
   all lighting phases and representative frame timings with video playback.

Raw logs, the baseline checkpoint and machine/device JSON evidence are kept in
the ignored `art/validation/celestial-20261111/` directory. The earlier chair,
Roadster and quality-preserving performance changes are documented separately in
[REALISM_PERFORMANCE_20261111.md](REALISM_PERFORMANCE_20261111.md).
