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
    setInterval: (callback, delay) => schedule(callback, delay, delay),
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
    if (extra.captureFactory) v.captureStream = () => extra.captureFactory(v);
    v.pause = () => { v.paused = true; v.dispatchEvent(new Event('pause')); };
    v.play = () => { v.plays++; v.paused = false; return Promise.resolve(); };
    videos.push(v); return v;
  }
  return { video, tick, reports, wrapper, body, html, Element, window: win, observed: () => observed,
    enter: resume => onMessage({ type: 'pip_mode', active: true, resume }),
    enterNative: resume => onMessage({ type: 'pip_mode', active: true, resume, isolate: false }),
    leave: () => onMessage({ type: 'pip_mode', active: false }),
    command: command => onMessage({ type: 'pip_command', command }),
    lifecycle: state => onMessage({ type: 'lifecycle', state }),
    captions: (active, live = true) => { onMessage({ type: 'captions', active, live }); if(active) onMessage({type:'captions_grant'}); },
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
  const recorders = [], sources = [], tracks = [];
  class Track extends EventTarget {
    stop() { this.stopped = true; }
    getSettings() { return {sampleRate:48000, channelCount:2}; }
  }
  class Stream {
    constructor(audio) { this.audio = audio; }
    getAudioTracks() { return this.audio; }
    getVideoTracks() { return []; }
    getTracks() { return this.audio; }
  }
  class FakeContext {
    constructor() { this.state = state; }
    resume() { return Promise.resolve(); }
    close() { this.closed = true; }
    createMediaElementSource() { throw Error('Must never reroute the media element'); }
    createMediaStreamSource(stream) { const node = { stream, links: 0, connect() { this.links++; }, disconnect() { this.links = 0; } }; sources.push(node); return node; }
    createAnalyser() { return { fftSize: 512, getByteTimeDomainData: data => data.fill(150) }; }
  }
  class FakeRecorder {
    static isTypeSupported(type) { return type === 'audio/webm;codecs=opus'; }
    constructor(stream, options) { this.stream=stream; this.mimeType=options.mimeType; this.options=options; this.state='inactive'; recorders.push(this); }
    start(slice) { this.slice=slice; this.state='recording'; }
    stop() { this.state='inactive'; }
  }
  return { recorders, sources, tracks, extra: { AudioContext: FakeContext, MediaRecorder: FakeRecorder, MediaStream: Stream,
    captureFactory: () => { const track=new Track(); tracks.push(track); return new Stream([track]); } } };
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
  h.fire('playing', v); h.message({type:'captions_grant'}); await flush(); await flush();
  assert.equal(audio.recorders.length, 2, 'a new recording starts with its own header');
  assert.equal(audio.sources.length, 2, 'each copied stream has a fresh meter');
  h.captions(false);
  assert.equal(audio.recorders[1].state, 'inactive');
});

test('a suspended meter does not prevent audio capture or reroute the video', async () => {
  const audio = audioMocks('suspended');
  const h = harness(audio.extra); h.video();
  h.window.AudioContext = audio.extra.AudioContext;
  h.captions(true); await flush(); h.tick(900); await flush(); await flush();
  assert.equal(audio.recorders.length, 1); h.tick(2000);
  assert.ok(h.reports.some(x => x.type === 'captions_status' && x.code === 'meter_unavailable'));
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
  assert.equal(audio.sources[0].links, 1, 'meter only; no speakers');
  h.message({ type: 'captions_restart' }); await flush(); await flush();
  assert.equal(audio.recorders.length, 2);
  h.captions(false);
  assert.ok(audio.sources.every(s=>s.links === 0));
  assert.ok(audio.tracks.every(t=>t.stopped));
});

test('frames offer a player but cannot capture before native grants ownership', async () => {
  const audio=audioMocks(), h=harness(audio.extra); h.video();
  h.message({type:'captions',active:true,live:true}); await flush();
  assert.ok(h.reports.some(x=>x.type==='captions_offer')); assert.equal(audio.recorders.length,0);
  h.message({type:'captions_grant'}); await flush(); assert.equal(audio.recorders.length,1);
  h.message({type:'captions_grant'}); await flush(); assert.equal(audio.recorders.length,1);
});
test('late Blob reads cannot send audio after disable', async () => {
  const audio=audioMocks(),h=harness(audio.extra);h.video();h.captions(true);await flush();
  let resolve; const promise=new Promise(r=>resolve=r);
  audio.recorders[0].ondataavailable({data:{size:3,arrayBuffer:()=>promise}});
  h.captions(false);resolve(new Uint8Array([1,2,3]).buffer);await flush();
  assert.equal(h.reports.filter(x=>x.type==='captions_audio').length,0);assert.ok(audio.tracks.every(t=>t.stopped));
});
test('a dynamically inserted player is discovered after activation', () => {
  const h=harness();h.message({type:'captions',active:true,live:true});h.tick(1000);
  assert.equal(h.reports.filter(x=>x.type==='captions_offer').length,0);
  h.video();h.tick(1000);assert.ok(h.reports.some(x=>x.type==='captions_offer'));
});
test('unsupported capture and MIME report errors without touching player audio', async () => {
  const h=harness();h.video();h.captions(true);await flush();
  assert.ok(h.reports.some(x=>x.type==='captions_error' && x.code==='unsupported'));
  const audio=audioMocks();audio.extra.MediaRecorder.isTypeSupported=()=>false;
  const h2=harness(audio.extra);h2.video();h2.captions(true);await flush();
  assert.ok(h2.reports.some(x=>x.type==='captions_error'&&x.code==='mime'));assert.equal(audio.recorders.length,0);
});
test('Blob completion order cannot reorder the container byte stream', async () => {
  const audio=audioMocks(), h=harness(audio.extra);h.video();h.captions(true);await flush();
  const recorder=audio.recorders[0];let first;
  recorder.ondataavailable({data:{size:1,arrayBuffer:()=>new Promise(r=>first=r)}});
  recorder.ondataavailable({data:{size:1,arrayBuffer:async()=>new Uint8Array([2]).buffer}});
  await flush();assert.equal(h.reports.filter(x=>x.type==='captions_audio').length,0);
  first(new Uint8Array([1]).buffer);await flush();await flush();
  assert.deepEqual(h.reports.filter(x=>x.type==='captions_audio').map(x=>x.data),['AQ==','Ag==']);
});
