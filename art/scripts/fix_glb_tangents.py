"""Replace zero-length TANGENT vectors in a GLB (vertices of zero-area UV triangles) with a valid tangent
perpendicular to the vertex normal, in place. Everything else in the file is left byte-identical.
  python3 art/scripts/fix_glb_tangents.py FILE.glb
"""
import json, struct, sys
path = sys.argv[1]
b = bytearray(open(path, 'rb').read())
n = struct.unpack('<I', b[12:16])[0]
j = json.loads(bytes(b[20:20 + n]))
off = 20 + n + 8
def base(i):
    a = j['accessors'][i]; bv = j['bufferViews'][a['bufferView']]
    return off + bv.get('byteOffset', 0) + a.get('byteOffset', 0), a['count'], bv.get('byteStride')
fixed = 0
for m in j['meshes']:
    for p in m['primitives']:
        if 'TANGENT' not in p['attributes']:
            continue
        ts, count, tstride = base(p['attributes']['TANGENT']); ns, _, nstride = base(p['attributes']['NORMAL'])
        tstride = tstride or 16; nstride = nstride or 12
        for k in range(count):
            tx, ty, tz, _w = struct.unpack_from('<4f', b, ts + tstride * k)
            if (tx * tx + ty * ty + tz * tz) ** .5 >= .5:
                continue
            nx, ny, nz = struct.unpack_from('<3f', b, ns + nstride * k)
            r = (1., 0., 0.) if abs(ny) > .9 else (0., 1., 0.)
            cx, cy, cz = ny * r[2] - nz * r[1], nz * r[0] - nx * r[2], nx * r[1] - ny * r[0]
            l = (cx * cx + cy * cy + cz * cz) ** .5
            struct.pack_into('<4f', b, ts + tstride * k, cx / l, cy / l, cz / l, 1.0)
            fixed += 1
open(path, 'wb').write(bytes(b))
print('fixed zero-length tangents:', fixed)
