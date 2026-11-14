// PhantomShield 9.2: modelos de respaldo con cambio automático y más proveedores.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../../main/assets/extensions/phantomshield');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');

const ok = body => {
  const content = body.messages ? body.messages[body.messages.length - 1].content : body.messages;
  const candidates = JSON.parse(content).candidates;
  return new Response(JSON.stringify({ usage: { prompt_tokens: 10, completion_tokens: 4 },
    choices: [{ message: { content: JSON.stringify({ decisions: candidates.map(c => ({ id: c.id, isAd: true, confidence: 0.97 })) }) } }] }));
};
const claudeOk = body => {
  const candidates = JSON.parse(body.messages[0].content).candidates;
  return new Response(JSON.stringify({ usage: { input_tokens: 10, output_tokens: 4 },
    content: [{ type: 'text', text: JSON.stringify({ decisions: candidates.map(c => ({ id: c.id, isAd: true, confidence: 0.97 })) }) }] }));
};

function harness(config, route) {
  let time = 1000000;
  const calls = [];
  const listeners = [];
  const store = { aiConfig: Object.assign({ enabled: true, consent: true, scope: 'public' }, config) };
  class Clock extends Date { static now() { return time; } }
  const context = vm.createContext({ URL, TextDecoder, AbortController, Date: Clock, setTimeout, clearTimeout, Response,
    browser: { storage: {
      local: {
        get: async defaults => ({ ...defaults, ...structuredClone(store) }),
        set: async values => { Object.assign(store, structuredClone(values)); }
      },
      onChanged: { addListener: fn => listeners.push(fn) }
    } },
    fetch: async (url, options) => {
      calls.push({ url, headers: options.headers });
      return route(url, JSON.parse(options.body), options);
    } });
  for (const file of ['data/ai-config.js', 'background/ai-classifier.js']) vm.runInContext(read(file), context);
  return {
    context, calls, store, ai: context.PhantomAI, config: context.PhantomAIConfig,
    advance(ms) { time += ms; },
    change(values) { Object.assign(store.aiConfig, values); listeners.forEach(fn => fn({ aiConfig: {} }, 'local')); }
  };
}
const candidate = (id = 'c-1') => [{ id, tag: 'DIV', signals: ['ad-class'], width: 300, height: 120 }];
const primary = { provider: 'custom', endpoint: 'https://primary.example/v1', model: 'main', apiKey: 'k-primary' };
const backups = [
  { provider: 'openrouter', endpoint: 'https://openrouter.ai/api/v1', model: 'openai/gpt-4.1-mini', apiKey: 'k-router' },
  { provider: 'openai', endpoint: 'https://api.openai.com/v1', model: 'gpt-4.1-mini', apiKey: 'k-openai' }
];

test('a primary that fails hands the same batch to the backup, which answers', async () => {
  const h = harness({ ...primary, backups }, (url, body) =>
    url.startsWith('https://primary.example') ? new Response('', { status: 503 }) : ok(body));
  const result = await h.ai.classify('news.com', candidate(), false);
  assert.equal(result.ok, true);
  assert.equal(result.decisions[0].hide, true);
  assert.equal(result.via, 'backup1');
  assert.deepEqual(h.calls.map(call => new URL(call.url).host), ['primary.example', 'openrouter.ai']);
  // Cada proveedor recibe solo su propia clave.
  assert.equal(h.calls[0].headers.Authorization, 'Bearer k-primary');
  assert.equal(h.calls[1].headers.Authorization, 'Bearer k-router');
  assert.equal(h.store.aiStatus.via, 'backup1');
  assert.equal(h.store.aiStatus.fallback, true);
  assert.equal(h.store.aiProviders.primary.state, 'server');
  assert.equal(h.store.aiProviders.backup1.state, 'ok');
  assert.equal((await h.ai.usage()).fallbacks, 1);
});

test('a failing provider is set aside for a while and retried later; settings changes retry it at once', async () => {
  let primaryUp = false;
  const h = harness({ ...primary, backups }, (url, body) =>
    url.startsWith('https://primary.example') ? (primaryUp ? ok(body) : Promise.reject(new TypeError('offline'))) : ok(body));
  await h.ai.classify('one.com', candidate('c-1'), false);
  h.calls.length = 0;
  await h.ai.classify('two.com', candidate('c-2'), false);
  assert.deepEqual(h.calls.map(call => new URL(call.url).host), ['openrouter.ai'], 'primary skipped while set aside');
  primaryUp = true;
  h.advance(25000);
  h.calls.length = 0;
  const back = await h.ai.classify('three.com', candidate('c-3'), false);
  assert.equal(back.via, 'primary');
  assert.deepEqual(h.calls.map(call => new URL(call.url).host), ['primary.example']);

  // Clave rechazada: apartado 30 min, salvo que el usuario cambie los ajustes.
  const auth = harness({ ...primary, backups }, (url, body) =>
    url.startsWith('https://primary.example') ? new Response('', { status: 401 }) : ok(body));
  await auth.ai.classify('one.com', candidate('c-1'), false);
  auth.advance(10 * 60000);
  auth.calls.length = 0;
  await auth.ai.classify('two.com', candidate('c-2'), false);
  assert.deepEqual(auth.calls.map(call => new URL(call.url).host), ['openrouter.ai']);
  auth.change({ apiKey: 'k-new' });
  auth.calls.length = 0;
  await auth.ai.classify('three.com', candidate('c-3'), false);
  assert.equal(new URL(auth.calls[0].url).host, 'primary.example');
});

