"""Author the lounge chair in the live Blender MCP session.

Run with exec(compile(open(__file__).read(), __file__, 'exec')).
Creates its own scene; preserves other scenes. Exports to art/exports for review.
"""
import bpy
import math
import json
import numpy as np
from pathlib import Path
from mathutils import Vector, Matrix

ROOT = (Path(__file__).resolve().parents[2] if '__file__' in globals() else Path(bpy.path.abspath('//')).resolve().parents[1])
if not (ROOT / "app/src/main/assets").is_dir():
    raise RuntimeError("Execute this script with __file__ set to its path in this project.")
TEX = ROOT / 'art/textures/chair'
OUT = ROOT / 'art/exports/chair'
TEX.mkdir(parents=True, exist_ok=True)
OUT.mkdir(parents=True, exist_ok=True)
scene = bpy.data.scenes.new('Lounge chair - atelier')
bpy.context.window.scene = scene
scene.unit_settings.system = 'METRIC'
asset = bpy.data.collections.new('CHAIR - editable components')
scene.collection.children.link(asset)
studio = bpy.data.collections.new('STUDIO - excluded from export')
scene.collection.children.link(studio)

def image(name, rgb, noncolor=False):
    h, w = rgb.shape[:2]
    im = bpy.data.images.new(name, width=w, height=h, alpha=False)
    if noncolor:
        im.colorspace_settings.name = 'Non-Color'
    rgba = np.ones((h,w,4), dtype=np.float32)
    rgba[:,:,:3] = np.clip(rgb, 0, 1)
    im.pixels.foreach_set(rgba.ravel())
    im.filepath_raw = str(TEX / (name+'.png'))
    im.file_format = 'PNG'
    im.save()
    return im

# Seamless, deterministic PBR maps. Grain is geometric microrelief, not a photo
# with directional highlights painted into it. Texture UV units represent metres.
n = 1024
y,x = np.mgrid[:n,:n].astype(np.float32)/n
rng = np.random.default_rng(5102026)
def noise(freqs):
    v=np.zeros((n,n),np.float32)
    for f,amp in freqs:
        for _ in range(4):
            a=int(rng.integers(1,f+1)); b=int(rng.integers(1,f+1))
            v += amp/4*np.sin(2*np.pi*(a*x+b*y)+rng.uniform(0,2*np.pi))
    return v
def normal(height, strength):
    dx=(np.roll(height,-1,1)-np.roll(height,1,1))*strength
    dy=(np.roll(height,-1,0)-np.roll(height,1,0))*strength
    v=np.stack((-dx,-dy,np.ones_like(dx)),axis=2)
    v/=np.linalg.norm(v,axis=2,keepdims=True)
    return v*.5+.5
cells=150
u=x*cells; v=y*cells
ix=np.floor(u); iy=np.floor(v)
first=np.full((n,n),100.,np.float32); second=first.copy()
seeds=rng.uniform(.15,.85,(cells,cells,2)).astype(np.float32)
for dx in (-1,0,1):
    for dy in (-1,0,1):
        s=seeds[(iy.astype(int)+dy)%cells,(ix.astype(int)+dx)%cells]
        d=(ix+dx+s[:,:,0]-u)**2+(iy+dy+s[:,:,1]-v)**2
        second=np.minimum(second,np.maximum(first,d)); first=np.minimum(first,d)
grain=np.clip((np.sqrt(second)-np.sqrt(first))*7,0,1)
cloud=noise([(4,1),(15,.4),(45,.15)])
pore=noise([(450,.10),(210,.15)])
height=grain*.65+pore*.15
leather_base=image('saddle_leather_basecolor',np.stack([.31+cloud*.023+grain*.010,.137+cloud*.015+grain*.006,.066+cloud*.008+grain*.003],axis=2))
leather_normal=image('saddle_leather_normal',normal(height,.12),True)
leather_rough=image('saddle_leather_roughness',np.repeat((.44+cloud*.035+(1-grain)*.025)[:,:,None],3,axis=2),True)
grainwood=np.zeros_like(x)
for f in (7,13,29,53,97):
    grainwood+=np.sin(2*np.pi*(x*f+.7*np.sin(2*np.pi*y)+.18*np.sin(2*np.pi*(y*3+x*2))))/5
