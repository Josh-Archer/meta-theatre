"""Import/reload the reviewed assets in the existing sandbox. NullRHI is not a visual test."""
import unreal,json,hashlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'art/validation/starship-20261113'
FOLDER='/Game/MetaTheatre/Starship20261113'
assets=unreal.EditorAssetLibrary;tools=unreal.AssetToolsHelpers.get_asset_tools()
record=OUT/'unreal-import.json'
names=['starship','super_heavy','starship_pad','starship_plume','space_roadster']
hashes={n:hashlib.sha256((ROOT/'art/exports/starship_20261113'/(n+'.glb')).read_bytes()).hexdigest() for n in names}
reload=record.exists() and json.loads(record.read_text()).get('sha256')==hashes
report={'renderer':'NullRHI','headsetProof':False,'freshReload':reload,'sha256':hashes,'assets':{}}
for name in names:
 folder=FOLDER+'/'+name
 if not reload:
  task=unreal.AssetImportTask();task.filename=str(ROOT/'art/exports/starship_20261113'/(name+'.glb'));task.destination_path=folder
  task.automated=True;task.save=True;task.replace_existing=True;task.async_=False
  tools.import_asset_tasks([task])
 meshes=[];textures=[]
 for path in assets.list_assets(folder,recursive=True,include_folder=False):
  a=assets.load_asset(path)
  if isinstance(a,unreal.StaticMesh):
   sub=unreal.get_editor_subsystem(unreal.StaticMeshEditorSubsystem)
   n=a.get_editor_property('nanite_settings');n.enabled=False;sub.set_nanite_settings(a,n,True)
   meshes.append({'path':path,'triangles':a.get_num_triangles(0),'materials':len(a.get_editor_property('static_materials')),'lods':a.get_num_lods()})
  elif isinstance(a,unreal.Texture2D):textures.append({'path':path,'size':[a.blueprint_get_size_x(),a.blueprint_get_size_y()]})
  assert assets.save_asset(path,only_if_is_dirty=False)
 assert len(meshes)==1 and meshes[0]['lods']==1,(name,meshes)
 assert meshes[0]['materials']==dict(starship=4,super_heavy=3,starship_pad=3,starship_plume=1,space_roadster=10)[name]
 report['assets'][name]={'meshes':meshes,'textures':textures}
(OUT/('unreal-reload.json' if reload else 'unreal-import.json')).write_text(json.dumps(report,indent=2)+'\n')
unreal.log('STARSHIP_VALIDATION_COMPLETE '+json.dumps(report))
