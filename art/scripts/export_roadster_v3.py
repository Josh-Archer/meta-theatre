"""Evaluate the Roadster scene into one indexed runtime mesh and an Unreal review FBX."""
import bpy, json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
scene=bpy.context.scene
assert scene.name=='ROADSTER v3 | Open cockpit'
col=next(c for c in scene.collection.children if c.name.startswith('ROADSTER v3 | Editable components'))
bpy.context.view_layer.update();deps=bpy.context.evaluated_depsgraph_get();copies=[]
for o in col.all_objects:
    if o.type not in {'MESH','FONT','CURVE'}:continue
    me=bpy.data.meshes.new_from_object(o.evaluated_get(deps),depsgraph=deps)
    cp=bpy.data.objects.new(o.name+' runtime',me);scene.collection.objects.link(cp)
    cp.matrix_world=o.matrix_world.copy();copies.append(cp)
bpy.ops.object.select_all(action='DESELECT')
for o in copies:o.select_set(True)
bpy.context.view_layer.objects.active=copies[0];bpy.ops.object.join();joined=bpy.context.object
joined.name='space_roadster'
# Applying scale before normals export avoids any reliance on negative node transforms.
bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
bpy.ops.object.mode_set(mode='EDIT');bpy.ops.mesh.select_all(action='SELECT')
bpy.ops.mesh.quads_convert_to_tris(quad_method='BEAUTY',ngon_method='BEAUTY');bpy.ops.object.mode_set(mode='OBJECT')
joined.data.validate(clean_customdata=True);joined.data.calc_loop_triangles()
report={'triangles':len(joined.data.loop_triangles),'vertices':len(joined.data.vertices),
        'materials':len(joined.data.materials),'components':len(col.objects),'dimensions_m':list(joined.dimensions)}
OUT=ROOT/'art/exports/flybys';OUT.mkdir(parents=True,exist_ok=True)
bpy.ops.export_scene.gltf(filepath=str(OUT/'space_roadster.glb'),export_format='GLB',use_selection=True,
    use_active_scene=True,export_apply=True,export_yup=True,export_texcoords=True,export_normals=True,
    export_tangents=False,export_materials='EXPORT',export_draco_mesh_compression_enable=False)
bpy.ops.export_scene.fbx(filepath=str(OUT/'space_roadster.fbx'),use_selection=True,object_types={'MESH'},
    apply_unit_scale=True,axis_forward='-Y',axis_up='Z',use_mesh_modifiers=True,bake_anim=False,path_mode='COPY',embed_textures=True)
bpy.data.objects.remove(joined,do_unlink=True)
for o in col.all_objects:
    for mat in getattr(o.data,'materials',[]):
        if mat and mat.use_nodes:
            for node in mat.node_tree.nodes:
                if node.type=='TEX_IMAGE' and node.image and node.image.name.startswith('Roadster carbon twill'):
                    node.image.filepath='//../textures/roadster/carbon_twill.png'
# Save just this scene and dependencies; retain all other live scenes in the session.
bpy.data.libraries.write(str(ROOT/'art/blender/space_roadster.blend'),{scene},fake_user=True,compress=True)
(ROOT/'art/validation/roadster-open/authoring-report.json').write_text(json.dumps(report,indent=2))
result=report
