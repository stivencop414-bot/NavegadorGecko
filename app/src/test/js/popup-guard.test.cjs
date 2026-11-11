// Bloqueo de ventanas emergentes: reglas $popup del motor y guarda de window.open.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assets = path.join(__dirname, '../../main/assets/extensions/phantomshield');
const read = file => fs.readFileSync(path.join(assets, file), 'utf8');

test('$popup rules only apply to new windows, never to page resources', () => {
  const context = vm.createContext({ console: { info() {}, warn() {}, log() {} }, URL, Map, Set });
  vm.runInContext(read('background/network-engine.js'), context);
  const engine = context.PhantomNetworkEngineInstance;
  engine.addRule(engine._createRuleObject('||popunder.example^$popup'));
  const page = 'https://news.example.org/';
  assert.equal(engine.shouldBlock('https://cdn.popunder.example/lib.js', 'script', page, false), false);
  assert.equal(engine.shouldBlock('https://popunder.example/go?id=1', 'popup', page, false), true);
});

globalThis.CustomEvent = globalThis.CustomEvent || class CustomEvent extends Event {};
function popupPage({ enabled = true, popups = true } = {}) {
  const opened = [];
  const attributes = { 'data-phantomshield-adblock': String(enabled), 'data-phantomshield-popups': String(popups) };
  class Element {
    constructor(tag, attrs = {}, text = '', rect = { width: 120, height: 40 }) {
      this.tagName = tag; this.attrs = attrs; this.textContent = text; this.rect = rect; this.className = attrs.class || ''; this.id = attrs.id || '';
    }
    getAttribute(name) { return this.attrs[name] ?? null; }
    getBoundingClientRect() { return this.rect; }
    querySelector() { return null; }
    closest(selector) {
      const interactive = /a\[href\]|button/.test(selector);
      if (selector === 'a[href]') return this.tagName === 'A' ? this : null;
      return interactive && ['A', 'BUTTON'].includes(this.tagName) ? this : null;
    }
    get href() { return new URL(this.attrs.href, 'https://news.example.org/').href; }
  }
  const document = new EventTarget();
  Object.assign(document, { readyState: 'complete', visibilityState: 'visible',
    documentElement: { getAttribute: name => attributes[name] ?? null }, querySelector: () => null, getElementById: () => null });
  const window = new EventTarget();
  window.open = url => { opened.push(url); return {}; };
  const context = vm.createContext({ window, document, Element, URL, Event, EventTarget, CustomEvent,
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
  return { opened, tap, Element, open: url => context.window.open(url) };
}

test('a tap anywhere that opens another site is blocked as a popunder', () => {
  const h = popupPage();
  h.tap(new h.Element('DIV', {}, 'Texto del artículo'));
  assert.equal(h.open('https://ads.other-network.example/landing'), null);
  assert.deepEqual(h.opened, []);
});

test('share, login and same-site windows still open', () => {
  const h = popupPage();
  h.tap(new h.Element('BUTTON', { 'aria-label': 'Compartir' }, 'Compartir'));
  h.open('https://share.partner.example/intent'); 
  h.tap(new h.Element('DIV'));
  h.open('https://news.example.org/gallery');
  h.open('https://accounts.google.com/o/oauth2/auth');
  assert.deepEqual(h.opened, ['https://share.partner.example/intent', 'https://news.example.org/gallery', 'https://accounts.google.com/o/oauth2/auth']);
});

test('a link that points to the opened site is allowed; turning the option off allows everything', () => {
  const h = popupPage();
  h.tap(new h.Element('A', { href: 'https://shop.example.com/item' }, 'Ver tienda'));
  h.open('https://shop.example.com/item');
  assert.equal(h.opened.length, 1);
  const off = popupPage({ popups: false });
  off.tap(new off.Element('DIV'));
  off.open('https://ads.other-network.example/landing');
  assert.equal(off.opened.length, 1);
});
