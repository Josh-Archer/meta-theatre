"""Run in Blender MCP to extract city-light-only emission in linear space."""
import bpy, numpy as np
from pathlib import Path
ROOT=(Path(__file__).resolve().parents[2] if '__file__' in globals() else Path(bpy.path.abspath('//')).resolve().parents[1])
if not (ROOT / "app/src/main/assets").is_dir():
    raise RuntimeError("Execute this script with __file__ set to its path in this project.")
OUT=ROOT/'art/textures/earth/dynamic'
im=bpy.data.images.load(str(OUT/'night_raw.png'),check_existing=True)
a=np.empty(4096*2048*4,np.float32); im.pixels.foreach_get(a); a=a.reshape(2048,4096,4)
rgb=a[:,:,:3]
lin=np.where(rgb<=.04045,rgb/12.92,((rgb+.055)/1.055)**2.4)
lin=np.maximum(lin-np.array([.008,.01,.018],np.float32),0)
encoded=np.where(lin<=.0031308,lin*12.92,1.055*lin**(1/2.4)-.055)
a[:,:,:3]=encoded; a[:,:,3]=1
out=bpy.data.images.new('Earth_Dynamic_CityLights_4K',width=4096,height=2048,alpha=False)
out.colorspace_settings.name='sRGB'; out.pixels.foreach_set(a.ravel()); out.update()
out.filepath_raw=str(OUT/'night.png'); out.file_format='PNG'; out.save()
# Remove stale baked-phase metadata inherited by the geometry copies.
for part in ['surface','clouds','atmosphere']:
    ob=bpy.data.objects['Earth_Dynamic_'+part.title()]
    for key in ['phase','lighting']:
        if key in ob: del ob[key]
    ob['lighting']='Continuous runtime sun; no baked phase'
    bpy.ops.object.select_all(action='DESELECT'); ob.select_set(True); bpy.context.view_layer.objects.active=ob
    bpy.ops.export_scene.gltf(filepath=str(ROOT/'art/exports/earth_dynamic'/('earth_dynamic_'+part+'.glb')),export_format='GLB',use_selection=True,use_active_scene=True,export_animations=False,export_extras=True)
result={'city_map':str(OUT/'night.png'),'black_background_removed_in_linear_space':True}
