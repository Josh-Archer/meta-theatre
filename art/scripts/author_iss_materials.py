"""ISS realistic surface materials (issue #12).

Run headless against the editable source:
  blender -b art/blender/orbital_visitors.blend --python art/scripts/author_iss_materials.py -- [--export PATH]

Authors deterministic, tileable PBR textures (art/textures/iss/), rebuilds the four
ISS material slots around them, box-projects UVs for the three non-photovoltaic
slots (photovoltaic UVs are left untouched so panel cells keep their layout),
saves the .blend, and exports the joined ISS GLB with the same triangulation
path used by export_space_visitors.py. Geometry is not changed.
"""
import sys, json
import bpy
import numpy as np
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] if '__file__' in globals() else Path(bpy.path.abspath('//')).resolve().parents[1]
if not (ROOT / 'app/src/main/assets').is_dir():
    raise RuntimeError('Run with the project checkout as the root.')
argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
EXPORT = Path(argv[argv.index('--export') + 1]) if '--export' in argv else ROOT / 'art/exports/flybys/iss.glb'
TEX = ROOT / 'art/textures/iss'
TEX.mkdir(parents=True, exist_ok=True)

# ---------------------------------------------------------------- noise helpers
def lowpass(n, sx, sy, seed):
    """Periodic gaussian-filtered noise, unit std. sx/sy are cutoff frequencies in cycles per texel."""
    rng = np.random.default_rng(seed)
    f = np.fft.fft2(rng.standard_normal((n, n)))
    fx = np.fft.fftfreq(n)[None, :]; fy = np.fft.fftfreq(n)[:, None]
    f *= np.exp(-(fx / sx) ** 2 - (fy / sy) ** 2)
    out = np.fft.ifft2(f).real
    return (out - out.mean()) / (out.std() + 1e-9)

def to_normal(h, strength):
    dx = (np.roll(h, -1, 1) - np.roll(h, 1, 1)) * .5
    dy = (np.roll(h, -1, 0) - np.roll(h, 1, 0)) * .5  # row index == +V in Blender image space
    nrm = np.stack([-dx * strength, -dy * strength, np.ones_like(h)], -1)
    nrm /= np.linalg.norm(nrm, axis=-1, keepdims=True)
    return nrm * .5 + .5

def groove(coord, period, width):
    """1 on a periodic line, falling off smoothly; coord in tile units [0,1)."""
    d = np.abs(((coord / period + .5) % 1) - .5) * period
    return np.exp(-(d / width) ** 2)

def srgb(lin):
    lin = np.clip(lin, 0, 1)
    return np.where(lin <= .0031308, lin * 12.92, 1.055 * lin ** (1 / 2.4) - .055)

def save(name, rgb, colorspace, jpeg=False):
    n = rgb.shape[0]
    px = np.ones((n, n, 4), dtype=np.float32)
    px[:, :, :3] = np.clip(rgb, 0, 1)
    if jpeg:
        name = name.replace('.png', '.jpg')
    path = TEX / name
    img = bpy.data.images.new(name, width=n, height=n, alpha=False)
    img.colorspace_settings.name = colorspace
    img.pixels.foreach_set(px.ravel())
    img.filepath_raw = str(path); img.file_format = 'JPEG' if jpeg else 'PNG'
    img.save()
    img.pack()
    return img

def mr(rough, metal):
    return np.stack([np.zeros_like(rough), np.clip(rough, 0, 1), np.clip(metal, 0, 1)], -1)

def grid(n):
    u, v = np.meshgrid(np.arange(n) / n, np.arange(n) / n)
    return u, v

# ---------------------------------------------------------------- texture sets
# Base colours keep the previous material factors as the mean so the overall tone does not shift.
BASE = {
    'blanket': (.72, .75, .73), 'aluminium': (.46, .51, .56), 'gold': (.47, .26, .055)}

def blanket():
    n = 512; u, v = grid(n)  # one tile = 4 m, 4x4 quilted cells
    wr = lowpass(n, .012, .012, 11)                       # broad wrinkles
    fine = lowpass(n, .05, .05, 12)
    seam = np.maximum(groove(u, .25, .006), groove(v, .25, .006))
    tape = np.maximum(groove(u, 1., .012), groove(v, 1., .012))
    pillow = .5 - .5 * np.cos(2 * np.pi * u * 4) * np.cos(2 * np.pi * v * 4)  # soft puffing between seams
    h = .35 * pillow - 1.2 * seam - .5 * tape + .22 * wr + .05 * fine
    shade = 1 + .035 * wr + .015 * fine - .16 * seam - .08 * tape
    col = np.array(BASE['blanket'])[None, None, :] * shade[..., None]
    rough = .58 + .05 * wr + .02 * fine + .06 * seam
    return srgb(col), to_normal(h, 1.1), mr(rough, np.full_like(rough, .15))

