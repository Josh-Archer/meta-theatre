"""Author orbital cloud patterns in the live Blender MCP scene; preserve editable volumes.

Execute with __file__ set to this project script. The density atlas stays in
art/textures for Unreal and reference. The native app does not package it.
"""
import bpy, math, json
import numpy as np
from pathlib import Path
from mathutils import Vector
ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'art/blender/weather_patterns.blend'
TEX = ROOT / 'art/textures/weather/cloud_patterns.png'
OUT = ROOT / 'art/exports/weather'
if SOURCE.exists():
    raise RuntimeError('Weather source already exists; inspect and version it before replacing.')
checkpoint=ROOT/'art/validation/weather-source-checkpoints/before_weather_patterns.blend'
checkpoint.parent.mkdir(parents=True,exist_ok=True)
bpy.ops.wm.save_as_mainfile(filepath=str(checkpoint), copy=True)
scene = bpy.data.scenes.new('WEATHER | Orbital cloud library')
bpy.context.window.scene = scene
for existing_scene in bpy.data.scenes:
    for layer in existing_scene.view_layers:
        for ob in layer.objects: ob.select_set(False, view_layer=layer)
scene.unit_settings.system = 'METRIC'
scene.render.engine = 'BLENDER_EEVEE'
scene.render.film_transparent = False
scene.render.resolution_x, scene.render.resolution_y = 1600, 1000
scene.render.resolution_percentage = 100
scene.view_settings.view_transform = 'AgX'
scene.world = bpy.data.worlds.new('Weather preview | deep orbital blue')
scene.world.use_nodes = True
scene.world.node_tree.nodes['Background'].inputs[0].default_value = (.015,.025,.05,1)
scene.world.node_tree.nodes['Background'].inputs[1].default_value = .25
library = bpy.data.collections.new('Editable density volumes'); scene.collection.children.link(library)
exports = bpy.data.collections.new('Neutral export meshes'); scene.collection.children.link(exports)
exports.hide_render = False
mat = bpy.data.materials.new('Cloud | soft scattering authoring reference'); mat.use_nodes = True
bsdf = mat.node_tree.nodes.get('Principled BSDF')
bsdf.inputs['Base Color'].default_value=(.86,.92,1,1)
bsdf.inputs['Roughness'].default_value=.98
bsdf.inputs['Subsurface Weight'].default_value=.20
noise=mat.node_tree.nodes.new('ShaderNodeTexNoise');noise.inputs['Scale'].default_value=7;noise.inputs['Detail'].default_value=5
bump=mat.node_tree.nodes.new('ShaderNodeBump');bump.inputs['Strength'].default_value=.25;bump.inputs['Distance'].default_value=.08
mat.node_tree.links.new(noise.outputs['Fac'],bump.inputs['Height']);mat.node_tree.links.new(bump.outputs['Normal'],bsdf.inputs['Normal'])
neutral = bpy.data.materials.new('Weather export | neutral white'); neutral.diffuse_color=(1,1,1,1)
neutral.use_nodes=True;neutral.node_tree.nodes['Principled BSDF'].inputs['Roughness'].default_value=1
names=['clear','cumulus','overcast','fog','rain_front','snow_bands','thunderhead','cirrus']
labels=['Clear','Cumulus','Overcast','Fog / low stratus','Rain front','Snow clouds','Thunderhead','High cirrus']
N=1024
axis=np.linspace(-1,1,N,dtype=np.float32); X,Y=np.meshgrid(axis,axis)
atlas=np.ones((N*2,N*4,4),dtype=np.float32);atlas[:,:,3]=0
stats=[]
for index,name in enumerate(names):
    if index==0: continue
    rng=np.random.default_rng(400+index)
    frequency=np.fft.fftfreq(N)[:,None]**2+np.fft.rfftfreq(N)[None,:]**2
    spectrum=(rng.normal(size=(N,N//2+1))+1j*rng.normal(size=(N,N//2+1)))*np.maximum(frequency,1/N**2)**(-.85)
    texture=np.fft.irfft2(spectrum,s=(N,N)).real
    texture=(texture-texture.mean())/texture.std()
    texture=texture.astype(np.float32)
    centers=[]
    if name=='cumulus':
        for k in range(22): centers.append((rng.uniform(-.8,.8),rng.uniform(-.75,.75),rng.uniform(.10,.22),rng.uniform(.09,.20),rng.uniform(.12,.28)))
    elif name in ('overcast','fog'):
        for k in range(40): centers.append((rng.uniform(-.95,.95),rng.uniform(-.85,.85),rng.uniform(.22,.40),rng.uniform(.12,.24), .055 if name=='fog' else .10))
    elif name in ('rain_front','snow_bands','cirrus'):
        for row in range(4 if name!='cirrus' else 6):
            for k in range(9):
                x=-.9+k*.225; y=-.7+row*(.42 if name!='cirrus' else .28)+.20*math.sin(x*3+row)
                centers.append((x,y,.22,.10 if name!='cirrus' else .045,.12 if name!='cirrus' else .045))
    else:
        for k in range(25):
            angle=rng.uniform(0,math.tau);rad=rng.uniform(0,.6)
            centers.append((math.cos(angle)*rad,math.sin(angle)*rad,.20,.18,rng.uniform(.20,.42)))
        for k in range(12): centers.append((-.75+k*.13,.30,.24,.19,.10))
    field=np.zeros((N,N),dtype=np.float32)
    meta=bpy.data.metaballs.new('Density | '+name);meta.resolution=.10;meta.render_resolution=.075;meta.threshold=.6
    obj=bpy.data.objects.new('Weather | '+name,meta);library.objects.link(obj);meta.materials.append(mat)
    for cx,cy,sx,sy,sz in centers:
        field+=np.exp(-((X-cx)/sx)**2-((Y-cy)/sy)**2)
        e=meta.elements.new();e.type='ELLIPSOID';e.co=(cx,cy,sz*.7);e.radius=1;e.size_x=sx*1.7;e.size_y=sy*1.7;e.size_z=sz
    if name=='fog': density=np.clip((field+.18*texture)*.50,0,1)
    elif name=='cirrus': density=np.clip((field+.20*texture-.15)*.65,0,1)
    elif name=='cumulus': density=np.clip((field+.25*texture-.26)*1.25,0,1)
    else: density=np.clip((field+.28*texture-.10)*.75,0,1)
    feather=np.clip((1-np.maximum(abs(X),abs(Y)))*9,0,1)
    density*=feather
    row,col=divmod(index,4);atlas[row*N:(row+1)*N,col*N:(col+1)*N,3]=density
    # Retain editable volumes and independent neutral meshes for staging/export.
    bpy.context.view_layer.objects.active=obj;obj.select_set(True)
    bpy.context.view_layer.update()
    dg=bpy.context.evaluated_depsgraph_get(); mesh=bpy.data.meshes.new_from_object(obj.evaluated_get(dg))
    export=bpy.data.objects.new('WX_'+name,mesh);exports.objects.link(export);mesh.materials.clear();mesh.materials.append(neutral)
    mesh.calc_loop_triangles();stats.append({'name':name,'triangles':len(mesh.loop_triangles)})
    for other in scene.objects: other.select_set(False)
    export.hide_set(False);export.select_set(True);bpy.context.view_layer.objects.active=export
    bpy.ops.export_scene.gltf(filepath=str(OUT/(name+'.glb')),export_format='GLB',use_selection=True,export_extras=False)
    export.select_set(False);export.hide_set(True)
    obj.location=((col-1.5)*3.1,(.5-row)*3.2,0)
    data=bpy.data.curves.new('Caption | '+name,'FONT');data.body=labels[index];data.align_x='CENTER';data.size=.23
    label=bpy.data.objects.new('Label | '+name,data);scene.collection.objects.link(label);label.location=(obj.location.x,obj.location.y-1.30,.02)
image=bpy.data.images.new('Weather | authored density atlas',width=N*4,height=N*2,alpha=True)
image.pixels.foreach_set(np.flipud(atlas).ravel());image.filepath_raw=str(TEX);image.file_format='PNG';image.save()
# Editable source retains a relative authored texture path. Do not copy the atlas
# into app/src/main/assets; the native renderer uses cloud_structure.png.
image.filepath='//../textures/weather/cloud_patterns.png'
# FBX staging uses the seven neutral model meshes, not preview captions or lights.
for o in scene.objects:o.select_set(False)
exports.hide_viewport=False
for o in exports.objects:o.hide_set(False);o.select_set(True)
bpy.context.view_layer.objects.active=next(iter(exports.objects))
bpy.ops.export_scene.fbx(filepath=str(OUT/'weather_patterns.fbx'),use_selection=True,object_types={'MESH'},bake_anim=False,path_mode='STRIP',add_leaf_bones=False)
for o in exports.objects:o.hide_set(True);o.select_set(False)
exports.hide_render=True
for pos,power,size in [((0,-4,10),1300,9),((-6,2,7),1000,7)]:
    data=bpy.data.lights.new('Weather softbox','AREA');data.energy=power;data.shape='DISK';data.size=size
    o=bpy.data.objects.new(data.name,data);scene.collection.objects.link(o);o.location=pos;o.rotation_euler=(Vector((0,0,0))-o.location).to_track_quat('-Z','Y').to_euler()
camera=bpy.data.objects.new('Weather library camera',bpy.data.cameras.new('Weather library camera'));scene.collection.objects.link(camera)
camera.location=(0,-8,15);camera.rotation_euler=(Vector((0,-.3,0))-camera.location).to_track_quat('-Z','Y').to_euler();camera.data.type='ORTHO';camera.data.ortho_scale=13.2;scene.camera=camera
scene.render.filepath=str(ROOT/'art/renders/weather/cloud_library.png')
bpy.ops.render.render(write_still=True)
scene.render.filepath='//../renders/weather/cloud_library.png'
# Scene-only library serialization excludes workstation UI/file-browser history.
bpy.data.libraries.write(str(SOURCE),{scene,image},path_remap='RELATIVE',fake_user=True,compress=True)
manifest={'atlas':[4096,2048],'tile':[1024,1024],'columns':4,'rows':2,'patterns':names,'meshes':stats,'runtime':'Density atlas on the existing Earth cloud shell; no added per-frame mesh draws.'}
(ROOT/'art/exports/weather/manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
namespace={'__file__':str(ROOT/'art/scripts/sanitize_weather_metadata.py')}
exec(compile((ROOT/'art/scripts/sanitize_weather_metadata.py').read_text(),'sanitize_weather_metadata.py','exec'),namespace)
result={'source_saved':True,'atlas_saved':True,'models':stats,'preview':'cloud_library.png'}
