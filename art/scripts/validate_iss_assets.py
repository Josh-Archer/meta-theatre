"""Check the ISS material pass against its preserved production geometry.

Requires NumPy/Pillow in the art validation environment. The Khronos validator
and lossless weld fingerprints are separate checks, not replaced by this audit.
"""
import hashlib
import io
import json
import struct
from collections import Counter
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]


def load(path):
    data = path.read_bytes()
    size = struct.unpack_from('<I', data, 12)[0]
    return json.loads(data[20:20+size]), data[28+size:]


def accessor(gltf, binary, index):
    a = gltf['accessors'][index]
    view = gltf['bufferViews'][a['bufferView']]
    dtype = {5126:'<f4', 5125:'<u4', 5123:'<u2', 5121:'u1'}[a['componentType']]
    width = {'SCALAR':1, 'VEC2':2, 'VEC3':3, 'VEC4':4}[a['type']]
    item_size = np.dtype(dtype).itemsize
    return np.ndarray((a['count'],width), dtype=dtype, buffer=binary,
        offset=view.get('byteOffset',0)+a.get('byteOffset',0),
        strides=(view.get('byteStride',item_size*width),item_size)).copy()


def triangles(gltf, binary, primitive, semantic):
    values = accessor(gltf,binary,primitive['attributes'][semantic])
    return values[accessor(gltf,binary,primitive['indices']).ravel()].reshape(-1,3,values.shape[1])


def image_bytes(gltf, binary, index):
    view = gltf['bufferViews'][gltf['images'][index]['bufferView']]
    start = view.get('byteOffset',0)
    return binary[start:start+view['byteLength']]


def report(path):
    gltf, binary = load(path)
    primitives = gltf['meshes'][0]['primitives']
    images = []
    for i, im in enumerate(gltf['images']):
        data = image_bytes(gltf,binary,i)
        w,h = Image.open(io.BytesIO(data)).size
        images.append({'name':im.get('name'), 'dimensions':[w,h],
            'sha256':hashlib.sha256(data).hexdigest(), 'rgba8_mipped_bytes':w*h*4*4/3})
    image_views = {im['bufferView'] for im in gltf['images']}
    geometry_bytes = sum(v['byteLength'] for i,v in enumerate(gltf['bufferViews']) if i not in image_views)
    for primitive in primitives:
        for semantic, index in primitive['attributes'].items():
            values = accessor(gltf,binary,index)
            assert np.isfinite(values).all(), semantic
            if semantic in {'NORMAL','TANGENT'}:
                assert np.allclose(np.linalg.norm(values[:,:3],axis=1),1,atol=1e-4), semantic
            if semantic == 'TANGENT':
                normals = accessor(gltf,binary,primitive['attributes']['NORMAL'])
                assert np.max(np.abs(np.sum(values[:,:3]*normals,axis=1))) < 2e-4
                assert np.all(np.abs(values[:,3]) == 1)
    return {'bytes':path.stat().st_size,
        'triangles':sum(gltf['accessors'][p['indices']]['count']//3 for p in primitives),
        'primitives':len(primitives), 'images':images, 'geometry_bytes':geometry_bytes,
        'texture_mib_rgba8_mipped':sum(im['rgba8_mipped_bytes'] for im in images)/1024**2,
        'materials':gltf['materials'], 'nodes':gltf['nodes']}, gltf, binary


before,j0,b0 = report(ROOT/'art/exports/iss_20261112/iss_before.glb')
after,j1,b1 = report(ROOT/'art/exports/iss_20261112/iss.glb')
checks = []
for p0,p1 in zip(j0['meshes'][0]['primitives'],j1['meshes'][0]['primitives']):
    record = {}
    for semantic in ['POSITION','NORMAL']+(['TEXCOORD_0'] if p0['material']==3 else []):
        a = triangles(j0,b0,p0,semantic); b = triangles(j1,b1,p1,semantic)
        assert a.shape == b.shape
        error = np.abs(a-b).max()
        record[semantic+'_max_error'] = float(error)
        assert error == 0, (p0['material'],semantic,error)
    assert j0['materials'][p0['material']].get('doubleSided',False) == j1['materials'][p1['material']].get('doubleSided',False)
    checks.append(record)
i0 = j0['materials'][3]['pbrMetallicRoughness']['baseColorTexture']['index']
i1 = j1['materials'][3]['pbrMetallicRoughness']['baseColorTexture']['index']
assert image_bytes(j0,b0,j0['textures'][i0]['source']) == image_bytes(j1,b1,j1['textures'][i1]['source'])
assert before['triangles'] == after['triangles'] == 37865
assert before['primitives'] == after['primitives'] == 4
# This source and output have a single mesh at the identity transform.
for gltf in [j0,j1]:
    for node in gltf['nodes']:
        assert not any(k in node for k in ['matrix','translation','rotation','scale'])
result = {'before':before, 'after':after, 'indexed_triangle_checks':checks,
          'pv_color_byte_identical':True, 'double_sided_preserved':True}
out = ROOT/'art/validation/iss-20261112/geometry-textures.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:{x:v[x] for x in ['bytes','triangles','primitives','geometry_bytes','texture_mib_rgba8_mipped']}
    for k,v in [('before',before),('after',after)]},indent=2))
print(checks)

# Optional cross-engine proof from the exported Unreal render sections. Axis
# conversion is glTF meters -> Unreal centimeters: (x,y,z) -> (x,z,y)*100.
sections_file = ROOT/'art/validation/iss-20261112/unreal-sections.json'
if sections_file.exists():
    sections = json.loads(sections_file.read_text())
    assert len(sections) == 4
    unreal_checks = []
    def triangle_key(triangle):
        return tuple(sorted(tuple(float(c) for c in vertex) for vertex in triangle))
    for primitive,section in zip(j1['meshes'][0]['primitives'],sections):
        source = triangles(j1,b1,primitive,'POSITION')
        converted = source[:,:,[0,2,1]] * np.float32(100)
        imported = np.array(section['vertices'],np.float32)[np.array(section['indices'])].reshape(-1,3,3)
        expected = Counter(map(triangle_key,converted))
        actual = Counter(map(triangle_key,imported))
        missing = expected-actual
        extra = actual-expected
        areas = {triangle_key(t):float(np.linalg.norm(np.cross(
            original[1].astype(float)-original[0], original[2].astype(float)-original[0])))
            for t,original in zip(converted,source)}
        assert not extra, 'Unreal introduced triangles or changed positions'
        assert all(areas[key] == 0 for key in missing), 'Unreal removed visible geometry'
        unreal_checks.append({'material':primitive['material'], 'source_triangles':len(source),
            'unreal_triangles':len(imported), 'missing':sum(missing.values()),
            'unexpected':0, 'missing_nonzero_area':0, 'max_removed_double_area_m2':0})
    (ROOT/'art/validation/iss-20261112/unreal-geometry-parity.json').write_text(json.dumps(unreal_checks,indent=2)+'\n')
    print('Unreal triangle multiset: every nonzero-area source triangle retained exactly.')
