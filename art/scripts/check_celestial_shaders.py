"""Exercise actual native GLSL on EGL, including data decoding and sky translation.

Desktop correctness checks, not Quest timing. Requires moderngl, numpy, Pillow.
"""
import json
import math
import re
import struct
from pathlib import Path
import moderngl
import numpy as np
from PIL import Image, JpegImagePlugin

ROOT = Path(__file__).resolve().parents[2]
SHADERS = ROOT / 'app/src/shaders'
STAGE = ROOT / 'art/textures/celestial_20261111'
BASE = ROOT / 'art/validation/celestial-20261111/baseline/app/src'
OUT = ROOT / 'art/validation/celestial-20261111'
ctx = moderngl.create_standalone_context(require=450, backend='egl')
shim = '''
struct Primitive { mat4 objectFromWorld; mat4 worldFromObject; };
const Primitive g_PrimitiveUniform = Primitive(mat4(1.0), mat4(1.0));
uniform vec3 testEye;
vec3 getEyeCenter() { return testEye; }
vec3 linear_to_srgb(vec3 v) {
    return mix(v*12.92, 1.055*pow(max(v,vec3(0)),vec3(1.0/2.4))-.055,
        greaterThan(v,vec3(.0031308)));
}
'''
vertex = '''#version 450
in vec3 position; in vec3 normal; in vec2 uv;
layout(location=0) out SolarVertex {vec2 uv; vec3 localNormal; vec3 localPosition;} solar;
void main() {
    solar.uv=uv; solar.localNormal=normal; solar.localPosition=position;
    gl_Position=vec4(position.xy/1.12,-position.z*.5,1);
}
'''


def source(folder, name):
    text = (folder / name).read_text()
    text = text.replace('#include "solar_fragment.glsl"', (folder / 'solar_fragment.glsl').read_text())
    text = text.replace('#include <data/shaders/common.glsl>', shim)
    text = re.sub(r'^#extension.*\n', '', text, flags=re.M)
    return text.replace('set = 3, ', '')


def srgb_decode(rgb):
    return np.where(rgb <= .04045, rgb / 12.92, ((rgb + .055) / 1.055) ** 2.4)


def texture(path, binding):
    # SceneTexture's sRGB format decodes RGB, while alpha remains linear.
    im = Image.open(path).convert('RGBA')
    rgb = np.asarray(im).astype('f4') / 255
    rgb[:, :, :3] = srgb_decode(rgb[:, :, :3])
    t = ctx.texture(im.size, 4, rgb.tobytes(), dtype='f4')
    t.repeat_x = True; t.repeat_y = False
    t.build_mipmaps(); t.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
    t.use(binding); return t


data = (ROOT / 'app/src/main/assets/solar_body.glb').read_bytes()
length = struct.unpack_from('<I', data, 12)[0]
gltf = json.loads(data[20:20+length]); binary = data[28+length:]
primitive = gltf['meshes'][0]['primitives'][0]


def accessor(index):
    a = gltf['accessors'][index]; view = gltf['bufferViews'][a['bufferView']]
    dtype = {5126: '<f4', 5123: '<u2', 5125: '<u4'}[a['componentType']]
    width = {'VEC3': 3, 'VEC2': 2, 'SCALAR': 1}[a['type']]
    return np.ndarray((a['count'], width), dtype, binary,
        offset=view.get('byteOffset', 0) + a.get('byteOffset', 0),
        strides=(view.get('byteStride', np.dtype(dtype).itemsize * width), np.dtype(dtype).itemsize)).copy()


arrays = [accessor(primitive['attributes'][a]) for a in ['POSITION', 'NORMAL', 'TEXCOORD_0']]
vbo = ctx.buffer(np.concatenate(arrays, axis=1).astype('f4').tobytes())
ibo = ctx.buffer(accessor(primitive['indices']).astype('u4').tobytes())
programs = [ctx.program(vertex_shader=vertex, fragment_shader=source(f, 'solar_surface.frag')) for f in [BASE / 'shaders', SHADERS]]
vaos = [ctx.vertex_array(p, [(vbo, '3f 3f 2f', 'position', 'normal', 'uv')], ibo) for p in programs]
for p in programs: p['testEye'].value = (0, 0, 10000)
ubo = ctx.buffer(reserve=48); ubo.bind_to_uniform_block(0)
color = ctx.texture((512, 512), 4, dtype='f4')
fbo = ctx.framebuffer([color], ctx.depth_renderbuffer((512, 512))); fbo.use()
ctx.enable(moderngl.DEPTH_TEST)
normal_texture = texture(STAGE / 'moon_normal.png', 3)
ring_texture = texture(BASE / 'main/assets/solar/saturn_rings.png', 2)
profiles = {'moon': (.65, 1, 1, 0), 'mercury': (.45, 1, 0, 0), 'mars': (.25, 1, 0, .025),
    'venus': (0, .90, 0, .08), 'jupiter': (0, .88, 0, .035), 'saturn': (0, .90, 0, .055),
    'uranus': (0, .95, 0, .065), 'neptune': (0, .95, 0, .065)}


def render(sun, profile, parameters=(0, 0, 74500/58232, 136780/58232), version=1):
    ubo.write(np.array([(*sun, 1), parameters, profile], dtype='f4').tobytes())
    fbo.clear(0, 0, 0, 0, depth=1); vaos[version].render()
    pixels = np.frombuffer(fbo.read(components=4, dtype='f4'), 'f4').reshape(512, 512, 4)
    assert np.isfinite(pixels).all() and pixels.min() >= 0
    assert pixels[:, :, 3].sum() > 100000, 'Empty globe raster'
    assert pixels[:, :, :3].max() <= 3, 'Unbounded grazing-angle response'
    return pixels.copy()


