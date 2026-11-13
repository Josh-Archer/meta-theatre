"""Render the actual regional shader on full and partitioned source geometry.

Desktop EGL parity, including seam/pole/horizon views. Requires ModernGL/NumPy.
This checks coverage and shading preservation, not Quest performance.
"""
import json
import hashlib
import re
import struct
from pathlib import Path

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT/'app/src/main/assets'
SHADERS = ROOT/'app/src/shaders'
OUT = ROOT/'art/validation/earth-partitions-20261112'
OUT.mkdir(parents=True,exist_ok=True)


def geometry(path):
    raw = path.read_bytes(); size = struct.unpack_from('<I',raw,12)[0]
    doc = json.loads(raw[20:20+size]); binary = raw[28+size:]
    primitive = doc['meshes'][0]['primitives'][0]
    def accessor(index):
        a = doc['accessors'][index]; v = doc['bufferViews'][a['bufferView']]
        dtype = np.dtype({5126:'<f4',5125:'<u4',5123:'<u2'}[a['componentType']])
        width = {'SCALAR':1,'VEC2':2,'VEC3':3}[a['type']]
        return np.ndarray((a['count'],width),dtype=dtype,buffer=binary,
            offset=v.get('byteOffset',0)+a.get('byteOffset',0),
            strides=(v.get('byteStride',width*dtype.itemsize),dtype.itemsize)).copy()
    attributes = [accessor(primitive['attributes'][key]) for key in ['POSITION','NORMAL','TEXCOORD_0']]
    return np.concatenate(attributes,axis=1), accessor(primitive['indices']).astype('u4').ravel()


def intersects(tile,west,north,span):
    start = ((west+180)/360) % 1
    end = start+span/360
    margin = .000002
    return (tile['maxV'] >= (90-north)/180-margin and
        tile['minV'] <= (90-north+span)/180+margin and
        any(tile['maxU']+shift >= start-margin and tile['minU']+shift <= end+margin for shift in [-1,0,1]))


ctx = moderngl.create_standalone_context(require=450,backend='egl')
shim = '''
struct Primitive { mat4 objectFromWorld; };
const Primitive g_PrimitiveUniform = Primitive(mat4(1.0));
uniform vec3 testEye;
vec3 getEyeCenter() { return testEye; }
vec3 linear_to_srgb(vec3 value) {
    return mix(value*12.92,1.055*pow(max(value,vec3(0)),vec3(1./2.4))-.055,
        greaterThan(value,vec3(.0031308)));
}
float linear_to_srgb(float value) { return linear_to_srgb(vec3(value)).x; }
'''
vertex = '''#version 450
in vec3 position; in vec3 normal; in vec2 uv;
uniform mat4 viewProjection;
layout(location=0) out EarthVertex { vec2 uv; vec3 localNormal; vec3 localPosition; } earth;
void main() {
    earth.uv=uv; earth.localNormal=normal; earth.localPosition=position;
    gl_Position=viewProjection*vec4(position,1);
}
'''
fragment = (SHADERS/'earth_detail.frag').read_text().replace('#include "earth_fragment.glsl"',
    (SHADERS/'earth_fragment.glsl').read_text()).replace('#include <data/shaders/common.glsl>',shim)
fragment = re.sub(r'^#extension.*\n','',fragment,flags=re.M).replace('set = 3, ','')
program = ctx.program(vertex_shader=vertex,fragment_shader=fragment)


def vao(vertices,indices):
    return ctx.vertex_array(program,[(ctx.buffer(vertices.tobytes()),'3f 3f 2f','position','normal','uv')],
        index_buffer=ctx.buffer(indices.tobytes()))


original_vertices,original_indices = geometry(ASSETS/'earth_dynamic_surface_detail.glb')
source_triangles = original_vertices[original_indices.reshape(-1,3)]
full = vao(original_vertices,original_indices)
tiles = json.loads((ASSETS/'earth/detail/manifest.json').read_text())['tiles']
parts = []
partition_hashes = []
for tile in tiles:
    vertices,indices = geometry(ASSETS/tile['asset'])
    assert len(indices)//3 == tile['triangles']
    partition_hashes.extend(hashlib.sha256(t.tobytes()).digest() for t in vertices[indices.reshape(-1,3)])
    parts.append(vao(vertices,indices))
original_hashes = [hashlib.sha256(t.tobytes()).digest() for t in source_triangles]
assert sorted(partition_hashes) == sorted(original_hashes), 'Indexed geometry changed, duplicated or lost'
uniforms = ctx.buffer(reserve=80); uniforms.bind_to_uniform_block(0)
rng = np.random.default_rng(13)
textures = []
for binding in range(1,6):
    values = rng.uniform(.1,.9,(64,128,4)).astype('f4')
    if binding == 3: values[:,:,2] = .98
    texture = ctx.texture((128,64),4,values.tobytes(),dtype='f4')
    texture.repeat_x=True; texture.repeat_y=False
    texture.filter=(moderngl.LINEAR,moderngl.LINEAR); texture.use(binding)
    textures.append(texture)
