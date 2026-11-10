// Bloqueo local de PhantomShield 8.5: motor cosmético, listas, fondo y YouTube.
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
  return { network: context.PhantomNetworkEngineInstance, cosmetic: context.PhantomCosmeticEngineInstance,
    lists: context.PhantomFilterListManager, context };
}

test('cosmetic engine separates site rules, generic lookups and always-on selectors', () => {
  const { cosmetic } = engines();
  assert.equal(cosmetic.addLine('news.example.com,blog.example.org##.promo-box'), true);
  assert.equal(cosmetic.addLine('##.ad-leaderboard'), true);
  assert.equal(cosmetic.addLine('###sponsor-top'), true);
  assert.equal(cosmetic.addLine('##a[href^="https://ads.example.net/"]'), true);
  const site = cosmetic.forHost('www.news.example.com');
  assert.deepEqual(Array.from(site.selectors), ['.promo-box', 'a[href^="https://ads.example.net/"]']);
  assert.equal(site.generic, true);
  assert.deepEqual(Array.from(cosmetic.forHost('other.example.net').selectors), ['a[href^="https://ads.example.net/"]']);
  // Las genéricas simples solo aparecen si la clase o el id existen en la página.
  assert.deepEqual(Array.from(cosmetic.lookup('other.example.net', ['content', 'ad-leaderboard'], ['main', 'sponsor-top'])),
    ['.ad-leaderboard', '#sponsor-top']);
  assert.deepEqual(Array.from(cosmetic.lookup('other.example.net', ['content'], ['main'])), []);
});

test('cosmetic exceptions and hide exceptions are honoured', () => {
  const { cosmetic } = engines();
  cosmetic.addLine('##.ad-box');
  cosmetic.addLine('shop.example.com##.promo');
  cosmetic.addLine('shop.example.com#@#.promo');
  cosmetic.addLine('docs.example.org#@#.ad-box');
  assert.deepEqual(Array.from(cosmetic.forHost('shop.example.com').selectors), []);
  assert.deepEqual(Array.from(cosmetic.lookup('docs.example.org', ['ad-box'], [])), []);
  assert.deepEqual(Array.from(cosmetic.lookup('news.example.org', ['ad-box'], [])), ['.ad-box']);
  assert.equal(cosmetic.addHideException('@@||fragile.example^$generichide'), true);
  assert.equal(cosmetic.forHost('www.fragile.example').generic, false);
  assert.deepEqual(Array.from(cosmetic.lookup('www.fragile.example', ['ad-box'], [])), []);
  assert.equal(cosmetic.addHideException('@@||bank.example^$elemhide,document'), true);
  cosmetic.addLine('bank.example##.banner');
  assert.deepEqual(Array.from(cosmetic.forHost('bank.example').selectors), []);
  // Con domain= la excepción no vale para todos los sitios.
  assert.equal(cosmetic.addHideException('@@||cdn.example^$generichide,domain=a.example'), false);
});

test('procedural filters, scriptlets and style injections are never turned into CSS', () => {
  const { cosmetic } = engines();
  for (const line of ['example.com##+js(set, a, b)', 'example.com##.x:has-text(Ad)', 'example.com##.x:style(color: red)',
    'example.com##^script:has-text(x)', 'example.com#?#.x:-abp-has(.y)', 'example.com#$#abort-on-property-read x',
    'example.com##.x { display: block }', 'example.*##.x', '~example.com##.x']) {
    assert.equal(cosmetic.addLine(line), false, line);
  }
  assert.equal(cosmetic.count(), 0);
  assert.equal(cosmetic.addLine('example.com##div:has(> .sponsored-label)'), true);
});

