import assert from 'node:assert/strict';
import test from 'node:test';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import {handleAdmin} from '../src/admin-api.ts';
import {adminMessage} from '../src/admin-language.ts';

test('API messages honor the requested language and preserve authentication status',async()=>{
  for(const [language,expected] of [['en-US,en;q=0.9','Admin authentication is required.'],['ko-KR','관리자 인증이 필요합니다.']]) {
    const response=await handleAdmin(new Request('https://store.test/api/admin/repositories',{headers:{'Accept-Language':language}}),{STORE:{} as any,ADMIN_TOKEN:'admin-token-'.repeat(5)});
    assert.equal(response.status,401);
    assert.equal(response.headers.get('Cache-Control'),'no-store');
    assert.equal((await response.json() as any).message,expected);
  }
  assert.equal(adminMessage('GitHub 저장소 조회에 실패했습니다 (403).','en'),'GitHub repository lookup failed (403).');
});

test('bundled admin script localizes static text, attributes and loading feedback',async()=>{
  const html=await readFile(new URL('../src/admin.html',import.meta.url),'utf8');
  const script=/<script>([\s\S]*?)<\/script>/.exec(html)![1];
  for(const [language,label,loading] of [['en-US','Repository management','Loading repositories…'],['ko-KR','저장소 관리','목록을 확인하고 있어요…'],['fr-FR','Repository management','Loading repositories…']]) {
    const textNode={textContent:'저장소 관리',parentElement:{closest:()=>false}};
    const field={attributes:{placeholder:'관리자 토큰 입력','aria-label':'저장소 관리'} as Record<string,string>,getAttribute(key:string){return this.attributes[key];},setAttribute(key:string,value:string){this.attributes[key]=value;}};
    const elements=new Map<string,any>();
    const makeElement=()=>({textContent:'',disabled:false,addEventListener(){},setAttribute(){},querySelectorAll:()=>[]});
    const document={documentElement:{lang:''},title:'저장소 관리 · MinAppStore',body:{},
      createTreeWalker:()=>{let visited=false;return {currentNode:textNode,nextNode(){if(visited)return false;visited=true;return true;}};},
      querySelectorAll:()=>[field],createElement:makeElement,
      getElementById:(id:string)=>{if(!elements.has(id))elements.set(id,makeElement());return elements.get(id);}};
    const context=vm.createContext({navigator:{languages:[language]},document,NodeFilter:{SHOW_TEXT:4},Intl});
    vm.runInContext(script,context);
    assert.equal(textNode.textContent,label);
    assert.equal(field.attributes['aria-label'],label);
    assert.equal(field.attributes.placeholder,language.startsWith('ko')?'관리자 토큰 입력':'Enter admin token');
    vm.runInContext('setBusy(true)',context);
    assert.equal(elements.get('login-button').textContent,loading);
    assert.equal(vm.runInContext('element("p", "repo-description", "나의 앱을").textContent',context),'나의 앱을');
  }
});