fine=np.sin(2*np.pi*(x*173+.5*np.sin(2*np.pi*y)))
woodcloud=noise([(3,1),(8,.2)])
wood=np.stack([.22+grainwood*.030+fine*.013+woodcloud*.02,.099+grainwood*.019+fine*.008+woodcloud*.011,.042+grainwood*.01+fine*.004+woodcloud*.005],axis=2)
wood_base=image('oiled_walnut_basecolor',wood)
wood_normal=image('oiled_walnut_normal',normal(grainwood*.1+fine*.05,.3),True)

def material(name,color,rough=.45,metal=0,base=None,norm=None,roughmap=None):
    m=bpy.data.materials.new(name); m.use_nodes=True
    p=m.node_tree.nodes.get('Principled BSDF')
    p.inputs['Base Color'].default_value=(*color,1)
    p.inputs['Roughness'].default_value=rough; p.inputs['Metallic'].default_value=metal
    m.diffuse_color=(*color,1)
    for im,slot in [(base,'Base Color'),(roughmap,'Roughness')]:
        if im:
            t=m.node_tree.nodes.new('ShaderNodeTexImage'); t.image=im
            m.node_tree.links.new(t.outputs['Color'],p.inputs[slot])
    if norm:
        t=m.node_tree.nodes.new('ShaderNodeTexImage'); t.image=norm
        q=m.node_tree.nodes.new('ShaderNodeNormalMap')
        m.node_tree.links.new(t.outputs['Color'],q.inputs['Color'])
        m.node_tree.links.new(q.outputs['Normal'],p.inputs['Normal'])
    return m
leather=material('01 Saddle leather - full grain',(.30,.13,.06),base=leather_base,norm=leather_normal,roughmap=leather_rough)
woodmat=material('02 Oiled walnut',(.19,.085,.035),.38,base=wood_base,norm=wood_normal)
metal=material('03 Graphite satin aluminum',(.045,.055,.065),.28,.82)
thread=material('04 Warm linen topstitch',(.42,.29,.15),.68)
hardware=material('05 Brushed champagne fittings',(.42,.31,.18),.26,.8)
rubber=material('06 Rubber feet and seam shadow',(.009,.011,.012),.83)

def put(o,mat=None):
    for c in list(o.users_collection): c.objects.unlink(o)
    asset.objects.link(o)
    if mat: o.data.materials.append(mat)
    return o
def mesh(name,verts,faces,mat,uvs=None):
    d=bpy.data.meshes.new(name); d.from_pydata(verts,[],faces); d.update()
    o=bpy.data.objects.new(name,d); asset.objects.link(o); d.materials.append(mat)
    for p in d.polygons: p.use_smooth=True
    if uvs:
        uv=d.uv_layers.new(name='Physical UV')
        for p in d.polygons:
            for li in p.loop_indices: uv.data[li].uv=uvs[d.loops[li].vertex_index]
    return o
def box(name,loc,dim,mat,bevel=.008):
    bpy.ops.mesh.primitive_cube_add(size=1,location=loc)
    o=put(bpy.context.object,mat);o.name=name; o.dimensions=dim
    bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    b=o.modifiers.new('Machined edge radius','BEVEL');b.width=bevel;b.segments=3
    o.modifiers.new('Weighted surface normals','WEIGHTED_NORMAL')
    for p in o.data.polygons:p.use_smooth=True
    return o
def tube(name,points,radius,mat,closed=False,sides=6):
    verts=[];faces=[]
    points=[Vector(p) for p in points]
    for i,p in enumerate(points):
        tangent=(points[(i+1)%len(points)]-points[(i-1)%len(points)]) if closed else (points[min(i+1,len(points)-1)]-points[max(0,i-1)])
        tangent.normalize();a=tangent.cross(Vector((0,0,1)))
        if a.length<.01:a=tangent.cross(Vector((0,1,0)))
        a.normalize();b=tangent.cross(a).normalized()
        for j in range(sides):verts.append(p+radius*(math.cos(2*math.pi*j/sides)*a+math.sin(2*math.pi*j/sides)*b))
    for i in range(len(points) if closed else len(points)-1):
        ni=(i+1)%len(points)
        for j in range(sides):faces.append((i*sides+j,i*sides+(j+1)%sides,ni*sides+(j+1)%sides,ni*sides+j))
    if not closed:
        faces.extend([tuple(reversed(range(sides))),tuple((len(points)-1)*sides+j for j in range(sides))])
    return mesh(name,verts,faces,mat)
