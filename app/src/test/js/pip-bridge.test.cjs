// Regression tests for the real content script's native-message and media events.
// Run with: node --test app/src/test/js/pip-bridge.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/assets/extensions/nexo_bridge/content_script.js'), 'utf8');

function harness() {
  let now = 100000, nextId = 1, onMessage;
  const timers = new Map(), reports = [], videos = [];
  class Element extends EventTarget {
    constructor(parent = null) {
      super(); this.parentElement = parent; this.isConnected = true;
      const classes = new Set();
      this.classList = { add: x => classes.add(x), remove: x => classes.delete(x), contains: x => classes.has(x) };
      this.styles = {}; this.children = [];
      this.style = { setProperty: (name, value) => { this.styles[name] = value; } };
    }
    getBoundingClientRect() { return { left: 0, top: 48, width: 640, height: 360 }; }
    appendChild(child) { child.parentElement = this; child.isConnected = true; this.children.push(child); return child; }
    remove() { this.isConnected = false; }
    setAttribute() {}
  }
  const html = new Element(), body = new Element(html), wrapper = new Element(body);
  const document = new Element();
  Object.assign(document, {
    documentElement: html, body, visibilityState: 'visible',
    getElementsByTagName: () => videos, querySelectorAll: () => [],
    createElement: () => new Element()
  });
  html.querySelectorAll = () => [];
  const port = { postMessage: value => reports.push(value), onMessage: { addListener: callback => onMessage = callback }, onDisconnect: { addListener() {} } };
  const schedule = (callback, delay, interval = 0) => {
    const id = nextId++; timers.set(id, { callback, at: now + delay, interval }); return id;
  };
  vm.runInNewContext(source, {
    document, window: new Element(), Date: { now: () => now },
    getComputedStyle: () => ({ display: 'block', visibility: 'visible' }),
    MutationObserver: class { observe() {} },
    browser: { runtime: { connectNative: () => port, sendNativeMessage: () => Promise.resolve({}) } },
    setTimeout: (callback, delay) => schedule(callback, delay), clearTimeout: id => timers.delete(id),
    setInterval: (callback, delay) => schedule(callback, delay, delay)
  });
  function tick(ms) {
    const end = now + ms;
    while (true) {
      const entry = [...timers].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!entry) break;
      const [id, timer] = entry; now = timer.at; timers.delete(id);
      if (timer.interval) timers.set(id, { ...timer, at: now + timer.interval });
      timer.callback();
    }
    now = end;
  }
  function video() {
    const v = new Element(wrapper);
    Object.assign(v, { paused: false, ended: false, error: null, currentSrc: 'test.webm', readyState: 4,
      videoWidth: 640, videoHeight: 360, currentTime: 50, plays: 0,
      seekable: { length: 1, start: () => 20, end: () => 80 } });
    v.pause = () => { v.paused = true; v.dispatchEvent(new Event('pause')); };
    v.play = () => { v.plays++; v.paused = false; return Promise.resolve(); };
    videos.push(v); return v;
  }
  return { video, tick, reports, wrapper, body, html, Element,
    enter: resume => onMessage({ type: 'pip_mode', active: true, resume }),
    enterNative: resume => onMessage({ type: 'pip_mode', active: true, resume, isolate: false }),
    leave: () => onMessage({ type: 'pip_mode', active: false }),
    command: command => onMessage({ type: 'pip_command', command }),
    changed: () => document.dispatchEvent(new Event('loadedmetadata')) };
}

