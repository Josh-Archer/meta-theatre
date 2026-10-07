const fs=require('fs'),path=require('path');
const validator=require('/tmp/meta-theatre-gltf-validation/node_modules/gltf-validator');
const root=path.resolve(__dirname,'../..');
(async()=>{
 const reports={};
 for(const name of ['iss','space_roadster']) {
  const bytes=fs.readFileSync(path.join(root,'art/exports/flybys',name+'.glb'));
  const json=JSON.parse(bytes.subarray(20,20+bytes.readUInt32LE(12)).toString());
  const report=await validator.validateBytes(new Uint8Array(bytes),{uri:name+'.glb'});
  const primitives=json.meshes.flatMap(x=>x.primitives);
  const triangles=primitives.reduce((s,p)=>s+json.accessors[p.indices].count/3,0);
  reports[name]={bytes:bytes.length,triangles,drawCalls:primitives.length,materials:json.materials.length,images:json.images?.length||0,requiredExtensions:json.extensionsRequired||[],issues:report.issues};
  if(report.issues.numErrors || json.extensionsRequired?.length || triangles>45000 || primitives.length>8)process.exitCode=1;
 }
 fs.writeFileSync(path.join(root,'art/validation/flybys/glb-validation.json'),JSON.stringify(reports,null,2));
 console.log(JSON.stringify(reports,null,2));
})();
