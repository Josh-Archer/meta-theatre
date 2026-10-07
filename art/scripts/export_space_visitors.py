import bpy,json
from pathlib import Path
ROOT=(Path(__file__).resolve().parents[2] if '__file__' in globals() else Path(bpy.path.abspath('//')).resolve().parents[1])
if not (ROOT / "app/src/main/assets").is_dir():
    raise RuntimeError("Execute this script with __file__ set to its path in this project.")
OUT=ROOT/'art/exports/flybys';scene=bpy.context.scene
bpy.context.view_layer.update();deps=bpy.context.evaluated_depsgraph_get();reports={}
for name,colname in [('iss','ISS | Quest optimized'),('space_roadster','ROADSTER | Editable components')]:
 col=bpy.data.collections[colname];copies=[]
 for o in list(col.objects):
  if o.type!='MESH':continue
  mesh=bpy.data.meshes.new_from_object(o.evaluated_get(deps),depsgraph=deps)
  p=bpy.data.objects.new(o.name+' export',mesh);scene.collection.objects.link(p);p.matrix_world=o.matrix_world.copy();copies.append(p)
 bpy.ops.object.select_all(action='DESELECT')
 for p in copies:p.select_set(True)
 bpy.context.view_layer.objects.active=copies[0];bpy.ops.object.join();joined=bpy.context.object;joined.name=name
 # Consistent indexed triangle output, no Draco or quantization requirements.
 bpy.ops.object.mode_set(mode='EDIT');bpy.ops.mesh.select_all(action='SELECT');bpy.ops.mesh.quads_convert_to_tris(quad_method='BEAUTY',ngon_method='BEAUTY');bpy.ops.object.mode_set(mode='OBJECT')
 joined.data.validate(verbose=False,clean_customdata=True)
 joined.data.calc_loop_triangles()
 reports[name]={'triangles':len(joined.data.loop_triangles),'vertices':len(joined.data.vertices),'materials':len(joined.data.materials),'dimensions_m':list(joined.dimensions),'components':len(col.objects)}
 bpy.ops.export_scene.gltf(filepath=str(OUT/(name+'.glb')),export_format='GLB',use_selection=True,use_active_scene=True,export_apply=True,export_yup=True,export_texcoords=True,export_normals=True,export_tangents=False,export_materials='EXPORT',export_draco_mesh_compression_enable=False)
 bpy.ops.export_scene.fbx(filepath=str(OUT/(name+'.fbx')),use_selection=True,object_types={'MESH'},apply_unit_scale=True,axis_forward='-Y',axis_up='Z',use_mesh_modifiers=True,bake_anim=False,path_mode='COPY',embed_textures=True)
 bpy.data.objects.remove(joined,do_unlink=True)
for im in bpy.data.images:
 if 'art/textures/flybys' in bpy.path.abspath(im.filepath):im.pack()
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'art/blender/orbital_visitors.blend'))
(ROOT/'art/validation/flybys/authoring-report.json').write_text(json.dumps(reports,indent=2))
result=reports
