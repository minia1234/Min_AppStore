import assert from 'node:assert/strict';
import test from 'node:test';
import {handleAdmin} from '../src/admin-api.ts';
const token='test-admin-token-'.repeat(4);
const repo='minia1234/app';
const version={versionCode:1,versionName:'1.0',minSdk:26,sha256:'a'.repeat(64),signerSha256:'b'.repeat(64),size:100,downloadUrl:'https://store.test/downloads/dev.test.app/1.apk',githubUrl:`https://github.com/${repo}/releases/download/v1/app.apk`,publishedAt:'2026-10-22T00:00:00Z',releaseNotes:''};
const app={packageName:'dev.test.app',name:'App',summary:'summary',description:'description',category:'Tools',author:'Me',repository:repo,tags:[],iconUrl:'',screenshots:[],downloadCount:0,versions:[version]};
const candidate=()=>({schemaVersion:1,updatedAt:'2026-10-22T00:00:00Z',apps:[structuredClone(app)]});
function setup(disabled=false){
 const values=new Map<string,any>([['repositories.json',{schemaVersion:1,updatedAt:'2026-10-22',linked:[repo],disabled:disabled?[repo]:[]}],['catalog.json',{schemaVersion:1,updatedAt:'2026-10-22',apps:disabled?[{...app,disabled:true}]:[]}]]);
 const env={ADMIN_TOKEN:token,STORE:{get:async(key:string)=>values.has(key)?{size:100,httpEtag:`"${key}"`,json:async()=>structuredClone(values.get(key))}:null,put:async(key:string,body:string,options?:any)=>{if(key==='sync-lock.json'&&values.has(key))return null;values.set(key,JSON.parse(body));return {httpEtag:'"new"'};},delete:async(key:string)=>{values.delete(key);}} as any};
 return {env,values};
}
function request(method='GET',body?:any,auth=token){return new Request('https://store.test/api/admin/sync',{method,headers:{Authorization:'Bearer '+auth,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});}
const payload=(catalog=candidate())=>({catalog,expectedCatalogEtag:'"catalog.json"',expectedRegistryEtag:'"repositories.json"'});
test('sync snapshot requires authentication and includes internal disabled pins',async()=>{const {env}=setup(true);assert.equal((await handleAdmin(request('GET',undefined,'bad'),env)).status,401);const response=await handleAdmin(request(),env);assert.equal(response.status,200);const body:any=await response.json();assert.equal(body.catalog.apps[0].disabled,true);assert.equal(body.registry.linked[0],repo);assert.equal(body.catalogEtag,'"catalog.json"');});
test('validated GitHub-only candidate publishes under snapshot ETags',async()=>{const {env,values}=setup();const response=await handleAdmin(request('PUT',payload()),env);assert.equal(response.status,200);assert.equal(values.get('catalog.json').apps[0].repository,repo);assert.equal(values.has('sync-lock.json'),false);});
test('stale registry snapshot is rejected without catalog overwrite',async()=>{const {env,values}=setup();const response=await handleAdmin(request('PUT',{...payload(),expectedRegistryEtag:'"stale"'}),env);assert.equal(response.status,409);assert.equal(values.get('catalog.json').apps.length,0);assert.equal(values.has('sync-lock.json'),false);});
test('sync refuses storage objects, wrong source owner and unlinked entries',async()=>{for(const mutate of [(a:any)=>a.versions[0].storageKey='apk/x',(a:any)=>a.mediaSources={},(a:any)=>a.repository='AIProject-k/other',(a:any)=>a.versions[0].githubUrl='https://github.com/outsider/app/releases/download/v1/app.apk',(a:any)=>a.versions[0].sha256='invalid']){const {env,values}=setup();const catalog=candidate();mutate(catalog.apps[0]);assert.equal((await handleAdmin(request('PUT',payload(catalog)),env)).status,400);assert.equal(values.get('catalog.json').apps.length,0);}});
test('disabled app identity is preserved and flag must match registry',async()=>{const {env}=setup(true);assert.equal((await handleAdmin(request('PUT',payload()),env)).status,400);const catalog=candidate();(catalog.apps[0] as any).disabled=true;assert.equal((await handleAdmin(request('PUT',payload(catalog)),env)).status,200);});
test('signer changes and removal of disabled pins are rejected',async()=>{const {env}=setup(true);const catalog=candidate();(catalog.apps[0] as any).disabled=true;catalog.apps[0].versions[0].signerSha256='c'.repeat(64);assert.equal((await handleAdmin(request('PUT',payload(catalog)),env)).status,400);catalog.apps=[];assert.equal((await handleAdmin(request('PUT',payload(catalog)),env)).status,400);});
test('new app cannot mix different signers across version history',async()=>{const {env}=setup();const catalog=candidate();catalog.apps[0].versions.push({...version,versionCode:2,signerSha256:'c'.repeat(64),downloadUrl:'https://store.test/downloads/dev.test.app/2.apk'});assert.equal((await handleAdmin(request('PUT',payload(catalog)),env)).status,400);});
test('sync rejects oversized bodies before acquiring a lock',async()=>{const {env,values}=setup();const response=await handleAdmin(request('PUT',{padding:'x'.repeat(2_000_000)}),env);assert.equal(response.status,413);assert.equal(values.has('sync-lock.json'),false);});
test('sync links repositories whose verified APK it publishes',async()=>{
 const {env,values}=setup();values.set('repositories.json',{schemaVersion:1,updatedAt:'2026-10-22',linked:[],disabled:[]});
 assert.equal((await handleAdmin(request('PUT',payload()),env)).status,400);
 const response=await handleAdmin(request('PUT',{...payload(),autoLink:[repo]}),env);
 assert.equal(response.status,200);assert.deepEqual((await response.json() as any).autoLinked,[repo]);
 assert.deepEqual(values.get('repositories.json').linked,[repo]);assert.equal(values.get('catalog.json').apps[0].repository,repo);
 assert.equal((await handleAdmin(request('PUT',{...payload(),autoLink:['outsider/app']}),env)).status,400);
});
test('APK icon must be a small PNG or WebP served from its own versioned store URL',async()=>{
 const png=btoa('\x89PNG\r\n\x1a\n'+'0'.repeat(20));
 for(const [iconData,iconUrl,status] of [[png,'https://store.test/icons/dev.test.app/1.png',200],[png,'https://store.test/icons/dev.test.app/2.png',400],[btoa('<svg/>'),'https://store.test/icons/dev.test.app/1.png',400],[btoa('\x89PNG\r\n\x1a\n'+'0'.repeat(12_000)),'https://store.test/icons/dev.test.app/1.png',400]] as const){
  const {env}=setup();const catalog=candidate();Object.assign(catalog.apps[0],{iconData,iconUrl});
  assert.equal((await handleAdmin(request('PUT',payload(catalog)),env)).status,status);
 }
});
const inventory=[{full_name:repo,name:'app',owner:{login:'minia1234'},description:'App description',html_url:`https://github.com/${repo}`,private:false,archived:false,default_branch:'main'}];
test('sync publishes validated repository inventory cache after catalog',async()=>{const {env,values}=setup();const response=await handleAdmin(request('PUT',{...payload(),repositoryInventory:inventory}),env);assert.equal(response.status,200);assert.equal(values.get('repositories-cache.json').repositories[0].full_name,repo);assert.equal(values.has('sync-lock.json'),false);});
test('invalid inventory owner or unsafe repository URL rejects publication',async()=>{for(const changes of [{owner:{login:'outsider'}},{html_url:'https://evil.test/minia1234/app'},{private:true}]){const {env,values}=setup();const response=await handleAdmin(request('PUT',{...payload(),repositoryInventory:[{...inventory[0],...changes}]}),env);assert.equal(response.status,400);assert.equal(values.get('catalog.json').apps.length,0);}});
test('advisory cache write failure does not invalidate published catalog or retain lock',async()=>{const {env,values}=setup();const put=env.STORE.put;env.STORE.put=async(key:string,...args:any[])=>{if(key==='repositories-cache.json')throw new Error('cache unavailable');return put(key,...args);};const response=await handleAdmin(request('PUT',{...payload(),repositoryInventory:inventory}),env);assert.equal(response.status,200);assert.equal(values.get('catalog.json').apps.length,1);assert.equal(values.has('sync-lock.json'),false);});
