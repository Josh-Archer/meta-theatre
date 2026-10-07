"""Run in the live Blender MCP session. Sources stay in art/; export is staged.
Textures: Solar System Scope / INOVE, CC BY 4.0 (see art/EARTH.md).
"""
import bpy, math, json
import numpy as np
from pathlib import Path
from mathutils import Vector, Matrix
ROOT = (Path(__file__).resolve().parents[2] if '__file__' in globals() else Path(bpy.path.abspath('//')).resolve().parents[1])
if not (ROOT / "app/src/main/assets").is_dir():
    raise RuntimeError("Execute this script with __file__ set to its path in this project.")
TEX = ROOT/'art/textures/earth'
OUT = ROOT/'art/exports/earth'
W,H = 4096,2048
PHASES = {'day':(-10,22), 'twilight':(-72,20), 'night':(-158,-18)}

def linear(c):
    return np.where(c <= .04045, c/12.92, ((c+.055)/1.055)**2.4)
def srgb(c):
    c=np.maximum(c,0)
    return np.where(c <= .0031308,c*12.92,1.055*c**(1/2.4)-.055)
def read(name):
    im=bpy.data.images.load(str(TEX/'runtime'/name),check_existing=True)
    a=np.empty(W*H*4,np.float32); im.pixels.foreach_get(a)
    return a.reshape(H,W,4)[:,:,:3].copy()
def write(name,rgb,alpha=None):
    h,w=rgb.shape[:2]
    im=bpy.data.images.new(name,width=w,height=h,alpha=alpha is not None)
    im.colorspace_settings.name='sRGB'
    a=np.ones((h,w,4),np.float32); a[:,:,:3]=np.clip(rgb,0,1)
    if alpha is not None: a[:,:,3]=alpha
    im.pixels.foreach_set(a.ravel()); im.update()
    im.filepath_raw=str(TEX/'runtime'/(name+'.png')); im.file_format='PNG'; im.save()
    return im

def unit(lon,lat):
    a,b=np.radians([lon,lat]); return np.array([np.cos(b)*np.cos(a),np.cos(b)*np.sin(a),np.sin(b)],np.float32)

def create_sphere(name,radius,segments,rings,collection):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=segments,ring_count=rings,radius=radius)
    ob=bpy.context.object; ob.name=name
    for c in list(ob.users_collection): c.objects.unlink(ob)
    collection.objects.link(ob)
    # Rebuild spherical UVs from known geographic axes, with explicit seam handling.
    uv=ob.data.uv_layers.active
    for p in ob.data.polygons:
        coords=[]
        for li in p.loop_indices:
            co=ob.data.vertices[ob.data.loops[li].vertex_index].co.normalized()
            coords.append(((math.atan2(co.y,co.x)+math.pi)/(2*math.pi),math.asin(max(-1,min(1,co.z)))/math.pi+.5))
        us=[u for u,v in coords]
        if max(us)-min(us)>.5: coords=[(u+1 if u<.5 else u,v) for u,v in coords]
        for j,li in enumerate(p.loop_indices):
            u,v=coords[j]
            if v<.00001 or v>.99999:
                others=[c[0] for k,c in enumerate(coords) if k!=j]; u=sum(others)/len(others)
            uv.data[li].uv=(u,v)
        p.use_smooth=True
    # Face Europe / Africa toward local -Y. glTF front is +Z.
    rot=Matrix.Rotation(math.radians(15),4,'X') @ Matrix.Rotation(math.radians(-110),4,'Z')
    ob.data.transform(rot)
    return ob

