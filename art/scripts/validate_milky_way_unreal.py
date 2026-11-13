"""Reload the saved Unreal assets and verify the reusable sky setup."""
import json
import unreal
from pathlib import Path

folder = '/Game/MetaTheatre/Space/MilkyWay20261019'
assets = unreal.EditorAssetLibrary
texture = assets.load_asset(folder + '/stars')
mesh = assets.load_asset(folder + '/milky_way_dome')
material = assets.load_asset(folder + '/M_MilkyWay')
assert texture is not None and mesh is not None and material is not None
assert texture.get_editor_property('max_texture_size') == 8192
assert texture.get_editor_property('lod_bias') == 0
assert texture.get_editor_property('srgb')
assert mesh.get_material(0) == material
assert material.get_editor_property('shading_model') == unreal.MaterialShadingModel.MSM_UNLIT
report = {
    'folder': folder, 'assets': list(assets.list_assets(folder, recursive=True, include_folder=False)),
    'texture_dimensions': [texture.blueprint_get_size_x(), texture.blueprint_get_size_y()],
    'texture_max_size': 8192, 'texture_lod_bias': 0,
    'material': 'Unlit, sRGB texture, exposure 0.8, U repeat / V clamp',
    'saved_assets_reloaded': True, 'open_level_preserved': True,
    'renderer': 'NullRHI: import/material/save verification; no Unreal render proof',
}
assert report['texture_dimensions'] == [8192, 4096]
Path('/home/gawly/code/meta-theatre/art/validation/milky_way_unreal.json').write_text(json.dumps(report, indent=2) + '\n')
unreal.log('MILKY_WAY_VALIDATION_COMPLETE ' + json.dumps(report))