color = ctx.texture((256,256),4,dtype='f4')
fbo = ctx.framebuffer(color_attachments=[color],depth_attachment=ctx.depth_renderbuffer((256,256)))
fbo.use(); ctx.enable(moderngl.CULL_FACE | moderngl.DEPTH_TEST | moderngl.BLEND)
ctx.blend_func = moderngl.SRC_ALPHA,moderngl.ONE_MINUS_SRC_ALPHA
ctx.depth_mask = False


def unit(v): return v/np.linalg.norm(v)


def surface_direction(uv):
    # Aim inside the imagery footprint, not at a nearest vertex that may lie
    # outside a one-degree polar region. Interpolate the original UV triangles.
    coordinates = source_triangles[:,:,6:].astype('f8')
    coordinates[:,:,0] -= np.round(coordinates[:,:,0].mean(axis=1)-uv[0])[:,None]
    first = coordinates[:,1]-coordinates[:,0]; second=coordinates[:,2]-coordinates[:,0]
    delta = uv-coordinates[:,0]
    determinant = first[:,0]*second[:,1]-first[:,1]*second[:,0]
    valid = np.abs(determinant)>1e-14
    divisor = np.where(valid,determinant,1)
    w1 = (delta[:,0]*second[:,1]-delta[:,1]*second[:,0])/divisor
    w2 = (first[:,0]*delta[:,1]-first[:,1]*delta[:,0])/divisor
    inside = valid & (w1 >= -1e-5) & (w2 >= -1e-5) & (w1+w2 <= 1+1e-5)
    index = np.flatnonzero(inside)[0]
    point = source_triangles[index,:,:3].T @ np.array([1-w1[index]-w2[index],w1[index],w2[index]])
    return unit(point)


results = []
for latitude in [-90.,-81.,0.,33.58,81.,90.]:
    for longitude in [-180.,-85.08,22.5,179.999]:
        for span,distance in [(18.,.08),(4.,.015),(1.,.004)]:
            # Wide polar caps exercise the original UV fan at the pole. Tiny
            # regions whose center falls outside that fan are covered by the
            # selector tests; they do not provide a useful nonempty image.
            if abs(latitude) == 90 and span < 18:
                continue
            north = min(90.,max(-90.+span,latitude+span/2)); west=longitude-span/2
            selected = [i for i,tile in enumerate(tiles) if intersects(tile,west,north,span)]
            assert 1 <= len(selected) <= 4, selected
            uv = np.array([((longitude+180)/360)%1,(90-(north-span/2))/180])
            direction = surface_direction(uv)
            up = np.array([0.,1.,0.]) if abs(direction[1]) < .95 else np.array([1.,0.,0.])
            tangent = unit(np.cross(up,direction))
            for horizon in [False,True]:
                eye = direction*(1+distance) + tangent*distance*(4 if horizon else 0)
                target = direction
                forward = unit(target-eye); right=unit(np.cross(forward,up)); camera_up=np.cross(right,forward)
                view = np.eye(4); view[:3,:3]=np.stack([right,camera_up,-forward]); view[:3,3]=-view[:3,:3]@eye
                near,far = .0001,10.
                projection = np.zeros((4,4)); projection[0,0]=projection[1,1]=1/np.tan(np.radians(60)/2)
                projection[2,2]=(far+near)/(near-far); projection[2,3]=2*far*near/(near-far); projection[3,2]=-1
                program['viewProjection'].write((projection@view).astype('f4').T.tobytes())
                program['testEye'].value=tuple(eye)
                uniforms.write(np.array([(*(direction*100),1),(1,0,0,0),(1,0,0,0),
                    (west,north,span,span),(0,0,0,0)],dtype='f4').tobytes())
                images=[]
                for draw in [[full],[parts[i] for i in selected]]:
                    fbo.clear(0,0,0,0,depth=1)
                    for array in draw: array.render()
                    images.append(np.frombuffer(fbo.read(components=4,dtype='f4'),dtype='f4').copy())
                assert all(np.isfinite(image).all() for image in images)
                assert images[0].max() > .01, (latitude,longitude,span,horizon,'empty view')
                error=float(np.max(np.abs(images[0]-images[1])))
                assert error <= 2e-6,(latitude,longitude,span,horizon,error)
                results.append({'latitude':latitude,'longitude':longitude,'span':span,'horizon':horizon,
                    'draws':len(selected),'triangles':sum(tiles[i]['triangles'] for i in selected),'maxAbsError':error})
report = {'renderer':ctx.info['GL_RENDERER'],'cases':len(results),
    'maxAbsError':max(r['maxAbsError'] for r in results),
    'maxTriangles':max(r['triangles'] for r in results),'maxDraws':max(r['draws'] for r in results),
    'results':results,'indexedTriangleAttributesExact':True,'headsetProof':False}
(OUT/'render-parity.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='results'},indent=2))