def aluminium():
    n = 512; u, v = grid(n)  # one tile = 2 m
    streak = lowpass(n, .0035, .38, 21)                   # long brushing along U
    patch = lowpass(n, .012, .012, 22)
    seam = np.maximum(groove(u, .5, .004), groove(v, .5, .004))
    h = .22 * streak + .12 * patch - .9 * seam
    shade = 1 + .045 * streak + .04 * patch - .22 * seam
    col = np.array(BASE['aluminium'])[None, None, :] * shade[..., None]
    rough = .30 + .045 * streak + .035 * patch + .05 * seam
    metal = .70 + .04 * patch
    return srgb(col), to_normal(h, .9), mr(rough, metal)

def gold():
    n = 512; u, v = grid(n)  # one tile = 3 m
    broad = lowpass(n, .02, .02, 31)
    crease = 1 - np.abs(lowpass(n, .018, .018, 32)).clip(0, 2.2) / 2.2   # ridged creases
    crease2 = 1 - np.abs(lowpass(n, .04, .04, 33)).clip(0, 2.2) / 2.2
    h = .8 * crease + .15 * crease2 + .3 * broad
    h = (h - h.mean()) / h.std()
    slope = to_normal(h, 1.0)[..., 0] - .5
    shade = 1 + .05 * broad + .22 * slope
    tint = np.array([1, 1, 1])[None, None, :] + np.stack([.03 * broad, 0 * broad, -.05 * broad], -1)
    col = np.array(BASE['gold'])[None, None, :] * (shade[..., None] * tint)
    rough = .34 + .05 * (1 - crease) + .03 * broad
    metal = .65 + .05 * np.clip(slope * 4, -1, 1)
    return srgb(col), to_normal(h, .55), mr(rough, metal)

def photovoltaic():
    n = 1024; u, v = grid(n)
    # Same cell layout as the shipped texture: 12 columns x 6 rows, 3.5% border.
    border = (np.mod(u * 12, 1) < .035) | (np.mod(v * 6, 1) < .035)
    cu = np.mod(u * 12, 1); cv = np.mod(v * 6, 1)
    cid = np.floor(u * 12) + 12 * np.floor(v * 6)
    rng = np.random.default_rng(41)
    tint = (rng.standard_normal(72)[cid.astype(int) % 72] * .05)             # per-cell tone
    bus = np.maximum(np.exp(-((cu - .33) / .008) ** 2), np.exp(-((cu - .66) / .008) ** 2))
    fingers = (np.mod(cv * 24, 1) < .08) * .35                               # fine grid fingers
    ar = 1 - ((cu - .5) ** 2 + (cv - .5) ** 2) * .5   # anti-reflective coating falloff toward cell corners
    base = np.array([.055, .10, .19])[None, None, :]
    nz = lowpass(n, .15, .15, 42)
    cell = base * (1 + tint[..., None] + .05 * nz[..., None] + .22 * (ar[..., None] - .75))
    cell = cell + np.array([.05, .035, .01])[None, None, :] * (bus[..., None] * .9 + fingers[..., None] * .35)
    bcol = np.array([.49, .28, .075])[None, None, :]
    col = np.where(border[..., None], bcol, cell)
    h = -1.0 * border + .15 * bus + .05 * fingers
    rough_cell = .36 + .04 * nz + .05 * (1 - ar)
    rough = np.where(border, .55, rough_cell)
    metal = np.where(border, .6, .3 + .05 * bus)
    # The 256 px MR map keeps the same layout; resample from the 1K fields.
    m = mr(rough, metal)[::4, ::4]
    # Normal map at 512
    h512 = h.reshape(512, 2, 512, 2).mean((1, 3))
    return col, to_normal(h512, 3.0), m

