/**
 * PhantomShield 8.3 - Background orchestrator.
 */
(function() {
  'use strict';

  const engine = globalThis.PhantomNetworkEngineInstance;
  const redirector = globalThis.PhantomSurrogateRedirector;
  const updater = globalThis.PhantomUpdaterInstance;
  const listManager = globalThis.PhantomFilterListManager;

  const DEFAULT_SETTINGS = Object.freeze({
    adblockEnabled: true,
    privacyEnabled: false
  });

  const settings = Object.assign({}, DEFAULT_SETTINGS);
  let countPersistTimer = null;
  let disabledDomains = [];

  // Pestaña -> host de la página principal y anuncios bloqueados en ella.
  const tabHosts = new Map();
  const tabBlocked = new Map();

  // A compact, count-only channel also read by the native browser action UI.
  let badgeTimer = null;
  let lastGlobalBadge = null;
  const lastTabBadges = new Map();
  const dirtyBadgeTabs = new Set();
  const badgeFallbackHosts = new Map();

  function badgeCount(value) {
    const count = Math.max(0, Math.floor(Number(value) || 0));
    return count > 999 ? '999+' : String(count);
  }

  function writeBadge(details) {
    try {
      Promise.resolve(browser.browserAction.setBadgeText(details)).catch(function() {});
    } catch (error) {}
  }

  function flushBadges() {
    badgeTimer = null;
    const globalText = settings.adblockEnabled ? badgeCount(engine && engine.getBlockedCount()) : 'OFF';
    if (globalText !== lastGlobalBadge) {
      lastGlobalBadge = globalText;
      writeBadge({ text: globalText });
    }
    for (const tabId of dirtyBadgeTabs) {
      if (!tabHosts.has(tabId) && !tabBlocked.has(tabId) && !lastTabBadges.has(tabId) && !badgeFallbackHosts.has(tabId)) continue;
      const text = !settings.adblockEnabled ? 'OFF'
        : isHostDisabled(tabHosts.get(tabId) || badgeFallbackHosts.get(tabId) || '') ? 'PAUSE'
        : badgeCount(tabBlocked.get(tabId));
      if (text !== lastTabBadges.get(tabId)) {
        lastTabBadges.set(tabId, text);
        writeBadge({ tabId: tabId, text: text });
      }
    }
    dirtyBadgeTabs.clear();
  }

  function queueBadgeUpdate(tabId, allTabs) {
    if (!browser.browserAction || typeof browser.browserAction.setBadgeText !== 'function') return;
    if (typeof tabId === 'number' && tabId >= 0) dirtyBadgeTabs.add(tabId);
    if (allTabs) {
      for (const id of tabHosts.keys()) dirtyBadgeTabs.add(id);
      for (const id of badgeFallbackHosts.keys()) dirtyBadgeTabs.add(id);
      for (const id of tabBlocked.keys()) dirtyBadgeTabs.add(id);
      for (const id of lastTabBadges.keys()) dirtyBadgeTabs.add(id);
    }
    // Requests may arrive in bursts. Update each changed badge once per second,
    // with no repeating timer and no host, title or URL in the action payload.
    if (badgeTimer === null) badgeTimer = setTimeout(flushBadges, 1000);
  }

  const TRACKING_PARAMS = new Set([
    'gclid', 'dclid', 'fbclid', 'msclkid', 'mc_eid', 'yclid', '_hsenc',
    'gbraid', 'wbraid', 'utm_id', 'utm_source', 'utm_medium',
    'utm_campaign', 'utm_term', 'utm_content'
  ]);

  function hostnameMatches(hostname, domain) {
    return hostname === domain || hostname.endsWith('.' + domain);
  }

  function stripTrackingParams(urlString) {
    try {
      const url = new URL(urlString);
      let changed = false;
      for (const parameter of Array.from(url.searchParams.keys())) {
        if (TRACKING_PARAMS.has(parameter.toLowerCase())) {
          url.searchParams.delete(parameter);
          changed = true;
        }
      }
      return changed ? url.toString() : null;
    } catch (error) {
      return null;
    }
  }

  function hostOf(value) {
    if (!value) return '';
    try {
      return new URL(value).hostname.toLowerCase();
    } catch (error) {
      return '';
    }
  }

  function normalizeDomainList(value) {
    if (!Array.isArray(value)) return [];
    return value
      .filter(function(entry) { return typeof entry === 'string' && entry.trim(); })
      .map(function(entry) { return entry.trim().toLowerCase().replace(/^www\./, ''); });
  }

  function isHostDisabled(hostname) {
    if (!hostname) return false;
    const host = hostname.replace(/^www\./, '');
    return disabledDomains.some(function(domain) {
      return hostnameMatches(host, domain);
    });
  }

  function isEnabledForHost(hostname) {
    return settings.adblockEnabled && !isHostDisabled(hostname);
  }

  function countTabBlock(tabId) {
    if (typeof tabId !== 'number' || tabId < 0) return;
    tabBlocked.set(tabId, (tabBlocked.get(tabId) || 0) + 1);
    queueBadgeUpdate(tabId);
  }

  function topHostForRequest(details) {
    if (details.type === 'main_frame') return hostOf(details.url);
    if (typeof details.tabId === 'number' && tabHosts.has(details.tabId)) {
      return tabHosts.get(details.tabId);
    }
    if (Array.isArray(details.frameAncestors) && details.frameAncestors.length > 0) {
      const top = details.frameAncestors[details.frameAncestors.length - 1];
      if (top && top.url) return hostOf(top.url);
    }
    return hostOf(details.documentUrl || details.originUrl || '');
  }

  function isExtensionUrl(url) {
    return url.startsWith('moz-extension://') || url.startsWith('chrome-extension://');
  }

  function scheduleCountPersist() {
    queueBadgeUpdate();
    if (!engine || countPersistTimer !== null) return;
    countPersistTimer = setTimeout(function() {
      countPersistTimer = null;
      browser.storage.local.set({
        blockedCount: engine.getBlockedCount()
      }).catch(function() {});
    }, 2000);
  }

  async function initialize() {
    try {
      const stored = await browser.storage.local.get(
        Object.assign({ blockedCount: 0, disabled_domains: [] }, DEFAULT_SETTINGS)
      );
      for (const key of Object.keys(DEFAULT_SETTINGS)) {
        settings[key] = Boolean(stored[key]);
      }
      disabledDomains = normalizeDomainList(stored.disabled_domains);
      if (engine) {
        engine.setBlockedCount(Number(stored.blockedCount) + engine.getBlockedCount());
      }
    } catch (error) {
      console.warn('[PhantomShield] Could not load settings.', error);
    }

    if (engine) {
      engine.init(
        globalThis.PHANTOM_AD_DOMAINS,
        globalThis.PHANTOM_TRACKER_DOMAINS,
        globalThis.PHANTOM_POPUP_DOMAINS
      );
    }

    if (listManager) {
      try {
        await listManager.reloadFromStorage();
      } catch (error) {
        console.warn('[PhantomShield] Could not load cached filter lists.', error);
      }
    }

    if (updater) {
      try {
        await updater.start();
      } catch (error) {
        console.warn('[PhantomShield] Filter updater failed to start.', error);
      }
    }
    queueBadgeUpdate(undefined, true);
  }

  browser.storage.onChanged.addListener(function(changes, areaName) {
    if (areaName !== 'local') return;
    if (changes.disabled_domains) {
      disabledDomains = normalizeDomainList(changes.disabled_domains.newValue);
    }
    for (const key of Object.keys(DEFAULT_SETTINGS)) {
      if (changes[key]) {
        settings[key] = changes[key].newValue === undefined
          ? DEFAULT_SETTINGS[key]
          : Boolean(changes[key].newValue);
      }
    }
    if (changes.adblockEnabled || changes.disabled_domains) queueBadgeUpdate(undefined, true);
  });

  browser.webRequest.onBeforeRequest.addListener(
    function(details) {
      if (!details.url || isExtensionUrl(details.url)) return {};

      if (details.type === 'main_frame' && typeof details.tabId === 'number' && details.tabId >= 0) {
        tabHosts.set(details.tabId, hostOf(details.url));
        badgeFallbackHosts.delete(details.tabId);
        tabBlocked.set(details.tabId, 0);
        queueBadgeUpdate(details.tabId);
      }

      const topHost = topHostForRequest(details);
      if (typeof details.tabId === 'number' && details.tabId >= 0 && topHost &&
          !tabHosts.has(details.tabId) && badgeFallbackHosts.get(details.tabId) !== topHost) {
        // Badge-only metadata must not change the request engine's routing state.
        badgeFallbackHosts.set(details.tabId, topHost);
        queueBadgeUpdate(details.tabId);
      }
      if (!isEnabledForHost(topHost)) return {};

      const url = details.url;
      const documentUrl = details.documentUrl || details.originUrl || details.initiator || null;

      if (details.type === 'main_frame' || details.type === 'sub_frame') {
        const strippedUrl = stripTrackingParams(url);
        if (strippedUrl && strippedUrl !== url) return { redirectUrl: strippedUrl };
      }

      if (redirector && details.type === 'script') {
        const surrogateUrl = redirector.getSurrogateUrl(url);
        if (surrogateUrl) {
          if (engine && !details.incognito) engine.recordBlocked();
          countTabBlock(details.tabId);
          if (!details.incognito) scheduleCountPersist();
          return { redirectUrl: surrogateUrl };
        }
      }

      const parsedUrl = new URL(url);
      if (hostnameMatches(parsedUrl.hostname, 'youtube.com') &&
          (parsedUrl.pathname.startsWith('/pagead/') || parsedUrl.pathname === '/api/stats/ads')) {
        if (engine && !details.incognito) engine.recordBlocked();
        countTabBlock(details.tabId);
        if (!details.incognito) scheduleCountPersist();
        return { cancel: true };
      }

      if (engine && engine.shouldBlock(url, details.type, documentUrl, !details.incognito)) {
        countTabBlock(details.tabId);
        if (!details.incognito) scheduleCountPersist();
        return { cancel: true };
      }

      return {};
    },
    { urls: ['<all_urls>'] },
    ['blocking']
  );

  if (browser.tabs && browser.tabs.onRemoved) {
    browser.tabs.onRemoved.addListener(function(tabId) {
      tabHosts.delete(tabId);
      tabBlocked.delete(tabId);
      badgeFallbackHosts.delete(tabId);
      lastTabBadges.delete(tabId);
      dirtyBadgeTabs.delete(tabId);
    });
  }

  if (browser.tabs && browser.tabs.onUpdated) {
    browser.tabs.onUpdated.addListener(function(tabId, changeInfo) {
      if (changeInfo && changeInfo.url) {
        const host = hostOf(changeInfo.url);
        if (host) {
          tabHosts.set(tabId, host);
          badgeFallbackHosts.delete(tabId);
          queueBadgeUpdate(tabId);
        }
      }
    });
  }

  const ready = initialize();

  if (browser.runtime.onInstalled) {
    browser.runtime.onInstalled.addListener(function(details) {
      if (!details || !['install', 'update'].includes(details.reason)) return;
      // Make transmission choices visible for new and upgrading users, including older Gecko.
      browser.storage.local.get({ aiConfig: globalThis.PhantomAIConfig.defaults }).then(function(stored) {
        return browser.storage.local.set({
          aiConfig: Object.assign({}, stored.aiConfig, { enabled: false, consent: false }),
          phantom_last_update: 0,
          phantom_update_results: []
        });
      }).then(function() {
        return browser.runtime.openOptionsPage();
      }).catch(function() {
        console.warn('[PhantomShield] No se pudieron abrir los controles de privacidad. La IA requiere activación explícita.');
      });
    });
  }

  browser.runtime.onMessage.addListener(function(message, sender) {
    if (!message || typeof message.type !== 'string') return undefined;

    if (message.type === 'AI_CLASSIFY') {
      return (async function() {
        await ready;
        const tab = sender && sender.tab;
        const host = hostOf(sender && sender.url);
        if (!tab || sender.id !== browser.runtime.id || sender.frameId !== 0 ||
            !/^https?:\/\//.test(sender.url || '') || host !== hostOf(tab.url) || !isEnabledForHost(host)) {
          return { ok: false, error: 'Página no autorizada para IA.' };
        }
        const result = await globalThis.PhantomAI.classify(host, message.candidates, tab.incognito);
        if (!isEnabledForHost(host)) return { ok: false, error: 'Protección desactivada.' };
        return result;
      })();
    }

    if (message.type === 'GET_SITE_STATE') {
      const tab = sender && sender.tab;
      const host = tab
        ? (hostOf(tab.url) || tabHosts.get(tab.id) || '')
        : '';
      return ready.then(async function() {
        return {
          ok: true,
          enabled: isEnabledForHost(host),
          aiEnabled: isEnabledForHost(host) && await globalThis.PhantomAI.available(host, tab && tab.incognito),
          host: host
        };
      });
    }

    if (message.type === 'GET_STATS') {
      const tabId = typeof message.tabId === 'number' ? message.tabId : -1;
      const host = typeof message.host === 'string' ? message.host.toLowerCase() : '';
      return Promise.resolve({
        ok: true,
        blockedCount: engine ? engine.getBlockedCount() : 0,
        tabBlocked: tabBlocked.get(tabId) || 0,
        siteDisabled: isHostDisabled(host),
        settings: Object.assign({}, settings)
      });
    }

    if (message.type === 'POPUP_BLOCKED') {
      const incognito = sender && sender.tab && sender.tab.incognito;
      if (engine && !incognito) engine.recordBlocked();
      if (sender && sender.tab) countTabBlock(sender.tab.id);
      if (!incognito) scheduleCountPersist();
      return Promise.resolve({ ok: true });
    }

    if (message.type === 'RESET_STATS') {
      if (engine) engine.resetBlockedCount();
      tabBlocked.clear();
      queueBadgeUpdate(undefined, true);
      if (countPersistTimer !== null) {
        clearTimeout(countPersistTimer);
        countPersistTimer = null;
      }
      return browser.storage.local.set({ blockedCount: 0 }).then(function() {
        return { ok: true, blockedCount: 0 };
      });
    }

    if (message.type === 'FORCE_UPDATE') {
      if (!updater) return Promise.resolve({ ok: false, error: 'Actualizador no disponible' });
      return updater.updateAllLists().catch(function(error) {
        return {
          ok: false,
          error: error && error.message ? error.message : 'No se pudieron actualizar las listas'
        };
      });
    }
    return undefined;
  });

})();
