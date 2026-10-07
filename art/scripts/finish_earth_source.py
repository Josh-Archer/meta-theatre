"""Add the editable 8K lighting rig, then preserve a packed final Blender source."""
import bpy, math
from pathlib import Path
from mathutils import Vector
ROOT=(Path(__file__).resolve().parents[2] if '__file__' in globals() else Path(bpy.path.abspath('//')).resolve().parents[1])
if not (ROOT / "app/src/main/assets").is_dir():
    raise RuntimeError("Execute this script with __file__ set to its path in this project.")
s=bpy.data.scenes['EARTH | Quest asset studio']; bpy.context.window.scene=s
source=bpy.data.collections['SOURCE | 8K PBR - enable to edit']
source.hide_viewport=False
surface=next(o for o in source.objects if o.name.startswith('Earth_Source_8K'))
# Copy the source mesh so cloud UVs and its geographic orientation stay aligned.
cloud=surface.copy(); cloud.data=surface.data.copy(); cloud.name='Earth_Source_8K_CloudDeck'; source.objects.link(cloud)
cloud.scale=(1.0035,)*3; cloud.data.materials.clear()
m=bpy.data.materials.new('Earth_Source | 8K translucent cloud deck'); m.use_nodes=True
n=m.node_tree.nodes; l=m.node_tree.links; bs=n.get('Principled BSDF')
bs.inputs['Base Color'].default_value=(.94,.97,1,1); bs.inputs['Roughness'].default_value=1
im=n.new('ShaderNodeTexImage'); im.image=bpy.data.images.load(str(ROOT/'art/textures/earth/source/8k_earth_clouds.jpg'),check_existing=True); im.image.colorspace_settings.name='Non-Color'; im.location=(-350,0)
l.new(im.outputs['Color'],bs.inputs['Alpha']); m.surface_render_method='DITHERED'; m.use_backface_culling=True
cloud.data.materials.append(m)
# This desktop source atmosphere uses view-dependent Fresnel, unlike the baked mobile rim.
a=surface.copy(); a.data=surface.data.copy(); a.name='Earth_Source_Fresnel_Atmosphere'; source.objects.link(a); a.scale=(1.012,)*3; a.data.materials.clear()
m=bpy.data.materials.new('Earth_Source | editable Fresnel atmosphere'); m.use_nodes=True
n=m.node_tree.nodes; n.clear(); l=m.node_tree.links
out=n.new('ShaderNodeOutputMaterial'); out.location=(500,0)
f=n.new('ShaderNodeLayerWeight'); f.location=(-450,0); f.inputs['Blend'].default_value=.2
p=n.new('ShaderNodeMath'); p.operation='POWER'; p.inputs[1].default_value=3; p.location=(-230,0); l.new(f.outputs['Facing'],p.inputs[0])
t=n.new('ShaderNodeBsdfTransparent'); t.location=(-20,-100)
e=n.new('ShaderNodeEmission'); e.location=(-20,100); e.inputs['Color'].default_value=(.008,.065,.22,1)
mix=n.new('ShaderNodeMixShader'); mix.location=(250,0); l.new(p.outputs[0],mix.inputs[0]); l.new(t.outputs[0],mix.inputs[1]); l.new(e.outputs[0],mix.inputs[2]); l.new(mix.outputs[0],out.inputs[0])
m.surface_render_method='BLENDED'; m.use_backface_culling=True; m.use_transparency_overlap=False; a.data.materials.append(m)
# Sun and emission mask share the same initial direction. The mask is labelled in the surface material.
surface_mat=surface.data.materials[0]
dot=next(n for n in surface_mat.node_tree.nodes if n.type=='VECT_MATH' and n.operation=='DOT_PRODUCT')
sun_dir=Vector(dot.inputs[1].default_value).normalized()
ld=bpy.data.lights.new('Earth_Source_Sun','SUN'); ld.energy=2.5; ld.angle=math.radians(.53)
sun=bpy.data.objects.new('Earth_Source_Sun',ld); source.objects.link(sun)
sun.rotation_euler=(-sun_dir).to_track_quat('-Z','Y').to_euler()
sun['editing_note']='After rotating, update labelled sun direction vector in Earth_Source surface material to match. Runtime maps are baked by build_earth.py.'
source.hide_render=True; source.hide_viewport=True
for phase in ['day','twilight','night']:
 c=bpy.data.collections['EARTH_'+phase.upper()]; c.hide_render=phase!='twilight'; c.hide_viewport=phase!='twilight'
bpy.ops.object.select_all(action='DESELECT')
surface_runtime=next(o for o in bpy.data.collections['EARTH_TWILIGHT'].objects if o.name.endswith('_Surface'))
surface_runtime.select_set(True); bpy.context.view_layer.objects.active=surface_runtime
s.camera.data.type='ORTHO'; s.camera.data.ortho_scale=2.35; s.camera.location=(0,-3.4,0)
for script in ['build_earth.py','finish_earth_source.py']:
 text=bpy.data.texts.new(script); text.write((ROOT/'art/scripts'/script).read_text())
readme=bpy.data.texts.new('EARTH - READ ME')
readme.write('Meta Theatre Earth\n\nDefault: Quest twilight preview (actual exported material graph).\nEnable exactly one EARTH_DAY / EARTH_TWILIGHT / EARTH_NIGHT collection.\nFor editable 8K PBR: hide all EARTH phase collections, enable SOURCE collection for viewport and render.\nThe source Sun and labelled surface-material sun vector start aligned. Update both when relighting.\nRuntime uses baked surface / city lights plus a 2K cloud deck and vertex-alpha rim.\nThe runtime atmosphere is authored for the theatre-facing hemisphere, not free-orbit viewing.\nExport only the three objects in one runtime phase with active-scene filtering.\nDo not export the source rig or review camera to the Android app.\nTextures: Solar System Scope / INOVE, CC BY 4.0, adapted from NASA data.\nFull attribution, runtime budgets and validation: art/EARTH.md.\n')
bpy.ops.file.make_paths_relative(); bpy.ops.file.pack_all()
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'art/blender/earth.blend'))
result={'file':str(ROOT/'art/blender/earth.blend'),'packed_images':sum(bool(i.packed_file) for i in bpy.data.images),'source_objects':[o.name for o in source.objects]}
