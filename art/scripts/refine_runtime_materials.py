"""Quality-preserving material pass; run in the live Blender MCP session.

Load separate editable sources, stage exports for review, and leave runtime publication
explicit. Existing sources and all geometry remain intact. No subdivision/decimation.
"""
import bpy
import json
import numpy as np
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TAG = 'realism_20261111'
OUT = ROOT / 'art/exports' / TAG
TEX = ROOT / 'art/textures' / TAG
OUT.mkdir(parents=True, exist_ok=True)
TEX.mkdir(parents=True, exist_ok=True)


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


def normals(height, strength):
    dx = (np.roll(height, -1, 1) - np.roll(height, 1, 1)) * strength
    dy = (np.roll(height, -1, 0) - np.roll(height, 1, 0)) * strength
    value = np.stack((-dx, -dy, np.ones_like(dx)), axis=2)
    return value / np.linalg.norm(value, axis=2, keepdims=True) * .5 + .5


# Seamless multiscale microrelief, shared by rubber, leather, and woven suit fabric.
# These are material data maps, with no painted directional highlights.
rng = np.random.default_rng(20261111)
n = 512
y, x = np.mgrid[:n, :n].astype(np.float32) / n
height = np.zeros_like(x)
for frequency, amplitude in [(9, .4), (31, .25), (81, .15), (181, .08)]:
    for unused in range(6):
        fx, fy = rng.integers(1, frequency + 1, size=2)
        height += amplitude / 6 * np.sin(2*np.pi*(fx*x + fy*y) + rng.uniform(0, 2*np.pi))
micro_normal = image('fine_grain_normal', normals(height, .9))
rough = np.clip(.70 + height*.18, .58, .82)
micro_orm = image('fine_grain_orm', np.stack((np.ones_like(x), rough, np.zeros_like(x)), axis=2))
weave_height = .18*np.sin(2*np.pi*x*64) * np.cos(2*np.pi*y*64)
weave_normal = image('carbon_weave_normal', normals(weave_height, .18))
weave_orm = image('carbon_weave_orm', np.stack((np.ones_like(x), .34 + weave_height*.15, np.zeros_like(x)), axis=2))


def map_node(material, im):
    node = material.node_tree.nodes.new('ShaderNodeTexImage')
    node.image = im
    return node


def detail(material, normal, orm, strength):
    tree = material.node_tree
    p = tree.nodes.get('Principled BSDF')
    source = map_node(material, normal)
    nm = tree.nodes.new('ShaderNodeNormalMap')
    nm.inputs['Strength'].default_value = strength
    tree.links.new(source.outputs['Color'], nm.inputs['Color'])
    tree.links.new(nm.outputs['Normal'], p.inputs['Normal'])
    channels = tree.nodes.new('ShaderNodeSeparateColor')
    tree.links.new(map_node(material, orm).outputs['Color'], channels.inputs['Color'])
    tree.links.new(channels.outputs['Green'], p.inputs['Roughness'])
    tree.links.new(channels.outputs['Blue'], p.inputs['Metallic'])


