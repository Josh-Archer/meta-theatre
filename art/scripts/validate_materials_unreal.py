"""Import the reviewed full-detail GLBs into the existing Unreal asset lab.

Run via tools/launch-unreal-asset-lab.sh -nullrhi -unattended -ExecutePythonScript=...
NullRHI establishes import/material/save compatibility, not rendered headset quality.
"""
import json
from pathlib import Path
import unreal

ROOT = Path(__file__).resolve().parents[2]
FOLDER = '/Game/MetaTheatre/Realism20261111'
assets = unreal.EditorAssetLibrary
tools = unreal.AssetToolsHelpers.get_asset_tools()
# Reruns validate this task's own versioned assets; other folders are untouched.
report = {'renderer':'NullRHI', 'headset_proof':False, 'folder':FOLDER, 'assets':{}}
for name in ['space_roadster','lounge_chair']:
    task=unreal.AssetImportTask()
    task.set_editor_property('filename',str(ROOT/'art/exports/realism_20261111'/(name+'.glb')))
    task.set_editor_property('destination_path',FOLDER+'/'+name)
    task.set_editor_property('automated',True)
    task.set_editor_property('save',True)
    task.set_editor_property('replace_existing',False)
    task.set_editor_property('async_',False)
    if not assets.does_directory_exist(FOLDER+'/'+name):
        tools.import_asset_tasks([task])
    paths=list(assets.list_assets(FOLDER+'/'+name,recursive=True,include_folder=False))
    meshes=[];textures=[];materials=[]
    for path in paths:
        asset=assets.load_asset(path)
        if isinstance(asset,unreal.StaticMesh):
            settings=asset.get_editor_property('nanite_settings')
            settings.set_editor_property('enabled',False)
            unreal.get_editor_subsystem(unreal.StaticMeshEditorSubsystem).set_nanite_settings(asset,settings,True)
            meshes.append({'path':path,'triangles':asset.get_num_triangles(0),
                'lods':asset.get_num_lods(),'materials':len(asset.get_editor_property('static_materials'))})
        elif isinstance(asset,unreal.Texture2D):
            textures.append({'path':path,'dimensions':[asset.blueprint_get_size_x(),asset.blueprint_get_size_y()]})
        elif isinstance(asset,unreal.MaterialInterface):materials.append(path)
        assert assets.save_asset(path,only_if_is_dirty=False)
    assert meshes, 'No static mesh imported'
    # Roadster has 64 exactly zero-area pole triangles; Unreal removes them.
    expected=136836 if name=='space_roadster' else 48436
    report['roadster_zero_area_triangles_removed_by_importer']=64
    assert sum(m['triangles'] for m in meshes)==expected,(name,meshes)
    report['assets'][name]={'meshes':meshes,'textures':textures,'materials':materials,'saved':True}
(ROOT/'art/validation/realism-20261111/unreal-validation.json').write_text(json.dumps(report,indent=2)+'\n')
unreal.log('REALISM_VALIDATION_COMPLETE '+json.dumps(report))
