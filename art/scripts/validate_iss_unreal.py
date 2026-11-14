"""Import and fresh-load the staged ISS into the existing Unreal asset lab.

NullRHI checks interoperability and saved content, not rendered appearance or
Quest runtime performance. No project, level or app migration is performed.
"""
import json
import hashlib
from pathlib import Path
import unreal

ROOT = Path(__file__).resolve().parents[2]
FOLDER = '/Game/MetaTheatre/ISS20261112'
(ROOT/'art/validation/iss-20261112').mkdir(parents=True, exist_ok=True)
assets = unreal.EditorAssetLibrary
task = unreal.AssetImportTask()
task.set_editor_property('filename',str(ROOT/'art/exports/iss_20261112/iss.glb'))
task.set_editor_property('destination_path',FOLDER)
task.set_editor_property('automated',True)
task.set_editor_property('save',True)
task.set_editor_property('replace_existing',True)
task.set_editor_property('async_',False)
source_sha256 = hashlib.sha256((ROOT/'art/exports/iss_20261112/iss.glb').read_bytes()).hexdigest()
import_record = ROOT/'art/validation/iss-20261112/unreal-import.json'
fresh_load = (import_record.exists() and
    json.loads(import_record.read_text()).get('source_sha256') == source_sha256 and
    assets.does_directory_exist(FOLDER))
if not fresh_load:
    unreal.AssetToolsHelpers.get_asset_tools().import_asset_tasks([task])
meshes = []; textures = []; materials = []
for path in assets.list_assets(FOLDER,recursive=True,include_folder=False):
    asset = assets.load_asset(path)
    if isinstance(asset,unreal.StaticMesh):
        subsystem = unreal.get_editor_subsystem(unreal.StaticMeshEditorSubsystem)
        build = subsystem.get_lod_build_settings(asset,0)
        build.set_editor_property('remove_degenerates',False)
        build.set_editor_property('recompute_normals',False)
        build.set_editor_property('recompute_tangents',False)
        build.set_editor_property('use_high_precision_tangent_basis',True)
        build.set_editor_property('use_full_precision_u_vs',True)
        subsystem.set_lod_build_settings(asset,0,build)
        settings = asset.get_editor_property('nanite_settings')
        settings.set_editor_property('enabled',False)
        subsystem.set_nanite_settings(asset,settings,True)
        meshes.append({'path':path, 'triangles':asset.get_num_triangles(0),
            'lods':asset.get_num_lods(), 'materials':len(asset.get_editor_property('static_materials'))})
    elif isinstance(asset,unreal.Texture2D):
        textures.append({'path':path, 'dimensions':[asset.blueprint_get_size_x(),asset.blueprint_get_size_y()]})
    elif isinstance(asset,unreal.MaterialInterface):
        materials.append(path)
    assert assets.save_asset(path,only_if_is_dirty=False)
assert len(meshes)==1 and meshes[0]['materials']==4 and meshes[0]['lods']==1, meshes
# 53 index-degenerate faces are reported by Khronos. Unreal also omits 2,286
# collinear triangles: all 2,339 omitted faces have EXACTLY zero geometric area.
# validate_iss_assets.py compares the full rendered triangle multiset below.
assert meshes[0]['triangles']==35526, meshes
assert len(textures)==8, textures
assert len(materials)==4, materials
sections = []
mesh = assets.load_asset(meshes[0]['path'])
for index in range(4):
    section = unreal.ProceduralMeshLibrary.get_section_from_static_mesh(mesh,0,index)
    sections.append({'vertices':[[v.x,v.y,v.z] for v in section[0]], 'indices':list(section[1])})
(ROOT/'art/validation/iss-20261112/unreal-sections.json').write_text(json.dumps(sections))
result = {'renderer':'NullRHI', 'headset_proof':False, 'fresh_load':fresh_load,
    'meshes':meshes, 'textures':textures, 'materials':materials, 'saved':True,
    'source_sha256':source_sha256}
name = 'unreal-reload.json' if fresh_load else 'unreal-import.json'
(ROOT/'art/validation/iss-20261112'/name).write_text(json.dumps(result,indent=2)+'\n')
unreal.log('ISS_VALIDATION_COMPLETE '+json.dumps(result))
