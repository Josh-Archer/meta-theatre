"""Strip workstation metadata from the new reference, preserving numeric data and image pixels."""
from pathlib import Path
import hashlib
import subprocess
import tempfile
from PIL import Image, PngImagePlugin

ROOT = Path(__file__).resolve().parents[2]
source = ROOT / 'art/blender/earth_scattering.blend'
home = str(Path.home()).encode()
neutral = b'/tmp/' + b'/' * (len(home) - 5)
assert len(home) == len(neutral)
with tempfile.TemporaryDirectory(prefix='theatre-reference-') as temporary:
    raw = Path(temporary) / 'reference.blend'
    with raw.open('wb') as output:
        subprocess.run(['zstd', '-dc', str(source)], stdout=output, check=True)
    original = raw.read_bytes()
    assert original.startswith(b'BLENDER')
    # Blender's saved file-browser directory is a null-terminated string. No image or numeric field is edited.
    assert original.count(home) <= 1
    if home in original:
        assert original[original.index(home) + len(home)] == 0
    clean = original.replace(home, neutral)
    assert len(clean) == len(original) and b'/home/' not in clean
    raw.write_bytes(clean)
    subprocess.run(['zstd', '-q', '-f', '-3', str(raw), '-o', str(source)], check=True)

for path in (ROOT / 'art/renders/earth_scattering').glob('*.png'):
    image = Image.open(path)
    image.load()
    before = hashlib.sha256(image.tobytes()).digest()
    metadata = PngImagePlugin.PngInfo()
    metadata.add(b'sRGB', b'\x00')
    metadata.add_text('Description', 'Blender lighting reference, not a headset capture')
    image.info.clear()
    image.save(path, pnginfo=metadata)
    assert before == hashlib.sha256(Image.open(path).tobytes()).digest()
print('Reference metadata sanitized; numeric data and pixels preserved.')
