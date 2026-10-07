const fs = require('fs');
const path = require('path');
const validator = require(process.env.GLTF_VALIDATOR_MODULE || '/tmp/meta-theatre-gltf-validation/node_modules/gltf-validator');
const root = path.resolve(__dirname, '../..');
function readAccessor(doc, bin, index) {
  const a=doc.accessors[index], v=doc.bufferViews[a.bufferView];
  const n={SCALAR:1,VEC2:2,VEC3:3,VEC4:4}[a.type];
  const [size, method]={5123:[2,'readUInt16LE'],5125:[4,'readUInt32LE'],5126:[4,'readFloatLE']}[a.componentType];
  return Array.from({length:a.count},(_,i)=>Array.from({length:n},(_,j)=>bin[method]((v.byteOffset||0)+(a.byteOffset||0)+i*(v.byteStride||n*size)+j*size)));
}
(async()=>{
  const report={};
  for (const part of ['body','corona','stars','rings']) {
    const file=`solar_${part}.glb`, bytes=fs.readFileSync(path.join(root,'art/exports/solar',file));
    const length=bytes.readUInt32LE(12), doc=JSON.parse(bytes.subarray(20,20+length)), bin=bytes.subarray(28+length);
    const p=doc.meshes[0].primitives[0], pos=readAccessor(doc,bin,p.attributes.POSITION), norms=readAccessor(doc,bin,p.attributes.NORMAL), uv=readAccessor(doc,bin,p.attributes.TEXCOORD_0);
    const indices=readAccessor(doc,bin,p.indices).flat();
    const validation=await validator.validateBytes(new Uint8Array(bytes),{uri:file});
    const checks={zeroErrors:validation.issues.numErrors===0,zeroWarnings:validation.issues.numWarnings===0,
      oneMeshOneMaterial:doc.meshes.length===1&&doc.materials.length===1,identityNode:doc.nodes.length===1&&!doc.nodes[0].rotation&&!doc.nodes[0].translation&&!doc.nodes[0].scale,noEmbeddedImages:!doc.images};
    const radii=pos.map(p=>Math.hypot(...p));
    if(part==='rings') {
      checks.planeXZ=pos.every(p=>Math.abs(p[1])<1e-6);
      checks.physicalBounds=Math.abs(Math.min(...radii)-74500/58232)<1e-5&&Math.abs(Math.max(...radii)-136780/58232)<1e-5;
      checks.bothFaces=norms.some(n=>n[1]>.99)&&norms.some(n=>n[1]<-.99);
      checks.radialUV=pos.every((p,i)=>Math.abs(uv[i][0]-(radii[i]-74500/58232)/(136780/58232-74500/58232))<1e-5);
    } else {
      const radius=part==='corona'?1.08:1;
      checks.radius=radii.every(r=>Math.abs(r-radius)<1e-5);
      checks.normals=pos.every((p,i)=>(p.reduce((s,x,j)=>s+x*norms[i][j],0))*(part==='stars'?-1:1)>radius*.995);
      const front=pos.findIndex(p=>Math.abs(p[0])<1e-6&&Math.abs(p[1])<1e-6&&p[2]>.99);
      checks.frontUV=front>=0&&Math.abs(uv[front][0]-.5)<1e-6&&Math.abs(uv[front][1]-.5)<1e-6;
      checks.northUV=pos.every((p,i)=>p[1]<radius-.00001||Math.abs(uv[i][1])<1e-6);
    }
    report[part]={file,bytes:bytes.length,triangles:indices.length/3,checks,validator:validation};
    if(Object.values(checks).some(x=>!x)) process.exitCode=1;
    console.log(part,JSON.stringify({triangles:indices.length/3,checks}));
  }
  fs.writeFileSync(path.join(root,'art/validation/solar_gltf_report.json'),JSON.stringify(report,null,2)+'\n');
})();
