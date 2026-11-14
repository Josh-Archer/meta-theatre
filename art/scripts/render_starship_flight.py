"""Render a preview from poses exported by the tested Kotlin flight evaluator.
Export with META_THEATRE_FLIGHT_PREVIEW=/absolute/path/flight-frames.json ./gradlew testDebugUnitTest.
The overview camera is for this Blender preview only; the Quest camera never moves.
"""
import bpy,json,math
from pathlib import Path
from mathutils import Vector,Quaternion,Matrix
ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'art/validation/starship-20261113'
OUT=DATA/'flight-frames';OUT.mkdir(parents=True,exist_ok=True)
frames=json.loads((DATA/'flight-frames.json').read_text())
s=bpy.data.scenes.new('STARSHIP | Native trajectory preview');bpy.context.window.scene=s
assets={}
for name in ['starship','super_heavy','starship_pad','starship_plume']:
 bpy.ops.import_scene.gltf(filepath=str(ROOT/'art/exports/starship_20261113'/(name+'.glb')))
 assets[name]=next(o for o in s.objects if o.name.startswith(name) and o.type=='MESH')
assets['booster_plume']=assets['starship_plume'].copy();s.collection.objects.link(assets['booster_plume'])
# Blender uses Z-up; native Meta uses Y-up.
def pos(p):return Vector((p[0],-p[2],p[1]))
def quat(q):return Quaternion((q[0],q[1],-q[3],q[2]))
def place(obj,pose,scale=.12):
 obj.hide_render=pose is None
 if pose:
  obj.location=pos(pose['p']);obj.rotation_mode='QUATERNION';obj.rotation_quaternion=quat(pose['q']);obj.scale=(scale,)*3
# Earth uses the same authored albedo as the app; this is not its custom atmosphere shader.
bpy.ops.mesh.primitive_uv_sphere_add(segments=192,ring_count=96,radius=90,location=(18,-150,-35));earth=bpy.context.object
for p in earth.data.polygons:p.use_smooth=True
m=bpy.data.materials.new('Preview Earth');m.use_nodes=True
p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=1
im=m.node_tree.nodes.new('ShaderNodeTexImage')
path=ROOT/'app/src/main/assets/earth/day.jpg'
if not path.exists():path=next((ROOT/'app/src/main/assets/earth').glob('*day*'))
im.image=bpy.data.images.load(str(path));m.node_tree.links.new(im.outputs['Color'],p.inputs['Base Color']);earth.data.materials.append(m)
s.world=bpy.data.worlds.new('Space');s.world.use_nodes=True;b=s.world.node_tree.nodes.get('Background');b.inputs['Color'].default_value=(.008,.012,.022,1);b.inputs['Strength'].default_value=.3
for name,loc,power,size in [('Key',(-100,30,180),350000,80),('Fill',(180,60,60),160000,120)]:
 l=bpy.data.lights.new(name,'AREA');l.energy=power;l.shape='DISK';l.size=size;o=bpy.data.objects.new(name,l);s.collection.objects.link(o);o.location=loc;o.rotation_euler=(Vector((18,-150,-35))-o.location).to_track_quat('-Z','Y').to_euler()
bpy.ops.object.camera_add();cam=bpy.context.object;s.camera=cam;cam.data.lens=42
fill_data=bpy.data.lights.new('Review camera softbox','AREA');fill_data.energy=80000;fill_data.size=18
fill=bpy.data.objects.new('Review camera softbox',fill_data);s.collection.objects.link(fill)
# A readable medium shot tracks the ship without changing any flight pose.
s.render.engine='CYCLES';s.cycles.samples=16;s.cycles.use_denoising=True
prefs=bpy.context.preferences.addons['cycles'].preferences
try:
 prefs.compute_device_type='OPTIX';prefs.get_devices();gpu=[d for d in prefs.devices if d.type=='OPTIX']
 for d in prefs.devices:d.use=d in gpu
 if gpu:s.cycles.device='GPU'
except Exception:pass
s.view_settings.view_transform='AgX';s.view_settings.look='AgX - Medium High Contrast'
s.render.resolution_x=1024;s.render.resolution_y=768;s.render.resolution_percentage=100;s.render.image_settings.file_format='PNG'
for i,frame in enumerate(frames):
 if 'frame_indices' in globals() and i not in frame_indices:continue
 place(assets['starship'],frame['ship']);place(assets['super_heavy'],frame['booster']);place(assets['starship_pad'],frame['pad'])
 for key,pose,power,size in [('starship_plume',frame['ship'],frame['shipThrust'],.65),('booster_plume',frame['booster'],frame['boosterThrust'],1.6)]:
  o=assets[key];place(o,pose if pose and power>.01 else None,size*power)
  if pose:o.location+=quat(pose['q'])@Vector((0,0,-.096))
 target=assets['starship'].location+assets['starship'].rotation_quaternion@Vector((0,0,3))
 # Track the coast from outside the orbit so Earth cannot obscure the spacecraft.
 # This moving review camera is not used by the native app.
 u=max(0,min(1,(frame['t']-20)/8))*(1-max(0,min(1,(frame['t']-70)/20)));u=u*u*(3-2*u)
 pad_rotation=quat(frame['pad']['q']);up=pad_rotation@Vector((0,0,1))
 radial=(target-earth.location).normalized();plane_normal=pad_rotation@Vector((0,-1,0))
 overview=target+radial*27+plane_normal*18
 close_target=target-up*(5*(1-max(0,min(1,(frame['t']-20)/8))))
 close=close_target+pad_rotation@Vector((-16,-26,8))
 cam.location=close.lerp(overview,u);aim=close_target.lerp(target-radial*2,u)
 forward=(aim-cam.location).normalized();camera_up=up.lerp(radial,u).normalized()
 camera_right=forward.cross(camera_up).normalized();camera_up=camera_right.cross(forward).normalized()
 cam.rotation_euler=Matrix((camera_right,camera_up,-forward)).transposed().to_euler()
 fill.location=cam.location+camera_up*6;fill.rotation_euler=(target-fill.location).to_track_quat('-Z','Y').to_euler()
 s.render.filepath=str(OUT/f'{i:04d}.png');bpy.ops.render.render(write_still=True)
result={'frames':len(frames),'folder':str(OUT),'source':'Kotlin native flight evaluator','preview_playback':'6x at 12 fps'}
