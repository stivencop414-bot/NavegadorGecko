const {chromium}=require('playwright'),assert=require('node:assert/strict');
const reports=require('node:path').resolve(__dirname,'../app/build/reports/browser');require('node:fs').mkdirSync(reports,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.NEXO_CHROMIUM||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 try{
 const page=await browser.newPage({viewport:{width:390,height:844},colorScheme:'dark'}),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  let state={aiConfig:{enabled:true,consent:true,endpoint:'https://api.example/v1',model:'small',scope:'public',rememberDecisions:false},adblockEnabled:true,privacyEnabled:false,disabled_domains:[]};
  let domains=[{host:'news.com',rules:4,hits:8,lastUsed:Date.now()},{host:'other.com',rules:1,hits:2,lastUsed:Date.now()}];
  const listeners=[];
  async function set(values){Object.assign(state,values);listeners.forEach(fn=>fn(Object.fromEntries(Object.entries(values).map(([k,v])=>[k,{newValue:v}])),'local'));}
  window.browser={storage:{local:{get:async defaults=>({...defaults,...state}),set},onChanged:{addListener:fn=>listeners.push(fn),removeListener(){}}},
   permissions:{getAll:async()=>({data_collection:[]}),request:async()=>true},
   runtime:{getManifest:()=>({version:'8.8.0'}),openOptionsPage:async()=>{},onMessage:{addListener(){},removeListener(){}},sendMessage:async m=>{
    if(m.type.startsWith('AI_MEMORY_')){
     if(m.type==='AI_MEMORY_CLEAR')domains=[];
     if(m.type==='AI_MEMORY_FORGET'||m.type==='AI_MEMORY_EXCLUDE')domains=domains.filter(d=>d.host!==m.host);
     if(m.type==='AI_MEMORY_EXCLUDE')await set({aiConfig:{...state.aiConfig,excludedHosts:[m.host]}});
     return {ok:true,domains};
    }
    return {ok:true,blockedCount:120,tabBlocked:12,tabAi:{analyzed:9,hidden:6,local:1,model:2,memory:3,cache:0,code:'ok'},siteDisabled:false,settings:state};
   }},tabs:{query:async()=>[{id:1,url:'https://news.com/',incognito:false}],sendMessage:async()=>({ok:true,analyzed:2,hidden:1})}};
 });
 await page.goto((process.env.NEXO_TEST_ORIGIN||'http://127.0.0.1:8780')+'/options/options.html');
 await page.waitForFunction(()=>document.querySelectorAll('.memory-domain').length===2);
 assert.equal(await page.locator('#remember-decisions').isChecked(),false);
 await page.locator('#remember-decisions').check();await page.locator('#save').click();
 await page.waitForFunction(()=>document.getElementById('status').textContent.includes('guardada'));
 assert.equal(await page.evaluate(async()=>(await browser.storage.local.get({})).aiConfig.rememberDecisions),true);
 for(const theme of ['dark','light']){await page.emulateMedia({colorScheme:theme});for(const width of [320,390,600]){
  await page.setViewportSize({width,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,theme+' '+width);
 }}
 await page.setViewportSize({width:390,height:844});await page.emulateMedia({colorScheme:'dark'});
 await page.locator('.memory-card').screenshot({path:reports+'/memory.png'});
 await page.locator('#memory-search').fill('news');assert.equal(await page.locator('.memory-domain').count(),1);
 await page.getByRole('button',{name:'Excluir de IA news.com',exact:true}).click();
 await page.waitForFunction(()=>document.getElementById('excluded-hosts').value==='news.com');
 await page.locator('#save').click();await page.waitForFunction(()=>!document.getElementById('save').disabled);
 assert.deepEqual(await page.evaluate(async()=>(await browser.storage.local.get({})).aiConfig.excludedHosts),['news.com']);
 await page.locator('#memory-search').fill('');await page.getByRole('button',{name:'Olvidar other.com',exact:true}).click();
 await page.waitForFunction(()=>document.querySelectorAll('.memory-domain').length===0);
 assert.equal(await page.locator('#memory-clear').isDisabled(),true);
 await page.goto((process.env.NEXO_TEST_ORIGIN||'http://127.0.0.1:8780')+'/popup/popup.html');await page.waitForFunction(()=>!document.getElementById('scan-ai').disabled);
 assert.match(await page.locator('#ai-page').textContent(),/2 del modelo · 3 de memoria/);
 await page.screenshot({path:reports+'/popup.png',fullPage:true});
 assert.deepEqual(errors,[]);console.log('UI browser: memory opt-in/save/search/forget/exclude, exclusion survives saving, 320/390/600 light/dark and source diagnostics passed.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
