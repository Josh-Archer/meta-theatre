"""Run in the live Blender MCP session after prepare_solar_textures.py.

Preserves the open Earth source, creates editable solar-system meshes/materials,
and stages neutral GLBs for custom Quest runtime materials.
"""
import bpy, math, json, numpy as np
from pathlib import Path
from mathutils import Vector

ROOT = (Path(__file__).resolve().parents[2] if '__file__' in globals() else Path(bpy.path.abspath('//')).resolve().parents[1])
if not (ROOT / "app/src/main/assets").is_dir():
    raise RuntimeError("Execute this script with __file__ set to its path in this project.")
OUT = ROOT / 'art/exports/solar'
TEX = ROOT / 'art/textures/solar/runtime'
OUT.mkdir(parents=True, exist_ok=True)
if bpy.data.filepath:
    bpy.ops.wm.save_as_mainfile(filepath=str(ROOT / 'art/blender/earth_dynamic_before_solar.blend'), copy=True)
scene = bpy.data.scenes.new('SOLAR | Editable asset library')
bpy.context.window.scene = scene
scene.render.engine = 'BLENDER_EEVEE'
scene.render.resolution_x = 2200
scene.render.resolution_y = 1100
scene.render.resolution_percentage = 100
scene.world = bpy.data.worlds.new('Space')
scene.world.use_nodes = True
scene.world.node_tree.nodes['Background'].inputs['Strength'].default_value = 0.0
scene.view_settings.view_transform = 'Standard'

# NASA's source is scene-linear EXR. A fixed curve makes the deep catalog legible
# against dark space without turning its Milky Way background into a gray dome.
src = bpy.data.images.load(str(ROOT / 'art/textures/solar/source/starmap_2020_4k.exr'))
pixels = np.empty(len(src.pixels), dtype=np.float32)
src.pixels.foreach_get(pixels)
pixels = pixels.reshape(-1, 4)
pixels[:, :3] = np.power(np.clip(pixels[:, :3], 0, 1), 1.4) * 0.72
pixels[:, 3] = 1
stars = bpy.data.images.new('NASA catalog | runtime exposure', width=4096, height=2048, alpha=False)
stars.colorspace_settings.name = 'Linear Rec.709'
stars.pixels.foreach_set(pixels.ravel())
stars.filepath_raw = str(TEX / 'stars.png')
stars.file_format = 'PNG'
stars.save()

neutral = bpy.data.materials.new('Runtime material slot')
neutral.use_nodes = True
neutral.node_tree.nodes['Principled BSDF'].inputs['Base Color'].default_value = (1,1,1,1)
neutral.node_tree.nodes['Principled BSDF'].inputs['Roughness'].default_value = 1

def sphere(name, segments=96, rings=48, radius=1, inward=False):
    verts, uv, faces = [], [], []
    for i in range(rings+1):
        lat = math.pi*i/rings
        for j in range(segments+1):
            angle = 2*math.pi*(j/segments-.5)
            verts.append((radius*math.sin(lat)*math.sin(angle), -radius*math.sin(lat)*math.cos(angle), radius*math.cos(lat)))
            uv.append((j/segments, 1-i/rings))
    for i in range(rings):
        for j in range(segments):
            a=i*(segments+1)+j; b=a+segments+1; c=b+1; d=a+1
            face=(a,b,c) if i==0 else ((a,b,d) if i==rings-1 else (a,b,c,d))
            faces.append(tuple(reversed(face)) if inward else face)
    mesh=bpy.data.meshes.new(name+' geometry'); mesh.from_pydata(verts,[],faces); mesh.update()
    layer=mesh.uv_layers.new(name='UVMap')
    for poly in mesh.polygons:
        poly.use_smooth=True
        for loop in poly.loop_indices: layer.data[loop].uv=uv[mesh.loops[loop].vertex_index]
    obj=bpy.data.objects.new(name,mesh); scene.collection.objects.link(obj)
    mesh.materials.append(neutral)
    return obj

