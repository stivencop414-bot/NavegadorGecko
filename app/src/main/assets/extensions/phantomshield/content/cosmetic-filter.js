/**
 * PhantomShield - Ocultación de elementos publicitarios.
 *
 * El fondo decide qué selectores corresponden a este documento (reglas del
 * sitio + genéricas) y los aplica como hoja de estilos de usuario. Este
 * script solo le informa qué clases e ids van apareciendo, para activar las
 * reglas genéricas que hagan falta, y mantiene un respaldo local por si el
 * navegador no admite esa inserción.
 */
(function() {
  'use strict';

  const hostname = location.hostname.toLowerCase();
  const hiddenAttribute = 'data-phantomshield-hidden';
  const MAX_TOKENS = 12000;          // clases + ids recordados por documento
  const MAX_SURVEY_NODES = 6000;     // elementos revisados por pasada
  const FLUSH_DELAY_MS = 350;

  let enabled = false;
  let generic = false;
  let styleElement = null;
  let observer = null;
  let flushTimer = null;
  let epoch = 0;
  // true cuando la inserción de CSS del navegador no funciona en este motor.
  let inline = false;
  const fallbackSelectors = [];
  const seen = new Set();
  let pendingClasses = [];
  let pendingIds = [];

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

  /** Reglas incluidas (formato ABP) que aplican a este documento: solo respaldo. */
  function bundledSelectors() {
    const raw = globalThis.PHANTOM_COSMETIC_RULES;
    const result = [];
    if (!Array.isArray(raw)) return result;
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
      result.push(selector);
    }
    return result;
  }

  // ---------------------------------------------------------------
  // Respaldo: <style> en la página (si el fondo no pudo insertar el CSS)
  // ---------------------------------------------------------------

  function renderFallbackStyle() {
    if (!enabled || !fallbackSelectors.length) return;
    let css = '';
    for (let i = 0; i < fallbackSelectors.length; i += 40) {
      css += fallbackSelectors.slice(i, i + 40).join(',\n') +
        ' {\n  display: none !important;\n  visibility: hidden !important;\n  pointer-events: none !important;\n}\n';
    }
    if (!styleElement) {
      styleElement = document.createElement('style');
      styleElement.id = 'phantomshield-cosmetic-style';
    }
    styleElement.textContent = css;
    if (!styleElement.isConnected) (document.head || document.documentElement).appendChild(styleElement);
  }

  function addFallback(selectors) {
    let changed = false;
    for (const selector of selectors || []) {
      if (typeof selector !== 'string' || fallbackSelectors.includes(selector) || !isValidSelector(selector)) continue;
      fallbackSelectors.push(selector);
      changed = true;
    }
    if (changed) renderFallbackStyle();
  }

  function removeStyle() {
    if (styleElement) styleElement.remove();
    styleElement = null;
    fallbackSelectors.length = 0;
  }

  // ---------------------------------------------------------------
  // Clases e ids presentes -> reglas genéricas
  // ---------------------------------------------------------------

  function note(element) {
    if (seen.size >= MAX_TOKENS) return;
    const id = element.id;
    if (id && typeof id === 'string' && id.length <= 80 && !seen.has('#' + id)) {
      seen.add('#' + id);
      pendingIds.push(id);
    }
    const classes = element.classList;
    if (!classes) return;
    for (let i = 0; i < classes.length && i < 24; i++) {
      const name = classes[i];
      if (name.length > 80 || seen.has('.' + name)) continue;
      seen.add('.' + name);
      pendingClasses.push(name);
    }
  }

  function survey(root) {
    if (!generic || !(root instanceof Element) || seen.size >= MAX_TOKENS) return;
    note(root);
    const elements = root.querySelectorAll('[class],[id]');
    const limit = Math.min(elements.length, MAX_SURVEY_NODES);
    for (let i = 0; i < limit; i++) note(elements[i]);
    if (pendingClasses.length || pendingIds.length) scheduleFlush();
  }

  function scheduleFlush() {
    if (flushTimer !== null) return;
    flushTimer = setTimeout(flush, FLUSH_DELAY_MS);
  }

  function flush() {
    flushTimer = null;
    if (!enabled || !generic || (!pendingClasses.length && !pendingIds.length)) return;
    const classes = pendingClasses.splice(0, 1500);
    const ids = pendingIds.splice(0, 1500);
    const revision = epoch;
    browser.runtime.sendMessage({ type: 'COSMETIC_LOOKUP', classes: classes, ids: ids, inline: inline }).then(function(reply) {
      if (revision !== epoch || !reply || !reply.enabled) return;
      if (!reply.injected) addFallback(reply.selectors);
    }).catch(function() {});
    if (pendingClasses.length || pendingIds.length) scheduleFlush();
  }

  // ---------------------------------------------------------------
  // Elementos añadidos después de cargar
  // ---------------------------------------------------------------

  function hideElement(element) {
    if (element && element.nodeType === Node.ELEMENT_NODE) {
      element.setAttribute(hiddenAttribute, 'true');
    }
  }

  function nodeLooksLikeAd(node) {
    const className = typeof node.className === 'string' ? node.className.toLowerCase() : '';
    const id = (node.id || '').toLowerCase();
    return /(^|[\s_-])ad(?:s|vert)?([\s_-]|$)/.test(className) ||
      /(^|[_-])ad(?:s|vert)?([_-]|$)/.test(id) ||
      className.includes('sponsored-post');
  }

  function startObserver() {
    if (!enabled || observer || !document.documentElement) return;
    observer = new MutationObserver(function(mutations) {
      for (const mutation of mutations) {
        for (const node of mutation.addedNodes) {
          if (!(node instanceof Element)) continue;
          if (nodeLooksLikeAd(node)) hideElement(node);
          survey(node);
        }
      }
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
    survey(document.documentElement);
  }

  function stopObserver() {
    if (observer) observer.disconnect();
    observer = null;
    clearTimeout(flushTimer);
    flushTimer = null;
    pendingClasses = [];
    pendingIds = [];
  }

  /**
   * ¿La hoja insertada por el navegador está activa? Un elemento de prueba con
   * el atributo de ocultación debe quedar con display:none.
   */
  function injectionWorks() {
    const root = document.documentElement;
    if (!root) return true;
    try {
      const probe = document.createElement('div');
      probe.setAttribute(hiddenAttribute, 'true');
      root.appendChild(probe);
      const hidden = getComputedStyle(probe).display === 'none';
      probe.remove();
      return hidden;
    } catch (error) {
      return true;
    }
  }

  function apply(state) {
    enabled = Boolean(state && state.enabled);
    generic = Boolean(enabled && state.generic);
    if (!enabled) {
      stopObserver();
      removeStyle();
      return;
    }
    if (state.injected && !inline && !injectionWorks()) {
      // El navegador aceptó la inserción pero no la aplicó: pedir los
      // selectores y usar el <style> de la página desde ahora.
      inline = true;
      refreshState();
      return;
    }
    if (!state.injected) {
      addFallback(['[' + hiddenAttribute + '="true"]']);
      addFallback(state.selectors && state.selectors.length ? state.selectors : bundledSelectors());
    }
    startObserver();
  }

  function refreshState() {
    const revision = ++epoch;
    browser.runtime.sendMessage({ type: 'GET_COSMETIC', inline: inline }).then(function(state) {
      if (revision === epoch) apply(state);
    }).catch(function() {
      // Sin respuesta del fondo: proteger con las reglas incluidas.
      if (revision === epoch) apply({ enabled: true, generic: false, injected: false, selectors: bundledSelectors() });
    });
  }

  refreshState();

  browser.storage.onChanged.addListener(function(changes, areaName) {
    if (areaName === 'local' && (changes.adblockEnabled || changes.disabled_domains)) {
      refreshState();
    }
  });
})();
