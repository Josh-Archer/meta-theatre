"""Validate native cabin geometry, material channels, and viewing clearances."""
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import struct
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
FOLDER = ROOT / 'art/exports/cabin_20261114'


def load(path):
    raw = path.read_bytes()
    magic, version, length = struct.unpack_from('<III', raw)
    assert (magic, version, length) == (0x46546c67, 2, len(raw))
    size = struct.unpack_from('<I', raw, 12)[0]
    return json.loads(raw[20:20 + size]), raw[28 + size:], raw


def values(doc, binary, index):
    a = doc['accessors'][index]
    v = doc['bufferViews'][a['bufferView']]
    width = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4}[a['type']]
    dtype = np.dtype({5126: '<f4', 5123: '<u2', 5125: '<u4'}[a['componentType']])
    return np.ndarray((a['count'], width), dtype, binary,
        offset=v.get('byteOffset', 0) + a.get('byteOffset', 0),
        strides=(v.get('byteStride', width * dtype.itemsize), dtype.itemsize)).copy()


def triangles(doc, binary):
    result = []
    for mesh in doc['meshes']:
        for p in mesh['primitives']:
            v = values(doc, binary, p['attributes']['POSITION'])
            indices = values(doc, binary, p['indices']).ravel()
            result.append(v[indices].reshape(-1, 3, 3))
    return np.concatenate(result)


def triangle_counter(tris):
    return Counter(tuple(sorted(tuple(float(x) for x in p) for p in t)) for t in tris)


before, b0, raw0 = load(FOLDER / 'hull_before.glb')
after, b1, raw1 = load(FOLDER / 'hull.glb')
t0, t1 = triangles(before, b0), triangles(after, b1)
assert not (triangle_counter(t0) - triangle_counter(t1)), 'Original shell geometry changed'
assert len(t0) == 25402
assert 25402 < len(t1) <= 140000
assert len(after['meshes']) <= 12
assert not after.get('extensionsRequired') and not after.get('extensionsUsed')
assert not after.get('animations') and not after.get('skins')
for node in after['nodes']:
    assert not any(k in node for k in ['matrix', 'scale', 'translation', 'rotation'])
for mesh in after['meshes']:
    assert len(mesh['primitives']) == 1, 'Each SDK mesh needs one uniform vertex layout'
    p = mesh['primitives'][0]
    pos = values(after, b1, p['attributes']['POSITION'])
    assert len(pos) <= 60000
    for semantic, index in p['attributes'].items():
        a = values(after, b1, index)
        assert np.isfinite(a).all(), semantic
        if semantic in {'NORMAL', 'TANGENT'}:
            assert np.allclose(np.linalg.norm(a[:, :3], axis=1), 1, atol=2e-5), semantic
        if semantic == 'TANGENT':
            n = values(after, b1, p['attributes']['NORMAL'])
            assert np.abs((n * a[:, :3]).sum(1)).max() < 2e-5
            assert set(np.unique(a[:, 3])) <= {-1, 1}
    assert values(after, b1, p['indices']).max() < len(pos)

# Conservative triangle-AABB overlap: a false positive fails for review rather
# than quietly admitting geometry into the seat or the default film plane.
clearances = {'seat': ([-.65, .08, -.65], [.65, 2.10, .65]),
              'default_screen': ([-1.61, .44, 3.18], [1.61, 2.26, 3.22])}
for name, (lo, hi) in clearances.items():
    overlap = np.all(t1.max(1) >= lo, axis=1) & np.all(t1.min(1) <= hi, axis=1)
    assert not overlap.any(), (name, int(overlap.sum()))

images = []
for im in after['images']:
    v = after['bufferViews'][im['bufferView']]
    payload = b1[v.get('byteOffset', 0):v.get('byteOffset', 0) + v['byteLength']]
    picture = Image.open(io.BytesIO(payload))
    assert picture.size == (1024, 1024)
    images.append({'name': im['name'], 'dimensions': list(picture.size),
                   'sha256': hashlib.sha256(payload).hexdigest()})
assert len(images) == 8
report = {'before_triangles': len(t0), 'triangles': len(t1),
          'original_shell_triangles_exact': True, 'material_draws': len(after['meshes']),
          'original_bytes': len(raw0), 'bytes': len(raw1), 'images': images,
          'texture_memory_mib_rgba8_with_mips': len(images) * 1024 * 1024 * 4 * 4 / 3 / 1024**2,
          'bounds': [t1.reshape(-1, 3).min(0).tolist(), t1.reshape(-1, 3).max(0).tolist()],
          'clearance_checks': list(clearances), 'core_gltf_only': True,
          'sha256': hashlib.sha256(raw1).hexdigest(), 'headset_verified': False}
(ROOT / 'art/validation/cabin-20261114/geometry-materials.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k: v for k, v in report.items() if k != 'images'}, indent=2))
