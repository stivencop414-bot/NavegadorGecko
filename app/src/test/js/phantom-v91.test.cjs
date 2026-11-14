// PhantomShield 9.1: $badfilter/$important, dominios aprendidos con la IA y ventanas emergentes.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assets = path.join(__dirname, '../../main/assets/extensions/phantomshield');
const manifest = JSON.parse(fs.readFileSync(path.join(assets, 'manifest.json')));
const read = file => fs.readFileSync(path.join(assets, file), 'utf8');

function engines(store = {}) {
  const context = vm.createContext({ console: { info() {}, warn() {}, log() {} }, URL, Map, Set, setTimeout, clearTimeout,
    browser: { storage: { local: { get: async key => ({ [key]: store[key] }) } } } });
  context.window = context;
  for (const file of ['data/domains-adservers.js', 'data/domains-trackers.js', 'data/domains-popups.js', 'data/cosmetic-rules.js',
    'background/network-engine.js', 'background/cosmetic-engine.js', 'background/filter-lists.js']) {
    vm.runInContext(read(file), context, { filename: file });
  }
  return { network: context.PhantomNetworkEngineInstance, lists: context.PhantomFilterListManager };
}

// ---------------------------------------------------------------
// Reglas
// ---------------------------------------------------------------

test('$badfilter disables the exact rule in any list order, keeping the others', async () => {
  const h = engines({
    phantom_list_easylist: '||cdn.widget.example^$third-party\n/sponsor-banner.\n||ads.real.example^',
    phantom_list_ubo_filters: undefined,
    'phantom_list_ubo-filters': '||cdn.widget.example^$badfilter,third-party\n/sponsor-banner.$badfilter'
  });
  await h.lists.reloadFromStorage();
  const page = 'https://news.example.org/';
  assert.equal(h.network.shouldBlock('https://cdn.widget.example/app.js', 'script', page, false), false);
  assert.equal(h.network.shouldBlock('https://news.example.org/img/sponsor-banner.png', 'image', page, false), false);
  assert.equal(h.network.shouldBlock('https://ads.real.example/x.js', 'script', page, false), true);
});

test('$important blocks even when an exception would allow the request', () => {
  const { network } = engines();
  network.addRule(network._createRuleObject('@@||tracker.example^'));
  network.addRule(network._createRuleObject('||tracker.example^$important,third-party'));
  network.addRule(network._createRuleObject('@@||cdn.example^'));
  network.addRule(network._createRuleObject('||cdn.example^'));
  const page = 'https://news.example.org/';
  assert.equal(network.shouldBlock('https://tracker.example/p.gif', 'image', page, false), true);
  assert.equal(network.shouldBlock('https://tracker.example/p.gif', 'image', 'https://tracker.example/', false), false);
  assert.equal(network.shouldBlock('https://cdn.example/lib.js', 'script', page, false), false);
});

// ---------------------------------------------------------------
// Dominios aprendidos
// ---------------------------------------------------------------

function domainsHarness(stored = {}) {
  const store = structuredClone(stored);
  const browser = { storage: { local: {
    get: async defaults => ({ ...defaults, ...structuredClone(store) }),
    set: async values => { Object.assign(store, structuredClone(values)); }
  } } };
  const context = vm.createContext({ browser, URL, Map, Set, Math, Date, Uint32Array, setTimeout, clearTimeout,
    crypto: require('node:crypto').webcrypto });
  vm.runInContext(read('background/ai-domains.js'), context);
  return { store, domains: context.PhantomAdDomains };
}

test('only ad infrastructure is learned: frames and click links of third parties, never safe or own-site hosts', () => {
  const { domains } = domainsHarness();
  const known = host => host.endsWith('doubleclick.net');
  const found = domains.evidence('news.example.org', 'https://serve.adnet-x.com/frame?z=1', [
    'https://shop-advertiser.com/product/42',            // tienda anunciada: no se aprende
    'https://trk.clicks-y.net/click?cid=9&url=https%3A',  // enlace de clic: sí
    'https://cdn.news.example.org/out/x',                 // propio sitio: no
    'https://www.google.com/aclk?sa=l'                    // servicio común: no
  ], known);
  assert.deepEqual([...found].sort(), ['adnet-x.com', 'clicks-y.net']);
  assert.deepEqual([...domains.evidence('news.example.org', 'https://googleads.g.doubleclick.net/pagead/ads', [], known)], []);
  // Alojamiento compartido: se aprende el host, no todo cloudfront.net.
  assert.deepEqual([...domains.evidence('news.example.org', 'https://d123.cloudfront.net/ad.html', [], known)], ['d123.cloudfront.net']);
});

