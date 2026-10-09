"""Run through Blender MCP in ROADSTER v3 | Open cockpit. Front is -Y; units are meters.

Reference: user-supplied modern red Roadster image. Proportions are visually
estimated from that photograph, not certified manufacturer dimensions.
Starman is retained as an artistic space-visitor passenger.
This creates a new collection, leaving the existing orbital_visitors source intact.
"""
import bpy, bmesh, math, json
import numpy as np
from pathlib import Path
from mathutils import Vector
ROOT = Path(__file__).resolve().parents[2]
scene = bpy.context.scene
assert scene.name == 'ROADSTER v3 | Open cockpit'
assert not scene.objects, 'Use a fresh authoring scene; never replace an existing scene.'
COL = bpy.data.collections.new('ROADSTER v3 | Editable components')
scene.collection.children.link(COL)
TEX = ROOT / 'art/textures/roadster'
TEX.mkdir(parents=True, exist_ok=True)

def material(name, color, metal=0, rough=.4):
    m=bpy.data.materials.new(name); m.diffuse_color=(*color,1);m.use_nodes=True
    p=m.node_tree.nodes.get('Principled BSDF')
    p.inputs['Base Color'].default_value=(*color,1)
    p.inputs['Metallic'].default_value=metal;p.inputs['Roughness'].default_value=rough
    return m
red=material('Roadster v3 | Cherry red paint',(.40,.003,.008),.18,.20)
red.node_tree.nodes.get('Principled BSDF').inputs['Coat Weight'].default_value=.8
red.node_tree.nodes.get('Principled BSDF').inputs['Coat Roughness'].default_value=.16
rubber=material('Roadster v3 | Tire rubber',(.015,.018,.022),0,.78)
leather=material('Roadster v3 | Black leather',(.023,.025,.028),0,.47)
carbon=material('Roadster v3 | Carbon trim',(.028,.032,.038),.4,.3)
alloy=material('Roadster v3 | Forged aluminium',(.022,.026,.033),.76,.29)
steel=material('Roadster v3 | Brake steel',(.070,.078,.083),.65,.48)
visor=material('Roadster v3 | Visor and optics',(.008,.018,.027),.48,.12)
white=material('Roadster v3 | Suit and light lenses',(.78,.81,.82),.05,.48)
window=material('Roadster v3 | Windshield',(.004,.011,.018),.48,.12)
p=window.node_tree.nodes.get('Principled BSDF');p.inputs['Alpha'].default_value=1
window.diffuse_color=(.004,.011,.018,1);window.surface_render_method='DITHERED';window.use_transparency_overlap=False
rearLamp=material('Roadster v3 | Ruby tail lenses',(.34,.004,.006),.12,.22)
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
# Broad compound surfaces. Nose -Y, dimensions estimated from the supplied photograph.
widthProfile=[(-2.30,.73),(-2.06,.88),(-1.44,1.005),(-.75,.966),(0,.958),(.65,1.003),(1.44,1.044),(1.95,1.014),(2.30,.895)]
shoulderProfile=[(-2.30,.49),(-2.06,.61),(-1.44,.83),(-.75,.84),(0,.824),(.65,.886),(1.44,.924),(1.95,.877),(2.30,.76)]
centerProfile=[(-2.30,.435),(-2.06,.515),(-1.44,.625),(-.66,.784),(1.46,.855),(1.85,.852),(2.30,.725)]
def width(y):return interp(y,widthProfile)
def shoulder(y):return interp(y,shoulderProfile)
def topz(x,y):
    u=abs(x)/width(y)
    z=interp(y,centerProfile)
    return z+(shoulder(y)-z)*math.sin(u*math.pi/2)**4+.008*math.sin(u*math.pi)-.035*max(0,(u-.9)/.1)**2
def body_y(y,u):
    front=max(0,1-((y+2.30)/.35));rear=max(0,1-((2.30-y)/.35))
    return y+(.18*front-.22*rear)*u*u

