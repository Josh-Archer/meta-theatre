"""Bake a continuous 4K orbital density field in live Blender; no tiled cloud stamps.
R: ranked source cloud morphology, G: multiscale erosion, B: warped frontal structure.
"""
import bpy, numpy as np, math, shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'art/textures/weather';OUT.mkdir(parents=True,exist_ok=True)
checkpoint=ROOT/'art/validation/scene-interactions/earth-quality/before_cloud_structure.blend'
bpy.ops.wm.save_as_mainfile(filepath=str(checkpoint),copy=True)
w,h=4096,2048
original=bpy.data.images.load(str(ROOT/'app/src/main/assets/earth/clouds.png'),check_existing=False)
pixels=np.empty(w*h*4,dtype=np.float32);original.pixels.foreach_get(pixels)
cloud=pixels.reshape(h,w,4)[:,:,3].copy();del pixels
rng=np.random.default_rng(72941)
fy=np.fft.fftfreq(h)[:,None];fx=np.fft.rfftfreq(w)[None,:]
k=np.sqrt(fx*fx+fy*fy)
def field(power):
    spectrum=(rng.normal(size=(h,w//2+1))+1j*rng.normal(size=(h,w//2+1))) * np.maximum(k,1/w)**(-power)
    spectrum[k<.004]=0
    spectrum *= np.exp(-(k/.13)**4)
    noise=np.fft.irfft2(spectrum,s=(h,w)).real.astype(np.float32)
    return (noise-noise.mean())/noise.std()
def rank(value):
    # Quantized empirical CDF avoids large sort-index buffers, retaining fine gradients.
    lo,hi=np.percentile(value,[.02,99.98])
    indices=np.clip(((value-lo)/(hi-lo)*65535).astype(np.int32),0,65535)
    histogram=np.bincount(indices.ravel(),minlength=65536);cdf=np.cumsum(histogram,dtype=np.float64)/indices.size
    return cdf[indices].astype(np.float32)
macro=rank(cloud*.82 + field(1.12)*.035)
erosion=rank(field(1.05)*.85+field(.80)*.15)
latitude=np.linspace(-math.pi/2,math.pi/2,h,dtype=np.float32)[:,None]
longitude=np.linspace(-math.pi,math.pi,w,endpoint=False,dtype=np.float32)[None,:]
# Longitude wrapping and polar convergence prevent stamp edges, dateline seams and pinched caps.
shift=(w*.137 + np.sin(latitude*3)*w*.055 + np.sin(longitude*2)*w*.012).astype(np.int32)
indices=(np.arange(w)[None,:]+shift)%w
front=rank(np.take_along_axis(cloud,indices,axis=1)*.8+field(1.05)*.04)
packed=np.ones((h,w,4),dtype=np.float32)
for channel,data in enumerate([macro,erosion,front]):
    pole=np.clip(np.cos(latitude)*10,0,1)
    data=data*pole+data.mean(axis=1,keepdims=True)*(1-pole)
    packed[:,:,channel]=data
image=bpy.data.images.new('Orbital weather | continuous morphology',width=w,height=h,alpha=False)
image.colorspace_settings.name='Non-Color';image.pixels.foreach_set(packed.ravel());image.update()
image.filepath_raw=str(OUT/'cloud_structure.png');image.file_format='PNG';image.save()
image.filepath='//../textures/weather/cloud_structure.png'
shutil.copyfile(OUT/'cloud_structure.png',ROOT/'app/src/main/assets/weather/cloud_structure.png')
# Preserve the artist-adjustable field setup in a separate source, leaving the open scene intact.
scene=bpy.data.scenes.new('WEATHER | Continuous orbital density')
bpy.context.window.scene=scene
scene['description']='Source morphology with spherical polar convergence; ranked cloud and erosion channels. See author_cloud_structure.py.'
scene['seed']=72941;scene['resolution']='4096x2048';scene['repeating_stamps']=False
bpy.data.libraries.write(str(ROOT/'art/blender/weather_continuous.blend'),{scene,image},path_remap='RELATIVE',fake_user=True,compress=True)
result={'continuous_field':[w,h],'channels':['morphology','erosion','fronts'],'source_saved':True,'repeated_stamps':False}
