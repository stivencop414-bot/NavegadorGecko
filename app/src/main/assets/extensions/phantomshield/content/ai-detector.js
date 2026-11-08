/* Candidate collection stays local; only coarse advertising signals leave the tab. */
(function() {
  'use strict';
  if (window.top !== window) return;
  let enabled = false;
  let observer = null;
  let timer = null;
  let busy = false;
  let epoch = 0;
  let sequence = 0;
  let batches = 0;
  let seen = new WeakMap();
  const pendingRoots = new Set();
  let walker = null;
  const hidden = new Map();
  const marker = /(?:^|[\s_-])(?:ads?|advert(?:isement|ising)?|sponsor(?:ed)?|promo(?:ted|tion)?|publicidad|banner)(?:$|[\s_-])/i;
  const labels = /\b(sponsored|patrocinado|promocionado|advertisement|publicidad)\b/i;
  const selector = 'aside, iframe, ins, article, section, div[data-ad-slot], div[data-ad-client], div[class], div[id]';

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
    while (visited < 800) {
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
    if (element.querySelector('a[rel~="sponsored"]')) signals.push('sponsored-link');
    if (!signals.length) return null;
    return { tag: element.tagName, signals: signals, width: rect.width, height: rect.height };
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
    if (busy) return { ok: false, error: 'Análisis IA en curso.' };
    if (batches >= 3) return { ok: false, error: 'Límite de tres lotes por página; recarga para analizar de nuevo.' };
    const candidates = [];
    const elements = new Map();
    // Bound traversal and payload. Never serialize HTML, text, URLs or form values.
    for (const element of nextElements()) {
      if (!element.isConnected || !element.matches(selector)) continue;
      const descriptor = describe(element);
      if (!descriptor) continue;
      const signature = JSON.stringify(descriptor);
      if (seen.get(element) === signature || Array.from(elements.values()).some(parent => parent.contains(element))) continue;
      const id = 'c-' + (++sequence);
      candidates.push(Object.assign({ id: id }, descriptor));
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
      if (!enabled || epoch !== revision) return { ok: false, error: 'El estado de protección cambió.' };
      if (!result || !result.ok) return result || { ok: false, error: 'IA no disponible.' };
      let count = 0;
      for (const candidate of candidates) {
        const element = elements.get(candidate.id);
        const current = describe(element);
        // A slow response must never hide a container that has changed content.
        if (changed.has(candidate.id) || !element.isConnected || !current || JSON.stringify(current) !== JSON.stringify({
          tag: candidate.tag, signals: candidate.signals, width: candidate.width, height: candidate.height
        })) continue;
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
      if (walker || pendingRoots.size) schedule();
    }
  }

  function schedule() {
    if (!enabled || busy || batches >= 3) return;
    if (timer !== null) return;
    timer = setTimeout(function() { timer = null; scan(); }, 1200);
  }

  async function refresh() {
    const revision = ++epoch;
    enabled = false;
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
      if (enabled && document.body) {
        pendingRoots.add(document.body);
        observer = new MutationObserver(function(records) {
          for (const record of records) {
            const roots = record.type === 'characterData' ? [record.target.parentElement] : record.addedNodes;
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
        observer.observe(document.body, { childList: true, subtree: true, characterData: true });
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
