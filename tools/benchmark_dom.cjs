// Compare ten identical desktop DOM fixtures; this does not measure Android page-load speed.
const { chromium } = require('playwright');
const fs=require('node:fs'),path=require('node:path'),{execFileSync}=require('node:child_process');
const file='app/src/main/assets/extensions/phantomshield/content/cosmetic-filter.js';
const before=execFileSync('git',['show',`${process.env.NEXO_BASE || '40f1ced759934989c2ebdbccf6c737f69a55972a'}:${file}`],{encoding:'utf8'});
const after=fs.readFileSync(path.resolve(__dirname,'..',file),'utf8');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.NEXO_CHROMIUM || undefined});
 try {
  const results={before:[],after:[]};
  for(let run=0;run<10;run++)for(const [label,source] of [['before',before],['after',after]]){
   const page=await browser.newPage();
   await page.setContent('<style>[data-phantomshield-hidden="true"]{display:none}</style><main>'+Array.from({length:12000},(_,i)=>`<div class="item c${i%500}" id="n${i}">row</div>`).join('')+'</main>');
   const sample=await page.evaluate(async source=>{
    window.browser={runtime:{sendMessage:async m=>m.type==='GET_COSMETIC'?{enabled:true,generic:true,injected:true}:{enabled:true,injected:true}},storage:{onChanged:{addListener(){}}}};
    let maxSlice=0,walks=0,queries=0;
    const original=document.createTreeWalker.bind(document);
    document.createTreeWalker=(...args)=>{const walker=original(...args),next=walker.nextNode.bind(walker);walker.nextNode=()=>{walks++;return next();};return walker;};
    const query=Element.prototype.querySelectorAll;
    Element.prototype.querySelectorAll=function(...args){queries++;return query.apply(this,args);};
    const timer=window.setTimeout.bind(window);
    window.setTimeout=(fn,ms,...args)=>timer(()=>{const t=performance.now();fn(...args);maxSlice=Math.max(maxSlice,performance.now()-t);},ms);
    const start=performance.now();
    (0,eval)(source);await Promise.resolve();await Promise.resolve();
    const setupMs=performance.now()-start;
    await new Promise(resolve=>timer(resolve,900));
    return {setupMs,maxSliceMs:maxSlice,walks,queries};
   },source);
   results[label].push(sample);await page.close();
  }
  console.log(JSON.stringify({environment:'desktop Chromium, 12000 nodes, ten paired runs',results},null,2));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