def spow(x,p):return math.copysign(abs(x)**p,x)
def cushion(name,a,b,c,loc,angle=0,detail=True):
    rot=Matrix.Rotation(angle,4,'X'); center=Vector(loc)
    def surface(t,lat):
        k=abs(math.cos(lat))**.46
        xx=a*spow(math.cos(t),.28)*k; yy=b*spow(math.sin(t),.28)*k
        zz=c*spow(math.sin(lat),.64)
        if detail:
            edge=max(abs(xx)/a,abs(yy)/b)
            wrinkle=(.0025*math.sin(39*t+7*lat)+.0013*math.sin(67*t-4*lat))*math.exp(-((edge-.90)/.12)**2)*abs(math.sin(lat))
            zz+=wrinkle
            if lat>0:
                # Slight sitting depression and restrained creases near the sewn border.
                zz-=.009*math.exp(-((xx/(a*.6))**2+(yy/(b*.68))**2))
        return rot@Vector((xx,yy,zz))+center
    nt=112 if detail else 64; nl=32 if detail else 20
    verts=[];uv=[];faces=[]
    for j in range(nl+1):
        lat=-math.pi/2+math.pi*j/nl
        for i in range(nt):
            t=2*math.pi*i/nt; p=surface(t,lat);verts.append(p)
            # Project in cushion local axes; grain is the same physical scale on both faces.
            q=rot.inverted()@(p-center);uv.append((q.x+.5,q.y+.5))
    for j in range(nl):
        for i in range(nt):
            k=j*nt+i;ni=j*nt+(i+1)%nt
            faces.append((k,ni,ni+nt,k+nt))
    o=mesh(name,verts,faces,leather,uv)
    # Continuous leather welt and two rows of actual small stitches.
    pts=[surface(2*math.pi*i/224,.18) for i in range(224)]
    tube(name+' / rolled leather welt',pts,.0024,leather,True)
    for row,lat in enumerate((.40,.49)):
        pts=[surface(2*math.pi*i/1200,lat) for i in range(1200)]
        lengths=[0.]
        for i in range(1,len(pts)):lengths.append(lengths[-1]+(pts[i]-pts[i-1]).length)
        total=lengths[-1]; count=int(total/.009)
        def sample(distance):
            i=max(1,min(len(pts)-1,int(np.searchsorted(lengths,distance))))
            weight=(distance-lengths[i-1])/max(1e-9,lengths[i]-lengths[i-1])
            return pts[i-1].lerp(pts[i],weight)
        # Combine discrete thread segments into one component.
        vs=[];fs=[]
        for k in range(count):
            p=sample(k*total/count)+rot@Vector((0,0,.00065));q=sample(k*total/count+.004)+rot@Vector((0,0,.00065))
            tangent=(q-p).normalized();a1=tangent.cross(rot@Vector((0,0,1))).normalized();a2=tangent.cross(a1).normalized();off=len(vs)
            for end in (p,q):
                for h in range(4):vs.append(end+.00065*(math.cos(h*math.pi/2)*a1+math.sin(h*math.pi/2)*a2))
            for h in range(4):fs.append((off+h,off+(h+1)%4,off+4+(h+1)%4,off+4+h))
        mesh(name+' / saddle stitch '+str(row+1),vs,fs,thread)
    return o

