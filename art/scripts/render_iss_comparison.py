"""Matched Cycles previews of the staged GLBs; not Quest rendering proof."""
import bpy
from pathlib import Path
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT/'art/renders/iss_20261112'
OUT.mkdir(parents=True, exist_ok=True)
prefs = bpy.context.preferences.addons['cycles'].preferences
prefs.compute_device_type = 'OPTIX'
prefs.get_devices()
for device in prefs.devices:
    device.use = device.type == 'OPTIX'
renders = []
for label, filename in [('before','iss_before.glb'), ('after','iss.glb')]:
    scene = bpy.data.scenes.new('ISS comparison | '+label)
    bpy.context.window.scene = scene
    bpy.ops.import_scene.gltf(filepath=str(ROOT/'art/exports/iss_20261112'/filename))
    scene.render.engine = 'CYCLES'
    scene.cycles.device = 'GPU'
    scene.cycles.samples = 64
    scene.cycles.seed = 42
    scene.cycles.use_denoising = True
    scene.render.resolution_x = 1600
    scene.render.resolution_y = 1000
    scene.render.resolution_percentage = 100
    scene.view_settings.view_transform = 'AgX'
    scene.view_settings.look = 'AgX - Medium High Contrast'
    world = bpy.data.worlds.new('Earth-side ambient | '+label)
    world.use_nodes = True
    world.node_tree.nodes['Background'].inputs[0].default_value = (.16,.20,.29,1)
    world.node_tree.nodes['Background'].inputs[1].default_value = .4
    scene.world = world
    light = bpy.data.lights.new('Sun | '+label, 'SUN')
    light.energy = 4
    light.angle = .0093
    sun = bpy.data.objects.new(light.name, light)
    scene.collection.objects.link(sun)
    sun.rotation_euler = (.45,-.6,-.5)
    camera_data = bpy.data.cameras.new('Review camera | '+label)
    camera = bpy.data.objects.new(camera_data.name, camera_data)
    scene.collection.objects.link(camera)
    scene.camera = camera
    camera_data.type = 'ORTHO'
    camera_data.clip_end = 2000
    views = [
        ('station', (0,0,0), (120,-140,100), 136),
        ('modules', (0,1,0), (40,-48,28), 27),
        ('arrays', (38,4,0), (20,-25,38), 32),
    ]
    for name, target, offset, scale in views:
        target = Vector(target)
        camera.location = target+Vector(offset)
        camera.rotation_euler = (target-camera.location).to_track_quat('-Z','Y').to_euler()
        camera_data.ortho_scale = scale
        scene.render.filepath = str(OUT/(name+'_'+label+'.png'))
        bpy.ops.render.render(write_still=True)
        renders.append(scene.render.filepath)
result = {'renders':renders, 'lighting':'Matched single Sun plus Earth-side ambient', 'headset_proof':False}
