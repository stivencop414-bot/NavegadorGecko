const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const {webcrypto}=require('node:crypto');
const root=path.join(__dirname,'../../main/assets/extensions/phantomshield');
function harness(store={},reply) {
 let now=Date.now(),calls=0,fail=false;const listeners=[];
 store.aiConfig||={enabled:true,consent:true,rememberDecisions:true,scope:'public',endpoint:'https://api.example/v1',model:'test'};
 class Clock extends Date {static now(){return now}}
 const browser={storage:{local:{get:async defaults=>({...defaults,...structuredClone(store)}),set:async values=>{
  if(fail)throw Error('disk full');const changes={};for(const [key,value] of Object.entries(values)){changes[key]={newValue:structuredClone(value)};store[key]=structuredClone(value)}listeners.forEach(fn=>fn(changes,'local'));
 }},onChanged:{addListener:fn=>listeners.push(fn)}}};
 const ctx=vm.createContext({browser,Date:Clock,URL,TextEncoder,TextDecoder,Uint8Array,crypto:webcrypto,AbortController,setTimeout,clearTimeout,
  fetch:async(url,options)=>{calls++;const payload=JSON.parse(JSON.parse(options.body).messages[1].content);
   if(reply)return reply(payload);
   return new Response(JSON.stringify({usage:{prompt_tokens:20,completion_tokens:5},choices:[{message:{content:JSON.stringify({decisions:payload.candidates.map(c=>({id:c.id,isAd:true,confidence:.99}))})}}]}));
  }});
 for(const f of ['data/ai-config.js','background/ai-memory.js','background/ai-classifier.js'])vm.runInContext(fs.readFileSync(path.join(root,f),'utf8'),ctx);
 return {store,ai:ctx.PhantomAI,memory:ctx.PhantomAIMemory,get calls(){return calls},advance(ms){now+=ms},fail(){fail=true},change(config){Object.assign(store.aiConfig,config);listeners.forEach(fn=>fn({aiConfig:{}},'local'))}};
}
const candidate=(id='c-1',key='slot promo text')=>({id,tag:'DIV',signals:['ad-class','ad-size'],width:300,height:250,localKey:key});
test('learning survives restart, reuses without tokens and keeps distinct domains/creatives separate',async()=>{
 const store={},first=harness(store);const remote=await first.ai.classify('news.com',[candidate()],false);
 assert.equal(remote.decisions[0].source,'model');assert.equal(first.calls,1);
 assert.equal(store.aiMemory.length,1);assert.doesNotMatch(JSON.stringify(store.aiMemory),/slot|promo|text|api\.example|test/);
 const second=harness(store),hit=await second.ai.classify('news.com',[candidate('c-2')],false);
 assert.equal(hit.decisions[0].source,'memory');assert.equal(second.calls,0);assert.equal((await second.ai.usage()).memoryHits,1);
 await second.ai.classify('other.com',[candidate()],false);assert.equal(second.calls,1);
 await second.ai.classify('news.com',[candidate('c-3','editorial instead')],false);assert.equal(second.calls,2);
 const report=await second.memory.summary();assert.equal(report.domains.find(d=>d.host==='news.com').rules,2);
});
test('private, excluded, disabled and opted-out calls never learn or reuse persistent rules',async()=>{
 const h=harness();await h.ai.classify('news.com',[candidate()],true);assert.equal(h.calls,0);assert.equal(h.store.aiMemory,undefined);
 await h.ai.classify('news.com',[candidate()],false);h.change({excludedHosts:['news.com']});
 assert.equal((await h.ai.classify('news.com',[candidate()],false)).ok,false);
 h.change({excludedHosts:[],rememberDecisions:false});await h.ai.classify('news.com',[candidate()],false);assert.equal(h.calls,2);
 h.change({enabled:false});assert.equal((await h.ai.classify('news.com',[candidate()],false)).ok,false);
});
test('forget, expiry and low confidence cause fresh classification',async()=>{
 const h=harness();await h.ai.classify('news.com',[candidate()],false);h.ai.invalidate();await h.memory.forget('news.com');
 assert.equal((await h.memory.summary()).domains.length,0);
 await h.ai.classify('news.com',[candidate()],false);assert.equal(h.calls,2);
 h.advance(31*86400000);h.ai.invalidate();await h.ai.classify('news.com',[candidate()],false);assert.equal(h.calls,3);
 const low=harness({},payload=>new Response(JSON.stringify({choices:[{message:{content:JSON.stringify({decisions:payload.candidates.map(c=>({id:c.id,isAd:true,confidence:.9}))})}}]})));
 const result=await low.ai.classify('news.com',[candidate()],false);assert.equal(result.decisions[0].hide,true);assert.equal((await low.memory.summary()).domains.length,0);
});
test('known decisions still apply when an unresolved candidate exceeds the provider budget',async()=>{
 const h=harness();await h.ai.classify('news.com',[candidate()],false);h.change({requestsPerMinute:1});
 const result=await h.ai.classify('news.com',[candidate('c-2'),candidate('c-3','new'),{...candidate('c-4'),signals:['ad-attribute']}],false);
 assert.equal(result.ok,true);assert.equal(result.warning.code,'budget');assert.equal(result.decisions[0].source,'memory');
 assert.equal(result.decisions[1].deferred,true);assert.equal(result.decisions[2].source,'local');assert.equal(h.calls,1);
});
test('deletion during an in-flight model call prevents resurrection',async()=>{
 let finish;const h=harness({},payload=>new Promise(resolve=>finish=()=>resolve(new Response(JSON.stringify({choices:[{message:{content:JSON.stringify({decisions:payload.candidates.map(c=>({id:c.id,isAd:true,confidence:1}))})}}]})))));
 const pending=h.ai.classify('news.com',[candidate()],false);
 while(!finish)await new Promise(setImmediate);
 h.ai.invalidate();await h.memory.forget('news.com');finish();assert.equal((await pending).ok,false);
 assert.equal((await h.memory.summary()).domains.length,0);
});
test('storage errors do not claim a saved rule and do not prevent model decisions',async()=>{
 const h=harness();await h.memory.ready;h.fail();const result=await h.ai.classify('news.com',[candidate()],false);
 assert.equal(result.ok,true);assert.equal(result.memoryError,true);assert.equal(result.decisions[0].hide,true);assert.equal(h.memory.entries.length,0);
});
test('memory bounds rules per site, total size, invalid rows and expires old records',async()=>{
 const h=harness();const key=i=>i.toString(16).padStart(64,'0');
 for(let site=0;site<22;site++)await h.memory.remember('site'+site+'.com',Array.from({length:60},(_,i)=>key(i)),()=>true);
 assert.equal(h.memory.entries.length,1000);assert.ok((await h.memory.summary()).domains.every(d=>d.rules<=50));
 assert.equal(h.memory.prune([{host:'localhost',key:key(1),created:Date.now(),lastUsed:Date.now(),hits:0}]).length,0);
 h.advance(31*86400000);assert.equal((await h.memory.summary()).domains.length,0);
});
