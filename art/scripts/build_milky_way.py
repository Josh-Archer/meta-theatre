"""Run through live Blender MCP with __file__ set. Stage, review, then publish.

NASA celestial-coordinate layers retain the existing sky's UV registration.
The editable HDR sources are never resized or overwritten.
"""
import bpy
import json
import math
import hashlib
import numpy as np
from pathlib import Path
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[2]
TEX = ROOT / 'art/textures/space'
OUT = ROOT / 'art/exports/space'
OUT.mkdir(parents=True, exist_ok=True)
(TEX / 'runtime').mkdir(parents=True, exist_ok=True)
assert 'SPACE | Milky Way review' not in bpy.data.scenes, 'Version the existing review before rebuilding'
assert bpy.context.mode == 'OBJECT'
source_dome = bpy.data.objects['Solar_Stars_Inward']

# Lift catalog star density without lifting empty sky or inventing nebulae.
# Luminance-based grading preserves the catalog's stellar colors.
def read_image(name):
    image = bpy.data.images.load(str(TEX / 'source' / name), check_existing=True)
    assert tuple(image.size) == (8192, 4096)
    pixels = np.empty(len(image.pixels), np.float32)
    image.pixels.foreach_get(pixels)
    return image, pixels.reshape(4096, 8192, 4)

diffuse_image, diffuse = read_image('milkyway_2020_8k.exr')
bright_image, bright = read_image('hiptyc_2020_8k.exr')
weights = np.array([.2126, .7152, .0722], dtype=np.float32)
luma = diffuse[:, :, :3] @ weights
toe = np.maximum(luma - .0012, 0)
gain = 2.8 * toe * toe / (toe + .006) / np.maximum(luma, 1e-8)
rgb = diffuse[:, :, :3] * gain[:, :, None] + bright[:, :, :3] * 1.10
combined_luma = rgb @ weights
rgb /= (1 + .5 * combined_luma[:, :, None])
rgba = np.ones((4096, 8192, 4), np.float32)
rgba[:, :, :3] = np.clip(rgb, 0, 1)
image = bpy.data.images.new('Milky Way | graded 8K runtime', width=8192, height=4096, alpha=False)
image.colorspace_settings.name = 'Linear Rec.709'
image.pixels.foreach_set(rgba.ravel())
image.file_format = 'PNG'
image.filepath_raw = str(TEX / 'runtime/stars.png')
image.save()
del diffuse, bright, rgb, rgba, luma, gain, toe, combined_luma

scene = bpy.data.scenes.new('SPACE | Milky Way review')
bpy.context.window.scene = scene
scene.world = bpy.data.worlds.new('Space | zero ambient')
scene.world.use_nodes = True
scene.world.node_tree.nodes['Background'].inputs['Strength'].default_value = 0
scene.render.engine = 'CYCLES'
scene.cycles.samples = 8
scene.cycles.use_denoising = False  # Denoising erases small stellar features.
try:
    prefs = bpy.context.preferences.addons['cycles'].preferences
    prefs.compute_device_type = 'OPTIX'
    prefs.get_devices()
    for device in prefs.devices:
        device.use = device.type == 'OPTIX'
    scene.cycles.device = 'GPU'
except (RuntimeError, TypeError):
    scene.cycles.device = 'CPU'
scene.render.resolution_x = 1800
scene.render.resolution_y = 1100
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = 'PNG'
scene.view_settings.view_transform = 'Standard'
scene.view_settings.look = 'None'
scene.view_settings.exposure = 0
scene.view_settings.gamma = 1