test('bundled cosmetic rules cover YouTube on mobile and desktop', () => {
  const { cosmetic, lists } = engines();
  return lists.reloadFromStorage().then(() => {
    const mobile = Array.from(cosmetic.forHost('m.youtube.com').selectors);
    for (const selector of ['ad-slot-renderer', 'ytm-promoted-sparkles-web-renderer', 'ytd-ad-slot-renderer', '#player-ads']) {
      assert.ok(mobile.includes(selector), selector);
    }
    assert.ok(!Array.from(cosmetic.forHost('example.org').selectors).includes('ad-slot-renderer'));
    assert.deepEqual(Array.from(cosmetic.lookup('example.org', ['adsbygoogle'], [])), ['.adsbygoogle']);
  });
});

test('downloaded lists feed both engines and plain domains need no rule objects', async () => {
  const text = ['[Adblock Plus 2.0]', '! comment', '||ads.tracker-one.example^', '||cdn.example.com/banners/$image,third-party',
    '@@||cdn.example.com/banners/logo.png', '##.sidebar-ad', 'news.example.org##.native-ad', '@@||fragile.example^$generichide',
    '||redirected.example^$redirect=noopjs', 'example.com##+js(nowoif)', '||uses-from.example^$script,from=site.example'].join('\n');
  const { network, cosmetic, lists } = engines({ phantom_list_easylist: text });
  await lists.reloadFromStorage();
  assert.equal(network.shouldBlock('https://x.ads.tracker-one.example/p.gif', 'image', 'https://site.example/', false), true);
  assert.equal(network.plainDomains.has('ads.tracker-one.example'), true);
  assert.equal(network.ruleKeys, null, 'dedupe index is released after loading');
  assert.equal(network.shouldBlock('https://cdn.example.com/banners/top.png', 'image', 'https://site.example/', false), true);
  assert.equal(network.shouldBlock('https://cdn.example.com/banners/logo.png', 'image', 'https://site.example/', false), false);
  assert.equal(network.shouldBlock('https://redirected.example/x.js', 'script', 'https://site.example/', false), false);
  assert.equal(network.shouldBlock('https://uses-from.example/a.js', 'script', 'https://site.example/', false), true);
  assert.equal(network.shouldBlock('https://uses-from.example/a.js', 'script', 'https://other.example/', false), false);
  assert.deepEqual(Array.from(cosmetic.lookup('site.example', ['sidebar-ad'], [])), ['.sidebar-ad']);
  assert.ok(Array.from(cosmetic.forHost('news.example.org').selectors).includes('.native-ad'));
  assert.equal(cosmetic.forHost('fragile.example').generic, false);
  assert.ok(lists.counts.network > 300 && lists.counts.cosmetic > 100);
});

test('pattern rules compile their regular expression only when first evaluated', () => {
  const { network } = engines();
  const rule = network._createRuleObject('||cdn.example.com/adserver/*/banner$image');
  assert.equal(rule.regex, null);
  network.addRule(rule);
  assert.equal(network.shouldBlock('https://cdn.example.com/adserver/9/banner', 'image', 'https://site.example/', false), true);
  assert.equal(Object.prototype.toString.call(rule.regex), '[object RegExp]');
  assert.equal(network._createRuleObject('||bad host/path'), null);
});

test('bundled domain lists are valid hostnames and avoid breakage-prone services', () => {
  const { context } = engines();
  const all = [...context.PHANTOM_AD_DOMAINS, ...context.PHANTOM_TRACKER_DOMAINS, ...context.PHANTOM_POPUP_DOMAINS];
  assert.ok(context.PHANTOM_AD_DOMAINS.length >= 200 && context.PHANTOM_TRACKER_DOMAINS.length >= 70);
  for (const domain of all) assert.match(domain, /^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])\.[a-z0-9-]+$/, domain);
  for (const risky of ['facebook.net', 'connect.facebook.net', 'sentry-cdn.com', 'googletagmanager.com', 'google.com', 'youtube.com', 'gstatic.com', 'googlevideo.com']) {
    assert.equal(all.includes(risky), false, risky);
  }
});

// ---------------------------------------------------------------
// Fondo
// ---------------------------------------------------------------