body=sphere('Solar_Body_Unit')
body['north_gltf']='+Y'; body['front_gltf']='+Z'; body['front_uv']=[.5,.5]
body['radius']=1.0
corona=sphere('Solar_Corona',64,32,1.08)
dome=sphere('Solar_Stars_Inward',64,32,1,True)
inner=74500/58232; outer=136780/58232
verts=[]; uvs=[]; faces=[]
for j in range(257):
    a=2*math.pi*j/256
    for radius,u in [(inner,0),(outer,1)]:
        verts.append((radius*math.cos(a),radius*math.sin(a),0)); uvs.append((u,.5))
for j in range(256):
    f=(j*2,j*2+1,j*2+3,j*2+2)
    faces.append(f)
offset=len(verts)
verts.extend(verts.copy()); uvs.extend(uvs.copy())
faces.extend([tuple(v+offset for v in reversed(f)) for f in faces.copy()])
mesh=bpy.data.meshes.new('Saturn main rings geometry'); mesh.from_pydata(verts,[],faces); mesh.update()
uv=mesh.uv_layers.new(name='UVMap')
for p in mesh.polygons:
    for loop in p.loop_indices: uv.data[loop].uv=uvs[mesh.loops[loop].vertex_index]
rings=bpy.data.objects.new('Solar_Saturn_Rings',mesh); scene.collection.objects.link(rings); mesh.materials.append(neutral)
rings['inner_radius_km']=74500; rings['outer_radius_km']=136780
rings['radius_units']='Saturn mean radius 58232 km'; rings['two_sided']='Opposite winding geometry'
parts={'body':body,'corona':corona,'stars':dome,'rings':rings}
reports={}
for key,obj in parts.items():
    bpy.ops.object.select_all(action='DESELECT'); obj.select_set(True); bpy.context.view_layer.objects.active=obj
    bpy.ops.export_scene.gltf(filepath=str(OUT / ('solar_'+key+'.glb')),export_format='GLB',use_selection=True,use_active_scene=True,export_animations=False,export_extras=True)
    obj.data.calc_loop_triangles()
    reports[key]={'triangles':len(obj.data.loop_triangles),'vertices':len(obj.data.vertices)}
    obj.hide_render=True; obj.hide_set(True)

def image_material(name, filename, emissive=False):
    mat=bpy.data.materials.new(name); mat.use_nodes=True
    nodes=mat.node_tree.nodes; nodes.clear(); links=mat.node_tree.links
    tex=nodes.new('ShaderNodeTexImage'); tex.image=bpy.data.images.load(str(TEX/filename),check_existing=True)
    output=nodes.new('ShaderNodeOutputMaterial')
    if emissive:
        bs=nodes.new('ShaderNodeEmission'); links.new(tex.outputs['Color'],bs.inputs['Color'])
        bs.inputs['Strength'].default_value=.72
    else:
        bs=nodes.new('ShaderNodeBsdfPrincipled'); links.new(tex.outputs['Color'],bs.inputs['Base Color'])
        bs.inputs['Roughness'].default_value=.9
    links.new(bs.outputs[0],output.inputs['Surface'])
    return mat

RADII={'mercury':2439.4,'venus':6051.8,'earth':6371.0084,'moon':1737.4,'mars':3389.5,'jupiter':69911,'saturn':58232,'uranus':25362,'neptune':24622,'sun':695700}
materials={name:image_material(name.title()+' | editable surface',name+'.jpg',name=='sun') for name in RADII if name!='earth'}
materials['stars']=image_material('NASA | catalog stars','stars.png',True)
ringmat=bpy.data.materials.new('Saturn | icy main rings'); ringmat.use_nodes=True
rt=ringmat.node_tree.nodes.new('ShaderNodeTexImage'); rt.image=bpy.data.images.load(str(TEX/'saturn_rings.png'))
bs=ringmat.node_tree.nodes.get('Principled BSDF'); bs.inputs['Roughness'].default_value=.92
ringmat.node_tree.links.new(rt.outputs['Color'],bs.inputs['Base Color'])
ringmat.node_tree.links.new(rt.outputs['Alpha'],bs.inputs['Alpha'])
ringmat.surface_render_method='DITHERED'; materials['rings']=ringmat

