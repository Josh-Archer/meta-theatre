"""Author the observation cabin in Blender; run through the live MCP bridge.

The original pressure shell remains geometrically intact. New interior fittings
are separate editable objects. Export is deliberately separate from authoring.
All dimensions are metres; the app's forward +Z is Blender -Y.
"""
from pathlib import Path
import json
import math
import bpy
import numpy as np
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[2]
ART = ROOT / 'art'
TEX = ART / 'textures/cabin_20261114'
TEX.mkdir(parents=True, exist_ok=True)
scene = bpy.data.scenes.new('Observation_Cabin_20261114')
bpy.context.window.scene = scene
scene.unit_settings.system = 'METRIC'
bpy.ops.import_scene.gltf(filepath=str(ART / 'exports/cabin_20261114/hull_before.glb'))
shell = next(o for o in scene.objects if o.type == 'MESH')
shell.name = 'Original pressure shell - geometry retained'
original_triangles = sum(len(p.vertices) - 2 for p in shell.data.polygons)
shell['original_triangles'] = original_triangles
shell['source'] = 'hull_before.glb; original vertices, faces and opening positions'


def image(name, rgb, noncolor=False):
    h, w = rgb.shape[:2]
    im = bpy.data.images.new(name, width=w, height=h, alpha=False)
    if noncolor:
        im.colorspace_settings.name = 'Non-Color'
    rgba = np.ones((h, w, 4), dtype=np.float32)
    rgba[:, :, :3] = np.clip(rgb, 0, 1)
    im.pixels.foreach_set(rgba.ravel())
    im.filepath_raw = str(TEX / (name + '.png'))
    im.file_format = 'PNG'
    im.save()
    im.pack()
    im.filepath_raw = '//../textures/cabin_20261114/' + name + '.png'
    return im


# Reproducible, seamless technical textures. These are authored patterns, not
# downloaded photographs. Restrained variation avoids noisy specular shimmer.
n = 1024
y, x = np.mgrid[:n, :n].astype(np.float32) / n
rng = np.random.default_rng(14112026)
grain = rng.random((n, n), dtype=np.float32) - .5
brush = np.sin(y * math.tau * 227) * .5 + np.sin(y * math.tau * 91) * .25
macro = np.sin(x * math.tau * 3) * np.cos(y * math.tau * 2)
rough = .43 + grain * .025 + brush * .028 + macro * .025
rough_image = image('Satin_roughness', np.repeat(rough[:, :, None], 3, axis=2), True)
normal = np.empty((n, n, 3), np.float32)
normal[:, :, 0] = .5 + np.sin(x * math.tau * 180) * .012
normal[:, :, 1] = .5 + brush * .022
normal[:, :, 2] = np.sqrt(1 - ((normal[:, :, :2] * 2 - 1) ** 2).sum(2)) * .5 + .5
normal_image = image('Fine_brushed_normal', normal, True)
panel = np.ones((n, n, 3), np.float32) * np.array([.72, .745, .755])
panel += (grain * .006 + macro * .008)[:, :, None]
edge = np.minimum.reduce([x, y, 1 - x, 1 - y])
panel *= (.975 + .025 * np.clip(edge * 28, 0, 1))[:, :, None]
panel_image = image('Ceramic_satin_base', panel)

# A matte composite deck with physically small anti-slip ridges, not a large
# repeated photographic panel border. Existing mesh seams remain the panel grid.
weave = (np.sin(x * math.tau * 64) * np.sin(y * math.tau * 64))
deck = np.ones((n, n, 3), np.float32) * np.array([.235, .255, .275])
deck += (grain * .009 + weave * .006 + macro * .003)[:, :, None]
deck_image = image('Composite_deck_base', deck)
deck_n = np.empty((n, n, 3), np.float32)
deck_n[:, :, 0] = .5 + np.cos(x * math.tau * 64) * np.sin(y * math.tau * 64) * .065
deck_n[:, :, 1] = .5 + np.sin(x * math.tau * 64) * np.cos(y * math.tau * 64) * .065
deck_n[:, :, 2] = np.sqrt(1 - ((deck_n[:, :, :2] * 2 - 1) ** 2).sum(2)) * .5 + .5
deck_normal = image('Composite_deck_normal', deck_n, True)

