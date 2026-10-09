"""Studio front/rear/side verification of the actual export geometry, executed via live MCP."""
import bpy, math
from pathlib import Path
from mathutils import Vector
ROOT=Path(__file__).resolve().parents[2];scene=bpy.context.scene
assert scene.name in {'ROADSTER v3 | Reference design','ROADSTER v3 | Runtime round trip'}
studio=bpy.data.collections.new('ROADSTER v3 | Studio review');scene.collection.children.link(studio)
def studio_object(o):
    for c in list(o.users_collection):c.objects.unlink(o)
    studio.objects.link(o)
    return o
world=bpy.data.worlds.new('Roadster neutral studio');scene.world=world;world.use_nodes=True
world.node_tree.nodes.get('Background').inputs['Color'].default_value=(.15,.17,.21,1)
world.node_tree.nodes.get('Background').inputs['Strength'].default_value=.18
for name,loc,power,size in [('Key softbox',(1,-3,5),650,5),('Side strip',(-3,0,2.8),500,4),('Rim softbox',(1,4,3),800,3)]:
    data=bpy.data.lights.new(name,'AREA');data.energy=power;data.shape='DISK';data.size=size
    o=bpy.data.objects.new(name,data);studio.objects.link(o);o.location=loc;o.rotation_euler=(Vector((0,0,.5))-o.location).to_track_quat('-Z','Y').to_euler()
bpy.ops.mesh.primitive_plane_add(size=200,location=(0,0,-.018));floor=studio_object(bpy.context.object)
mat=bpy.data.materials.new('Studio graphite');mat.diffuse_color=(.055,.064,.08,1);mat.use_nodes=True
p=mat.node_tree.nodes.get('Principled BSDF');p.inputs['Base Color'].default_value=(.055,.064,.08,1);p.inputs['Roughness'].default_value=.38
floor.data.materials.append(mat)
bpy.ops.object.camera_add();camera=studio_object(bpy.context.object);scene.camera=camera
camera.data.type='ORTHO';camera.data.ortho_scale=5.65
scene.render.engine='CYCLES';scene.cycles.samples=40;scene.cycles.use_denoising=True
prefs=bpy.context.preferences.addons['cycles'].preferences
try:
    prefs.compute_device_type='OPTIX';prefs.get_devices()
    for d in prefs.devices:d.use=d.type=='OPTIX'
    scene.cycles.device='GPU'
except Exception:scene.cycles.device='CPU'
scene.render.resolution_x=1400;scene.render.resolution_y=900;scene.render.resolution_percentage=100
scene.view_settings.view_transform='AgX';scene.view_settings.look='AgX - Medium High Contrast';scene.render.image_settings.file_format='PNG'
views=[('front',(5,-7,2.20)),('rear',(5,7,2.8)),('side',(-6,0,1.7))]
OUT=ROOT/'art/validation/roadster-v3'
if scene.name.endswith('Runtime round trip'):OUT=OUT/'roundtrip'
OUT.mkdir(parents=True,exist_ok=True)
for name,pos in views:
    camera.location=pos;camera.rotation_euler=(Vector((0,0,.63))-camera.location).to_track_quat('-Z','Y').to_euler()
    scene.render.filepath=str(OUT/(name+'.png'));bpy.ops.render.render(write_still=True)
result={'renders':[name+'.png' for name,_ in views]}
