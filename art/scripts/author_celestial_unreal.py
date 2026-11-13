"""Import reviewed celestial maps into the existing Unreal asset sandbox.

The native application uses GLSL; these saved PBR/unlit materials are editable
Unreal review assets, not a claim of matching BRDFs or headset rendering.
"""
import json
from pathlib import Path
import unreal

ROOT = Path(__file__).resolve().parents[2]
FOLDER = '/Game/MetaTheatre/Celestial20261111'
assets = unreal.EditorAssetLibrary
tools = unreal.AssetToolsHelpers.get_asset_tools()
lib = unreal.MaterialEditingLibrary
world = unreal.get_editor_subsystem(unreal.UnrealEditorSubsystem).get_editor_world().get_path_name()
assets.make_directory(FOLDER)


def import_file(path, name):
    destination = FOLDER + '/' + name
    if not assets.does_asset_exist(destination):
        task = unreal.AssetImportTask()
        task.set_editor_property('filename', str(path))
        task.set_editor_property('destination_path', FOLDER)
        task.set_editor_property('destination_name', name)
        task.set_editor_property('automated', True)
        task.set_editor_property('save', True)
        if path.suffix == '.fbx':
            options = unreal.FbxImportUI()
            options.set_editor_property('import_materials', False)
            options.set_editor_property('import_textures', False)
            options.static_mesh_import_data.set_editor_property('combine_meshes', True)
            options.static_mesh_import_data.set_editor_property('generate_lightmap_u_vs', False)
            task.set_editor_property('options', options)
        tools.import_asset_tasks([task])
    asset = assets.load_asset(destination)
    assert asset is not None, destination
    return asset


stage = ROOT / 'art/textures/celestial_20261111'
textures = {}
for name in ['moon', 'moon_normal', 'stars', 'mercury', 'venus', 'mars', 'jupiter', 'saturn', 'uranus', 'neptune']:
    suffix = '.png' if name == 'moon_normal' else '.jpg'
    folder = stage if name in ['moon', 'moon_normal', 'stars'] else ROOT / 'app/src/main/assets/solar'
    texture = import_file(folder / (name + suffix), name)
    texture.set_editor_property('srgb', name != 'moon_normal')
    texture.set_editor_property('max_texture_size', 8192 if name == 'stars' else 2048)
    texture.set_editor_property('lod_bias', 0)
    texture.set_editor_property('address_x', unreal.TextureAddress.TA_WRAP)
    texture.set_editor_property('address_y', unreal.TextureAddress.TA_CLAMP)
    if name == 'moon_normal':
        texture.set_editor_property('compression_settings', unreal.TextureCompressionSettings.TC_NORMALMAP)
        texture.set_editor_property('flip_green_channel', True)  # OpenGL source -> Unreal convention.
    textures[name] = texture

mesh = import_file(ROOT / 'art/exports/celestial_20261111/planet_review.fbx', 'planet_review')
dome = import_file(ROOT / 'art/exports/space/milky_way_dome.fbx', 'milky_way_dome')
for model in [mesh, dome]:
    settings = model.get_editor_property('nanite_settings'); settings.set_editor_property('enabled', False)
    unreal.get_editor_subsystem(unreal.StaticMeshEditorSubsystem).set_nanite_settings(model, settings, True)

for name, texture in textures.items():
    if name == 'moon_normal': continue
    path = FOLDER + '/M_' + name
    material = assets.load_asset(path) if assets.does_asset_exist(path) else tools.create_asset('M_' + name, FOLDER, unreal.Material, unreal.MaterialFactoryNew())
    lib.delete_all_material_expressions(material)
    sample = lib.create_material_expression(material, unreal.MaterialExpressionTextureSample, -500, 0)
    sample.set_editor_property('texture', texture)
    if name == 'stars':
        material.set_editor_property('shading_model', unreal.MaterialShadingModel.MSM_UNLIT)
        material.set_editor_property('two_sided', True)
        strength = lib.create_material_expression(material, unreal.MaterialExpressionScalarParameter, -500, 200)
        strength.set_editor_property('parameter_name', 'SkyExposure'); strength.set_editor_property('default_value', .8)
        multiply = lib.create_material_expression(material, unreal.MaterialExpressionMultiply, -200, 0)
        assert lib.connect_material_expressions(sample, 'RGB', multiply, 'A')
        assert lib.connect_material_expressions(strength, '', multiply, 'B')
        assert lib.connect_material_property(multiply, '', unreal.MaterialProperty.MP_EMISSIVE_COLOR)
        dome.set_material(0, material)
    else:
        assert lib.connect_material_property(sample, 'RGB', unreal.MaterialProperty.MP_BASE_COLOR)
        roughness = lib.create_material_expression(material, unreal.MaterialExpressionConstant, -500, 200)
        roughness.set_editor_property('r', 1.0)
        assert lib.connect_material_property(roughness, '', unreal.MaterialProperty.MP_ROUGHNESS)
        specular = lib.create_material_expression(material, unreal.MaterialExpressionConstant, -500, 350)
        specular.set_editor_property('r', 0.0)
        assert lib.connect_material_property(specular, '', unreal.MaterialProperty.MP_SPECULAR)
        if name == 'moon':
            normal = lib.create_material_expression(material, unreal.MaterialExpressionTextureSample, -500, -250)
            normal.set_editor_property('texture', textures['moon_normal'])
            normal.set_editor_property('sampler_type', unreal.MaterialSamplerType.SAMPLERTYPE_NORMAL)
            assert lib.connect_material_property(normal, 'RGB', unreal.MaterialProperty.MP_NORMAL)
            mesh.set_material(0, material)
    lib.recompile_material(material)
    assets.set_metadata_tag(material, 'NativeRenderer', 'Meta Spatial custom GLSL; Unreal material is an editable review reference')

paths = list(assets.list_assets(FOLDER, recursive=True, include_folder=False))
for path in paths: assert assets.save_asset(path, only_if_is_dirty=False)
assert unreal.get_editor_subsystem(unreal.UnrealEditorSubsystem).get_editor_world().get_path_name() == world
report = {'folder': FOLDER, 'assets': paths,
    'textures': {name: [t.blueprint_get_size_x(), t.blueprint_get_size_y()] for name, t in textures.items()},
    'planet_triangles': mesh.get_num_triangles(0), 'sky_triangles': dome.get_num_triangles(0),
    'nanite': False, 'open_level_preserved': True,
    'validation': 'NullRHI import/material/save only; native shader rendering tested separately on EGL'}
(ROOT / 'art/validation/celestial-20261111/unreal.json').write_text(json.dumps(report, indent=2) + '\n')
unreal.log('CELESTIAL_ASSETS_SAVED ' + json.dumps(report))
