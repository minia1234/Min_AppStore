const hex=/^[0-9a-f]{64}$/;
const packageName=/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
export function validateCandidate(catalog:any,previous:any,registry:{linked:string[];disabled?:string[]},origin:string):void {
  const bad=()=>{throw new Error('Invalid or untrusted catalog candidate');};
  const text=(value:unknown,max:number,empty=false)=>typeof value==='string' && value.length<=max && (empty || !!value.trim());
  const date=(value:unknown)=>typeof value==='string' && value.length<50 && Number.isFinite(Date.parse(value));
  const https=(value:unknown)=>{try{const url=new URL(String(value));return url.protocol==='https:' && !url.username && !url.password && !!url.hostname;}catch{return false;}};
  if(catalog?.schemaVersion!==1 || !date(catalog.updatedAt) || !Array.isArray(catalog.apps) || catalog.apps.length>100)bad();
  const linked=new Set(registry.linked.map(repo=>repo.toLowerCase()));
  const disabled=new Set((registry.disabled || []).map(repo=>repo.toLowerCase()));
  const oldApps=previous?.apps || [];
  const seenRepo=new Set(),seenPackage=new Set();
  for(const app of catalog.apps) {
    if(!app || !text(app.repository,200) || !/^(?:minia1234|AIProject-k)\/[A-Za-z0-9_.-]+$/i.test(app.repository) || !linked.has(app.repository.toLowerCase()) || seenRepo.has(app.repository.toLowerCase()))bad();
    seenRepo.add(app.repository.toLowerCase());
    if(!text(app.packageName,200) || !packageName.test(app.packageName) || seenPackage.has(app.packageName))bad();
    seenPackage.add(app.packageName);
    if(app.disabled!==undefined && typeof app.disabled!=='boolean')bad();
    if((app.disabled===true)!==disabled.has(app.repository.toLowerCase()) || 'mediaSources' in app || 'storageKey' in app)bad();
    for(const [key,max] of Object.entries({name:80,summary:200,description:10000,category:40,author:80}))if(!text(app[key],max))bad();
    if(!Array.isArray(app.tags) || app.tags.length>12 || app.tags.some((tag:unknown)=>!text(tag,40)))bad();
    if(!text(app.iconUrl,2048,true) || (app.iconUrl && !https(app.iconUrl)) || !Array.isArray(app.screenshots) || app.screenshots.length>8 || app.screenshots.some((url:unknown)=>!text(url,2048)||!https(url)))bad();
    if(!Number.isSafeInteger(app.downloadCount) || app.downloadCount<0 || !Array.isArray(app.versions) || !app.versions.length || app.versions.length>100)bad();
    const old=oldApps.find((item:any)=>item.repository.toLowerCase()===app.repository.toLowerCase());
    const priorPackage=oldApps.find((item:any)=>item.packageName===app.packageName);
    if(priorPackage && priorPackage.repository.toLowerCase()!==app.repository.toLowerCase())bad();
    if(old && old.packageName!==app.packageName)bad();
    const codes=new Set();let latest=0;
    for(const version of app.versions) {
      if(!version || 'storageKey' in version || 'mediaSources' in version || !Number.isSafeInteger(version.versionCode) || version.versionCode<1 || version.versionCode>2_100_000_000 || codes.has(version.versionCode))bad();
      codes.add(version.versionCode);latest=Math.max(latest,version.versionCode);
      if(!text(version.versionName,80) || !Number.isSafeInteger(version.minSdk) || version.minSdk<1 || version.minSdk>1000 || !Number.isSafeInteger(version.size) || version.size<1 || version.size>512*1024*1024 || !hex.test(version.sha256) || !hex.test(version.signerSha256) || !date(version.publishedAt) || !text(version.releaseNotes,10000,true))bad();
      if(version.signerSha256!==app.versions[0].signerSha256)bad();
      let github:URL;try{github=new URL(version.githubUrl);}catch{bad();continue;}
      const prefix=`/${app.repository}/releases/download/`;
      if(github.protocol!=='https:' || github.hostname!=='github.com' || github.username || github.password || github.port || github.search || github.hash || !github.pathname.startsWith(prefix) || !/^\/[^/]+\/[A-Za-z0-9_.-]+\.apk$/.test(github.pathname.slice(prefix.length-1)))bad();
      if(version.downloadUrl!==`${origin}/downloads/${app.packageName}/${version.versionCode}.apk`)bad();
      if(old) {
        if(old.versions.some((prior:any)=>prior.signerSha256!==version.signerSha256))bad();
        const prior=old.versions.find((item:any)=>item.versionCode===version.versionCode);
        if(prior && (prior.sha256!==version.sha256 || prior.size!==version.size || prior.minSdk!==version.minSdk || prior.versionName!==version.versionName))bad();
      }
    }
    if(old && latest<Math.max(...old.versions.map((item:any)=>item.versionCode)))bad();
    if('iconData' in app) {
      // A launcher icon read from the APK is stored here and served at /icons/{package}/{latest versionCode}.{png|webp}.
      if(typeof app.iconData!=='string')bad();
      let bytes:Uint8Array;try{bytes=Uint8Array.from(atob(app.iconData),c=>c.charCodeAt(0));}catch{bad();continue;}
      const ascii=(from:number,to:number)=>String.fromCharCode(...bytes.slice(from,to));
      const kind=ascii(0,8)==='\x89PNG\r\n\x1a\n'?'png':ascii(0,4)==='RIFF' && ascii(8,12)==='WEBP'?'webp':'';
      if(bytes.length>12_000 || !kind || app.iconUrl!==`${origin}/icons/${app.packageName}/${latest}.${kind}`)bad();
    }
  }
  for(const old of oldApps)if(linked.has(old.repository.toLowerCase()) && !seenRepo.has(old.repository.toLowerCase()))bad();
}
