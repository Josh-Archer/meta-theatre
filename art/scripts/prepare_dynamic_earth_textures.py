"""Prepare unlit source maps for the live Earth shader (Python + Pillow).
City emission extraction is performed in Blender by finish_dynamic_earth_textures.py.
"""
from pathlib import Path
from PIL import Image
ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'art/textures/earth/source'
OUT=ROOT/'art/textures/earth/dynamic'; OUT.mkdir(parents=True,exist_ok=True)
for src,dst,size in [('8k_earth_daymap.jpg','day.jpg',(4096,2048)),('8k_earth_normal_map.tif','normal.jpg',(4096,2048)),('8k_earth_specular_map.tif','ocean.jpg',(2048,1024)),('8k_earth_nightmap.jpg','night_raw.png',(4096,2048))]:
    with Image.open(SOURCE/src) as im:
        im=im.convert('RGB').resize(size,Image.Resampling.LANCZOS)
        if dst.endswith('.jpg'): im.save(OUT/dst,quality=96,subsampling=0,optimize=True)
        else: im.save(OUT/dst)
with Image.open(SOURCE/'8k_earth_clouds.jpg') as im:
    alpha=im.convert('L').resize((2048,1024),Image.Resampling.LANCZOS)
    alpha=alpha.point([round(min(.96,(v/255)**1.18*.93)*255) for v in range(256)])
    cloud=Image.new('RGBA',alpha.size,(255,255,255,0)); cloud.putalpha(alpha); cloud.save(OUT/'clouds.png')
print('Prepared 4K day/normal, 2K ocean/clouds, intermediate night map')
