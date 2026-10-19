"""Separate Blender lighting reference; runtime uses the three existing meshes and custom shaders.

Load earth_detailed.blend through Blender MCP first. No existing source or geometry is replaced.
"""
from pathlib import Path
import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[2]
scene = bpy.context.scene
assert all('Detailed Earth | ' + part in scene.objects for part in ('Surface', 'Clouds', 'Atmosphere'))
checkpoint = ROOT / 'art/validation/earth-scattering-before.blend'
bpy.ops.wm.save_as_mainfile(filepath=str(checkpoint), copy=True)
scene.name = 'EARTH | Scattering and night reference'
scene.render.resolution_x = 1200
scene.render.resolution_y = 1200
scene.cycles.samples = 48
scene.render.use_stamp = False
scene['reference_only'] = 'Cycles authoring reference, not native shader output or a headset capture.'
scene['native_runtime'] = 'Three unchanged meshes, 8K land and city maps, 4K cloud density and profiles.'

surface = scene.objects['Detailed Earth | Surface'].active_material
for node in surface.node_tree.nodes:
    if node.bl_idname == 'ShaderNodeMapRange' and node.inputs['To Min'].default_value > 2:
        node.inputs['To Min'].default_value = 3.2
cloud = scene.objects['Detailed Earth | Clouds'].active_material
for node in cloud.node_tree.nodes:
    if node.bl_idname == 'ShaderNodeBump':
        node.inputs['Strength'].default_value = .9
        node.inputs['Distance'].default_value = .0025

# The full sphere volume is stopped by the opaque surface. Only the thin outer shell is visible.
# Native rendering approximates this shell's optical path analytically without a volume ray march.
atmosphere = scene.objects['Detailed Earth | Atmosphere'].active_material
nodes = atmosphere.node_tree.nodes
nodes.clear()
output = nodes.new('ShaderNodeOutputMaterial')
scatter = nodes.new('ShaderNodeVolumeScatter')
scatter.inputs['Color'].default_value = (.09, .30, .72, 1)
scatter.inputs['Density'].default_value = 1.8
scatter.inputs['Anisotropy'].default_value = .15
atmosphere.node_tree.links.new(scatter.outputs[0], output.inputs['Volume'])

output_dir = ROOT / 'art/renders/earth_scattering'
output_dir.mkdir(parents=True, exist_ok=True)
sun = scene.objects['Detailed Earth | Sun']
for label, direction in [('daylight', (-.55, -.8, .28)), ('twilight', (-.97, -.1, .05)), ('night', (-.2, .95, .1))]:
    vector = Vector(direction).normalized()
    sun.rotation_euler = (-vector).to_track_quat('-Z', 'Y').to_euler()
    for node in surface.node_tree.nodes:
        if node.bl_idname == 'ShaderNodeVectorMath' and node.operation == 'DOT_PRODUCT':
            node.inputs[1].default_value = vector
    scene.render.filepath = str(output_dir / (label + '.png'))
    bpy.ops.render.render(write_still=True)

scene.render.filepath = '//../renders/earth_scattering/night.png'
for image in bpy.data.images:
    if image.source == 'FILE':
        image.filepath = '//../../app/src/main/assets/earth/' + Path(image.filepath).name
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT / 'art/blender/earth_scattering.blend'), compress=True)
result = {'reference': 'art/blender/earth_scattering.blend', 'renders': ['daylight', 'twilight', 'night'],
          'runtime_geometry_changed': False}
