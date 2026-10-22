interface StoredJson {body:string;etag:string}
const KEYS=new Set(['catalog.json','repositories.json','repositories-cache.json','sync-lock.json','indi-store-bucket.json']);
export function createMetadataStore(storage:DurableObjectStorage):R2Bucket {
  const read=async(key:string)=>{
    if(!KEYS.has(key))return null;
    const record=await storage.get<StoredJson>(key);
    if(!record)return null;
    return {httpEtag:record.etag,size:new TextEncoder().encode(record.body).length,body:record.body,json:async<T>()=>JSON.parse(record.body) as T,text:async()=>record.body};
  };
  return {
    get:read,head:read,
    put:async(key:string,body:string,options?:R2PutOptions)=>{
      if(!KEYS.has(key) || typeof body!=='string' || new TextEncoder().encode(body).length>2_000_000)throw new Error('Only bounded JSON metadata is supported');
      JSON.parse(body);
      const hash=new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(body)));
      const etag='"'+Array.from(hash,byte=>byte.toString(16).padStart(2,'0')).join('')+'"';
      return storage.transaction(async tx=>{
        const previous=await tx.get<StoredJson>(key);
        const condition=options?.onlyIf;
        const headers=condition instanceof Headers?condition:new Headers();
        if(headers.get('If-None-Match')==='*' && previous)return null;
        if(headers.has('If-Match') && headers.get('If-Match')!==previous?.etag)return null;
        await tx.put(key,{body,etag});
        return {httpEtag:etag,size:new TextEncoder().encode(body).length};
      });
    },
    delete:async(key:string)=>{if(!KEYS.has(key))throw new Error('Unsupported metadata key');await storage.delete(key);}
  } as unknown as R2Bucket;
}
