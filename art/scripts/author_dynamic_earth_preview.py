"""Live Blender preview of the app's day/night equations, controlled by scene['local_hour'].
This is an authoring reference, not proof of the headset renderer.
"""
import bpy, math
from pathlib import Path
ROOT=(Path(__file__).resolve().parents[2] if '__file__' in globals() else Path(bpy.path.abspath('//')).resolve().parents[1])
if not (ROOT / "app/src/main/assets").is_dir():
    raise RuntimeError("Execute this script with __file__ set to its path in this project.")
s=bpy.data.scenes['EARTH | Local time authoring']; bpy.context.window.scene=s
s['local_hour']=12.0
s.id_properties_ui('local_hour').update(min=0,max=24,description='Headset-local artistic solar hour; noon centered on equator/100W')
TEX=ROOT/'art/textures/earth/dynamic'
class Graph:
    def __init__(self,name):
        self.mat=bpy.data.materials.new(name); self.mat.use_nodes=True
        self.n=self.mat.node_tree.nodes; self.n.clear(); self.l=self.mat.node_tree.links
        self.out=self.n.new('ShaderNodeOutputMaterial'); self.out.location=(1000,0)
        self.geo=self.n.new('ShaderNodeNewGeometry')
        self.geo.location=(-1400,700)
        sun=self.n.new('ShaderNodeCombineXYZ'); sun.name='Sun from local time'; sun.location=(-1400,450)
        for i,expression in [(0,'-sin((hour-12)*pi/12)'),(1,'-cos((hour-12)*pi/12)')]:
            drv=sun.inputs[i].driver_add('default_value').driver
            drv.expression=expression
            v=drv.variables.new(); v.name='hour'; v.type='SINGLE_PROP'; v.targets[0].id_type='SCENE'; v.targets[0].id=s; v.targets[0].data_path='["local_hour"]'
        self.sun=sun.outputs[0]; self.normal=self.geo.outputs['Normal']; self.view=self.geo.outputs['Incoming']
        self.elevation=self.vec('DOT_PRODUCT',self.normal,self.sun,value=True)
        self.day=self.smooth(self.elevation,-.08,.08)
        ndv=self.math('MINIMUM',1,self.math('MAXIMUM',self.vec('DOT_PRODUCT',self.normal,self.view,value=True),0))
        self.rim=self.math('POWER',self.math('SUBTRACT',1,ndv),3.5)
    def feed(self,value,socket):
        if hasattr(value,'is_output'): self.l.new(value,socket)
        else: socket.default_value=value
    def math(self,op,a,b=0):
        n=self.n.new('ShaderNodeMath'); n.operation=op; self.feed(a,n.inputs[0]); self.feed(b,n.inputs[1]); return n.outputs[0]
    def vec(self,op,a,b=None,value=False):
        n=self.n.new('ShaderNodeVectorMath'); n.operation=op; self.feed(a,n.inputs[0])
        if b is not None: self.feed(b,n.inputs['Scale'] if op=='SCALE' else n.inputs[1])
        return n.outputs['Value'] if value else n.outputs['Vector']
    def smooth(self,a,lo,hi):
        n=self.n.new('ShaderNodeMapRange'); n.interpolation_type='SMOOTHSTEP'; n.clamp=True
        self.feed(a,n.inputs[0]); n.inputs['From Min'].default_value=lo; n.inputs['From Max'].default_value=hi
        return n.outputs[0]
    def tex(self,name,noncolor=False):
        n=self.n.new('ShaderNodeTexImage'); n.label=name; n.image=bpy.data.images.load(str(TEX/name),check_existing=True)
        if noncolor: n.image.colorspace_settings.name='Non-Color'
        return n
    def output(self,color,alpha=None):
        if alpha is None: self.l.new(color,self.out.inputs[0])
        else:
            tr=self.n.new('ShaderNodeBsdfTransparent'); mix=self.n.new('ShaderNodeMixShader'); self.feed(alpha,mix.inputs[0])
            self.l.new(tr.outputs[0],mix.inputs[1]); self.l.new(color,mix.inputs[2]); self.l.new(mix.outputs[0],self.out.inputs[0])
            self.mat.surface_render_method='BLENDED'; self.mat.use_transparency_overlap=False
        self.mat.use_backface_culling=True
    def finish(self,part):
        ob=bpy.data.objects['Earth_Dynamic_'+part.title()]; ob.data.materials.clear(); ob.data.materials.append(self.mat)
        # Readable columns with the controls kept at the top.
        for i,node in enumerate(self.n):
            if node==self.out or node==self.geo or node.name=='Sun from local time': continue
            node.location=(-1000+(i//7)*260,250-(i%7)*200)

# Surface: static geography, per-pixel moving sun, civil twilight and city emission.
g=Graph('Earth Dynamic | surface - local time')
day=g.tex('day.jpg'); city=g.tex('night.jpg'); normal=g.tex('normal.jpg',True); ocean=g.tex('ocean.jpg',True)
nm=g.n.new('ShaderNodeNormalMap'); nm.inputs['Strength'].default_value=.28; g.l.new(normal.outputs['Color'],nm.inputs['Color'])
diffuse=g.math('ADD',.08,g.math('MULTIPLY',.92,g.math('MAXIMUM',g.vec('DOT_PRODUCT',nm.outputs[0],g.sun,value=True),0)))
shade=g.math('ADD',.002,g.math('MULTIPLY',g.day,diffuse))
base=g.vec('SCALE',day.outputs['Color'],shade)
night=g.math('MULTIPLY',2.8,g.math('SUBTRACT',1,g.smooth(g.elevation,-.12,.02)))
color=g.vec('ADD',base,g.vec('SCALE',city.outputs['Color'],night))
half=g.vec('NORMALIZE',g.vec('ADD',g.sun,g.view))
spec=g.math('MULTIPLY',g.day,g.math('MULTIPLY',.42,g.math('MULTIPLY',ocean.outputs['Color'],g.math('POWER',g.math('MAXIMUM',g.vec('DOT_PRODUCT',g.normal,half,value=True),0),72))))
color=g.vec('ADD',color,g.vec('SCALE',(1,1,1),spec))
band=g.math('EXPONENT',g.math('MULTIPLY',-22,g.math('ABSOLUTE',g.elevation)))
color=g.vec('ADD',color,g.vec('SCALE',(.075,.018,.004),g.math('MULTIPLY',band,g.math('POWER',g.math('SUBTRACT',1,g.math('MINIMUM',1,g.math('MAXIMUM',g.vec('DOT_PRODUCT',g.normal,g.view,value=True),0))),2))))
g.output(color); g.finish('surface')
# Clouds receive the same moving sunlight.
g=Graph('Earth Dynamic | clouds - local time'); cloud=g.tex('clouds.png',True)
shade=g.math('ADD',.008,g.math('MULTIPLY',g.day,g.math('ADD',.16,g.math('MULTIPLY',.84,g.math('MAXIMUM',g.elevation,0)))))
cloud_color=g.vec('ADD',g.vec('SCALE',(1,1,1),shade),g.vec('SCALE',(.085,.025,.005),g.math('EXPONENT',g.math('MULTIPLY',-22,g.math('ABSOLUTE',g.elevation)))))
g.output(cloud_color,g.math('MULTIPLY',.68,cloud.outputs['Alpha'])); g.finish('clouds')
# Atmosphere is view-dependent, without baked vertex colors.
g=Graph('Earth Dynamic | atmosphere - local time')
band=g.math('MULTIPLY',.7,g.math('EXPONENT',g.math('MULTIPLY',-18,g.math('ABSOLUTE',g.elevation))))
blue=g.vec('SCALE',(.065,.28,.65),g.math('SUBTRACT',1,band))
orange=g.vec('SCALE',(.58,.095,.018),band)
alpha=g.math('MULTIPLY',g.rim,g.math('MULTIPLY',.42,g.math('ADD',.12,g.math('MULTIPLY',.88,g.day))))
g.output(g.vec('ADD',blue,orange),alpha); g.finish('atmosphere')
for area in bpy.context.screen.areas:
    if area.type=='VIEW_3D':
        area.spaces.active.region_3d.view_perspective='CAMERA'; area.spaces.active.shading.type='MATERIAL'; area.spaces.active.shading.use_scene_world=True
for name in ['build_dynamic_earth_geometry.py','prepare_dynamic_earth_textures.py','finish_dynamic_earth_textures.py','author_dynamic_earth_preview.py']:
    txt=bpy.data.texts.new(name); txt.write((ROOT/'art/scripts'/name).read_text())
bpy.ops.file.make_paths_relative(); bpy.ops.file.pack_all(); bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'art/blender/earth_dynamic.blend'))
result={'file':bpy.data.filepath,'time_control':'Scene custom property local_hour, 0-24','center':'0 N, 100 W'}
