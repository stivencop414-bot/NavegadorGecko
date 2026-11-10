const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const content = path.join(__dirname, '../../main/assets/extensions/phantomshield/content');

function pageHooks(enabled = true, includeNativeBridge = false) {
  let time = 0, id = 0, scans = 0;
  const timers = new Map(), observers = [];
  const attributes = { 'data-phantomshield-adblock': String(enabled) };
  const playingVideo = { paused: false, ended: false, isConnected: true, videoWidth: 640, videoHeight: 360,
    getBoundingClientRect: () => ({ width: 640, height: 360, left: 0, top: 0, right: 640, bottom: 360 }) };
  const document = new EventTarget();
  Object.assign(document, { readyState: 'complete', visibilityState: 'visible',
    documentElement: { getAttribute: name => attributes[name] ?? null },
    querySelector: () => null,
    getElementsByTagName: () => [playingVideo], querySelectorAll: () => [playingVideo],
    getElementById: () => { scans++; return null; }
  });
  const window = new EventTarget();
  window.open = () => null;
  const schedule = (fn, delay) => { timers.set(++id, { fn, at: time + delay }); return id; };
  const context = vm.createContext({
    window, document, location: { hostname: 'www.youtube.com', href: 'https://www.youtube.com/watch?v=test' },
    navigator: { userActivation: { isActive: false } }, URL, Element: class {},
    innerWidth: 640, innerHeight: 360, getComputedStyle: () => ({ display: 'block', visibility: 'visible' }),
    browser: { runtime: { connectNative: () => ({ postMessage() {}, onMessage: { addListener() {} }, onDisconnect: { addListener() {} } }), sendNativeMessage: () => Promise.resolve({ backgroundMedia: true }) } },
    setInterval: () => 99999,
    setTimeout: schedule, clearTimeout: value => timers.delete(value), requestAnimationFrame: fn => schedule(fn, 0),
    MutationObserver: class {
      constructor(fn) { this.fn = fn; this.active = false; observers.push(this); }
      observe() { this.active = true; } disconnect() { this.active = false; }
    }
  });
  if (includeNativeBridge) vm.runInContext(fs.readFileSync(path.join(content, '../../nexo_bridge/content_script.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(content, 'page-hooks.js'), 'utf8'), context);
  function tick(ms) {
    const end = time + ms;
    while (true) {
      const next = [...timers].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      const [value, task] = next; time = task.at; timers.delete(value); task.fn();
    }
    time = end;
  }
  return { tick, scans: () => scans, active: () => observers.filter(x => x.active).length,
    mutate: () => observers.filter(x => x.active).forEach(x => x.fn([])),
    toggle: value => { attributes['data-phantomshield-adblock'] = String(value); document.dispatchEvent(new Event('phantomshield:settings')); },
    visibility: (value, notify = true) => { document.visibilityState = value; if (notify) document.dispatchEvent(new Event('visibilitychange')); },
    focus: () => window.dispatchEvent(new Event('focus')),
    timeupdate: () => document.dispatchEvent(new Event('timeupdate'))
  };
}

test('YouTube observer and repeated DOM queries stop when protection is off', () => {
  const h = pageHooks(); h.tick(300); assert.ok(h.scans() > 0);
  h.toggle(false); const scans = h.scans(); assert.equal(h.active(), 0);
  for (let n = 0; n < 20; n++) { h.mutate(); h.timeupdate(); h.tick(50); }
  assert.equal(h.scans(), scans);
});
test('continuous YouTube mutations are throttled instead of scanning every frame', () => {
  const h = pageHooks();
  for (let n = 0; n < 20; n++) { h.mutate(); h.tick(50); }
  assert.ok(h.scans() >= 1); assert.ok(h.scans() <= 4, 'at most four scans per second');
});
test('YouTube monitoring suspends in hidden tabs and resumes when visible', () => {
  const h = pageHooks(); h.tick(300); h.visibility('hidden'); const scans = h.scans();
  assert.equal(h.active(), 0); h.timeupdate(); h.tick(5000); assert.equal(h.scans(), scans);
  h.visibility('visible'); assert.equal(h.active(), 1); h.tick(300); assert.ok(h.scans() > scans);
});

async function aiPage(visible = true) {
  let id = 0, walks = 0;
  const observers = [], timers = new Map(), nodes = [], messages = [];
  class Element {
    constructor(tag = 'DIV') { this.tagName = tag; this.isConnected = true; this.className = 'ad-banner'; this.id = ''; }
    closest() { return null; } querySelector() { return null; } querySelectorAll() { return []; }
    contains(value) { return value === this; }
    getBoundingClientRect() { return { width: 100, height: 50 }; }
    matches() { return this.tagName === 'DIV'; }
    hasAttribute(name) { return name === 'data-ad-slot'; }
    getAttribute() { return ''; }
  }
  const document = new EventTarget();
  Object.assign(document, { body: new Element('BODY'), activeElement: {}, visibilityState: visible ? 'visible' : 'hidden',
    createTreeWalker: () => { walks++; let index = 0; return { nextNode: () => nodes[index++] || null }; }
  });
  const browser = { runtime: { id: 'phantom', onMessage: { addListener: fn => messages.push(fn) },
    sendMessage: async request => request.type === 'GET_SITE_STATE' ? { enabled: true, aiEnabled: true } :
      { ok: true, decisions: request.candidates.map(x => ({ id: x.id, hide: false })) }
  }, storage: { onChanged: { addListener() {} } } };
  const window = new EventTarget(); window.top = window;
  vm.runInNewContext(fs.readFileSync(path.join(content, 'ai-detector.js'), 'utf8'), {
    document, window, browser, Element, URL, innerWidth: 1000, innerHeight: 800, NodeFilter: { SHOW_ELEMENT: 1, SHOW_TEXT: 4 },
    location: { hostname: 'news.example.com', href: 'https://news.example.com/' },
    getComputedStyle: () => ({ display: 'block' }),
    setTimeout: (fn, ms) => { timers.set(++id, { fn, ms }); return id; }, clearTimeout: value => timers.delete(value),
    MutationObserver: class {
      constructor(fn) { this.fn = fn; this.active = false; observers.push(this); }
      observe(target) { this.target = target; this.active = true; } disconnect() { this.active = false; }
    }
  });
  await Promise.resolve(); await Promise.resolve();
  return { active: () => observers.filter(x => x.active && x.target === document.body).length,
    walks: () => walks, queued: () => timers.size,
    visibility: (value, notify = true) => { document.visibilityState = value; if (notify) document.dispatchEvent(new Event('visibilitychange')); },
    focus: () => window.dispatchEvent(new Event('focus')),
    mutate: () => observers.filter(x => x.active && x.target === document.body).forEach(x => x.fn([])),
    scan: async () => { nodes.push(new Element()); return messages[0]({ type: 'AI_SCAN' }, { id: 'phantom' }); }
  };
}

test('enabled AI does not observe or schedule scans in a background document', async () => {
  const h = await aiPage(false); assert.equal(h.active(), 0); assert.equal(h.queued(), 0); assert.equal(h.walks(), 0);
  h.visibility('visible'); assert.equal(h.active(), 1); assert.equal(h.queued(), 1);
  h.visibility('hidden'); assert.equal(h.active(), 0); assert.equal(h.queued(), 0);
});
test('AI disconnects the document observer once its request batches are exhausted', async () => {
  const h = await aiPage(); assert.equal(h.active(), 1);
  for (let n = 0; n < 4; n++) { const result = await h.scan(); assert.equal(result.ok, true); }
  assert.equal(h.active(), 0); assert.equal(h.queued(), 0);
  const result = await h.scan(); assert.equal(result.ok, false);
});

test('media events suspend and resume YouTube monitoring when native protection consumes visibilitychange', () => {
  const h = pageHooks(); h.tick(300); h.visibility('hidden', false);
  h.timeupdate(); assert.equal(h.active(), 0); const scans = h.scans();
  h.tick(1000); assert.equal(h.scans(), scans);
  h.visibility('visible', false); h.timeupdate(); h.tick(300);
  assert.equal(h.active(), 1); assert.ok(h.scans() > scans);
});
test('AI scheduler checks hidden state independently and focus wakes it without visibilitychange', async () => {
  const h = await aiPage(); h.visibility('hidden', false); h.mutate();
  assert.equal(h.active(), 0); assert.equal(h.queued(), 0);
  h.visibility('visible', false); h.focus();
  assert.equal(h.active(), 1); assert.equal(h.queued(), 1);
});

test('actual Nexo visibility protection and Phantom monitoring coexist without a stuck background scanner', () => {
  const h = pageHooks(true, true); h.tick(300);
  // Nexo's real listener consumes this event because its media element is playing.
  h.visibility('hidden'); h.timeupdate(); assert.equal(h.active(), 0);
  const scans = h.scans(); h.tick(1000); assert.equal(h.scans(), scans);
  h.visibility('visible'); h.timeupdate(); h.tick(300);
  assert.equal(h.active(), 1); assert.ok(h.scans() > scans);
});