cases = []
for name, profile in profiles.items():
    tex = texture((STAGE if name == 'moon' else BASE / 'main/assets/solar') / (name + '.jpg'), 1)
    phases = []
    for angle in [0, 30, 60, 90, 120, 150, 180]:
        a = math.radians(angle)
        result = render((10000*math.sin(a), 0, 10000*math.cos(a)), profile)
        phases.append(float(result[:, :, :3].mean()))
        cases.append({'body': name, 'phase': angle, 'mean_linear': phases[-1]})
    assert phases[0] > phases[-1] * 100, (name, phases)
    assert all(a > b for a, b in zip(phases, phases[1:])), (name, phases)
    for sun in [(0, 10000, 0), (0, -10000, 0), (1000, 4000, 8000)]:
        render(sun, profile); cases.append({'body': name, 'polar_or_oblique_sun': sun})
    tex.release()

tex = texture(STAGE / 'moon.jpg', 1)
sun = (-8600, 1600, 4800)
relief = render(sun, profiles['moon'])
flat = render(sun, (.65, 1, 0, 0))
normal_effect = float(np.abs(relief - flat).max())
assert normal_effect > .03, 'Terrain normal path did not affect lighting'
rgb = np.clip(relief[::-1, :, :3], 0, 1)
rgb = np.where(rgb <= .0031308, rgb * 12.92, 1.055 * rgb ** (1/2.4) - .055)
Image.fromarray(np.round(rgb * 255).astype('uint8')).save(OUT / 'moon-native-glsl.png')
tex.release()
tex = texture(BASE / 'main/assets/solar/saturn.jpg', 1)
no_ring = render((6000, 3000, 3000), profiles['saturn'])
shadow = render((6000, 3000, 3000), profiles['saturn'], (0, 1, 74500/58232, 136780/58232))
assert np.abs(no_ring - shadow).max() > .01, 'Ring shadow not exercised'
assert np.all(shadow <= no_ring + 1e-6), 'Shadow added energy'
tex.release()
tex = texture(BASE / 'main/assets/solar/sun.jpg', 1)
before = render(sun, (0, 1, 0, 0), (1, 0, 0, 0), version=0)
after = render(sun, (0, 1, 0, 0), (1, 0, 0, 0))
assert np.array_equal(before, after), 'Sun limb response changed'
tex.release()

# Run the actual sky vertex source in transform feedback with translated cameras.
sky_shim = shim + '''
uniform mat4 testClip;
mat4 getClipFromWorld() { return testClip; }
void postprocessPosition(inout vec4 p) {}
'''
app_shim = '''
in vec3 position;
struct App2VertexUnpacked {vec3 position; vec3 normal; vec2 uv;};
App2VertexUnpacked getApp2VertexUnpacked() {
    return App2VertexUnpacked(position, normalize(position), vec2(.5));
}
'''
sky_code = (SHADERS / 'solar_stars.vert').read_text().replace('#include <data/shaders/common.glsl>', sky_shim).replace('#include <data/shaders/app2vertex.glsl>', app_shim)
sky_program = ctx.program(vertex_shader=sky_code, varyings=['gl_Position'])
sky_vbo = ctx.buffer(np.array([[1, .5, -.4], [-.5, .2, 1]], 'f4').tobytes())
sky_vao = ctx.vertex_array(sky_program, [(sky_vbo, '3f', 'position')])
feedback = ctx.buffer(reserve=32); projected = []
for eye in [(0, 0, 0), (1000, 40, -800), (-5000, -600, 3000)]:
    matrix = np.eye(4, dtype='f4'); matrix[:3, 3] = -np.array(eye)
    sky_program['testClip'].write(matrix.T.tobytes()); sky_program['testEye'].value = eye
    sky_vao.transform(feedback, mode=moderngl.POINTS, vertices=2)
    projected.append(np.frombuffer(feedback.read(), 'f4').copy())
assert np.any(projected[0] != 0), 'Transform feedback was empty'
assert all(np.max(np.abs(p - projected[0])) < .0003 for p in projected), projected

encoding = {}
for name, size in [('moon', (2048, 1024)), ('stars', (8192, 4096))]:
    png = Image.open(STAGE / (name + '.png')).convert('RGB')
    jpg = Image.open(STAGE / (name + '.jpg'))
    assert png.size == jpg.size == size and JpegImagePlugin.get_sampling(jpg) == 0
    # Row blocks keep encoding QA from allocating several extra full 8K float buffers.
    squared = 0
    for row in range(0, size[1], 128):
        box = (0, row, size[0], min(row + 128, size[1]))
        delta = np.asarray(png.crop(box), dtype='f4') - np.asarray(jpg.crop(box), dtype='f4')
        squared += float(np.sum(delta * delta, dtype=np.float64))
    psnr = 10 * math.log10(255**2 / (squared / (size[0] * size[1] * 3)))
    assert psnr > 40, (name, psnr)
    encoding[name] = {'dimensions': size, 'psnr_db': psnr, 'jpeg_subsampling': '4:4:4'}
report = {'renderer': ctx.info['GL_RENDERER'], 'surface_cases': len(cases), 'normal_effect_max': normal_effect,
    'sun_unchanged': True, 'saturn_shadow_checked': True, 'sky_translation_cases': len(projected),
    'encoding': encoding, 'cases': cases, 'quest_verified': False}
(OUT / 'shader-validation.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k: v for k, v in report.items() if k != 'cases'}, indent=2))
