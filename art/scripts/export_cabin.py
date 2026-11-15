"""Export the active authored cabin into one uniform-layout mesh per material.

Evaluates bevels and text without modifying editable sources. Exact vertex tuples
are welded; no decimation, resizing, or approximate geometry welding is used.
This avoids the mixed-primitive mesh import issue observed on the Quest SDK.
"""
from pathlib import Path
from collections import defaultdict
import json
import bpy

ROOT = Path(__file__).resolve().parents[2]
source = bpy.context.scene
assert source.get('native_contract'), 'Activate the authored cabin scene first'
dependency_graph = bpy.context.evaluated_depsgraph_get()
groups = defaultdict(list)
source_counts = {}
zero_area_caps = 0
for obj in source.objects:
    if obj.type not in {'MESH', 'CURVE', 'FONT'} or obj.get('preview_only'):
        continue
    evaluated = obj.evaluated_get(dependency_graph)
    mesh = bpy.data.meshes.new_from_object(evaluated, depsgraph=dependency_graph)
    mesh.calc_loop_triangles()
    matrix = obj.matrix_world
    normal_matrix = matrix.to_3x3().inverted().transposed()
    uv_layer = mesh.uv_layers.active
    normals = mesh.corner_normals
    source_counts[obj.name] = len(mesh.loop_triangles)
    for tri in mesh.loop_triangles:
        mat = mesh.materials[tri.material_index]
        corners = []
        for li in tri.loops:
            p = matrix @ mesh.vertices[mesh.loops[li].vertex_index].co
            normal = (normal_matrix @ normals[li].vector).normalized()
            if normal.length_squared < .5:
                # Some bevel end caps have zero-area corners. Keep their geometry
                # but use the triangle normal, or +Z if the triangle is degenerate.
                normal = (normal_matrix @ tri.normal).normalized()
                if normal.length_squared < .5:
                    normal = __import__('mathutils').Vector((0, 0, 1))
            if 'woven slate' in mat.name:
                axis = max(range(3), key=lambda i: abs(normal[i]))
                planar = [(p.y, p.z), (p.x, p.z), (p.x, p.y)][axis]
                uv = tuple(c / .12 for c in planar)
            elif uv_layer:
                uv = tuple(uv_layer.data[li].uv)
            else:
                axis = max(range(3), key=lambda i: abs(normal[i]))
                uv = [(p.y, p.z), (p.x, p.z), (p.x, p.y)][axis]
            corners.append((tuple(p), tuple(normal), tuple(uv)))
        if not obj.get('original_triangles'):
            from mathutils import Vector
            a, b, c = [Vector(corner[0]) for corner in corners]
            if (b - a).cross(c - a).length_squared == 0:
                zero_area_caps += 1
                continue
        groups[mat].append(corners)
    bpy.data.meshes.remove(mesh)

# Write a core glTF 2.0 container from evaluated Blender attributes. This also
# avoids a Blender 5.3 crash in normals_split_custom_set_from_vertices on large
# disconnected meshes. Material values and all texture channels stay explicit.
import struct
import numpy as np

document = {'asset': {'version': '2.0', 'generator': 'Meta Theatre Blender cabin exporter'},
            'scene': 0, 'scenes': [{'nodes': []}], 'nodes': [], 'meshes': [],
            'materials': [], 'textures': [], 'images': [],
            'samplers': [{'magFilter': 9729, 'minFilter': 9987, 'wrapS': 10497, 'wrapT': 10497}],
            'accessors': [], 'bufferViews': [], 'buffers': []}
binary = bytearray()
texture_indices = {}


def view(data, target=None):
    binary.extend(b'\0' * (-len(binary) % 4))
    result = {'buffer': 0, 'byteOffset': len(binary), 'byteLength': len(data)}
    if target:
        result['target'] = target
    index = len(document['bufferViews'])
    document['bufferViews'].append(result)
    binary.extend(data)
    return index


