"""Matched Blender authoring previews, not Quest captures. Use after map staging."""
import bpy
import math
from mathutils import Vector
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'art/renders/celestial_20261111'
OUT.mkdir(parents=True, exist_ok=True)
STAGE = ROOT / 'art/textures/celestial_20261111'
BASE = ROOT / 'art/validation/celestial-20261111/baseline/app/src/main/assets/solar'
PROFILES = {'moon': (.65, 1, 1, 0), 'mercury': (.45, 1, 0, 0), 'mars': (.25, 1, 0, .025),
    'venus': (0, .90, 0, .08), 'jupiter': (0, .88, 0, .035), 'saturn': (0, .90, 0, .055),
    'uranus': (0, .95, 0, .065), 'neptune': (0, .95, 0, .065)}


def scene(name, size=(1400, 1400)):
    s = bpy.data.scenes.new(name); bpy.context.window.scene = s
    s.world = bpy.data.worlds.new(name + ' black'); s.world.use_nodes = True
    s.world.node_tree.nodes['Background'].inputs['Strength'].default_value = 0
    s.render.engine = 'CYCLES'; s.cycles.samples = 16; s.cycles.use_denoising = False
    prefs = bpy.context.preferences.addons['cycles'].preferences
    prefs.compute_device_type = 'OPTIX'; prefs.get_devices()
    for d in prefs.devices: d.use = d.type == 'OPTIX'
    s.cycles.device = 'GPU'
    s.render.resolution_x, s.render.resolution_y = size
    s.render.resolution_percentage = 100
    s.render.image_settings.file_format = 'PNG'
    s.view_settings.view_transform = 'Standard'; s.view_settings.look = 'None'
    camera = bpy.data.objects.new(name + ' camera', bpy.data.cameras.new(name + ' lens'))
    s.collection.objects.link(camera); s.camera = camera
    return s, camera


