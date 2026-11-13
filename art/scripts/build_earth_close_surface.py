"""Run in the live project Blender scene via MCP; preserve the original globe and UV layout."""
import bpy, json
from pathlib import Path
root = Path('/home/gawly/code/meta-theatre')
backup = root / 'art/blender/before_mountain_zoom_20261021.blend'
if not backup.exists():
    bpy.ops.wm.save_as_mainfile(filepath=str(backup), copy=True)
source = bpy.data.objects['Earth_Dynamic_Surface']
collection = bpy.data.collections.new('EARTH_CLOSE_DETAIL')
bpy.context.scene.collection.children.link(collection)
obj = source.copy()
obj.data = source.data.copy()
obj.name = 'Earth_Close_Surface'
collection.objects.link(obj)
bpy.ops.object.select_all(action='DESELECT')
obj.select_set(True)
bpy.context.view_layer.objects.active = obj
modifier = obj.modifiers.new('Close orbital curvature', 'SUBSURF')
modifier.subdivision_type = 'SIMPLE'
modifier.levels = 2
bpy.ops.object.modifier_apply(modifier=modifier.name)
for vertex in obj.data.vertices:
    vertex.co.normalize()
for polygon in obj.data.polygons:
    polygon.use_smooth = True
obj['purpose'] = 'Close-view satellite and elevation-normal overlay; mountains are shaded from streamed DEM normals'
obj['source'] = 'Earth_Dynamic_Surface, simple subdivision 2, normalized radius, inherited UVs'
obj['runtime_material'] = 'earth_detail custom shader'
obj.data.calc_loop_triangles()
export = root / 'app/src/main/assets/earth_dynamic_surface_detail.glb'
bpy.ops.export_scene.gltf(filepath=str(export), export_format='GLB', use_selection=True, use_active_scene=True,
    export_animations=False, export_extras=True)
bpy.ops.wm.save_as_mainfile(filepath=str(root/'art/blender/earth_close_detail.blend'), copy=True)
print(json.dumps({'asset':str(export),'vertices':len(obj.data.vertices),'triangles':len(obj.data.loop_triangles),
    'radius_min':min(v.co.length for v in obj.data.vertices),'radius_max':max(v.co.length for v in obj.data.vertices)}))