async function background(options = {}) {
  const event = () => ({ listeners: [], addListener(fn) { this.listeners.push(fn); } });
  const changed = event(), beforeRequest = event(), message = event(), installed = event();
  const stored = Object.assign({}, options.stored), inserted = [], fetched = [];
  const browser = {
    storage: { onChanged: changed, local: {
      async get(defaults) {
        if (typeof defaults === 'string') return { [defaults]: stored[defaults] };
        return { ...defaults, ...Object.fromEntries(Object.keys(defaults).filter(key => key in stored).map(key => [key, stored[key]])) };
      },
      async set(values) {
        const delta = Object.fromEntries(Object.entries(values).map(([key, value]) => [key, { newValue: value, oldValue: stored[key] }]));
        Object.assign(stored, values);
        changed.listeners.forEach(fn => fn(delta, 'local'));
      }
    } },
    runtime: { id: 'phantom', getURL: file => 'moz-extension://phantom/' + file, onMessage: message, onInstalled: installed,
      openOptionsPage: async () => { throw new Error('options must not open on update'); } },
    webRequest: { onBeforeRequest: beforeRequest },
    tabs: { onRemoved: event(), onUpdated: event() },
    alarms: { create() {}, onAlarm: event() },
    browserAction: { setBadgeText: async () => {} }
  };
  if (options.insertCSS) browser.tabs.insertCSS = async (tabId, details) => { inserted.push({ tabId, details }); };
  const context = vm.createContext({ browser, URL, Map, Set, TextDecoder, AbortController, console: { info() {}, warn() {}, log() {} },
    Response, fetch: async url => { fetched.push(url); if (options.fetch) return options.fetch(url); throw new Error('offline'); },
    setTimeout: (fn, ms) => { const id = setTimeout(fn, Math.min(ms, 5)); id.unref(); return id; }, clearTimeout });
  context.window = context;
  for (const file of manifest.background.scripts) vm.runInContext(read(file), context, { filename: file });
  await new Promise(resolve => setTimeout(resolve, 30));
  const tab = { id: 7, url: 'https://news.example.org/story', incognito: false };
  return { stored, inserted, fetched, installed, context,
    request: override => beforeRequest.listeners[0]({ url: 'https://doubleclick.net/ad.js', documentUrl: 'https://news.example.org/story',
      type: 'script', tabId: 7, incognito: false, ...override }),
    message: (value, sender = { id: 'phantom', tab, frameId: 0, url: tab.url }) => message.listeners[0](value, sender) };
}

test('pages the user opens are not cancelled; only pop-up networks are', async () => {
  const h = await background();
  assert.deepEqual({ ...h.request({ type: 'main_frame', url: 'https://www.googleadservices.com/pagead/aclk?sa=L' }) }, {});
  assert.deepEqual({ ...h.request({ type: 'main_frame', url: 'https://www.taboola.com/' }) }, {});
  assert.equal(h.request({ type: 'main_frame', url: 'https://popads.net/landing' }).cancel, true);
  assert.equal(h.request({ type: 'script' }).cancel, true);
});

test('YouTube ad endpoints are blocked while playback requests continue', async () => {
  const h = await background();
  const yt = { documentUrl: 'https://m.youtube.com/watch?v=abc', type: 'xmlhttprequest' };
  for (const url of ['https://m.youtube.com/pagead/adview?ai=1', 'https://www.youtube.com/api/stats/ads?ver=2',
    'https://www.youtube.com/get_midroll_info?ei=1', 'https://www.youtube.com/youtubei/v1/player/ad_break?key=1',
    'https://www.youtube.com/ptracking?html5=1']) {
    assert.equal(h.request({ ...yt, url }).cancel, true, url);
  }
  for (const url of ['https://www.youtube.com/youtubei/v1/player?prettyPrint=false', 'https://m.youtube.com/youtubei/v1/next',
    'https://rr2---sn-abc.googlevideo.com/videoplayback?expire=1', 'https://i.ytimg.com/vi/abc/hq720.jpg']) {
    assert.deepEqual({ ...h.request({ ...yt, url, type: url.includes('ytimg') ? 'image' : 'xmlhttprequest' }) }, {}, url);
  }
});

