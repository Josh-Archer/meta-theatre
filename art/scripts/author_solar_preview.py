"""Create a seated Blender reference from the runtime layout. Not headset proof."""
import bpy, math
from mathutils import Vector, Matrix, Euler
from pathlib import Path
ROOT=(Path(__file__).resolve().parents[2] if '__file__' in globals() else Path(bpy.path.abspath('//')).resolve().parents[1])
if not (ROOT / "app/src/main/assets").is_dir():
    raise RuntimeError("Execute this script with __file__ set to its path in this project.")
SUN_DISTANCE=149597870.7/6371.0084*90
SUN_RENDER_DISTANCE=80000
scene=bpy.data.scenes.new('SOLAR | Seated theatre reference')
bpy.context.window.scene=scene
scene.render.engine='BLENDER_EEVEE'; scene.render.resolution_x=1800; scene.render.resolution_y=1200; scene.render.resolution_percentage=100
scene.world=bpy.data.worlds['Space']; scene.view_settings.view_transform='Standard'
scene['local_hour']=9.0
scene.id_properties_ui('local_hour').update(min=0,max=24,description='The same equatorial civil-time cycle as the Quest scene')
eye=Vector((0,0,1.6)); earth_center=Vector((18,150,-35))
def facing(center):
    front=(eye-center).normalized(); right=Vector((0,0,1)).cross(front).normalized(); up=right.cross(-front)
    return Matrix((right,-front,up)).transposed().to_quaternion()
def copy_obj(source,name,center,radius,material=None,rotation=None):
    ob=bpy.data.objects[source].copy(); ob.data=ob.data.copy(); ob.name=name; scene.collection.objects.link(ob)
    ob.hide_render=False; ob.hide_viewport=False; ob.hide_set(False); ob.location=center; ob.scale=(radius,)*3
    ob.rotation_mode='QUATERNION'; ob.rotation_quaternion=rotation or facing(Vector(center))
    if material:
        ob.data.materials.clear(); ob.data.materials.append(material)
    return ob
sun=bpy.data.objects.new('Shared Sun | local civil time',None); scene.collection.objects.link(sun)
q=facing(earth_center); matrix=q.to_matrix()
for i in range(3):
    driver=sun.driver_add('location',i).driver
    # Convert the SDK world Sun into Blender world coordinates; API +X maps to -local mesh X.
    driver.expression=f'{earth_center[i]}+{SUN_DISTANCE}*({matrix[i][0]}*sin((hour-12)*pi/12)+{-matrix[i][1]}*cos((hour-12)*pi/12))'
    v=driver.variables.new();v.name='hour';v.type='SINGLE_PROP';v.targets[0].id_type='SCENE';v.targets[0].id=scene;v.targets[0].data_path='["local_hour"]'

def sun_direction(nodes,links,geo):
    xyz=nodes.new('ShaderNodeCombineXYZ'); xyz.label='Shared world Sun position'
    for i in range(3):
        d=xyz.inputs[i].driver_add('default_value').driver;d.expression='coordinate'
        v=d.variables.new();v.name='coordinate';v.type='TRANSFORMS';v.targets[0].id=sun;v.targets[0].transform_type=['LOC_X','LOC_Y','LOC_Z'][i];v.targets[0].transform_space='WORLD_SPACE'
    sub=nodes.new('ShaderNodeVectorMath');sub.operation='SUBTRACT';links.new(xyz.outputs[0],sub.inputs[0]);links.new(geo.outputs['Position'],sub.inputs[1])
    norm=nodes.new('ShaderNodeVectorMath');norm.operation='NORMALIZE';links.new(sub.outputs[0],norm.inputs[0]);return norm.outputs[0]

def lit_material(name):
    mat=bpy.data.materials.new(name+' | shared Sun reference');mat.use_nodes=True
    n=mat.node_tree.nodes;n.clear();l=mat.node_tree.links
    geo=n.new('ShaderNodeNewGeometry'); direction=sun_direction(n,l,geo)
    dot=n.new('ShaderNodeVectorMath');dot.operation='DOT_PRODUCT';l.new(direction,dot.inputs[0]);l.new(geo.outputs['Normal'],dot.inputs[1])
    pos=n.new('ShaderNodeMath');pos.operation='MAXIMUM';l.new(dot.outputs['Value'],pos.inputs[0]);pos.inputs[1].default_value=0
    add=n.new('ShaderNodeMath');add.operation='MULTIPLY_ADD';l.new(pos.outputs[0],add.inputs[0]);add.inputs[1].default_value=1.05;add.inputs[2].default_value=.002
    tex=n.new('ShaderNodeTexImage');tex.image=bpy.data.images.load(str(ROOT/'art/textures/solar/runtime'/(name+'.jpg')),check_existing=True)
    scale=n.new('ShaderNodeVectorMath');scale.operation='SCALE';l.new(tex.outputs[0],scale.inputs[0]);l.new(add.outputs[0],scale.inputs['Scale'])
    em=n.new('ShaderNodeEmission');l.new(scale.outputs[0],em.inputs[0]);out=n.new('ShaderNodeOutputMaterial');l.new(em.outputs[0],out.inputs[0])
    return mat

for layer in ['Surface','Clouds','Atmosphere']:
    ob=copy_obj('Earth_Dynamic_'+layer,'Earth | '+layer,earth_center,90)
    mat=ob.data.materials[0].copy();ob.data.materials.clear();ob.data.materials.append(mat)
    n=mat.node_tree.nodes;l=mat.node_tree.links;geo=next(x for x in n if x.type=='NEW_GEOMETRY')
    old=n.get('Sun from local time'); direction=sun_direction(n,l,geo)
    for link in list(old.outputs[0].links):l.new(direction,link.to_socket)
    n.remove(old)

