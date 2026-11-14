// Validate published native assets and preserve every Roadster accessor byte.
const fs=require('fs'),path=require('path'),validator=require('gltf-validator');
const root=path.resolve(__dirname,'../..'),out=path.join(root,'art/validation/starship-20261113');
function glb(p){const b=fs.readFileSync(p),n=b.readUInt32LE(12);return {b,d:JSON.parse(b.subarray(20,20+n)),bin:b.subarray(28+n)};}
function accessor(g,i){const a=g.d.accessors[i],v=g.d.bufferViews[a.bufferView];return g.bin.subarray(v.byteOffset||0,(v.byteOffset||0)+v.byteLength);}
(async()=>{
 const report={};for(const name of ['starship','super_heavy','starship_pad','starship_plume','space_roadster']){
  const g=glb(path.join(root,'app/src/main/assets',name+'.glb'));
  const issues=(await validator.validateBytes(new Uint8Array(g.b))).issues;
  const primitives=g.d.meshes.flatMap(m=>m.primitives);
  report[name]={bytes:g.b.length,triangles:primitives.reduce((n,p)=>n+g.d.accessors[p.indices].count/3,0),primitives:primitives.length,images:(g.d.images||[]).length,issues};
  if(issues.numErrors||issues.numWarnings)throw Error(name+' invalid');
  const budget={starship:[27000,4],super_heavy:[87000,3],starship_pad:[8100,3],starship_plume:[256,1],space_roadster:[145000,10]}[name];
  if(report[name].triangles>budget[0]||primitives.length>budget[1])throw Error(name+' exceeds runtime budget');
  if(g.d.meshes.length!==1||g.d.nodes.length!==1)throw Error(name+' unexpected exported object');
  if(!g.b.equals(fs.readFileSync(path.join(root,'art/exports/starship_20261113',name+'.glb'))))throw Error(name+' runtime differs');
 }
 const a=glb(path.join(root,'art/exports/realism_20261111/space_roadster.glb')),b=glb(path.join(root,'app/src/main/assets/space_roadster.glb'));
 if(JSON.stringify(a.d.accessors)!==JSON.stringify(b.d.accessors)||JSON.stringify(a.d.meshes)!==JSON.stringify(b.d.meshes)||JSON.stringify(a.d.nodes)!==JSON.stringify(b.d.nodes))throw Error('Roadster geometry schema changed');
 for(let i=0;i<a.d.accessors.length;i++)if(!accessor(a,i).equals(accessor(b,i)))throw Error('Roadster geometry bytes changed at '+i);
 report.roadsterGeometryExact=true;
 fs.mkdirSync(out,{recursive:true});fs.writeFileSync(path.join(out,'asset-validation.json'),JSON.stringify(report,null,2));
 console.log(JSON.stringify(Object.fromEntries(Object.entries(report).map(([k,v])=>[k,typeof v==='object'?{bytes:v.bytes,triangles:v.triangles,draws:v.primitives,errors:v.issues.numErrors,warnings:v.issues.numWarnings}:v])),null,2));
})().catch(e=>{console.error(e);process.exitCode=1;});