test('unreadable answers and refusals also fail over; every attempt counts against the budget', async () => {
  const h = harness({ ...primary, backups, requestsPerMinute: 2 }, (url, body) => {
    if (url.startsWith('https://primary.example')) {
      return new Response(JSON.stringify({ choices: [{ message: { content: 'no sé' } }] }));
    }
    if (url.startsWith('https://openrouter.ai')) return new Response(JSON.stringify({ choices: [{ message: { refusal: 'no' } }] }));
    return ok(body);
  });
  const result = await h.ai.classify('news.com', candidate(), false);
  // Límite de 2 por minuto: el tercer proveedor no llega a llamarse.
  assert.equal(h.calls.length, 2);
  assert.equal(result.ok, false);
  assert.equal(result.code, 'budget');
  h.advance(61000);
  const next = await h.ai.classify('news.com', candidate('c-9'), false);
  assert.equal(next.ok, true);
  assert.equal(next.via, 'backup2');
});

test('when every provider is down the page keeps local blocking and the reason is explained', async () => {
  const h = harness({ ...primary, backups: [backups[0]] }, () => new Response('', { status: 500 }));
  const first = await h.ai.classify('news.com', candidate(), false);
  assert.equal(first.ok, false);
  assert.equal(first.code, 'server');
  assert.match(first.error, /problema temporal/);
  h.calls.length = 0;
  const second = await h.ai.classify('news.com', candidate('c-2'), false);
  assert.equal(h.calls.length, 0, 'no calls while all providers are set aside');
  assert.equal(second.code, 'rate');
});

test('Claude and Chat Completions providers can be mixed in one chain', async () => {
  const h = harness({ provider: 'claude', endpoint: 'https://api.anthropic.com/v1', model: 'claude-haiku-5-5', apiKey: 'k-claude',
    backups: [{ provider: 'gemini', endpoint: '', model: 'gemini-2.5-flash', apiKey: 'k-gemini' }] }, (url, body) =>
    url.includes('anthropic') ? new Response('', { status: 529 }) : ok(body));
  const result = await h.ai.classify('news.com', candidate(), false);
  assert.equal(result.ok, true);
  assert.equal(h.calls[0].url, 'https://api.anthropic.com/v1/messages');
  assert.equal(h.calls[0].headers['x-api-key'], 'k-claude');
  assert.equal(h.calls[1].url, 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions');
  assert.equal(h.calls[1].headers.Authorization, 'Bearer k-gemini');
  assert.equal(h.calls[1].headers['x-api-key'], undefined);
});

test('backups are validated: at most two, complete, HTTPS; a backup key requires the authentication permission', () => {
  const { config } = harness(primary, ok);
  const base = { ...primary, enabled: true, consent: true };
  assert.equal(config.validate({ ...base, backups }).backups.length, 2);
  assert.throws(() => config.validate({ ...base, backups: [...backups, backups[0]] }), /Como máximo 2/);
  assert.throws(() => config.validate({ ...base, backups: [{ provider: 'custom', endpoint: 'https://x.example/v1' }] }), /Respaldo 1: indica la API y el modelo/);
  assert.throws(() => config.validate({ ...base, backups: [{ provider: 'custom', endpoint: 'http://x.example/v1', model: 'm' }] }), /Respaldo 1: La API debe usar HTTPS/);
  assert.equal(config.validate({ ...base, backups: [{ provider: 'groq', model: 'llama-3.3-70b-versatile' }] }).backups[0].endpoint, 'https://api.groq.com/openai/v1');
  assert.deepEqual([...config.requiredPermissions({ apiKey: '', backups: [{ apiKey: 'x' }] })], ['websiteContent', 'browsingActivity', 'authenticationInfo']);
  // Configuraciones guardadas sin respaldos siguen siendo válidas.
  assert.deepEqual([...config.validate({ ...base }).backups], []);
  for (const id of ['openai', 'claude', 'openrouter', 'gemini', 'groq', 'deepseek', 'mistral', 'xai', 'custom']) assert.ok(config.providers[id], id);
});

test('testing a backup reuses its saved key only for the same address', async () => {
  const h = harness({ ...primary, backups }, (url, body) => ok(body));
  const reply = await h.ai.probe({ slot: 'backup2', provider: 'openai', endpoint: 'https://api.openai.com/v1', model: 'gpt-4.1-mini', apiKey: '' });
  assert.equal(reply.ok, true);
  assert.equal(h.calls[0].headers.Authorization, 'Bearer k-openai');
  assert.equal(h.store.aiProviders.backup2.state, 'ok');
  h.calls.length = 0;
  await h.ai.probe({ slot: 'backup1', provider: 'custom', endpoint: 'https://other.example/v1', model: 'm', apiKey: '' });
  assert.equal(h.calls[0].headers.Authorization, undefined);
});

// ---------------------------------------------------------------
// Pantalla de opciones
// ---------------------------------------------------------------

async function optionsPage(config) {
  const html = read('options/options.html');
  const elements = new Map();
  for (const match of html.matchAll(/<[^>]*\bid="([^"]+)"[^>]*>/g)) {
    elements.set(match[1], { listeners: {}, value: '', checked: false, hidden: /\bhidden\b/.test(match[0]), disabled: false,
      type: (match[0].match(/\btype="([^"]+)"/) || [])[1] || '', attributes: {}, textContent: '', children: [],
      addEventListener(name, fn) { this.listeners[name] = fn; }, setAttribute(name, value) { this.attributes[name] = value; },
      focus() {}, replaceChildren() { this.children = []; }, appendChild(child) { this.children.push(child); } });
  }
  let stored = structuredClone(config);
  const sent = [];
  const makeElement = tag => ({ tag, children: [], listeners: {}, textContent: '', className: '', value: '',
    setAttribute() {}, addEventListener(name, fn) { this.listeners[name] = fn; }, appendChild(child) { this.children.push(child); } });
  const context = vm.createContext({ URL, document: { getElementById: id => elements.get(id), createElement: makeElement },
    browser: { storage: { local: { get: async defaults => ({ ...defaults, aiConfig: stored }), set: async values => { if (values.aiConfig) stored = values.aiConfig; } } },
      permissions: { getAll: async () => ({ data_collection: [] }), request: async () => true },
      runtime: { sendMessage: async message => { sent.push(message); return { ok: true, ms: 800, detected: true, kept: true }; } } } });
  vm.runInContext(read('data/ai-config.js'), context);
  vm.runInContext(read('options/options.js'), context);
  await new Promise(setImmediate);
  const find = (node, predicate) => { if (predicate(node)) return node; for (const child of node.children || []) { const hit = find(child, predicate); if (hit) return hit; } return null; };
  return { field: id => elements.get(id), get saved() { return stored; }, sent, find,
    fire: async (target, event = 'click') => { await target.listeners[event]({ preventDefault() {} }); await new Promise(setImmediate); } };
}