class Graph:
    def __init__(self, name):
        self.mat = bpy.data.materials.new(name); self.mat.use_nodes = True
        self.n = self.mat.node_tree.nodes; self.n.clear(); self.l = self.mat.node_tree.links
        self.geo = self.n.new('ShaderNodeNewGeometry')
    def feed(self, x, socket):
        if hasattr(x, 'is_output'): self.l.new(x, socket)
        else: socket.default_value = x
    def math(self, op, a, b=0):
        n = self.n.new('ShaderNodeMath'); n.operation = op
        self.feed(a, n.inputs[0]); self.feed(b, n.inputs[1]); return n.outputs[0]
    def vec(self, op, a, b=None):
        n = self.n.new('ShaderNodeVectorMath'); n.operation = op; self.feed(a, n.inputs[0])
        if b is not None: self.feed(b, n.inputs['Scale'] if op == 'SCALE' else n.inputs[1])
        return n.outputs['Value' if op == 'DOT_PRODUCT' else 'Vector']
    def texture(self, path, data=False):
        n = self.n.new('ShaderNodeTexImage'); n.image = bpy.data.images.load(str(path), check_existing=True)
        if data: n.image.colorspace_settings.name = 'Non-Color'
        return n.outputs['Color']
    def finish(self, color):
        em = self.n.new('ShaderNodeEmission'); self.feed(color, em.inputs['Color'])
        out = self.n.new('ShaderNodeOutputMaterial'); self.l.new(em.outputs[0], out.inputs['Surface'])
        for i, node in enumerate(self.n): node.location = ((i // 7) * 240, -(i % 7) * 200)
        return self.mat


body = bpy.data.objects['Solar_Body_Unit']
for name in ['moon', 'jupiter', 'mars']:
    for version in ['before', 'after']:
        s, camera = scene(name + ' ' + version)
        camera.location = (0, -4, 0); camera.rotation_euler = (Vector() - camera.location).to_track_quat('-Z', 'Y').to_euler()
        camera.data.type = 'ORTHO'; camera.data.ortho_scale = 2.25
        obj = body.copy(); obj.data = body.data.copy(); s.collection.objects.link(obj)
        obj.name = name + ' | ' + version; obj.hide_render = False; obj.hide_viewport = False; obj.hide_set(False)
        obj.location = (0, 0, 0); obj.scale = (1, 1, 1)
        g = Graph(name + ' | native lighting ' + version)
        color = g.texture((STAGE if name == 'moon' and version == 'after' else BASE) / (name + '.jpg'))
        geo_normal, view = g.geo.outputs['Normal'], g.geo.outputs['Incoming']
        normal = geo_normal
        sun = Vector((-.86, -.48, .16)).normalized()
        if version == 'after' and name == 'moon':
            nm = g.n.new('ShaderNodeNormalMap')
            g.feed(g.texture(STAGE / 'moon_normal.png', True), nm.inputs['Color'])
            normal = nm.outputs['Normal']
        incidence = g.math('MAXIMUM', g.vec('DOT_PRODUCT', normal, sun), 0)
        diffuse = incidence
        if version == 'after':
            particulate, exponent, strength, haze = PROFILES[name]
            emission = g.math('MAXIMUM', g.vec('DOT_PRODUCT', normal, view), 0)
            ls = g.math('DIVIDE', g.math('MULTIPLY', 2, incidence), g.math('MAXIMUM', g.math('ADD', incidence, emission), .08))
            cloud = g.math('MULTIPLY', incidence, g.math('POWER', g.math('MAXIMUM', g.math('MULTIPLY', incidence, emission), .01), exponent - 1))
            diffuse = g.math('ADD', g.math('MULTIPLY', cloud, 1-particulate), g.math('MULTIPLY', ls, particulate))
            horizon = g.n.new('ShaderNodeMapRange'); horizon.interpolation_type = 'SMOOTHSTEP'; horizon.clamp = True
            g.feed(g.vec('DOT_PRODUCT', geo_normal, sun), horizon.inputs[0])
            horizon.inputs['From Min'].default_value = -.006; horizon.inputs['From Max'].default_value = .006
            diffuse = g.math('MULTIPLY', diffuse, horizon.outputs[0])
        shaded = g.vec('SCALE', color, g.math('ADD', .002, g.math('MULTIPLY', 1.05, diffuse)))
        if version == 'after':
            rim = g.math('SUBTRACT', 1, g.math('MINIMUM', 1, g.math('MAXIMUM', g.vec('DOT_PRODUCT', geo_normal, view), 0)))
            amount = g.math('MULTIPLY', haze, g.math('MULTIPLY', g.math('POWER', rim, 3), incidence))
            amount = g.math('MULTIPLY', amount, horizon.outputs[0])
            gray = g.vec('DOT_PRODUCT', color, (.2126, .7152, .0722))
            tint = g.vec('ADD', g.vec('SCALE', color, .65), g.vec('SCALE', (1, 1, 1), g.math('MULTIPLY', gray, .35)))
            shaded = g.vec('ADD', shaded, g.vec('SCALE', tint, amount))
        obj.data.materials.clear(); obj.data.materials.append(g.finish(shaded))
        s['native_surface_profile'] = PROFILES[name] if version == 'after' else (0, 1, 0, 0)
        s['note'] = 'Matched authoring reference; native GLSL is independently exercised on EGL; not headset evidence.'
        s.render.filepath = str(OUT / (name + '_' + version + '.png'))
        bpy.ops.render.render(write_still=True)

source_dome = bpy.data.objects['Solar_Stars_Inward']
for field, ra, dec in [('core', 266.4, -28.94), ('cygnus', 305, 40)]:
    for version in ['before', 'after']:
        s, camera = scene('Sky ' + field + ' ' + version, (1800, 1100))
        dome = source_dome.copy(); dome.data = source_dome.data.copy(); s.collection.objects.link(dome)
        dome.hide_render = False; dome.hide_viewport = False; dome.hide_set(False); dome.scale = (100, 100, 100)
        g = Graph('Catalog sky ' + field + ' ' + version)
        color = g.texture((STAGE if version == 'after' else BASE) / 'stars.jpg')
        dome.data.materials.clear(); dome.data.materials.append(g.finish(g.vec('SCALE', color, .8)))
        a, d = math.radians(-ra), math.radians(dec)
        direction = Vector((math.sin(a)*math.cos(d), -math.cos(a)*math.cos(d), math.sin(d)))
        camera.rotation_euler = direction.to_track_quat('-Z', 'Y').to_euler()
        camera.data.lens = 15.1; camera.data.sensor_width = 36; camera.data.clip_end = 200
        s.render.filepath = str(OUT / ('sky_' + field + '_' + version + '.png'))
        bpy.ops.render.render(write_still=True)

for im in bpy.data.images:
    if im.source == 'FILE' and im.has_data:
        im.pack()
export_scene = bpy.data.scenes.new('CELESTIAL | Neutral export')
bpy.context.window.scene = export_scene
obj = body.copy(); obj.data = body.data.copy(); export_scene.collection.objects.link(obj)
obj.hide_render = False; obj.hide_viewport = False; obj.hide_set(False)
obj.location = (0, 0, 0); obj.rotation_euler = (0, 0, 0); obj.scale = (1, 1, 1)
obj.data.materials.clear(); obj.data.materials.append(bpy.data.materials.new('Native custom surface slot'))
obj.select_set(True); bpy.context.view_layer.objects.active = obj
export = ROOT / 'art/exports/celestial_20261111'; export.mkdir(parents=True, exist_ok=True)
bpy.ops.export_scene.fbx(filepath=str(export / 'planet_review.fbx'), use_selection=True,
    object_types={'MESH'}, add_leaf_bones=False, bake_anim=False, axis_forward='-Z', axis_up='Y')
bpy.ops.file.make_paths_relative()
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT / 'art/blender/celestial_20261111.blend'))
result = {'renders': 10, 'folder': str(OUT), 'source': bpy.data.filepath}