test('a domain is blocked after ads on two sites (or four on one), survives restart and can be allowed', async () => {
  const first = domainsHarness();
  assert.deepEqual([...await first.domains.learn('news.example.org', ['adnet-x.com'])], []);
  assert.equal(first.domains.isBlocked('serve.adnet-x.com'), false);
  assert.deepEqual([...await first.domains.learn('blog.other.example', ['adnet-x.com'])], ['adnet-x.com']);
  assert.equal(first.domains.isBlocked('serve.adnet-x.com'), true);
  for (let n = 0; n < 3; n++) await first.domains.learn('news.example.org', ['solo-site.net']);
  assert.equal(first.domains.isBlocked('solo-site.net'), false);
  await first.domains.learn('news.example.org', ['solo-site.net']);
  assert.equal(first.domains.isBlocked('a.solo-site.net'), true);
  await first.domains.flush();
  // Nunca se guardan nombres de los sitios visitados.
  assert.doesNotMatch(JSON.stringify(first.store.aiAdDomains), /news\.example|other\.example/);

  const second = domainsHarness(first.store);
  await second.domains.ready;
  assert.equal(second.domains.isBlocked('serve.adnet-x.com'), true);
  await second.domains.allow('adnet-x.com', true);
  assert.equal(second.domains.isBlocked('serve.adnet-x.com'), false);
  assert.deepEqual([...await second.domains.learn('news.example.org', ['adnet-x.com'])], []);
  const list = await second.domains.list();
  assert.deepEqual([...list.allowed], ['adnet-x.com']);
  assert.equal(list.domains.some(entry => entry.domain === 'adnet-x.com'), false);
});

async function background(stored = {}) {
  const event = () => ({ listeners: [], addListener(fn) { this.listeners.push(fn); } });
  const changed = event(), beforeRequest = event(), message = event(), installed = event();
  const store = Object.assign({}, stored);
  const browser = {
    storage: { onChanged: changed, local: {
      async get(defaults) {
        if (typeof defaults === 'string') return { [defaults]: store[defaults] };
        return { ...defaults, ...Object.fromEntries(Object.keys(defaults).filter(key => key in store).map(key => [key, structuredClone(store[key])])) };
      },
      async set(values) {
        const delta = Object.fromEntries(Object.entries(values).map(([key, value]) => [key, { newValue: value }]));
        Object.assign(store, structuredClone(values));
        changed.listeners.forEach(fn => fn(delta, 'local'));
      }
    } },
    runtime: { id: 'phantom', getURL: file => 'moz-extension://phantom/' + file, onMessage: message, onInstalled: installed,
      sendMessage: async () => ({}) },
    webRequest: { onBeforeRequest: beforeRequest },
    tabs: { onRemoved: event(), onUpdated: event() },
    alarms: { create() {}, onAlarm: event() },
    browserAction: { setBadgeText: async () => {} }
  };
  const fetchModel = async () => new Response(JSON.stringify({ usage: { prompt_tokens: 10, completion_tokens: 5 },
    choices: [{ message: { content: JSON.stringify({ decisions: [{ id: 'c-1', isAd: true, confidence: 0.99 }] }) } }] }));
  const context = vm.createContext({ browser, URL, Map, Set, TextDecoder, TextEncoder, AbortController, Response, Uint8Array, Uint32Array,
    crypto: require('node:crypto').webcrypto, console: { info() {}, warn() {}, log() {} },
    fetch: async url => (String(url).includes('api.example') ? fetchModel() : Promise.reject(new Error('offline'))),
    setTimeout: (fn, ms) => { const id = setTimeout(fn, Math.min(ms, 5)); id.unref(); return id; }, clearTimeout });
  context.window = context;
  for (const file of manifest.background.scripts) vm.runInContext(read(file), context, { filename: file });
  await new Promise(resolve => setTimeout(resolve, 30));
  return {
    store, context,
    request: details => beforeRequest.listeners[0]({ tabId: 7, incognito: false, ...details }),
    message: (value, sender) => message.listeners[0](value, sender)
  };
}

