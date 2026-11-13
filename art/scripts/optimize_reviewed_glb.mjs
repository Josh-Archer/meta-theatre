/** Lossless cleanup after Blender export; dependencies installed outside the app.
 * Usage: node art/scripts/optimize_reviewed_glb.mjs /path/to/gltf-tools/node_modules asset.glb ...
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
const [dependencies, ...files] = process.argv.slice(2);
const pkg = name => pathToFileURL(path.resolve(dependencies, '@gltf-transform', name, 'dist/index.js')).href;
const { NodeIO } = await import(pkg('core'));
const { ALL_EXTENSIONS } = await import(pkg('extensions'));
const { weld, prune } = await import(pkg('functions'));
const io = new NodeIO().registerExtensions(ALL_EXTENSIONS);
function fingerprint(document) {
  const meshes = document.getRoot().listMeshes().map(mesh => mesh.listPrimitives().map(primitive => {
    const indices = primitive.getIndices().getArray();
    return primitive.listSemantics().sort().map(semantic => {
      const accessor = primitive.getAttribute(semantic), array = accessor.getArray();
      const stride = accessor.getElementSize() * array.BYTES_PER_ELEMENT;
      const bytes = Buffer.from(array.buffer, array.byteOffset, array.byteLength);
      const hash = createHash('sha256');
      for (const index of indices) hash.update(bytes.subarray(index*stride, (index+1)*stride));
      return [semantic, indices.length, hash.digest('hex')];
    });
  }));
  const images = document.getRoot().listTextures().map(texture =>
    createHash('sha256').update(texture.getImage()).digest('hex'));
  return { meshes, images };
}
for (const file of files) {
  const before = await fs.stat(file);
  const document = await io.read(file);
  // Only stock PBR assets: custom Earth shaders consume attributes independently.
  for (const mesh of document.getRoot().listMeshes()) for (const primitive of mesh.listPrimitives()) {
    const material = primitive.getMaterial();
    if (!material) continue;
    const extensions = material.listExtensions();
    const normalNeeded = !!material.getNormalTexture() || extensions.some(e => e.getClearcoatNormalTexture?.());
    if (!normalNeeded) primitive.setAttribute('TANGENT', null);
    const textured = !!(material.getBaseColorTexture() || material.getNormalTexture() ||
      material.getMetallicRoughnessTexture() || material.getEmissiveTexture() || material.getOcclusionTexture()) ||
      extensions.some(e => e.listTextureInfo?.().length || e.getClearcoatTexture?.() || e.getClearcoatRoughnessTexture?.() || e.getClearcoatNormalTexture?.());
    if (!textured) primitive.setAttribute('TEXCOORD_0', null);
  }
  // weld merges only bitwise-identical vertices; no simplification or quantization.
  const original = fingerprint(document);
  await document.transform(weld(), prune({ keepAttributes: true }));
  assert.deepEqual(fingerprint(document), original, 'Indexed vertex values or textures changed');
  const temporary = file + '.pending.glb';
  await io.write(temporary, document);
  assert.deepEqual(fingerprint(await io.read(temporary)), original, 'Round-trip changed data');
  await fs.rename(temporary, file);
  console.log(JSON.stringify({ file, beforeBytes: before.size, afterBytes: (await fs.stat(file)).size }));
}
