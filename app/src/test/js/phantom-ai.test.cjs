const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../../main/assets/extensions/phantomshield');
function harness(overrides = {}, reply) {
  let time = 100000, calls = [], storageListener;
  let config = {enabled:true,consent:true,endpoint:'https://api.example/v1',model:'small',scope:'public',...overrides};
  class Clock extends Date { static now() { return time; } }
  const context = vm.createContext({URL,TextDecoder,AbortController,Date:Clock,setTimeout,clearTimeout,
    browser:{storage:{local:{get:async()=>({aiConfig:config})},onChanged:{addListener(fn){storageListener=fn}}},
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
const candidate = (id='c-1',width=100) => [{id,tag:'DIV',signals:['ad-attribute'],width,height:50}];
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
