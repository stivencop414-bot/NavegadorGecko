const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../../main/assets/extensions/phantomshield');
const html = fs.readFileSync(path.join(root, 'options/options.html'), 'utf8');
const source = fs.readFileSync(path.join(root, 'options/options.js'), 'utf8');
const configSource = fs.readFileSync(path.join(root, 'data/ai-config.js'), 'utf8');
const base = {enabled:false,consent:false,endpoint:'',model:'',apiKey:'',allowedHosts:[],timeoutMs:5000,jsonMode:false};
async function harness(config={},permissions={data_collection:[]}) {
  const elements = new Map();
  for(const match of html.matchAll(/<[^>]*\bid="([^"]+)"[^>]*>/g)) {
    const listeners = {};
    elements.set(match[1], {listeners, value:'',checked:false,hidden:/\bhidden\b/.test(match[0]),disabled:/\bdisabled\b/.test(match[0]),type:(match[0].match(/\btype="([^"]+)"/)||[])[1]||'',attributes:{},textContent:'',
      addEventListener(name,fn){listeners[name]=fn},setAttribute(name,value){this.attributes[name]=value},focus(){this.focused=true}});
  }
  let stored = {...base,...config},allowed=true,failStorage=false,writes=0,throwRequest=false,sent=[],testReply={ok:true,ms:900,detected:true,kept:true};
  const makeElement = () => ({children:[],listeners:{},textContent:'',className:'',setAttribute(){},addEventListener(name,fn){this.listeners[name]=fn},appendChild(child){this.children.push(child)}});
  for(const element of elements.values()){element.children=[];element.replaceChildren=function(){this.children=[]};element.appendChild=function(child){this.children.push(child)}}
  const context = vm.createContext({URL,document:{getElementById:id=>elements.get(id),createElement:makeElement},browser:{storage:{local:{get:async()=>({aiConfig:stored}),set:async values=>{if(failStorage)throw Error('storage unavailable');stored=values.aiConfig;writes++}}},permissions:{getAll:async()=>permissions,request:async()=>{if(throwRequest)throw Error('unsupported');return allowed}},runtime:{sendMessage:async message=>{sent.push(message);return testReply}}}});
  vm.runInContext(configSource,context);vm.runInContext(source,context);await new Promise(setImmediate);
  return {field:id=>elements.get(id),get saved(){return stored},get writes(){return writes},allow:()=>allowed=true,deny:()=>allowed=false,fail:()=>failStorage=true,unsupported:()=>throwRequest=true,sent,reply:value=>testReply=value,
    fire:async(id,event='click')=>{await elements.get(id).listeners[event]({preventDefault(){}});await new Promise(setImmediate)}};
}
test('local-only is the default; hidden fields cannot require a provider',async()=>{
  const h=await harness();
  assert.equal(h.field('enabled').checked,false);assert.equal(h.field('ai-fields').hidden,true);assert.equal(h.field('ai-fields').disabled,true);assert.equal(h.writes,0);
  h.field('endpoint').value='invalid draft';await h.fire('ai-form','submit');assert.equal(h.saved.enabled,false);assert.equal(h.saved.endpoint,'');
});
test('the saved secret is never populated, including show-key',async()=>{
  const h=await harness({apiKey:'saved-secret'});
  assert.equal(h.field('api-key').value,'');await h.fire('show-key');assert.equal(h.field('api-key').value,'');assert.equal(h.field('api-key').type,'text');
});
test('deleting saved key requires confirmation then disables AI and clears consent',async()=>{
  const h=await harness({enabled:true,consent:true,endpoint:'https://api.example/v1',model:'fast',apiKey:'saved-secret'});
  await h.fire('delete-key');assert.equal(h.saved.apiKey,'saved-secret');assert.equal(h.field('delete-confirm').hidden,false);
  await h.fire('cancel-delete');assert.equal(h.saved.apiKey,'saved-secret');assert.equal(h.field('delete-confirm').hidden,true);
  await h.fire('delete-key');await h.fire('confirm-delete');assert.equal(h.saved.apiKey,'');assert.equal(h.saved.enabled,false);assert.equal(h.saved.consent,false);assert.equal(h.field('ai-fields').hidden,true);
});
test('failed key deletion preserves the saved configuration',async()=>{
  const h=await harness({apiKey:'saved-secret'});h.fail();await h.fire('delete-key');await h.fire('confirm-delete');
  assert.equal(h.saved.apiKey,'saved-secret');assert.match(h.field('status').textContent,/No se pudo borrar/);
});
test('changing endpoint cannot silently send a saved key to another provider',async()=>{
  const h=await harness({enabled:true,consent:true,endpoint:'https://first.example/v1',model:'fast',apiKey:'saved-secret'});
  h.field('endpoint').value='https://second.example/v1';h.field('consent').checked=true;await h.fire('ai-form','submit');
  assert.equal(h.writes,0);assert.equal(h.saved.endpoint,'https://first.example/v1');assert.match(h.field('status').textContent,/clave nueva/);
});
test('native permission refusal prevents enabling AI',async()=>{
  const h=await harness();h.field('enabled').checked=true;await h.fire('enabled','change');h.field('endpoint').value='https://api.example/v1';h.field('model').value='fast';h.field('hosts').value='www.example.com';h.field('consent').checked=true;h.deny();await h.fire('ai-form','submit');
  assert.equal(h.writes,0);assert.equal(h.saved.enabled,false);assert.match(h.field('status').textContent,/Autoriza/);
});
test('local-only ignores invalid unsaved connection fields and retains key',async()=>{
  const h=await harness({enabled:true,consent:true,endpoint:'https://api.example/v1',model:'fast',apiKey:'saved-secret'});
  h.field('endpoint').value='invalid';await h.fire('local-only');assert.equal(h.saved.enabled,false);assert.equal(h.saved.consent,false);assert.equal(h.saved.apiKey,'saved-secret');assert.equal(h.field('ai-fields').hidden,true);
});

