"""Original Starship-inspired flight exhibit. Versioned Blender source, native GLBs.
Reference proportions: 9 m diameter, 50 m ship / 71 m booster (early flight-test
configuration). Not a CAD reconstruction or a prediction of an actual mission.
Run through the live Blender bridge with __file__ set to this script.
"""
import bpy, math, json, numpy as np
from pathlib import Path
from mathutils import Vector
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'art/exports/starship_20261113';OUT.mkdir(parents=True,exist_ok=True)
TEX=ROOT/'art/textures/starship';TEX.mkdir(parents=True,exist_ok=True)
scene=bpy.data.scenes.new('STARSHIP | Flight exhibit 20261113');bpy.context.window.scene=scene
scene.unit_settings.system='METRIC'

def material(name,color,metal,rough):
 m=bpy.data.materials.new(name);m.use_nodes=True;m.diffuse_color=(*color,1)
 p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Base Color'].default_value=(*color,1);p.inputs['Metallic'].default_value=metal;p.inputs['Roughness'].default_value=rough
 return m
steel=material('Starship | Brushed stainless',(.48,.50,.52),.88,.38)
tiles=material('Starship | Ceramic heat shield',(.018,.021,.025),0,.83)
engine=material('Starship | Engine alloy',(.12,.105,.09),.8,.48)
dark=material('Starship | Dark structure',(.028,.035,.043),.65,.5)
concrete=material('Starship | Launch deck',(.13,.15,.16),0,.85)
# A small repeating physical tile, not a low-resolution atlas stretched over the
# whole ship. Geometry carries ring seams, flaps, engine bells and the silhouette.
n=1024;y,x=np.mgrid[:n,:n].astype(np.float32)/n
rng=np.random.default_rng(13)
# Two staggered hexagonal cells in a seamless rectangle.
px=x*3;py=y*math.sqrt(3)
dist=np.full_like(x,99)
for iy in range(-1,3):
 for ix in range(-1,4):
  cx=ix*1.5;cy=iy*math.sqrt(3)+(ix%2)*math.sqrt(3)/2
  dx=np.abs(px-cx);dy=np.abs(py-cy)
  d=np.maximum(dy/(math.sqrt(3)/2),dx+dy/math.sqrt(3))
  dist=np.minimum(dist,d)
gap=np.clip((dist-.96)/.04,0,1)
height=1-gap
noise=rng.normal(0,.004,(n,n)).astype(np.float32)

def save_map(name,values):
 im=bpy.data.images.new(name,width=n,height=n);im.colorspace_settings.name='Non-Color'
 rgba=np.ones((n,n,4),np.float32);rgba[:,:,:3]=values;im.pixels.foreach_set(rgba.ravel())
 im.filepath_raw=str(TEX/(name+'.png'));im.file_format='PNG';im.save();im.pack();return im
v=np.stack((-(np.roll(height,-1,1)-np.roll(height,1,1))*3.0,-(np.roll(height,-1,0)-np.roll(height,1,0))*3.0,np.ones_like(x)),axis=2)
v=v/np.linalg.norm(v,axis=2,keepdims=True)
normal=save_map('ceramic_normal',v*.5+.5)
rough=save_map('ceramic_orm',np.stack((1-gap*.22,np.clip(.83+noise+gap*.08,0,1),np.zeros_like(x)),axis=2))
for image,kind in [(normal,'normal'),(rough,'rough')]:
 t=tiles.node_tree;node=t.nodes.new('ShaderNodeTexImage');node.image=image
 p=t.nodes.get('Principled BSDF')
 if kind=='normal':
  nm=t.nodes.new('ShaderNodeNormalMap');t.links.new(node.outputs['Color'],nm.inputs['Color']);t.links.new(nm.outputs['Normal'],p.inputs['Normal'])
 else:
  sep=t.nodes.new('ShaderNodeSeparateColor');t.links.new(node.outputs['Color'],sep.inputs['Color']);t.links.new(sep.outputs['Green'],p.inputs['Roughness']);t.links.new(sep.outputs['Blue'],p.inputs['Metallic'])
# Collection-local builders retain named editable components.
col=None

def mesh(name,verts,faces,mat,smooth=False):
 me=bpy.data.meshes.new(name);me.from_pydata(verts,[],faces);me.update()
 o=bpy.data.objects.new(name,me);col.objects.link(o);me.materials.append(mat)
 for f in me.polygons:f.use_smooth=smooth
 uv=me.uv_layers.new(name='UVMap')
 for f in me.polygons:
  axes=[i for i in range(3) if i!=max(range(3),key=lambda i:abs(f.normal[i]))]
  for li in f.loop_indices:
   co=me.vertices[me.loops[li].vertex_index].co;uv.data[li].uv=(co[axes[0]]/.5,co[axes[1]]/.29)
 return o

