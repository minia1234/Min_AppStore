import assert from 'node:assert/strict';
import test from 'node:test';
import worker, { parseRange } from '../src/index.ts';
const version = {versionCode:2,size:10,githubUrl:'https://github.com/owner/app/releases/download/v2/a.apk',storageKey:'apks/a/2/hash.apk'};
const catalog = {schemaVersion:1,apps:[{packageName:'dev.test.app',versions:[version]}]};
function env(present = true) { return {STORE:{get:async (key:string, options?:any) => key === 'catalog.json' ? {httpEtag:'"cat"',body:JSON.stringify(catalog),json:async()=>catalog} : present ? {size:10,httpEtag:'"apk"',body:'345',range:options?.range} : null, head:async()=>present?{size:10,httpEtag:'"apk"'}:null}} as any; }
test('catalog ETag supports 304 and weak validators',async()=>{
 const first=await worker.fetch(new Request('https://store.test/api/catalog'),env()); assert.equal(first.headers.get('etag'),'"cat"');
 const cached=await worker.fetch(new Request('https://store.test/api/catalog',{headers:{'If-None-Match':'W/"cat"'}}),env());assert.equal(cached.status,304);
});
test('releases wrap versions and unknown app is 404',async()=>{assert.equal((await (await worker.fetch(new Request('https://store.test/api/apps/dev.test.app/releases'),env())).json() as any).versions.length,1);assert.equal((await worker.fetch(new Request('https://store.test/api/apps/no.app/releases'),env())).status,404);});
test('single ranges include suffix and bounds; reject multi or unsatisfiable',()=>{assert.deepEqual(parseRange('bytes=3-5',10),{offset:3,length:3});assert.deepEqual(parseRange('bytes=-3',10),{offset:7,length:3});assert.deepEqual(parseRange('bytes=8-',10),{offset:8,length:2});assert.equal(parseRange('bytes=10-',10),null);assert.equal(parseRange('bytes=0-1,3-4',10),null);});
test('APK ranged GET and HEAD return HTTP range headers',async()=>{for(const method of ['GET','HEAD']) {const r=await worker.fetch(new Request('https://store.test/downloads/dev.test.app/2.apk',{method,headers:{Range:'bytes=3-5'}}),env());assert.equal(r.status,206);assert.equal(r.headers.get('content-range'),'bytes 3-5/10');assert.equal(r.headers.get('content-length'),'3');if(method==='HEAD')assert.equal(await r.text(),'');}});
test('missing R2 APK redirects to verified GitHub URL',async()=>{const r=await worker.fetch(new Request('https://store.test/downloads/dev.test.app/2.apk'),env(false));assert.equal(r.status,302);assert.equal(r.headers.get('location'),version.githubUrl);});
test('bad range returns 416 and unsupported methods return 405',async()=>{assert.equal((await worker.fetch(new Request('https://store.test/downloads/dev.test.app/2.apk',{headers:{Range:'bytes=20-'}}),env())).status,416);assert.equal((await worker.fetch(new Request('https://store.test/api/catalog',{method:'POST'}),env())).status,405);});
test('media GET and HEAD stream WebP with cache ETag and conditional 304',async()=>{
 const digest='a'.repeat(64); const fixture=env();
 const mediaCatalog={...catalog,apps:[{...catalog.apps[0],mediaSources:{[digest]:'https://raw.githubusercontent.com/owner/app/main/icon.webp'}}]};
 const get=fixture.STORE.get;
 fixture.STORE.get=async(key:string,options?:any)=>key==='catalog.json'?{json:async()=>mediaCatalog}:get(key,options);
 for(const method of ['GET','HEAD']) {
  const result=await worker.fetch(new Request(`https://store.test/media/${digest}.webp`,{method}),fixture);
  assert.equal(result.status,200);assert.equal(result.headers.get('content-type'),'image/webp');assert.equal(result.headers.get('etag'),'"apk"');
  if(method==='HEAD')assert.equal(await result.text(),'');
 }
 assert.equal((await worker.fetch(new Request(`https://store.test/media/${digest}.webp`,{headers:{'If-None-Match':'"apk"'}}),fixture)).status,304);
});
test('missing referenced media redirects to source; unknown hashes are 404',async()=>{
 const digest='a'.repeat(64);const fixture=env(false);const source='https://raw.githubusercontent.com/owner/app/main/icon.webp';
 fixture.STORE.get=async(key:string)=>key==='catalog.json'?{json:async()=>({apps:[{repository:'owner/app',mediaSources:{[digest]:source}}]})}:null;
 const result=await worker.fetch(new Request(`https://store.test/media/${digest}.webp`),fixture);
 assert.equal(result.status,302);assert.equal(result.headers.get('location'),source);
 assert.equal((await worker.fetch(new Request(`https://store.test/media/${'b'.repeat(64)}.webp`),fixture)).status,404);
});

test('disabled apps disappear from catalog and cannot use releases downloads or media',async()=>{
 const fixture=env();const digest='c'.repeat(64);
 const hidden={...catalog,apps:[{...catalog.apps[0],disabled:true,mediaSources:{[digest]:'https://raw.githubusercontent.com/owner/app/main/icon.webp'}}]};
 const get=fixture.STORE.get;
 fixture.STORE.get=async(key:string,options?:any)=>key==='catalog.json'?{httpEtag:'"hidden"',body:JSON.stringify(hidden),json:async()=>hidden}:get(key,options);
 const response=await worker.fetch(new Request('https://store.test/api/catalog'),fixture);
 assert.equal((await response.json() as any).apps.length,0);
 for(const path of ['/api/apps/dev.test.app/releases','/downloads/dev.test.app/2.apk',`/media/${digest}.webp`])assert.equal((await worker.fetch(new Request('https://store.test'+path),fixture)).status,404);
});
