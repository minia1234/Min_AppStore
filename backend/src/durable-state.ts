import {createMetadataStore} from './state';
import worker from './index';
import type {AdminEnv} from './admin-api';

export class MinAppStoreState {
  private ready:Promise<void>;
  private store:R2Bucket;
  constructor(state:DurableObjectState,private env:AdminEnv) {
    this.store=createMetadataStore(state.storage);
    this.ready=state.blockConcurrencyWhile(async()=>{
      const now=new Date().toISOString();
      for(const [key,value] of Object.entries({
        'indi-store-bucket.json':{dedicatedTo:'indi-store',owners:['minia1234','AIProject-k']},
        'repositories.json':{schemaVersion:1,updatedAt:now,linked:[],disabled:[]},
        'catalog.json':{schemaVersion:1,updatedAt:now,apps:[]}
      })) {
        if(!await this.store.get(key))await this.store.put(key,JSON.stringify(value),{onlyIf:new Headers({'If-None-Match':'*'})});
      }
    });
  }
  async fetch(request:Request):Promise<Response> {
    await this.ready;
    return worker.fetch(request,{...this.env,STORE:this.store});
  }
}
