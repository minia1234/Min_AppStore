import assert from 'node:assert/strict';
import test from 'node:test';
import {createMetadataStore} from '../src/state.ts';
class MemoryStorage {
  values=new Map<string,unknown>();
  async get<T>(key:string){return this.values.get(key) as T|undefined;}
  async put(key:string,value:unknown){this.values.set(key,value);}
  async delete(key:string){return this.values.delete(key);}
  async transaction<T>(callback:(storage:MemoryStorage)=>Promise<T>){return callback(this);}
}
test('JSON metadata adapter preserves ETags and conditional lock protocol',async()=>{
 const store=createMetadataStore(new MemoryStorage() as any);
 const first=await store.put('sync-lock.json','{"id":"first"}',{onlyIf:new Headers({'If-None-Match':'*'})});assert.ok(first);
 assert.equal(await store.put('sync-lock.json','{"id":"second"}',{onlyIf:new Headers({'If-None-Match':'*'})}),null);
 assert.equal((await store.get('sync-lock.json'))?.httpEtag,first.httpEtag);
 assert.equal((await (await store.get('sync-lock.json'))?.json() as any).id,'first');
 assert.equal(await store.put('sync-lock.json','{}',{onlyIf:new Headers({'If-Match':'"wrong"'})}),null);
 assert.ok(await store.put('sync-lock.json','{"id":"next"}',{onlyIf:new Headers({'If-Match':first.httpEtag})}));
 await store.delete('sync-lock.json');assert.equal(await store.head('sync-lock.json'),null);
});
test('metadata storage rejects binary asset keys and oversized JSON',async()=>{
 const store=createMetadataStore(new MemoryStorage() as any);
 await assert.rejects(()=>store.put('apks/app.apk','{}'));
 await assert.rejects(()=>store.put('catalog.json','x'.repeat(2_000_001)));
 await assert.rejects(()=>store.put('catalog.json','not json'));
});