def lathe(name,profile,mat,segments=96,tiled=False):
 verts=[]
 for z,r in profile:
  for j in range(segments):
   a=j*2*math.pi/segments;verts.append((r*math.cos(a),r*math.sin(a),z))
 faces=[(i*segments+j,i*segments+(j+1)%segments,(i+1)*segments+(j+1)%segments,(i+1)*segments+j) for i in range(len(profile)-1) for j in range(segments)]
 o=mesh(name,verts,faces,mat,True)
 if tiled:
  o.data.materials.append(tiles)
  for f in o.data.polygons:
   j=f.index%segments;i=f.index//segments
   if math.sin((j+.5)*2*math.pi/segments)<-.03:f.material_index=1
   for k,li in enumerate(f.loop_indices):
    jj=j+(k in [1,2]);ii=i+(k>=2)
    o.data.uv_layers.active.data[li].uv=(jj/segments*56.6,profile[ii][0]/.29)
 return o

def box(name,loc,size,mat,bevel=0):
 bpy.ops.mesh.primitive_cube_add(size=1,location=loc);o=bpy.context.object;o.name=name
 for c in list(o.users_collection):c.objects.unlink(o)
 col.objects.link(o);o.dimensions=size;bpy.ops.object.transform_apply(location=False,rotation=False,scale=True);o.data.materials.append(mat)
 if bevel:
  mod=o.modifiers.new('Machined edge','BEVEL');mod.width=bevel;mod.segments=2
  o.modifiers.new('Face normals','WEIGHTED_NORMAL')
 return o

def ring(name,z,radius,minor,mat):
 bpy.ops.mesh.primitive_torus_add(major_radius=radius,minor_radius=minor,major_segments=96,minor_segments=6,location=(0,0,z));o=bpy.context.object;o.name=name
 for c in list(o.users_collection):c.objects.unlink(o)
 col.objects.link(o);o.data.materials.append(mat)
 for p in o.data.polygons:p.use_smooth=True
 if name == 'Welded tank ring':
  import bmesh
  bm=bmesh.new();bm.from_mesh(o.data)
  bmesh.ops.delete(bm,geom=[f for f in bm.faces if f.calc_center_median().y < 0],context='FACES')
  bm.to_mesh(o.data);bm.free()
 return o

def nozzle(name,x,y,z,r,height):
 # Open bell with a rolled lip, inner lining and narrowed throat.
 profile=[(z+height, r*.24),(z+height*.78,r*.28),(z+height*.50,r*.44),(z+height*.24,r*.73),(z,r),(z-.035,r),(z-.035,r-.06),(z+height*.24,r*.73-.06),(z+height*.50,r*.44-.06),(z+height*.78,r*.28-.05),(z+height,r*.24-.05)]
 o=lathe(name,profile,engine,40);o.location.x=x;o.location.y=y

def export(col,name):
 bpy.ops.object.select_all(action='DESELECT');copies=[];deps=bpy.context.evaluated_depsgraph_get()
 for o in col.all_objects:
  if o.type!='MESH':continue
  me=bpy.data.meshes.new_from_object(o.evaluated_get(deps),depsgraph=deps)
  cp=bpy.data.objects.new(o.name+' | export',me);scene.collection.objects.link(cp);cp.matrix_world=o.matrix_world.copy();copies.append(cp)
 for o in copies:o.select_set(True)
 bpy.context.view_layer.objects.active=copies[0];bpy.ops.object.join();o=bpy.context.object;o.name=name
 # Explicit origin at the engine-base/pad-ground plane, +Z vehicle axis.
 scene.cursor.location=(0,0,0);bpy.ops.object.origin_set(type='ORIGIN_CURSOR')
 triangulate=o.modifiers.new('Export triangles','TRIANGULATE');bpy.ops.object.modifier_apply(modifier=triangulate.name)
 o.data.calc_loop_triangles();tri=len(o.data.loop_triangles)
 bpy.ops.export_scene.gltf(filepath=str(OUT/(name+'.glb')),export_format='GLB',use_selection=True,use_active_scene=True,export_yup=True,export_apply=True,export_normals=True,export_tangents=True,export_materials='EXPORT')
 bpy.data.objects.remove(o,do_unlink=True)
 return {'triangles':tri,'materials':len({s.material.name for ob in col.all_objects for s in ob.material_slots if s.material})}

reports={}
col=bpy.data.collections.new('Starship | Editable upper stage');scene.collection.children.link(col)
profile=[(0,4.5),(2,4.5),(5,4.5),(10,4.5),(15,4.5),(20,4.5),(25,4.5),(30,4.5),(33,4.5)]
for i in range(1,25):
 t=i/24;profile.append((33+17*t,max(.015,4.5*math.cos(t*math.pi/2)**.72)))
