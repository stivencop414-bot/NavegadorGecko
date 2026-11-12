// Regression tests for the real content script's native-message and media events.
// Run with: node --test app/src/test/js/pip-bridge.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/assets/extensions/nexo_bridge/content_script.js'), 'utf8');

function harness(extra = {}) {
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
  const win = new Element();
  let observed = 0;
  const port = { postMessage: value => reports.push(value), onMessage: { addListener: callback => onMessage = callback }, onDisconnect: { addListener() {} } };
  const schedule = (callback, delay, interval = 0) => {
    const id = nextId++; timers.set(id, { callback, at: now + delay, interval }); return id;
  };
  vm.runInNewContext(source, {
    document, window: win, Date: { now: () => now },
    getComputedStyle: () => ({ display: 'block', visibility: 'visible' }),
    MutationObserver: class { observe() { observed++; } },
    browser: { runtime: { connectNative: () => port, sendNativeMessage: () => Promise.resolve({}) } },
    setTimeout: (callback, delay) => schedule(callback, delay), clearTimeout: id => timers.delete(id),
    setInterval: (callback, delay) => schedule(callback, delay, delay), clearInterval: id => timers.delete(id),
    btoa, ...extra
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
  return { video, tick, reports, wrapper, body, html, Element, document, window: win, observed: () => observed,
    enter: resume => onMessage({ type: 'pip_mode', active: true, resume }),
    enterNative: resume => onMessage({ type: 'pip_mode', active: true, resume, isolate: false }),
    leave: () => onMessage({ type: 'pip_mode', active: false }),
    command: command => onMessage({ type: 'pip_command', command }),
    lifecycle: state => onMessage({ type: 'lifecycle', state }),
    captions: (active, live = true, lang) => onMessage({ type: 'captions', active, live, lang }),
    message: value => onMessage(value),
    fire: (type, target) => { const event = new Event(type); Object.defineProperty(event, 'target', { value: target }); document.dispatchEvent(event); },
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

test('visibilitychange reaches pages that are not playing media', () => {
  const h = harness(), v = h.video(); v.pause();
  let seen = 0; h.window.addEventListener('visibilitychange', () => seen++);
  h.window.dispatchEvent(new Event('visibilitychange'));
  assert.equal(seen, 1);
});

test('visibilitychange is hidden while media keeps playing in the background', () => {
  const h = harness(); h.video();
  let seen = 0; h.window.addEventListener('visibilitychange', () => seen++);
  h.window.dispatchEvent(new Event('visibilitychange'));
  assert.equal(seen, 0);
});

test('the bridge does not observe every DOM mutation of the page', () => {
  const h = harness(); h.tick(5000);
  assert.equal(h.observed(), 0);
});

test('returning to the app repairs a frozen playing video without reloading', () => {
  const h = harness(), v = h.video(); let seeks = 0, time = 50;
  Object.defineProperty(v, 'currentTime', { get: () => time, set: value => { seeks++; time = value; } });
  h.lifecycle('background'); h.lifecycle('foreground');
  h.tick(500); h.tick(1400);
  assert.ok(seeks >= 1, 'a frozen decoder is rebuilt with a seek');
  h.tick(1400);
  assert.ok(v.plays >= 1, 'a second stall restarts playback');
});
test('a video that keeps advancing after returning is left alone', () => {
  const h = harness(), v = h.video(); let seeks = 0, time = 50;
  Object.defineProperty(v, 'currentTime', { get: () => time, set: value => { seeks++; time = value; } });
  h.lifecycle('background'); h.lifecycle('foreground');
  h.tick(500); time += 1; h.tick(1400);
  assert.equal(seeks, 0); assert.equal(v.plays, 0);
});
test('a video paused by the system while leaving resumes when background media is allowed', () => {
  const h = harness(), v = h.video();
  h.lifecycle('background'); v.paused = true; h.lifecycle('foreground'); h.tick(500);
  assert.equal(v.paused, false); assert.equal(v.plays, 1);
});
test('a video the user had paused before leaving stays paused', () => {
  const h = harness(), v = h.video(); v.paused = true; h.tick(5000);
  h.lifecycle('background'); h.lifecycle('foreground'); h.tick(3000);
  assert.equal(v.paused, true); assert.equal(v.plays, 0);
});

function audioMocks(state = 'running') {
  const recorders = [], sources = [];
  class FakeContext {
    constructor() { this.state = state; }
    resume() { return Promise.resolve(); }
    close() { this.closed = true; }
    createMediaElementSource(video) { const node = { video, links: 0, connect() { this.links++; }, disconnect() { this.links--; } }; sources.push(node); return node; }
    createMediaStreamDestination() { return { stream: {}, connect() {} }; }
    createAnalyser() { return { fftSize: 512, connect() {}, getByteTimeDomainData: data => data.fill(150) }; }
    get destination() { return {}; }
  }
  class FakeRecorder {
    static isTypeSupported(type) { return type === 'audio/webm;codecs=opus'; }
    constructor(stream, options) { this.options = options; this.state = 'inactive'; recorders.push(this); }
    start(slice) { this.slice = slice; this.state = 'recording'; }
    stop() { this.state = 'inactive'; }
  }
  const window = {};
  return { recorders, sources, extra: { AudioContext: FakeContext, MediaRecorder: FakeRecorder } };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

test('live captions record compressed audio only while the video plays', async () => {
  const audio = audioMocks();
  const h = harness(audio.extra), v = h.video();
  h.window.AudioContext = audio.extra.AudioContext;
  h.captions(true); await flush(); await flush();
  assert.equal(audio.sources.length, 1, 'audio taken from the video element');
  const recorder = audio.recorders[0];
  assert.equal(recorder.state, 'recording'); assert.equal(recorder.slice, 250);
  assert.equal(recorder.options.audioBitsPerSecond, 24000);
  assert.ok(h.reports.some(x => x.type === 'captions_start'));
  recorder.ondataavailable({ data: { size: 3, arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer } });
  await flush(); await flush();
  assert.ok(h.reports.some(x => x.type === 'captions_audio' && x.data === 'AQID'));
  h.fire('pause', v);
  assert.equal(recorder.state, 'inactive');
  assert.ok(h.reports.some(x => x.type === 'captions_stop'));
  h.fire('playing', v); await flush(); await flush();
  assert.equal(audio.recorders.length, 2, 'a new recording starts with its own header');
  assert.equal(audio.sources.length, 1, 'the element source is reused');
  h.captions(false);
  assert.equal(audio.recorders[1].state, 'inactive');
});

test('a suspended audio context never reroutes (and silences) the video', async () => {
  const audio = audioMocks('suspended');
  const h = harness(audio.extra); h.video();
  h.window.AudioContext = audio.extra.AudioContext;
  h.captions(true); await flush(); h.tick(900); await flush(); await flush();
  assert.equal(audio.sources.length, 0);
  assert.ok(h.reports.some(x => x.type === 'captions_error' && x.code === 'suspended'));
});

test('going to the background stops captions recording and returning resumes it', async () => {
  const audio = audioMocks();
  const h = harness(audio.extra); h.video();
  h.window.AudioContext = audio.extra.AudioContext;
  h.captions(true); await flush(); await flush();
  h.lifecycle('background');
  assert.equal(audio.recorders[0].state, 'inactive');
  h.lifecycle('foreground'); h.tick(1000); await flush(); await flush();
  assert.equal(audio.recorders.length, 2);
});

test('a video with its own subtitle track is translated from the track, without audio capture', async () => {
  const audio = audioMocks();
  const h = harness(audio.extra), v = h.video();
  h.window.AudioContext = audio.extra.AudioContext;
  const listeners = {};
  const cue = { text: '<i>Hello</i>  there', startTime: 1, endTime: 3 };
  v.textTracks = [{ kind: 'subtitles', mode: 'showing', language: 'en', cues: [cue], activeCues: [cue],
    addEventListener: (type, fn) => { listeners[type] = fn; }, removeEventListener: type => { delete listeners[type]; } }];
  h.captions(true, false); await flush();
  assert.equal(v.textTracks[0].mode, 'hidden', 'native rendering hidden while Nexo shows its own');
  assert.ok(h.reports.some(x => x.type === 'captions_track'));
  const sent = h.reports.find(x => x.type === 'captions_cues');
  assert.deepEqual(JSON.parse(JSON.stringify(sent.cues)), [{ i: 0, s: 1, e: 3, t: 'Hello there' }]);
  assert.ok(h.reports.some(x => x.type === 'captions_active' && x.ids[0] === 0));
  assert.equal(audio.recorders.length, 0, 'no Deepgram audio for tracked videos');
  h.captions(false);
  assert.equal(v.textTracks[0].mode, 'showing', 'original mode restored');
});

test('without a track and without a Deepgram key the page asks for a key', async () => {
  const audio = audioMocks();
  const h = harness(audio.extra); h.video();
  h.window.AudioContext = audio.extra.AudioContext;
  h.captions(true, false); await flush();
  assert.ok(h.reports.some(x => x.type === 'captions_error' && x.code === 'need_key'));
  assert.equal(audio.recorders.length, 0);
});

test('a restart after a network drop starts a fresh recording and stopping detaches the capture branch', async () => {
  const audio = audioMocks();
  const h = harness(audio.extra); h.video();
  h.window.AudioContext = audio.extra.AudioContext;
  h.captions(true); await flush(); await flush();
  assert.equal(audio.sources[0].links, 3, 'speakers + recorder + level meter');
  h.message({ type: 'captions_restart' }); await flush(); await flush();
  assert.equal(audio.recorders.length, 2);
  h.captions(false);
  assert.equal(audio.sources[0].links, 1, 'only the speaker output stays connected');
});

// --- Subtítulos: líneas HLS que llegan tarde y texto dibujado por el reproductor ---

function fakeTrack(cues, active = []) {
  const listeners = {};
  return { kind: 'subtitles', mode: 'disabled', language: 'en', cues, activeCues: active,
    fire: () => listeners.cuechange && listeners.cuechange(),
    addEventListener: (type, fn) => { listeners[type] = fn; }, removeEventListener: type => { delete listeners[type]; } };
}

test('subtitle lines appended later by HLS are sent and shown with stable ids', async () => {
  const h = harness(), v = h.video();
  const first = { text: 'One', startTime: 10, endTime: 12 };
  const track = fakeTrack([first], [first]);
  v.textTracks = [track];
  h.captions(true, false); await flush();
  const earlier = { text: 'Zero', startTime: 2, endTime: 4 }, later = { text: 'Two', startTime: 14, endTime: 16 };
  // Las nuevas líneas se insertan ordenadas: los índices antiguos se desplazan.
  track.cues = [earlier, first, later];
  track.activeCues = [later];
  track.fire();
  const sent = h.reports.filter(x => x.type === 'captions_cues').flatMap(x => x.cues).map(c => [c.i, c.t]);
  assert.deepEqual(sent, [[0, 'One'], [1, 'Zero'], [2, 'Two']]);
  const active = h.reports.filter(x => x.type === 'captions_active').pop();
  assert.deepEqual(Array.from(active.ids), [2]);
});

function youtubeHarness(tracks, currentTrack = null) {
  const h = harness(), v = h.video();
  const calls = [];
  const api = {
    loadModule: name => calls.push(['load', name]),
    unloadModule: name => calls.push(['unload', name]),
    getOption: (module, option) => option === 'tracklist' ? tracks : currentTrack,
    setOption: (module, option, value) => { calls.push(['set', value.languageCode]); currentTrack = value; }
  };
  const player = new h.Element(h.body);
  player.wrappedJSObject = api;
  let segments = [];
  const box = new h.Element(player);
  h.document.getElementById = id => id === 'movie_player' ? player : null;
  h.document.querySelectorAll = selector => {
    if (selector.includes('.ytp-caption-segment')) return segments;
    if (selector.includes('.ytp-caption-window-container')) return segments.length ? [box] : [];
    return [];
  };
  h.document.head = h.html;
  return { h, v, calls, box, say: text => { segments = text === null ? [] : text.split('|').map(t => ({ textContent: t })); } };
}

test('YouTube captions are read from the player without Deepgram and the site copy is hidden', async () => {
  const y = youtubeHarness([{ languageCode: 'en', kind: 'asr' }, { languageCode: 'es', kind: '' }]);
  y.h.captions(true, false, 'en'); await flush();
  assert.deepEqual(y.calls.slice(0, 2), [['load', 'captions'], ['set', 'en']]);
  assert.ok(y.h.reports.some(x => x.type === 'captions_track' && x.source === 'dom'));
  assert.equal(y.h.reports.some(x => x.type === 'captions_error'), false);
  y.say('Hello|there'); y.h.tick(260);
  assert.equal(y.h.reports.filter(x => x.type === 'captions_text').pop().text, 'Hello there');
  assert.equal(y.box.styles.opacity, '0', 'YouTube text hidden while Nexo draws it');
  y.h.tick(1000);
  assert.equal(y.h.reports.filter(x => x.type === 'captions_text').length, 1, 'unchanged text is not resent');
  y.say(null); y.h.tick(260);
  assert.equal(y.h.reports.filter(x => x.type === 'captions_text').pop().text, '');
  y.h.captions(false);
  assert.deepEqual(y.calls.pop(), ['unload', 'captions'], 'Nexo turns off only what it turned on');
});

test('a YouTube track the user already chose is kept', async () => {
  const y = youtubeHarness([{ languageCode: 'en' }, { languageCode: 'fr' }], { languageCode: 'fr' });
  y.h.captions(true, false); await flush();
  assert.equal(y.calls.some(call => call[0] === 'set'), false);
  y.h.captions(false);
  assert.equal(y.calls.some(call => call[0] === 'unload'), false);
});

test('without any captions the page asks for a key, then picks up the site captions once enabled', async () => {
  const h = harness(); h.video();
  let segments = [];
  h.document.querySelectorAll = selector => selector.includes('.ytp-caption-segment') ? segments : [];
  h.captions(true, false); await flush();
  assert.ok(h.reports.some(x => x.type === 'captions_error' && x.code === 'need_key'));
  segments = [{ textContent: 'Bonjour' }];
  h.tick(1100);
  assert.ok(h.reports.some(x => x.type === 'captions_track' && x.source === 'dom'));
  assert.equal(h.reports.filter(x => x.type === 'captions_text').pop().text, 'Bonjour');
});

test('a new video in the same player chooses the caption source again', async () => {
  const h = harness(), v = h.video();
  h.captions(true, false); await flush();
  const cue = { text: 'Hola', startTime: 0, endTime: 2 };
  v.textTracks = [fakeTrack([cue], [cue])];
  h.fire('loadedmetadata', v); h.tick(1300); await flush();
  assert.ok(h.reports.some(x => x.type === 'captions_track' && x.source === 'track'));
  assert.ok(h.reports.some(x => x.type === 'captions_cues'));
});
