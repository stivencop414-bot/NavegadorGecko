const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../../main/assets/extensions/phantomshield/popup');
const html = fs.readFileSync(path.join(root,'popup.html'),'utf8');
const source = fs.readFileSync(path.join(root,'popup.js'),'utf8');
async function harness(initial={},tab={id:10,url:'https://www.example.com/page',incognito:false}) {
  const elements=new Map(),documentEvents={},storageListeners=[];
  for(const m of html.matchAll(/<[^>]*\bid="([^"]+)"[^>]*>/g)) {
    const listeners={};elements.set(m[1],{listeners,disabled:/\bdisabled\b/.test(m[0]),hidden:/\bhidden\b/.test(m[0]),checked:false,textContent:'',className:'',addEventListener(name,fn){listeners[name]=fn},focus(){}});
  }
  let state={adblockEnabled:true,privacyEnabled:false,disabled_domains:[],...initial},scans=0,writes=0,fail=false;
  const browser={runtime:{getManifest:()=>({version:'8.3.2'}),openOptionsPage:async()=>{},sendMessage:async m=>m.type==='GET_STATS'?({ok:true,blockedCount:1234,tabBlocked:7,siteDisabled:state.disabled_domains.some(x=>m.host===x||m.host.endsWith('.'+x)),settings:state}):({ok:true})},
    tabs:{query:async()=>[tab],reload:async()=>{},sendMessage:async()=>{scans++;return {ok:true,analyzed:2,hidden:1}}},
    storage:{onChanged:{addListener(fn){storageListeners.push(fn)}},local:{get:async defaults=>({...defaults,...state}),set:async values=>{if(fail)throw Error('storage failed');state={...state,...values};writes++;const changes=Object.fromEntries(Object.keys(values).map(key=>[key,{}]));storageListeners.forEach(fn=>fn(changes,'local'))}}}};
  vm.runInNewContext(source,{URL,browser,document:{getElementById:id=>elements.get(id),addEventListener:(name,fn)=>documentEvents[name]=fn},window:{addEventListener(){}}});
  await documentEvents.DOMContentLoaded();
  return {field:id=>elements.get(id),get state(){return state},get scans(){return scans},get writes(){return writes},fail(){fail=true},async fire(id,name='click'){await elements.get(id).listeners[name]();await new Promise(setImmediate)}};
}
test('internal page hides inapplicable site toggle and never claims site blocks',async()=>{
  const h=await harness({}, {id:10,url:'about:blank',incognito:false});
  assert.equal(h.field('site-control').hidden,true);assert.equal(h.field('site-blocks').textContent,'—');assert.equal(h.field('current-domain').textContent,'Página segura de Nexo');assert.equal(h.field('shield-status').textContent,'Listo');
});
test('AI stays inactive by default; only actual counters are shown',async()=>{
  const h=await harness();assert.equal(h.scans,0);assert.equal(h.writes,0);assert.equal(h.field('scan-ai').disabled,true);assert.equal(h.field('site-blocks').textContent,'7');assert.equal(h.field('total-blocks').textContent,(1234).toLocaleString('es'));
});
test('re-enabling subdomain removes its inherited parent exclusion',async()=>{
  const h=await harness({disabled_domains:['example.com','other.example']},{id:10,url:'https://sub.example.com/page',incognito:false});
  assert.equal(h.field('site-toggle').checked,false);assert.match(h.field('site-hint').textContent,/example.com/);
  h.field('site-toggle').checked=true;await h.fire('site-toggle','change');assert.deepEqual(Array.from(h.state.disabled_domains),['other.example']);assert.equal(h.field('site-toggle').checked,true);
});
test('AI scan requires exact authorized host and explicit consent',async()=>{
  const config={enabled:true,consent:true,allowedHosts:['example.com']};
  const mismatch=await harness({aiConfig:config});assert.equal(mismatch.field('scan-ai').disabled,true);
  const denied=await harness({aiConfig:{...config,consent:false,allowedHosts:['www.example.com']}});assert.equal(denied.field('scan-ai').disabled,true);
  const accepted=await harness({aiConfig:{...config,allowedHosts:['www.example.com']}});assert.equal(accepted.field('scan-ai').disabled,false);assert.equal(accepted.scans,0);await accepted.fire('scan-ai');assert.equal(accepted.scans,1);
});
test('AI scanning is disabled in private tabs and for paused protection',async()=>{
  const aiConfig={enabled:true,consent:true,allowedHosts:['www.example.com']};
  const privateTab=await harness({aiConfig},{id:10,url:'https://www.example.com/page',incognito:true});assert.equal(privateTab.field('scan-ai').disabled,true);
  const paused=await harness({aiConfig,disabled_domains:['example.com']});assert.equal(paused.field('scan-ai').disabled,true);
});
test('failed setting change restores actual state instead of showing false success',async()=>{
  const h=await harness();h.fail();h.field('adblock-toggle').checked=false;await h.fire('adblock-toggle','change');assert.equal(h.state.adblockEnabled,true);assert.equal(h.field('adblock-toggle').checked,true);assert.match(h.field('message').textContent,/No se pudo guardar/);
});