lathe('Stainless barrel and ogive',profile,steel,128,True)
# Bottom closure above the six engines and structural lip.
lathe('Aft engine bay',[(2.5,.01),(2.5,4.42),(0,4.42)],dark)
for z in np.arange(2.0,34,1.8):ring('Welded tank ring',float(z),4.505,.018,steel)
ring('Skirt rim',.08,4.5,.065,steel)
for side in [-1,1]:box('Recovery support lug',(0,side*4.55,7.4),(1.3,.65,.45),steel,.1)
for sign in [-1,1]:
 for front in [False,True]:
  z=36 if front else 5
  shape=[(4.1,z),(6.7,z-1.0),(7.5,z+2.0),(5.3,z+8.5),(4.0,z+9.0)] if not front else [(3.8,z),(5.8,z-.7),(6.2,z+1.4),(4.1,z+6),(2.5,z+6.7)]
  verts=[(sign*a,b,c) for b in [-.35,.35] for a,c in shape]
  faces=[tuple(range(4,-1,-1)),tuple(range(5,10))]+[(j,(j+1)%5,(j+1)%5+5,j+5) for j in range(5)]
  ob=mesh(('Forward' if front else 'Aft')+' aerodynamic flap',verts,faces,steel)
  ob.data.materials.append(tiles);ob.data.polygons[0].material_index=1
  mod=ob.modifiers.new('Rounded flap edges','BEVEL');mod.width=.10;mod.segments=3;ob.modifiers.new('Panel normals','WEIGHTED_NORMAL')
  box('Flap actuator fairing',(sign*4.45,0,z+3),(0.50,.85,5),steel,.18)
for i in range(3):
 a=i*2*math.pi/3
 nozzle('Vacuum Raptor',2.65*math.cos(a),2.65*math.sin(a),-.7,1.12,2.8)
 a+=math.pi/3;nozzle('Sea-level Raptor',1.15*math.cos(a),1.15*math.sin(a),-.6,.65,2.3)
reports['starship']=export(col,'starship')
col=bpy.data.collections.new('Super Heavy | Editable booster');scene.collection.children.link(col)
lathe('Booster tank barrel',[(0,4.5),(2,4.5),(65,4.5),(68,4.5),(68,4.3),(65,4.3)],steel,128)
for z in np.arange(2,68,1.8):ring('Booster tank weld',float(z),4.505,.018,steel)
for i in range(4):
 a=i*math.pi/2
 race=box('External raceway',(4.49*math.cos(a),4.49*math.sin(a),36),(.42,.42,57),steel,.08)
 # Recognizable open grid fins with real gaps, rather than an opaque fin card.
 for j in range(8):
  loc=Vector((4.4+j*.38,0,64));loc.rotate(__import__('mathutils').Matrix.Rotation(a,3,'Z'))
  ob=box('Grid fin rib',loc,(.055,3.0,.7),dark,.025);ob.rotation_euler.z=a
 for j in range(9):
  loc=Vector((5.75,-1.5+j*.375,64));loc.rotate(__import__('mathutils').Matrix.Rotation(a,3,'Z'))
  ob=box('Grid fin cross rib',loc,(2.8,.055,.7),dark,.02);ob.rotation_euler.z=a
for j in range(7):
 z=68.3+j*.38;ring('Hot-stage ring rail',z,4.5,.075,steel)
for i in range(33):
 count,rad,index=(20,3.55,i) if i<20 else ((10,2.05,i-20) if i<30 else (3,.8,i-30))
 a=index*2*math.pi/count;nozzle('Booster Raptor',rad*math.cos(a),rad*math.sin(a),-.75,.44,1.65)
reports['super_heavy']=export(col,'super_heavy')
col=bpy.data.collections.new('Launch site | Editable tower and deck');scene.collection.children.link(col)
lathe('Launch deck',[(0,.01),(0,25),(1.2,25),(1.2,.01)],concrete,96)
for x in [-4,4]:
 for y in [-4,4]:box('Launch mount leg',(x,y,4),(1.2,1.2,6),dark,.14)
ring('Launch mount crown',7,5.3,.6,steel)
# Tower at one side; recovery arms cradle the ship near its aft flaps.
for x in [11,16]:
 for y in [-3,3]:box('Tower column',(x,y,64),(1.1,1.1,126),dark,.10)
for z in range(5,130,8):
 box('Tower cross frame',(13.5,0,z),(6.2,7.2,.6),steel,.08)
 for side in [-1,1]:
  ob=box('Tower diagonal',(13.5,side*3,z+4),(.45,.45,9.5),steel,.05);ob.rotation_euler.y=math.radians(34)
for side in [-1,1]:box('Recovery arm',(5.1,side*4.5,85),(14,.8,1.2),dark,.15)
reports['starship_pad']=export(col,'starship_pad')
# Low-cost opaque emissive plume; separate entity, only visible during burns.
col=bpy.data.collections.new('Flight effects | Engine plume');scene.collection.children.link(col)
plume=material('Methalox plume',(.12,.32,.8),0,1)
p=plume.node_tree.nodes.get('Principled BSDF');p.inputs['Emission Color'].default_value=(.3,.5,1,1);p.inputs['Emission Strength'].default_value=2
lathe('Exhaust cone',[(0,.38),(-.18,.46),(-.65,.32),(-1.2,.23),(-1.9,.01)],plume,32)
reports['starship_plume']=export(col,'starship_plume')
for im in [normal,rough]:im.filepath=bpy.path.relpath(im.filepath,start=str(ROOT/'art/blender'))
bpy.data.libraries.write(str(ROOT/'art/blender/starship_20261113.blend'),{scene},fake_user=True,compress=True)
(OUT/'authoring.json').write_text(json.dumps(reports,indent=2)+'\n')
result=reports