def surface(name,rows,cols,point,mat):
    vs=[point(i/rows,j/cols) for i in range(rows+1) for j in range(cols+1)];fs=[]
    for i in range(rows):
        for j in range(cols):a=i*(cols+1)+j;fs.append((a,a+1,a+cols+2,a+cols+1))
    return mesh(name,vs,fs,mat)

# Low unbroken hood; modern design has no exposed rectangular radiator grilles.
for name,ya,yb,rows in [('Compound hood and flared front wings',-2.30,-.66,56),('Fastback deck and wide rear haunches',1.46,2.30,36)]:
    def point(t,u,ya=ya,yb=yb):
        y=ya+(yb-ya)*t;x=(2*u-1)*width(y)
        return (x,body_y(y,x/width(y)),topz(x,y))
    surface(name,rows,40,point,red)
axles=[(-1.44,.358,.389,.255,.886,.296),(1.44,.378,.408,.300,.890,.316)]
# Sculpted flank: shallow waist under the shoulder, deeper low sweep through the door.
def flank_x(y,t):
    w=width(y)
    waist=interp(y,[(-2.30,.02),(-1.44,.02),(-.82,.065),(0,.13),(.80,.075),(1.44,.02),(2.30,.015)])
    return w-interp(t,[(0,.033),(.12,0),(.35,waist),(.57,waist*.76),(.8,.027),(1,0)])
def flank_z(y,t):
    lo=.145
    for cy,cz,ar,tw,track,rim in axles:
        if abs(y-cy)<ar:lo=max(lo,cz+math.sqrt(ar*ar-(y-cy)**2))
    return lo+(shoulder(y)-.035-lo)*t
for side in [-1,1]:
    def point(t,u,side=side):
        y=-2.30+4.60*t
        return (side*flank_x(y,u),body_y(y,1),flank_z(y,u))
    surface('Continuous sculpted flank with wheel openings',230,16,point,red)
    # Narrow raised wheel lips and curved inner liners, not floating wheel rings.
    for cy,cz,ar,tw,track,rim in axles:
        angles=np.linspace(-.50,math.pi+.50,72)
        pts=[(side*(width(cy+ar*math.cos(a))+.0005),cy+ar*math.cos(a),cz+ar*math.sin(a)) for a in angles]
        tube('Flush rolled wheel arch edge',pts,.0045,red,6)
        vs=[];fs=[]
        for a in angles:
            y=cy+ar*math.cos(a);z=cz+ar*math.sin(a)
            vs.extend([(side*(width(y)-.009),y,z),(side*(track-tw/2-.075),y,z)])
        for k in range(len(angles)-1):fs.append((k*2,k*2+1,k*2+3,k*2+2))
        mesh('Wheelhouse inner liner',vs,fs,rubber)
    # The side of the cockpit flows into the high rear shoulder; no square door tops.
    def point(t,u,side=side):
        y=-.66+2.12*t;inside=interp(y,[(-.66,.80),(.2,.73),(.80,.73),(1.46,.57)])
        return (side*(inside+(width(y)-inside)*u),y,shoulder(y)+.010*math.sin(u*math.pi)-.035*u*u)
    surface('Painted cockpit shoulder sweep',64,12,point,red)
    # Deep crescent sill follows the lower door's sculptural diagonal.
    ys=np.linspace(-.96,1.015,58)
    def sill_z(y):return interp(y,[(-.96,.155),(-.55,.171),(.16,.247),(.72,.352),(1.015,.418)])
    def point(t,u,side=side):
        y=-.96+1.975*t;z=sill_z(y)+u*.045
        return (side*(width(y)-.009+.022*math.sin(u*math.pi)),y,z)
    surface('Swept lower door blade',58,6,point,red)
    tube('Carbon lower sill',[(side*(width(y)-.02),y,sill_z(y)-.019) for y in ys],.013,carbon,8)
    # Door seam begins at the A pillar and ends at the upswept quarter, kept hairline-thin.
    points=[]
    for y,t in [(-.76,.95),(-.82,.62),(-.87,.34),(-.68,.13),(-.32,.13),(.1,.2),(.51,.37),(.81,.58),(.86,.91)]:
        points.append((side*(flank_x(y,t)+.002),y,flank_z(y,t)))
    tube('Flush door shut line',points,.0024,carbon,6)
    # Flush release and three narrow slats behind the front wheel match the image's details.
    box('Flush door release',(side*.967,.41,.847),(.008,.128,.013),carbon,.003)
    for dz in [0,.023,.046]:
        tube('Front quarter vent slot',[(side*.973,-.88,.72+dz),(side*.971,-.72,.725+dz)],.005,carbon,6)
    tube('Mirror mounting stalk',[(side*.79,-.37,.875),(side*.99,-.43,.901)],.012,carbon)
    ellipsoid('Low-profile mirror housing',(side*1.023,-.435,.915),(.07,.13,.037),red,32,12)
    ellipsoid('Mirror reflective face',(side*1.023,-.325,.914),(.05,.010,.025),alloy,24,10)