def emission_material(name,image=None,vertex=False,alpha=False):
    mat=bpy.data.materials.new(name); mat.use_nodes=True
    n=mat.node_tree.nodes; n.clear(); l=mat.node_tree.links
    out=n.new('ShaderNodeOutputMaterial'); out.location=(420,0)
    em=n.new('ShaderNodeEmission'); em.location=(0,70); em.inputs['Strength'].default_value=1
    if image:
        t=n.new('ShaderNodeTexImage'); t.image=image; t.location=(-340,80)
    elif vertex:
        t=n.new('ShaderNodeVertexColor'); t.layer_name='Atmosphere'; t.location=(-340,80)
    if image or vertex: l.new(t.outputs['Color'],em.inputs['Color'])
    if alpha:
        tr=n.new('ShaderNodeBsdfTransparent'); tr.location=(0,-120)
        mix=n.new('ShaderNodeMixShader'); mix.location=(220,0)
        l.new(t.outputs['Alpha'],mix.inputs[0]); l.new(tr.outputs[0],mix.inputs[1]); l.new(t.outputs['Color'],mix.inputs[2]); l.new(mix.outputs[0],out.inputs[0])
        mat.surface_render_method='BLENDED'; mat.use_transparency_overlap=False
    else: l.new(t.outputs['Color'],out.inputs[0])
    mat.use_backface_culling=True
    return mat

# Keep the pre-existing scene intact; author a new self-contained scene in this file.
scene=bpy.data.scenes.new('EARTH | Quest asset studio')
bpy.context.window.scene=scene
scene.render.engine='BLENDER_EEVEE'
scene.render.resolution_x=1600; scene.render.resolution_y=1600; scene.render.resolution_percentage=100
scene.render.image_settings.file_format='PNG'; scene.render.film_transparent=False
scene.world=bpy.data.worlds.new('Earth | deep space'); scene.world.use_nodes=True
scene.world.node_tree.nodes['Background'].inputs[0].default_value=(0.0001,0.0002,0.0005,1)
scene.world.node_tree.nodes['Background'].inputs[1].default_value=.1
scene.view_settings.view_transform='Standard'; scene.view_settings.look='None'
scene.view_settings.exposure=0; scene.view_settings.gamma=1
bpy.ops.object.camera_add(location=(0,-3.4,0))
cam=bpy.context.object; cam.name='Earth_ReviewCamera'; cam.rotation_euler=(Vector((0,0,0))-cam.location).to_track_quat('-Z','Y').to_euler()
cam.data.type='ORTHO'; cam.data.ortho_scale=2.35; scene.camera=cam

