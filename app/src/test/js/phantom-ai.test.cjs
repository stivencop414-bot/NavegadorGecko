const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../../main/assets/extensions/phantomshield');
function harness(overrides = {}, reply, initialStats = {}) {
  let time = 100000, calls = [], storageListener, stats = {...initialStats};
  let config = {enabled:true,consent:true,endpoint:'https://api.example/v1',model:'small',scope:'public',...overrides};
  class Clock extends Date { static now() { return time; } }
  const context = vm.createContext({URL,TextDecoder,AbortController,Date:Clock,setTimeout,clearTimeout,
    browser:{storage:{local:{get:async()=>({aiConfig:config,aiStats:stats}),set:async values=>{if(values.aiStats)stats=values.aiStats}},onChanged:{addListener(fn){storageListener=fn}}},
      permissions:{getAll:async()=>({data_collection:['websiteContent','browsingActivity','authenticationInfo']})}},
    fetch:async(url,options)=>{
      calls.push({url,options});
      if(reply) return reply(url,options);
      const candidates=JSON.parse(options.body).messages[1].content;
      return new Response(JSON.stringify({choices:[{message:{content:JSON.stringify({decisions:JSON.parse(candidates).candidates.map(x=>({id:x.id,isAd:true,confidence:.98}))})}}]}));
    }});
  for(const file of ['data/ai-config.js','background/ai-classifier.js']) vm.runInContext(fs.readFileSync(path.join(root,file),'utf8'),context);
  return {api:context.PhantomAIConfig,ai:context.PhantomAI,calls,
    advance(ms){time+=ms},change(values){config={...config,...values};storageListener({aiConfig:{}},'local')}};
}
const candidate = (id='c-1',width=100) => [{id,tag:'DIV',signals:['ad-class'],width,height:50}];
test('existing configurations retain exact-site scope; automatic mode excludes local names and IPs',()=>{
  const h=harness(),old=h.api.validate({allowedHosts:['example.com']});
  assert.equal(old.scope,'selected');assert.equal(h.api.hostAllowed(old,'www.example.com'),false);
  assert.equal(h.api.hostAllowed(old,'example.com'),true);
  for(const host of ['localhost','127.0.0.1','10.0.0.1','[::1]','router.local','portal.internal','x.onion','x.home.arpa','printer','x..com']) {
    assert.equal(h.api.hostAllowed({scope:'public'},host),false,host);
  }
  assert.equal(h.api.hostAllowed({scope:'public'},'news.example.com'),true);
});
test('parent exclusions override automatic scope and subdomain authorization respects label boundaries',()=>{
  const h=harness();
  assert.equal(h.api.hostAllowed({scope:'public',excludedHosts:['example.com']},'news.example.com'),false);
  assert.equal(h.api.hostAllowed({scope:'selected',includeSubdomains:true,allowedHosts:['example.com']},'news.example.com'),true);
  assert.equal(h.api.hostAllowed({scope:'selected',includeSubdomains:true,allowedHosts:['example.com']},'fakeexample.com'),false);
});
test('invalid budgets fail validation',()=>{
  const h=harness();for(const value of [0,13,1.5,Infinity]) assert.throws(()=>h.api.validate({requestsPerMinute:value}));
  assert.throws(()=>h.api.validate({requestsPerHour:121}));
});
test('automatic classification works without individual sites, shares cache and sends only normalized signals',async()=>{
  const h=harness();const result=await h.ai.classify('news.example.com',candidate(),false);
  assert.equal(result.ok,true);assert.equal(result.decisions[0].hide,true);
  const second=await h.ai.classify('news.example.com',candidate('c-2'),false);
  assert.equal(second.decisions[0].id,'c-2');assert.equal(h.calls.length,1);
  const payload=JSON.parse(JSON.parse(h.calls[0].options.body).messages[1].content);
  assert.deepEqual(Object.keys(payload).sort(),['candidates','host']);
  assert.deepEqual(Object.keys(payload.candidates[0]).sort(),['height','id','signals','tag','width']);
  assert.equal(h.calls[0].options.redirect,'error');assert.equal(h.calls[0].options.credentials,'omit');
});
test('private, excluded and disabled sites never call the provider',async()=>{
  const h=harness({excludedHosts:['bank.com']});
  assert.equal((await h.ai.classify('news.example.com',candidate(),true)).ok,false);
  assert.equal((await h.ai.classify('bank.com',candidate(),false)).ok,false);
  h.change({enabled:false});assert.equal((await h.ai.classify('news.example.com',candidate(),false)).ok,false);
  assert.equal(h.calls.length,0);
});
test('minute and hourly budgets apply across tabs and hosts, while cached decisions remain usable',async()=>{
  const h=harness({requestsPerMinute:1,requestsPerHour:2});
  assert.equal((await h.ai.classify('one.com',candidate(),false)).ok,true);
  assert.equal((await h.ai.classify('two.com',candidate(),false)).ok,false);
  assert.equal((await h.ai.classify('one.com',candidate('c-2'),false)).ok,true);
  h.advance(61000);assert.equal((await h.ai.classify('two.com',candidate(),false)).ok,true);
  h.advance(61000);assert.equal((await h.ai.classify('three.com',candidate(),false)).ok,false);
  assert.equal(h.calls.length,2);
  h.advance(3600000);assert.equal((await h.ai.classify('three.com',candidate(),false)).ok,true);
});
test('429 applies Retry-After across sites and configuration changes do not reset the budget',async()=>{
  const h=harness({},()=>new Response('',{status:429,headers:{'Retry-After':'120'}}));
  await h.ai.classify('one.com',candidate(),false);h.change({model:'other'});h.advance(61000);
  await h.ai.classify('two.com',candidate(),false);assert.equal(h.calls.length,1);
  h.advance(60000);await h.ai.classify('two.com',candidate(),false);assert.equal(h.calls.length,2);
});
test('disabling AI aborts in-flight requests and rejects late decisions',async()=>{
  let resolve,signal;
  const h=harness({},(_,options)=>{signal=options.signal;return new Promise(r=>resolve=r)});
  const pending=h.ai.classify('one.com',candidate(),false);await new Promise(setImmediate);
  h.change({enabled:false});assert.equal(signal.aborted,true);
  resolve(new Response(JSON.stringify({choices:[{message:{content:'{"decisions":[{"id":"c-1","isAd":true,"confidence":1}]}'}}]})));
  assert.equal((await pending).ok,false);assert.equal(h.ai.cache.size,0);
});
test('malformed provider decisions fail closed without exposing provider bodies',async()=>{
  const h=harness({},()=>new Response(JSON.stringify({choices:[{message:{content:'secret-provider-body'}}]})));
  const result=await h.ai.classify('one.com',candidate(),false);
  assert.equal(result.ok,false);assert.doesNotMatch(result.error,/secret-provider/);
});