test('backups can be added, tested, saved and removed without ever showing a saved key', async () => {
  const page = await optionsPage({ enabled: true, consent: true, ...primary, timeoutMs: 12000, allowedHosts: [], scope: 'public',
    backups: [backups[0]] });
  const list = page.field('backups');
  assert.equal(list.children.length, 1);
  const first = list.children[0];
  const keyInput = page.find(first, node => node.tag === 'input' && node.type === 'password');
  assert.equal(keyInput.value, '');
  assert.match(keyInput.placeholder, /Guardada/);

  // Añadir un segundo respaldo (ChatGPT) y probarlo antes de guardar.
  await page.fire(page.field('add-backup'));
  assert.equal(page.field('consent').checked, false);
  const second = list.children[1];
  const select = page.find(second, node => node.tag === 'select');
  select.value = 'openai';
  await page.fire(select, 'change');
  page.find(second, node => node.tag === 'input' && node.type === 'text').value = 'gpt-4.1-mini';
  page.find(second, node => node.tag === 'input' && node.type === 'password').value = 'k-new-openai';
  await page.fire(page.find(second, node => node.tag === 'button' && node.textContent === 'Probar'));
  assert.equal(page.sent.at(-1).config.slot, 'backup2');
  assert.equal(page.sent.at(-1).config.endpoint, 'https://api.openai.com/v1');
  assert.equal(page.field('add-backup').hidden, true, 'at most two backups');

  page.field('consent').checked = true;
  await page.fire(page.field('ai-form'), 'submit');
  assert.equal(page.saved.backups.length, 2);
  assert.equal(page.saved.backups[0].apiKey, 'k-router', 'saved key kept when left empty');
  assert.equal(page.saved.backups[1].provider, 'openai');
  assert.equal(page.saved.backups[1].apiKey, 'k-new-openai');

  // Cambiar la dirección de un respaldo con clave guardada exige otra clave.
  const endpoint = page.find(page.field('backups').children[0], node => node.tag === 'input' && node.type === 'url');
  endpoint.value = 'https://elsewhere.example/v1';
  page.field('consent').checked = true;
  await page.fire(page.field('ai-form'), 'submit');
  assert.match(page.field('status').textContent, /Respaldo 1: al cambiar la dirección/);
  assert.equal(page.saved.backups[0].endpoint, 'https://openrouter.ai/api/v1');

  // Quitarlo y guardar.
  await page.fire(page.find(page.field('backups').children[0], node => node.tag === 'button' && node.textContent === 'Quitar'));
  page.field('consent').checked = true;
  await page.fire(page.field('ai-form'), 'submit');
  assert.equal(page.saved.backups.length, 1);
  assert.equal(page.saved.backups[0].provider, 'openai');
});