def accessor(values, kind, component, target, bounds=False):
    v = view(values.tobytes(), target)
    a = {'bufferView': v, 'componentType': component, 'count': len(values), 'type': kind}
    if bounds:
        a['min'] = values.min(axis=0).tolist()
        a['max'] = values.max(axis=0).tolist()
    i = len(document['accessors'])
    document['accessors'].append(a)
    return i


def texture(im, packed_roughness=False):
    key = (im.name, packed_roughness)
    if key in texture_indices:
        return texture_indices[key]
    path = Path(bpy.path.abspath(im.filepath_raw))
    if packed_roughness:
        values = np.array(im.pixels[:], dtype=np.float32).reshape(im.size[1], im.size[0], 4)
        values[:, :, 0] = 1
        values[:, :, 2:] = 1
        packed = bpy.data.images.new('Satin_ORM_export', width=im.size[0], height=im.size[1], alpha=False)
        packed.colorspace_settings.name = 'Non-Color'
        packed.pixels.foreach_set(values.ravel())
        path = ROOT / 'art/textures/cabin_20261114/Satin_ORM.png'
        packed.filepath_raw = str(path)
        packed.file_format = 'PNG'
        packed.save()
        packed.pack()
    i = len(document['images'])
    document['images'].append({'name': path.stem, 'mimeType': 'image/png',
                               'bufferView': view(path.read_bytes())})
    document['textures'].append({'source': i, 'sampler': 0})
    texture_indices[key] = i
    return i


def input_image(socket):
    return socket.links[0].from_node.image if socket.is_linked else None


def export_material(mat):
    p = mat.node_tree.nodes.get('Principled BSDF')
    base = input_image(p.inputs['Base Color'])
    rough = input_image(p.inputs['Roughness'])
    pbr = {'baseColorFactor': [1, 1, 1, 1] if base else list(p.inputs['Base Color'].default_value),
           'metallicFactor': p.inputs['Metallic'].default_value,
           'roughnessFactor': 1 if rough else p.inputs['Roughness'].default_value}
    if base:
        pbr['baseColorTexture'] = {'index': texture(base)}
    if rough:
        pbr['metallicRoughnessTexture'] = {'index': texture(rough, True)}
    m = {'name': mat.name.split('.')[0], 'pbrMetallicRoughness': pbr,
         'doubleSided': not mat.use_backface_culling}
    if p.inputs['Normal'].is_linked:
        normal = p.inputs['Normal'].links[0].from_node
        m['normalTexture'] = {'index': texture(input_image(normal.inputs['Color'])),
                              'scale': normal.inputs['Strength'].default_value}
    emission = list(p.inputs['Emission Color'].default_value)[:3]
    if max(emission) and p.inputs['Emission Strength'].default_value:
        m['emissiveFactor'] = emission
    document['materials'].append(m)
    return len(document['materials']) - 1


def tangent_frame(pos, norm, uv, faces):
    t = np.zeros_like(pos)
    b = np.zeros_like(pos)
    p0, p1, p2 = (pos[faces[:, i]] for i in range(3))
    u0, u1, u2 = (uv[faces[:, i]] for i in range(3))
    e1, e2 = p1 - p0, p2 - p0
    d1, d2 = u1 - u0, u2 - u0
    det = d1[:, 0] * d2[:, 1] - d1[:, 1] * d2[:, 0]
    reciprocal = np.zeros_like(det)
    valid = np.abs(det) > 1e-12
    reciprocal[valid] = 1 / det[valid]
    a = (e1 * d2[:, 1:2] - e2 * d1[:, 1:2]) * reciprocal[:, None]
    c = (e2 * d1[:, 0:1] - e1 * d2[:, 0:1]) * reciprocal[:, None]
    for i in range(3):
        np.add.at(t, faces[:, i], a)
        np.add.at(b, faces[:, i], c)
    t -= norm * np.sum(norm * t, axis=1)[:, None]
    length = np.linalg.norm(t, axis=1)
    bad = length < 1e-8
    helper = np.zeros_like(norm[bad])
    helper[:, 0] = 1
    helper[np.abs(norm[bad, 0]) > .8] = [0, 1, 0]
    t[bad] = np.cross(norm[bad], helper)
    t /= np.linalg.norm(t, axis=1)[:, None]
    sign = np.where(np.sum(np.cross(norm, t) * b, axis=1) < 0, -1, 1)
    return np.column_stack([t, sign]).astype('<f4')