# Smooth nose, with the broad black undercut and narrow red chin from the photograph.
def fascia_y(x,z):
    u=x/.73;t=(z-.135)/.355
    return -2.30+.18*u*u+.090*(1-u*u)*math.sin(t*math.pi)
def nose(t,u):
    x=(2*u-1)*.73;z=.135+t*(topz(x,-2.30)-.135)
    return (x,fascia_y(x,z),z)
surface('Rounded modern nose skin',16,64,nose,red)
# Conforming intake overlay follows the fascia, preventing panels crossing through its grille.
def mouth(t,u):
    q=2*u-1;x=q*(.62+.09*t)
    bottom=.148+.074*abs(q)**3;top=.387-.018*abs(q)**2
    z=bottom+(top-bottom)*t
    return (x,fascia_y(x,z)-.007,z)
surface('Wide black sculpted front intake',10,64,mouth,carbon)
tube('Front intake upper edge',[mouth(1,i/64) for i in range(65)],.008,visor,8)
tube('Integrated lower splitter',[mouth(0,i/64) for i in range(65)],.013,carbon,8)
for side in [-1,1]:
    # Swept slit lamps. Separate dark pocket, segmented optics and a narrow daylight blade.
    xy=[(.48,-2.065),(.71,-2.048),(.925,-1.898),(.912,-1.844),(.76,-1.944),(.54,-1.989)]
    vs=[(side*x,y,topz(x,y)+.005) for x,y in xy]
    mesh('Swept horizontal headlight lens',vs,[tuple(range(len(vs)))],visor,False)
    tube('Headlight perimeter seal',vs+[vs[0]],.0025,carbon,6)
    pts=[(side*x,y,topz(x,y)+.010) for x,y in [(.51,-2.053),(.70,-2.031),(.905,-1.899)]]
    tube('Slim daylight blade',pts,.0045,white,6)
    for x,y in [(.61,-2.011),(.711,-1.993),(.811,-1.947)]:
        # Reflector aperture lies on the compound wing surface, not a raised button.
        vertices=[(side*(x+dx),y+dy,topz(x+dx,y+dy)+.013) for dx,dy in [(-.032,-.012),(.032,-.012),(.032,.014),(-.032,.014)]]
        mesh('Recessed rectangular optical element',vertices,[(0,1,2,3)],white,False)
    # Subtle red corner accent under the lamp; no old round fog lamps.
    tube('Front aero corner crease',[(side*x,y,z) for x,y,z in [(.69,-2.139,.21),(.755,-2.082,.235),(.807,-1.999,.284)]],.005,red,6)
# Two hood character ridges run softly from the nose toward the glass.
for side in [-1,1]:
    points=[]
    for y in np.linspace(-1.98,-.68,48):
        x=side*interp(y,[(-1.98,.37),(-1.35,.45),(-.68,.64)])
        points.append((x,y,topz(x,y)+.002))
    tube('Fine hood perimeter seam',points,.0017,carbon,6)