# Lighting calculations in linear space, UV samples are south-to-north like bpy images.
day=linear(read('4k_earth_daymap.png'))
night=linear(read('4k_earth_nightmap.png'))
cloud=read('4k_earth_clouds.png')[:,:,0]
spec=read('4k_earth_specular_map.png')[:,:,0]
normal=read('4k_earth_normal_map.png')*2-1
lon=(np.arange(W,dtype=np.float32)+.5)/W*2*np.pi-np.pi
lat=(np.arange(H,dtype=np.float32)+.5)/H*np.pi-np.pi/2
cl=np.cos(lat)[:,None]; sl=np.sin(lat)[:,None]
co=np.cos(lon)[None,:]; si=np.sin(lon)[None,:]
N=np.stack(np.broadcast_arrays(cl*co,cl*si,sl),axis=-1)
T=np.stack(np.broadcast_arrays(-si,co,np.zeros((H,1),np.float32)),axis=-1)
B=np.stack(np.broadcast_arrays(-sl*co,-sl*si,cl),axis=-1)
# Restrained terrain relief, smooth ocean normals.
normal[:,:,:2]*=.45*(1-spec[:,:,None]); normal[:,:,2]=1
NN=N*normal[:,:,2:]+T*normal[:,:,0:1]+B*normal[:,:,1:2]
NN/=np.linalg.norm(NN,axis=-1,keepdims=True)
V=unit(20,15)
# The source night image contains a dim blue background: remove that from emission.
lights=np.maximum(night-np.array([.008,.01,.018],np.float32),0)
cloud_alpha=np.clip(cloud**1.18*.93,0,.96)
phase_collections={}
for phase,(sunlon,sunlat) in PHASES.items():
    sun=unit(sunlon,sunlat)
    ndl=np.einsum('ijk,k->ij',N,sun); detail=np.einsum('ijk,k->ij',NN,sun)
    daylight=np.clip(ndl/.16,0,1); daylight=daylight*daylight*(3-2*daylight)
    nightmask=1-np.clip((ndl+.12)/.20,0,1); nightmask=nightmask*nightmask*(3-2*nightmask)
    diffuse=np.maximum(detail,0)**.85
    # Subtle blue bounce lets the dark hemisphere remain just visible.
    ambient=np.array([.0007,.0012,.0035],np.float32)
    shadow=1-np.roll(cloud_alpha,4,axis=1)*.20
    rgb=day*(diffuse[:,:,None]*1.16*shadow[:,:,None]+ambient)
    half=(sun+V); half/=np.linalg.norm(half)
    highlight=np.maximum(np.einsum('ijk,k->ij',N,half),0)**170
    rgb+=highlight[:,:,None]*spec[:,:,None]*daylight[:,:,None]*np.array([.16,.20,.24])
    rgb+=lights*nightmask[:,:,None]*2.8
    # Atmospheric haze is weak in the interior and slightly stronger near the limb.
    rim=(1-np.clip(np.einsum('ijk,k->ij',N,V),0,1))**4
    rgb+=rim[:,:,None]*np.maximum(ndl,0)[:,:,None]*np.array([.003,.012,.034])
    surf_image=write('earth_'+phase+'_surface_4k',srgb(rgb))
    # A separate, elevated shell gives real cloud parallax; downsample to 2K later.
    cloud_light=np.maximum(ndl,0)**.8
    cloud_rgb=np.zeros((H,W,3),np.float32)+np.array([.0008,.0018,.0045])
    cloud_rgb+=cloud_light[:,:,None]*np.array([.9,.96,1.0])
    cloud_image=write('earth_'+phase+'_clouds_2k',srgb(cloud_rgb)[::2,::2],cloud_alpha[::2,::2])
    c=bpy.data.collections.new('EARTH_'+phase.upper()); scene.collection.children.link(c); phase_collections[phase]=c
    ob=create_sphere('Earth_'+phase+'_Surface',1,128,64,c)
    ob.data.materials.append(emission_material('Earth_'+phase+'_BakedSurface',surf_image))
    ob['phase']=phase; ob['radius_metres']=1.0; ob['lighting']='Baked for static theatre orbit; no live terminator'
    clouds=create_sphere('Earth_'+phase+'_CloudDeck',1.0035,96,48,c)
    clouds.data.materials.append(emission_material('Earth_'+phase+'_Clouds',cloud_image,alpha=True))
    atmos=create_sphere('Earth_'+phase+'_Atmosphere',1.012,128,64,c)
    attr=atmos.data.color_attributes.new(name='Atmosphere',type='FLOAT_COLOR',domain='POINT')
    rot=Matrix.Rotation(math.radians(15),4,'X') @ Matrix.Rotation(math.radians(-110),4,'Z')
    local_sun=rot.to_3x3() @ Vector(sun)
    for v,col in zip(atmos.data.vertices,attr.data):
        n=v.co.normalized(); facing=max(0,-n.y)
        edge=(1-facing)**5
        sunfac=max(0,n.dot(local_sun))
        opacity=edge*(.14+.72*sunfac)
        col.color=(.025,.22,.65,opacity)
    atmos.data.materials.append(emission_material('Earth_'+phase+'_Atmosphere',vertex=True,alpha=True))
    c.hide_render=phase!='twilight'; c.hide_viewport=phase!='twilight'

