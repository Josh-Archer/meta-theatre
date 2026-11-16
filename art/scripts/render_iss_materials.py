"""Matched ISS renders for issue #12.
  blender -b --python art/scripts/render_iss_materials.py -- INPUT.glb OUT_PREFIX
Imports a GLB into an empty scene and renders identical cameras/lighting (Cycles, CPU-safe).
"""
import sys, math
import bpy
from mathutils import Vector
inp, prefix = sys.argv[sys.argv.index('--') + 1:][:2]
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=inp)
sc = bpy.context.scene
sc.render.engine = 'CYCLES'; sc.cycles.samples = 48; sc.cycles.use_denoising = True; sc.cycles.device = 'CPU'
sc.render.resolution_x, sc.render.resolution_y = 960, 540
sc.world = bpy.data.worlds.new('w'); sc.world.use_nodes = True
bg = sc.world.node_tree.nodes['Background']; bg.inputs['Color'].default_value = (.004, .005, .01, 1); bg.inputs['Strength'].default_value = 1
sun = bpy.data.objects.new('sun', bpy.data.lights.new('sun', 'SUN')); sc.collection.objects.link(sun)
sun.data.energy = 4.5; sun.rotation_euler = (math.radians(55), 0, math.radians(35))
fill = bpy.data.objects.new('fill', bpy.data.lights.new('fill', 'SUN')); sc.collection.objects.link(fill)
fill.data.energy = .35; fill.rotation_euler = (math.radians(110), 0, math.radians(-140))
cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam')); sc.collection.objects.link(cam); sc.camera = cam
mesh = [o for o in bpy.context.scene.objects if o.type == 'MESH'][0]
me = mesh.data
def sample(slot, pick):
    fs = [p for p in me.polygons if me.materials[p.material_index].name == slot]
    fs.sort(key=lambda p: p.center.x * 1000 + p.center.y * 10 + p.center.z)  # deterministic
    return fs[int(len(fs) * pick)]
views = {'overall': (Vector((70, -125, 55)), Vector((0, 0, 0)), 35)}
for key, slot, pick, dist in [('pv', 'ISS | Photovoltaic cells', .5, 16), ('gold', 'ISS | Gold insulation', .5, 7),
                              ('aluminium', 'ISS | Brushed aluminium', .5, 6), ('blanket', 'ISS | Thermal blanket ivory', .5, 7)]:
    f = sample(slot, pick); n = mesh.matrix_world.to_3x3() @ f.normal; c = mesh.matrix_world @ f.center
    if n.z < 0 and key != 'pv': n = -n
    views[key] = (c + (n.normalized() * .55 + Vector((.55, -.55, .35))).normalized() * dist, c, 35)
for name, (loc, tgt, lens) in views.items():
    cam.location = loc; cam.data.lens = lens
    cam.rotation_euler = (tgt - loc).to_track_quat('-Z', 'Y').to_euler()
    sc.render.filepath = f'{prefix}_{name}.png'; bpy.ops.render.render(write_still=True)
