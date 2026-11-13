"""Author staged celestial maps through live Blender; never overwrite source data.

Uses NASA LROC 2025 colour, LOLA elevation and the existing 8K star catalogs.
Run with __file__ set through the project Blender bridge. Publication follows review.
"""
import bpy
import hashlib
import json
import numpy as np
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TAG = 'celestial_20261111'
OUT = ROOT / 'art/textures' / TAG
OUT.mkdir(parents=True, exist_ok=True)


def read(path, data=False, size=None):
    im = bpy.data.images.load(str(path), check_existing=False)
    if data:
        im.colorspace_settings.name = 'Non-Color'
    original_size = tuple(im.size)
    if size:
        im.scale(*size)
    pixels = np.empty(len(im.pixels), np.float32)
    im.pixels.foreach_get(pixels)
    return im, pixels.reshape(im.size[1], im.size[0], 4), original_size


def save(name, rgb, data=False):
    h, w = rgb.shape[:2]
    im = bpy.data.images.new(name, width=w, height=h, alpha=False)
    im.colorspace_settings.name = 'Non-Color' if data else 'Linear Rec.709'
    rgba = np.ones((h, w, 4), np.float32)
    rgba[:, :, :3] = rgb
    im.pixels.foreach_set(rgba.ravel())
    im.file_format = 'PNG'
    im.filepath_raw = str(OUT / (name + '.png'))
    im.save()
    im.pack()
    return im


source = ROOT / 'art/textures/solar/nasa'
color_source, rgba, color_size = read(source / 'lroc_color_16bit_srgb_4k.tif', size=(2048, 1024))
moon = save('moon', rgba[:, :, :3])
del rgba
dem_source, rgba, dem_size = read(source / 'ldem_16.tif', data=True, size=(2048, 1024))
height = rgba[:, :, 0].copy()  # NASA float TIFF: kilometres relative to 1737.4 km.
assert np.isfinite(height).all() and height.min() < -5 and height.max() > 5
del rgba
h, w = height.shape
latitude = ((np.arange(h, dtype=np.float32) + .5) / h - .5) * np.pi
# Blender pixels run south to north. OpenGL tangent +Y is north.
# Bound longitude's metric at the polar sampling footprint, avoiding amplified pole noise.
dx_km = (1737.4 + height) * np.maximum(np.cos(latitude[:, None]), .015) * (2 * np.pi / w)
dy_km = (1737.4 + height) * (np.pi / h)
dx = (np.roll(height, -1, axis=1) - np.roll(height, 1, axis=1)) / (2 * dx_km)
dy = np.gradient(height, axis=0) / dy_km
normal = np.stack((-dx, -dy, np.ones_like(height)), axis=2)
normal /= np.linalg.norm(normal, axis=2, keepdims=True)
moon_normal = save('moon_normal', normal * .5 + .5, data=True)
terrain_stats = {'height_km': [float(height.min()), float(height.max())],
    'slope_degrees_p50_p95_p99': np.percentile(np.degrees(np.arccos(normal[:, :, 2])), [50, 95, 99]).tolist(),
    'relief_scale': 1, 'dimensions': [w, h], 'colour_source_dimensions': color_size,
    'elevation_source_dimensions': dem_size, 'normal_convention': 'OpenGL +Y north; native glTF V is inverted'}
del height, dx_km, dy_km, dx, dy, normal

# Separate bright-star shoulder prevents channel clipping from desaturating stars.
diffuse_source, diffuse, unused = read(ROOT / 'art/textures/space/source/milkyway_2020_8k.exr')
bright_source, bright, unused = read(ROOT / 'art/textures/space/source/hiptyc_2020_8k.exr')
weights = np.array([.2126, .7152, .0722], np.float32)
luma = diffuse[:, :, :3] @ weights
toe = np.maximum(luma - .0012, 0)
gain = 3.15 * toe * toe / (toe + .008) / np.maximum(luma, 1e-8)
rgb = diffuse[:, :, :3] * gain[:, :, None] + bright[:, :, :3] * 1.10
peak = rgb.max(axis=2)
shoulder = .72 + .28 * (-np.expm1(-np.maximum(peak - .72, 0) / .28))
rgb *= np.where(peak > .72, shoulder / np.maximum(peak, 1e-8), 1)[:, :, None]
assert np.isfinite(rgb).all() and rgb.min() >= 0 and rgb.max() <= 1.00001
stars = save('stars', rgb)
sky_stats = {'dimensions': list(stars.size), 'diffuse_exposure': 3.15,
    'toe': .0012, 'toe_softness': .008, 'bright_star_exposure': 1.10,
    'highlight_knee': .72, 'peak_channel_shoulder': True,
    'clipped_pixel_fraction': float((rgb.max(axis=2) >= 1).mean())}
del diffuse, bright, luma, toe, gain, rgb, peak, shoulder

for im in (color_source, dem_source, diffuse_source, bright_source):
    # Keep originals on disk, never embed resized copies as if they were source data.
    bpy.data.images.remove(im)
with bpy.data.libraries.load(str(ROOT / 'art/blender/solar_system.blend')) as (library, target):
    target.scenes = [name for name in library.scenes if name.startswith('SOLAR |')]
scene = next(s for s in target.scenes if s.name.startswith('SOLAR | Editable'))
bpy.context.window.scene = scene
scene.name = 'CELESTIAL | Editable library 20261111'
scene['moon_source'] = 'NASA SVS CGI Moon Kit: LROC 2025 colour and LOLA 16 pixels per degree elevation'
scene['moon_normal'] = json.dumps(terrain_stats)
scene['sky_grade'] = json.dumps(sky_stats)
for material in bpy.data.materials:
    if not material.use_nodes:
        continue
    for node in material.node_tree.nodes:
        if node.type == 'TEX_IMAGE' and node.image:
            if material.name.startswith('Moon |'):
                node.image = moon
            elif material.name.startswith('NASA |'):
                node.image = stars
    if material.name.startswith('Moon |'):
        nodes, links = material.node_tree.nodes, material.node_tree.links
        tex = nodes.new('ShaderNodeTexImage'); tex.image = moon_normal
        nm = nodes.new('ShaderNodeNormalMap')
        links.new(tex.outputs['Color'], nm.inputs['Color'])
        links.new(nm.outputs['Normal'], nodes['Principled BSDF'].inputs['Normal'])
        nodes['Principled BSDF'].inputs['Specular IOR Level'].default_value = 0
        nodes['Principled BSDF'].inputs['Roughness'].default_value = 1
text = bpy.data.texts.new(Path(__file__).name); text.write(Path(__file__).read_text())
for im in (moon, moon_normal, stars):
    im.filepath = bpy.path.relpath(im.filepath, start=str(ROOT / 'art/blender'))
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT / 'art/blender/celestial_20261111.blend'))
report = {'moon': terrain_stats, 'sky': sky_stats,
    'source_hashes': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in source.glob('*.tif')},
    'runtime_extra_rgba8_mips_mib': 2048 * 1024 * 4 * 4 / 3 / 1024**2,
    'extra_draws': 0, 'extra_triangles': 0, 'quest_verified': False}
(ROOT / 'art/validation/celestial-20261111/authoring.json').write_text(json.dumps(report, indent=2) + '\n')
result = report