# High-resolution editable PBR source, with day/night emission controlled by sun direction.
source=bpy.data.collections.new('SOURCE | 8K PBR - enable to edit'); scene.collection.children.link(source)
surface=create_sphere('Earth_Source_8K',1,256,128,source)
mat=bpy.data.materials.new('Earth_Source | 8K day + masked city emission'); mat.use_nodes=True
n=mat.node_tree.nodes; l=mat.node_tree.links; bs=n.get('Principled BSDF')
bs.location=(500,120); n.get('Material Output').location=(850,120)
def texnode(file,name,x,y,noncolor=False):
    t=n.new('ShaderNodeTexImage'); t.name=name; t.label=name; t.location=(x,y)
    t.image=bpy.data.images.load(str(TEX/'source'/file),check_existing=True)
    if noncolor: t.image.colorspace_settings.name='Non-Color'
    return t
base=texnode('8k_earth_daymap.jpg','8K continents / ocean',-650,400); l.new(base.outputs['Color'],bs.inputs['Base Color'])
nor=texnode('8k_earth_normal_map.tif','8K terrain detail',-650,-130,True)
nm=n.new('ShaderNodeNormalMap'); nm.location=(-80,-120); nm.inputs['Strength'].default_value=.25
l.new(nor.outputs['Color'],nm.inputs['Color']); l.new(nm.outputs[0],bs.inputs['Normal'])
sp=texnode('8k_earth_specular_map.tif','8K ocean mask',-650,-460,True)
ramp=n.new('ShaderNodeMapRange'); ramp.location=(-80,-400); ramp.inputs['To Min'].default_value=.85; ramp.inputs['To Max'].default_value=.22
l.new(sp.outputs[0],ramp.inputs[0]); l.new(ramp.outputs[0],bs.inputs['Roughness'])
nt=texnode('8k_earth_nightmap.jpg','8K city lights',-650,60)
l.new(nt.outputs[0],bs.inputs['Emission Color'])
geom=n.new('ShaderNodeNewGeometry'); geom.location=(-600,800)
dot=n.new('ShaderNodeVectorMath'); dot.operation='DOT_PRODUCT'; dot.location=(-350,780)
rot=Matrix.Rotation(math.radians(15),4,'X') @ Matrix.Rotation(math.radians(-110),4,'Z')
dot.inputs[1].default_value=rot.to_3x3() @ Vector(unit(*PHASES['twilight']))
dot.label='Editable sun direction (world space)'; l.new(geom.outputs['Normal'],dot.inputs[0])
mask=n.new('ShaderNodeMapRange'); mask.location=(0,650); mask.clamp=True
mask.inputs['From Min'].default_value=-.12; mask.inputs['From Max'].default_value=.08; mask.inputs['To Min'].default_value=2.8; mask.inputs['To Max'].default_value=0
l.new(dot.outputs['Value'],mask.inputs[0]); l.new(mask.outputs[0],bs.inputs['Emission Strength'])
surface.data.materials.append(mat)
source.hide_render=True; source.hide_viewport=True

# Export only the three runtime layers, never the review camera or source mesh.
for phase,c in phase_collections.items():
    c.hide_viewport=False
    bpy.ops.object.select_all(action='DESELECT')
    for ob in c.objects: ob.select_set(True)
    bpy.context.view_layer.objects.active=next(iter(c.objects))
    bpy.ops.export_scene.gltf(filepath=str(OUT/('earth_'+phase+'.glb')),export_format='GLB',use_selection=True,use_active_scene=True,export_animations=False,export_cameras=False,export_lights=False,export_image_format='AUTO',export_extras=True,export_copyright='Earth maps: Solar System Scope / INOVE, CC BY 4.0; adaptation: Meta Theatre')
    c.hide_viewport=phase!='twilight'
# Camera view and useful material preview on opening the .blend.
for area in bpy.context.screen.areas:
    if area.type=='VIEW_3D':
        area.spaces.active.region_3d.view_perspective='CAMERA'
        area.spaces.active.shading.type='MATERIAL'
        area.spaces.active.shading.use_scene_world=True
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'art/blender/earth_v001.blend'))
result={'blend':str(ROOT/'art/blender/earth_v001.blend'),'exports':[str(p) for p in OUT.glob('*.glb')],'scene':scene.name}