test('OpenAI usage is persisted; cached decisions add detections without extra tokens',async()=>{
 const h=harness({},(_,options)=>new Response(JSON.stringify({usage:{prompt_tokens:120,completion_tokens:15},choices:[{message:{content:JSON.stringify({decisions:[{id:'c-1',isAd:true,confidence:.99}]})}}]})));
 await h.ai.classify('one.com',candidate(),false);await h.ai.classify('one.com',candidate('c-2'),false);
 const stats=await h.ai.usage();assert.equal(stats.requests,1);assert.equal(stats.inputTokens,120);assert.equal(stats.outputTokens,15);
 assert.equal(stats.detected,2);assert.equal(stats.analyzed,2);assert.equal(stats.cacheHits,1);assert.equal(stats.usageMissing,0);
 assert.equal(JSON.stringify(stats).includes('one.com'),false);
});
test('Claude uses Messages authentication and parses content blocks and token usage',async()=>{
 const h=harness({provider:'claude',endpoint:'https://api.anthropic.com/v1',apiKey:'test-key'},(_,options)=>new Response(JSON.stringify({usage:{input_tokens:42,output_tokens:9,cache_read_input_tokens:8,cache_creation_input_tokens:5},content:[{type:'text',text:'{"decisions":[{"id":"c-1","isAd":true,"confidence":0.98}]}'}]})));
 const result=await h.ai.classify('one.com',candidate(),false);assert.equal(result.ok,true);assert.equal(result.decisions[0].hide,true);
 const call=h.calls[0];assert.equal(call.url,'https://api.anthropic.com/v1/messages');assert.equal(call.options.headers['x-api-key'],'test-key');
 assert.equal(call.options.headers.Authorization,undefined);const body=JSON.parse(call.options.body);assert.equal(typeof body.system,'string');assert.equal(body.messages[0].role,'user');
 const stats=await h.ai.usage();assert.equal(stats.inputTokens,55);assert.equal(stats.outputTokens,9);
});
test('missing usage is not invented and private pages leave all counters unchanged',async()=>{
 const h=harness();await h.ai.classify('one.com',candidate(),true);assert.equal((await h.ai.usage()).requests,0);
 await h.ai.classify('one.com',candidate(),false);const stats=await h.ai.usage();assert.equal(stats.usageMissing,1);assert.equal(stats.inputTokens,0);
});
test('provider presets allow endpoint overrides and OpenRouter uses Chat Completions',async()=>{
 const h=harness({provider:'openrouter',endpoint:'https://openrouter.ai/api/v1'});
 assert.equal(h.api.validate({provider:'claude'}).endpoint,'https://api.anthropic.com/v1');
 assert.equal(h.api.validate({provider:'claude',endpoint:'https://proxy.example/anthropic'}).endpoint,'https://proxy.example/anthropic');
 await h.ai.classify('one.com',candidate(),false);assert.equal(h.calls[0].url,'https://openrouter.ai/api/v1/chat/completions');
});