test('ads the AI hides teach their network, which is then blocked as a third party everywhere', async () => {
  const h = await background({ aiConfig: { enabled: true, consent: true, scope: 'public', provider: 'custom',
    endpoint: 'https://api.example/v1', model: 'test' } });
  const candidate = { id: 'c-1', tag: 'DIV', signals: ['ad-class', 'ad-size'], width: 300, height: 250,
    localUrls: ['https://shop-advertiser.com/p', 'https://serve.adnet-x.com/frame?z=1'], localFrame: 'https://serve.adnet-x.com/frame?z=1' };
  for (const [tabId, url] of [[7, 'https://news.example.org/a'], [8, 'https://blog.other-site.net/b']]) {
    h.request({ type: 'main_frame', url, tabId });
    const tab = { id: tabId, url, incognito: false };
    const result = await h.message({ type: 'AI_CLASSIFY', candidates: [candidate] }, { id: 'phantom', tab, frameId: 0, url });
    assert.equal(result.ok, true, JSON.stringify(result));
    assert.equal(result.decisions[0].hide, true);
  }
  const ad = h.request({ type: 'script', url: 'https://cdn.adnet-x.com/tag.js', documentUrl: 'https://third-site.net/', tabId: 9 });
  assert.equal(ad.cancel, true);
  // La tienda anunciada no se aprendió, y quien abre adnet-x.com a propósito llega.
  assert.deepEqual({ ...h.request({ type: 'image', url: 'https://shop-advertiser.com/i.png', documentUrl: 'https://third-site.net/', tabId: 9 }) }, {});
  assert.deepEqual({ ...h.request({ type: 'main_frame', url: 'https://adnet-x.com/', tabId: 9 }) }, {});
  const own = 'moz-extension://phantom/options/options.html';
  const listed = await h.message({ type: 'AI_DOMAINS_LIST' }, { id: 'phantom', url: own });
  assert.equal(listed.domains[0].domain, 'adnet-x.com');
  assert.equal(listed.domains[0].state, 'blocked');
  // Una página web no puede leer ni cambiar la lista.
  assert.equal((await h.message({ type: 'AI_DOMAINS_LIST' }, { id: 'phantom', url: 'https://evil.example/' })).ok, false);
  await h.message({ type: 'AI_DOMAINS_ALLOW', domain: 'adnet-x.com' }, { id: 'phantom', url: own });
  assert.deepEqual({ ...h.request({ type: 'script', url: 'https://cdn.adnet-x.com/tag.js', documentUrl: 'https://third-site.net/', tabId: 9 }) }, {});
});

test('turning learning off keeps classification working but saves no domains', async () => {
  const h = await background({ aiConfig: { enabled: true, consent: true, scope: 'public', provider: 'custom',
    endpoint: 'https://api.example/v1', model: 'test', learnDomains: false } });
  const candidate = { id: 'c-1', tag: 'IFRAME', signals: ['third-party-frame'], width: 300, height: 250,
    localUrls: ['https://serve.adnet-z.com/f'], localFrame: 'https://serve.adnet-z.com/f' };
  for (const [tabId, url] of [[7, 'https://news.example.org/a'], [8, 'https://blog.other-site.net/b']]) {
    h.request({ type: 'main_frame', url, tabId });
    await h.message({ type: 'AI_CLASSIFY', candidates: [candidate] }, { id: 'phantom', tab: { id: tabId, url, incognito: false }, frameId: 0, url });
  }
  assert.equal(h.context.PhantomAdDomains.isBlocked('serve.adnet-z.com'), false);
  assert.equal((await h.context.PhantomAdDomains.list()).domains.length, 0);
});

// ---------------------------------------------------------------
// Ventanas emergentes
// ---------------------------------------------------------------

