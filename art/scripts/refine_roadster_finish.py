"""Material-only revision; preserve every runtime geometry byte and image resolution.
Run in Blender (live bridge or --background --python). Root is derived from this file.
"""
from pathlib import Path
import bpy, json, struct, numpy as np
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'art/exports/starship_20261113';OUT.mkdir(parents=True,exist_ok=True)
TEX=ROOT/'art/textures/roadster_20261113';TEX.mkdir(parents=True,exist_ok=True)
source=ROOT/'art/exports/realism_20261111/space_roadster.glb'
data=source.read_bytes();size=struct.unpack_from('<I',data,12)[0]
doc=json.loads(data[20:20+size]);start=20+size;blob=data[start+8:]
# Bake subdued relief into the pixels, so the native result does not depend on
# whether an importer implements glTF normalTexture.scale.
replacement={}; new_images={}
normal_ids={doc['textures'][m['normalTexture']['index']]['source'] for m in doc['materials'] if 'normalTexture' in m}
for i in normal_ids:
 im=doc['images'][i];view=doc['bufferViews'][im['bufferView']];raw=blob[view.get('byteOffset',0):view.get('byteOffset',0)+view['byteLength']]
 path=TEX/('normal_'+str(i)+'.png');path.write_bytes(raw)
 image=bpy.data.images.load(str(path),check_existing=False);image.colorspace_settings.name='Non-Color'
 w,h=image.size;px=np.empty(w*h*4,np.float32);image.pixels.foreach_get(px);px=px.reshape(h,w,4)
 n=px[:,:,:3]*2-1;n[:,:,:2]*=.20;n/=np.maximum(np.linalg.norm(n,axis=2,keepdims=True),1e-6)
 px[:,:,:3]=n*.5+.5;image.pixels.foreach_set(px.ravel());image.filepath_raw=str(path);image.file_format='PNG';image.save();image.pack()
 replacement[im['bufferView']]=path.read_bytes();new_images[i]=image
for m in doc['materials']:
 name=m['name'];p=m['pbrMetallicRoughness']
 if 'Cherry red' in name:
  p.update(baseColorFactor=[.235,.006,.012,1],roughnessFactor=.32,metallicFactor=0)
  m['extensions']['KHR_materials_clearcoat'].update(clearcoatFactor=.55,clearcoatRoughnessFactor=.24)
 elif 'Windshield' in name or 'Visor and optics' in name:
  p.update(baseColorFactor=[.012,.019,.023,1],metallicFactor=0,roughnessFactor=.18)
 if 'normalTexture' in m:m['normalTexture']['scale']=1
# Repack buffer views without touching any accessor bytes.
chunks=bytearray()
for i,v in enumerate(doc['bufferViews']):
 value=replacement.get(i,blob[v.get('byteOffset',0):v.get('byteOffset',0)+v['byteLength']])
 while len(chunks)%4:chunks.append(0)
 v['byteOffset']=len(chunks);v['byteLength']=len(value);chunks.extend(value)
while len(chunks)%4:chunks.append(0)
doc['buffers'][0]['byteLength']=len(chunks)
js=json.dumps(doc,separators=(',',':')).encode();js+=b' '*((-len(js))%4)
glb=struct.pack('<III',0x46546c67,2,28+len(js)+len(chunks))+struct.pack('<II',len(js),0x4e4f534a)+js+struct.pack('<II',len(chunks),0x004e4942)+chunks
(OUT/'space_roadster.glb').write_bytes(glb)
# Preserve editable components and their authored normals in a versioned source.
with bpy.data.libraries.load(str(ROOT/'art/blender/space_roadster_realism_20261111.blend')) as (a,b):b.scenes=a.scenes
scene=next(s for s in b.scenes if any('ROADSTER' in c.name for c in s.collection.children));bpy.context.window.scene=scene
scene.name='Roadster | Native finish 20261113'
for m in {slot.material for o in scene.objects for slot in o.material_slots if slot.material}:
 p=m.node_tree.nodes.get('Principled BSDF')
 if not p:continue
 if 'Cherry red' in m.name:
  p.inputs['Base Color'].default_value=(.235,.006,.012,1);p.inputs['Roughness'].default_value=.32
  p.inputs['Metallic'].default_value=0;p.inputs['Coat Weight'].default_value=.55;p.inputs['Coat Roughness'].default_value=.24
 elif 'Windshield' in m.name or 'Visor and optics' in m.name:
  p.inputs['Base Color'].default_value=(.012,.019,.023,1);p.inputs['Metallic'].default_value=0;p.inputs['Roughness'].default_value=.18
 for node in m.node_tree.nodes:
  if node.type=='NORMAL_MAP':node.inputs['Strength'].default_value=1
  if node.type=='TEX_IMAGE' and node.image:
   for i,image in new_images.items():
    if node.image.name.startswith(doc['images'][i]['name']):
     node.image=image
     break
   node.image.pack()
   if Path(node.image.filepath).is_absolute():node.image.filepath=bpy.path.relpath(node.image.filepath,start=str(ROOT/'art/blender'))
bpy.data.libraries.write(str(ROOT/'art/blender/space_roadster_finish_20261113.blend'),{scene},fake_user=True,compress=True)
result={'runtime':str(OUT/'space_roadster.glb'),'normal_images':sorted(normal_ids),'materials':len(doc['materials'])}
