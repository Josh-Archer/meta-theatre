"""Run in the existing MetaTheatreAssetLab Unreal editor, with WEATHER_ROOT set.

Only creates the Weather folder; preserves the open level. Android uses the
same atlas through its native Earth shader, not Unreal packages.
"""
import unreal, json
from pathlib import Path
root = Path(WEATHER_ROOT)
folder = '/Game/MetaTheatre/Weather'
assets = unreal.EditorAssetLibrary
assert not assets.does_directory_exist(folder), 'Inspect/version the weather folder before replacing assets'
assets.make_directory(folder)
tools = unreal.AssetToolsHelpers.get_asset_tools()
tasks=[]
for filename in ['art/exports/weather/weather_patterns.fbx', 'art/textures/weather/cloud_patterns.png']:
    task=unreal.AssetImportTask()
    task.set_editor_property('filename', str(root/filename))
    task.set_editor_property('destination_path',folder)
    task.set_editor_property('automated',True)
    task.set_editor_property('save',True)
    if filename.endswith('.fbx'):
        options=unreal.FbxImportUI()
        options.set_editor_property('import_materials',False)
        options.set_editor_property('import_textures',False)
        options.set_editor_property('import_as_skeletal',False)
        options.static_mesh_import_data.set_editor_property('combine_meshes',False)
        options.static_mesh_import_data.set_editor_property('generate_lightmap_u_vs',False)
        task.set_editor_property('options',options)
    tasks.append(task)
tools.import_asset_tasks(tasks)
texture=assets.load_asset(folder+'/cloud_patterns')
assert texture is not None
texture.set_editor_property('srgb',False)
texture.set_editor_property('compression_settings',unreal.TextureCompressionSettings.TC_DEFAULT)
material=tools.create_asset('M_OrbitalCloud',folder,unreal.Material,unreal.MaterialFactoryNew())
material.set_editor_property('blend_mode',unreal.BlendMode.BLEND_TRANSLUCENT)
material.set_editor_property('two_sided',True)
material.set_editor_property('shading_model',unreal.MaterialShadingModel.MSM_UNLIT)
lib=unreal.MaterialEditingLibrary
uv=lib.create_material_expression(material,unreal.MaterialExpressionTextureCoordinate,-800,0)
scale=lib.create_material_expression(material,unreal.MaterialExpressionConstant2Vector,-800,150)
scale.set_editor_property('r',.25);scale.set_editor_property('g',.5)
mul=lib.create_material_expression(material,unreal.MaterialExpressionMultiply,-550,0)
lib.connect_material_expressions(uv,'',mul,'A');lib.connect_material_expressions(scale,'',mul,'B')
offset=lib.create_material_expression(material,unreal.MaterialExpressionVectorParameter,-550,200)
offset.set_editor_property('parameter_name','TileOffset')
offset.set_editor_property('default_value',unreal.LinearColor(.25,0,0,0))
add=lib.create_material_expression(material,unreal.MaterialExpressionAdd,-300,0)
lib.connect_material_expressions(mul,'',add,'A')
mask=lib.create_material_expression(material,unreal.MaterialExpressionComponentMask,-350,200)
mask.set_editor_property('r',True);mask.set_editor_property('g',True)
mask.set_editor_property('b',False);mask.set_editor_property('a',False)
assert lib.connect_material_expressions(offset,'',mask,'')
assert lib.connect_material_expressions(mask,'',add,'B')
sample=lib.create_material_expression(material,unreal.MaterialExpressionTextureSample,-80,0)
sample.set_editor_property('texture',texture)
lib.connect_material_expressions(add,'',sample,'UVs')
lib.connect_material_property(sample,'RGB',unreal.MaterialProperty.MP_EMISSIVE_COLOR)
lib.connect_material_property(sample,'A',unreal.MaterialProperty.MP_OPACITY)
lib.recompile_material(material)
instances=[]
for i,name in enumerate(['clear','cumulus','overcast','fog','rain_front','snow_bands','thunderhead','cirrus']):
    instance=tools.create_asset('MI_'+name,folder,unreal.MaterialInstanceConstant,unreal.MaterialInstanceConstantFactoryNew())
    lib.set_material_instance_parent(instance,material)
    lib.set_material_instance_vector_parameter_value(instance,'TileOffset',unreal.LinearColor((i%4)*.25,(i//4)*.5,0,0))
    instances.append(instance)
paths=assets.list_assets(folder,recursive=True,include_folder=False)
for path in paths: assets.save_asset(path,only_if_is_dirty=False)
summary={'imported':len(paths),'materials':len(instances)+1,'texture':[texture.blueprint_get_size_x(),texture.blueprint_get_size_y()],
    'folder':folder,'open_level_preserved':True,'runtime':'Native Android Earth cloud shader consumes the shared authored density atlas.'}
(root/'art/exports/weather/unreal_authoring.json').write_text(json.dumps(summary,indent=2)+'\n')
unreal.log('Weather authoring complete')