# Nearly-new upholstery: a small change in fiber sheen at the contact area.
# No stains, tears or broad discoloration. UVs cover an individual cushion.
contact = np.exp(-(((x - .54) / .30) ** 2 + ((y - .49) / .31) ** 2) * 1.4)
seat_base = np.ones((n, n, 3), np.float32) * np.array([.073, .10, .125])
seat_base *= (1 + .045 * contact + .012 * grain)[:, :, None]
# PNG base colors are sRGB; the existing fabric factor is scene-linear.
seat_base = np.where(seat_base <= .0031308, seat_base * 12.92,
                     1.055 * seat_base ** (1 / 2.4) - .055)
seat_base_image = image('Seat_fabric_base', seat_base)
seat_rough = .87 - .045 * contact + grain * .012
seat_rough_image = image('Seat_fabric_roughness', np.repeat(seat_rough[:, :, None], 3, 2), True)
seat_n = np.empty((n, n, 3), np.float32)
seat_n[:, :, 0] = .5 + .065 * np.cos(x * math.tau * 384) * np.sin(y * math.tau * 288)
seat_n[:, :, 1] = .5 + .065 * np.sin(x * math.tau * 384) * np.cos(y * math.tau * 288)
seat_n[:, :, 2] = np.sqrt(1 - ((seat_n[:, :, :2] * 2 - 1) ** 2).sum(2)) * .5 + .5
seat_normal_image = image('Seat_fabric_normal', seat_n, True)


def material(name, color, metal=0, roughness=.5, base=None, normal_map=None,
             rough_map=None, emission=None):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    m.diffuse_color = (*color, 1)
    m.use_backface_culling = False
    p = m.node_tree.nodes.get('Principled BSDF')
    p.inputs['Base Color'].default_value = (*color, 1)
    p.inputs['Metallic'].default_value = metal
    p.inputs['Roughness'].default_value = roughness
    p.inputs['Specular IOR Level'].default_value = .5
    p.inputs['IOR'].default_value = 1.5
    for im, socket in [(base, 'Base Color'), (rough_map, 'Roughness')]:
        if im:
            node = m.node_tree.nodes.new('ShaderNodeTexImage')
            node.image = im
            m.node_tree.links.new(node.outputs['Color'], p.inputs[socket])
    if normal_map:
        node = m.node_tree.nodes.new('ShaderNodeTexImage')
        node.image = normal_map
        nm = m.node_tree.nodes.new('ShaderNodeNormalMap')
        nm.inputs['Strength'].default_value = .6
        m.node_tree.links.new(node.outputs['Color'], nm.inputs['Color'])
        m.node_tree.links.new(nm.outputs['Normal'], p.inputs['Normal'])
    if emission:
        p.inputs['Emission Color'].default_value = (*emission, 1)
        p.inputs['Emission Strength'].default_value = 1
    return m


ceramic = material('Cabin | satin ceramic panels', (.50, .53, .55), base=panel_image,
                   rough_map=rough_image, normal_map=normal_image)
frame = material('Cabin | pearl anodized frame', (.48, .51, .53), .65, .4,
                 rough_map=rough_image, normal_map=normal_image)
deckmat = material('Cabin | non-slip graphite deck', (.045, .053, .062), .05, .78,
                   base=deck_image, normal_map=deck_normal)
rubber = material('Cabin | graphite seals', (.018, .024, .032), .05, .66)
metal = material('Cabin | titanium fittings', (.16, .20, .24), .82, .36,
                 rough_map=rough_image, normal_map=normal_image)
bronze = material('Cabin | champagne alloy', (.32, .23, .13), .8, .36,
                  rough_map=rough_image, normal_map=normal_image)
fabric = material('Cabin | woven slate upholstery', (.073, .10, .125), 0, .87,
                  normal_map=deck_normal)
