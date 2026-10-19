"""Inspect native 8K cloud detail in a separate close-up Blender reference.

Run through Blender MCP with earth_scattering.blend loaded. This is a Cycles
lighting reference; native shader appearance and performance need Quest checks.
"""
from pathlib import Path
import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[2]
scene = bpy.context.scene
assert 'Detailed Earth | Clouds' in scene.objects
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT / 'art/validation/earth-before-close-clouds.blend'), copy=True)
for image in bpy.data.images:
    if image.source == 'FILE':
        image.reload()
cloud_image = next(image for image in bpy.data.images if image.name.startswith('clouds.png'))
assert tuple(cloud_image.size) == (8192, 4096)
scene.name = 'EARTH | Native 8K cloud close reference'
scene['native_runtime'] = '8K optical depth, multi-scale native cloud slopes; unchanged three meshes.'
scene['reference_only'] = 'Cycles lighting reference, not native shader output or a headset capture.'
scene.camera.data.ortho_scale = 1.25
vector = Vector((-.55, -.8, .28)).normalized()
scene.objects['Detailed Earth | Sun'].rotation_euler = (-vector).to_track_quat('-Z', 'Y').to_euler()
for node in scene.objects['Detailed Earth | Surface'].active_material.node_tree.nodes:
    if node.bl_idname == 'ShaderNodeVectorMath' and node.operation == 'DOT_PRODUCT':
        node.inputs[1].default_value = vector
scene.render.use_stamp = False
output = ROOT / 'art/renders/earth_close_clouds'
output.mkdir(parents=True, exist_ok=True)
scene.render.filepath = str(output / 'close-clouds.png')
bpy.ops.render.render(write_still=True)
scene.render.filepath = '//../renders/earth_close_clouds/close-clouds.png'
bpy.data.libraries.write(str(ROOT / 'art/blender/earth_close_clouds.blend'), {scene}, path_remap='RELATIVE_ALL', fake_user=True, compress=True)
result = {'cloud_source': list(cloud_image.size), 'source': 'art/blender/earth_close_clouds.blend',
          'render': 'art/renders/earth_close_clouds/close-clouds.png', 'runtime_geometry_changed': False}
