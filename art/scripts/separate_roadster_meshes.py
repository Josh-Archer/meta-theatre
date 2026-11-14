"""Give each static Roadster material primitive its own glTF mesh.

The Quest importer corrupts this asset when primitives with different vertex
layouts share a mesh. Preserve the complete binary payload and primitive data;
only replace the single mesh node with a parent and one child per primitive.
This deliberately accepts only the Roadster's single-node, static export.
"""
import argparse
import copy
import json
import struct
from pathlib import Path


def separate_meshes(data):
    magic, version, length = struct.unpack_from("<III", data)
    if (magic, version, length) != (0x46546C67, 2, len(data)):
        raise ValueError("Expected a complete GLB 2.0 file")
    size, kind = struct.unpack_from("<II", data, 12)
    if kind != 0x4E4F534A or size % 4:
        raise ValueError("Expected an aligned JSON chunk")
    original = json.loads(data[20:20 + size])
    if (len(original.get("meshes", [])) != 1 or
            len(original.get("nodes", [])) != 1 or
            original["nodes"][0].get("mesh") != 0 or
            original["nodes"][0].get("children") or
            original.get("skins") or original.get("animations")):
        raise ValueError("Expected one static mesh on one root node")
    mesh = original["meshes"][0]
    if mesh.keys() - {"name", "primitives"} or any(
            "targets" in p for p in mesh["primitives"]):
        raise ValueError("Mesh metadata and morph targets require explicit handling")
    document = copy.deepcopy(original)
    document["meshes"] = []
    for i, primitive in enumerate(mesh["primitives"]):
        document["meshes"].append({
            "name": f"Roadster material {i}", "primitives": [primitive]})
        document["nodes"].append({"name": f"Roadster part {i}", "mesh": i})
    root = document["nodes"][0]
    del root["mesh"]
    root["children"] = list(range(1, len(document["nodes"])))
    # All transforms stay on the original parent. Geometry, textures, materials,
    # accessor formats, indices and scene references are unchanged.
    encoded = json.dumps(document, separators=(",", ":")).encode()
    encoded += b" " * (-len(encoded) % 4)
    remaining_chunks = data[20 + size:]
    return (struct.pack("<III", magic, version, 20 + len(encoded) + len(remaining_chunks)) +
            struct.pack("<II", len(encoded), kind) + encoded + remaining_chunks)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = separate_meshes(args.source.read_bytes())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(result)
    print(f"Wrote {args.output}: {len(result)} bytes; binary payload unchanged")