layout=[('mercury',2439.4,400,-65,16,0),('venus',6051.8,650,-34,32,0),('mars',3389.5,480,48,23,25.2),('jupiter',69911,5200,97,8,3.1),('saturn',58232,7600,-112,22,-18),('uranus',25362,6500,148,34,97.8),('neptune',24622,7000,-160,-10,28.3),('moon',1737.4,300,20,40,6.7)]
for name,km,d,yaw,el,roll in layout:
    a=math.radians(yaw);e=math.radians(el);center=eye+Vector((math.sin(a)*math.cos(e),math.cos(a)*math.cos(e),math.sin(e)))*d
    rotation=facing(center) @ Euler((math.radians(26.7 if name=='saturn' else 0),math.radians(roll),0),'XYZ').to_quaternion()
    radius=km/6371.0084*90
    mat=lit_material(name) if name!='saturn' else bpy.data.materials['Saturn | editable surface']
    copy_obj('Solar_Body_Unit',name.title()+' | theatre',center,radius,mat,rotation)
    if name=='saturn':copy_obj('Solar_Saturn_Rings','Saturn rings | theatre',center,radius,bpy.data.materials['Saturn | icy main rings'],rotation)
stars=copy_obj('Solar_Stars_Inward','NASA catalog | theatre',(0,0,0),110000,bpy.data.materials['NASA | catalog stars'])
stars.rotation_euler=(0,0,0);stars.rotation_mode='XYZ';stars.rotation_euler=(0,0,0)
disk=copy_obj('Solar_Body_Unit','Sun | theatre',(0,0,0),695700/6371.0084*90,bpy.data.materials['Sun | editable surface'])
display=bpy.data.objects.new('Sun | angular-size projection',None);scene.collection.objects.link(display)
for i in range(3):
    d=display.driver_add('location',i).driver
    d.expression=f'{eye[i]}+{SUN_RENDER_DISTANCE}*(s{i}-{eye[i]})/sqrt((s0-{eye[0]})**2+(s1-{eye[1]})**2+(s2-{eye[2]})**2)'
    for j in range(3):
        v=d.variables.new();v.name=f's{j}';v.type='TRANSFORMS';v.targets[0].id=sun;v.targets[0].transform_type=['LOC_X','LOC_Y','LOC_Z'][j];v.targets[0].transform_space='WORLD_SPACE'
    d=disk.driver_add('scale',i).driver
    d.expression=f'{695700/6371.0084*90*SUN_RENDER_DISTANCE}/sqrt((s0-{eye[0]})**2+(s1-{eye[1]})**2+(s2-{eye[2]})**2)'
    for j in range(3):
        v=d.variables.new();v.name=f's{j}';v.type='TRANSFORMS';v.targets[0].id=sun;v.targets[0].transform_type=['LOC_X','LOC_Y','LOC_Z'][j];v.targets[0].transform_space='WORLD_SPACE'
constraint=disk.constraints.new('COPY_LOCATION');constraint.target=display
# The self-lit sky and visible photosphere must not occlude the preview Sun light.
for ob in [stars,disk]:
    ob.visible_shadow=False
    mat=ob.data.materials[0];n=mat.node_tree.nodes;l=mat.node_tree.links;out=next(x for x in n if x.type=='OUTPUT_MATERIAL')
    old=out.inputs['Surface'].links[0].from_socket
    path=n.new('ShaderNodeLightPath');transparent=n.new('ShaderNodeBsdfTransparent');mix=n.new('ShaderNodeMixShader')
    l.new(path.outputs['Is Shadow Ray'],mix.inputs[0]);l.new(old,mix.inputs[1]);l.new(transparent.outputs[0],mix.inputs[2]);l.new(mix.outputs[0],out.inputs['Surface'])
    mat.surface_render_method='DITHERED'
bpy.data.materials['Saturn | icy main rings'].use_backface_culling=True
bpy.ops.object.light_add(type='SUN'); lamp=bpy.context.object;lamp.name='Sun | reference shadow light';lamp.data.energy=2.5;lamp.data.angle=math.radians(.53)
constraint=lamp.constraints.new('COPY_LOCATION');constraint.target=sun
target=bpy.data.objects.new('Lighting aim',None);scene.collection.objects.link(target)
constraint=lamp.constraints.new('TRACK_TO');constraint.target=target;constraint.track_axis='TRACK_NEGATIVE_Z';constraint.up_axis='UP_Y'
bpy.ops.object.camera_add(location=eye);cam=bpy.context.object;cam.name='Seat eye | reference'
cam.rotation_euler=Vector((0,math.cos(math.radians(10)),math.sin(math.radians(10)))).to_track_quat('-Z','Y').to_euler()
cam.data.lens=13;cam.data.sensor_width=36;cam.data.clip_start=.05;cam.data.clip_end=120000;scene.camera=cam
scene['note']='Blender authoring reference at app layout and size ratios. Custom Quest shaders, native import handedness, stereo and performance require device verification.'
for im in bpy.data.images:
    if im.source=='FILE' and not im.packed_file:
        try:im.pack()
        except RuntimeError:pass
for script in ['build_solar_assets.py','prepare_solar_textures.py','author_solar_preview.py']:
    text=bpy.data.texts.new(script);text.write((ROOT/'art/scripts'/script).read_text())
scene.frame_set(2);bpy.context.view_layer.update()
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'art/blender/solar_system.blend'))
result={'scene':scene.name,'local_hour':scene['local_hour'],'objects':len(scene.objects)}