next(n for n in fabric.node_tree.nodes if n.type == 'NORMAL_MAP').inputs['Strength'].default_value = .14
seat_fabric = material('Cabin | gently settled seat fabric', (.073, .10, .125), 0, .87,
                       base=seat_base_image, normal_map=seat_normal_image,
                       rough_map=seat_rough_image)
next(n for n in seat_fabric.node_tree.nodes if n.type == 'NORMAL_MAP').inputs['Strength'].default_value = .22
white = material('Cabin | warm light diffuser', (.88, .85, .76), 0, .35,
                 emission=(1, .84, .62))
cyan = material('Cabin | ice blue indicator', (.03, .22, .28), .1, .4,
                emission=(.07, .55, .73))
ink = material('Cabin | etched lettering', (.56, .59, .60), .25, .5)
display = material('Cabin | instrument glass', (.008, .017, .022), .15, .21)
shell_material_indices = [p.material_index for p in shell.data.polygons]
shell.data.materials.clear()
for mat in [ceramic, frame, deckmat, rubber]:
    shell.data.materials.append(mat)
for poly, index in zip(shell.data.polygons, shell_material_indices):
    poly.material_index = index


def assign(obj, mat, name):
    obj.name = name
    obj.data.materials.append(mat)
    obj['cabin_asset'] = True
    return obj


def box(name, loc, dims, mat, bevel=.025, rotation=None):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc)
    o = bpy.context.object
    o.dimensions = dims
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    assign(o, mat, name)
    for polygon in o.data.polygons:
        polygon.use_smooth = True
    if bevel:
        b = o.modifiers.new('Machined edge radius', 'BEVEL')
        b.width = bevel
        b.segments = 3
        b.affect = 'EDGES'
        norm = o.modifiers.new('Weighted face normals', 'WEIGHTED_NORMAL')
        norm.keep_sharp = True
    if rotation:
        o.rotation_euler = rotation
    return o


def tube(name, points, radius, mat, resolution=3):
    data = bpy.data.curves.new(name, 'CURVE')
    data.dimensions = '3D'
    data.resolution_u = 1
    data.bevel_depth = radius
    data.bevel_resolution = resolution
    data.use_fill_caps = True
    spline = data.splines.new('POLY')
    spline.points.add(len(points) - 1)
    for p, co in zip(spline.points, points):
        p.co = (*co, 1)
    o = bpy.data.objects.new(name, data)
    scene.collection.objects.link(o)
    return assign(o, mat, name)


