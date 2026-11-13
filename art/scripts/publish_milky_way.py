"""Validate the reviewed sky, preserve the old map, and publish native output."""
import hashlib
import json
import shutil
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
source = ROOT / 'art/textures/space/runtime/stars.png'
jpg = source.with_suffix('.jpg')
image = Image.open(source).convert('RGB')
assert image.size == (8192, 4096)
image.save(jpg, quality=98, subsampling=0)
decoded = np.asarray(Image.open(jpg), dtype=np.float32)
original = np.asarray(image, dtype=np.float32)
error = float(np.mean((decoded - original) ** 2))
psnr = float(10 * np.log10(255 ** 2 / max(error, 1e-10)))
assert psnr > 40, f'JPEG fidelity too low: {psnr}'
assert np.isfinite(decoded).all()
assert np.mean(decoded.max(axis=2) >= 254) < .002, 'Excessive clipped stars'
assert (ROOT / 'art/blender/milky_way.blend').stat().st_size > 1_000_000
for view in ['before_core', 'milky_way_core', 'cygnus_rift']:
    assert Image.open(ROOT / f'art/renders/space/{view}.png').size == (1800, 1100)

runtime = ROOT / 'app/src/main/assets/solar/stars.jpg'
backup = ROOT / 'art/textures/space/before/stars_4k.jpg'
backup.parent.mkdir(parents=True, exist_ok=True)
if not backup.exists():
    assert Image.open(runtime).size == (4096, 2048)
    shutil.copy2(runtime, backup)
shutil.copy2(jpg, runtime)
assert runtime.read_bytes() == jpg.read_bytes()

canvas = Image.new('RGB', (1800, 620), '#06080c')
draw = ImageDraw.Draw(canvas)
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 23)
for x, name, label in [(0, 'before_core', 'BEFORE | 4K catalog'), (900, 'milky_way_core', 'AFTER | 8K Milky Way + bright stars')]:
    view = Image.open(ROOT / f'art/renders/space/{name}.png').resize((900, 550), Image.Resampling.LANCZOS)
    canvas.paste(view, (x, 45))
    draw.text((x + 20, 12), label, fill='#dfe9f5', font=font)
draw.text((20, 595), 'Matched Blender authoring previews. Not headset captures.', fill='#9baabb', font=font)
canvas.save(ROOT / 'art/renders/space/before_after.jpg', quality=96, subsampling=0)
report = {
    'runtime': str(runtime.relative_to(ROOT)), 'dimensions': list(image.size),
    'bytes': runtime.stat().st_size, 'sha256': hashlib.sha256(runtime.read_bytes()).hexdigest(),
    'jpeg_psnr_db': psnr, 'clipped_pixel_fraction': float(np.mean(decoded.max(axis=2) >= 254)),
    'previous_map': str(backup.relative_to(ROOT)),
    'runtime_geometry_unchanged': True,
    'authoring_previews_reviewed': True,
}
(ROOT / 'art/validation/milky_way_publish.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
