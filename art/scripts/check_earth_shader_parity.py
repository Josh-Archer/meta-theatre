"""Compare actual Earth shader sources before/after using desktop EGL float targets.

The small SDK shim provides identical transforms and colour conversion to both
versions. This tests shader math parity; it is not Quest driver or FPS evidence.
Usage: python check_earth_shader_parity.py BASELINE_SHADER_DIR
Dependencies: moderngl, numpy.
"""
import json
import re
import sys
from pathlib import Path
import moderngl
import numpy as np
ROOT=Path(__file__).resolve().parents[2]
BASE=Path(sys.argv[1])
CURRENT=ROOT/'app/src/shaders'
ctx=moderngl.create_standalone_context(require=450,backend='egl')
shim='''
struct Primitive { mat4 objectFromWorld; };
const Primitive g_PrimitiveUniform = Primitive(mat4(1.0));
uniform vec3 testEye;
vec3 getEyeCenter() { return testEye; }
vec3 linear_to_srgb(vec3 value) {
    return mix(value * 12.92, 1.055 * pow(max(value, vec3(0.0)), vec3(1.0/2.4)) - .055,
        greaterThan(value, vec3(.0031308)));
}
float linear_to_srgb(float value) { return linear_to_srgb(vec3(value)).x; }
'''
vertex='''#version 450
in vec2 position;
layout(location=0) out EarthVertex { vec2 uv; vec3 localNormal; vec3 localPosition; } earth;
void main() {
    earth.uv = position;
    float lon=position.x*6.2831853, lat=(.5-position.y)*3.14159265;
    earth.localPosition=vec3(cos(lat)*sin(lon),sin(lat),cos(lat)*cos(lon));
    earth.localNormal=earth.localPosition;
    gl_Position=vec4(position*2.0-1.0,0.0,1.0);
}
'''
def source(folder,name):
    code=(folder/name).read_text().replace('#include "earth_fragment.glsl"',(folder/'earth_fragment.glsl').read_text())
    code=code.replace('#include <data/shaders/common.glsl>',shim)
    code=re.sub(r'^#extension.*\n','',code,flags=re.M)
    code=code.replace('set = 3, ','')
    return code
vertices=np.array([(x/64,y/32) for y in range(33) for x in range(65)],dtype='f4')
indices=[]
for y in range(32):
    for x in range(64):
        a=y*65+x;indices.extend([a,a+1,a+66,a,a+66,a+65])
vbo=ctx.buffer(vertices.tobytes());ibo=ctx.buffer(np.array(indices,dtype='u4').tobytes())
ubo=ctx.buffer(reserve=80);ubo.bind_to_uniform_block(0)
rng=np.random.default_rng(11711)
textures=[]
for binding in range(1,6):
    pixels=rng.uniform(.05,.95,(64,128,4)).astype('f4')
    if binding==3: pixels[:,:,2]=.95
    texture=ctx.texture((128,64),4,pixels.tobytes(),dtype='f4')
    texture.repeat_x=True;texture.repeat_y=False;texture.filter=(moderngl.LINEAR,moderngl.LINEAR)
    texture.use(binding);textures.append(texture)
color=ctx.texture((256,128),4,dtype='f4');fbo=ctx.framebuffer(color_attachments=[color]);fbo.use()
results=[]
for name in ['earth_surface.frag','earth_detail.frag']:
    programs=[ctx.program(vertex_shader=vertex,fragment_shader=source(folder,name)) for folder in [BASE,CURRENT]]
    arrays=[ctx.vertex_array(p,[(vbo,'2f','position')],index_buffer=ibo) for p in programs]
    cloud_results={}
    for clear in [0.,1.]:
      for style in [0.,1.]:
       for sun in [(0,0,100),(100,0,0),(0,100,0),(0,0,-100)]:
        for regional in [False,True]:
         uniforms=np.array([(*sun,1),(style,0,0,0),(clear,0,0,0),(-180,90,360,180),
             (.2,.2,.6,.6) if regional else (0,0,0,0)],dtype='f4')
         ubo.write(uniforms.tobytes());images=[]
         for p,vao in zip(programs,arrays):
            p['testEye'].value=(0.,0.,3.)
            fbo.clear(0,0,0,0);vao.render()
            images.append(np.frombuffer(fbo.read(components=4,dtype='f4'),dtype='f4'))
         assert all(np.isfinite(a).all() for a in images),(name,clear,style,sun,regional)
         assert float(np.max(images[0])) > .01, 'Empty shader output'
         cloud_results[(clear,style,sun,regional)]=images[0].copy()
         error=float(np.max(np.abs(images[0]-images[1])))
         assert error<=2e-6,(name,clear,style,sun,regional,error)
         results.append({'shader':name,'cloud_visibility':clear,'live_clouds':style,'sun':sun,'regional_normals':regional,'max_abs_error':error})
    for key,clear_image in cloud_results.items():
        clear,style,sun,regional=key
        if clear==0.:
            assert not np.array_equal(clear_image,cloud_results[(1.,style,sun,regional)]), 'Cloud test did not exercise attenuation'
report={'renderer':ctx.info['GL_RENDERER'],'cases':len(results),'max_abs_error':max(r['max_abs_error'] for r in results),'results':results}
(ROOT/'art/validation/realism-20261111/shader-parity.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='results'},indent=2))
