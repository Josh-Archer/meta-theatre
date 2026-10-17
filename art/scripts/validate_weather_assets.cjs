const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const crypto = require('crypto');
const validator = require('gltf-validator');
const root = path.resolve(__dirname, '../..');
(async () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(root, 'art/exports/weather/manifest.json')));
  let triangles = 0;
  for (const model of manifest.meshes) {
    const bytes = fs.readFileSync(path.join(root, `art/exports/weather/${model.name}.glb`));
    const document = JSON.parse(bytes.subarray(20, 20 + bytes.readUInt32LE(12)));
    const report = await validator.validateBytes(new Uint8Array(bytes), { uri: `${model.name}.glb` });
    assert.equal(report.issues.numErrors, 0);
    assert.equal(report.issues.numWarnings, 0);
    assert.equal(document.meshes.length, 1);
    assert.equal(document.nodes.length, 1);
    assert.equal(document.nodes[0].name, `WX_${model.name}`);
    const count = document.accessors[document.meshes[0].primitives[0].indices].count / 3;
    assert.equal(count, model.triangles);
    triangles += count;
  }
  const source = fs.readFileSync(path.join(root, 'art/textures/weather/cloud_patterns.png'));
  const runtime = fs.readFileSync(path.join(root, 'app/src/main/assets/weather/cloud_patterns.png'));
  assert.equal(source.readUInt32BE(16), 4096);
  assert.equal(source.readUInt32BE(20), 2048);
  assert.equal(crypto.createHash('sha256').update(source).digest('hex'), crypto.createHash('sha256').update(runtime).digest('hex'));
  console.log(JSON.stringify({ models: manifest.meshes.length, triangles, errors: 0, warnings: 0, atlas: manifest.atlas, runtimeMatchesSource: true }));
})().catch(error => { console.error(error.message); process.exitCode = 1; });
