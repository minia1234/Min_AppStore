import {validateCandidate} from './sync-validation';
export interface AdminEnv {
  STORE:R2Bucket;
  ADMIN_TOKEN?:string;
  GITHUB_OWNERS?:string;
  GITHUB_READ_TOKEN?:string;
  GITHUB_REPOSITORY?:string;
  GITHUB_DISPATCH_TOKEN?:string;
  GITHUB_REF?:string;
}
const OWNERS=['minia1234','AIProject-k'];
interface Registry {schemaVersion:1;updatedAt:string;linked:string[];disabled?:string[]}
interface GitHubRepository {full_name:string;name:string;owner:{login:string};description:string|null;html_url:string;private:boolean;archived:boolean;default_branch:string}
interface RepositoryInventory {updatedAt:string;repositories:GitHubRepository[]}
class AdminError extends Error {constructor(public status:number,message:string){super(message);}}
const json=(body:unknown,status=200)=>Response.json(body,{status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
function allowedOwners(env:AdminEnv) {
  const configured=(env.GITHUB_OWNERS || OWNERS.join(',')).split(',').map(owner=>owner.trim());
  if(configured.length!==2 || new Set(configured.map(owner=>owner.toLowerCase())).size!==2 || configured.some(owner=>!OWNERS.some(allowed=>allowed.toLowerCase()===owner.toLowerCase()))) throw new AdminError(503,'허용 계정 설정을 확인해 주세요.');
  return OWNERS;
}
function allowedRepository(fullName:unknown,owners:string[]):fullName is string {
  return typeof fullName==='string' && /^[A-Za-z0-9-]+\/[A-Za-z0-9_.-]+$/.test(fullName) && owners.some(owner=>fullName.split('/')[0].toLowerCase()===owner.toLowerCase());
}
function validateInventory(value:unknown,owners:string[]):GitHubRepository[] {
  if(!Array.isArray(value) || value.length>2000)throw new AdminError(400,'저장소 목록 크기 제한을 확인해 주세요.');
  const seen=new Set<string>();
  return value.map(repo=>{
    if(!repo || !allowedRepository(repo.full_name,owners) || repo.full_name.length>200 || repo.private!==false || typeof repo.archived!=='boolean' || typeof repo.name!=='string' || repo.name!==repo.full_name.split('/')[1] || repo.name.length>100 || typeof repo.owner?.login!=='string' || repo.owner.login.toLowerCase()!==repo.full_name.split('/')[0].toLowerCase() || (repo.description!==null && (typeof repo.description!=='string' || repo.description.length>2000)) || typeof repo.default_branch!=='string' || !repo.default_branch || repo.default_branch.length>255 || /[\x00-\x1f\x7f]/.test(repo.default_branch) || repo.html_url!==`https://github.com/${repo.full_name}` || seen.has(repo.full_name.toLowerCase()))throw new AdminError(400,'허용 계정의 공개 저장소 목록만 사용할 수 있습니다.');
    seen.add(repo.full_name.toLowerCase());
    return {full_name:repo.full_name,name:repo.name,owner:{login:repo.owner.login},description:repo.description,html_url:repo.html_url,private:false,archived:repo.archived,default_branch:repo.default_branch};
  });
}
async function readInventory(store:R2Bucket,owners:string[]):Promise<RepositoryInventory|null> {
  try {
    const cached=(await readJson<RepositoryInventory>(store,'repositories-cache.json')).value;
    if(!cached)return null;
    if(typeof cached.updatedAt!=='string' || !Number.isFinite(Date.parse(cached.updatedAt)))throw new Error('Invalid inventory timestamp');
    return {updatedAt:cached.updatedAt,repositories:validateInventory(cached.repositories,owners)};
  }catch{console.warn('Repository inventory cache unavailable or invalid');return null;}
}
async function cacheInventory(store:R2Bucket,inventory:RepositoryInventory):Promise<void> {
  try {
    const body=JSON.stringify(inventory);
    if(new TextEncoder().encode(body).length>2_000_000)throw new Error('Inventory too large');
    const result=await store.put('repositories-cache.json',body,{httpMetadata:{contentType:'application/json'}});
    if(!result)throw new Error('Inventory cache write rejected');
  }catch{console.warn('Repository inventory cache update failed; catalog and links remain valid');}
}
async function authorized(request:Request,expected:string) {
  const header=request.headers.get('Authorization') || '';
  if(!header.startsWith('Bearer ') || header.length>1100)return false;
  const digest=async(value:string)=>new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value)));
  const [actual,wanted]=await Promise.all([digest(header.slice(7)),digest(expected)]);
  let difference=0;
  for(let i=0;i<wanted.length;i++)difference|=wanted[i]^actual[i];
  return difference===0;
}
async function github<T>(env:AdminEnv,path:string):Promise<T> {
  const headers:Record<string,string>={Accept:'application/vnd.github+json','User-Agent':'Indi-Store-Admin','X-GitHub-Api-Version':'2022-11-28'};
  const token=env.GITHUB_READ_TOKEN || env.GITHUB_DISPATCH_TOKEN;
  if(token)headers.Authorization='Bearer '+token;
  const response=await fetch('https://api.github.com'+path,{headers,redirect:'manual'});
  if(response.status===404)throw new AdminError(400,'공개 저장소를 찾을 수 없습니다.');
  if(!response.ok)throw new AdminError(502,`GitHub 저장소 조회에 실패했습니다 (${response.status}).`);
  const body=await response.text();
  if(new TextEncoder().encode(body).length>2_000_000)throw new AdminError(502,'GitHub 응답 크기 제한을 초과했습니다.');
  return JSON.parse(body) as T;
}
async function readJson<T>(store:R2Bucket,key:string):Promise<{value:T|null;etag:string|null}> {
  const object=await store.get(key);
  if(!object)return {value:null,etag:null};
  if(object.size>2_000_000)throw new AdminError(503,'저장된 설정 크기를 확인해 주세요.');
  return {value:await object.json<T>(),etag:object.httpEtag};
}
async function registry(store:R2Bucket,owners:string[]) {
  const stored=await readJson<Registry>(store,'repositories.json');
  const value=stored.value || {schemaVersion:1 as const,updatedAt:new Date().toISOString(),linked:[]};
  if(value.schemaVersion!==1 || !Array.isArray(value.linked) || value.linked.length>100 || value.linked.some(name=>!allowedRepository(name,owners)) || new Set(value.linked.map(name=>name.toLowerCase())).size!==value.linked.length)throw new AdminError(503,'저장소 연결 설정을 확인해 주세요.');
  if(value.disabled!==undefined && (!Array.isArray(value.disabled) || value.disabled.length>2000 || value.disabled.some(name=>!allowedRepository(name,owners)) || new Set(value.disabled.map(name=>name.toLowerCase())).size!==value.disabled.length))throw new AdminError(503,'저장소 비활성 설정을 확인해 주세요.');
  return {...stored,value};
}
async function writeJson(store:R2Bucket,key:string,value:unknown,etag:string|null) {
  const body=JSON.stringify(value);
  if(new TextEncoder().encode(body).length>2_000_000)throw new AdminError(503,'설정 크기 제한을 초과했습니다.');
  const result=await store.put(key,body,{httpMetadata:{contentType:'application/json'},onlyIf:new Headers(etag?{'If-Match':etag}:{'If-None-Match':'*'})});
  if(!result)throw new AdminError(409,'설정이 다른 작업에서 변경되었습니다. 관리자 복구가 필요합니다.');
  return result;
}
export async function dispatchSync(env:Pick<AdminEnv,'GITHUB_REPOSITORY'|'GITHUB_DISPATCH_TOKEN'|'GITHUB_REF'>):Promise<boolean> {
  if(!env.GITHUB_REPOSITORY || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(env.GITHUB_REPOSITORY) || !env.GITHUB_DISPATCH_TOKEN)return false;
  try {
    const response=await fetch(`https://api.github.com/repos/${env.GITHUB_REPOSITORY}/actions/workflows/sync-store.yml/dispatches`,{method:'POST',headers:{Authorization:'Bearer '+env.GITHUB_DISPATCH_TOKEN,Accept:'application/vnd.github+json','User-Agent':'Indi-Store','X-GitHub-Api-Version':'2022-11-28','Content-Type':'application/json'},body:JSON.stringify({ref:env.GITHUB_REF || 'main'}),redirect:'manual'});
    return response.ok;
  } catch {return false;}
}
async function requestBody(request:Request,limit=4096):Promise<any> {
  if(!request.headers.get('Content-Type')?.startsWith('application/json'))throw new AdminError(400,'JSON 요청이 필요합니다.');
  const reader=request.body?.getReader();
  if(!reader)throw new AdminError(400,'요청 내용이 없습니다.');
  let size=0,body='';const decoder=new TextDecoder();
  while(true){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>limit){await reader.cancel();throw new AdminError(413,'요청 크기 제한을 초과했습니다.');}body+=decoder.decode(value,{stream:true});}
  try{return JSON.parse(body+decoder.decode());}catch{throw new AdminError(400,'올바른 JSON 요청이 필요합니다.');}
}
export async function adminPagePolicy(html:string):Promise<string> {
  const hashes=await Promise.all(['script','style'].map(async tag=>{
    const content=new RegExp(`<${tag}>([\\s\\S]*?)<\\/${tag}>`).exec(html)?.[1];
    if(content===undefined)throw new Error('Admin page is missing required content');
    const bytes=new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(content)));
    return `'sha256-${btoa(String.fromCharCode(...bytes))}'`;
  }));
  return `default-src 'none'; script-src ${hashes[0]}; style-src ${hashes[1]}; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`;
}
export async function handleAdmin(request:Request,env:AdminEnv):Promise<Response> {
  const path=new URL(request.url).pathname;
  if(path==='/admin' || path==='/admin/') {
    if(request.method!=='GET')return json({message:'GET 요청만 지원합니다.'},405);
    const html=(await import('./admin.html')).default.replace(/\r\n?/g,'\n');
    return new Response(html,{headers:{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Content-Security-Policy':await adminPagePolicy(html)}});
  }
  if(path!=='/api/admin/repositories' && path!=='/api/admin/sync')return json({message:'Not found'},404);
  try {
    if(!env.ADMIN_TOKEN || env.ADMIN_TOKEN.length<32)throw new AdminError(503,'관리자 인증 설정이 필요합니다.');
    if(!await authorized(request,env.ADMIN_TOKEN))return json({message:'관리자 인증이 필요합니다.'},401);
    const owners=allowedOwners(env);
    if(path==='/api/admin/sync') {
      if(request.method==='GET') {
        const [catalog,current]=await Promise.all([readJson(env.STORE,'catalog.json'),registry(env.STORE,owners)]);
        return json({catalog:catalog.value || {schemaVersion:1,updatedAt:new Date().toISOString(),apps:[]},registry:current.value,catalogEtag:catalog.etag,registryEtag:current.etag});
      }
      if(request.method!=='PUT')return json({message:'GET/PUT 요청만 지원합니다.'},405);
      const input=await requestBody(request,2_000_000);
      if(!input || !('catalog' in input) || !('expectedCatalogEtag' in input) || !('expectedRegistryEtag' in input))throw new AdminError(400,'동기화 스냅샷이 필요합니다.');
      const inventory=input.repositoryInventory===undefined?null:validateInventory(input.repositoryInventory,owners);
      const acquired=await env.STORE.put('sync-lock.json',JSON.stringify({id:crypto.randomUUID(),startedAt:new Date().toISOString(),action:'publishCatalog'}),{onlyIf:new Headers({'If-None-Match':'*'})});
      if(!acquired)return json({message:'다른 작업이 진행 중입니다.',code:'BUSY'},409);
      let attemptedWrite=false;
      try {
        const [catalog,current]=await Promise.all([readJson<any>(env.STORE,'catalog.json'),registry(env.STORE,owners)]);
        if(catalog.etag!==input.expectedCatalogEtag || current.etag!==input.expectedRegistryEtag)throw new AdminError(409,'스냅샷이 변경되었습니다. 다시 조회하고 동기화해 주세요.');
        try{validateCandidate(input.catalog,catalog.value,current.value,new URL(request.url).origin);}catch{throw new AdminError(400,'연동 상태 또는 앱 서명·메타데이터 검증에 실패했습니다.');}
        attemptedWrite=true;
        const updated=await writeJson(env.STORE,'catalog.json',input.catalog,catalog.etag);
        if(inventory)await cacheInventory(env.STORE,{updatedAt:new Date().toISOString(),repositories:inventory});
        await env.STORE.delete('sync-lock.json');
        return json({published:true,catalogEtag:updated?.httpEtag,updatedAt:input.catalog.updatedAt});
      } catch(error) {
        if(!attemptedWrite)await env.STORE.delete('sync-lock.json');
        throw error;
      }
    }
    if(request.method==='GET') {
      const current=await registry(env.STORE,owners);
      let inventory=await readInventory(env.STORE,owners);
      if(!inventory) {
      const repositories:GitHubRepository[]=[];
      for(const owner of owners) {
        for(let page=1;page<=11;page++) {
          const batch=await github<GitHubRepository[]>(env,`/users/${owner}/repos?type=owner&sort=full_name&per_page=100&page=${page}`);
          if(!Array.isArray(batch) || (page===11 && batch.length))throw new AdminError(502,'계정당 저장소 1,000개 제한을 초과했습니다.');
          repositories.push(...batch.filter(repo=>!repo.private && repo.owner.login.toLowerCase()===owner.toLowerCase()));
          if(batch.length<100)break;
        }
      }
      const seen=new Set<string>();
      inventory={updatedAt:new Date().toISOString(),repositories:validateInventory(repositories.filter(repo=>!seen.has(repo.full_name.toLowerCase()) && !!seen.add(repo.full_name.toLowerCase())),owners)};
      await cacheInventory(env.STORE,inventory);
      }
      return json({owners,repositories:inventory.repositories.map(repo=>({fullName:repo.full_name,name:repo.name,owner:repo.owner.login,description:repo.description || '',url:repo.html_url,linked:current.value.linked.some(name=>name.toLowerCase()===repo.full_name.toLowerCase()),disabled:(current.value.disabled || []).some(name=>name.toLowerCase()===repo.full_name.toLowerCase()),archived:repo.archived,defaultBranch:repo.default_branch})),updatedAt:new Date().toISOString(),inventoryUpdatedAt:inventory.updatedAt,inventoryStale:Date.now()-Date.parse(inventory.updatedAt)>24*60*60*1000});
    }
    if(request.method!=='PUT')return json({message:'GET/PUT 요청만 지원합니다.'},405);
    const input=await requestBody(request);
    if(!allowedRepository(input?.fullName,owners) || (typeof input.linked!=='boolean' && typeof input.disabled!=='boolean') || (input.linked!==undefined && typeof input.linked!=='boolean') || (input.disabled!==undefined && typeof input.disabled!=='boolean'))throw new AdminError(400,'허용된 저장소와 변경할 연결·비활성 여부를 지정해 주세요.');
    let fullName=input.fullName;
    let linked=false,disabled=false;
    const lock={id:crypto.randomUUID(),startedAt:new Date().toISOString(),action:'setRepositoryState',fullName};
    const acquired=await env.STORE.put('sync-lock.json',JSON.stringify(lock),{onlyIf:new Headers({'If-None-Match':'*'}),httpMetadata:{contentType:'application/json'}});
    if(!acquired)return json({message:'동기화 또는 다른 관리자 작업이 진행 중입니다. 잠시 후 다시 시도해 주세요.',code:'BUSY'},409);
    let attemptedWrite=false;
    try {
      const marker=(await readJson<{dedicatedTo:string;owners?:string[];owner?:string}>(env.STORE,'indi-store-bucket.json')).value;
      const markerOwners=marker?.owners || (marker?.owner?[marker.owner]:[]);
      if(marker?.dedicatedTo!=='indi-store' || !markerOwners.length || markerOwners.some(owner=>!owners.some(allowed=>allowed.toLowerCase()===owner.toLowerCase())))throw new AdminError(503,'메타데이터 저장소 계정 설정을 확인해 주세요.');
      const current=await registry(env.STORE,owners);
      linked=typeof input.linked==='boolean'?input.linked:current.value.linked.some(name=>name.toLowerCase()===fullName.toLowerCase());
      disabled=typeof input.disabled==='boolean'?input.disabled:(current.value.disabled || []).some(name=>name.toLowerCase()===fullName.toLowerCase());
      if(input.linked===true || input.disabled===false) {
        const cached=await readInventory(env.STORE,owners);
        const repo=cached?.repositories.find(repo=>repo.full_name.toLowerCase()===fullName.toLowerCase()) || await github<GitHubRepository>(env,`/repos/${fullName}`);
        if(repo.private || repo.full_name.toLowerCase()!==fullName.toLowerCase() || !allowedRepository(repo.full_name,owners) || !owners.some(owner=>owner.toLowerCase()===repo.owner.login.toLowerCase()))throw new AdminError(400,'허용 계정의 공개 저장소만 연결할 수 있습니다.');
        fullName=repo.full_name;
      }
      const selected=current.value.linked.filter(name=>name.toLowerCase()!==fullName.toLowerCase());
      if(linked)selected.push(fullName);
      if(selected.length>100)throw new AdminError(400,'연결 가능한 앱 저장소는 최대 100개입니다.');
      const hidden=(current.value.disabled || []).filter(name=>name.toLowerCase()!==fullName.toLowerCase());
      if(disabled)hidden.push(fullName);
      if(hidden.length>2000)throw new AdminError(400,'비활성 저장소 제한을 초과했습니다.');
      const now=new Date().toISOString();
      {
        const catalog=await readJson<{schemaVersion:number;updatedAt?:string;apps:{repository:string;disabled?:boolean}[]}>(env.STORE,'catalog.json');
        if(catalog.value) {
          if(catalog.value.schemaVersion!==1 || !Array.isArray(catalog.value.apps))throw new AdminError(503,'카탈로그 복구가 필요합니다.');
          const apps=catalog.value.apps.filter(app=>linked || app.repository.toLowerCase()!==fullName.toLowerCase())
            .map(app=>app.repository.toLowerCase()===fullName.toLowerCase()?{...app,disabled}:app);
          if(JSON.stringify(apps)!==JSON.stringify(catalog.value.apps)) {
            attemptedWrite=true;
            await writeJson(env.STORE,'catalog.json',{...catalog.value,updatedAt:now,apps},catalog.etag);
          }
        }
      }
      attemptedWrite=true;
      await writeJson(env.STORE,'repositories.json',{schemaVersion:1,updatedAt:now,linked:selected.sort((a,b)=>a.localeCompare(b)),disabled:hidden.sort((a,b)=>a.localeCompare(b))},current.etag);
      await env.STORE.delete('sync-lock.json');
    } catch(error) {
      if(!attemptedWrite)await env.STORE.delete('sync-lock.json');
      throw error;
    }
    const syncRequested=linked && !disabled && await dispatchSync(env);
    return json({fullName,linked,disabled,syncRequested,message:disabled?'비활성화했습니다. 연동 선택은 유지되며 두 기본 목록에서 숨깁니다.':linked?(syncRequested?'설정을 저장하고 동기화를 요청했습니다.':'설정을 저장했습니다. 동기화는 설정 후 수동 실행하거나 다음 예약 실행에서 진행됩니다.'):'연결을 해제하고 공개 목록에서 제거했습니다.'});
  } catch(error) {
    if(!(error instanceof AdminError))console.error('Admin operation failed',error instanceof Error?error.message:'Unknown error');
    return json({message:error instanceof AdminError?error.message:'관리자 작업을 완료하지 못했습니다. 저장 상태와 동기화 잠금을 확인해 주세요.'},error instanceof AdminError?error.status:503);
  }
}
