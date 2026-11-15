"""Reimport the finished GLB and prepare cameras for live Blender review."""
from pathlib import Path
import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[2]
source = bpy.context.scene
assert source.get('native_contract')
scene = bpy.data.scenes.new('Cabin_Final_Runtime_Review')
bpy.context.window.scene = scene
bpy.ops.import_scene.gltf(filepath=str(ROOT / 'art/exports/cabin_20261114/hull.glb'))
for obj in source.objects:
    if obj.type == 'LIGHT':
        copied = obj.copy()
        copied.data = obj.data.copy()
        scene.collection.objects.link(copied)
scene.world = source.world


def camera(name, loc, target, lens):
    obj = bpy.data.objects.new(name, bpy.data.cameras.new(name + ' lens'))
    scene.collection.objects.link(obj)
    obj.location = loc
    obj.rotation_euler = (Vector(target) - obj.location).to_track_quat('-Z', 'Y').to_euler()
    obj.data.lens = lens
    obj.data.clip_start = .05
    return obj


scene.camera = camera('Cabin overview', (0, 2.8, 1.6), (0, -5, 1.9), 20)
camera('Cabin bench detail', (-1.45, 1.55, 1.44), (3.3, -.35, .80), 31)
camera('Cabin upholstery detail', (1.45, 2.0, 1.6), (3.28, .18, .72), 47)
scene.render.engine = 'CYCLES'
scene.cycles.samples = 128
scene.cycles.use_denoising = True
scene.render.resolution_x = 1920
scene.render.resolution_y = 1234
scene.render.resolution_percentage = 100
scene.view_settings.view_transform = 'AgX'
scene.view_settings.look = 'AgX - Medium High Contrast'
scene['preview_note'] = 'Final native GLB with authoring lights; not a headset screenshot'
result = {'scene': scene.name, 'objects': len(scene.objects)}
