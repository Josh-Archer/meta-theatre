"""Create a separate Blender lighting reference from existing Earth geometry.
Run with earth_dynamic.blend loaded. Does not replace runtime geometry or the old source.
"""
import bpy, math
from pathlib import Path
from mathutils import Vector
ROOT=Path(__file__).resolve().parents[2]
scene=bpy.data.scenes.new('EARTH | Detailed orbital lighting');bpy.context.window.scene=scene
scene.render.engine='CYCLES';scene.cycles.samples=32;scene.cycles.use_denoising=True
scene.render.resolution_x=1200;scene.render.resolution_y=1200;scene.render.resolution_percentage=100
scene.view_settings.view_transform='Standard';scene.view_settings.look='None'
scene.world=bpy.data.worlds.new('Detailed Earth | dark space');scene.world.use_nodes=True
scene.world.node_tree.nodes['Background'].inputs['Color'].default_value=(.0002,.0004,.0008,1)
scene.world.node_tree.nodes['Background'].inputs['Strength'].default_value=.08
sunDirection=Vector(globals().get("PREVIEW_SUN",(-.55,-.8,.28))).normalized()
images=[]
def texture(nodes,name,noncolor=False):
    image=bpy.data.images.load(str(ROOT/'app/src/main/assets/earth'/name),check_existing=False)
    if noncolor:image.colorspace_settings.name='Non-Color'
    images.append(image)
    node=nodes.new('ShaderNodeTexImage');node.image=image;node.interpolation='Linear'
    return node
objects={}
for part in ['Surface','Clouds','Atmosphere']:
    original=bpy.data.objects['Earth_Dynamic_'+part]
    ob=original.copy();ob.data=original.data.copy();ob.data.materials.clear();scene.collection.objects.link(ob)
    ob.name='Detailed Earth | '+part;objects[part]=ob
    mat=bpy.data.materials.new('Detailed Earth | '+part);mat.use_nodes=True
    nodes=mat.node_tree.nodes;nodes.clear();links=mat.node_tree.links
    output=nodes.new('ShaderNodeOutputMaterial');output.location=(800,0)
    if part=='Surface':
        bs=nodes.new('ShaderNodeBsdfPrincipled');bs.inputs['Roughness'].default_value=.85
        day=texture(nodes,'day.jpg');night=texture(nodes,'night.jpg');normal=texture(nodes,'normal.jpg',True);ocean=texture(nodes,'ocean.jpg',True)
        links.new(day.outputs['Color'],bs.inputs['Base Color'])
        nm=nodes.new('ShaderNodeNormalMap');nm.inputs['Strength'].default_value=.28
        links.new(normal.outputs['Color'],nm.inputs['Color']);links.new(nm.outputs[0],bs.inputs['Normal'])
        rough=nodes.new('ShaderNodeMapRange');rough.inputs['From Min'].default_value=0;rough.inputs['From Max'].default_value=1
        rough.inputs['To Min'].default_value=.88;rough.inputs['To Max'].default_value=.25
        links.new(ocean.outputs['Color'],rough.inputs[0]);links.new(rough.outputs[0],bs.inputs['Roughness'])
        geo=nodes.new('ShaderNodeNewGeometry');dot=nodes.new('ShaderNodeVectorMath');dot.operation='DOT_PRODUCT';dot.inputs[1].default_value=sunDirection
        links.new(geo.outputs['Normal'],dot.inputs[0])
        nightWeight=nodes.new('ShaderNodeMapRange');nightWeight.clamp=True;nightWeight.interpolation_type='SMOOTHSTEP'
        nightWeight.inputs['From Min'].default_value=-.12;nightWeight.inputs['From Max'].default_value=.02
        nightWeight.inputs['To Min'].default_value=2.8;nightWeight.inputs['To Max'].default_value=0
        links.new(dot.outputs['Value'],nightWeight.inputs[0]);links.new(nightWeight.outputs[0],bs.inputs['Emission Strength'])
        links.new(night.outputs['Color'],bs.inputs['Emission Color']);links.new(bs.outputs[0],output.inputs[0])
    elif part=='Clouds':
        cloud=texture(nodes,'clouds.png',True)
        bump=nodes.new('ShaderNodeBump');bump.inputs['Strength'].default_value=.75;bump.inputs['Distance'].default_value=.002
        links.new(cloud.outputs['Alpha'],bump.inputs['Height'])
        bs=nodes.new('ShaderNodeBsdfPrincipled');bs.inputs['Base Color'].default_value=(1,1,1,1);bs.inputs['Roughness'].default_value=1
        links.new(bump.outputs[0],bs.inputs['Normal'])
        optical=nodes.new('ShaderNodeMath');optical.operation='MULTIPLY';optical.inputs[1].default_value=-2.5;links.new(cloud.outputs['Alpha'],optical.inputs[0])
        exponent=nodes.new('ShaderNodeMath');exponent.operation='EXPONENT';links.new(optical.outputs[0],exponent.inputs[0])
        inverse=nodes.new('ShaderNodeMath');inverse.operation='SUBTRACT';inverse.inputs[0].default_value=1;links.new(exponent.outputs[0],inverse.inputs[1])
        mix=nodes.new('ShaderNodeMixShader');transparent=nodes.new('ShaderNodeBsdfTransparent')
        links.new(inverse.outputs[0],mix.inputs[0]);links.new(transparent.outputs[0],mix.inputs[1]);links.new(bs.outputs[0],mix.inputs[2]);links.new(mix.outputs[0],output.inputs[0])
    else:
        geo=nodes.new('ShaderNodeLayerWeight');power=nodes.new('ShaderNodeMath');power.operation='POWER';power.inputs[1].default_value=4
        inverse=nodes.new('ShaderNodeMath');inverse.operation='SUBTRACT';inverse.inputs[0].default_value=1;links.new(geo.outputs['Facing'],inverse.inputs[1])
        links.new(geo.outputs['Facing'],power.inputs[0]);scale=nodes.new('ShaderNodeMath');scale.operation='MULTIPLY';scale.inputs[1].default_value=.3
        links.new(power.outputs[0],scale.inputs[0]);emission=nodes.new('ShaderNodeEmission');emission.inputs[0].default_value=(.045,.19,.48,1)
        mix=nodes.new('ShaderNodeMixShader');transparent=nodes.new('ShaderNodeBsdfTransparent')
        links.new(scale.outputs[0],mix.inputs[0]);links.new(transparent.outputs[0],mix.inputs[1]);links.new(emission.outputs[0],mix.inputs[2]);links.new(mix.outputs[0],output.inputs[0])
        ob.visible_shadow=False
    ob.data.materials.append(mat)