# Editable review arrangement: true body ratios; horizontal spacing is designed.
gallery=bpy.data.scenes.new('SOLAR | Size reference gallery'); gallery.world=scene.world
gallery.render.engine='BLENDER_EEVEE'; gallery.render.resolution_x=2400; gallery.render.resolution_y=1100; gallery.render.resolution_percentage=100
gallery.view_settings.view_transform='AgX'; bpy.context.window.scene=gallery
positions={'mercury':-46,'venus':-41,'earth':-36,'moon':-32,'mars':-28,'jupiter':-11,'saturn':27,'uranus':57,'neptune':69}
earthmat=bpy.data.materials.new('Earth | reference day'); earthmat.use_nodes=True
et=earthmat.node_tree.nodes.new('ShaderNodeTexImage'); et.image=bpy.data.images.load(str(ROOT/'art/textures/earth/dynamic/day.jpg'))
earthmat.node_tree.links.new(et.outputs['Color'],earthmat.node_tree.nodes['Principled BSDF'].inputs['Base Color']); materials['earth']=earthmat
for name,x in positions.items():
    obj=body.copy(); obj.data=body.data.copy(); obj.name=name.title()+' | true mean size'; gallery.collection.objects.link(obj)
    obj.hide_render=False; obj.hide_viewport=False; obj.hide_set(False)
    r=RADII[name]/RADII['earth']; obj.scale=(r,r,r); obj.location=(x,0,0)
    obj.data.materials.clear(); obj.data.materials.append(materials[name])
    obj['mean_radius_km']=RADII[name]
    if name=='saturn':
        obj.rotation_euler=(math.radians(25),math.radians(-18),0)
        ring=rings.copy(); ring.data=rings.data.copy(); gallery.collection.objects.link(ring)
        ring.name='Saturn | main rings reference'; ring.hide_render=False; ring.hide_set(False)
        ring.location=obj.location; ring.scale=obj.scale; ring.rotation_euler=obj.rotation_euler
        ring.data.materials.clear(); ring.data.materials.append(ringmat)
    curve=bpy.data.curves.new(name+' label','FONT'); curve.body=name.upper(); curve.align_x='CENTER'; curve.size=.75
    text=bpy.data.objects.new(name+' label',curve); gallery.collection.objects.link(text)
    text.location=(x,-1,-24); text.rotation_euler=(math.pi/2,0,0)
    label=bpy.data.materials.get('Reference typography')
    if label is None:
        label=bpy.data.materials.new('Reference typography'); label.use_nodes=True
        n=label.node_tree.nodes; n.clear(); em=n.new('ShaderNodeEmission'); em.inputs['Color'].default_value=(.6,.7,.8,1)
        o=n.new('ShaderNodeOutputMaterial'); label.node_tree.links.new(em.outputs[0],o.inputs[0])
    curve.materials.append(label)
bpy.ops.object.light_add(type='SUN',location=(-20,-100,60)); light=bpy.context.object
light.name='Gallery | shared Sun'; light.rotation_euler=(Vector((15,0,0))-light.location).to_track_quat('-Z','Y').to_euler(); light.data.energy=2.5; light.data.angle=math.radians(.53)
bpy.ops.object.camera_add(location=(12,-150,20)); cam=bpy.context.object
cam.rotation_euler=(Vector((12,0,-2))-cam.location).to_track_quat('-Z','Y').to_euler(); cam.data.type='ORTHO'; cam.data.ortho_scale=135; gallery.camera=cam
gallery['notes']='Mean body radii at one scale. Spacing is designed for viewing. Sun omitted here because it is 109.2 Earth radii.'
scene['notes']='Neutral reusable GLBs. Runtime loads custom materials, shared moving Sun and physically proportional body sizes. NASA catalog sky is self-lit.'
scene['radii_km']=json.dumps(RADII)
for im in bpy.data.images:
    if im.source=='FILE':
        try: im.pack()
        except RuntimeError: pass
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'art/blender/solar_system.blend'))
(ROOT/'art/validation/solar_geometry_budget.json').write_text(json.dumps(reports,indent=2)+'\n')
result={'source':str(ROOT/'art/blender/solar_system.blend'),'exports':reports,'ring_ratios':[inner,outer]}
