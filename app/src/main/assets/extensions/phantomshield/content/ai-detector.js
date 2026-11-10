/* Candidate collection stays local; only the signals of the accepted detail level leave the tab. */
(function() {
  'use strict';
  if (window.top !== window) return;
  let enabled = false;
  let rich = false;
  let observer = null;
  let timer = null;
  let busy = false;
  let epoch = 0;
  let sequence = 0;
  let batches = 0;
  let seen = new WeakMap();
  const pendingRoots = new Set();
  const MAX_NODES_PER_SCAN = 400;
  const MAX_BATCHES = 4;
  let walker = null;
  const hidden = new Map();
  const marker = /(?:^|[\s_-])(?:ads?|advert(?:isement|ising)?|sponsor(?:ed)?|promo(?:ted|tion)?|publicidad|banner)(?:$|[\s_-])/i;
  const labels = /\b(sponsored|patrocinado|promocionado|advertisement|publicidad)\b/i;
  const selector = 'aside, iframe, ins, article, section, li, figure, a[href], div[data-ad-slot], div[data-ad-client], div[class], div[id]';
  // Tamaños estándar de anuncios (IAB) en píxeles CSS.
  const AD_SIZES = [[300, 250], [336, 280], [728, 90], [970, 90], [970, 250], [320, 50], [320, 100],
    [300, 600], [160, 600], [468, 60], [300, 50], [250, 250], [300, 100]];
  const TWO_PART_SUFFIX = /\.(?:com?|org|net|gov|edu|ac)\.[a-z]{2}$/;

  function siteOf(host) {
    const labels = String(host || '').toLowerCase().split('.').filter(Boolean);
    return labels.slice(TWO_PART_SUFFIX.test(host) ? -3 : -2).join('.');
  }
  const pageSite = siteOf(location.hostname);

  function hostOfUrl(value) {
    try {
      const url = new URL(value, location.href);
      return url.protocol === 'http:' || url.protocol === 'https:' ? url.hostname.toLowerCase() : '';
    } catch (error) { return ''; }
  }

  function isAdSize(width, height) {
    return AD_SIZES.some(size => Math.abs(width - size[0]) <= 6 && Math.abs(height - size[1]) <= 6);
  }

  /** Primeros caracteres de texto visible; nunca scripts, estilos ni campos. */
  function excerpt(element) {
    let text = '';
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    let node;
    let visited = 0;
    while (text.length < 220 && visited < 80 && (node = walker.nextNode())) {
      visited++;
      const parent = node.parentElement;
      if (!parent || /^(?:SCRIPT|STYLE|NOSCRIPT|TEMPLATE|TEXTAREA|OPTION)$/.test(parent.tagName)) continue;
      const value = node.nodeValue.replace(/\s+/g, ' ').trim();
      if (value) text += (text ? ' ' : '') + value;
    }
    return text.slice(0, 220);
  }

  function enqueue(root) {
    if (!(root instanceof Element) || pendingRoots.has(document.body)) return;
    if (pendingRoots.size >= 40) {
      pendingRoots.clear();
      pendingRoots.add(document.body);
    } else pendingRoots.add(root);
  }

  function* nextElements() {
    let visited = 0;
    // Continue where the previous scan stopped; do not repeatedly scan only the top of a long feed.
    while (visited < MAX_NODES_PER_SCAN) {
      let element;
      if (!walker) {
        const root = pendingRoots.values().next().value;
        if (!root) break;
        pendingRoots.delete(root);
        if (!root.isConnected) continue;
        walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
        element = root;
      } else {
        element = walker.nextNode();
        if (!element) { walker = null; continue; }
      }
      visited++;
      yield element;
    }
  }

  function describe(element) {
    if (element.closest('form, nav, header, [role="dialog"], [contenteditable="true"], #phantomshield-ad-bait') ||
        element.querySelector('input, textarea, select, [contenteditable="true"]') ||
        element.contains(document.activeElement) || hidden.has(element)) return null;
    const rect = element.getBoundingClientRect();
    if (rect.width < 20 || rect.height < 10 || rect.width * rect.height > innerWidth * innerHeight * 0.7 ||
        getComputedStyle(element).display === 'none') return null;
    const signals = [];
    if (element.hasAttribute('data-ad-slot') || element.hasAttribute('data-ad-client')) signals.push('ad-attribute');
    if (marker.test(element.className + ' ' + element.id)) signals.push('ad-class');
    const label = (element.getAttribute('aria-label') || '') + ' ' +
      Array.from(element.querySelectorAll('small, [aria-label], [class*="sponsor"], [class*="label"]')).slice(0, 8)
        .map(node => ((node.getAttribute('aria-label') || '') + ' ' + node.textContent).slice(0, 80)).join(' ');
    const match = label.match(labels);
    if (match) signals.push(/sponsor|patrocin|promocion/i.test(match[0]) ? 'sponsored-label' : 'advertisement-label');
    if (element.querySelector('a[rel~="sponsored"]') ||
        (element.tagName === 'A' && /(?:^|\s)sponsored(?:\s|$)/i.test(element.getAttribute('rel') || ''))) {
      signals.push('sponsored-link');
    }

    // Señales estructurales: marco de terceros, tamaño de anuncio, enlaces hacia fuera.
    const frameElement = element.tagName === 'IFRAME' ? element : element.querySelector('iframe[src]');
    const frameHost = frameElement ? hostOfUrl(frameElement.getAttribute('src') || '') : '';
    const thirdPartyFrame = Boolean(frameHost && siteOf(frameHost) !== pageSite);
    if (thirdPartyFrame && (element.tagName === 'IFRAME' || signals.length)) signals.push('third-party-frame');
    const adSized = isAdSize(rect.width, rect.height);
    if (adSized && (signals.length || element.tagName === 'IFRAME')) signals.push('ad-size');

    const anchors = element.tagName === 'A' ? [element] : Array.from(element.querySelectorAll('a[href]')).slice(0, 8);
    const linkHosts = [];
    for (const anchor of anchors) {
      const host = hostOfUrl(anchor.getAttribute('href') || '');
      if (host && !linkHosts.includes(host)) linkHosts.push(host);
    }
    const external = linkHosts.filter(host => siteOf(host) !== pageSite);
    // Banner típico: pocos enlaces, todos hacia otro sitio, con imagen y tamaño de anuncio.
    if (rich && external.length && external.length === linkHosts.length && anchors.length <= 4 &&
        (adSized || signals.length) && element.querySelector('img, picture, video, iframe')) {
      signals.push('external-links');
      if (adSized && !signals.includes('ad-size')) signals.push('ad-size');
    }
    if (!signals.length) return null;
    if (/^(?:fixed|sticky)$/.test(getComputedStyle(element).position)) signals.push('fixed-position');

    const descriptor = { tag: element.tagName, signals: signals, width: rect.width, height: rect.height };
    if (rich) {
      // Contexto para que el modelo pueda decidir; el fondo lo vuelve a limitar y sanear.
      const text = excerpt(element);
      if (text) descriptor.text = text;
      if (external.length) descriptor.links = external.slice(0, 3);
      if (thirdPartyFrame) descriptor.frame = frameHost;
      const tokens = (String(typeof element.className === 'string' ? element.className : '') + ' ' + element.id)
        .split(/\s+/).filter(token => /^[A-Za-z0-9_-]{2,32}$/.test(token)).slice(0, 6);
      if (tokens.length) descriptor.cls = tokens;
    }
    return descriptor;
  }

  function restore() {
    for (const [element, previous] of hidden) {
      // Restore only our own inline override; preserve subsequent page changes.
      if (element.style.getPropertyValue('display') === 'none' && element.style.getPropertyPriority('display') === 'important') {
        if (previous.value) element.style.setProperty('display', previous.value, previous.priority);
        else element.style.removeProperty('display');
      }
    }
    hidden.clear();
  }

  async function scan() {
    if (!enabled) return { ok: false, error: 'Activa IA y autoriza este sitio en los ajustes.' };
    if (document.visibilityState === 'hidden') {
      stopObserver();
      return { ok: false, error: 'Vuelve a esta página para analizarla.' };
    }
    startObserver();
    if (busy) return { ok: false, error: 'Análisis IA en curso.' };
    if (batches >= MAX_BATCHES) return { ok: false, error: 'Límite de lotes por página alcanzado; recarga para analizar de nuevo.' };
    const candidates = [];
    const elements = new Map();
    const descriptors = new Map();
    // Bound traversal and payload. Never serialize HTML, full URLs or form values;
    // a short text excerpt is included only in the detailed mode the user accepted.
    for (const element of nextElements()) {
      if (!element.isConnected || !element.matches(selector)) continue;
      const descriptor = describe(element);
      if (!descriptor) continue;
      const signature = JSON.stringify(descriptor);
      if (seen.get(element) === signature || Array.from(elements.values()).some(parent => parent.contains(element))) continue;
      const id = 'c-' + (++sequence);
      candidates.push(Object.assign({ id: id }, descriptor));
      descriptors.set(id, signature);
      elements.set(id, element);
      if (candidates.length === 12) break;
    }
    if (!candidates.length) {
      if (walker || pendingRoots.size) schedule();
      return { ok: true, analyzed: 0, hidden: 0 };
    }
    busy = true;
    batches++;
    const revision = epoch;
    const changed = new Set();
    const watcher = new MutationObserver(function(records) {
      for (const [id, element] of elements) {
        if (records.some(record => element === record.target || element.contains(record.target))) changed.add(id);
      }
    });
    for (const element of elements.values()) watcher.observe(element, {
      childList: true, subtree: true, attributes: true, characterData: true
    });
    try {
      const result = await browser.runtime.sendMessage({ type: 'AI_CLASSIFY', candidates: candidates });
      if (!enabled || epoch !== revision || document.visibilityState === 'hidden') return { ok: false, error: 'El estado de protección cambió.' };
      if (!result || !result.ok) return result || { ok: false, error: 'IA no disponible.' };
      let count = 0;
      for (const candidate of candidates) {
        const element = elements.get(candidate.id);
        const current = describe(element);
        // A slow response must never hide a container that has changed content.
        if (changed.has(candidate.id) || !element.isConnected || !current ||
            JSON.stringify(current) !== descriptors.get(candidate.id)) continue;
        seen.set(element, JSON.stringify(current));
        const decision = result.decisions.find(item => item.id === candidate.id);
        if (decision && decision.hide === true) {
          hidden.set(element, { value: element.style.getPropertyValue('display'), priority: element.style.getPropertyPriority('display') });
          element.style.setProperty('display', 'none', 'important');
          count++;
        }
      }
      return { ok: true, analyzed: candidates.length, hidden: count };
    } catch (error) {
      return { ok: false, error: 'IA no disponible; continúa el bloqueo local.' };
    } finally {
      watcher.disconnect(); busy = false;
      if (batches >= MAX_BATCHES) {
        stopObserver();
        pendingRoots.clear();
        walker = null;
      } else if (walker || pendingRoots.size) schedule();
    }
  }

  function schedule() {
    if (document.visibilityState === 'hidden') { stopObserver(); return; }
    if (!enabled || busy || batches >= MAX_BATCHES) return;
    if (timer !== null) return;
    timer = setTimeout(function() { timer = null; scan(); }, 1200);
  }

  function stopObserver() {
    if (observer) observer.disconnect();
    observer = null;
    clearTimeout(timer);
    timer = null;
  }

  function startObserver() {
    if (!enabled || observer || batches >= MAX_BATCHES || !document.body || document.visibilityState === 'hidden') return;
    observer = new MutationObserver(function(records) {
      for (const record of records) {
        const target = record.target instanceof Element ? record.target : record.target.parentElement;
        if (target) enqueue(target.closest(selector) || target);
        const roots = record.type === 'characterData' || record.type === 'attributes' ? [] : record.addedNodes;
        if (roots.length > 40) {
          // A large insertion is one subtree of work, not hundreds of queued roots.
          enqueue(record.target);
          continue;
        }
        for (const root of roots) {
          enqueue(root);
        }
      }
      schedule();
    });
    observer.observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true,
      attributeFilter: ['class', 'id', 'aria-label', 'data-ad-slot', 'data-ad-client', 'rel'] });
  }

  function updateVisibility() {
    if (document.visibilityState === 'hidden') stopObserver();
    else if (enabled && batches < MAX_BATCHES) { enqueue(document.body); startObserver(); schedule(); }
  }
  document.addEventListener('visibilitychange', updateVisibility);
  // Native media protection can consume visibilitychange before content listeners.
  window.addEventListener('focus', updateVisibility);
  window.addEventListener('pageshow', updateVisibility);


  async function refresh() {
    const revision = ++epoch;
    enabled = false;
    rich = false;
    if (observer) observer.disconnect();
    observer = null;
    clearTimeout(timer);
    timer = null;
    restore();
    seen = new WeakMap();
    pendingRoots.clear();
    walker = null;
    try {
      const state = await browser.runtime.sendMessage({ type: 'GET_SITE_STATE' });
      if (revision !== epoch) return;
      enabled = Boolean(state && state.enabled && state.aiEnabled);
      rich = Boolean(enabled && state.aiDetail === 'rich');
      if (enabled && document.body) {
        pendingRoots.add(document.body);
        startObserver();
        schedule();
      }
    } catch (error) { enabled = false; }
  }
  browser.runtime.onMessage.addListener(function(message, sender) {
    if (sender.id === browser.runtime.id && message && message.type === 'AI_SCAN') {
      if (!walker && document.body) pendingRoots.add(document.body);
      return scan();
    }
    return undefined;
  });
  browser.storage.onChanged.addListener(function(changes, area) {
    if (area === 'local' && (changes.aiConfig || changes.adblockEnabled || changes.disabled_domains)) refresh();
  });
  refresh();
})();
