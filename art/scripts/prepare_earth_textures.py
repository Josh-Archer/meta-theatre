"""Download attributed source maps and prepare 4K working copies (Python + Pillow)."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.request import Request, urlopen
from PIL import Image
ROOT = Path(__file__).resolve().parents[2]
source = ROOT / 'art/textures/earth/source'
working = ROOT / 'art/textures/earth/runtime'
source.mkdir(parents=True, exist_ok=True)
working.mkdir(parents=True, exist_ok=True)
FILES = ['8k_earth_daymap.jpg', '8k_earth_nightmap.jpg', '8k_earth_clouds.jpg',
         '8k_earth_normal_map.tif', '8k_earth_specular_map.tif']
def prepare(name):
    target = source / name
    if not target.exists():
        request = Request('https://www.solarsystemscope.com/textures/download/' + name,
                          headers={'User-Agent': 'Mozilla/5.0', 'Referer': 'https://www.solarsystemscope.com/textures/'})
        with urlopen(request, timeout=60) as response:
            data = response.read()
        target.write_bytes(data)
    with Image.open(target) as image:
        assert image.size == (8192, 4096), (name, image.size)
        image = image.convert('RGB')
        image.thumbnail((4096, 2048))
        image.save(working / (target.stem.replace('8k_', '4k_') + '.png'))
    return name
if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=3) as executor:
        for name in executor.map(prepare, FILES):
            print(name)
