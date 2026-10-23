import {dispatchSync,handleAdmin,type AdminEnv} from './admin-api';
interface Env extends Omit<AdminEnv,'STORE'> {STORE?:R2Bucket;STATE?:DurableObjectNamespace}
export {MinAppStoreState} from './durable-state';
interface Version { versionCode:number; size:number; githubUrl:string; storageKey?:string }
interface Catalog { schemaVersion:number; apps:{packageName:string; versions:Version[]; mediaSources?:Record<string,string>;disabled?:boolean;iconUrl?:string;iconData?:string}[] }

export function parseRange(header:string,size:number):{offset:number,length:number}|null {
  const match = /^bytes=(\d*)-(\d*)$/.exec(header);
  if (!match || (!match[1] && !match[2]) || size <= 0) return null;
  let start = match[1] ? Number(match[1]) : Math.max(0,size-Number(match[2]));
  let end = match[1] && match[2] ? Math.min(Number(match[2]),size-1) : size-1;
  if (![start,end,Number(match[1] || match[2])].every(Number.isSafeInteger) || start >= size || end < start || (!match[1] && Number(match[2]) === 0)) return null;
  return {offset:start,length:end-start+1};
}
function matches(header:string|null,etag:string) {
  return header?.split(',').some(value=>value.trim()==='*' || value.trim().replace(/^W\//,'')===etag) ?? false;
}
const unavailable = () => new Response('Catalog unavailable',{status:503,headers:{'Retry-After':'60'}});

export default {
  async fetch(request:Request,env:Env):Promise<Response> {
    if(!env.STORE) {
      if(!env.STATE)return unavailable();
      return env.STATE.get(env.STATE.idFromName('default')).fetch(request);
    }
    const path = new URL(request.url).pathname;
    if(path==='/admin' || path==='/admin/' || path.startsWith('/api/admin/'))return handleAdmin(request,env as AdminEnv);
    if (!['GET','HEAD'].includes(request.method)) return new Response('Method not allowed',{status:405,headers:{Allow:'GET, HEAD'}});
    const releases = /^\/api\/apps\/([A-Za-z0-9_.]+)\/releases$/.exec(path);
    const download = /^\/downloads\/([A-Za-z0-9_.]+)\/([1-9][0-9]*)\.apk$/.exec(path);
    const media = /^\/media\/([0-9a-f]{64})\.webp$/.exec(path);
    const icon = /^\/icons\/([A-Za-z0-9_.]+)\/([1-9][0-9]*)\.(png|webp)$/.exec(path);
    if (path !== '/api/catalog' && !releases && !download && !media && !icon) return new Response('Not found',{status:404});
    try {
      const object = await env.STORE.get('catalog.json');
      if (!object) return unavailable();
      const stored = await object.json<Catalog>();
      const visible = stored.apps.filter(app=>app.disabled!==true);
      const catalog = {...stored,apps:visible.map(({disabled,iconData,...app})=>app)};
      if (icon) {
        const data = visible.find(app=>app.packageName===icon[1] && app.iconUrl?.endsWith(path))?.iconData;
        if (!data) return new Response('Not found',{status:404});
        const headers = {'Content-Type':`image/${icon[3]}`,'Cache-Control':'public, max-age=86400, immutable','X-Content-Type-Options':'nosniff'};
        return new Response(request.method==='HEAD'?null:Uint8Array.from(atob(data),c=>c.charCodeAt(0)),{headers});
      }
      if (path === '/api/catalog') {
        const headers = {'Content-Type':'application/json; charset=utf-8','ETag':object.httpEtag,'Cache-Control':'public, max-age=60'};
        if (matches(request.headers.get('If-None-Match'),object.httpEtag)) return new Response(null,{status:304,headers});
        return new Response(request.method === 'HEAD' ? null : JSON.stringify(catalog),{headers});
      }
      if (media) {
        const source=catalog.apps.map(app=>app.mediaSources?.[media[1]]).find(Boolean);
        if (!source) return new Response('Not found',{status:404});
        const redirect=()=>/^https:\/\/(?:github\.com|raw\.githubusercontent\.com)\//.test(source)
          ? Response.redirect(source,302) : unavailable();
        const key=`media/${media[1]}.webp`;
        const image=request.method==='HEAD'?await env.STORE.head(key):await env.STORE.get(key);
        if (!image) return redirect();
        const headers={'Content-Type':'image/webp','Content-Length':String(image.size),'ETag':image.httpEtag,'Cache-Control':'public, max-age=86400, immutable','X-Content-Type-Options':'nosniff'};
        if (matches(request.headers.get('If-None-Match'),image.httpEtag)) return new Response(null,{status:304,headers});
        return new Response(request.method==='HEAD'?null:(image as R2ObjectBody).body,{headers});
      }
      const app = catalog.apps.find(app=>app.packageName===(releases || download)![1]);
      if (!app) return new Response('Not found',{status:404});
      if (releases) return new Response(request.method==='HEAD'?null:JSON.stringify({versions:app.versions}),{headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'public, max-age=60'}});
      const version = app.versions.find(v=>v.versionCode===Number(download![2]));
      if (!version) return new Response('Not found',{status:404});
      const redirect = () => /^https:\/\/github\.com\/[^/]+\/[^/]+\/releases\/download\//.test(version.githubUrl)
        ? Response.redirect(version.githubUrl,302) : unavailable();
      if (!version.storageKey) return redirect();
      const metadata = await env.STORE.head(version.storageKey);
      if (!metadata) return redirect();
      const headers = new Headers({'Content-Type':'application/vnd.android.package-archive','Content-Length':String(metadata.size),'Accept-Ranges':'bytes','ETag':metadata.httpEtag,'Cache-Control':'public, max-age=3600, immutable','X-Content-Type-Options':'nosniff'});
      if (matches(request.headers.get('If-None-Match'),metadata.httpEtag)) return new Response(null,{status:304,headers});
      const rangeHeader = request.headers.get('Range');
      const ifRange = request.headers.get('If-Range');
      const range = rangeHeader && (!ifRange || ifRange===metadata.httpEtag) ? parseRange(rangeHeader,metadata.size) : undefined;
      if (range === null) return new Response(null,{status:416,headers:{'Content-Range':`bytes */${metadata.size}`}});
      if (range) {
        headers.set('Content-Range',`bytes ${range.offset}-${range.offset+range.length-1}/${metadata.size}`);
        headers.set('Content-Length',String(range.length));
      }
      if (request.method==='HEAD') return new Response(null,{status:range?206:200,headers});
      const apk = await env.STORE.get(version.storageKey,{range});
      if (!apk) return redirect();
      return new Response(apk.body,{status:range?206:200,headers});
    } catch (error) {
      console.error('Store read failed',String(error));
      return unavailable();
    }
  },
  async scheduled(_event:ScheduledController,env:Env):Promise<void> {
    if(!await dispatchSync(env))throw new Error('GitHub sync dispatch unavailable or rejected');
  }
};