# Origin matches Lounge.kt: Blender Z=-0.39 maps to glTF Y=-0.39.
# Blender -Y is forward; glTF +Z is forward.
cushion('Seat / shaped loose cushion',.353,.370,.077,(0,-.067,-.078),math.radians(5))
cushion('Back / reclined cushion',.349,.282,.076,(0,.276,.183),math.radians(72))
# Shaped walnut undertray and back shell follow the cushions, with a narrow reveal.
tray=box('Walnut seat shell',(0,-.045,-.176),(.735,.755,.046),woodmat,.021);tray.rotation_euler.x=math.radians(5)
shell=box('Walnut back shell',(0,.362,.155),(.737,.580,.028),woodmat,.013);shell.rotation_euler.x=math.radians(72)
# Thin inset panel and structural cross rails.
box('Seat suspension',(0,-.035,-.204),(.682,.669,.028),rubber,.012)
for y0 in (-.338,.27):box('Cross rail '+str(y0),(0,y0,-.218),(.814,.023,.032),metal,.006)
for side in (-1,1):
    xx=side*.412
    box('Side suspension rail '+str(side),(xx,-.009,-.218),(.028,.856,.032),metal,.006)
    # Sleigh rail built from a rounded rectangular centerline, solid machined metal.
    ymin,ymax=-.437,.419;zmin,zmax=-.371,.164;rad=.024
    # Rounded rectangular section, four corners along an explicitly ordered loop.
    pts=[]
    for cy,cz,angles in [(ymin+rad,zmin+rad,(math.pi,1.5*math.pi)),(ymax-rad,zmin+rad,(1.5*math.pi,2*math.pi)),(ymax-rad,zmax-rad,(0,.5*math.pi)),(ymin+rad,zmax-rad,(.5*math.pi,math.pi))]:
        for j in range(9):
            t=angles[0]+j*(angles[1]-angles[0])/8
            pts.append((xx,cy+rad*math.cos(t),cz+rad*math.sin(t)))
    tube(('Left' if side<0 else 'Right')+' / continuous sled frame',pts,.014,metal,True,8)
    arm=box('Walnut arm cap '+str(side),(xx,-.016,.172),(.083,.651,.032),woodmat,.015)
    cushion('Arm leather pad '+str(side),.037,.264,.017,(xx,-.016,.195),detail=False)
    for yy in (-.355,.338):
        box('Rubber isolation foot',(xx,yy,-.382),(.032,.085,.016),rubber,.007)
    for yy,zz in [(-.336,-.216),(.270,-.216),(.28,.16)]:
        bpy.ops.mesh.primitive_cylinder_add(vertices=24,radius=.010,depth=.005,location=(xx+side*.014,yy,zz),rotation=(0,math.pi/2,0))
        o=put(bpy.context.object,hardware);o.name='Recessed frame fastener'
        b=o.modifiers.new('Fastener edge','BEVEL');b.width=.001;b.segments=2
        box('Fastener slot',(xx+side*.017,yy,zz),(.001,.011,.0018),rubber,.0005)
    # Rear shell support stanchion.
    tube('Back support '+str(side),[(side*.29,.27,-.20),(side*.29,.36,.12),(side*.29,.434,.32)],.011,metal,False,8)
    bpy.ops.mesh.primitive_cylinder_add(vertices=24,radius=.016,depth=.006,location=(side*.29,.435,.32),rotation=(math.radians(72),0,0))
    o=put(bpy.context.object,hardware);o.name='Back shell mounting washer'
    b=o.modifiers.new('Washer bevel','BEVEL');b.width=.001;b.segments=2

# Wood UVs from the authored geometry axes; consistent elongated grain on arms.
for o in asset.objects:
    if o.type=='MESH' and o.data.materials and o.data.materials[0]==woodmat:
        uv=o.data.uv_layers.active or o.data.uv_layers.new()
        for p in o.data.polygons:
            for li in p.loop_indices:
                co=o.data.vertices[o.data.loops[li].vertex_index].co
                uv.data[li].uv=(co.x*1.7+.5,(co.y+co.z)*.85+.5)

# Joining meshes preserves layers by name. One consistent layer prevents wood
# components from sampling an empty UV channel after joining with the cushions.
for o in asset.objects:
    if o.type=='MESH':
        uv=o.data.uv_layers.active or o.data.uv_layers.new()
        uv.name='Physical UV'

# Render studio uses only the same six glTF-compatible materials as the export.
scene.world=bpy.data.worlds.new('Warm neutral studio');scene.world.use_nodes=True
scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.14,.16,.19,1)
scene.world.node_tree.nodes['Background'].inputs[1].default_value=.35
def studio_link(o):
    for c in list(o.users_collection):c.objects.unlink(o)
    studio.objects.link(o)