def settled_cushion(loc, side, wear, index):
    """Rounded fabric cushion with a shallow, asymmetric sitting impression."""
    dims = np.array([.94, .71, .24])
    half = dims / 2
    radius = .075
    inner = half - radius
    divisions = [24, 18, 4]
    vertices, faces, face_axes, lookup = [], [], [], {}

    def vertex(p):
        core = np.clip(p, -inner, inner)
        direction = p - core
        p = core + direction / np.linalg.norm(direction) * radius
        # Two broad contact lobes plus a light compression ripple by the seam.
        xx, yy, zz = p
        upper = np.clip((zz - (half[2] - radius)) / radius, 0, 1)
        upper = upper * upper * (3 - 2 * upper)
        center_x = side * (.055 + index * .006)
        contact = math.exp(-(((xx - center_x) / .25) ** 2 + ((yy + .014) / .23) ** 2) * 1.2)
        lobes = sum(math.exp(-(((xx - center_x) / .19) ** 2 +
                              ((yy - dy) / .10) ** 2) * 1.4) for dy in [-.085, .085])
        dent = -.012 * wear * (.62 * contact + .38 * lobes)
        creases = sum(math.exp(-((yy - dy) / .035) ** 2) for dy in [-.245, .235])
        ripple = .0012 * wear * creases * math.exp(-((xx + side * .30) / .15) ** 2) * math.sin(xx * 28 + index)
        p[2] += upper * (dent + ripple)
        key = tuple(round(float(v), 8) for v in p)
        if key not in lookup:
            lookup[key] = len(vertices)
            vertices.append(tuple(p))
        return lookup[key]

    for axis in range(3):
        u, v = [a for a in range(3) if a != axis]
        for sign in [-1, 1]:
            grid = {}
            for i in range(divisions[u] + 1):
                for j in range(divisions[v] + 1):
                    p = np.zeros(3)
                    p[axis] = sign * half[axis]
                    p[u] = (i / divisions[u] - .5) * dims[u]
                    p[v] = (j / divisions[v] - .5) * dims[v]
                    grid[i, j] = vertex(p)
            for i in range(divisions[u]):
                for j in range(divisions[v]):
                    face = [grid[i, j], grid[i + 1, j], grid[i + 1, j + 1], grid[i, j + 1]]
                    if sign * (-1 if axis == 1 else 1) < 0:
                        face.reverse()
                    faces.append(face)
                    face_axes.append((axis, sign, u, v))
    mesh = bpy.data.meshes.new('Gently settled cushion surface')
    mesh.from_pydata(vertices, [], faces)
    mesh.update()
    obj = bpy.data.objects.new('Upholstered seat cushion - light use', mesh)
    scene.collection.objects.link(obj)
    obj.location = loc
    assign(obj, fabric, obj.name)
    mesh.materials.append(seat_fabric)
    uv = mesh.uv_layers.new(name='Cushion surface')
    for polygon, (axis, sign, u, v) in zip(mesh.polygons, face_axes):
        polygon.use_smooth = True
        polygon.material_index = int(axis == 2 and sign == 1)
        for loop in polygon.loop_indices:
            p = mesh.vertices[mesh.loops[loop].vertex_index].co
            uv.data[loop].uv = (p[u] / dims[u] + .5, p[v] / dims[v] + .5)
    obj['use_level'] = wear
    obj['finish_note'] = 'Nearly-new upholstery: shallow sitting impression and slight seam compression'
    return obj


def arch(name, yy, rad, depth, thick, mat, a0=0, a1=math.pi, segments=72):
    verts = []
    faces = []
    for i in range(segments + 1):
        a = a0 + (a1 - a0) * i / segments
        for r, yp in [(rad, yy - depth / 2), (rad, yy + depth / 2),
                      (rad + thick, yy - depth / 2), (rad + thick, yy + depth / 2)]:
            verts.append((math.cos(a) * r, yp, math.sin(a) * r))
    for i in range(segments):
        a, b = i * 4, (i + 1) * 4
        faces.extend([(a, b, b + 1, a + 1), (a + 2, a + 3, b + 3, b + 2),
                      (a, a + 2, b + 2, b), (a + 1, b + 1, b + 3, a + 3)])
    faces.extend([(0, 1, 3, 2), (segments * 4, segments * 4 + 2,
                  segments * 4 + 3, segments * 4 + 1)])
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], faces)
    mesh.update()
    for polygon in mesh.polygons:
        polygon.use_smooth = True
    o = bpy.data.objects.new(name, mesh)
    scene.collection.objects.link(o)
    assign(o, mat, name)
    be = o.modifiers.new('Soft structural edges', 'BEVEL')
    be.width = .008
    be.segments = 2
    o.modifiers.new('Weighted structural normals', 'WEIGHTED_NORMAL')
    return o


def text(name, body, loc, size, mat, rotation=(math.pi / 2, 0, 0), align='CENTER'):
    data = bpy.data.curves.new(name, 'FONT')
    data.body = body
    data.size = size
    data.align_x = align
    data.extrude = .0004
    data.resolution_u = 4
    o = bpy.data.objects.new(name, data)
    scene.collection.objects.link(o)
    o.location = loc
    o.rotation_euler = rotation
    return assign(o, mat, name)


def fastener(loc, direction=(0, 1, 0)):
    bpy.ops.mesh.primitive_cylinder_add(vertices=12, radius=.019, depth=.007, location=loc)
    o = assign(bpy.context.object, bronze, 'Captive quarter-turn fastener')
    o.rotation_euler = Vector(direction).to_track_quat('Z', 'Y').to_euler()
    return o


