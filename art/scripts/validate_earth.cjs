// npm install --prefix /tmp/meta-theatre-gltf-validation gltf-validator
const fs = require('fs');
const path = require('path');
const validator = require('/tmp/meta-theatre-gltf-validation/node_modules/gltf-validator');
const root = path.resolve(__dirname, '../..');
(async () => {
  const reports = {};
  for (const phase of ['day', 'twilight', 'night']) {
    const file = path.join(root, 'art/exports/earth', `earth_${phase}.glb`);
    const bytes = fs.readFileSync(file);
    const report = await validator.validateBytes(new Uint8Array(bytes), {uri: path.basename(file)});
    const doc = JSON.parse(bytes.subarray(20, 20 + bytes.readUInt32LE(12)).toString());
    const primitives = doc.meshes.flatMap(mesh => mesh.primitives);
    const triangles = primitives.reduce((n,p) => n + doc.accessors[p.indices].count / 3, 0);
    const checks = {
      only_three_earth_nodes: doc.nodes.length === 3 && doc.nodes.every(n => n.name.startsWith(`Earth_${phase}_`)),
      unit_radius: primitives.every(p => doc.accessors[p.attributes.POSITION].max.every(v => v <= 1.013)),
      all_unlit: doc.materials.every(m => m.extensions?.KHR_materials_unlit),
      two_transparent_shells: doc.materials.filter(m => m.alphaMode === 'BLEND').length === 2,
      no_animation: !doc.animations,
      geometry_budget: triangles < 45000,
      size_budget: bytes.length < 12 * 1024 * 1024,
      validator_no_errors: report.issues.numErrors === 0,
    };
    reports[phase] = {bytes:bytes.length, triangles, primitives:primitives.length, checks, validator:report};
    console.log(phase, JSON.stringify({bytes:bytes.length,triangles,checks,issues:report.issues}));
    if (Object.values(checks).some(v => !v)) process.exitCode = 1;
  }
  fs.writeFileSync(path.join(root, 'art/validation/earth_gltf_report.json'), JSON.stringify(reports,null,2));
})();
