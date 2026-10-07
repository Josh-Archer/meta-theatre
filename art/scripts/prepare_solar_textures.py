"""Acquire credited source maps and prepare bounded runtime copies.

Run with Python + Pillow. The NASA linear EXR is converted inside Blender by
build_solar_assets.py, preserving the source here for re-authoring.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import json
import requests
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'art/textures/solar/source'
RUNTIME = ROOT / 'art/textures/solar/runtime'
SOURCE.mkdir(parents=True, exist_ok=True)
RUNTIME.mkdir(parents=True, exist_ok=True)
PLANETS = {
    'mercury': '8k_mercury.jpg', 'venus': '4k_venus_atmosphere.jpg',
    'mars': '8k_mars.jpg', 'jupiter': '8k_jupiter.jpg',
    'saturn': '8k_saturn.jpg', 'uranus': '2k_uranus.jpg',
    'neptune': '2k_neptune.jpg', 'moon': '8k_moon.jpg', 'sun': '8k_sun.jpg',
    'saturn_rings': '8k_saturn_ring_alpha.png',
}

def acquire(item):
    name, filename = item
    url = 'https://www.solarsystemscope.com/textures/download/' + filename
    if name == 'stars':
        url = 'https://svs.gsfc.nasa.gov/vis/a000000/a004800/a004851/starmap_2020_4k.exr'
    destination = SOURCE / filename
    if not destination.exists():
        response = requests.get(url, timeout=90, headers={
            'User-Agent': 'Mozilla/5.0', 'Referer': 'https://www.solarsystemscope.com/textures/'})
        response.raise_for_status()
        destination.write_bytes(response.content)
    dimensions = None
    if name != 'stars':
        with Image.open(destination) as image:
            dimensions = list(image.size)
            if name == 'saturn_rings':
                image.convert('RGBA').resize((2048, 1), Image.Resampling.LANCZOS).save(RUNTIME / 'saturn_rings.png')
            else:
                image.convert('RGB').resize((2048, 1024), Image.Resampling.LANCZOS).save(RUNTIME / (name + '.jpg'), quality=94, subsampling=0)
    return dict(name=name, source=str(destination.relative_to(ROOT)), url=url,
                dimensions=dimensions, bytes=destination.stat().st_size,
                sha256=hashlib.sha256(destination.read_bytes()).hexdigest())

if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=3) as pool:
        records = list(pool.map(acquire, [*PLANETS.items(), ('stars', 'starmap_2020_4k.exr')]))
    (ROOT / 'art/validation/solar_source_manifest.json').write_text(json.dumps(records, indent=2) + '\n')
    for record in records:
        print(record['name'], record['dimensions'], record['bytes'])
