const {test}=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.join(__dirname,'../../main/assets/extensions/nexo_network');
function harness({privacy=true,proxy=true,session={routes:{}}}={}) {
 const listeners={},states={rtc:true,predict:true},timers=[];
 const setting=key=>({set:async({value})=>{states[key]=value},get:async()=>({value:states[key]}),clear:async()=>{states[key]=true}});
 const browser={storage:{session:{get:async()=>session,set:async values=>Object.assign(session,values)}},runtime:{id:'network',getURL:x=>'moz-extension://network/'+x,onMessage:{addListener:fn=>listeners.message=fn}},
  webRequest:{onBeforeRequest:{addListener:fn=>listeners.request=fn}},tabs:{onRemoved:{addListener:fn=>listeners.remove=fn}}};
 if(privacy)browser.privacy={network:{peerConnectionEnabled:setting('rtc'),networkPredictionEnabled:setting('predict')}};
 if(proxy)browser.proxy={onRequest:{addListener:fn=>listeners.proxy=fn}};
 const ctx=vm.createContext({browser,URL,setTimeout:(fn,ms)=>{timers.push({fn,ms});return timers.length}});
 for(const file of ['policy.js','background.js'])vm.runInContext(fs.readFileSync(path.join(root,file),'utf8'),ctx);
 const sender=id=>({id:'network',frameId:0,url:'moz-extension://network/bootstrap.html',tab:{id}});
 return {states,timers,policy:ctx.NexoRoutePolicy,
  apply:(id,profile)=>listeners.message({type:'APPLY_ROUTE',profile},sender(id)),
  message:(m,s)=>listeners.message(m,s),proxy:details=>listeners.proxy(details),request:d=>listeners.request(d),remove:id=>listeners.remove(id)};
}
test('SOCKS route applies to only its tab, resolves DNS remotely and has no direct fallback',async()=>{
 const h=harness();assert.equal((await h.apply(7,{type:'socks',host:'127.0.0.1',port:9050})).ok,true);
 const result=h.proxy({tabId:7});assert.equal(result[0].type,'socks');assert.equal(result[0].proxyDNS,true);assert.equal(result[1],null);
 assert.equal(h.proxy({tabId:8}),undefined);assert.equal(h.states.rtc,false);assert.equal(h.states.predict,false);
 assert.equal(h.request({tabId:-1,url:'https://site.com/background'}).cancel,true);
 const content=await h.message({type:'ROUTE_POLICY'},{id:'network',url:'https://site.com',tab:{id:7}});
 assert.equal(content.host,undefined);assert.equal(content.port,undefined);
 h.remove(7);await new Promise(setImmediate);assert.equal(h.states.rtc,true);assert.equal(h.proxy({tabId:7}),undefined);
});
test('web pages cannot replace proxy policy',async()=>{
 const h=harness();assert.equal(await h.message({type:'APPLY_ROUTE',profile:{type:'socks',host:'evil.com',port:1080}},
  {id:'network',frameId:0,url:'https://site.com',tab:{id:1}}),undefined);assert.equal(await h.proxy({tabId:1}),undefined);
});
test('missing proxy or privacy capability rejects routing rather than acknowledging it',async()=>{
 for(const options of [{privacy:false},{proxy:false}]){const h=harness(options);assert.equal((await h.apply(1,{type:'socks',host:'localhost',port:9050})).ok,false)}
 const direct=harness({privacy:false,proxy:false});assert.equal((await direct.apply(1,{type:'direct',burner:true,seed:42})).ok,true);
});
test('simulation latency delays only the selected tab and geolocation does not contain proxy data',async()=>{
 const h=harness();await h.apply(2,{type:'direct',latency:250,geo:{lat:4.7,lon:-74.1}});
 assert.equal(h.proxy({tabId:2}),undefined);
 const waiting=h.request({tabId:2,url:'https://site.com'});assert.equal(h.timers[0].ms,250);h.timers[0].fn();assert.deepEqual({...await waiting},{});
 assert.deepEqual({...h.request({tabId:3,url:'https://site.com'})},{});
 assert.throws(()=>h.policy.validate({type:'socks',host:'host',port:0}));assert.throws(()=>h.policy.validate({type:'direct',geo:{lat:91,lon:0}}));
});

test('routing survives a background restart using RAM-only session storage',async()=>{
 const session={routes:{}};const first=harness({session});await first.apply(5,{type:'socks',host:'localhost',port:1080});
 const restarted=harness({session});const route=await restarted.proxy({tabId:5});assert.equal(route[0].host,'localhost');assert.equal(route[1],null);
 assert.equal(restarted.states.rtc,false);assert.equal(await restarted.proxy({tabId:6}),undefined);
});

test('routing component packages every declared script and bootstrap resource',()=>{
 const manifest=JSON.parse(fs.readFileSync(path.join(root,'manifest.json'),'utf8'));
 const files=[...manifest.background.scripts,...manifest.content_scripts.flatMap(script=>script.js),manifest.options_ui.page,'bootstrap.js'];
 for(const file of files)assert.equal(fs.statSync(path.join(root,file)).isFile(),true,file);
 assert.equal(manifest.web_accessible_resources,undefined);
 assert.ok(manifest.permissions.includes('nativeMessagingFromContent'));
});