# ---------------------------------------------------------------- build
scene = bpy.data.scenes['ORBITAL VISITORS | Editable source']
assert bpy.context.scene == scene
iss = bpy.data.collections['ISS | Quest optimized']
spec = {
    'ISS | Thermal blanket ivory': ('blanket', blanket, 4.0),
    'ISS | Brushed aluminium': ('aluminium', aluminium, 2.0),
    'ISS | Gold insulation': ('gold', gold, 3.0),
    'ISS | Photovoltaic cells': ('pv', photovoltaic, None),
}
report = {}
for matname, (key, fn, tile) in spec.items():
    bc, nm, m = fn()
    imgs = {
        # Smooth, low-contrast maps are stored as JPEG; normals and the 1-texel photovoltaic grid stay lossless PNG.
        'color': save(f'iss_{key}_basecolor.png', bc, 'sRGB', jpeg=key != 'pv'),
        'normal': save(f'iss_{key}_normal.png', nm, 'Non-Color'),
        'mr': save(f'iss_{key}_metal_rough.png', m, 'Non-Color', jpeg=key != 'pv'),
    }
    mat = bpy.data.materials[matname]
    nt = mat.node_tree
    for node in list(nt.nodes):
        if node.type not in {'BSDF_PRINCIPLED', 'OUTPUT_MATERIAL'}:
            nt.nodes.remove(node)
    bsdf = nt.nodes['Principled BSDF']
    def tex(img, y):
        t = nt.nodes.new('ShaderNodeTexImage'); t.image = img; t.location = (-600, y); t.interpolation = 'Linear'; return t
    tc, tn, tm = tex(imgs['color'], 300), tex(imgs['normal'], 0), tex(imgs['mr'], -300)
    nt.links.new(tc.outputs['Color'], bsdf.inputs['Base Color'])
    nmap = nt.nodes.new('ShaderNodeNormalMap'); nmap.location = (-300, 0); nmap.inputs['Strength'].default_value = 1.0
    nt.links.new(tn.outputs['Color'], nmap.inputs['Color']); nt.links.new(nmap.outputs['Normal'], bsdf.inputs['Normal'])
    sep = nt.nodes.new('ShaderNodeSeparateColor'); sep.location = (-300, -300)
    nt.links.new(tm.outputs['Color'], sep.inputs['Color'])
    nt.links.new(sep.outputs['Green'], bsdf.inputs['Roughness']); nt.links.new(sep.outputs['Blue'], bsdf.inputs['Metallic'])
    report[matname] = {k: [list(i.size), i.colorspace_settings.name] for k, i in imgs.items()}

# Box-project UVs for the three non-photovoltaic slots; slot 3 (PV) UVs stay as authored.
TILE = {0: 4.0, 1: 2.0, 2: 3.0}
for o in iss.objects:
    if o.type != 'MESH':
        continue
    me = o.data; uv = me.uv_layers.active
    for f in me.polygons:
        if f.material_index == 3:
            continue
        a = int(np.argmax(np.abs(f.normal)))
        ax = [(1, 2), (0, 2), (0, 1)][a]
        for li in f.loop_indices:
            p = me.vertices[me.loops[li].vertex_index].co
            uv.data[li].uv = (p[ax[0]] / TILE[f.material_index], p[ax[1]] / TILE[f.material_index])

scene['iss_materials'] = 'Issue #12: authored tileable PBR maps in art/textures/iss; non-PV UVs box projected; PV UVs unchanged.'
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT / 'art/blender/orbital_visitors.blend'))

# ---------------------------------------------------------------- export (same path as export_space_visitors.py, ISS only)
bpy.context.view_layer.update(); deps = bpy.context.evaluated_depsgraph_get()
copies = []
for o in list(iss.objects):
    if o.type != 'MESH':
        continue
    mesh = bpy.data.meshes.new_from_object(o.evaluated_get(deps), depsgraph=deps)
    p = bpy.data.objects.new(o.name + ' export', mesh); scene.collection.objects.link(p); p.matrix_world = o.matrix_world.copy(); copies.append(p)
bpy.ops.object.select_all(action='DESELECT')
for p in copies:
    p.select_set(True)
bpy.context.view_layer.objects.active = copies[0]; bpy.ops.object.join(); joined = bpy.context.object; joined.name = 'iss'
bpy.ops.object.mode_set(mode='EDIT'); bpy.ops.mesh.select_all(action='SELECT')
bpy.ops.mesh.quads_convert_to_tris(quad_method='BEAUTY', ngon_method='BEAUTY'); bpy.ops.object.mode_set(mode='OBJECT')
joined.data.validate(verbose=False, clean_customdata=True); joined.data.calc_loop_triangles()
report['export'] = {'triangles': len(joined.data.loop_triangles), 'vertices': len(joined.data.vertices),
                    'materials': len(joined.data.materials), 'dimensions_m': list(joined.dimensions)}
EXPORT.parent.mkdir(parents=True, exist_ok=True)
bpy.ops.export_scene.gltf(filepath=str(EXPORT), export_format='GLB', use_selection=True, use_active_scene=True, export_apply=True,
                          export_yup=True, export_texcoords=True, export_normals=True, export_tangents=True,
                          export_materials='EXPORT', export_draco_mesh_compression_enable=False)
print('ISS_REPORT', json.dumps(report))
