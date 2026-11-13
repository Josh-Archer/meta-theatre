"""Partition the existing Earth overlay without changing any indexed attributes.

Run with the art NumPy environment. Keeps the original GLB and Blender source;
each source triangle belongs to exactly one independently loadable region.
"""
import copy
import hashlib
import json
import struct
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT/'app/src/main/assets/earth_dynamic_surface_detail.glb'
OUTPUT = ROOT/'app/src/main/assets/earth/detail'
VALIDATION = ROOT/'art/validation/earth-partitions-20261112'
COLUMNS, ROWS = 16, 8
OUTPUT.mkdir(parents=True, exist_ok=True)
VALIDATION.mkdir(parents=True, exist_ok=True)
raw = SOURCE.read_bytes()
json_size = struct.unpack_from('<I',raw,12)[0]
source = json.loads(raw[20:20+json_size])
binary = raw[28+json_size:]
assert len(source['meshes']) == 1 and len(source['meshes'][0]['primitives']) == 1
assert not source.get('images') and not source.get('skins') and not source.get('animations')
primitive = source['meshes'][0]['primitives'][0]


def read_accessor(index):
    a = source['accessors'][index]; view = source['bufferViews'][a['bufferView']]
    dtype = np.dtype({5126:'<f4',5125:'<u4',5123:'<u2'}[a['componentType']])
    width = {'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}[a['type']]
    return np.ndarray((a['count'],width), dtype=dtype, buffer=binary,
        offset=view.get('byteOffset',0)+a.get('byteOffset',0),
        strides=(view.get('byteStride',width*dtype.itemsize),dtype.itemsize)).copy()


indices = read_accessor(primitive['indices']).reshape(-1,3)
attributes = {name:read_accessor(index) for name,index in primitive['attributes'].items()}
uv_triangles = attributes['TEXCOORD_0'][indices]
assert np.isfinite(uv_triangles).all()
assert uv_triangles[:,:,1].min() >= 0 and uv_triangles[:,:,1].max() <= 1
assert np.ptp(uv_triangles[:,:,0],axis=1).max() < .05, 'Unsplit UV seam triangle'
centers = uv_triangles.mean(axis=1)
# A few source UVs exceed 1 at the repeating seam. Canonicalize only selection
# bounds per triangle; the exported texture coordinates remain bit-identical.
canonical_uv = uv_triangles.copy()
canonical_uv[:,:,0] -= np.floor(centers[:,0])[:,None]
columns = np.clip(((centers[:,0] % 1)*COLUMNS).astype(int),0,COLUMNS-1)
rows = np.clip((centers[:,1]*ROWS).astype(int),0,ROWS-1)
ownership = rows*COLUMNS+columns
visited = np.zeros(len(indices),np.int8)
tiles = []
for tile_id in range(COLUMNS*ROWS):
    triangle_ids = np.flatnonzero(ownership == tile_id)
    assert len(triangle_ids)
    visited[triangle_ids] += 1
    original_indices = indices[triangle_ids].ravel()
    referenced, remapped = np.unique(original_indices,return_inverse=True)
    doc = copy.deepcopy(source)
    doc['meshes'][0]['name'] = f'Earth detail {tile_id:03d}'
    doc['nodes'][0]['name'] = f'Earth detail {tile_id:03d}'
    doc['accessors'] = []; doc['bufferViews'] = []
    output_binary = bytearray()

    def add_accessor(values, component_type, kind, target):
        while len(output_binary)%4: output_binary.append(0)
        offset = len(output_binary); data = values.tobytes()
        output_binary.extend(data)
        view = len(doc['bufferViews'])
        doc['bufferViews'].append({'buffer':0,'byteOffset':offset,'byteLength':len(data),'target':target})
        accessor = {'bufferView':view,'componentType':component_type,'count':len(values),'type':kind}
        if kind == 'VEC3' and target == 34962:
            accessor['min'] = values.min(axis=0).tolist(); accessor['max'] = values.max(axis=0).tolist()
        doc['accessors'].append(accessor)
        return len(doc['accessors'])-1

    out_primitive = doc['meshes'][0]['primitives'][0]
    out_primitive['attributes'] = {}
    for name,values in attributes.items():
        original_accessor = source['accessors'][primitive['attributes'][name]]
        selected = values[referenced]
        assert np.array_equal(selected[remapped],values[original_indices]), name
        out_primitive['attributes'][name] = add_accessor(selected,
            original_accessor['componentType'],original_accessor['type'],34962)
    assert len(referenced) < 65536
    out_primitive['indices'] = add_accessor(remapped.astype('<u2'),5123,'SCALAR',34963)
    while len(output_binary)%4: output_binary.append(0)
    doc['buffers'] = [{'byteLength':len(output_binary)}]
    encoded = json.dumps(doc,separators=(',',':')).encode()
    encoded += b' '*(-len(encoded)%4)
    glb = (struct.pack('<III',0x46546c67,2,28+len(encoded)+len(output_binary))+
        struct.pack('<II',len(encoded),0x4e4f534a)+encoded+
        struct.pack('<II',len(output_binary),0x004e4942)+output_binary)
    name = f'{tile_id:03d}.glb'
    (OUTPUT/name).write_bytes(glb)
    used_uv = canonical_uv[triangle_ids].reshape(-1,2)
    tiles.append({'asset':'earth/detail/'+name,'minU':float(used_uv[:,0].min()),
        'maxU':float(used_uv[:,0].max()),'minV':float(used_uv[:,1].min()),
        'maxV':float(used_uv[:,1].max()),'triangles':len(triangle_ids),'bytes':len(glb)})
assert (visited == 1).all()
manifest = {'version':1,'sourceSha256':hashlib.sha256(raw).hexdigest(),
    'triangles':len(indices),'columns':COLUMNS,'rows':ROWS,'tiles':tiles}
(OUTPUT/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
report = {'sourceBytes':len(raw),'partitionBytes':sum(t['bytes'] for t in tiles),
    'triangles':len(indices),'tiles':len(tiles),'indexedAttributesExact':True,
    'everyTriangleAssignedOnce':True,'minimumTriangles':min(t['triangles'] for t in tiles),
    'maximumTriangles':max(t['triangles'] for t in tiles)}
(VALIDATION/'partition.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
