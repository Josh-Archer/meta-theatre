"""Execute in the live Blender MCP session. Canonical meters, editable components and runtime copies."""
import bpy, math, json
import numpy as np
from pathlib import Path
from mathutils import Vector, Matrix
ROOT=(Path(__file__).resolve().parents[2] if '__file__' in globals() else Path(bpy.path.abspath('//')).resolve().parents[1])
if not (ROOT / "app/src/main/assets").is_dir():
    raise RuntimeError("Execute this script with __file__ set to its path in this project.")
scene=bpy.context.scene
assert scene.name=='ORBITAL VISITORS | Editable source'
def collection(name):
 c=bpy.data.collections.new(name);scene.collection.children.link(c);return c
ref=collection('NASA ISS | Full imported reference')
iss=collection('ISS | Quest optimized')
car=collection('ROADSTER | Editable components')
for o in list(scene.objects):
 if o.type in {'MESH','EMPTY'}:
  for c in list(o.users_collection):c.objects.unlink(o)
  ref.objects.link(o)
ref.hide_render=True;ref.hide_viewport=True

def material(name,rgba,metal=0,rough=.4):
 m=bpy.data.materials.new(name);m.diffuse_color=rgba;m.use_nodes=True
 p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Base Color'].default_value=rgba
 p.inputs['Metallic'].default_value=metal;p.inputs['Roughness'].default_value=rough
 return m
white=material('ISS | Thermal blanket ivory',(.72,.75,.73,1),.15,.58)
metal=material('ISS | Brushed aluminium',(.46,.51,.56,1),.7,.3)
gold=material('ISS | Gold insulation',(.47,.26,.055,1),.65,.34)
pv=material('ISS | Photovoltaic cells',(.12,.1,.035,1),.35,.42)
# Authored cell texture, color-space handled by Blender; 1K repeating UVs, no procedural runtime nodes.
n=1024
u,v=np.meshgrid(np.arange(n)/n,np.arange(n)/n)
pixels=np.zeros((n,n,4),dtype=np.float32)
grid=(np.mod(u*12,1)<.035)|(np.mod(v*6,1)<.035)
base=np.array([.055,.10,.19]); border=np.array([.49,.28,.075])
pixels[:,:,:3]=np.where(grid[:,:,None],border,base)
pixels[:,:,3]=1
image=bpy.data.images.new('ISS photovoltaic grid | 1024',width=n,height=n)
image.pixels.foreach_set(pixels.ravel());image.filepath_raw=str(ROOT/'art/textures/flybys/iss_cells.png');image.file_format='PNG';image.save()
node=pv.node_tree.nodes.new('ShaderNodeTexImage');node.image=image
pv.node_tree.links.new(node.outputs['Color'],pv.node_tree.nodes.get('Principled BSDF').inputs['Base Color'])
# Core contains the complete station; seven remote truss duplicates remain in the reference collection.
core=[o for o in ref.objects if o.type=='MESH' and o.name.startswith('polySurfa')]
points=[o.matrix_world@v.co for o in core for v in o.data.vertices]
minimum=Vector([min(p[i] for p in points) for i in range(3)])
maximum=Vector([max(p[i] for p in points) for i in range(3)])
center=(minimum+maximum)/2; factor=109/(maximum.y-minimum.y)
cyclic=Matrix(((0,1,0,0),(0,0,1,0),(1,0,0,0),(0,0,0,1)))
for src in core:
 o=bpy.data.objects.new('ISS | '+src.name,src.data.copy());iss.objects.link(o)
 o.data.transform(cyclic@Matrix.Scale(factor,4)@Matrix.Translation(-center)@src.matrix_world)
 old=[m.name for m in o.data.materials]
 o.data.materials.clear()
 for m in [white,metal,gold,pv]:o.data.materials.append(m)
 for face in o.data.polygons:
  name=old[face.material_index]
  face.material_index=3 if name.startswith('lambert7') else 1 if ('anisotropic' in name or 'truss' in name or 'apollo' in name) else 2 if 'lambert3' in name else 0
  face.use_smooth=True
 uv=o.data.uv_layers.active or o.data.uv_layers.new(name='PV UV')
 for face in o.data.polygons:
  for li in face.loop_indices:
   p=o.data.vertices[o.data.loops[li].vertex_index].co
   uv.data[li].uv=(p.x/6,p.y/6)
 mod=o.modifiers.new('Flyby silhouette simplification','DECIMATE');mod.ratio=.22
 bpy.context.view_layer.objects.active=o;o.select_set(True)
 bpy.ops.object.modifier_apply(modifier=mod.name);o.select_set(False)

red=material('Roadster | Multi coat electric red',(.48,.009,.018,1),.52,.23)
red.node_tree.nodes.get('Principled BSDF').inputs['Coat Weight'].default_value=.65
black=material('Roadster | Rubber and leather',(.012,.016,.021,1),.05,.57)
chrome=material('Roadster | Wheel aluminium',(.55,.6,.66,1),.8,.23)
glass=material('Roadster | Tinted glass and visor',(.035,.09,.13,1),.35,.17)
suit=material('Starman | Woven suit',(.82,.86,.87,1),0,.68)
lamp=material('Roadster | Lamp lenses',(.68,.77,.8,1),.15,.22)

