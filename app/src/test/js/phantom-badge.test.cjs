const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assets = path.join(__dirname, '../../main/assets/extensions/phantomshield');
const manifest = JSON.parse(fs.readFileSync(path.join(assets, 'manifest.json')));

async function harness(initial = {}) {
  let time = 0, id = 0;
  const timers = new Map(), writes = [], stored = { ...initial };
  const event = () => ({ listeners: [], addListener(fn) { this.listeners.push(fn); } });
  const changed = event(), requests = event(), messages = event(), removed = event(), updated = event();
  const browser = {
    storage: { onChanged: changed, local: {
      async get(defaults) { return typeof defaults === 'string' ? { [defaults]: stored[defaults] } : { ...defaults, ...stored }; },
      async set(values) {
        const changes = Object.fromEntries(Object.entries(values).map(([key, newValue]) => [key, { newValue, oldValue: stored[key] }]));
        Object.assign(stored, values); changed.listeners.forEach(fn => fn(changes, 'local'));
      }
    } },
    browserAction: { setBadgeText: details => { writes.push({ at: time, ...details }); return Promise.resolve(); } },
    runtime: { id: 'phantom', getURL: file => 'moz-extension://phantom/' + file, onMessage: messages, onInstalled: event(), openOptionsPage: async () => {} },
    webRequest: { onBeforeRequest: requests }, tabs: { onRemoved: removed, onUpdated: updated },
    alarms: { create() {}, onAlarm: event() }, permissions: { onRemoved: event(), getAll: async () => ({ data_collection: [] }) }
  };
  const context = vm.createContext({ browser, URL, Map, Set, AbortController, TextDecoder,
    console: { warn() {}, log() {}, info() {} }, fetch: async () => { throw new Error('offline'); },
    setTimeout: (fn, delay) => { timers.set(++id, { fn, at: time + delay }); return id; }, clearTimeout: value => timers.delete(value)
  });
  context.window = context;
  for (const file of manifest.background.scripts) vm.runInContext(fs.readFileSync(path.join(assets, file), 'utf8'), context);
  await new Promise(setImmediate);
  function tick(ms) {
    const end = time + ms;
    while (true) {
      const next = [...timers].filter(([, task]) => task.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      const [value, task] = next; time = task.at; timers.delete(value); task.fn();
    }
    time = end;
  }
  return {
    tick, writes, storage: browser.storage.local,
    message: value => messages.listeners[0](value, { id: browser.runtime.id }),
    request: values => requests.listeners[0]({ url: 'https://doubleclick.net/ad.js', documentUrl: 'https://example.com/', type: 'script', tabId: 10, incognito: false, ...values }),
    closeTab: id => removed.listeners.forEach(fn => fn(id)),
    navigate: url => updated.listeners.forEach(fn => fn(10, { url })),
    global: () => writes.filter(x => x.tabId === undefined).at(-1)?.text,
    tab: (id = 10) => writes.filter(x => x.tabId === id).at(-1)?.text
  };
}

test('initial badge reflects stored total and exposes only count payloads', async () => {
  const h = await harness({ blockedCount: 7 }); h.tick(1000); assert.equal(h.global(), '7');
  assert.equal(h.writes.length, 1); assert.deepEqual(Object.keys(h.writes[0]).sort(), ['at', 'text']);
  h.tick(10000); assert.equal(h.writes.length, 1, 'no polling after initialization');
});
test('blocked bursts update default and per-tab counts once per second', async () => {
  const h = await harness(); h.tick(1000); const before = h.writes.length;
  for (let i = 0; i < 80; i++) assert.equal(h.request().cancel, true);
  h.tick(999); assert.equal(h.writes.length, before);
  h.tick(1); assert.equal(h.global(), '80'); assert.equal(h.tab(), '80');
  assert.equal(h.writes.length, before + 2);
  h.tick(10000); assert.equal(h.writes.length, before + 2);
});
test('global disabling, site exclusions and main-frame navigation update action state', async () => {
  const h = await harness(); h.request(); h.tick(1000);
  await h.storage.set({ adblockEnabled: false }); h.tick(1000);
  assert.equal(h.global(), 'OFF'); assert.equal(h.tab(), 'OFF');
  await h.storage.set({ adblockEnabled: true, disabled_domains: ['example.com'] }); h.tick(1000);
  assert.equal(h.global(), '1'); assert.equal(h.tab(), 'PAUSE');
  h.request({ type: 'main_frame', url: 'https://other.example/page' }); h.tick(1000);
  assert.equal(h.tab(), '0');
});
test('private blocks affect ephemeral page count but never the global total badge', async () => {
  const h = await harness({ blockedCount: 9 }); h.tick(1000); const globalWrites = h.writes.filter(x => x.tabId === undefined).length;
  h.request({ incognito: true }); h.tick(1000);
  assert.equal(h.global(), '9'); assert.equal(h.tab(), '1');
  assert.equal(h.writes.filter(x => x.tabId === undefined).length, globalWrites);
});
test('reset clears page/default badges and tab closure cancels pending tab writes', async () => {
  const h = await harness(); h.request(); h.tick(1000);
  await h.message({ type: 'RESET_STATS' }); h.tick(1000);
  assert.equal(h.global(), '0'); assert.equal(h.tab(), '0');
  h.request(); h.closeTab(10); const before = h.writes.filter(x => x.tabId === 10).length; h.tick(1000);
  assert.equal(h.writes.filter(x => x.tabId === 10).length, before);
});
test('counts cap at 999+ and fragment URL changes do not reset a page count', async () => {
  const h = await harness(); for (let i = 0; i < 1002; i++) h.request(); h.tick(1000);
  assert.equal(h.global(), '999+'); assert.equal(h.tab(), '999+');
  const before = h.writes.length; h.navigate('https://example.com/#section'); h.tick(1000);
  assert.equal(h.tab(), '999+'); assert.equal(h.writes.length, before, 'unchanged badge is not re-published');
});