# Cabin floor and center tunnel remain hidden below the waist.
box('Composite undertray',(0,0,.135),(1.68,4.27,.055),carbon,.025)
box('Recessed cockpit tub',(0,.35,.365),(1.38,1.67,.32),leather,.05)
box('Low wrap dashboard',(0,-.54,.744),(1.43,.22,.145),leather,.045)
box('Carbon center tunnel',(0,.10,.49),(.19,1.06,.16),carbon,.025)
# Shaped sports bucket seats, made from surfaces instead of a pile of spherical pillows.
for sx in [-.37,.37]:
    def cushion(t,u,sx=sx):
        x=(2*u-1)*.246;y=-.005+.60*t
        z=.482+.064*(abs(2*u-1)**3)+.012*math.cos((t-.5)*math.pi)
        return (sx+x,y,z)
    surface('Contoured sport seat cushion',20,20,cushion,leather)
    def back(t,u,sx=sx):
        x=(2*u-1)*(.245-.053*t);z=.49+.55*t
        y=.57+.095*t-.067*abs(2*u-1)**3
        return (sx+x,y,z)
    surface('Contoured sport seat back',26,20,back,leather)
    for side in [-1,1]:
        pts=[(sx+side*(.245-.053*t),.57+.095*t-.067,.49+.55*t) for t in np.linspace(0,1,25)]
        tube('Seat outer bolster',pts,.036,leather,10)
        pts=[(sx+side*.246,-.005+.60*t,.55) for t in np.linspace(0,1,20)]
        tube('Seat cushion bolster',pts,.029,leather,10)
    ellipsoid('Integrated seat headrest',(sx,.68,1.048),(.137,.052,.117),leather,28,12)
    for z in np.linspace(.61,.97,6):
        t=(z-.49)/.55;tube('Fine seat stitching',[(sx-.13,.568+.095*t,z),(sx+.13,.568+.095*t,z)],.0014,steel,4)
# Compound glass canopy: broad convex windshield, flush roof and flowing fastback.
def windshield(t,u):
    q=2*u-1;x=q*(.84-.09*t)
    y=-.71+.88*t-.075*(1-q*q)*(1-t)
    z=.818+.454*math.sin(t*math.pi/2)-.050*q*q
    return (x,y,z)
surface('Convex panoramic front glass',32,48,windshield,window)
for side in [-1,1]:
    tube('Thin painted A pillar',[windshield(t,0 if side<0 else 1) for t in np.linspace(0,1,33)],.012,red,10)
    tube('Black window gasket',[windshield(t,.008 if side<0 else .992) for t in np.linspace(0,1,33)],.0035,carbon,6)
tube('Low windshield base gasket',[windshield(0,i/48) for i in range(49)],.005,carbon,8)
# Roof panel and longitudinal roof rails are removed for the open cockpit.
# Retain the windshield header and rear canopy rim as finished cut edges.
tube('Finished windshield header',[windshield(1,i/48) for i in range(49)],.009,carbon,8)
def rear_glass(t,u):
    q=2*u-1
    z=interp(t,[(0,1.272),(.28,1.21),(.63,1.016),(1,topz(.57,1.46)+.002)])
    return (q*(.75-.18*t),.79+.67*t,z-.050*q*q*(1-t))
surface('Curved fastback rear glass',28,48,rear_glass,window)
tube('Finished rear canopy rim',[rear_glass(0,i/48) for i in range(49)],.009,carbon,8)
tube('Rear glass seal',[rear_glass(1,i/48) for i in range(49)],.005,carbon,8)
for side in [-1,1]:
    # The rear shoulder wraps directly into the glass edge, closing the previous open gap.
    def pillar(t,u,side=side):
        edge=rear_glass(t,0 if side<0 else 1);y=edge[1]
        x=abs(edge[0])+(width(y)-abs(edge[0]))*u
        z=edge[2]*(1-u)+(shoulder(y)-.035)*u+.026*math.sin(u*math.pi)
        z=z*(1-t**8)+(topz(x,y)+.002)*t**8
        return (side*x,y,z)
    surface('Continuous sculpted rear canopy buttress',28,16,pillar,red)
    tube('Rear canopy glass gasket',[rear_glass(t,0 if side<0 else 1) for t in np.linspace(0,1,29)],.004,carbon,6)
    # A small smoked quarter window, framed within the curved buttress.
    vs=[]
    for t,u in [(.09,.12),(.38,.52),(.55,.14)]:
        x,y,z=pillar(t,u);vs.append((x,y,z+.005))
    mesh('Smoked rear quarter window',vs,[(0,1,2)],window,False)
