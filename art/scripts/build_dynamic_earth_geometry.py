"""Run through Blender MCP with earth.blend open; preserve the static authored scene.
Geographic center: equator, 100 W. Geometry faces Blender -Y / glTF +Z.
Runtime code supplies live materials and a time-dependent sun direction.
"""
import bpy, math, json
from pathlib import Path
from mathutils import Matrix, Vector
ROOT=(Path(__file__).resolve().parents[2] if '__file__' in globals() else Path(bpy.path.abspath('//')).resolve().parents[1])
if not (ROOT / "app/src/main/assets").is_dir():
    raise RuntimeError("Execute this script with __file__ set to its path in this project.")
OUT=ROOT/'art/exports/earth_dynamic'; OUT.mkdir(parents=True,exist_ok=True)
if 'EARTH | Local time authoring' in bpy.data.scenes:
    raise RuntimeError('Dynamic scene already exists; preserve edits before rebuilding.')
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'art/blender/earth_static_before_dynamic.blend'),copy=True)
scene=bpy.data.scenes.new('EARTH | Local time authoring'); bpy.context.window.scene=scene
scene.render.engine='BLENDER_EEVEE'; scene.render.resolution_x=1200; scene.render.resolution_y=1200; scene.render.resolution_percentage=100
scene.world=bpy.data.worlds.new('Dynamic Earth | space'); scene.world.use_nodes=True
scene.world.node_tree.nodes['Background'].inputs['Color'].default_value=(.00005,.0001,.00025,1)
scene.world.node_tree.nodes['Background'].inputs['Strength'].default_value=.1
scene.view_settings.view_transform='Standard'; scene.view_settings.look='None'
old=Matrix.Rotation(math.radians(15),4,'X') @ Matrix.Rotation(math.radians(-110),4,'Z')
new=Matrix.Rotation(math.radians(10),4,'Z')
reorient=new @ old.inverted()
parts={}
for part,old_name,radius in [('surface','Earth_twilight_Surface',1.0),('clouds','Earth_twilight_CloudDeck',1.0035),('atmosphere','Earth_twilight_Atmosphere',1.012)]:
    col=bpy.data.collections.new('EARTH_DYNAMIC_'+part.upper()); scene.collection.children.link(col)
    source=bpy.data.objects[old_name]
    ob=source.copy(); ob.data=source.data.copy(); ob.name='Earth_Dynamic_'+part.title(); col.objects.link(ob)
    ob.data.transform(reorient); ob.data.materials.clear()
    # The former atmosphere's baked vertex colors must not affect the dynamic shader.
    for attr in list(ob.data.color_attributes): ob.data.color_attributes.remove(attr)
    material=bpy.data.materials.new('Earth_Dynamic_'+part.title()+'_RuntimeSlot'); material.use_nodes=True
    bs=material.node_tree.nodes.get('Principled BSDF'); bs.inputs['Base Color'].default_value=(1,1,1,1); bs.inputs['Roughness'].default_value=1
    ob.data.materials.append(material)
    ob['center_longitude_degrees']=-100.0; ob['center_latitude_degrees']=0.0
    ob['front_gltf']='+Z'; ob['north_gltf']='+Y'; ob['radius_metres']=radius
    ob['runtime_material']='Assigned by Meta Spatial SDK; no baked lighting'
    bpy.ops.object.select_all(action='DESELECT'); ob.select_set(True); bpy.context.view_layer.objects.active=ob
    bpy.ops.export_scene.gltf(filepath=str(OUT/('earth_dynamic_'+part+'.glb')),export_format='GLB',use_selection=True,use_active_scene=True,export_animations=False,export_extras=True)
    parts[part]=ob
# Studio camera sees the exact target coordinate in the image center.
bpy.ops.object.camera_add(location=(0,-3.4,0))
cam=bpy.context.object; cam.name='Earth_Dynamic_Equator100W_Camera'; cam.rotation_euler=(Vector((0,0,0))-cam.location).to_track_quat('-Z','Y').to_euler()
cam.data.type='ORTHO'; cam.data.ortho_scale=2.3; scene.camera=cam
scene['center_longitude_degrees']=-100.0; scene['center_latitude_degrees']=0.0
scene['local_hour']=12.0
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'art/blender/earth_dynamic.blend'))
result={'source':str(ROOT/'art/blender/earth_dynamic.blend'),'geometry':[str(OUT/('earth_dynamic_'+p+'.glb')) for p in parts], 'center':[-100.0,0.0]}
