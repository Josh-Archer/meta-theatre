"""Remove authoring workstation metadata; preserve every preview pixel."""
from pathlib import Path
from PIL import Image,PngImagePlugin
import hashlib
ROOT=Path(__file__).resolve().parents[2]
for path in (ROOT/'art/renders/earth_detailed').glob('*.png'):
    image=Image.open(path);image.load();before=hashlib.sha256(image.tobytes()).digest()
    metadata=PngImagePlugin.PngInfo();metadata.add(b'sRGB',b'\x00')
    metadata.add_text('Description','Blender authoring reference, not a headset capture')
    image.info.clear();image.save(path,pnginfo=metadata)
    assert before==hashlib.sha256(Image.open(path).tobytes()).digest()
print('Preview metadata sanitized; pixels unchanged')