# Wide-spaced structural hoops make the curved shell read at human scale.
# Fitted inner liners cover the exposed lattice in the enclosed end bays. The
# central observation openings (Blender Y -3.6 .. +3.6) remain completely open.
for y0, y1 in [(-6.94, -5.43), (-5.38, -3.77), (3.77, 5.38), (5.43, 6.94)]:
    for j in range(14):
        arch('Removable curved ceramic liner', (y0 + y1) / 2, 4.555, y1 - y0, .065,
             ceramic, (j + .025) * math.pi / 14, (j + .975) * math.pi / 14, 9)

for i, yy in enumerate([-6.8, -3.6, 0, 3.6, 6.8]):
    arch('Pressure hoop %02d' % i, yy, 4.50, .15, .10, metal)
    arch('Hoop inset %02d' % i, yy + .084, 4.515, .012, .026, bronze)
    # Recessed light arcs live in the top shoulder, leaving side apertures open.
    for a0, a1 in [(.23 * math.pi, .39 * math.pi), (.61 * math.pi, .77 * math.pi)]:
        arch('Recessed arc light housing', yy - .105, 4.49, .10, .065, rubber, a0, a1, 20)
        arch('Warm arc diffuser', yy - .11, 4.479, .045, .018, white, a0, a1, 20)

# Long ceiling services sit against the upper shell, never over the film panel.
for side in [-1, 1]:
    xx = side * 1.68
    zz = math.sqrt(4.46 ** 2 - xx ** 2)
    box('Overhead service spine', (xx, 0, zz), (.30, 13.5, .11), rubber, .035,
        (0, side * -.38, 0))
    box('Continuous warm ceiling diffuser', (xx - side * .025, 0, zz - .068),
        (.075, 13.3, .025), white, .012, (0, side * -.38, 0))
    for yy in [-5.4, -1.8, 1.8, 5.4]:
        box('Service spine clamp', (xx, yy, zz - .005), (.34, .07, .14), metal, .012,
            (0, side * -.38, 0))

# Floor-edge services and raised kick plates frame a clear central walkway.
for side in [-1, 1]:
    box('Deck service sill', (side * 3.95, 0, .13), (.25, 13.8, .25), metal, .035)
    box('Recessed path-light channel', (side * 3.79, 0, .15), (.04, 13.45, .09), rubber, .01)
    box('Soft blue path-light diffuser', (side * 3.765, 0, .16), (.018, 13.1, .025), cyan, .006)
    # Inlaid walkway rails are visibly embedded in the deck, not floating lines.
    box('Deck inlaid champagne guide', (side * 1.05, 0, .004), (.022, 13.7, .006), bronze, .002)

# Forward bulkhead / pressure door. Existing wall remains underneath intact.
# Front faces point into the cabin (+Y); text is rotated to match.
front = (math.pi / 2, 0, math.pi)
box('Door reveal', (0, -7.03, 1.52), (2.24, .12, 3.05), rubber, .14)
box('Pressure door frame', (0, -6.94, 1.52), (2.10, .15, 2.92), metal, .13)
box('Inset pressure gasket', (0, -6.84, 1.50), (1.89, .08, 2.69), rubber, .10)
for side in [-1, 1]:
    box('Split pocket door', (side * .465, -6.78, 1.50), (.915, .09, 2.60), ceramic, .075)
    box('Door inset lower protection', (side * .465, -6.719, .53), (.79, .018, .43), metal, .02)
    box('Door grip recess', (side * .16, -6.72, 1.30), (.09, .025, .34), rubber, .025)
    box('Door grip', (side * .16, -6.695, 1.30), (.022, .035, .23), bronze, .01)
    box('Door light recess', (side * 1.11, -6.9, 1.56), (.075, .09, 2.16), rubber, .018)
    box('Door warm perimeter diffuser', (side * 1.11, -6.84, 1.56), (.025, .025, 2.08), white, .008)
text('Pressure door number', '01', (0, -6.70, 2.30), .32, metal, front)
text('Bulkhead header', 'OBSERVATION', (0, -6.94, 3.28), .23, ink, front)
text('Bulkhead subheader', 'META THEATRE   /   CREW DECK', (0, -6.94, 3.04), .070, ink, front)

