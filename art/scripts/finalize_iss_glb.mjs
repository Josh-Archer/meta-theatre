/** Preserve production normals through Blender's normal quantization, and build
 * MikkTSpace tangents against those exact normals and the new material UVs.
 * Usage: node art/scripts/finalize_iss_glb.mjs /path/to/art/node_modules
 * Run after refine_iss_materials.py, before optimize_reviewed_glb.mjs.
 */
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const dependencies = path.resolve(process.argv[2]);
const { NodeIO } = await import(pathToFileURL(path.join(dependencies,'@gltf-transform/core/dist/index.js')));
const { ALL_EXTENSIONS } = await import(pathToFileURL(path.join(dependencies,'@gltf-transform/extensions/dist/index.js')));
const { prune } = await import(pathToFileURL(path.join(dependencies,'@gltf-transform/functions/dist/index.js')));
const require = createRequire(import.meta.url);
const { generateTangents } = require(path.join(dependencies,'mikktspace'));
const folder = fileURLToPath(new URL('../exports/iss_20261112/',import.meta.url));
const io = new NodeIO().registerExtensions(ALL_EXTENSIONS);
const before = await io.read(path.join(folder,'iss_before.glb'));
const document = await io.read(path.join(folder,'iss.glb'));
const original = before.getRoot().listMeshes()[0].listPrimitives();
const revised = document.getRoot().listMeshes()[0].listPrimitives();
assert.equal(original.length,4); assert.equal(revised.length,4);
const buffer = document.getRoot().listBuffers()[0];
function expanded(primitive,semantic) {
  const a = primitive.getAttribute(semantic), values = a.getArray(), width = a.getElementSize();
  const indices = primitive.getIndices().getArray();
  const result = new Float32Array(indices.length*width);
  for (let i=0;i<indices.length;i++) for(let c=0;c<width;c++) result[i*width+c]=values[indices[i]*width+c];
  return result;
}
let fallbackCount=0;
for(let i=0;i<4;i++) {
  const base=original[i], next=revised[i];
  const positions=expanded(base,'POSITION'), normals=expanded(base,'NORMAL');
  assert.deepEqual(expanded(next,'POSITION'),positions,'Triangle order or positions changed');
  const uv=i===3 ? expanded(base,'TEXCOORD_0') : expanded(next,'TEXCOORD_0');
  for(const semantic of next.listSemantics()) next.setAttribute(semantic,null);
  function set(semantic,values,type) {
    next.setAttribute(semantic,document.createAccessor().setBuffer(buffer).setType(type).setArray(values));
  }
  set('POSITION',positions,'VEC3'); set('NORMAL',normals,'VEC3'); set('TEXCOORD_0',uv,'VEC2');
  if(next.getMaterial().getNormalTexture()) {
    const tangents=generateTangents(positions,normals,uv);
    for(let v=0;v<positions.length/3;v++) {
      const t=v*4,n=v*3;
      // mikktspace-wasm's UV convention is opposite glTF's, as in the
      // glTF-Transform tangents implementation. Reverse handedness once.
      tangents[t+3]*=-1;
      const normal=normals.subarray(n,n+3);
      // Degenerate faces can also receive a unit default tangent parallel to
      // their normal. Project the generated basis onto the exact normal plane.
      const dot=tangents[t]*normal[0]+tangents[t+1]*normal[1]+tangents[t+2]*normal[2];
      const normalSquared=normal[0]**2+normal[1]**2+normal[2]**2;
      for(let c=0;c<3;c++) tangents[t+c]-=normal[c]*dot/normalSquared;
      const length=Math.hypot(...tangents.subarray(t,t+3));
      if(!Number.isFinite(length)||length<1e-5) {
        const axis=[0,0,0]; axis[normal.reduce((best,value,index)=>Math.abs(value)<Math.abs(normal[best])?index:best,0)]=1;
        const cross=[axis[1]*normal[2]-axis[2]*normal[1],axis[2]*normal[0]-axis[0]*normal[2],axis[0]*normal[1]-axis[1]*normal[0]];
        const size=Math.hypot(...cross);
        for(let c=0;c<3;c++) tangents[t+c]=cross[c]/size;
        tangents[t+3]=1; fallbackCount++;
      } else for(let c=0;c<3;c++) tangents[t+c]/=length;
    }
    set('TANGENT',tangents,'VEC4');
  }
  // Indexed triangle order stays explicit for the audited lossless weld pass.
  next.setIndices(document.createAccessor().setBuffer(buffer).setType('SCALAR')
    .setArray(Uint32Array.from({length:positions.length/3},(_,index)=>index)));
}
await document.transform(prune({keepAttributes:true}));
await io.write(path.join(folder,'iss.glb'),document);
console.log(JSON.stringify({productionPositionsAndNormals:'bit-identical per indexed corner',fallbackTangents:fallbackCount}));