def move(o,name,mat):
 o.name=name
 for c in list(o.users_collection):c.objects.unlink(o)
 car.objects.link(o);o.data.materials.clear();o.data.materials.append(mat)
 return o

def cube(name,loc,scale,mat,bevel=.03):
 bpy.ops.mesh.primitive_cube_add(size=1,location=loc);o=move(bpy.context.object,name,mat);o.scale=scale
 bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
 if bevel:
  mod=o.modifiers.new('Manufactured edges','BEVEL');mod.width=bevel;mod.segments=3
  bpy.context.view_layer.objects.active=o;bpy.ops.object.modifier_apply(modifier=mod.name)
 for p in o.data.polygons:p.use_smooth=True
 return o

def sphere(name,loc,scale,mat):
 bpy.ops.mesh.primitive_uv_sphere_add(segments=24,ring_count=12,location=loc)
 o=move(bpy.context.object,name,mat);o.scale=scale
 for p in o.data.polygons:p.use_smooth=True
 return o

def tube(name,a,b,r,mat,vertices=12):
 a,b=Vector(a),Vector(b);d=b-a
 bpy.ops.mesh.primitive_cylinder_add(vertices=vertices,radius=r,depth=d.length,location=(a+b)/2)
 o=move(bpy.context.object,name,mat);o.rotation_euler=d.to_track_quat('Z','Y').to_euler()
 for p in o.data.polygons:p.use_smooth=True
 return o

def loft(name,rows,mat):
 # Cross sections around the lower shell; open cockpit is constructed separately.
 verts=[]
 for y,width,zTop,zBottom in rows:
  verts.extend([(0,y,zBottom),(-width*.85,y,zBottom+.07),(-width,y,zTop-.14),(-width*.92,y,zTop),(width*.92,y,zTop),(width,y,zTop-.14),(width*.85,y,zBottom+.07)])
 faces=[];k=7
 for r in range(len(rows)-1):
  for j in range(k):faces.append((r*k+j,r*k+(j+1)%k,(r+1)*k+(j+1)%k,(r+1)*k+j))
 faces += [tuple(range(k-1,-1,-1)),tuple((len(rows)-1)*k+j for j in range(k))]
 mesh=bpy.data.meshes.new(name);mesh.from_pydata(verts,[],faces);mesh.update()
 o=bpy.data.objects.new(name,mesh);car.objects.link(o);mesh.materials.append(mat)
 mod=o.modifiers.new('Coachwork smoothing','BEVEL');mod.width=.045;mod.segments=3
 bpy.context.view_layer.objects.active=o;o.select_set(True);bpy.ops.object.modifier_apply(modifier=mod.name);o.select_set(False)
 for p in mesh.polygons:p.use_smooth=True
 return o
loft('Roadster | Lower sill shell',[(-1.98,.64,.48,.23),(-1.78,.83,.57,.19),(-1.2,.92,.58,.17),(-.6,.87,.57,.17),(.55,.89,.62,.18),(1.2,.94,.68,.2),(1.8,.85,.59,.22),(1.97,.66,.54,.28)],red)
loft('Roadster | Sculpted bonnet',[(-1.95,.61,.5,.47),(-1.72,.78,.62,.49),(-1.15,.83,.77,.57),(-.7,.77,.84,.58)],red)
loft('Roadster | Rear deck',[(.57,.81,.79,.58),(1.16,.88,.81,.64),(1.65,.82,.72,.61),(1.95,.63,.56,.5)],red)
for x in [-.87,.87]:
 cube('Roadster | Door shoulder',(x,0,.7),(.1,1.35,.27),red)
 tube('Roadster | Door seam',(x*1.015,-.65,.7),(x*1.015,.55,.75),.007,black,8)
 # Side intakes and mirror housing.
 cube('Roadster | Side cooling intake',(x*1.07,.54,.53),(.04,.32,.13),black,.02)
 sphere('Roadster | Mirror',(x*1.10,-.57,.91),(.12,.075,.05),red)
 tube('Roadster | Mirror support',(x,-.6,.78),(x*1.10,-.57,.91),.014,black)
