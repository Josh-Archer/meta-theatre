# ISS surface materials (issue #12)

Runtime asset: `app/src/main/assets/iss.glb` (name unchanged; byte-identical copy in `art/exports/flybys/iss.glb`).
Editable source: `art/blender/orbital_visitors.blend` (collection `ISS | Quest optimized`); the pre-change
source is kept as `art/blender/before_iss_materials_20261116.blend`. Authored maps: `art/textures/iss/`.

## Rebuild

```
blender -b art/blender/before_iss_materials_20261116.blend --python art/scripts/author_iss_materials.py -- --export /var/tmp/iss_raw.glb
gltf-transform prune /var/tmp/iss_raw.glb /var/tmp/iss.glb        # verified lossless cleanup
python3 art/scripts/fix_glb_tangents.py /var/tmp/iss.glb          # 62 zero-length tangents on degenerate UV triangles
cp /var/tmp/iss.glb app/src/main/assets/iss.glb && cp /var/tmp/iss.glb art/exports/flybys/iss.glb
NODE_PATH=<gltf-validator install>/node_modules node art/scripts/validate_space_visitors.cjs
blender -b --python art/scripts/render_iss_materials.py -- app/src/main/assets/iss.glb art/renders/iss/after
```

`art/scripts/build_space_visitors.py` still creates the geometry and the four slots; `author_iss_materials.py` only
replaces slot shading and UVs. Geometry, slot order and photovoltaic UVs are unchanged.

## Material content

| Slot | Maps (base color / normal / metal-rough) | Content |
| --- | --- | --- |
| 0 Thermal blanket ivory | 512 JPG / 512 PNG / 512 JPG | 4 m tile, 1 m quilt seams with tape, soft puffing, broad wrinkles |
| 1 Brushed aluminium | 512 JPG / 512 PNG / 512 JPG | 2 m tile, directional brushing, patchy roughness, 0.5 m panel seams |
| 2 Gold insulation | 512 JPG / 512 PNG / 512 JPG | 3 m tile, restrained crinkled-foil creases, tint and roughness variation |
| 3 Photovoltaic cells | 1024 PNG / 512 PNG / 256 PNG | same 12x6 cell layout and borders as before, per-cell tone, busbars, fingers, coating falloff |

Slots 0-2 use box-projected UVs (dominant face axis); slot 3 UVs are untouched. Mean base color, roughness and
metalness of each slot match the previous material factors, so overall tone should not shift.

## Budget (asset bytes and estimated memory, not on-device performance)

| | Before | After |
| --- | --- | --- |
| Triangles / draw calls / slots | 37,865 / 4 / 4 | 37,865 / 4 / 4 |
| Dimensions (m, Blender axes) | 109.0 x 71.655 x 14.644 | identical |
| GLB bytes | 1,262,484 | 4,224,428 |
| Vertices (UV seams, tangents) | 31,711 | 50,571 |
| Textures | 1 x 1024^2 | 12 maps, see above |
| Texture memory, RGBA8 + full mips (x4/3) | 5.33 MiB | 19.00 MiB |
| Vertex buffer (interleaved) | 1.01 MB | 2.43 MB |

Texture arithmetic: slots 0-2 are 3 x (512^2 x 4 B) x 3 maps = 9 MiB; slot 3 is 4 + 1 + 0.25 MiB; 14.25 MiB x 4/3 = 19.0 MiB.
Added estimate is about 13.7 MiB textures plus 1.4 MB vertex data. The estimate assumes the runtime uploads RGBA8;
it is not a measurement.

## Lossless cleanup

`gltf-transform prune` is the only cleanup applied. On the previous asset it removed unreferenced accessors and the
UVs of textureless primitives (1,262,484 to 1,048,724 bytes). Every slot is now textured, so it removes only 364 bytes.
Pruned output was compared with the raw export: all POSITION/NORMAL/TEXCOORD/TANGENT/index buffers, image bytes,
material factors and texture/sampler bindings are identical. `fix_glb_tangents.py` is not cleanup; it repairs 62
zero-length tangents that the validator flags as errors.
