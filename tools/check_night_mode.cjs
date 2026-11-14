// Optional real Chromium smoke test. Install Playwright separately; no app runtime dependency.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const path = require('node:path');
(async () => {
  const browser = await chromium.launch({headless:true, executablePath:process.env.NEXO_CHROMIUM || undefined});
  try {
    const page = await browser.newPage({viewport:{width:412,height:915}});
    const errors=[]; page.on('pageerror', e=>errors.push(String(e)));
    const dir=path.resolve(__dirname,'../app/src/main/assets/extensions/nexo_bridge');
    await page.setContent('<style>body { background:white; color:black } .card {background:#fff; color:#111;padding:20px} input{background:white;color:black}</style><body><h1>Nexo night test</h1><div class="card">Readable text</div><input value="hello"><video></video><img alt="test" src="data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2220%22 height=%2220%22%3E%3Crect width=%2220%22 height=%2220%22 fill=%22red%22/%3E%3C/svg%3E"></body>');
    await page.evaluate(()=>{window.browser={runtime:{sendMessage:async()=>true}};});
    await page.addScriptTag({path:path.join(dir,'vendor/darkreader.js')});
    await page.addScriptTag({path:path.join(dir,'night.js')});
    await page.evaluate(()=>NexoNight.apply({enabled:true,background:'#181a1b'}));
    await page.waitForFunction(()=>getComputedStyle(document.body).backgroundColor !== 'rgb(255, 255, 255)');
    await page.evaluate(()=>{const el=document.createElement('div');el.className='card';el.textContent='Dynamic';document.body.append(el);});
    await page.waitForFunction(()=>getComputedStyle(document.querySelector('.card:last-child')).backgroundColor !== 'rgb(255, 255, 255)');
    const colors=await page.evaluate(()=>({body:getComputedStyle(document.body).backgroundColor,text:getComputedStyle(document.body).color,
      image:getComputedStyle(document.querySelector('img')).filter,video:getComputedStyle(document.querySelector('video')).filter}));
    assert.equal(colors.image,'none'); assert.equal(colors.video,'none');
    await page.evaluate(()=>NexoNight.apply({enabled:false}));
    await page.waitForFunction(()=>getComputedStyle(document.body).backgroundColor === 'rgb(255, 255, 255)');
    assert.deepEqual(errors,[]);
    console.log(JSON.stringify({passed:true,colors,restored:true}));
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
