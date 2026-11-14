"""Matched native GLB material review and Starship asset presentation; not Quest captures."""
import bpy,math
from mathutils import Vector
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'art/renders/starship_20261113';OUT.mkdir(parents=True,exist_ok=True)

def studio(name,assets,aim,cam,ortho):
 s=bpy.data.scenes.new(name);bpy.context.window.scene=s
 for asset,loc in assets:
  bpy.ops.import_scene.gltf(filepath=str(asset))
  for o in bpy.context.selected_objects:
   if not o.parent:o.location+=Vector(loc)
 s.world=bpy.data.worlds.new(name+' world');s.world.use_nodes=True
 b=s.world.node_tree.nodes.get('Background');b.inputs['Color'].default_value=(.10,.12,.16,1);b.inputs['Strength'].default_value=.30
 for name,loc,power,size in [('Key',(-40,-50,65),100000,40),('Rim',(25,20,50),150000,25),('Fill',(30,-10,25),60000,30)]:
  if ortho<10:loc=tuple(v/10 for v in loc);power/=200;size/=10
  else:power*=.3
  l=bpy.data.lights.new(name,'AREA');l.energy=power;l.shape='DISK';l.size=size
  o=bpy.data.objects.new(name,l);s.collection.objects.link(o);o.location=loc;o.rotation_euler=(Vector(aim)-o.location).to_track_quat('-Z','Y').to_euler()
 bpy.ops.object.camera_add();c=bpy.context.object;s.camera=c;c.data.type='ORTHO';c.data.ortho_scale=ortho
 c.location=cam;c.rotation_euler=(Vector(aim)-c.location).to_track_quat('-Z','Y').to_euler()
 s.render.engine='CYCLES';s.cycles.samples=48;s.cycles.use_denoising=True
 p=bpy.context.preferences.addons['cycles'].preferences
 try:
  p.compute_device_type='OPTIX';p.get_devices();gpu=[d for d in p.devices if d.type=='OPTIX']
  for d in p.devices:d.use=d in gpu
  if gpu:s.cycles.device='GPU'
 except Exception:pass
 s.render.resolution_x=1400;s.render.resolution_y=1100;s.render.resolution_percentage=100
 s.view_settings.view_transform='AgX';s.view_settings.look='AgX - Medium High Contrast';s.render.image_settings.file_format='PNG'
 return s
for v,path in [('before',ROOT/'art/exports/realism_20261111/space_roadster.glb'),('after',ROOT/'art/exports/starship_20261113/space_roadster.glb')]:
 s=studio('Roadster '+v,[(path,(0,0,0))],(0,0,.65),(-5,-7,3.4),5.65)
 s.render.filepath=str(OUT/('roadster_'+v+'.png'));bpy.ops.render.render(write_still=True)
for name,cam in [('starship_tiles',(65,-100,55)),('starship_steel',(-65,100,55))]:
 s=studio(name,[(ROOT/'art/exports/starship_20261113/starship.glb',(0,0,0))],(0,0,24),cam,72)
 s.render.filepath=str(OUT/(name+'.png'));bpy.ops.render.render(write_still=True)
s=studio('Launch stack',[(ROOT/'art/exports/starship_20261113'/f,(0,0,z)) for f,z in [('starship_pad.glb',0),('super_heavy.glb',7),('starship.glb',78)]],(4,0,64),(-130,-180,105),190)
s.render.filepath=str(OUT/'launch_stack.png');bpy.ops.render.render(write_still=True)
result={'renders':str(OUT)}