globalThis.CustomEvent = globalThis.CustomEvent || class CustomEvent extends Event {};
function popupPage() {
  const opened = [], navigated = [];
  let blocked = 0;
  const attributes = { 'data-phantomshield-adblock': 'true', 'data-phantomshield-popups': 'true' };
  class Element {
    constructor(tag, attrs = {}, text = '') {
      this.tagName = tag; this.attrs = attrs; this.textContent = text; this.className = attrs.class || ''; this.id = attrs.id || '';
    }
    getAttribute(name) { return this.attrs[name] ?? null; }
    getBoundingClientRect() { return { width: 120, height: 40 }; }
    querySelector() { return null; }
    closest(selector) {
      if (selector === 'a[href]') return this.tagName === 'A' && this.attrs.href ? this : null;
      return ['A', 'BUTTON'].includes(this.tagName) ? this : null;
    }
    get href() { return new URL(this.attrs.href, 'https://news.example.org/').href; }
  }
  class HTMLElement extends Element {}
  HTMLElement.prototype.click = function() { navigated.push(this.href); };
  class HTMLFormElement extends Element {
    get action() { return new URL(this.attrs.action, 'https://news.example.org/').href; }
  }
  HTMLFormElement.prototype.submit = function() { navigated.push(this.action); };
  HTMLFormElement.prototype.requestSubmit = function() { navigated.push(this.action); };
  const frameWindow = { open: url => { opened.push('frame:' + url); return {}; } };
  class HTMLIFrameElement extends Element {}
  Object.defineProperty(HTMLIFrameElement.prototype, 'contentWindow', { configurable: true, get() { return frameWindow; } });
  Object.defineProperty(HTMLIFrameElement.prototype, 'contentDocument', { configurable: true, get() { return { defaultView: frameWindow }; } });

  const document = new EventTarget();
  Object.assign(document, { readyState: 'complete', visibilityState: 'visible',
    documentElement: { getAttribute: name => attributes[name] ?? null }, querySelector: () => null, getElementById: () => null });
  document.addEventListener('phantomshield:popup-blocked', () => { blocked++; });
  const window = new EventTarget();
  window.open = url => { opened.push(url); return {}; };
  const context = vm.createContext({ window, document, Element, HTMLElement, HTMLFormElement, HTMLIFrameElement, URL, Event, EventTarget,
    CustomEvent, Proxy, Reflect, WeakSet,
    location: { hostname: 'news.example.org', href: 'https://news.example.org/story' },
    navigator: { userActivation: { isActive: false } }, innerWidth: 400, innerHeight: 800,
    getComputedStyle: () => ({ opacity: '1', visibility: 'visible' }),
    setTimeout: () => 0, clearTimeout() {}, MutationObserver: class { observe() {} disconnect() {} } });
  context.window = new Proxy(window, {
    get: (target, key) => key in target ? Reflect.get(target, key) : context[key],
    set: (target, key, value) => { target[key] = value; return true; }
  });
  vm.runInContext(read('content/page-hooks.js'), context);
  const tap = target => { const event = new Event('click'); Object.defineProperty(event, 'isTrusted', { value: true });
    Object.defineProperty(event, 'target', { value: target }); window.dispatchEvent(event); };
  return { opened, navigated, tap, Element, HTMLElement, HTMLFormElement, HTMLIFrameElement, blocked: () => blocked,
    open: url => context.window.open(url) };
}

test('a link created in memory and clicked by a script cannot open another site', () => {
  const h = popupPage();
  const ad = new h.HTMLElement('A', { href: 'https://ads.network.example/landing', target: '_blank' });
  ad.click();
  assert.deepEqual(h.navigated, []);
  assert.equal(h.blocked(), 1);
  // Mismo sitio, o un botón que el usuario tocó y que lleva a ese destino: se abre.
  new h.HTMLElement('A', { href: '/gallery', target: '_blank' }).click();
  h.tap(new h.Element('BUTTON', {}, 'Abrir en shop.example.com'));
  new h.HTMLElement('A', { href: 'https://shop.example.com/item', target: '_blank' }).click();
  assert.deepEqual(h.navigated, ['https://news.example.org/gallery', 'https://shop.example.com/item']);
});

test('an empty iframe window cannot be used to bypass the guard', () => {
  const h = popupPage();
  h.tap(new h.Element('DIV', {}, 'Texto'));
  const frame = new h.HTMLIFrameElement('IFRAME');
  assert.equal(frame.contentWindow.open('https://ads.network.example/x'), null);
  assert.equal(frame.contentDocument.defaultView.open('https://ads.network.example/y'), null);
  assert.deepEqual(h.opened, []);
  frame.contentWindow.open('https://news.example.org/print');
  assert.deepEqual(h.opened, ['frame:https://news.example.org/print']);
});

test('blank windows only open from a real control; scripted forms to other sites are stopped', () => {
  const h = popupPage();
  h.tap(new h.Element('DIV', {}, 'Texto del artículo'));
  assert.equal(h.open('about:blank'), null);
  h.tap(new h.Element('BUTTON', {}, 'Pagar'));
  assert.notEqual(h.open('about:blank'), null);
  assert.deepEqual(h.opened, ['about:blank']);
  h.tap(new h.Element('DIV', {}, 'Texto'));
  new h.HTMLFormElement('FORM', { action: 'https://ads.network.example/go', target: '_blank' }).submit();
  new h.HTMLFormElement('FORM', { action: '/search', target: '_blank' }).submit();
  new h.HTMLFormElement('FORM', { action: 'https://ads.network.example/go' }).requestSubmit();
  assert.deepEqual(h.navigated, ['https://news.example.org/search', 'https://ads.network.example/go']);
});

test('page and data pop-up network lists stay in sync', () => {
  const data = vm.runInNewContext(read('data/domains-popups.js') + ';window.PHANTOM_POPUP_DOMAINS', { window: {} });
  const page = read('content/page-hooks.js');
  for (const domain of data) assert.match(page, new RegExp("'" + domain.replace(/\./g, '\\.') + "'"), domain);
});