# Curved red cowl below the glass joins the hood without a floating frame.
def cowl(t,u):
    a=windshield(0,u);x=a[0];b=(x,-.66,topz(x,-.66))
    return tuple(a[k]*(1-t)+b[k]*t for k in range(3))
surface('Curved windshield cowl',4,48,cowl,red)
# Rear: slim blades, swept corners, integrated lip and a deep carbon diffuser.
def rear_face(t,u):
    q=2*u-1;x=q*.895;z=.15+t*(topz(x,2.30)-.15)
    return (x,2.30-.22*q*q-.027*math.sin(t*math.pi)*(1-q*q),z)
surface('Rounded tail fascia',16,64,rear_face,red)
for side in [-1,1]:
    pts=[]
    for x in np.linspace(.20,.86,30):
        z=.704-.03*(x/.9)**2;y=2.30-.22*(x/.895)**2+.006
        pts.append((side*x,y,z))
    tube('Slim ruby rear light blade',pts,.008,rearLamp,8)
    tube('Rear lamp dark bezel',[(x,y-.003,z-.011) for x,y,z in pts],.013,visor,8)
    tube('Subtle integrated rear lip',[(side*x,2.294-.22*(x/.895)**2,topz(x,2.30)+.003) for x in np.linspace(0,.885,40)],.009,red,8)
box('Recessed rear registration panel',(0,2.294,.53),(.47,.018,.16),carbon,.018)
# Wide concave diffuser, bounded by actual curved red panels.
def diffuser(t,u):
    q=2*u-1;x=q*.77;z=.175+.225*t+.015*abs(q)**2
    return (x,2.30-.22*(x/.895)**2+.010,z)
surface('Rear carbon diffuser insert',8,48,diffuser,carbon)
for x in np.linspace(-.63,.63,7):
    box('Vertical diffuser vane',(x,2.20,.18),(.014,.27,.105),carbon,.003)
