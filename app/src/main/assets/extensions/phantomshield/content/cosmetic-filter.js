/**
 * PhantomShield 8.2 - Reversible cosmetic filtering.
 */
(function() {
  'use strict';

  const hostname = location.hostname.toLowerCase();
  const hiddenAttribute = 'data-phantomshield-hidden';
  let enabled = false;
  let styleElement = null;
  let observer = null;
  let debounceTimer = null;

  function hostMatches(domain) {
    return hostname === domain || hostname.endsWith('.' + domain);
  }

  function isValidSelector(selector) {
    try {
      document.createDocumentFragment().querySelector(selector);
      return true;
    } catch (error) {
      return false;
    }
  }

  /**
   * Acepta dos formatos de reglas:
   *  - Array estilo ABP: ["##.sel", "youtube.com##.sel", "~ejemplo.com##.sel"]
   *  - Objeto: { generic: [], youtube: [], procedural: [] }
   */
  function parseRules(raw) {
    const result = { selectors: [], procedural: [] };
    if (Array.isArray(raw)) {
      for (const line of raw) {
        if (typeof line !== 'string') continue;
        const separator = line.indexOf('##');
        if (separator === -1) continue;
        const selector = line.slice(separator + 2).trim();
        const domains = line.slice(0, separator).split(',')
          .map(function(d) { return d.trim().toLowerCase(); })
          .filter(Boolean);
        if (!selector) continue;
        const included = domains.filter(function(d) { return !d.startsWith('~'); });
        const excluded = domains.filter(function(d) { return d.startsWith('~'); })
          .map(function(d) { return d.slice(1); });
        if (excluded.some(hostMatches)) continue;
        if (included.length > 0 && !included.some(hostMatches)) continue;
        result.selectors.push(selector);
      }
    } else if (raw && typeof raw === 'object') {
      result.selectors.push.apply(result.selectors, raw.generic || []);
      if (hostMatches('youtube.com')) {
        result.selectors.push.apply(result.selectors, raw.youtube || []);
      }
      result.procedural = Array.isArray(raw.procedural) ? raw.procedural : [];
    }
    result.selectors = result.selectors.filter(isValidSelector);
    return result;
  }

  const rules = parseRules(globalThis.PHANTOM_COSMETIC_RULES);
  const standardSelectors = rules.selectors;

  function buildStyleText() {
    const selectors = standardSelectors.concat(['[' + hiddenAttribute + '="true"]']);
    if (selectors.length === 0) return '';
    return selectors.join(',\n') +
      ' {\n  display: none !important;\n  visibility: hidden !important;\n' +
      '  pointer-events: none !important;\n}\n';
  }

  function ensureStyle() {
    if (!enabled || styleElement) return;
    const css = buildStyleText();
    if (!css) return;
    styleElement = document.createElement('style');
    styleElement.id = 'phantomshield-cosmetic-style';
    styleElement.textContent = css;
    (document.head || document.documentElement).appendChild(styleElement);
  }

  function removeStyle() {
    if (styleElement) styleElement.remove();
    styleElement = null;
  }

  function hideElement(element) {
    if (element && element.nodeType === Node.ELEMENT_NODE) {
      element.setAttribute(hiddenAttribute, 'true');
    }
  }

  function evaluateProceduralFilters() {
    if (!enabled) return;
    for (const rule of rules.procedural) {
      if (!rule || typeof rule.selector !== 'string') continue;
      try {
        const elements = document.querySelectorAll(rule.selector);
        for (const element of elements) {
          if (rule.pseudo === 'has-text') {
            const expected = String(rule.args || '').trim().toLocaleLowerCase();
            const actual = (element.textContent || '').toLocaleLowerCase();
            if (expected && actual.includes(expected)) hideElement(element);
          }
        }
      } catch (error) {}
    }
  }

  function nodeLooksLikeAd(node) {
    if (!(node instanceof Element)) return false;
    const className = typeof node.className === 'string' ? node.className.toLowerCase() : '';
    const id = (node.id || '').toLowerCase();
    return /(^|[\s_-])ad(?:s|vert)?([\s_-]|$)/.test(className) ||
      /(^|[_-])ad(?:s|vert)?([_-]|$)/.test(id) ||
      className.includes('sponsored-post');
  }

  function scheduleProceduralScan() {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(evaluateProceduralFilters, 200);
  }

  function startObserver() {
    if (!enabled || observer || !document.documentElement) return;
    observer = new MutationObserver(function(mutations) {
      let shouldScan = false;
      for (const mutation of mutations) {
        for (const node of mutation.addedNodes) {
          if (!(node instanceof Element)) continue;
          shouldScan = true;
          if (nodeLooksLikeAd(node)) hideElement(node);
        }
      }
      if (shouldScan) scheduleProceduralScan();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
    scheduleProceduralScan();
  }

  function stopObserver() {
    if (observer) observer.disconnect();
    observer = null;
    clearTimeout(debounceTimer);
    debounceTimer = null;
  }

  function applyEnabled(nextEnabled) {
    enabled = Boolean(nextEnabled);
    if (enabled) {
      ensureStyle();
      startObserver();
    } else {
      stopObserver();
      removeStyle();
    }
  }

  function refreshState() {
    browser.runtime.sendMessage({ type: 'GET_SITE_STATE' }).then(function(state) {
      applyEnabled(Boolean(state && state.enabled));
    }).catch(function() {
      applyEnabled(true);
    });
  }

  refreshState();

  browser.storage.onChanged.addListener(function(changes, areaName) {
    if (areaName === 'local' && (changes.adblockEnabled || changes.disabled_domains)) {
      refreshState();
    }
  });
})();
