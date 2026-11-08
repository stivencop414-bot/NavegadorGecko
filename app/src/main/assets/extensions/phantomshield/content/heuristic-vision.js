/**
 * PhantomShield 8.2 - Sponsored-post detector for Facebook and Instagram.
 */
(function() {
  'use strict';

  const hostname = location.hostname.toLowerCase();
  if (!(hostname === 'facebook.com' || hostname.endsWith('.facebook.com') ||
        hostname === 'instagram.com' || hostname.endsWith('.instagram.com'))) {
    return;
  }

  const hiddenAttribute = 'data-phantomshield-vision-hidden';
  const sponsoredTerms = ['sponsored', 'patrocinado', 'publicidad', 'promocionado'];
  let enabled = true;
  let observer = null;
  let scanTimer = null;
  let styleElement = null;

  function ensureStyle() {
    if (styleElement) return;
    styleElement = document.createElement('style');
    styleElement.id = 'phantomshield-vision-style';
    styleElement.textContent =
      '[' + hiddenAttribute + '="true"] { display: none !important; }';
    (document.head || document.documentElement).appendChild(styleElement);
  }

  function removeStyle() {
    if (styleElement) styleElement.remove();
    styleElement = null;
  }

  function normalizeText(value) {
    return String(value || '')
      .replace(/[\u200B-\u200D\uFEFF]/g, '')
      .replace(/\s+/g, ' ')
      .toLocaleLowerCase();
  }

  function containsSponsoredLabel(article) {
    const header = article.querySelector('header, h1, h2, h3, h4, [role="heading"]');
    if (!header) return false;
    const region = header.parentElement || header;
    const text = normalizeText(region.textContent);
    return sponsoredTerms.some(function(term) {
      return text.includes(term);
    });
  }

  function scan() {
    if (!enabled) return;
    const articles = document.querySelectorAll(
      '[role="article"], div[data-pagelet^="FeedUnit"]'
    );
    for (const article of articles) {
      if (containsSponsoredLabel(article)) {
        article.setAttribute(hiddenAttribute, 'true');
      }
    }
  }

  function scheduleScan() {
    clearTimeout(scanTimer);
    scanTimer = setTimeout(scan, 300);
  }

  function start() {
    if (!enabled || observer || !document.body) return;
    ensureStyle();
    observer = new MutationObserver(scheduleScan);
    observer.observe(document.body, { childList: true, subtree: true });
    scan();
  }

  function stop() {
    if (observer) observer.disconnect();
    observer = null;
    clearTimeout(scanTimer);
    scanTimer = null;
    removeStyle();
  }

  function setEnabled(value) {
    enabled = Boolean(value);
    if (enabled) {
      if (document.body) start();
      else document.addEventListener('DOMContentLoaded', start, { once: true });
    } else {
      stop();
    }
  }

  function refreshState() {
    browser.runtime.sendMessage({ type: 'GET_SITE_STATE' }).then(function(state) {
      setEnabled(Boolean(state && state.enabled));
    }).catch(function() {
      setEnabled(true);
    });
  }

  refreshState();

  browser.storage.onChanged.addListener(function(changes, areaName) {
    if (areaName === 'local' && (changes.adblockEnabled || changes.disabled_domains)) {
      refreshState();
    }
  });
})();
