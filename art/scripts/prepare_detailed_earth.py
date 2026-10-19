"""Use existing attributed source detail, without enlarging lower-resolution runtime maps."""
from pathlib import Path
from PIL import Image
import shutil
ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'art/textures/earth/source';OUT=ROOT/'art/textures/earth/detailed';OUT.mkdir(parents=True,exist_ok=True)
RUNTIME=ROOT/'app/src/main/assets/earth'
shutil.copyfile(SOURCE/'8k_earth_daymap.jpg',OUT/'day.jpg')
with Image.open(SOURCE/'8k_earth_nightmap.jpg') as image:
    def city_lut(background):
        values=[]
        for value in range(256):
            encoded=value/255
            linear=encoded/12.92 if encoded<=.04045 else ((encoded+.055)/1.055)**2.4
            linear=max(0,linear-background)
            encoded=linear*12.92 if linear<=.0031308 else 1.055*linear**(1/2.4)-.055
            values.append(round(min(1,encoded)*255))
        return values
    luts=[city_lut(background) for background in [.008,.01,.018]]
    result=Image.new('RGB',image.size)
    for y in range(0,image.height,128):
        stripe=image.crop((0,y,image.width,min(y+128,image.height)))
        result.paste(Image.merge('RGB',[channel.point(lut) for channel,lut in zip(stripe.split(),luts)]),(0,y))
    result.save(OUT/'night.jpg',quality=98,subsampling=0,optimize=True)
with Image.open(SOURCE/'8k_earth_specular_map.tif') as image:
    image.convert('RGB').resize((4096,2048),Image.Resampling.LANCZOS).save(OUT/'ocean.jpg',quality=98,subsampling=0,optimize=True)
with Image.open(SOURCE/'8k_earth_clouds.jpg') as image:
    # Preserve the original fine filaments for close orbital views.
    alpha=image.convert('L')
    alpha=alpha.point([round(min(.98,(v/255)**1.02)*255) for v in range(256)])
    cloud=Image.new('RGBA',alpha.size,(255,255,255,0));cloud.putalpha(alpha);cloud.save(OUT/'clouds.png')
for name in ['day.jpg','night.jpg','ocean.jpg','clouds.png']:shutil.copyfile(OUT/name,RUNTIME/name)
print('Earth: native 8K day/city/cloud detail, 4K ocean and terrain normals')
