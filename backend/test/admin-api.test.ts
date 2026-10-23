import assert from 'node:assert/strict';
import test from 'node:test';
import {handleAdmin} from '../src/admin-api.ts';
import {readFile} from 'node:fs/promises';

const token='secret-admin-token-'.repeat(3);
const owners=['minia1234','AIProject-k'];
const repository={full_name:'minia1234/app',name:'app',owner:{login:'minia1234'},description:'앱',html_url:'https://github.com/minia1234/app',private:false,archived:false,default_branch:'main'};
class MemoryR2 {
  objects=new Map<string,string>();
  operations:string[]=[];
  failKey='';
  constructor() {this.objects.set('indi-store-bucket.json',JSON.stringify({dedicatedTo:'indi-store',owners}));}
  async get(key:string) {const value=this.objects.get(key);return value===undefined?null:{httpEtag:'"old"',size:new TextEncoder().encode(value).length,json:async()=>JSON.parse(value),text:async()=>value,body:value};}
  async put(key:string,value:string,options:any={}) {
    const conditions=options.onlyIf instanceof Headers?options.onlyIf:new Headers();
    if(conditions.get('If-None-Match')==='*'&&this.objects.has(key))return null;
    this.operations.push(`put:${key}`);
    if(this.failKey===key)throw new Error('Uncertain write failure');
    this.objects.set(key,value);return {httpEtag:'"new"'};
  }
  async delete(key:string) {this.operations.push(`delete:${key}`);this.objects.delete(key);}
}
function setup(){const store=new MemoryR2();return {STORE:store,ADMIN_TOKEN:token};}
function req(method='GET',body?:unknown,authorization=token) {return new Request('https://store.test/api/admin/repositories',{method,headers:{Authorization:'Bearer '+authorization,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});}
async function withGitHub(action:()=>Promise<void>,response=(url:string)=>url.includes('/users/')?[repository]:repository){
 const original=globalThis.fetch;
 globalThis.fetch=async(input:any)=>Response.json(response(String(input)));
 try{await action();}finally{globalThis.fetch=original;}
}
test('admin requires bearer authentication and returns no-store responses',async()=>{
 const env=setup();const response=await handleAdmin(req('GET',undefined,'wrong'),env);
 assert.equal(response.status,401);assert.equal(response.headers.get('cache-control'),'no-store');assert.equal(env.STORE.operations.length,0);
});
test('cached inventory lists and links repositories without fetching a rate-limited GitHub API',async()=>{
 const env=setup();env.STORE.objects.set('repositories-cache.json',JSON.stringify({updatedAt:'2026-01-01T00:00:00Z',repositories:[repository]}));
 const original=globalThis.fetch;let calls=0;globalThis.fetch=async()=>{calls++;return new Response('rate limit',{status:403});};
 try {
  const listed=await handleAdmin(req(),env);assert.equal(listed.status,200);const body:any=await listed.json();assert.equal(body.repositories[0].fullName,repository.full_name);assert.equal(body.inventoryStale,true);assert.equal(body.inventoryUpdatedAt,'2026-01-01T00:00:00Z');
  const linked=await handleAdmin(req('PUT',{fullName:repository.full_name,linked:true}),env);assert.equal(linked.status,200);assert.equal(calls,0);
 }finally{globalThis.fetch=original;}
});
test('malformed cached owner is never trusted for repository listing',async()=>{
 const env=setup();env.STORE.objects.set('repositories-cache.json',JSON.stringify({updatedAt:new Date().toISOString(),repositories:[{...repository,owner:{login:'outsider'}}]}));
 await withGitHub(async()=>{assert.equal((await handleAdmin(req(),env)).status,200);},url=>url.includes('/users/minia1234/')?[repository]:[]);
});
test('admin lists only allowlisted public owner repositories with default unlinked state',async()=>{
 await withGitHub(async()=>{const response=await handleAdmin(req(),setup());assert.equal(response.status,200);const body:any=await response.json();assert.deepEqual(body.owners,owners);assert.equal(body.repositories.length,1);assert.equal(body.repositories[0].fullName,'minia1234/app');assert.equal(body.repositories[0].linked,false);},url=>url.includes('/users/minia1234/')?[repository,{...repository,full_name:'minia1234/private',private:true}]:[]);
});
test('link saves registry and reports no dispatch configuration honestly',async()=>{
 await withGitHub(async()=>{const env=setup();const response=await handleAdmin(req('PUT',{fullName:'minia1234/app',linked:true}),env);assert.equal(response.status,200);const body:any=await response.json();assert.equal(body.syncRequested,false);assert.equal(body.linked,true);assert.deepEqual(JSON.parse(env.STORE.objects.get('repositories.json')!).linked,['minia1234/app']);assert.equal(env.STORE.objects.has('sync-lock.json'),false);});
});
test('unlink immediately removes app from catalog but never deletes release bytes',async()=>{
 const env=setup();env.STORE.objects.set('repositories.json',JSON.stringify({schemaVersion:1,updatedAt:'2026-10-21T00:00:00Z',linked:['minia1234/app']}));
 env.STORE.objects.set('catalog.json',JSON.stringify({schemaVersion:1,apps:[{repository:'minia1234/app'},{repository:'AIProject-k/other'}]}));
 env.STORE.objects.set('apks/keep.apk','apk');
 const response=await handleAdmin(req('PUT',{fullName:'minia1234/app',linked:false}),env);
 assert.equal(response.status,200);assert.deepEqual(JSON.parse(env.STORE.objects.get('repositories.json')!).linked,[]);assert.equal(JSON.parse(env.STORE.objects.get('catalog.json')!).apps.length,1);assert.equal(env.STORE.objects.get('apks/keep.apk'),'apk');
});
test('shared sync lock returns 409 and prevents changes',async()=>{
 const env=setup();env.STORE.objects.set('sync-lock.json','{"id":"running"}');
 const response=await handleAdmin(req('PUT',{fullName:'minia1234/app',linked:false}),env);
 assert.equal(response.status,409);assert.equal(env.STORE.objects.get('sync-lock.json'),'{"id":"running"}');assert.equal(env.STORE.objects.has('repositories.json'),false);
});
test('lock left by a failed write expires after ten minutes but a recent one still blocks',async()=>{
 const env=setup();env.STORE.objects.set('sync-lock.json',JSON.stringify({id:'running',startedAt:new Date().toISOString()}));
 assert.equal((await handleAdmin(req('PUT',{fullName:'minia1234/app',linked:false}),env)).status,409);
 env.STORE.objects.set('sync-lock.json',JSON.stringify({id:'dead',startedAt:new Date(Date.now()-11*60*1000).toISOString()}));
 const response=await handleAdmin(req('PUT',{fullName:'minia1234/app',linked:false}),env);
 assert.equal(response.status,200);assert.equal(env.STORE.objects.has('sync-lock.json'),false);
});
test('unallowed owner and private repository cannot be linked',async()=>{
 assert.equal((await handleAdmin(req('PUT',{fullName:'outsider/app',linked:true}),setup())).status,400);
 await withGitHub(async()=>{const env=setup();assert.equal((await handleAdmin(req('PUT',{fullName:'minia1234/app',linked:true}),env)).status,400);assert.equal(env.STORE.objects.has('repositories.json'),false);},()=>({...repository,private:true}));
});
test('partial unlink failure retains recovery lock and does not restore public app',async()=>{
 const env=setup();env.STORE.objects.set('catalog.json',JSON.stringify({schemaVersion:1,apps:[{repository:'minia1234/app'}]}));env.STORE.failKey='repositories.json';
 const response=await handleAdmin(req('PUT',{fullName:'minia1234/app',linked:false}),env);
 assert.equal(response.status,503);assert.equal(env.STORE.objects.has('sync-lock.json'),true);assert.equal(JSON.parse(env.STORE.objects.get('catalog.json')!).apps.length,0);
});
test('successful configured dispatch is reflected in link response',async()=>{
 const original=globalThis.fetch;let dispatched=false;
 globalThis.fetch=async(input:any,init?:RequestInit)=>{if(init?.method==='POST'){dispatched=true;return new Response(null,{status:204});}return Response.json(repository);};
 try {const env={...setup(),GITHUB_REPOSITORY:'minia1234/store',GITHUB_DISPATCH_TOKEN:'dispatch'};const response=await handleAdmin(req('PUT',{fullName:'minia1234/app',linked:true}),env);assert.equal((await response.json() as any).syncRequested,true);assert.equal(dispatched,true);}finally{globalThis.fetch=original;}
});

test('admin page policy permits only the actual bundled script and styles',async()=>{
 const {adminPagePolicy}=await import('../src/admin-api.ts');
 const html=await readFile(new URL('../src/admin.html',import.meta.url),'utf8');
 const policy=await adminPagePolicy(html);
 assert.equal(policy.includes("'unsafe-inline'"),false);
 for(const tag of ['script','style']) {
   const content=new RegExp(`<${tag}>([\\s\\S]*?)<\\/${tag}>`).exec(html)![1];
   const hash=Buffer.from(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(content))).toString('base64');
   assert.equal(policy.includes(`'sha256-${hash}'`),true);
 }
});

