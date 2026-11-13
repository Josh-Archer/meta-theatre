"""Matched studio renders of actual baseline and staged GLBs, not headset captures."""
import bpy
from mathutils import Vector
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'art/renders/realism_20261111';OUT.mkdir(parents=True,exist_ok=True)
for name in ['space_roadster','lounge_chair']:
    for version,folder in [('before',ROOT/'art/validation/realism-20261111/baseline'),('after',ROOT/'art/exports/realism_20261111')]:
        scene=bpy.data.scenes.new(name+' '+version+' GLB review')
        bpy.context.window.scene=scene
        bpy.ops.import_scene.gltf(filepath=str(folder/(name+'.glb')))
        scene.world=bpy.data.worlds.new('Neutral comparison studio');scene.world.use_nodes=True
        background=scene.world.node_tree.nodes.get('Background')
        background.inputs['Color'].default_value=(.15,.17,.21,1)
        background.inputs['Strength'].default_value=.18
        chair=name=='lounge_chair'
        scale=.34 if chair else 1
        aim=Vector((0,0,.02 if chair else .63))
        for title,loc,power,size in [('Key',(1,-3,5),650,5),('Fill',(-3,0,2.8),500,4),('Rim',(1,4,3),800,3)]:
            data=bpy.data.lights.new(title,'AREA');data.energy=power*scale*scale;data.shape='DISK';data.size=size*scale
            obj=bpy.data.objects.new(title,data);scene.collection.objects.link(obj)
            obj.location=Vector(loc)*scale
            obj.rotation_euler=(aim-obj.location).to_track_quat('-Z','Y').to_euler()
        bpy.ops.mesh.primitive_plane_add(size=200,location=(0,0,-.391 if chair else -.018))
        floor=bpy.context.object
        material=bpy.data.materials.new('Studio graphite');material.use_nodes=True
        p=material.node_tree.nodes.get('Principled BSDF');p.inputs['Base Color'].default_value=(.055,.064,.08,1);p.inputs['Roughness'].default_value=.5
        floor.data.materials.append(material)
        bpy.ops.object.camera_add();camera=bpy.context.object;scene.camera=camera
        camera.data.type='ORTHO';camera.data.ortho_scale=1.6 if chair else 5.65
        camera.location=(1.45,-1.85,1.03) if chair else (-5,-7,3.4)
        camera.rotation_euler=(aim-camera.location).to_track_quat('-Z','Y').to_euler()
        scene.render.engine='CYCLES';scene.cycles.samples=64;scene.cycles.use_denoising=True
        prefs=bpy.context.preferences.addons['cycles'].preferences
        try:
            prefs.compute_device_type='OPTIX';prefs.get_devices()
            available=[d for d in prefs.devices if d.type=='OPTIX']
            if available:
                for d in prefs.devices:d.use=d in available
                scene.cycles.device='GPU'
        except Exception: pass
        scene.render.resolution_x=1400;scene.render.resolution_y=1100 if chair else 900
        scene.render.resolution_percentage=100;scene.render.image_settings.file_format='PNG'
        scene.view_settings.view_transform='AgX';scene.view_settings.look='AgX - Medium High Contrast'
        scene.render.filepath=str(OUT/(name+'_'+version+'.png'))
        bpy.ops.render.render(write_still=True)
result={'folder':str(OUT),'renders':4,'source':'GLB round trip; identical cameras, lighting, exposure, samples'}