# Service cabinets and visible louvres supply useful scale and manufacturing
# detail. Separate faces have spacing; no coplanar decal sheets are used.
for side in [-1, 1]:
    xx = side * 2.55
    box('Equipment bay surround', (xx, -7.00, 1.23), (1.35, .22, 2.42), rubber, .075)
    box('Equipment bay door', (xx, -6.84, 1.22), (1.24, .11, 2.27), ceramic, .06)
    box('Vent shadow well', (xx, -6.772, 1.55), (.92, .025, .72), rubber, .035)
    for z in np.linspace(1.27, 1.85, 10):
        box('Sloped ventilation louvre', (xx, -6.738, float(z)), (.84, .055, .021),
            metal, .007, (math.radians(-18), 0, 0))
    box('Inspection plate', (xx, -6.77, .67), (.88, .027, .62), metal, .03)
    text('Service panel stencil', 'LIFE SUPPORT' if side < 0 else 'POWER / DATA',
         (xx, -6.745, 2.12), .075, metal, front)
    text('Inspection stencil', 'SERVICE ACCESS', (xx, -6.748, .69), .058, ink, front)
    for dx in [-.51, .51]:
        for z in [.22, 2.23]:
            fastener((xx + dx, -6.767, z))
    box('Panel status slit', (xx, -6.75, .95), (.26, .028, .014), cyan, .004)

# Low side consoles keep the panoramic openings and screen cone unobstructed.
for side in [-1, 1]:
    xx = side * 3.33
    for yy in [-4.5, 3.9]:
        box('Console recessed plinth', (xx, yy, .13), (.96, 2.0, .25), rubber, .04)
        box('Ceramic console body', (xx, yy, .45), (1.02, 2.18, .62), ceramic, .09)
        box('Console top shadow seam', (xx, yy, .78), (1.08, 2.23, .055), rubber, .023)
        box('Machined console surface', (xx, yy, .815), (1.10, 2.25, .045), metal, .022)
        for dy in [-.65, .0, .65]:
            box('Console inset drawer', (xx - side * .523, yy + dy, .47),
                (.025, .59, .40), frame, .018)
            box('Drawer finger pull', (xx - side * .545, yy + dy, .62),
                (.025, .24, .028), rubber, .01)
        box('Instrument screen bezel', (xx, yy - .4, .86), (.72, .87, .055), rubber, .04)
        box('Instrument screen glass', (xx, yy - .4, .893), (.65, .80, .013), display, .025)
        # Modest display marks; the geometry is decoration, not live telemetry.
        for k in range(5):
            box('Instrument tick', (xx - .22 + k * .11, yy - .42, .902),
                (.026, .13 + k * .055, .004), cyan, .002)
        text('Instrument label', 'CABIN SYSTEMS', (xx, yy - .72, .903), .045, ink,
             (0, 0, 0))
    # Woven side bench: four cushions, a shaped back and a raised metal base.
    yy = .35
    box('Bench recessed foot', (xx, yy, .12), (.78, 2.9, .23), rubber, .055)
    box('Bench shell', (xx, yy, .37), (.99, 3.04, .27), frame, .08)
    for index, dy in enumerate([-1.10, -.367, .367, 1.10]):
        wear = [.16, .80, .58, .24][index if side < 0 else 3 - index]
        settled_cushion((xx - side * .025, yy + dy, .59), side, wear, index)
        box('Upholstered back cushion', (xx + side * .40, yy + dy, .97),
            (.19, .71, .73), fabric, .065, (0, side * -.12, 0))
        # Continuous piping is geometry so it survives the native importer.
        welt = []
        for cx, cy, start in [(.395, .280, 0), (-.395, .280, 90),
                              (-.395, -.280, 180), (.395, -.280, 270)]:
            for angle in np.linspace(start, start + 90, 9):
                a = math.radians(angle)
                welt.append((xx - side * .025 + cx + .075 * math.cos(a),
                             yy + dy + cy + .075 * math.sin(a), .618))
        welt.append(welt[0])
        tube('Seat cushion stitched welt', welt, .0025, fabric, 1)
    for dy in [-1.49, 1.49]:
        box('Bench end cheek', (xx, yy + dy, .63), (1.04, .12, .67), metal, .05)
        box('Bench padded arm', (xx - side * .03, yy + dy, .99), (.92, .16, .08), fabric, .035)