dome = source_dome.copy()
dome.data = source_dome.data.copy()
dome.name = 'Milky Way | inward celestial dome'
scene.collection.objects.link(dome)
dome.hide_viewport = False
dome.hide_render = False
dome.hide_set(False)
dome.scale = (100, 100, 100)
dome.data.materials.clear()
material = bpy.data.materials.new('Space | NASA Milky Way 8K unlit')
material.use_nodes = True
nodes = material.node_tree.nodes
nodes.clear()
tex = nodes.new('ShaderNodeTexImage')
tex.image = image
tex.interpolation = 'Linear'
tex.label = 'NASA celestial layers, graded in scene-linear light'
emission = nodes.new('ShaderNodeEmission')
emission.inputs['Strength'].default_value = .8  # Exact native sky shader multiplier.
output = nodes.new('ShaderNodeOutputMaterial')
material.node_tree.links.new(tex.outputs['Color'], emission.inputs['Color'])
material.node_tree.links.new(emission.outputs[0], output.inputs['Surface'])
dome.data.materials.append(material)

camera_data = bpy.data.cameras.new('Space | 100 degree review')
camera = bpy.data.objects.new('Space | central observer', camera_data)
scene.collection.objects.link(camera)
scene.camera = camera
camera_data.lens = 15.1
camera_data.sensor_width = 36
camera_data.clip_end = 200
# NASA's map is centered on RA 0; RA increases to the left.
ra, dec = math.radians(266.4), math.radians(-28.94)
angle = -ra
direction = Vector((math.sin(angle) * math.cos(dec), -math.cos(angle) * math.cos(dec), math.sin(dec)))
camera.rotation_euler = direction.to_track_quat('-Z', 'Y').to_euler()
scene['diffuse_exposure'] = 2.8
scene['bright_star_exposure'] = 1.10
scene['black_toe'] = .0012
scene['toe_softness'] = .006
scene['highlight_shoulder'] = .5
scene['coordinates'] = 'ICRF/J2000 celestial RA/Dec; existing native UV orientation preserved'
scene['note'] = 'Authoring preview, not a Quest capture. No artificial star parallax, twinkle, bloom or volumetric layers.'
scene['regenerate'] = 'Version this scene, then run art/scripts/build_milky_way.py through live Blender MCP.'
for source in (diffuse_image, bright_image):
    source.use_fake_user = True
    source.filepath = bpy.path.relpath(source.filepath, start=str(ROOT / 'art/blender'))
image.pack()
text = bpy.data.texts.new('build_milky_way.py')
text.write(Path(__file__).read_text())

# Share a neutral unit dome with Unreal. Android retains the existing GLB.
bpy.ops.object.select_all(action='DESELECT')
dome.select_set(True)
bpy.context.view_layer.objects.active = dome
dome.scale = (1, 1, 1)
neutral = bpy.data.materials.new('Space | neutral export slot')
neutral.use_nodes = True
dome.data.materials[0] = neutral
bpy.ops.export_scene.fbx(filepath=str(OUT / 'milky_way_dome.fbx'), use_selection=True,
    object_types={'MESH'}, add_leaf_bones=False, bake_anim=False, axis_forward='-Z', axis_up='Y')
dome.data.materials[0] = material
dome.scale = (100, 100, 100)
dome.data.calc_loop_triangles()
manifest = {
    'source_url': 'https://svs.gsfc.nasa.gov/4851/',
    'sources': [{'file': str(p.relative_to(ROOT)), 'bytes': p.stat().st_size,
        'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted((TEX / 'source').glob('*.exr'))],
    'dimensions': [8192, 4096], 'triangles': len(dome.data.loop_triangles),
    'draw_calls': 1, 'texture_samples_per_fragment': 1,
    'rgba8_mip_chain_mib': 8192 * 4096 * 4 * 4 / 3 / 1024 ** 2,
    'previous_rgba8_mip_chain_mib': 4096 * 2048 * 4 * 4 / 3 / 1024 ** 2,
    'grade': {k: scene[k] for k in ['diffuse_exposure', 'bright_star_exposure', 'black_toe', 'toe_softness', 'highlight_shoulder']},
    'headset_visual_and_performance_verified': False,
}
(ROOT / 'art/validation/milky_way_manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT / 'art/blender/milky_way.blend'))
result = manifest
