# ISS materials

The November 12 material pass implements the local asset work for
[#12](https://github.com/Josh-Archer/meta-theatre/issues/12). It adds restrained
thermal blanket creases and overlaps, foil wrinkles, brushed aluminum roughness
variation, and a solar-cell coating response. Four stock PBR materials remain;
no extra render layer, displacement, transparent surface or custom shader was
added. Physical scale, visitor placement, interaction and flyby timing are unchanged.

The NASA / Michael D. Carbajal geometry and its existing material assignments
remain the basis of the model. Surface response was reviewed against
[NASA solar-array photography](https://www.nasa.gov/image-article/solar-arrays-international-space-station/)
and [ESA station radiator photography](https://www.esa.int/media/international-space-station-radiators).
The new maps contain material data, not copied photographs or painted lighting.
Existing attribution remains in `app/src/main/assets/licenses/space-visitors.txt`.

## Sources and exports

- `blender/iss_realism_20261112.blend`: packed editable material source using
  the production geometry. The original component source in
  `blender/orbital_visitors.blend` is unchanged.
- `textures/iss_20261112/`: three 512² tangent-space normal maps and four 256²
  packed parameter maps (roughness in green, metallic in blue).
- `exports/iss_20261112/iss_before.glb`: preserved baseline for repeatable
  authoring and geometry comparisons.
- `exports/iss_20261112/iss.glb` and `iss.fbx`: staged interchange exports.
  The finalized GLB is authoritative for the native app.
- `exports/flybys/iss.glb` and `iss.fbx`: current canonical exports.
- `../app/src/main/assets/iss.glb`: current runtime asset, byte-identical to
  the finalized staged GLB.

The original 1024² photovoltaic color PNG and its UVs are byte/value identical.
The previously untextured materials now use dominant-axis UV projection to avoid
the old XY projection's collapsed UVs on vertical faces. No vertex positions,
split normals, triangle winding or material assignments change in the runtime GLB.

## Authoring and validation

Run `scripts/refine_iss_materials.py` in the live Blender MCP session with
`__file__` set. It creates a separate scene and packed source and stages exports;
it does not publish to the app. Do not rerun the original combined visitor
exporter: it would also overwrite the separately improved Roadster.

Blender quantizes some imported split normals. The finalization step restores
every indexed position and normal from the baseline, then generates MikkTSpace
tangents using the new UVs and exact original normals. Tangents are projected
onto the normal plane, with a finite orthonormal fallback for degenerate faces.
The subsequent optimizer welds only bit-identical complete vertices and checks
indexed attribute and image fingerprints before and after serialization.

From the repository root, with art dependencies installed outside the app:

```sh
node art/scripts/finalize_iss_glb.mjs art/validation/realism-20261111/gltf-tools/node_modules
node art/scripts/optimize_reviewed_glb.mjs art/validation/realism-20261111/gltf-tools/node_modules art/exports/iss_20261112/iss.glb
art/validation/realism-20261111/shader-venv/bin/python art/scripts/validate_iss_assets.py
```

The checked dependency versions are glTF-Transform 4.5.1 and mikktspace 1.1.1;
the Python audit uses NumPy and Pillow. Also run Khronos glTF Validator and
`scripts/render_iss_comparison.py` through Blender. Review the staged GLB before
copying it to the canonical export and runtime asset paths.

Matched Cycles previews use identical camera, single Sun, ambient light and
exposure: [station](renders/iss_20261112/station_after.png),
[modules before](renders/iss_20261112/modules_before.png),
[modules after](renders/iss_20261112/modules_after.png), and
[arrays](renders/iss_20261112/arrays_after.png). These are desktop previews.

`scripts/validate_iss_unreal.py` imports the final GLB into the existing sandbox
at `/Game/MetaTheatre/ISS20261112`, saves it, and verifies a fresh process reload.
Nanite is disabled; the import uses full-precision UVs and tangent basis.
It also exports render-section triangles for the Python multiset comparison.
NullRHI verifies imported content and geometry, not Unreal rendered appearance.

## Runtime cost

| Quantity | Before | After |
| --- | ---: | ---: |
| Triangles | 37,865 | 37,865 |
| Material primitives | 4 | 4 |
| Span | 109 m | 109 m |
| GLB bytes | 1,262,484 | 2,994,292 |
| Geometry buffer bytes | 1,241,942 | 2,476,456 |
| Estimated RGBA8 texture memory with mipmaps | 5.33 MiB | 10.67 MiB |

The material upgrade costs approximately 5.33 MiB of additional texture memory
and 1.18 MiB of stored geometry buffers for useful UV seams and tangents.
Packed parameter channels and omission of an unused aluminum tangent stream
limit that cost. Lossless welding reduces the expanded finalization stream from
6,006,324 to 2,994,292 bytes; that comparison is against the intermediate stream,
not the old asset. The final asset is larger than the original. No FPS improvement
has been measured or claimed.

See [validation evidence](validation/ISS_20261112.md) for the build, installed
APK and remaining headset check.