# Compact ceiling grille with real slats in the rear viewing hemisphere.
for yy in [-4.6, 4.6]:
    box('Crown ventilation recess', (0, yy, 4.58), (1.2, 1.3, .11), rubber, .07)
    for xx in np.linspace(-.48, .48, 13):
        box('Crown ventilation fin', (float(xx), yy, 4.50), (.025, 1.1, .07), metal, .009)


def area(name, loc, target, energy, color, size, size_y=None):
    data = bpy.data.lights.new(name, 'AREA')
    data.energy = energy
    data.color = color
    data.shape = 'RECTANGLE'
    data.size = size
    data.size_y = size_y or size
    obj = bpy.data.objects.new(name, data)
    scene.collection.objects.link(obj)
    obj.location = loc
    obj.rotation_euler = (Vector(target) - obj.location).to_track_quat('-Z', 'Y').to_euler()
    obj['preview_only'] = True
    return obj


# Preview lighting corresponds to visible fixtures. Native GLB contains emissive
# diffusers and PBR materials; these Cycles lamps are intentionally not exported.
for side in [-1, 1]:
    for yy in [-4.8, -1.6, 1.6, 4.8]:
        area('Practical ceiling illumination', (side * 1.65, yy, 4.0),
             (side * .7, yy, 0), 150, (1, .86, .69), .25, 3.0)
    area('Cool aperture fill', (side * 4.1, .5, 2.3), (0, .5, 1.2),
         240, (.52, .72, 1.0), 6, 2)
area('Bulkhead fill', (0, -5.7, 3.5), (0, -7, 1.5), 140, (1, .84, .64), 3, .4)
world = bpy.data.worlds.new('Cabin review - neutral space')
world.use_nodes = True
world.node_tree.nodes['Background'].inputs['Color'].default_value = (.055, .08, .12, 1)
world.node_tree.nodes['Background'].inputs['Strength'].default_value = .18
scene.world = world

camera = bpy.data.objects.new('Cabin review camera', bpy.data.cameras.new('Cabin review lens'))
scene.collection.objects.link(camera)
camera.location = (0, 2.8, 1.6)
camera.rotation_euler = (Vector((0, -5, 1.9)) - camera.location).to_track_quat('-Z', 'Y').to_euler()
camera.data.lens = 20
camera.data.clip_start = .05
camera.data.clip_end = 100
camera['preview_only'] = True
scene.camera = camera
scene.render.engine = 'CYCLES'
scene.cycles.samples = 96
scene.cycles.use_denoising = True
scene.render.resolution_x = 1920
scene.render.resolution_y = 1234
scene.render.resolution_percentage = 100
scene.view_settings.view_transform = 'AgX'
scene.view_settings.look = 'AgX - Medium High Contrast'
scene['native_contract'] = 'hull.glb, identity transform; seat at origin; screen +Z 3.2m'
scene['preview_note'] = 'Blender authoring preview, not a headset capture'
scene['source_shell_triangles_retained'] = original_triangles
scene['asset_provenance'] = 'Original procedural additions; existing hull retained; no downloaded assets'
bpy.ops.wm.save_as_mainfile(filepath=str(ART / 'blender/cabin_realism_20261114.blend'))
report = {'scene': scene.name, 'source_shell_triangles_retained': original_triangles,
          'objects': len(scene.objects), 'textures': [str(p.relative_to(ROOT)) for p in TEX.glob('*.png')],
          'camera_blender': list(camera.location), 'headset_verified': False}
(ART / 'validation/cabin-20261114/authoring.json').write_text(json.dumps(report, indent=2) + '\n')
result = report