light=bpy.data.lights.new('Detailed Earth | Sun','SUN');light.energy=2;light.angle=.0093
sun=bpy.data.objects.new(light.name,light);scene.collection.objects.link(sun)
sun.rotation_euler=(-sunDirection).to_track_quat('-Z','Y').to_euler()
camera=bpy.data.objects.new('Detailed Earth | reference camera',bpy.data.cameras.new('Detailed Earth | reference camera'));scene.collection.objects.link(camera)
camera.location=(0,-3.4,.15);camera.rotation_euler=(-camera.location).to_track_quat('-Z','Y').to_euler();camera.data.type='ORTHO';camera.data.ortho_scale=2.35;scene.camera=camera
scene['reference_only']='Cycles authoring reference; native app uses custom shaders, three draws and live weather density.'
scene.render.filepath=str(ROOT/'art/renders/earth_detailed'/(globals().get('PREVIEW_NAME','daylight')+'.png'));Path(scene.render.filepath).parent.mkdir(parents=True,exist_ok=True)
bpy.ops.render.render(write_still=True)
scene.render.filepath='//../renders/earth_detailed/'+globals().get('PREVIEW_NAME','daylight')+'.png'
for image in images:
    image.filepath='//../../app/src/main/assets/earth/'+Path(image.filepath).name
if globals().get('SAVE_SOURCE',True):
    bpy.data.libraries.write(str(ROOT/'art/blender/earth_detailed.blend'),{scene},path_remap='RELATIVE_ALL',fake_user=True,compress=True)
result={'source_saved':bool(globals().get('SAVE_SOURCE',True)),'render_saved':True,'preview_only':True}