test('late site pause is recovered after the former six-second limit', () => {
  const h = harness(), v = h.video(); h.enter(true); h.tick(15000);
  v.pause(); h.tick(200); assert.equal(v.paused, false); assert.equal(v.plays, 1);
});
test('explicit pause cancels pending recovery and remains paused across polling', () => {
  const h = harness(), v = h.video(); h.enter(true); v.pause(); h.command('pause');
  h.tick(15000); assert.equal(v.paused, true); assert.equal(v.plays, 0);
});
test('explicit play enables recovery again', () => {
  const h = harness(), v = h.video(); h.enter(true); h.command('pause'); h.command('play');
  h.tick(15000); v.pause(); h.tick(200); assert.equal(v.plays, 2);
});
test('entering with a paused video does not start it', () => {
  const h = harness(), v = h.video(); v.pause(); h.enter(false); h.tick(15000); assert.equal(v.plays, 0);
});
test('ended or failed video is never restarted by recovery', () => {
  for (const condition of ['ended', 'error']) {
    const h = harness(), v = h.video(); h.enter(true); v[condition] = true; v.pause();
    h.tick(15000); assert.equal(v.plays, 0);
  }
});
test('recovery is bounded to three attempts per ten seconds', () => {
  const h = harness(), v = h.video(); h.enter(true);
  for (let i = 0; i < 5; i++) { v.pause(); h.tick(200); }
  assert.equal(v.plays, 3);
});
test('moving a connected video updates its ancestor isolation chain', () => {
  const h = harness(), v = h.video(); h.enter(true);
  const replacement = new h.Element(h.body); v.parentElement = replacement; h.tick(2100);
  assert.equal(replacement.classList.contains('nexo-pip-ancestor'), true);
  assert.equal(h.wrapper.classList.contains('nexo-pip-ancestor'), false);
});
test('a new playing element replaces the retained paused player', () => {
  const h = harness(), old = h.video(); h.enter(true); old.pause();
  const next = h.video(); h.tick(2100);
  assert.equal(next.classList.contains('nexo-pip-video'), true);
  assert.equal(old.classList.contains('nexo-pip-video'), false);
});
test('seek clamps to the available range including live windows', () => {
  const h = harness(), v = h.video(); h.enter(true);
  h.command('seek_forward'); assert.equal(v.currentTime, 60);
  v.currentTime = 79; h.command('seek_forward'); assert.equal(v.currentTime, 80);
  v.currentTime = 21; h.command('seek_backward'); assert.equal(v.currentTime, 20);
  v.seekable.length = 0; h.command('seek_forward'); assert.equal(v.currentTime, 20);
});
test('leaving PiP restores classes and cancels pending playback', () => {
  const h = harness(), v = h.video(); h.enter(true); v.pause(); h.leave(); h.tick(2100);
  assert.equal(v.plays, 0); assert.equal(v.classList.contains('nexo-pip-video'), false);
  assert.equal(h.wrapper.classList.contains('nexo-pip-ancestor'), false);
});
test('constant media events cannot starve video-state reporting', () => {
  const h = harness(); h.video();
  for (let i = 0; i < 12; i++) { h.changed(); h.tick(50); }
  assert.ok(h.reports.some(x => x.type === 'video_state' && x.playing));
});

test('duplicate PiP mode messages do not undo explicit pause', () => {
  const h = harness(), v = h.video(); h.enter(true); h.command('pause'); h.enter(true);
  h.tick(15000); assert.equal(v.paused, true); assert.equal(v.plays, 0);
});

test('resume intent survives a video arriving after the PiP mode message', () => {
  const h = harness(); h.enter(true); const v = h.video(); v.pause();
  h.tick(2300); assert.equal(v.paused, false); assert.equal(v.plays, 1);
});

test('native crop mode leaves the page classes untouched and masks the rest', () => {
  const h = harness(), v = h.video(); h.enterNative(true); h.tick(2100);
  assert.equal(h.html.classList.contains('nexo-pip-root'), false);
  assert.equal(v.classList.contains('nexo-pip-video'), false);
  assert.equal(h.wrapper.classList.contains('nexo-pip-ancestor'), false);
  const mask = h.html.children.find(c => c.isConnected);
  assert.ok(mask, 'mask overlay appended');
  assert.equal(mask.styles['box-shadow'], '0 0 0 100vmax #000');
  assert.equal(mask.styles.top, '48px');
  h.leave(); assert.equal(mask.isConnected, false);
});

test('native crop mode still recovers a site pause', () => {
  const h = harness(), v = h.video(); h.enterNative(true); h.tick(500);
  v.pause(); h.tick(200); assert.equal(v.paused, false); assert.equal(v.plays, 1);
});

test('video state reports the on-screen rectangle', () => {
  const h = harness(); h.video(); h.tick(2100);
  const state = h.reports.filter(x => x.type === 'video_state').pop();
  assert.deepEqual([state.rx, state.ry, state.rw, state.rh], [0, 48, 640, 360]);
});

test('css mode puts back the root class when the site rewrites it', () => {
  const h = harness(); h.video(); h.enter(true); h.tick(100);
  h.html.classList.remove('nexo-pip-root'); h.tick(2100);
  assert.equal(h.html.classList.contains('nexo-pip-root'), true);
});
