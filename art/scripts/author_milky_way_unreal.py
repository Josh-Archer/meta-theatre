"""Import into the existing asset lab with Unreal's -ExecutePythonScript.

Creates only /Game/MetaTheatre/Space/MilkyWay20261019. Preserves the open level.
Run with the project's existing tools/launch-unreal-asset-lab.sh launcher.
"""
import unreal
import json
from pathlib import Path

ROOT = Path('/home/gawly/code/meta-theatre')
FOLDER = '/Game/MetaTheatre/Space/MilkyWay20261019'
assets = unreal.EditorAssetLibrary
assert not assets.does_directory_exist(FOLDER), 'Version existing authored sky assets before rerunning'
level = unreal.get_editor_subsystem(unreal.UnrealEditorSubsystem).get_editor_world().get_path_name()
assets.make_directory(FOLDER)
tools = unreal.AssetToolsHelpers.get_asset_tools()
tasks = []
for file in ['art/exports/space/milky_way_dome.fbx', 'art/textures/space/runtime/stars.jpg']:
    task = unreal.AssetImportTask()
    task.set_editor_property('filename', str(ROOT / file))
    task.set_editor_property('destination_path', FOLDER)
    task.set_editor_property('automated', True)
    task.set_editor_property('save', True)
    if file.endswith('.fbx'):
        options = unreal.FbxImportUI()
        options.set_editor_property('import_materials', False)
        options.set_editor_property('import_textures', False)
        options.set_editor_property('import_as_skeletal', False)
        options.static_mesh_import_data.set_editor_property('combine_meshes', True)
        options.static_mesh_import_data.set_editor_property('generate_lightmap_u_vs', False)
        task.set_editor_property('options', options)
    tasks.append(task)
tools.import_asset_tasks(tasks)
texture = assets.load_asset(FOLDER + '/stars')
mesh = assets.load_asset(FOLDER + '/milky_way_dome')
assert texture is not None and mesh is not None
texture.set_editor_property('srgb', True)
texture.set_editor_property('max_texture_size', 8192)
texture.set_editor_property('lod_bias', 0)
texture.set_editor_property('address_x', unreal.TextureAddress.TA_WRAP)
texture.set_editor_property('address_y', unreal.TextureAddress.TA_CLAMP)
material = tools.create_asset('M_MilkyWay', FOLDER, unreal.Material, unreal.MaterialFactoryNew())
material.set_editor_property('shading_model', unreal.MaterialShadingModel.MSM_UNLIT)
material.set_editor_property('two_sided', True)
lib = unreal.MaterialEditingLibrary
sample = lib.create_material_expression(material, unreal.MaterialExpressionTextureSample, -500, 0)
sample.set_editor_property('texture', texture)
strength = lib.create_material_expression(material, unreal.MaterialExpressionScalarParameter, -500, 180)
strength.set_editor_property('parameter_name', 'SkyExposure')
strength.set_editor_property('default_value', .8)
multiply = lib.create_material_expression(material, unreal.MaterialExpressionMultiply, -200, 0)
assert lib.connect_material_expressions(sample, 'RGB', multiply, 'A')
assert lib.connect_material_expressions(strength, '', multiply, 'B')
assert lib.connect_material_property(multiply, '', unreal.MaterialProperty.MP_EMISSIVE_COLOR)
lib.recompile_material(material)
mesh.set_material(0, material)
mesh.set_editor_property('allow_cpu_access', False)
paths = assets.list_assets(FOLDER, recursive=True, include_folder=False)
for path in paths:
    assert assets.save_asset(path, only_if_is_dirty=False)
assert unreal.get_editor_subsystem(unreal.UnrealEditorSubsystem).get_editor_world().get_path_name() == level
report = {
    'folder': FOLDER, 'assets': list(paths),
    'texture_dimensions': [texture.blueprint_get_size_x(), texture.blueprint_get_size_y()],
    'texture_max_size': texture.get_editor_property('max_texture_size'),
    'texture_lod_bias': texture.get_editor_property('lod_bias'),
    'material': 'Unlit, sRGB texture, exposure 0.8, U repeat / V clamp',
    'open_level_preserved': True,
    'renderer': 'NullRHI: asset import/material/save proof only; no Unreal render proof',
}
(ROOT / 'art/validation/milky_way_unreal.json').write_text(json.dumps(report, indent=2) + '\n')
unreal.log('MILKY_WAY_AUTHORING_COMPLETE ' + json.dumps(report))
