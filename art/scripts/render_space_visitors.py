import bpy,math
from pathlib import Path
from mathutils import Vector
ROOT=(Path(__file__).resolve().parents[2] if '__file__' in globals() else Path(bpy.path.abspath('//')).resolve().parents[1])
if not (ROOT / "app/src/main/assets").is_dir():
    raise RuntimeError("Execute this script with __file__ set to its path in this project.")
scene=bpy.context.scene
scene.render.engine='CYCLES';scene.cycles.samples=24;scene.cycles.use_denoising=True
scene.render.resolution_x=1280;scene.render.resolution_y=800;scene.render.resolution_percentage=100
world=bpy.data.worlds.new('Orbital studio');world.use_nodes=True
world.node_tree.nodes['Background'].inputs[0].default_value=(.035,.047,.075,1);world.node_tree.nodes['Background'].inputs[1].default_value=.45;scene.world=world
studio=bpy.data.collections.new('STUDIO | Preview only');scene.collection.children.link(studio)
for name,rotation,power in [('Sun key',(.45,-.6,-.5),3.3),('Sun rim',(1.3,.8,2.5),1.8)]:
 d=bpy.data.lights.new(name,'SUN');d.energy=power;d.angle=.06
 o=bpy.data.objects.new(name,d);studio.objects.link(o);o.rotation_euler=rotation
camData=bpy.data.cameras.new('Visitors review camera');cam=bpy.data.objects.new('Visitors review camera',camData);studio.objects.link(cam);scene.camera=cam
camData.type='ORTHO';camData.clip_end=2000
iss=bpy.data.collections['ISS | Quest optimized'];car=bpy.data.collections['ROADSTER | Editable components']
results=[]
for name,target,distance,scale in [('iss',Vector((0,0,0)),Vector((120,-140,100)),145),('roadster',Vector((0,0,.65)),Vector((5,-6,3.8)),5.5)]:
 iss.hide_render=name!='iss';car.hide_render=name!='roadster'
 cam.location=target+distance;cam.rotation_euler=(target-cam.location).to_track_quat('-Z','Y').to_euler();camData.ortho_scale=scale
 scene.render.filepath=str(ROOT/f'art/validation/flybys/{name}-preview.png')
 bpy.ops.render.render(write_still=True);results.append(scene.render.filepath)
iss.hide_render=False;car.hide_render=False
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'art/blender/orbital_visitors.blend'))
result={'renders':results}
