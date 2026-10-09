"""Run through Blender MCP in ROADSTER v2 | Authored source. Front is -Y; units are meters.

Reference proportions: Tesla's original Roadster service manual; appearance:
SpaceX Falcon Heavy/Starman imagery. Geometry and textures are authored here.
This creates a new collection, leaving the existing orbital_visitors source intact.
"""
import bpy, bmesh, math, json
import numpy as np
from pathlib import Path
from mathutils import Vector
ROOT = Path(__file__).resolve().parents[2]
scene = bpy.context.scene
assert scene.name == 'ROADSTER v2 | Authored source'
assert not scene.objects, 'Use a fresh authoring scene; never replace an existing scene.'
COL = bpy.data.collections.new('ROADSTER v2 | Editable components')
scene.collection.children.link(COL)
TEX = ROOT / 'art/textures/roadster'
TEX.mkdir(parents=True, exist_ok=True)

def material(name, color, metal=0, rough=.4):
    m=bpy.data.materials.new(name); m.diffuse_color=(*color,1);m.use_nodes=True
    p=m.node_tree.nodes.get('Principled BSDF')
    p.inputs['Base Color'].default_value=(*color,1)
    p.inputs['Metallic'].default_value=metal;p.inputs['Roughness'].default_value=rough
    return m