bpy.ops.mesh.primitive_plane_add(size=200,location=(0,0,-.391));floor=bpy.context.object;floor.name='Studio floor';studio_link(floor)
floor.data.materials.append(material('Studio only - warm grey',(.11,.105,.095),.85))
for name,loc,power,size,color in [('Key softbox',(-1.8,-2.2,2.8),280,2.2,(1,.86,.72)),('Cool fill',(2,-.6,1.5),180,1.6,(.79,.88,1)),('Rear strip',(0,2,2),350,1.2,(1,.88,.72))]:
    d=bpy.data.lights.new(name,'AREA');d.energy=power;d.shape='DISK';d.size=size;d.color=color
    o=bpy.data.objects.new(name,d);studio.objects.link(o);o.location=loc;o.rotation_euler=(Vector((0,0,0))-o.location).to_track_quat('-Z','Y').to_euler()
d=bpy.data.cameras.new('Chair product camera');o=bpy.data.objects.new('Chair product camera',d);studio.objects.link(o)
o.location=(1.45,-1.85,1.03);o.rotation_euler=(Vector((0,0,.025))-o.location).to_track_quat('-Z','Y').to_euler();d.type='ORTHO';d.ortho_scale=1.53;scene.camera=o
scene.render.engine='CYCLES';scene.cycles.samples=64;scene.cycles.use_denoising=True
scene.render.resolution_x=1400;scene.render.resolution_y=1400;scene.render.resolution_percentage=100
scene.view_settings.view_transform='AgX'

bpy.context.view_layer.update()
# Export a joined evaluated copy: six material primitives rather than per-stitch nodes.
deps=bpy.context.evaluated_depsgraph_get();copies=[]
for o in list(asset.objects):
    if o.type!='MESH':continue
    data=bpy.data.meshes.new_from_object(o.evaluated_get(deps),depsgraph=deps)
    p=bpy.data.objects.new(o.name+' export',data);scene.collection.objects.link(p);p.matrix_world=o.matrix_world.copy();copies.append(p)
bpy.ops.object.select_all(action='DESELECT')
for o in copies:o.select_set(True)
bpy.context.view_layer.objects.active=copies[0]
bpy.ops.object.join();joined=bpy.context.object;joined.name='Lounge Chair'
# Remove coincident poles in cushion geometry; validate normals on watertight solids.
bpy.ops.object.mode_set(mode='EDIT');bpy.ops.mesh.select_all(action='SELECT');bpy.ops.mesh.remove_doubles(threshold=.00001);bpy.ops.mesh.normals_make_consistent(inside=False);bpy.ops.object.mode_set(mode='OBJECT')
bpy.ops.object.mode_set(mode='EDIT');bpy.ops.mesh.select_all(action='SELECT');bpy.ops.mesh.quads_convert_to_tris(quad_method='BEAUTY',ngon_method='BEAUTY');bpy.ops.object.mode_set(mode='OBJECT')
joined.data.calc_loop_triangles()
report={'triangles':len(joined.data.loop_triangles),'vertices':len(joined.data.vertices),'materials':len(joined.data.materials),'dimensions_blender_metres':list(joined.dimensions),'authored_components':len(asset.objects)}
bpy.ops.export_scene.gltf(filepath=str(OUT/'lounge_chair.glb'),export_format='GLB',use_selection=True,use_active_scene=True,export_apply=True,export_yup=True,export_texcoords=True,export_normals=True,export_tangents=True,export_materials='EXPORT')
bpy.ops.export_scene.fbx(filepath=str(OUT/'lounge_chair.fbx'),use_selection=True,object_types={'MESH'},apply_unit_scale=True,axis_forward='-Y',axis_up='Z',use_mesh_modifiers=True,bake_anim=False)
bpy.data.objects.remove(joined,do_unlink=True)
for im in bpy.data.images:
    if im.filepath and str(TEX) in bpy.path.abspath(im.filepath):im.pack()
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'art/blender/lounge_chair_v003.blend'))
(ROOT/'art/validation/chair/authoring-report.json').write_text(json.dumps(report,indent=2))
result=report
