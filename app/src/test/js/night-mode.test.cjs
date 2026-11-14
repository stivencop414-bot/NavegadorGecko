const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const dir = path.join(__dirname, '../../main/assets/extensions/nexo_bridge');

function harness(dark = false) {
  const doc = new EventTarget(), win = new EventTarget();
  const root = { appendChild() {} };
  Object.assign(doc, { hidden: false, readyState: 'complete', documentElement: root, body: {},
    createElement: () => ({ remove() {} }) });
  let loads = 0, enables = 0, disables = 0, fetchMethod, resolveLoad;
  const context = vm.createContext({ document: doc, window: win, URL, setTimeout, clearTimeout,
    location: { href: 'https://example.com/page', origin: 'https://example.com' },
    getComputedStyle: () => ({ backgroundColor: dark ? 'rgb(20, 20, 20)' : 'rgb(255, 255, 255)' }),
    MutationObserver: class { observe() {} disconnect() {} },
    browser: { runtime: { sendMessage() { loads++; return new Promise(resolve => { resolveLoad = resolve; }); } } },
    DarkReader: { enable() { enables++; }, disable() { disables++; }, setFetchMethod(fn) { fetchMethod = fn; } },
    fetch: async (...args) => args
  });
  vm.runInContext(fs.readFileSync(path.join(dir, 'night.js'), 'utf8'), context);
  return { apply: enabled => context.NexoNight.apply({ enabled, allowFetch: true, background: '#181a1b' }),
    load: () => resolveLoad?.(true), flush: async () => { for (let i=0;i<10;i++) await Promise.resolve(); },
    counts: () => ({loads,enables,disables}),
    hidden(value) { doc.hidden = value; win.dispatchEvent(new Event('visibilitychange')); },
    fetch: url => fetchMethod(url) };
}

test('night engine is lazy and disabling during load cannot recolor the page', async () => {
  const h = harness(); h.apply(false); assert.equal(h.counts().loads, 0);
  h.apply(true); assert.equal(h.counts().loads, 1);
  h.apply(false); h.load(); await h.flush(); assert.equal(h.counts().enables, 0);
});
test('native dark pages do not load the engine', () => {
  const h = harness(true); h.apply(true); assert.equal(h.counts().loads, 0);
});
test('hidden documents release theme observers and resume without reloading engine', async () => {
  const h = harness(); h.apply(true); h.load(); await h.flush();
  assert.equal(h.counts().enables, 1);
  h.hidden(true); assert.equal(h.counts().disables, 1);
  h.hidden(false); await h.flush(); assert.equal(h.counts().enables, 2);
  assert.equal(h.counts().loads, 1);
});
test('theme fetches cannot disclose credentials or access cross-origin resources', async () => {
  const h = harness(); h.apply(true); h.load(); await h.flush();
  for (const url of ['https://other.test/style.css', 'http://example.com/x', 'https://u:p@example.com/x'])
    await assert.rejects(h.fetch(url));
  const result = await h.fetch('https://example.com/style.css');
  assert.equal(result[1].credentials, 'omit'); assert.equal(result[1].redirect, 'error');
});
test('engine loader only accepts its own content-script frame and a fixed packaged path', async () => {
  let listener; const calls = [];
  vm.runInNewContext(fs.readFileSync(path.join(dir, 'night_background.js'), 'utf8'), {
    browser: { runtime: { id: 'nexo', onMessage: { addListener: fn => listener = fn } },
      tabs: { executeScript: async (...args) => calls.push(args) } }
  });
  listener({type:'nexo_night_load'}, {id:'other',tab:{id:7},frameId:2});
  assert.equal(calls.length,0);
  assert.equal(await listener({type:'nexo_night_load',file:'evil.js'}, {id:'nexo',tab:{id:7},frameId:2}),true);
  assert.equal(calls[0][1].file,'vendor/darkreader.js'); assert.equal(calls[0][1].frameId,2);
});