# Forged directional split spokes and low-profile tires from the reference.
for cy,cz,ar,tw,track,rim in axles:
    for side in [-1,1]:
        xc=side*track;segments=72;vs=[];fs=[]
        profile=[(-tw/2,rim),(-tw*.49,cz*.965),(-tw*.36,cz*.995),(-tw*.20,cz),(tw*.20,cz),(tw*.36,cz*.995),(tw*.49,cz*.965),(tw/2,rim)]
        for x,r in profile:
            for k in range(segments):a=2*math.pi*k/segments;vs.append((xc+x,cy+r*math.cos(a),cz+r*math.sin(a)))
        for i in range(len(profile)-1):
            for k in range(segments):a=i*segments+k;fs.append((a,i*segments+(k+1)%segments,(i+1)*segments+(k+1)%segments,a+segments))
        mesh('Low-profile performance tire',vs,fs,rubber)
        faceX=xc+side*(tw*.5+.003)
        for rad,rTube,mat,off,name in [(rim-.004,.008,alloy,0,'Forged outer rim lip'),(rim-.018,.012,steel,-.020,'Deep wheel barrel'),(.207,.009,steel,-.070,'Ventilated brake rotor edge')]:
            tube(name,[(faceX+side*off,cy+rad*math.cos(a),cz+rad*math.sin(a)) for a in np.linspace(0,2*math.pi,73)],rTube,mat,8)
        ellipsoid('Brake disc',(faceX-side*.070,cy,cz),(.005,.207,.207),steel,40,12)
        box('Red performance brake caliper',(faceX-side*.045,cy+.174,cz),(.038,.064,.147),red,.012)
        # Five split pairs, shaped extruded quad strips with twist, rather than round tubes.
        for k in range(5):
            for split in [-1,1]:
                vs=[];fs=[];rows=12
                for i in range(rows+1):
                    t=i/rows;r=.048+(rim-.070)*t;a=k*2*math.pi/5+split*.095*t+.15*t
                    w=.014-.004*t
                    for depth,edge in [(-.014,-1),(-.014,1),(.002,1),(.002,-1)]:
                        v=Vector((faceX+side*depth,cy+r*math.cos(a),cz+r*math.sin(a)))
                        tangent=Vector((0,-math.sin(a),math.cos(a)));vs.append(tuple(v+edge*w*tangent))
                for i in range(rows):
                    for e in range(4):a=i*4+e;fs.append((a,i*4+(e+1)%4,(i+1)*4+(e+1)%4,a+4))
                fs.extend([tuple(reversed(range(4))),tuple(rows*4+j for j in range(4))])
                mesh('Twisted split forged wheel spoke',vs,fs,alloy)
        ellipsoid('Dark forged wheel center',(faceX+side*.004,cy,cz),(.012,.060,.060),alloy,24,12)
        for k in range(5):
            a=k*2*math.pi/5;ellipsoid('Wheel fastener',(faceX+side*.016,cy+.040*math.cos(a),cz+.040*math.sin(a)),(.004,.004,.004),steel,12,6)
        # Narrow directional tread channels give a performance tire, not off-road blocks.
        for k in range(42):
            a=k*2*math.pi/42
            for sign in [-1,1]:
                pts=[]
                for t in np.linspace(.08,1,4):
                    aa=a+.065*t;pts.append((xc+sign*tw*.30*t,cy+(cz+.0007)*math.cos(aa),cz+(cz+.0007)*math.sin(aa)))
                tube('Fine directional tire channel',pts,.0015,rubber,4)
# Starman remains the staged space-visitor occupant; move the prior suit into this cabin.

before_suit=set(COL.objects)
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

for o in set(COL.objects)-before_suit:
    o.location.y+=.18;o.location.z+=.035

# Minimal geometric badges; no personal license plates or imported photograph textures.
def badge_text(name,body,loc,size,mat,rotation):
    cu=bpy.data.curves.new(name,'FONT');cu.body=body;cu.size=size;cu.align_x='CENTER';cu.extrude=.0002
    o=bpy.data.objects.new(name,cu);COL.objects.link(o);o.location=loc;o.rotation_euler=rotation;cu.materials.append(mat)
    return o
badge_text('Rear TESLA lettering','T E S L A',(0,2.309,.66),.038,alloy,(math.pi/2,0,math.pi))
box('Dashboard display bezel',(0,-.422,.766),(.19,.012,.095),visor,.008)
badge_text('Dashboard inscription',"DON'T PANIC",(0,-.413,.755),.017,white,(math.pi/2,0,0))
mesh('Small nose T badge',[(x,y,topz(x,y)+.003) for x,y in [(-.038,-2.165),(.038,-2.165),(.032,-2.145),(.008,-2.145),(.004,-2.11),(-.004,-2.11),(-.008,-2.145),(-.032,-2.145)]],[tuple(range(8))],alloy,False)
scene['reference']='User-supplied modern red Roadster image; artist-estimated surfaces and dimensions'
scene['generation']='Modern concept design matching the supplied image'
scene['historical_payload']=False
scene['open_cockpit']=True
scene['occupant']='Starman: white suit, helmet, black visor, gloves and restraints'
scene['nominal_length_m']=4.60;scene['wheelbase_m']=2.88
scene['asset_origin']='Body center, tire contact plane z=0; front -Y'
bpy.context.view_layer.update()
result={'components':len(COL.objects),'bodyLength':4.60,'wheelbase':2.88,'scene':scene.name}