draws = []
for material, triangles in groups.items():
    material_index = export_material(material)
    vertices, normals, uvs, faces, lookup = [], [], [], [], {}

    def flush():
        if not faces:
            return
        # Blender +Z-up to glTF +Y-up. Flip V for glTF's top-left image origin.
        pos = np.array(vertices, dtype='<f4')[:, [0, 2, 1]]
        pos[:, 2] *= -1
        norm = np.array(normals, dtype='<f4')[:, [0, 2, 1]]
        norm[:, 2] *= -1
        norm /= np.linalg.norm(norm, axis=1)[:, None]
        uv = np.array(uvs, dtype='<f4')
        uv[:, 1] = 1 - uv[:, 1]
        indices = np.array(faces, dtype='<u2')
        attrs = {'POSITION': accessor(pos, 'VEC3', 5126, 34962, True),
                 'NORMAL': accessor(norm, 'VEC3', 5126, 34962)}
        m = document['materials'][material_index]
        if ('normalTexture' in m or any(k.endswith('Texture') for k in m['pbrMetallicRoughness'])):
            attrs['TEXCOORD_0'] = accessor(uv, 'VEC2', 5126, 34962)
        if 'normalTexture' in document['materials'][material_index]:
            attrs['TANGENT'] = accessor(tangent_frame(pos, norm, uv, indices), 'VEC4', 5126, 34962)
        primitive = {'attributes': attrs, 'material': material_index,
                     'indices': accessor(indices.ravel(), 'SCALAR', 5123, 34963), 'mode': 4}
        i = len(document['meshes'])
        name = material.name.split('.')[0] + ' / %02d' % i
        document['meshes'].append({'name': name, 'primitives': [primitive]})
        document['nodes'].append({'name': name, 'mesh': i})
        document['scenes'][0]['nodes'].append(i)
        draws.append({'name': name, 'vertices': len(pos), 'triangles': len(faces),
                      'material': material.name})

    for tri in triangles:
        if len(vertices) + 3 > 60000:
            flush()
            vertices, normals, uvs, faces, lookup = [], [], [], [], {}
        face = []
        for key in tri:
            if key not in lookup:
                lookup[key] = len(vertices)
                vertices.append(key[0])
                normals.append(key[1])
                uvs.append(key[2])
            face.append(lookup[key])
        faces.append(face)
    flush()

out = ROOT / 'art/exports/cabin_20261114/hull.glb'
document['buffers'] = [{'byteLength': len(binary)}]
encoded = json.dumps(document, separators=(',', ':')).encode()
encoded += b' ' * (-len(encoded) % 4)
binary.extend(b'\0' * (-len(binary) % 4))
length = 12 + 8 + len(encoded) + 8 + len(binary)
out.write_bytes(struct.pack('<III', 0x46546c67, 2, length) +
    struct.pack('<II', len(encoded), 0x4e4f534a) + encoded +
    struct.pack('<II', len(binary), 0x004e4942) + binary)
report = {'source_scene': source.name, 'draws': draws,
          'triangles': sum(d['triangles'] for d in draws),
          'zero_area_added_bevel_caps_removed': zero_area_caps,
          'source_object_triangles': source_counts,
          'export_bytes': out.stat().st_size, 'headset_verified': False}
(ROOT / 'art/validation/cabin-20261114/export.json').write_text(json.dumps(report, indent=2) + '\n')
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT / 'art/blender/cabin_realism_20261114.blend'))
result = {'draws': len(draws), 'triangles': report['triangles'], 'bytes': out.stat().st_size}