# Wheel torus tires along X with recessed multi-spoke rims.
for x in [-.86,.86]:
 for y in [-1.24,1.26]:
  bpy.ops.mesh.primitive_torus_add(major_segments=40,minor_segments=12,location=(x,y,.34),major_radius=.255,minor_radius=.075,rotation=(0,math.pi/2,0))
  o=move(bpy.context.object,'Roadster | Performance tire',black)
  for p in o.data.polygons:p.use_smooth=True
  outer=x+math.copysign(.065,x)
  tube('Roadster | Brake rotor',(outer-.014,y,.34),(outer+.014,y,.34),.205,chrome,32)
  tube('Roadster | Dark rim recess',(outer,y,.34),(outer+math.copysign(.018,x),y,.34),.18,black,32)
  for angle in range(0,360,72):
   a=math.radians(angle);a2=a+.18
   tube('Roadster | Five spoke alloy',(outer+math.copysign(.021,x),y+.05*math.sin(a),.34+.05*math.cos(a)),(outer+math.copysign(.023,x),y+.195*math.sin(a2),.34+.195*math.cos(a2)),.025,chrome,8)
  tube('Roadster | Wheel center',(outer,y,.34),(outer+math.copysign(.03,x),y,.34),.055,chrome,16)
# Dark open cockpit, two seats, console and windshield.
cube('Roadster | Cockpit well',(0,-.03,.665),(1.52,1.21,.10),black)
for x in [-.4,.4]:
 cube('Roadster | Seat cushion',(x,.12,.74),(.51,.55,.13),black,.07)
 seat=cube('Roadster | Sport seat back',(x,.43,.96),(.49,.17,.5),black,.08);seat.rotation_euler.x=-.13
 sphere('Roadster | Headrest',(x,.47,1.17),(.16,.08,.11),black)
cube('Roadster | Center console',(0,.02,.83),(.15,.67,.20),black)
cube('Roadster | Dashboard',(0,-.49,.90),(1.43,.19,.2),black)
# Four-corner windscreen with slanted top and thin structural frame.
verts=[(-.75,-.63,.85),(.75,-.63,.85),(.64,-.40,1.22),(-.64,-.40,1.22)]
me=bpy.data.meshes.new('Roadster windscreen');me.from_pydata(verts,[],[(0,1,2,3),(3,2,1,0)]);me.materials.append(glass)
o=bpy.data.objects.new('Roadster | Windshield',me);car.objects.link(o)
for a,b in [(0,3),(1,2),(2,3)]:tube('Roadster | Windshield frame',verts[a],verts[b],.024,black)
for x in [-.61,.61]:
 sphere('Roadster | Headlight',(x,-1.68,.65),(.17,.28,.035),lamp)
 sphere('Roadster | Tail light',(x,1.83,.59),(.16,.03,.07),red)
cube('Roadster | Front grille',(0,-1.97,.37),(.89,.025,.10),black,.025)
cube('Roadster | Rear diffuser',(0,1.93,.33),(1.15,.07,.14),black)
# Starman: helmet, wraparound dark visor, suited torso, gloves, seated legs.
sphere('Starman | Torso',(-.4,.17,1.02),(.205,.145,.27),suit)
sphere('Starman | Helmet',(-.4,.16,1.33),(.16,.145,.17),suit)
sphere('Starman | Black wraparound visor',(-.4,.039,1.34),(.14,.045,.098),glass)
for x in [-.51,-.28]:
 tube('Starman | Thigh',(x,.18,.80),(x,-.13,.79),.077,suit)
 tube('Starman | Lower leg',(x,-.13,.79),(x,-.42,.57),.063,suit)
 sphere('Starman | Boot',(x,-.47,.55),(.072,.13,.05),black)
for shoulder,elbow,hand in [((-.56,.17,1.12),(-.62,-.04,1.00),(-.54,-.3,1.02)),((-.23,.17,1.12),(-.14,-.02,1.01),(-.3,-.3,1.02))]:
 tube('Starman | Upper sleeve',shoulder,elbow,.058,suit);tube('Starman | Forearm',elbow,hand,.047,suit)
 sphere('Starman | Glove',hand,(.055,.058,.045),suit)
# Small steering ring, placard; avoid text triangles at flyby distance.
bpy.ops.mesh.primitive_torus_add(major_segments=24,minor_segments=8,major_radius=.145,minor_radius=.017,location=(-.4,-.32,1.00),rotation=(math.pi/2-.3,0,0))
move(bpy.context.object,'Roadster | Steering wheel',black)
cube('Roadster | Dashboard placard',(.34,-.51,1.01),(.17,.02,.08),lamp,.006)
# A discreet payload mount under the car, as in the Falcon Heavy payload.
cube('Roadster | Payload adapter',(0,.07,.10),(.95,1.28,.12),metal,.035)
for x in [-.39,.39]:tube('Roadster | Adapter strut',(x,-.46,.12),(x,.58,.12),.035,metal)
# Save editable source before joining export copies; no source destruction.
scene['provenance']='ISS adapted from NASA ISS B / Michael D. Carbajal; Roadster and Starman authored procedurally. Flyby display distances are staged.'
scene['units']='meters; ISS array span 109m; Roadster body length 3.95m'
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'art/blender/orbital_visitors.blend'))
result={'source':bpy.data.filepath,'iss_triangles':sum(len(o.data.loop_triangles) for o in iss.objects if o.type=='MESH'),'car_components':len(car.objects),'iss_components':len(iss.objects)}
