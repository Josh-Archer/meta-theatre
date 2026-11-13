"""ISS-only material authoring in the live Blender MCP session.

Input is the preserved production GLB: no simplification or geometry edits.
Run finalize_iss_glb.mjs afterward to retain the production split normals
exactly, then the lossless optimizer. Exports stay staged until validation.
The original NASA reference and component-based orbital_visitors.blend survive.
"""
import json
from pathlib import Path

import bpy
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
TAG = 'iss_20261112'
OUT = ROOT / 'art/exports' / TAG
TEX = ROOT / 'art/textures' / TAG
OUT.mkdir(parents=True, exist_ok=True)
TEX.mkdir(parents=True, exist_ok=True)
scene = bpy.data.scenes.new('ISS | Surface materials 20261112')
bpy.context.window.scene = scene
bpy.ops.import_scene.gltf(filepath=str(OUT / 'iss_before.glb'))
obj = next(o for o in scene.objects if o.type == 'MESH')
obj.name = 'iss'
assert len(obj.data.polygons) == 37865
assert len(obj.data.materials) == 4


def image(name, values):
    h, w = values.shape[:2]
    im = bpy.data.images.new(name, width=w, height=h, alpha=False)
    im.colorspace_settings.name = 'Non-Color'
    rgba = np.ones((h, w, 4), dtype=np.float32)
    rgba[:, :, :3] = values
    im.pixels.foreach_set(rgba.ravel())
    im.filepath_raw = str(TEX / (name + '.png'))
    im.file_format = 'PNG'
    im.save()
    im.pack()
    return im


def normal(height, strength):
    dx = (np.roll(height, -1, 1) - np.roll(height, 1, 1)) * strength
    dy = (np.roll(height, -1, 0) - np.roll(height, 1, 0)) * strength
    v = np.stack((-dx, -dy, np.ones_like(dx)), axis=2)
    return v / np.linalg.norm(v, axis=2, keepdims=True) * .5 + .5


def orm(name, roughness, metal):
    # Average 2x2 samples for the lower-bandwidth parameter map. The authored
    # color texture stays at its original 1024 resolution, byte-for-byte.
    roughness = roughness.reshape(256, 2, 256, 2).mean(axis=(1, 3))
    metal = np.broadcast_to(metal, (512, 512)).reshape(256, 2, 256, 2).mean(axis=(1, 3))
    return image(name, np.stack((np.ones_like(roughness), roughness, metal), axis=2))


def attach(mat, parameter_map, normal_map=None):
    tree = mat.node_tree
    p = next(n for n in tree.nodes if n.type == 'BSDF_PRINCIPLED')
    tex = tree.nodes.new('ShaderNodeTexImage'); tex.image = parameter_map
    separate = tree.nodes.new('ShaderNodeSeparateColor')
    tree.links.new(tex.outputs['Color'], separate.inputs['Color'])
    tree.links.new(separate.outputs['Green'], p.inputs['Roughness'])
    tree.links.new(separate.outputs['Blue'], p.inputs['Metallic'])
    if normal_map:
        tex = tree.nodes.new('ShaderNodeTexImage'); tex.image = normal_map
        bump = tree.nodes.new('ShaderNodeNormalMap')
        bump.inputs['Strength'].default_value = 1
        tree.links.new(tex.outputs['Color'], bump.inputs['Color'])
        tree.links.new(bump.outputs['Normal'], p.inputs['Normal'])
    return p


# Seamless periodic height fields represent surface response, not painted light.
y, x = np.mgrid[:512, :512].astype(np.float32) / 512
rng = np.random.default_rng(20261112)
fold = np.zeros_like(x)
for frequency, amplitude in [(3, .34), (9, .22), (23, .12), (51, .045)]:
    for unused in range(8):
        fx, fy = rng.integers(1, frequency + 1, 2)
        phase = rng.uniform(0, 2*np.pi)
        fold += amplitude / 8 * np.sin(2*np.pi*(fx*x + fy*y) + phase)
