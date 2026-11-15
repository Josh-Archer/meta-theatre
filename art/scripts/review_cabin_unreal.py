"""Import the final native GLB and author a review map in the existing asset lab.

Run with UnrealEditor -ExecutePythonScript=<this file> -nullrhi
-EnablePlugins=PythonScriptPlugin,EditorScriptingUtilities. This is import and
saved-map evidence; use capture_cabin_unreal.py for a separate viewport render.
"""
import json
from pathlib import Path
import unreal

ROOT = Path(__file__).resolve().parents[2]
DEST = '/Game/MetaTheatre/Cabin20261114'
assets = unreal.EditorAssetLibrary
tools = unreal.AssetToolsHelpers.get_asset_tools()
mesh_editor = unreal.get_editor_subsystem(unreal.StaticMeshEditorSubsystem)
levels = unreal.get_editor_subsystem(unreal.LevelEditorSubsystem)
actors = unreal.get_editor_subsystem(unreal.EditorActorSubsystem)
job = unreal.AssetImportTask()
job.filename = str(ROOT / 'art/exports/cabin_20261114/hull.glb')
job.destination_path = DEST + '/Hull'
job.automated = True
job.save = True
job.replace_existing = True
job.async_ = False
tools.import_asset_tasks([job])
paths = assets.list_assets(DEST + '/Hull', recursive=True, include_folder=False)
meshes, textures, materials = [], [], []
for path in paths:
    obj = assets.load_asset(path)
    if isinstance(obj, unreal.StaticMesh):
        settings = obj.get_editor_property('nanite_settings')
        settings.set_editor_property('enabled', False)
        mesh_editor.set_nanite_settings(obj, settings, True)
        mesh_editor.remove_collisions(obj)
        meshes.append(obj)
    elif isinstance(obj, unreal.Texture2D):
        textures.append({'path': path, 'dimensions': [obj.blueprint_get_size_x(), obj.blueprint_get_size_y()]})
    elif isinstance(obj, unreal.MaterialInterface):
        materials.append(path)
    assets.save_asset(path, only_if_is_dirty=False)
expected = json.loads((ROOT / 'art/validation/cabin-20261114/export.json').read_text())
assert len(meshes) == len(expected['draws']), len(meshes)
count = sum(mesh.get_num_triangles(0) for mesh in meshes)
assert count == expected['triangles'], count
assert len(textures) == 8 and all(t['dimensions'] == [1024, 1024] for t in textures)

map_path = DEST + '/ObservationCabin'
if assets.does_asset_exist(map_path):
    assert levels.load_level(map_path)
    for actor in actors.get_all_level_actors():
        if actor.get_actor_label().startswith(('Cabin / ', 'Warm ceiling', 'Cool observation',
                                               'Bulkhead fill', 'Cabin review')):
            actors.destroy_actor(actor)
else:
    assert levels.new_level(map_path)
for mesh in meshes:
    actor = actors.spawn_actor_from_class(unreal.StaticMeshActor, unreal.Vector())
    actor.static_mesh_component.set_static_mesh(mesh)
    actor.set_actor_label('Cabin / ' + mesh.get_name())


# glTF coordinates (x,y,z) become Unreal (x,z,y)*100. Blender review coordinates
# therefore map to (x,-y,z)*100, matching the imported static meshes.
def point(x, y, z):
    return unreal.Vector(x * 100, -y * 100, z * 100)


def rect(name, loc, target, power, color, width, height):
    obj = actors.spawn_actor_from_class(unreal.RectLight, point(*loc))
    obj.set_actor_label(name)
    obj.set_actor_rotation(unreal.MathLibrary.find_look_at_rotation(point(*loc), point(*target)), False)
    light = obj.get_component_by_class(unreal.RectLightComponent)
    light.set_editor_property('intensity_units', unreal.LightUnits.LUMENS)
    light.set_intensity(power)
    light.set_light_color(unreal.LinearColor(*color))
    light.set_editor_property('source_width', width * 100)
    light.set_editor_property('source_height', height * 100)
    light.set_editor_property('attenuation_radius', 1800)
    return obj


for side in [-1, 1]:
    for yy in [-4.8, -1.6, 1.6, 4.8]:
        rect('Warm ceiling practical', (side * 1.65, yy, 4.0), (side * .7, yy, 0),
             1800, (1, .86, .69), .25, 3)
    rect('Cool observation aperture', (side * 4.1, .5, 2.3), (0, .5, 1.2),
         2800, (.52, .72, 1), 6, 2)
rect('Bulkhead fill', (0, -5.7, 3.5), (0, -7, 1.5), 1600, (1, .84, .64), 3, .4)
camera = actors.spawn_actor_from_class(unreal.CineCameraActor, point(0, 2.8, 1.6))
camera.set_actor_label('Cabin review - Blender matched framing')
camera.set_actor_rotation(unreal.MathLibrary.find_look_at_rotation(point(0, 2.8, 1.6), point(0, -5, 1.9)), False)
component = camera.get_cine_camera_component()
component.set_editor_property('current_focal_length', 20)
film = component.get_editor_property('filmback')
film.sensor_width = 36
film.sensor_height = 36 * 1234 / 1920
component.set_editor_property('filmback', film)
focus = component.get_editor_property('focus_settings')
focus.focus_method = unreal.CameraFocusMethod.DISABLE
component.set_editor_property('focus_settings', focus)
pp = component.get_editor_property('post_process_settings')
pp.override_auto_exposure_method = True
pp.auto_exposure_method = unreal.AutoExposureMethod.AEM_MANUAL
pp.override_auto_exposure_bias = True
pp.auto_exposure_bias = -4
pp.override_auto_exposure_apply_physical_camera_exposure = True
pp.auto_exposure_apply_physical_camera_exposure = False
component.set_editor_property('post_process_settings', pp)
assert levels.save_current_level()
assets.save_directory(DEST)
report = {'folder': DEST, 'map': map_path, 'triangles': count,
          'meshes': [{'path': m.get_path_name(), 'triangles': m.get_num_triangles(0),
                      'lods': m.get_num_lods(), 'materials': len(m.get_editor_property('static_materials'))} for m in meshes],
          'textures': textures, 'materials': materials, 'saved': True,
          'renderer': 'NullRHI import and map authoring', 'headset_verified': False}
(ROOT / 'art/validation/cabin-20261114/unreal.json').write_text(json.dumps(report, indent=2) + '\n')
unreal.log('CABIN_UNREAL_IMPORT_OK ' + json.dumps(report))