test('domain chips validate exact hosts and remain drafts until saving',async()=>{
  const h=await harness();h.field('new-domain').value='*.example.com';await h.fire('add-domain');assert.equal(h.field('hosts').value,'');assert.equal(h.writes,0);
  h.field('new-domain').value='www.example.com';await h.fire('add-domain');assert.equal(h.field('hosts').value,'www.example.com');assert.equal(h.field('domain-chips').children.length,1);assert.equal(h.writes,0);
  await h.field('domain-chips').children[0].children[1].listeners.click();assert.equal(h.field('hosts').value,'');
});
test('timeout presets change the draft without enabling AI',async()=>{
  const h=await harness();await h.fire('timeout-8');assert.equal(Number(h.field('timeout').value),8000);assert.equal(h.saved.enabled,false);assert.equal(h.writes,0);
});

test('automatic scope requires renewed consent and saves exclusions and shared budgets',async()=>{
  const h=await harness({enabled:true,consent:true,endpoint:'https://api.example/v1',model:'small'});
  h.field('scope').value='public';await h.fire('scope','change');
  assert.equal(h.field('consent').checked,false);assert.equal(h.field('selected-sites').hidden,true);
  await h.fire('ai-form','submit');assert.equal(h.writes,0);
  h.field('consent').checked=true;h.field('excluded-hosts').value='bank.com\nwork.com';
  h.field('requests-minute').value=3;h.field('requests-hour').value=20;
  await h.fire('ai-form','submit');assert.equal(h.saved.scope,'public');
  assert.deepEqual(Array.from(h.saved.excludedHosts),['bank.com','work.com']);assert.equal(h.saved.requestsPerHour,20);
});

test('provider selection prefills URL and requires renewed consent without exposing saved key',async()=>{
 const h=await harness({enabled:true,consent:true,endpoint:'https://api.example/v1',model:'small',apiKey:'secret'});
 h.field('provider').value='claude';await h.fire('provider','change');
 assert.equal(h.field('endpoint').value,'https://api.anthropic.com/v1');assert.equal(h.field('consent').checked,false);
 assert.equal(h.field('api-key').value,'');assert.equal(h.field('provider-advanced').open,false);
});

test('an unsupported native permission prompt no longer blocks enabling AI; the form consent decides',async()=>{
  const h=await harness();h.field('enabled').checked=true;await h.fire('enabled','change');
  h.field('endpoint').value='https://api.example/v1';h.field('model').value='fast';h.field('scope').value='public';h.field('consent').checked=true;
  h.unsupported();await h.fire('ai-form','submit');
  assert.equal(h.saved.enabled,true);assert.equal(h.saved.consent,true);
  // Sin el consentimiento del formulario sigue sin activarse.
  const strict=await harness();strict.field('enabled').checked=true;await strict.fire('enabled','change');
  strict.field('endpoint').value='https://api.example/v1';strict.field('model').value='fast';strict.unsupported();await strict.fire('ai-form','submit');
  assert.equal(strict.writes,0);
});
test('detail level defaults to detailed for new setups, is kept for saved ones and changing it renews consent',async()=>{
  const fresh=await harness();assert.equal(fresh.field('detail').value,'rich');
  const saved=await harness({enabled:true,consent:true,endpoint:'https://api.example/v1',model:'small',scope:'public'});
  assert.equal(saved.field('detail').value,'basic');assert.equal(saved.field('consent-detail').hidden,true);
  saved.field('detail').value='rich';await saved.fire('detail','change');
  assert.equal(saved.field('consent').checked,false);assert.equal(saved.field('consent-detail').hidden,false);
  await saved.fire('ai-form','submit');assert.equal(saved.writes,0);
  saved.field('consent').checked=true;await saved.fire('ai-form','submit');assert.equal(saved.saved.detail,'rich');
});
test('test connection sends the unsaved draft, never the stored key, and explains the result',async()=>{
  const h=await harness({apiKey:'saved-secret',endpoint:'https://api.example/v1',model:'old'});
  h.field('provider').value='claude';await h.fire('provider','change');h.field('model').value='claude-opus-5-5';
  await h.fire('test-ai');
  assert.equal(h.sent[0].type,'AI_TEST');assert.equal(h.sent[0].config.model,'claude-opus-5-5');assert.equal(h.sent[0].config.apiKey,'');
  assert.equal(JSON.stringify(h.sent).includes('saved-secret'),false);
  assert.match(h.field('test-result').textContent,/Conexión correcta/);assert.equal(h.writes,0);
  h.reply({ok:false,code:'auth',http:401,error:'El proveedor rechazó la clave de API. Revísala en Configurar IA.'});
  await h.fire('test-ai');assert.match(h.field('test-result').textContent,/rechazó la clave.*HTTP 401/);
});
test('provider presets offer one-tap model suggestions',async()=>{
  const h=await harness();h.field('provider').value='claude';await h.fire('provider','change');
  const chips=h.field('model-chips').children;assert.ok(chips.length>=3);
  assert.equal(chips[0].textContent,'claude-haiku-5-5');chips[0].listeners.click();assert.equal(h.field('model').value,'claude-haiku-5-5');
});