# A shallow stitched overlap in each 1.5 m blanket repeat, with broad creases.
seam = np.exp(-(np.minimum(y, 1-y) / .008)**2)
blanket_height = fold*.45 - seam*.035
blanket_normal = image('blanket_normal', normal(blanket_height, 1.5))
foil_normal = image('foil_normal', normal(fold, 4.5))
blanket_orm = orm('blanket_orm', np.clip(.57 + fold*.14 + seam*.06, .49, .66), 0)
foil_orm = orm('foil_orm', np.clip(.34 + fold*.3, .24, .45), .83)
brush = .025*np.sin(2*np.pi*y*121) + .018*np.sin(2*np.pi*y*57 + np.sin(2*np.pi*x*3))
metal_orm = orm('aluminium_orm', np.clip(.32 + brush + fold*.05, .26, .39), .87)
# Cell coating follows the EXISTING 12x6 color-grid UVs exactly. No new tint,
# extra clearcoat extension or extra draw is required in the native PBR path.
u = np.mod(x*12, 1); v = np.mod(y*6, 1)
edge = np.minimum(np.minimum(u, 1-u), np.minimum(v, 1-v))
border = np.clip((.043-edge)/.015, 0, 1)
cell_normal = image('cell_normal', normal(-border*.018, .7))
cell_orm = orm('cell_orm', .27 + border*.17 + .012*np.sin(2*np.pi*x*12), .12 + border*.23)
materials = list(obj.data.materials)
for i, old in enumerate(materials):
    mat = old.copy(); obj.data.materials[i] = mat
    if 'Thermal' in mat.name:
        p = attach(mat, blanket_orm, blanket_normal)
        p.inputs['Base Color'].default_value = (.74, .75, .72, 1)
    elif 'Brushed' in mat.name:
        attach(mat, metal_orm)
    elif 'Gold' in mat.name:
        p = attach(mat, foil_orm, foil_normal)
        p.inputs['Base Color'].default_value = (.57, .36, .105, 1)
    elif 'Photovoltaic' in mat.name:
        attach(mat, cell_orm, cell_normal)
    else:
        raise ValueError(mat.name)

# The old untextured materials had XY projection, degenerate on vertical faces.
# Per-face dominant-axis projection supplies UVs without touching positions or
# split normals. Solar array UVs and the original color image are untouched.
uv = obj.data.uv_layers.active
for poly in obj.data.polygons:
    if 'Photovoltaic' in obj.data.materials[poly.material_index].name:
        continue
    axis = max(range(3), key=lambda i: abs(poly.normal[i]))
    axes = [i for i in range(3) if i != axis]
    repeat_m = 1.5 if poly.material_index == 0 else .8
    for li in poly.loop_indices:
        co = obj.data.vertices[obj.data.loops[li].vertex_index].co
        uv.data[li].uv = (co[axes[0]]/repeat_m, co[axes[1]]/repeat_m)

scene['source'] = 'NASA / Michael D. Carbajal ISS B; preserved production triangles and split normals'
scene['source_components'] = 'orbital_visitors.blend / ISS | Quest optimized'
scene['material_notes'] = 'Procedural response maps; no geometry displacement or baked illumination'
bpy.ops.object.select_all(action='DESELECT'); obj.select_set(True)
bpy.context.view_layer.objects.active = obj
bpy.ops.export_scene.gltf(filepath=str(OUT/'iss.glb'), export_format='GLB',
    use_selection=True, use_active_scene=True, export_apply=True, export_yup=True,
    export_texcoords=True, export_normals=True, export_tangents=True,
    export_materials='EXPORT', export_extras=True)
bpy.ops.export_scene.fbx(filepath=str(OUT/'iss.fbx'), use_selection=True,
    object_types={'MESH'}, apply_unit_scale=True, axis_forward='-Y', axis_up='Z',
    use_mesh_modifiers=True, bake_anim=False, path_mode='COPY', embed_textures=True)
for mat in obj.data.materials:
    for node in mat.node_tree.nodes:
        if node.type == 'TEX_IMAGE' and node.image:
            node.image.pack()
            if Path(node.image.filepath).is_absolute():
                node.image.filepath = bpy.path.relpath(node.image.filepath, start=str(ROOT/'art/blender'))
bpy.data.libraries.write(str(ROOT/'art/blender/iss_realism_20261112.blend'), {scene}, fake_user=True, compress=True)
result = {'triangles':len(obj.data.polygons), 'materials':len(obj.data.materials),
          'dimensions':list(obj.dimensions), 'staged_glb':str(OUT/'iss.glb'),
          'requires_finalization':'finalize_iss_glb.mjs then optimize_reviewed_glb.mjs'}
(OUT/'authoring.json').write_text(json.dumps(result, indent=2)+'\n')
