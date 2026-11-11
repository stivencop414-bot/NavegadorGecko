const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assets = path.join(__dirname, '../../main/assets/extensions/phantomshield');
const manifest = JSON.parse(fs.readFileSync(path.join(assets, 'manifest.json')));

async function background() {
  const event = () => ({ listeners: [], addListener(fn) { this.listeners.push(fn); } });
  const changed = event(), beforeRequest = event(), message = event(), timers = [];
  const stored = {};
  let calls = 0;
  const browser = {
    storage: { onChanged: changed, local: {
      async get(defaults) {
        if (typeof defaults === 'string') return { [defaults]: stored[defaults] };
        return { ...defaults, ...stored };
      },
      async set(values) {
        const delta = Object.fromEntries(Object.entries(values).map(([key, value]) => [key, { newValue: value, oldValue: stored[key] }]));
        Object.assign(stored, values);
        changed.listeners.forEach(fn => fn(delta, 'local'));
      }
    } },
    runtime: { id: manifest.browser_specific_settings.gecko.id,
      getURL: file => 'moz-extension://phantom/' + file, onMessage: message, onInstalled: event(),
      openOptionsPage: async () => {} },
    webRequest: { onBeforeRequest: beforeRequest },
    tabs: { onRemoved: event(), onUpdated: event() },
    alarms: { create() {}, onAlarm: event() },
    permissions: { onRemoved: event(), getAll: async () => ({ data_collection: [] }) }
  };
  const context = vm.createContext({ browser, URL, Map, Set, TextDecoder, AbortController, console,
    fetch: async () => { calls++; throw new Error('Offline test'); },
    setTimeout: (fn, ms) => { const id = setTimeout(fn, ms); id.unref(); timers.push(id); return id; },
    clearTimeout
  });
  context.window = context;
  for (const file of manifest.background.scripts) {
    vm.runInContext(fs.readFileSync(path.join(assets, file), 'utf8'), context, { filename: file });
  }
  await new Promise(setImmediate);
  return {
    browser, stored, memory:context.PhantomAIMemory, calls: () => calls,
    request: override => beforeRequest.listeners[0]({
      url: 'https://doubleclick.net/ad.js', documentUrl: 'https://example.com/page',
      type: 'script', tabId: 10, incognito: false, ...override
    }),
    message: (value, sender = { id: browser.runtime.id }) => message.listeners[0](value, sender),
    close: () => timers.forEach(clearTimeout)
  };
}

test('every manifest resource is bundled and identity stays stable', () => {
  assert.equal(manifest.browser_specific_settings.gecko.id, 'phantomshield@geckoview.internal');
  const resources = [...manifest.background.scripts, ...Object.values(manifest.icons),
    ...manifest.web_accessible_resources, manifest.browser_action.default_popup,
    manifest.options_ui.page, ...manifest.content_scripts.flatMap(x => [...x.js || [], ...x.css || []])];
  for (const file of resources) assert.ok(fs.statSync(path.join(assets, file)).isFile(), file);
});
test('bundled rules block advertising offline without blocking ordinary pages', async () => {
  const h = await background();
  try {
    assert.equal(h.request().cancel, true);
    assert.equal(h.request({ url: 'https://example.com/article' }).cancel, undefined);
    const state = await h.message({ type: 'GET_STATS', tabId: 10, host: 'example.com' });
    assert.ok(state.blockedCount >= 1);
  } finally { h.close(); }
});
test('global switch and per-site exclusions restore network access', async () => {
  const h = await background();
  try {
    await h.browser.storage.local.set({ adblockEnabled: false });
    assert.equal(h.request().cancel, undefined);
    await h.browser.storage.local.set({ adblockEnabled: true, disabled_domains: ['example.com'] });
    assert.equal(h.request().cancel, undefined);
    assert.equal(h.request({ documentUrl: 'https://other.example/' }).cancel, true);
  } finally { h.close(); }
});
test('private blocks do not increment the persistent global counter', async () => {
  const h = await background();
  try {
    assert.equal(h.request({ incognito: true }).cancel, true);
    const state = await h.message({ type: 'GET_STATS', tabId: 10, host: 'example.com' });
    assert.equal(state.blockedCount, 0);
    assert.equal(state.tabBlocked, 1);
  } finally { h.close(); }
});
test('AI is off by default and makes no provider request', async () => {
  const h = await background();
  try {
    const initial = h.calls();
    const response = await h.message({ type: 'AI_CLASSIFY', candidates: [] }, {
      id: h.browser.runtime.id, frameId: 0, url: 'https://example.com/page',
      tab: { id: 10, url: 'https://example.com/page', incognito: false }
    });
    assert.equal(response.ok, false);
    assert.match(response.error, /IA desactivada/);
    assert.equal(h.calls(), initial);
  } finally { h.close(); }
});

 test('learned-domain management is restricted to own normal extension pages',async()=>{
  const h=await background();try{
   await h.memory.remember('news.com',['a'.repeat(64)],()=>true);
   for(const sender of [
    {id:h.browser.runtime.id,url:'https://news.com',tab:{id:10}},
    {id:'other',url:'moz-extension://phantom/options/options.html'},
    {id:h.browser.runtime.id,url:'moz-extension://phantom/options/options.html',tab:{id:10,incognito:true}}
   ])assert.equal((await h.message({type:'AI_MEMORY_CLEAR'},sender)).ok,false);
   const own={id:h.browser.runtime.id,url:'moz-extension://phantom/options/options.html'};
   assert.equal((await h.message({type:'AI_MEMORY_LIST'},own)).domains.length,1);
   const result=await h.message({type:'AI_MEMORY_EXCLUDE',host:'news.com'},own);
   assert.equal(result.ok,true);assert.equal(result.domains.length,0);assert.ok(h.stored.aiConfig.excludedHosts.includes('news.com'));
   assert.equal(h.stored.aiMemoryRevision,null);
  }finally{h.close()}
 });

 test('idle is visible after detector initialization and restoring ads reverses its badge contribution',async()=>{
  const h=await background();try{
   const sender={id:h.browser.runtime.id,frameId:0,url:'https://news.com/',tab:{id:10,url:'https://news.com/',incognito:false}};
   await h.message({type:'AI_PAGE',restored:true,code:'ready'},sender);
   await h.message({type:'AI_PAGE',idle:true},sender);
   assert.equal((await h.message({type:'GET_STATS',tabId:10})).tabAi.code,'idle');
   await h.message({type:'AI_PAGE',hidden:2,sources:{memory:2},code:'ok'},sender);
   assert.equal((await h.message({type:'GET_STATS',tabId:10})).tabBlocked,2);
   await h.message({type:'AI_PAGE',restored:true,code:'reset'},sender);
   const restored=await h.message({type:'GET_STATS',tabId:10});
   assert.equal(restored.tabBlocked,0);assert.equal(restored.tabAi.hidden,0);assert.equal(restored.tabAi.memory,0);
  }finally{h.close()}
 });