red=material('Roadster v2 | Cherry red paint',(.37,.006,.012),.38,.24)
red.node_tree.nodes.get('Principled BSDF').inputs['Coat Weight'].default_value=.8
red.node_tree.nodes.get('Principled BSDF').inputs['Coat Roughness'].default_value=.16
rubber=material('Roadster v2 | Tire rubber',(.015,.018,.022),0,.78)
leather=material('Roadster v2 | Black leather',(.023,.025,.028),0,.47)
carbon=material('Roadster v2 | Carbon trim',(.028,.032,.038),.4,.3)
alloy=material('Roadster v2 | Forged aluminium',(.48,.53,.59),.85,.23)
steel=material('Roadster v2 | Brake steel',(.18,.21,.24),.75,.4)
visor=material('Roadster v2 | Visor and optics',(.008,.018,.027),.48,.12)
white=material('Roadster v2 | Suit and light lenses',(.78,.81,.82),.05,.48)
window=material('Roadster v2 | Windshield',(.20,.30,.35),.1,.16)
p=window.node_tree.nodes.get('Principled BSDF');p.inputs['Alpha'].default_value=.12
window.diffuse_color=(.20,.30,.35,.12);window.surface_render_method='DITHERED';window.use_transparency_overlap=False
rearLamp=material('Roadster v2 | Ruby tail lenses',(.34,.004,.006),.12,.22)
p=rearLamp.node_tree.nodes.get('Principled BSDF');p.inputs['Emission Color'].default_value=(.2,.001,.001,1);p.inputs['Emission Strength'].default_value=.25
# Authored repeating weave, usable in glTF without procedural shader evaluation.
n=512; yy,xx=np.mgrid[:n,:n];twill=((xx//8+yy//8)%4<2)
value=np.where(twill,.08,.025)+.018*np.cos((xx+yy)*math.pi/4)
pixels=np.zeros((n,n,4),dtype=np.float32);pixels[:,:,:3]=value[:,:,None];pixels[:,:,3]=1
im=bpy.data.images.new('Roadster carbon twill',width=n,height=n);im.pixels.foreach_set(pixels.ravel())
im.filepath_raw=str(TEX/'carbon_twill.png');im.file_format='PNG';im.save();im.pack()
node=carbon.node_tree.nodes.new('ShaderNodeTexImage');node.image=im
carbon.node_tree.links.new(node.outputs['Color'],carbon.node_tree.nodes.get('Principled BSDF').inputs['Base Color'])

def mesh(name,verts,faces,mat,smooth=True):
    me=bpy.data.meshes.new(name);me.from_pydata(verts,[],faces);me.update()
    bm=bmesh.new();bm.from_mesh(me);bmesh.ops.recalc_face_normals(bm,faces=list(bm.faces));bm.to_mesh(me);bm.free()
    o=bpy.data.objects.new(name,me);COL.objects.link(o);me.materials.append(mat)
    for p in me.polygons:p.use_smooth=smooth
    if mat==carbon:
        uv=me.uv_layers.new(name='Authored weave UV')
        for lp in me.loops:
            v=me.vertices[lp.vertex_index].co;uv.data[lp.index].uv=(v.y*3,v.z*3+v.x*3)
    return o

def adopt(o,name,mat):
    o.name=name
    for c in list(o.users_collection):c.objects.unlink(o)
    COL.objects.link(o);o.data.materials.append(mat)
    for p in o.data.polygons:p.use_smooth=True
    return o

def ellipsoid(name,loc,scale,mat,seg=32,rings=16):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=seg,ring_count=rings,location=loc)
    o=adopt(bpy.context.object,name,mat);o.scale=scale
    return o

def box(name,loc,scale,mat,bevel=.02):
    bpy.ops.mesh.primitive_cube_add(size=1,location=loc);o=adopt(bpy.context.object,name,mat);o.scale=scale
    bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    if bevel:
        m=o.modifiers.new('Machined edge radius','BEVEL');m.width=bevel;m.segments=3
        bpy.ops.object.modifier_apply(modifier=m.name)
    # Flat large faces, smoothly rounded edge normals.
    for p in o.data.polygons:p.use_smooth=False
    m=o.modifiers.new('Weighted panel normals','WEIGHTED_NORMAL');m.keep_sharp=True
    return o

def tube(name,points,radius,mat,sides=8):
    # Parallel rings with tangent frames; editable mesh, no runtime curve dependency.
    verts=[];faces=[]
    for i,p in enumerate(points):
        p=Vector(p);t=Vector(points[min(i+1,len(points)-1)])-Vector(points[max(0,i-1)])
        t.normalize();n=t.cross(Vector((0,0,1)))
        if n.length<.01:n=t.cross(Vector((0,1,0)))
        n.normalize();b=t.cross(n).normalized()
        for j in range(sides):verts.append(tuple(p+radius*(math.cos(j*2*math.pi/sides)*n+math.sin(j*2*math.pi/sides)*b)))
    for i in range(len(points)-1):
        for j in range(sides):a=i*sides+j;faces.append((a,i*sides+(j+1)%sides,(i+1)*sides+(j+1)%sides,a+sides))
    faces+=[tuple(reversed(range(sides))),tuple((len(points)-1)*sides+j for j in range(sides))]
    return mesh(name,verts,faces,mat)

def interp(y,points):
    # Cubic Hermite interpolation of measured section profiles.
    for i in range(len(points)-1):
        a,v=points[i];b,w=points[i+1]
        if y<=b:
            t=max(0,min(1,(y-a)/(b-a)))
            slope0=(w-points[max(0,i-1)][1])/(b-points[max(0,i-1)][0])
            slope1=(points[min(len(points)-1,i+2)][1]-v)/(points[min(len(points)-1,i+2)][0]-a)
            return (2*t**3-3*t*t+1)*v+(t**3-2*t*t+t)*(b-a)*slope0+(-2*t**3+3*t*t)*w+(t**3-t*t)*(b-a)*slope1
    return points[-1][1]
widthProfile=[(-1.973,.63),(-1.75,.77),(-1.10,.858),(-.45,.82),(0,.795),(.60,.83),(1.249,.864),(1.8,.80),(1.973,.72)]
shoulderProfile=[(-1.973,.46),(-1.75,.56),(-1.10,.72),(-.45,.73),(-.15,.66),(.40,.68),(.85,.82),(1.249,.83),(1.8,.73),(1.973,.66)]
def width(y):return interp(y,widthProfile)
def shoulder(y):return interp(y,shoulderProfile)
def body_y(y,u):
    front=max(0,1-((y+1.973)/.273))
    rear=max(0,1-((1.973-y)/.273))
    return y+(.12*front-.10*rear)*u*u
def topz(x,y):
    u=abs(x)/width(y)
    center=interp(y,[(-1.973,.455),(-1.6,.49),(-1.1,.57),(-.50,.695),(.82,.80),(1.3,.79),(1.973,.66)])
    return center+(shoulder(y)-center)*math.sin(u*math.pi/2)**3+.012*math.sin(u*math.pi)
# Hood and rear deck are compound surfaces; the cockpit remains an actual opening.
for name,y0,y1,rows in [('Long sculpted bonnet',-1.973,-.47,48),('Rear haunches and deck',.82,1.973,40)]:
    verts=[];faces=[];cols=32
    for i in range(rows+1):
        y=y0+(y1-y0)*i/rows
        for j in range(cols+1):
            x=width(y)*(2*j/cols-1);verts.append((x,body_y(y,x/width(y)),topz(x,y)))
    for i in range(rows):
        for j in range(cols):a=i*(cols+1)+j;faces.append((a,a+1,a+cols+2,a+cols+1))
    mesh(name,verts,faces,red)
# Side skin with true wheel openings, rolled lips and lined wheel wells.
axles=[(-1.102,.299,.333,.175,.728),(1.249,.317,.351,.225,.7425)]
for side in [-1,1]:
    verts=[];faces=[];rows=180;cols=8
    for i in range(rows+1):
        y=-1.973+3.946*i/rows;lo=.155
        for cy,cz,r,tw,track in axles:
            if abs(y-cy)<r:lo=max(lo,cz+math.sqrt(r*r-(y-cy)**2))
        hi=shoulder(y)
        for j in range(cols+1):
            t=j/cols;z=lo+(hi-lo)*t
            x=width(y)-.027*(1-t)-.025*math.sin(t*math.pi)
            verts.append((side*x,body_y(y,1),z))
    for i in range(rows):
        for j in range(cols):a=i*(cols+1)+j;faces.append((a,a+1,a+cols+2,a+cols+1))
    mesh(('Left' if side<0 else 'Right')+' continuous side panels with wheel arches',verts,faces,red)
    for cy,cz,r,tw,track in axles:
        angles=np.linspace(-.43,math.pi+.43,64)
        pts=[(side*(width(cy+r*math.cos(a))-.003),cy+r*math.cos(a),cz+r*math.sin(a)) for a in angles]
        tube('Rolled arch lip',pts,.009,red,8)
        vs=[];fs=[]
        for a in angles:
            y=cy+r*math.cos(a);z=cz+r*math.sin(a)
            vs.extend([(side*(width(y)-.01),y,z),(side*(track-tw/2-.07),y,z)])
        for k in range(len(angles)-1):fs.append((2*k,2*k+1,2*k+3,2*k+2))
        mesh('Black wheel arch liner',vs,fs,rubber)
    # Narrow cockpit rails connect the front and rear, instead of filling in the roof.
    vs=[];fs=[];rows=40
    for i in range(rows+1):
        y=-.47+1.29*i/rows
        for j in range(5):
            t=j/4;vs.append((side*(.625+(width(y)-.625)*t),y,shoulder(y)+.015*math.sin(t*math.pi)))
    for i in range(rows):
        for j in range(4):a=i*5+j;fs.append((a,a+1,a+6,a+5))
    mesh('Cockpit shoulder rail',vs,fs,red)
    tube('Door shut line',[(side*(width(y)+.001),y,z) for y,z in [(-.45,.724),(-.49,.48),(-.43,.24),(.41,.24),(.61,.42),(.59,.72)]],.003,carbon,6)
    tube('Sill highlight',[(side*(width(y)-.012),y,.19) for y in np.linspace(-.70,.84,32)],.012,red)
    box('Flush door release',(side*.802,.32,.65),(.012,.13,.02),carbon,.005)
    # Deep sculpted intake ahead of the rear wheel.
    mesh('Rear quarter recessed intake',[(side*.837,.60,.34),(side*.848,.68,.65),(side*.848,.91,.69),(side*.854,.94,.46)],[(0,1,2,3)],carbon,False)
    tube('Intake bright leading edge',[(side*.849,.64,.36),(side*.85,.71,.64),(side*.852,.90,.68)],.008,red)
    tube('Mirror stalk',[(side*.72,-.30,.755),(side*.84,-.34,.83)],.016,carbon)
    ellipsoid('Aerodynamic mirror housing',(side*.869,-.34,.837),(.055,.115,.047),red)
    ellipsoid('Mirror inset',(side*.871,-.245,.838),(.042,.011,.032),alloy)
# Nose: curving bumper, a central intake and inset fog lamps.
vs=[];fs=[]
for j in range(9):
    z=.155+j*.30/8
    for i in range(41):
        u=2*i/40-1;vs.append((u*.63,-1.973+.12*u*u+.012*(1-u*u)*math.sin(j/8*math.pi),z))
for j in range(8):
    for i in range(40):a=j*41+i;fs.append((a,a+1,a+42,a+41))
mesh('Rounded front fascia',vs,fs,red)
def fascia_y(x,z):
    u=x/.63;t=(z-.155)/.30
    return -1.973+.12*u*u+.012*(1-u*u)*math.sin(t*math.pi)-.006
outline=[];vs=[];fs=[]
for j in range(5):
    t=j/4
    for i in range(33):
        u=2*i/32-1;x=u*(.41+.05*t);z=.235+.035*abs(u)**3+(.355-.235-.035*abs(u)**3)*t
        vs.append((x,fascia_y(x,z),z))
for j in range(4):
    for i in range(32):a=j*33+i;fs.append((a,a+1,a+34,a+33))
mesh('Smile intake recessed mesh',vs,fs,carbon)
outline=vs[:33]+[vs[j*33+32] for j in range(1,5)]+list(reversed(vs[4*33:4*33+32]))+[vs[j*33] for j in range(3,0,-1)]
tube('Intake surround',outline+[outline[0]],.011,rubber)
for x in np.linspace(-.35,.35,18):
    tube('Intake vertical fin',[(x,fascia_y(x,z)-.004,z) for z in np.linspace(.256,.342,6)],.0025,steel,4)
for side in [-1,1]:
    ellipsoid('Fog lamp recess',(side*.543,-1.922,.296),(.05,.011,.04),carbon)
    ellipsoid('Fog lamp optic',(side*.543,-1.934,.296),(.032,.009,.027),white)
# Swept teardrop lamps conform to bonnet, with twin reflectors and perimeter seal.
for side in [-1,1]:
    outline=[]
    for a in np.linspace(0,2*math.pi,49):
        y=-1.53+.335*math.cos(a);x=side*(.631+.108*math.sin(a)*(1-.3*math.cos(a)))
        outline.append((x,y,topz(x,y)+.010))
    mesh('Swept headlamp housing',[(side*.632,-1.53,topz(.632,-1.53)+.012)]+outline[:-1],[(0,i+1,(i+1)%48+1) for i in range(48)],visor)
    tube('Headlight gasket',outline,.005,carbon,6)
    for y,r in [(-1.64,.071),(-1.39,.049)]:
        x=side*.633;z=topz(x,y)+.018
        o=ellipsoid('Projector reflector',(x,y,z),(r,r,.019),alloy,24,12)
        ellipsoid('Projector optic',(x,y,z+.012),(r*.68,r*.68,.014),white,24,12)
    vs=[(side*.632,-1.53,topz(.632,-1.53)+.063)];fs=[]
    for k in range(1,9):
        t=k/8
        for a in np.linspace(0,2*math.pi,48,endpoint=False):
            y=-1.53+t*.335*math.cos(a);x=side*(.632+t*.108*math.sin(a)*(1-.3*math.cos(a)))
            vs.append((x,y,topz(x,y)+.016+.047*(1-t*t)))
    for i in range(48):fs.append((0,1+i,1+(i+1)%48))
    for k in range(7):
        for i in range(48):a=1+k*48+i;b=1+k*48+(i+1)%48;fs.append((a,b,b+48,a+48))
    mesh('Contiguous curved headlamp lens cover',vs,fs,window)
    ellipsoid('Amber side marker',(side*.80,-1.62,.53),(.012,.075,.025),rearLamp,20,10)
# Bonnet seams and two radiator outlet slots, positioned as on the first-generation car.
for side in [-1,1]:
    points=[]
    for y in np.linspace(-1.78,-.48,48):
        x=side*interp(y,[(-1.78,.45),(-1.1,.48),(-.48,.56)])
        points.append((x,y,topz(x,y)+.002))
    tube('Bonnet panel gap',points,.0028,carbon,6)
    for y in [-.79]:
        vs=[(x,yy,topz(x,yy)+.004) for x,yy in [(side*.11,y-.07),(side*.48,y-.07),(side*.48,y+.07),(side*.11,y+.07)]]
        mesh('Hood radiator outlet',vs,[(0,1,2,3)],carbon,False)
        for xx in np.linspace(.15,.44,7):tube('Outlet grille rib',[(side*xx,y-.067,topz(side*xx,y)+.007),(side*xx,y+.067,topz(side*xx,y)+.007)],.003,steel,4)
# Chassis/cabin floor and carved, bolstered leather seats.
box('Carbon undertray',(0,0,.16),(1.36,3.7,.055),carbon,.025)
box('Cockpit tub',(0,.17,.32),(1.25,1.25,.30),leather,.075)
box('Dashboard',(0,-.43,.68),(1.20,.22,.19),leather,.06)
for side in [-1,1]:
    box('Seat sculpted cushion',(side*.34,.17,.45),(.43,.51,.105),leather,.045)
    back=box('Seat sculpted back',(side*.34,.54,.66),(.43,.115,.47),leather,.065);back.rotation_euler.x=-.15
    for sx in [-1,1]:
        ellipsoid('Seat cushion bolster',(side*.34+sx*.205,.17,.49),(.05,.255,.075),leather)
        ellipsoid('Seat back bolster',(side*.34+sx*.19,.51,.67),(.058,.10,.24),leather)
    ellipsoid('Seat head restraint',(side*.34,.575,.92),(.14,.075,.12),leather)
    for z in np.linspace(.53,.81,7):tube('Seat horizontal stitching',[(side*.34-.14,.471,z),(side*.34+.14,.471,z)],.0018,alloy,4)
box('Center console',(0,.04,.47),(.18,.82,.18),carbon,.028)
box('Touchscreen center bezel',(0,-.399,.713),(.22,.013,.115),carbon,.01)
# DON'T PANIC dashboard letters as geometry; all lettering remains editable.
def text(name,body,loc,size,mat,rotation):
    cu=bpy.data.curves.new(name,'FONT');cu.body=body;cu.size=size;cu.align_x='CENTER';cu.extrude=.0003
    o=bpy.data.objects.new(name,cu);COL.objects.link(o);o.location=loc;o.rotation_euler=rotation;cu.materials.append(mat)
    return o
text('Dashboard inscription',"DON'T PANIC",(0,-.387,.70),.021,white,(math.pi/2,0,0))
# Swept wraparound windshield with thin painted A-pillars and rubber gasket.
vs=[];fs=[];cols=32;rows=12
for j in range(rows+1):
    t=j/rows
    for i in range(cols+1):
        u=2*i/cols-1;vs.append((u*(.73-.09*t),-.49+.32*t+.045*(1-u*u),.746+.381*t-.025*u*u))
for j in range(rows):
    for i in range(cols):a=j*(cols+1)+i;fs.append((a,a+1,a+cols+2,a+cols+1))
mesh('Curved raked windshield',vs,fs,window)
for side in [-1,1]:tube('Painted windshield A pillar',[(side*(.73-.09*t),-.49+.32*t,.721+.381*t) for t in np.linspace(0,1,16)],.022,red,10)
tube('Windshield header',[(u*.64,-.17+.045*(1-u*u),1.127-.025*u*u) for u in np.linspace(-1,1,33)],.025,carbon,10)
tube('Windshield lower seal',[(u*.73,-.49+.045*(1-u*u),.746-.025*u*u) for u in np.linspace(-1,1,33)],.012,carbon)
for side in [-1,1]:
    tube('Seatbelt roll hoop',[(side*.61,.68,.79),(side*.60,.77,1.06),(side*.52,.80,1.105),(side*.08,.80,1.105)],.038,carbon,12)
# Rear fascia with four round taillights and lower diffuser.
vs=[];fs=[]
for j in range(9):
    z=.17+j*.49/8
    for i in range(41):u=2*i/40-1;vs.append((u*.72,1.973-.10*u*u,z))
for j in range(8):
    for i in range(40):a=j*41+i;fs.append((a,a+1,a+42,a+41))
mesh('Rear bumper skin',vs,fs,red)
box('Rear black diffuser',(0,1.958,.255),(1.22,.04,.20),carbon,.025)
for x in np.linspace(-.49,.49,6):box('Diffuser fin',(x,1.8,.175),(.012,.35,.075),carbon,.004)
for side in [-1,1]:
    for x in [.44,.61]:
        ellipsoid('Round taillight black bezel',(side*x,1.973-.10*(x/.72)**2+.012,.562),(.077,.015,.077),carbon,32,12)
        ellipsoid('Round ruby tail lens',(side*x,1.973-.10*(x/.72)**2+.026,.562),(.064,.011,.064),rearLamp,32,12)
    tube('Rear deck edge',[(side*x,1.928-.08*(x/.72)**2,.697) for x in np.linspace(0,.7,24)],.015,red)
text('Rear lettering','TESLA',(0,1.987,.552),.047,alloy,(math.pi/2,0,math.pi))
# Small T badge on the nose, authored geometric mark rather than a texture decal.
mesh('Nose T badge',[(-.04,-1.825,.498),(.04,-1.825,.498),(.032,-1.80,.505),(.009,-1.80,.505),(.005,-1.754,.516),(-.005,-1.754,.516),(-.009,-1.80,.505),(-.032,-1.80,.505)],[tuple(range(8))],alloy,False)
# Revolved tire carcass, inset discs, ten thin directional alloy spokes, valve and tread.
for cy,cz,r,tw,track in axles:
    rr=cz;rim=.2032 if cy<0 else .2159
    for side in [-1,1]:
        xc=side*track;profile=[(-tw/2,rim),(-tw*.49,rr*.88),(-tw*.36,rr*.98),(-tw*.20,rr),(tw*.20,rr),(tw*.36,rr*.98),(tw*.49,rr*.88),(tw/2,rim)]
        vs=[];fs=[];segments=64
        for x,rad in profile:
            for j in range(segments):a=2*math.pi*j/segments;vs.append((xc+x,cy+rad*math.cos(a),cz+rad*math.sin(a)))
        for k in range(len(profile)-1):
            for j in range(segments):a=k*segments+j;fs.append((a,k*segments+(j+1)%segments,(k+1)*segments+(j+1)%segments,a+segments))
        mesh('Directional tire carcass',vs,fs,rubber)
        faceX=xc+side*(tw/2+.003)
        # Alloy rim ring and dark inner barrel; brake visible through real spoke gaps.
        for rad,radTube,mat,off,name in [(rim-.01,.012,alloy,0,'Polished rim lip'),(rim-.016,.017,carbon,-.035,'Wheel barrel'),(.143,.016,steel,-.065,'Ventilated brake disc')]:
            tube(name,[(faceX+side*off,cy+rad*math.cos(a),cz+rad*math.sin(a)) for a in np.linspace(0,2*math.pi,65)],radTube,mat,8)
        ellipsoid('Brake rotor',(faceX-side*.06,cy,cz),(.008,.143,.143),steel,32,16)
        box('Red brake caliper',(faceX-side*.045,cy+.122,cz),(.035,.07,.12),red,.014)
        for k in range(10):
            a=k*2*math.pi/10
            pts=[]
            for t in np.linspace(0,1,5):
                rad=.040+(rim-.058)*t;angle=a+.13*t
                pts.append((faceX+side*.007*math.sin(t*math.pi),cy+rad*math.cos(angle),cz+rad*math.sin(angle)))
            tube('Directional forged wheel spoke',pts,.011,alloy,6)
        ellipsoid('Wheel center cap',(faceX+side*.012,cy,cz),(.012,.042,.042),alloy,24,12)
        for k in range(5):
            a=k*2*math.pi/5;ellipsoid('Wheel lug',(faceX+side*.021,cy+.067*math.cos(a),cz+.032*math.sin(a)),(.004,.005,.005),steel,12,6)
        # Fine directional channels delineate the tread, kept shallow to avoid a tractor tire.
        for k in range(40):
            a=k*2*math.pi/40
            for sign in [-1,1]:
                pts=[]
                for t in np.linspace(.05,1,4):
                    aa=a+.075*t;pts.append((xc+sign*tw*.30*t,cy+(rr+.0008)*math.cos(aa),cz+(rr+.0008)*math.sin(aa)))
                tube('Tire tread channel',pts,.002,rubber,4)
# Starman: anatomically shaped seated figure with tapered sleeves, gloves, suit paneling and visor.
x=-.34
ellipsoid('Starman seated hips',(x,.30,.53),(.15,.13,.11),white)
body=ellipsoid('Starman tailored torso',(x,.30,.79),(.175,.105,.235),white,32,20);body.rotation_euler.x=-.10
ellipsoid('Starman neck ring',(x,.275,1.002),(.093,.082,.035),carbon)
ellipsoid('Starman helmet shell',(x,.25,1.125),(.125,.115,.145),white,40,24)
# Visor conforms to the helmet as a contiguous curved patch facing the bonnet.
vs=[];fs=[]
for j in range(9):
    phi=-.13+.69*j/8
    for i in range(25):
        a=-1.05+2.10*i/24
        vs.append((x+.128*math.sin(a)*math.cos(phi),.25-.119*math.cos(a)*math.cos(phi),1.125+.148*math.sin(phi)))
for j in range(8):
    for i in range(24):a=j*25+i;fs.append((a,a+1,a+26,a+25))
mesh('Starman panoramic black visor',vs,fs,visor)
tube('Helmet brow rim',[(x+.129*math.sin(a)*math.cos(.56),.25-.120*math.cos(a)*math.cos(.56),1.125+.148*math.sin(.56)) for a in np.linspace(-1.05,1.05,25)],.005,white)
for side in [-1,1]:
    ellipsoid('Helmet hinge',(x+side*.12,.215,1.13),(.012,.022,.022),carbon,20,10)
# Smooth tapered segment via sphere aligned between anatomical joints.
def limb(name,a,b,radii,mat):
    mid=(Vector(a)+Vector(b))/2;d=Vector(b)-Vector(a)
    o=ellipsoid(name,mid,(radii[0],radii[1],d.length/2+.022),mat,24,14)
    o.rotation_euler=d.to_track_quat('Z','Y').to_euler();return o
# Left arm resting on the door, right hand on the wheel.
for a,b,c in [((x-.15,.28,.93),(-.60,.15,.76),(-.70,-.09,.73)),((x+.15,.28,.93),(-.11,.02,.74),(-.25,-.20,.78))]:
    limb('Starman upper sleeve',a,b,(.069,.059),white)
    ellipsoid('Starman elbow joint',b,(.063,.055,.060),carbon)
    limb('Starman forearm sleeve',b,c,(.055,.046),white)
    ellipsoid('Starman black glove',c,(.062,.051,.037),carbon)
for side in [-1,1]:
    hip=(x+side*.075,.26,.53);knee=(x+side*.09,-.14,.48);ankle=(x+side*.09,-.36,.31)
    limb('Starman upper trouser',hip,knee,(.076,.068),white)
    ellipsoid('Starman articulated knee',knee,(.078,.061,.053),carbon)
    limb('Starman lower trouser',knee,ankle,(.060,.051),white)
    ellipsoid('Starman boot',(ankle[0],ankle[1]-.055,.29),(.065,.12,.055),carbon)
for side in [-1,1]:tube('Suit chest seam',[(x+side*.115,.196,.66),(x+side*.11,.188,.81),(x+side*.12,.198,.90)],.003,carbon,6)
tube('Suit zipper',[(x,.19,.67),(x,.188,.90)],.0025,carbon,6)
# Restraint webbing is separate from the suit.
for side in [-1,1]:tube('Seat belt shoulder strap',[(x+side*.11,.31,.96),(x+side*.092,.183,.79),(x+side*.08,.173,.62)],.012,carbon,6)
box('Harness buckle',(x,.166,.63),(.057,.020,.033),alloy,.005)
# Wheel in a tilted plane, connected to steering column and three spokes.
center=Vector((x,-.25,.765));u=Vector((1,0,0));v=Vector((0,.35,.94));normal=u.cross(v)
pts=[tuple(center+.137*(math.cos(a)*u+math.sin(a)*v)) for a in np.linspace(0,2*math.pi,49)]
tube('Leather steering wheel',pts,.017,leather,10)
for a in [math.pi/6,5*math.pi/6,3*math.pi/2]:tube('Steering wheel spoke',[tuple(center),tuple(center+.123*(math.cos(a)*u+math.sin(a)*v))],.018,alloy,6)
ellipsoid('Steering wheel airbag',center,(.044,.021,.04),leather)
tube('Steering column',[tuple(center),tuple(center+normal*.18)],.028,carbon)
scene['reference']='Tesla original Roadster, SpaceX Falcon Heavy payload with Starman; authored geometry'
scene['nominal_length_m']=3.946;scene['wheelbase_m']=2.351
scene['front_axle_y']=-1.102;scene['rear_axle_y']=1.249
scene['generation']='First-generation open-top Roadster, not the second-generation concept'
scene['asset_origin']='Body center, tire contact plane z=0; front -Y'
# Mesh evaluation is deferred to the export, retaining editable curved surfaces and parts.
bpy.context.view_layer.update()
result={'components':len(COL.objects),'bodyLength':3.946,'wheelbase':2.351,'scene':scene.name}
