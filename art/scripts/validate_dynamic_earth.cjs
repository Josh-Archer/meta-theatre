const fs=require('fs');
const path=require('path');
const validator=require('/tmp/meta-theatre-gltf-validation/node_modules/gltf-validator');
const root=path.resolve(__dirname,'../..');
function accessor(doc,bin,index) {
 const a=doc.accessors[index],v=doc.bufferViews[a.bufferView];
 const components={SCALAR:1,VEC2:2,VEC3:3,VEC4:4}[a.type];
 const types={5123:[2,'readUInt16LE'],5125:[4,'readUInt32LE'],5126:[4,'readFloatLE']};
 const [size,read]=types[a.componentType]; const stride=v.byteStride||components*size;
 return Array.from({length:a.count},(_,i)=>Array.from({length:components},(_,j)=>bin[read]((v.byteOffset||0)+(a.byteOffset||0)+i*stride+j*size)));
}
(async()=>{
 const results={};
 for(const part of ['surface','clouds','atmosphere']) {
  const filename=`earth_dynamic_${part}.glb`,bytes=fs.readFileSync(path.join(root,'art/exports/earth_dynamic',filename));
  const jsonSize=bytes.readUInt32LE(12),doc=JSON.parse(bytes.subarray(20,20+jsonSize));
  const binary=bytes.subarray(28+jsonSize); const primitive=doc.meshes[0].primitives[0];
  const pos=accessor(doc,binary,primitive.attributes.POSITION),uv=accessor(doc,binary,primitive.attributes.TEXCOORD_0),indices=accessor(doc,binary,primitive.indices).flat();
  let totalWeight=0,u=0,v=0;
  // The center ray lies on the equatorial row; interpolate its two neighboring vertices.
  const equator=pos.map((p,i)=>({p,uv:uv[i]})).filter(a=>Math.abs(a.p[1])<1e-6&&a.p[2]>.98);
  const left=equator.filter(a=>a.p[0]<=0).sort((a,b)=>b.p[0]-a.p[0])[0];
  const right=equator.filter(a=>a.p[0]>=0).sort((a,b)=>a.p[0]-b.p[0])[0];
  const t=-left.p[0]/(right.p[0]-left.p[0]);
  const centerUV=[left.uv[0]*(1-t)+right.uv[0]*t,left.uv[1]*(1-t)+right.uv[1]*t];
  const north=pos.map((p,i)=>({p,uv:uv[i]})).sort((a,b)=>b.p[1]-a.p[1])[0];
  const report=await validator.validateBytes(new Uint8Array(bytes),{uri:filename});
  const checks={validatorNoErrors:report.issues.numErrors===0,oneMeshOneMaterial:doc.meshes.length===1&&doc.materials.length===1,identityNode:doc.nodes.length===1&&!doc.nodes[0].rotation&&!doc.nodes[0].translation&&!doc.nodes[0].scale,centerLongitude100W:Math.abs(centerUV[0]-2/9)<1e-5,centerEquator:Math.abs(centerUV[1]-.5)<1e-6,northUp:north.p[1]>.99&&Math.abs(north.uv[1])<1e-6,noBakedVertexColors:primitive.attributes.COLOR_0===undefined,noEmbeddedTextures:!doc.images};
  results[part]={bytes:bytes.length,triangles:indices.length/3,centerUV,checks,validator:report};
  console.log(part,JSON.stringify({bytes:bytes.length,triangles:indices.length/3,centerUV,checks}));
  if(Object.values(checks).some(v=>!v))process.exitCode=1;
 }
 fs.writeFileSync(path.join(root,'art/validation/earth_dynamic_gltf_report.json'),JSON.stringify(results,null,2));
})();
