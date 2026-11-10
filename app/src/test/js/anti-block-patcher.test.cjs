const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const {webcrypto}=require('node:crypto');
const ctx=vm.createContext({URL,crypto:webcrypto,TextEncoder,TextDecoder,Uint8Array,browser:{}});
vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/assets/extensions/phantomshield/background/anti-block-patcher.js'),'utf8'),ctx);
const patcher=ctx.PhantomAntiBlockPatcher;
for(const library of ['blockadblock','fuckadblock'])test('patches only exact known bytes of '+library,async()=>{
 const bytes=fs.readFileSync(path.join(__dirname,'fixtures/'+library+'-3.2.1.js'));
 const url='https://cdnjs.cloudflare.com/ajax/libs/'+library+'/3.2.1/'+library+'.min.js';
 const result=await patcher.rewrite(url,bytes);assert.notEqual(result,bytes);new vm.Script(new TextDecoder().decode(result));
 assert.notEqual(await patcher.rewrite('https://site.example/assets/'+library+'.min.js',bytes),bytes);
 const modified=Buffer.concat([bytes,Buffer.from('\n')]);assert.equal(await patcher.rewrite(url,modified),modified);
 assert.equal(await patcher.rewrite('https://unrelated.com/'+library+'.js',bytes),bytes);
 assert.equal(await patcher.rewrite(url+'?x=1',bytes),bytes);
 const text=new TextDecoder().decode(result);assert.match(text,library==='blockadblock'?/o=!1;this\._options\.debug/:/i=!1;this\._options\.debug/);
});

function streamHarness() {
 const filters=[];
 const context=vm.createContext({URL,crypto:webcrypto,TextEncoder,TextDecoder,Uint8Array,browser:{webRequest:{filterResponseData(){
  const filter={output:[],closed:false,write(chunk){this.output.push(Buffer.from(chunk))},close(){this.closed=true}};
  filters.push(filter);return filter;
 }}}});
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/assets/extensions/phantomshield/background/anti-block-patcher.js'),'utf8'),context);
 return {patcher:context.PhantomAntiBlockPatcher,filters};
}
test('response filtering preserves bytes on overflow and when disabled during a response',async()=>{
 const h=streamHarness(),url='https://site.example/blockadblock.min.js';
 const details={type:'script',url,requestId:'1'};
 let enabled=true,patched=0;
 assert.equal(h.patcher.intercept(details,()=>enabled,()=>patched++),true);
 const original=fs.readFileSync(path.join(__dirname,'fixtures/blockadblock-3.2.1.js'));
 const first=h.filters[0];first.ondata({data:original});enabled=false;await first.onstop();
 assert.deepEqual(Buffer.concat(first.output),original);assert.equal(patched,0);assert.equal(first.closed,true);
 enabled=true;h.patcher.intercept(details,()=>enabled,()=>patched++);
 const large=Buffer.alloc(131073,65),tail=Buffer.from('end'),second=h.filters[1];
 second.ondata({data:large});second.ondata({data:tail});await second.onstop();
 assert.deepEqual(Buffer.concat(second.output),Buffer.concat([large,tail]));assert.equal(patched,0);assert.equal(second.closed,true);
});
test('response filter caps concurrency and releases its slot on errors',()=>{
 const h=streamHarness(),details={type:'script',url:'https://site.example/blockadblock.min.js',requestId:'1'};
 for(let i=0;i<8;i++)assert.equal(h.patcher.intercept(details,()=>true,()=>{}),true);
 assert.equal(h.patcher.intercept(details,()=>true,()=>{}),false);
 h.filters[0].onerror();assert.equal(h.patcher.intercept(details,()=>true,()=>{}),true);
});

function detection(source,name){
 const scope={document:{body:{getAttribute:()=>true}},getComputedStyle:()=>({getPropertyValue:()=> 'none'}),addEventListener(){},attachEvent(){},setTimeout:()=>1,clearTimeout(){},setInterval:()=>1,clearInterval(){},console};
 const sandbox=vm.createContext({window:scope,setTimeout:()=>1});vm.runInContext(source,sandbox);
 const events=[];const fake={_options:{debug:false,loopMaxNumber:5},_var:{bait:{offsetParent:null},loop:null,loopNumber:0},_stopLoop(){},_destroyBait(){},emitEvent(value){events.push(value)}};
 scope[name].prototype._checkBait.call(fake,false);return events;
}
test('verified patch changes the known detector outcome while preserving its callback API',async()=>{
 for(const [library,name] of [['blockadblock','BlockAdBlock'],['fuckadblock','FuckAdBlock']]){
  const original=fs.readFileSync(path.join(__dirname,'fixtures/'+library+'-3.2.1.js'));
  const changed=await patcher.rewrite('https://cdnjs.cloudflare.com/ajax/libs/'+library+'/3.2.1/'+library+'.min.js',original);
  assert.deepEqual(detection(original.toString(),name),[true]);assert.deepEqual(detection(new TextDecoder().decode(changed),name),[false]);
 }
});