test('GitHub requests use Workers-supported manual redirects and reject redirected responses',async()=>{
 const original=globalThis.fetch;
 globalThis.fetch=async(_input:any,init?:RequestInit)=>{
   assert.equal(init?.redirect,'manual');
   return new Response(null,{status:302,headers:{Location:'https://other.test/steal'}});
 };
 try {assert.equal((await handleAdmin(req(),setup())).status,502);}finally{globalThis.fetch=original;}
});

test('disabling preserves link and internal app pin, is visible to admin and can be restored',async()=>{
 await withGitHub(async()=>{
  const env=setup();
  env.STORE.objects.set('repositories.json',JSON.stringify({schemaVersion:1,linked:['minia1234/app']}));
  env.STORE.objects.set('catalog.json',JSON.stringify({schemaVersion:1,apps:[{repository:'minia1234/app',packageName:'dev.test.app',versions:[{signerSha256:'pinned'}]}]}));
  const disabled=await handleAdmin(req('PUT',{fullName:'minia1234/app',disabled:true}),env);
  assert.equal(disabled.status,200);
  const saved=JSON.parse(env.STORE.objects.get('repositories.json')!);
  assert.deepEqual(saved.linked,['minia1234/app']);assert.deepEqual(saved.disabled,['minia1234/app']);
  const app=JSON.parse(env.STORE.objects.get('catalog.json')!).apps[0];
  assert.equal(app.disabled,true);assert.equal(app.versions[0].signerSha256,'pinned');
  const listed:any=await (await handleAdmin(req(),env)).json();assert.equal(listed.repositories[0].disabled,true);
  const restored=await handleAdmin(req('PUT',{fullName:'minia1234/app',disabled:false}),env);
  assert.equal(restored.status,200);assert.equal(JSON.parse(env.STORE.objects.get('catalog.json')!).apps[0].disabled,false);
 });
});

test('unlinked repositories can be disabled and linking does not override disabled state',async()=>{
 await withGitHub(async()=>{
  const env=setup();assert.equal((await handleAdmin(req('PUT',{fullName:'minia1234/app',disabled:true}),env)).status,200);
  assert.equal((await handleAdmin(req('PUT',{fullName:'minia1234/app',linked:true}),env)).status,200);
  assert.deepEqual(JSON.parse(env.STORE.objects.get('repositories.json')!).disabled,['minia1234/app']);
 });
});