test('aggregate usage survives a new classifier and configuration changes',async()=>{
 const h=harness({},undefined,{requests:4,inputTokens:100,outputTokens:30,detected:3});
 h.change({model:'other'});const stats=await h.ai.usage();assert.equal(stats.requests,4);assert.equal(stats.inputTokens,100);assert.equal(stats.detected,3);
 await h.ai.classify('one.com',candidate(),false);assert.equal((await h.ai.usage()).requests,5);
});

// --- 8.5: compatibilidad con modelos actuales, diagnóstico y análisis detallado ---
const okBody = id => JSON.stringify({decisions:[{id,isAd:true,confidence:.9}]});
test('Claude requests omit temperature, leave room for reasoning and skip thinking blocks',async()=>{
 const h=harness({provider:'claude',endpoint:'https://api.anthropic.com/v1',apiKey:'k',model:'claude-haiku-5-5'},()=>new Response(JSON.stringify({stop_reason:'end_turn',content:[{type:'thinking',thinking:''},{type:'text',text:'```json\n'+okBody('c-1')+'\n```'}]})));
 const result=await h.ai.classify('one.com',candidate(),false);assert.equal(result.ok,true);assert.equal(result.decisions[0].hide,true);
 const body=JSON.parse(h.calls[0].options.body);
 assert.equal('temperature' in body,false);assert.equal('response_format' in body,false);assert.equal('stream' in body,false);
 assert.ok(body.max_tokens>=2048);assert.equal(h.calls[0].options.headers['anthropic-version'],'2023-06-01');
});
test('a Claude refusal hides nothing and is reported without retrying',async()=>{
 const h=harness({provider:'claude',endpoint:'https://api.anthropic.com/v1',apiKey:'k'},()=>new Response(JSON.stringify({stop_reason:'refusal',content:[]})));
 const result=await h.ai.classify('one.com',candidate(),false);assert.equal(result.ok,false);assert.equal(result.code,'refusal');assert.equal(h.calls.length,1);
});
test('OpenAI uses max_completion_tokens; compatible APIs retry once with the minimal body after HTTP 400',async()=>{
 const openai=harness({provider:'openai',endpoint:'https://api.openai.com/v1',apiKey:'k'},()=>new Response(JSON.stringify({choices:[{message:{content:okBody('c-1')}}]})));
 await openai.ai.classify('one.com',candidate(),false);
 const first=JSON.parse(openai.calls[0].options.body);assert.ok(first.max_completion_tokens>0);assert.equal('max_tokens' in first,false);assert.equal('temperature' in first,false);
 let n=0;
 const custom=harness({},(_,options)=>{n++;const body=JSON.parse(options.body);
  return 'max_tokens' in body?new Response('{"error":{"message":"Unsupported parameter: max_tokens secret-detail"}}',{status:400})
   :new Response(JSON.stringify({choices:[{message:{content:okBody('c-1')}}]}));});
 const result=await custom.ai.classify('one.com',candidate(),false);assert.equal(result.ok,true);assert.equal(n,2);
 // La variante aceptada se recuerda: la siguiente petición no repite el error.
 await custom.ai.classify('two.com',candidate('c-1',300),false);assert.equal(n,3);
 assert.equal((await custom.ai.usage()).requests,3);
});
test('provider failures are explained by category and never echo the provider body',async()=>{
 for(const [status,code,pattern] of [[401,'auth',/clave/],[403,'auth',/clave/],[404,'model',/modelo/],[400,'request',/solicitud/],[429,'rate',/limit/i],[500,'server',/temporal/]]){
  const h=harness({provider:'claude',endpoint:'https://api.anthropic.com/v1',apiKey:'k'},()=>new Response('{"error":{"message":"secret-provider-body"}}',{status}));
  const result=await h.ai.classify('one.com',candidate(),false);
  assert.equal(result.ok,false);assert.equal(result.code,code,String(status));assert.match(result.error,pattern);assert.doesNotMatch(result.error,/secret/);
 }
 const offline=harness({},()=>{throw new TypeError('NetworkError')});
 assert.equal((await offline.ai.classify('one.com',candidate(),false)).code,'network');
});
test('the detailed mode adds bounded, sanitized context; the basic mode never sends it',async()=>{
 const noisy=[{id:'c-1',tag:'A',signals:['external-links','ad-size','made-up'],width:300,height:250,
  text:'Oferta   exclusiva\n llama al 300 123 4567 o escribe a ana@correo.com '+'x'.repeat(400),
  links:['ads.example.net','NOT A HOST','b.example','c.example','d.example'],frame:'javascript:alert(1)',cls:['Banner_Top','x','ok-class','<script>'],html:'<b>secret</b>',url:'https://one.com/private?token=1'}];
 const rich=harness({detail:'rich'});await rich.ai.classify('one.com',noisy,false);
 const sent=JSON.parse(JSON.parse(rich.calls[0].options.body).messages[1].content).candidates[0];
 assert.deepEqual(Object.keys(sent).sort(),['cls','height','id','links','signals','tag','text','width']);
 assert.ok(sent.text.length<=160);assert.doesNotMatch(sent.text,/ana@correo|300 123 4567|\n/);assert.match(sent.text,/\[correo\]|\[número\]/);
 assert.deepEqual(sent.links,['ads.example.net','b.example','c.example']);assert.deepEqual(sent.cls,['banner_top','ok-class']);
 assert.deepEqual(sent.signals,['ad-size','external-links']);
 const basic=harness();await basic.ai.classify('one.com',noisy,false);
 const plain=JSON.parse(JSON.parse(basic.calls[0].options.body).messages[1].content).candidates[0];
 assert.deepEqual(Object.keys(plain).sort(),['height','id','signals','tag','width']);
});
test('decisions need 0.8 confidence; unknown ids and malformed entries are ignored instead of failing the batch',async()=>{
 const two=[{id:'c-1',tag:'DIV',signals:['ad-class'],width:100,height:50},{id:'c-2',tag:'DIV',signals:['ad-class'],width:300,height:250}];
 const h=harness({},()=>new Response(JSON.stringify({choices:[{message:{content:'Aquí tienes: '+JSON.stringify({decisions:[
  {id:'c-1',isAd:true,confidence:.79},{id:'c-2',isAd:true,confidence:.8},{id:'zzz',isAd:true,confidence:1},{id:'c-1',isAd:'yes'},null]})}}]})));
 const result=await h.ai.classify('one.com',two,false);assert.equal(result.ok,true);
 assert.deepEqual(result.decisions.map(x=>x.hide),[false,true]);
});
test('existing configurations keep the basic detail level and the longer default timeout validates',()=>{
 const h=harness();
 assert.equal(h.api.validate({enabled:true,consent:true,endpoint:'https://api.example/v1',model:'m'}).detail,'basic');
 assert.equal(h.api.validate({detail:'rich'}).detail,'rich');assert.throws(()=>h.api.validate({detail:'everything'}));
 assert.equal(h.api.validate({}).timeoutMs,12000);assert.equal(h.api.validate({timeoutMs:30000}).timeoutMs,30000);assert.throws(()=>h.api.validate({timeoutMs:30001}));
});
test('connection test works before enabling, uses invented samples and reports the real outcome',async()=>{
 const good=harness({enabled:false,consent:false,provider:'claude',endpoint:'https://api.anthropic.com/v1',apiKey:'saved-key',model:'old'},
  ()=>new Response(JSON.stringify({content:[{type:'text',text:JSON.stringify({decisions:[{id:'t-1',isAd:true,confidence:.97},{id:'t-2',isAd:false,confidence:.9}]})}]})));
 const ok=await good.ai.probe({provider:'claude',endpoint:'https://api.anthropic.com/v1',model:'claude-opus-5-5',apiKey:''});
 assert.equal(ok.ok,true);assert.equal(ok.detected,true);assert.equal(ok.kept,true);
 const call=good.calls[0];assert.equal(call.options.headers['x-api-key'],'saved-key','saved key reused for the same endpoint');
 const body=JSON.parse(call.options.body);assert.equal(body.model,'claude-opus-5-5');
 assert.equal(JSON.parse(body.messages[0].content).host,'prueba.example');
 // Otra dirección nunca recibe la clave guardada.
 const other=await good.ai.probe({provider:'custom',endpoint:'https://other.example/v1',model:'m',apiKey:''});
 assert.equal(good.calls[1].options.headers.Authorization,undefined);assert.equal(other.ok,false);
 const bad=harness({provider:'claude',endpoint:'https://api.anthropic.com/v1'},()=>new Response('{}',{status:401}));
 const failed=await bad.ai.probe({provider:'claude',endpoint:'https://api.anthropic.com/v1',model:'m',apiKey:'wrong'});
 assert.equal(failed.ok,false);assert.equal(failed.code,'auth');assert.equal(failed.http,401);assert.match(failed.error,/clave/);
 assert.equal((await bad.ai.probe({provider:'claude',model:''})).ok,false);assert.equal(bad.calls.length,1);
});

test('HTTP 400 fallback cannot spend past the hourly or minute budget',async()=>{
 const h=harness({requestsPerMinute:1,requestsPerHour:1},()=>new Response('',{status:400}));
 const result=await h.ai.classify('one.com',candidate(),false);assert.equal(result.code,'budget');assert.equal(h.calls.length,1);assert.equal((await h.ai.usage()).requests,1);
});

test('definitive ad signals are hidden locally without calling the provider',async()=>{
 const h=harness();
 const result=await h.ai.classify('news.example.com',[
  {id:'c-1',tag:'INS',signals:['ad-attribute'],width:300,height:250},
  {id:'c-2',tag:'IFRAME',signals:['third-party-frame','ad-size'],width:728,height:90}],false);
 assert.equal(result.ok,true); assert.equal(h.calls.length,0);
 assert.deepEqual(result.decisions.map(d=>d.hide),[true,true]);
 const usage=await h.ai.usage(); assert.equal(usage.local,2); assert.equal(usage.detected,2);
});
