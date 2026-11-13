"""File-format conversion only: preserve authored pixels with JPEG 98, 4:4:4.

Run after Blender map authoring and before comparison renders/shader checks.
The Blender PNGs remain the lossless texture masters.
"""
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[2] / 'art/textures/celestial_20261111'
for name in ['moon', 'stars']:
    Image.open(root / (name + '.png')).convert('RGB').save(
        root / (name + '.jpg'), quality=98, subsampling=0)