test('cosmetic rules are injected as user CSS when the browser supports it', async () => {
  const h = await background({ insertCSS: true });
  const first = await h.message({ type: 'GET_COSMETIC' });
  assert.equal(first.enabled, true); assert.equal(first.injected, true); assert.equal(first.selectors.length, 0);
  assert.equal(h.inserted[0].tabId, 7); assert.equal(h.inserted[0].details.cssOrigin, 'user');
  assert.match(h.inserted[0].details.code, /data-phantomshield-hidden/);
  assert.match(h.inserted[0].details.code, /display:none!important/);
  const lookup = await h.message({ type: 'COSMETIC_LOOKUP', classes: ['adsbygoogle', 'story-body'], ids: [] });
  assert.equal(lookup.injected, true);
  assert.match(h.inserted[1].details.code, /^\.adsbygoogle\{/);
});

test('without insertCSS the selectors are returned for the in-page fallback; disabled sites get nothing', async () => {
  const h = await background({ stored: { disabled_domains: ['paused.example'] } });
  const reply = await h.message({ type: 'GET_COSMETIC' });
  assert.equal(reply.injected, false);
  assert.ok(reply.selectors.includes('[data-phantomshield-hidden="true"]'));
  assert.ok(reply.selectors.includes('ins.adsbygoogle'));
  const paused = { id: 'phantom', frameId: 0, url: 'https://paused.example/', tab: { id: 9, url: 'https://paused.example/' } };
  assert.equal((await h.message({ type: 'GET_COSMETIC' }, paused)).enabled, false);
  assert.equal((await h.message({ type: 'COSMETIC_LOOKUP', classes: ['adsbygoogle'], ids: [] }, paused)).selectors.length, 0);
});

test('updating the extension keeps the AI configuration and never opens options', async () => {
  const aiConfig = { enabled: true, consent: true, provider: 'claude', endpoint: 'https://api.anthropic.com/v1', model: 'claude-opus-5-5',
    apiKey: 'k', scope: 'public', detail: 'rich' };
  const h = await background({ stored: { aiConfig } });
  h.installed.listeners[0]({ reason: 'update' });
  await new Promise(resolve => setTimeout(resolve, 60));
  assert.equal(h.stored.aiConfig.enabled, true); assert.equal(h.stored.aiConfig.consent, true);
  assert.equal(h.stored.aiConfig.detail, 'rich');
  assert.ok(h.fetched.length > 0, 'lists are refreshed after an update');
});

test('list downloads fall back to mirrors and optional lists do not block completion', async () => {
  const body = '! list\n' + '||mirror-only.example^\n'.repeat(20);
  const h = await background({ fetch: async url => {
    if (url.startsWith('https://easylist.to/') || url.includes('adblockplus.org')) throw new Error('unreachable');
    return new Response(body);
  } });
  const results = await h.message({ type: 'FORCE_UPDATE' });
  assert.ok(Array.isArray(results));
  assert.equal(results.find(x => x.id === 'easylist').ok, true, 'mirror used');
  const spanish = results.find(x => x.id === 'easylist-es');
  assert.equal(spanish.ok, false); assert.equal(spanish.optional, true);
  assert.ok(h.stored.phantom_last_update > 0);
  assert.ok(h.fetched.includes('https://raw.githubusercontent.com/uBlockOrigin/uAssetsCDN/main/thirdparties/easylist.txt'));
  assert.equal(h.request({ url: 'https://mirror-only.example/x.js' }).cancel, true);
});

test('an HTML error page is not stored as a filter list', async () => {
  const h = await background({ fetch: async () => new Response('<!doctype html><html><body>' + 'x'.repeat(400) + '</body></html>') });
  const results = await h.message({ type: 'FORCE_UPDATE' });
  assert.ok(results.every(result => !result.ok));
  assert.equal(h.stored.phantom_list_easylist, undefined);
});

// ---------------------------------------------------------------
// YouTube (page-hooks.js, mundo MAIN)
// ---------------------------------------------------------------

function youtubePage(enabled = true, host = 'm.youtube.com') {
  const attributes = { 'data-phantomshield-adblock': String(enabled) };
  const timers = [];
  const player = { classList: { values: new Set(), contains(name) { return this.values.has(name); } }, querySelector: () => video };
  const video = { muted: false, playbackRate: 1, duration: 30, currentTime: 2, paused: false, play: () => Promise.resolve() };
  const skip = { clicks: 0, click() { this.clicks++; } };
  let skipVisible = false;
  const document = new EventTarget();
  Object.assign(document, { readyState: 'complete', visibilityState: 'visible',
    documentElement: { getAttribute: name => attributes[name] ?? null },
    getElementById: id => id === 'movie_player' ? player : null,
    querySelector: selector => selector.includes('skip') ? (skipVisible ? skip : null) : null });
  const window = new EventTarget();
  window.open = () => null;
  class FakeResponse { constructor(data) { this.data = data; } json() { return Promise.resolve(JSON.parse(JSON.stringify(this.data))); } }
  class FakeXHR {}
  Object.defineProperty(FakeXHR.prototype, 'response', { configurable: true, get() { return this._response; } });
  window.Response = FakeResponse;
  const context = vm.createContext({ window, document, location: { hostname: host, href: 'https://' + host + '/watch?v=x' },
    navigator: { userActivation: { isActive: false } }, URL, Element: class {},
    // Copia propia: page-hooks reemplaza JSON.parse y no debe tocar el JSON del test.
    JSON: { parse: JSON.parse, stringify: JSON.stringify }, Reflect, Proxy, Object, Number, Promise,
    Response: FakeResponse, XMLHttpRequest: FakeXHR,
    setTimeout: (fn, ms) => { timers.push(fn); return timers.length; }, clearTimeout() {},
    MutationObserver: class { observe() {} disconnect() {} } });
  // En la página, las propiedades de window son variables globales.
  context.window = new Proxy(window, {
    get: (target, key) => key in target ? Reflect.get(target, key) : context[key],
    set: (target, key, value) => { target[key] = value; return true; },
    defineProperty: (target, key, descriptor) => Reflect.defineProperty(target, key, descriptor)
  });
  vm.runInContext(read('content/page-hooks.js'), context);
  return { context, window, video, player, skip, FakeResponse, FakeXHR,
    showSkip: () => { skipVisible = true; },
    run: () => { while (timers.length) timers.shift()(); },
    mutate: () => document.dispatchEvent(new Event('timeupdate')),
    toggle: value => { attributes['data-phantomshield-adblock'] = String(value); document.dispatchEvent(new Event('phantomshield:settings')); } };
}

const playerData = () => ({ videoDetails: { videoId: 'abc', title: 'Video' }, streamingData: { formats: [1] },
  adPlacements: [{ adPlacementRenderer: {} }], playerAds: [{}], adSlots: [{}], adBreakHeartbeatParams: 'Q0FB' });

test('YouTube player responses lose their ads through JSON.parse while content stays intact', () => {
  const h = youtubePage();
  const parsed = vm.runInContext('JSON.parse(' + JSON.stringify(JSON.stringify(playerData())) + ')', h.context);
  for (const key of ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams']) assert.equal(key in parsed, false, key);
  assert.equal(parsed.videoDetails.videoId, 'abc'); assert.equal(parsed.streamingData.formats.length, 1);
  const nested = vm.runInContext('JSON.parse(' + JSON.stringify(JSON.stringify({ playerResponse: playerData(), response: { contents: {} } })) + ')', h.context);
  assert.equal('adPlacements' in nested.playerResponse, false); assert.ok(nested.response.contents);
  const batch = vm.runInContext('JSON.parse(' + JSON.stringify(JSON.stringify([{ playerResponse: playerData() }, { response: {} }])) + ')', h.context);
  assert.equal('playerAds' in batch[0].playerResponse, false);
  // Los valores que no son objetos y los JSON ajenos siguen igual.
  assert.equal(vm.runInContext('JSON.parse("42")', h.context), 42);
  assert.deepEqual({ ...vm.runInContext('JSON.parse(\'{"a":[1,2]}\')', h.context) }, { a: [1, 2] });
  assert.match(vm.runInContext('Function.prototype.toString.call(JSON.parse)', h.context), /native code/);
});

test('embedded ytInitialPlayerResponse, fetch json and XHR json are pruned too', async () => {
  const h = youtubePage();
  h.window.ytInitialPlayerResponse = playerData();
  assert.equal('adPlacements' in h.window.ytInitialPlayerResponse, false);
  assert.equal(h.window.ytInitialPlayerResponse.videoDetails.title, 'Video');
  const json = await new h.FakeResponse({ playerResponse: playerData() }).json();
  assert.equal('adSlots' in json.playerResponse, false);
  const xhr = new h.FakeXHR(); xhr.responseType = 'json'; xhr._response = playerData();
  assert.equal('playerAds' in xhr.response, false);
  const text = new h.FakeXHR(); text.responseType = ''; text._response = 'plain';
  assert.equal(text.response, 'plain');
});

test('Shorts ad entries are removed from the watch sequence', () => {
  const h = youtubePage();
  const entries = [{ command: { reelWatchEndpoint: { videoId: 'a' } } },
    { command: { reelWatchEndpoint: { videoId: 'ad', adClientParams: { isAd: true } } } },
    { command: { reelWatchEndpoint: { videoId: 'b' } } }];
  const parsed = vm.runInContext('JSON.parse(' + JSON.stringify(JSON.stringify({ entries })) + ')', h.context);
  assert.deepEqual(parsed.entries.map(entry => entry.command.reelWatchEndpoint.videoId), ['a', 'b']);
});

test('pausing protection on the site stops pruning; other sites are never touched', () => {
  const paused = youtubePage(false);
  const parsed = vm.runInContext('JSON.parse(' + JSON.stringify(JSON.stringify(playerData())) + ')', paused.context);
  assert.equal(parsed.adPlacements.length, 1);
  paused.toggle(true);
  assert.equal('adPlacements' in vm.runInContext('JSON.parse(' + JSON.stringify(JSON.stringify(playerData())) + ')', paused.context), false);
  const other = youtubePage(true, 'news.example.org');
  assert.equal(vm.runInContext('JSON.parse', other.context), JSON.parse, 'JSON.parse is not wrapped outside YouTube');
  assert.equal(vm.runInContext('JSON.parse(' + JSON.stringify(JSON.stringify(playerData())) + ')', other.context).adPlacements.length, 1);
});

test('an ad that still starts is muted, skipped to its end and playback is restored afterwards', () => {
  const h = youtubePage();
  h.player.classList.values.add('ad-showing');
  h.mutate(); h.run();
  assert.equal(h.video.muted, true);
  assert.ok(h.video.currentTime > 29, 'jumped to the end of the ad');
  h.showSkip(); h.video.currentTime = 1; h.mutate(); h.run();
  assert.ok(h.skip.clicks >= 1);
  h.player.classList.values.delete('ad-showing'); h.video.playbackRate = 16;
  h.mutate(); h.run();
  assert.equal(h.video.muted, false); assert.equal(h.video.playbackRate, 1);
});

test('a page that reports the injected CSS is not applied gets the selectors inline', async () => {
  const h = await background({ insertCSS: true });
  const reply = await h.message({ type: 'GET_COSMETIC', inline: true });
  assert.equal(reply.injected, false); assert.ok(reply.selectors.includes('ins.adsbygoogle'));
  assert.equal(h.inserted.length, 0);
  const lookup = await h.message({ type: 'COSMETIC_LOOKUP', classes: ['adsbygoogle'], ids: [], inline: true });
  assert.deepEqual(Array.from(lookup.selectors), ['.adsbygoogle']);
});