def export_asset(name, source_name, collection_prefix):
    with bpy.data.libraries.load(str(ROOT / 'art/blender' / source_name)) as (source, target):
        target.scenes = source.scenes
    scene = next(s for s in target.scenes if any(c.name.startswith(collection_prefix) for c in s.collection.children))
    scene.name = name + ' | Material refinement 20261111'
    bpy.context.window.scene = scene
    collection = next(c for c in scene.collection.children if c.name.startswith(collection_prefix))
    materials = {}
    for obj in collection.all_objects:
        for slot in obj.material_slots:
            old = slot.material
            if old and old.name not in materials:
                materials[old.name] = old.copy()
            if old:
                slot.material = materials[old.name]
    if name == 'space_roadster':
        for label, mat in materials.items():
            p = mat.node_tree.nodes.get('Principled BSDF')
            if 'Cherry red' in label:
                # Pigmented automotive paint is a dielectric; the clearcoat remains.
                p.inputs['Metallic'].default_value = 0
                p.inputs['Roughness'].default_value = .26
                p.inputs['Coat Roughness'].default_value = .19
            elif 'Tire rubber' in label:
                detail(mat, micro_normal, micro_orm, .55)
            elif 'Black leather' in label:
                detail(mat, micro_normal, micro_orm, .25)
            elif 'Carbon trim' in label:
                detail(mat, weave_normal, weave_orm, .45)
            # Suit and headlights deliberately share a material; don't make lenses fabric.
        for obj in collection.all_objects:
            if obj.type != 'MESH':
                continue
            mat = obj.data.materials[0] if obj.data.materials else None
            if mat and any(t in mat.name for t in ['Tire rubber', 'Black leather']):
                uv = obj.data.uv_layers.active or obj.data.uv_layers.new(name='Physical grain')
                # Dominant-axis box projection; keep per-face UVs and normals independent.
                for poly in obj.data.polygons:
                    axis = max(range(3), key=lambda i: abs(poly.normal[i]))
                    axes = [i for i in range(3) if i != axis]
                    for li in poly.loop_indices:
                        co = obj.matrix_world @ obj.data.vertices[obj.data.loops[li].vertex_index].co
                        uv.data[li].uv = (co[axes[0]] * 12, co[axes[1]] * 12)
            # Freeze smooth quad normals before export triangulation. Otherwise the
            # triangulation diagonal changes glossy shading on compound body panels.
            if obj.data.polygons and all(p.use_smooth for p in obj.data.polygons):
                obj.data.normals_split_custom_set([tuple(n.vector) for n in obj.data.corner_normals])
    else:
        for label, mat in materials.items():
            p = mat.node_tree.nodes.get('Principled BSDF')
            if 'Saddle leather' in label:
                for node in mat.node_tree.nodes:
                    if node.type == 'NORMAL_MAP':
                        node.inputs['Strength'].default_value = .65
                    if node.type == 'TEX_IMAGE' and node.image and 'roughness' in node.image.name:
                        old = node.image
                        w, h = old.size
                        pixels = np.empty(w*h*4, np.float32)
                        old.pixels.foreach_get(pixels)
                        pixels = pixels.reshape(h,w,4)
                        yy,xx = np.mgrid[:h,:w].astype(np.float32)
                        # Subtle finish variation, retaining all existing grain pixels.
                        variation = .018*np.sin(xx*2*np.pi/w*7)*np.cos(yy*2*np.pi/h*11)
                        values = np.clip(pixels[:,:,:3] + .09 + variation[:,:,None], 0, 1)
                        node.image = image('saddle_leather_roughness', values)
            elif 'Oiled walnut' in label:
                p.inputs['Roughness'].default_value = .43
                for node in mat.node_tree.nodes:
                    if node.type == 'NORMAL_MAP':
                        node.inputs['Strength'].default_value = .65
    bpy.context.view_layer.update()
    deps = bpy.context.evaluated_depsgraph_get()
    copies = []
    for obj in collection.all_objects:
        if obj.type not in {'MESH','FONT','CURVE'}:
            continue
        mesh = bpy.data.meshes.new_from_object(obj.evaluated_get(deps), depsgraph=deps)
        cp = bpy.data.objects.new(obj.name + ' export', mesh)
        scene.collection.objects.link(cp)
        cp.matrix_world = obj.matrix_world.copy()
        if name == 'lounge_chair':
            # The original chair exporter welds cushion poles at this tolerance.
            import bmesh
            bm = bmesh.new(); bm.from_mesh(mesh)
            bmesh.ops.remove_doubles(bm, verts=list(bm.verts), dist=.00001)
            bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
            bm.to_mesh(mesh); bm.free()
        copies.append(cp)
    # One consistently named UV set avoids empty channels when joining objects.
    for obj in copies:
        uv = obj.data.uv_layers.active or obj.data.uv_layers.new()
        uv.name = 'UVMap'
    bpy.ops.object.select_all(action='DESELECT')
    for obj in copies: obj.select_set(True)
    bpy.context.view_layer.objects.active = copies[0]
    bpy.ops.object.join()
    joined = bpy.context.object
    joined.name = name
    triangulate = joined.modifiers.new('Runtime triangles with authored normals', 'TRIANGULATE')
    triangulate.keep_custom_normals = True
    bpy.ops.object.modifier_apply(modifier=triangulate.name)
    joined.data.calc_loop_triangles()
    report = {'triangles': len(joined.data.loop_triangles), 'vertices': len(joined.data.vertices),
              'bounds': [list(v) for v in joined.bound_box], 'materials': len(joined.data.materials)}
    bpy.ops.export_scene.gltf(filepath=str(OUT/(name+'.glb')), export_format='GLB',
        use_selection=True, use_active_scene=True, export_apply=True, export_yup=True,
        export_texcoords=True, export_normals=True, export_tangents=True, export_materials='EXPORT')
    bpy.ops.export_scene.fbx(filepath=str(OUT/(name+'.fbx')), use_selection=True,
        object_types={'MESH'}, apply_unit_scale=True, axis_forward='-Y', axis_up='Z',
        use_mesh_modifiers=True, bake_anim=False, path_mode='COPY', embed_textures=True)
    bpy.data.objects.remove(joined, do_unlink=True)
    for mat in materials.values():
        for node in mat.node_tree.nodes:
            if node.type == 'TEX_IMAGE' and node.image:
                node.image.pack()
                if Path(node.image.filepath).is_absolute():
                    node.image.filepath = bpy.path.relpath(node.image.filepath, start=str(ROOT/'art/blender'))
    bpy.data.libraries.write(str(ROOT/'art/blender'/(name+'_'+TAG+'.blend')), {scene}, fake_user=True, compress=True)
    return report

reports = {
    'space_roadster': export_asset('space_roadster', 'space_roadster.blend', 'ROADSTER v3 | Editable components'),
    'lounge_chair': export_asset('lounge_chair', 'lounge_chair.blend', 'CHAIR - editable components'),
}
(OUT/'authoring.json').write_text(json.dumps(reports, indent=2)+'\n')
result = reports
