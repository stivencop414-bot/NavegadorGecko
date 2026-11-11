/**
 * PhantomShield 8.3 - Background orchestrator.
 */
(function() {
  'use strict';

  const engine = globalThis.PhantomNetworkEngineInstance;
  const redirector = globalThis.PhantomSurrogateRedirector;
  const updater = globalThis.PhantomUpdaterInstance;
  const listManager = globalThis.PhantomFilterListManager;
  const cosmetic = globalThis.PhantomCosmeticEngineInstance;

  const DEFAULT_SETTINGS = Object.freeze({
    adblockEnabled: true,
    privacyEnabled: false,
    antiBlockEnabled: false
  });

  const settings = Object.assign({}, DEFAULT_SETTINGS);
  let countPersistTimer = null;
  let disabledDomains = [];

  // Pestaña -> host de la página principal y anuncios bloqueados en ella.
  const tabHosts = new Map();
  const tabBlocked = new Map();
  // Pestaña -> actividad de IA en la página actual (solo números y un código).
  const tabAi = new Map();

  function aiTabState(tabId) {
    let state = tabAi.get(tabId);
    if (!state) {
      state = { active: false, scans: 0, analyzed: 0, hidden: 0, local: 0, model: 0, memory: 0, cache: 0, code: '', at: 0 };
      tabAi.set(tabId, state);
    }
    return state;
  }

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

  const POPUP_HOSTS = (Array.isArray(globalThis.PHANTOM_POPUP_DOMAINS) ? globalThis.PHANTOM_POPUP_DOMAINS : [])
    .filter(function(entry) { return typeof entry === 'string'; });

  // Peticiones con las que YouTube pide, mide o confirma anuncios.
  const YOUTUBE_AD_PATHS = ['/pagead/', '/api/stats/ads', '/get_midroll_', '/youtubei/v1/player/ad_break', '/ptracking'];
  function isYouTubeAdRequest(parsedUrl) {
    const host = parsedUrl.hostname;
    if (!(hostnameMatches(host, 'youtube.com') || hostnameMatches(host, 'youtube-nocookie.com') ||
          hostnameMatches(host, 'youtubekids.com'))) return false;
    const path = parsedUrl.pathname;
    return YOUTUBE_AD_PATHS.some(function(prefix) { return path.startsWith(prefix); });
  }

  // ---------------------------------------------------------------
  // Ocultación de elementos (filtros cosméticos)
  // ---------------------------------------------------------------

  const HIDE_BASE_SELECTOR = '[data-phantomshield-hidden="true"]';
  const selectorValidity = new Map();

  function validSelector(selector) {
    if (typeof document === 'undefined' || !document.createDocumentFragment) return true;
    let valid = selectorValidity.get(selector);
    if (valid === undefined) {
      try { document.createDocumentFragment().querySelector(selector); valid = true; }
      catch (error) { valid = false; }
      if (selectorValidity.size > 60000) selectorValidity.clear();
      selectorValidity.set(selector, valid);
    }
    return valid;
  }

  function cosmeticCss(selectors) {
    // Reglas pequeñas: un selector que el motor no entienda solo anula su grupo.
    let css = '';
    for (let i = 0; i < selectors.length; i += 40) {
      css += selectors.slice(i, i + 40).join(',\n') + '{display:none!important;}\n';
    }
    return css;
  }

  /*
   * Hoja de estilos de usuario insertada por el navegador: no depende del DOM
   * de la página, así que ni su CSP ni sus scripts pueden quitarla.
   */
  async function injectCosmetic(sender, selectors) {
    const tab = sender && sender.tab;
    if (!selectors.length || !tab || !browser.tabs || typeof browser.tabs.insertCSS !== 'function') return false;
    try {
      await browser.tabs.insertCSS(tab.id, {
        code: cosmeticCss(selectors), cssOrigin: 'user', runAt: 'document_start',
        frameId: typeof sender.frameId === 'number' ? sender.frameId : 0
      });
      return true;
    } catch (error) {
      return false;
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
        tabAi.delete(details.tabId);
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
      if(globalThis.PhantomAntiBlockPatcher && globalThis.PhantomAntiBlockPatcher.intercept(details,
          () => settings.antiBlockEnabled && isEnabledForHost(topHost), () => {})) return {};

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
      const block = function() {
        if (engine && !details.incognito) engine.recordBlocked();
        countTabBlock(details.tabId);
        if (!details.incognito) scheduleCountPersist();
        return { cancel: true };
      };

      if (details.type === 'main_frame') {
        // Una página que el usuario abre no se cancela (las reglas de red son
        // para recursos): antes, tocar un resultado patrocinado o visitar el
        // sitio de una empresa de publicidad daba error de conexión. Solo se
        // cortan las redes de ventanas emergentes, que nadie visita a propósito.
        return POPUP_HOSTS.some(function(domain) { return hostnameMatches(parsedUrl.hostname, domain); })
          ? block() : {};
      }

      if (isYouTubeAdRequest(parsedUrl)) return block();

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
      tabAi.delete(tabId);
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
      /*
       * Solo se fuerza una nueva descarga de listas (el formato de reglas
       * puede haber cambiado). La configuración de IA se conserva: antes cada
       * actualización de la app la apagaba y revocaba el consentimiento, así
       * que la IA dejaba de funcionar sin avisar. La IA sigue apagada por
       * defecto y solo usa el alcance que el usuario aceptó.
       */
      ready.then(function() {
        return browser.storage.local.set({ phantom_last_update: 0 });
      }).then(function() {
        return updater ? updater.updateAllLists() : undefined;
      }).catch(function() {});
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
        const before=aiTabState(tab.id);
        const result = await globalThis.PhantomAI.classify(host, message.candidates, tab.incognito);
        if(tabAi.get(tab.id)!==before)return {ok:false,code:'changed',error:'La página cambió.'};
        if (!isEnabledForHost(host)) return { ok: false, error: 'Protección desactivada.' };
        const ai = aiTabState(tab.id);
        ai.active = true;
        ai.at = Date.now();
        if (result && result.ok) {
          ai.scans++;
          ai.analyzed += result.decisions.filter(d=>!d.deferred).length;
          ai.code = result.warning ? result.warning.code : 'ok';
        } else {
          ai.code = (result && result.code) || 'error';
        }
        return result;
      })();
    }

    if (message.type === 'AI_PAGE') {
      // El detector informa de lo que realmente ocultó y de pasadas sin candidatos.
      const tab = sender && sender.tab;
      if (!tab || sender.id !== browser.runtime.id || sender.frameId !== 0 || tab.incognito || !/^https?:/.test(sender.url||'') || hostOf(sender.url)!==hostOf(tab.url)) return Promise.resolve({ ok: false });
      const ai = aiTabState(tab.id);
      ai.active = true;
      ai.at = Date.now();
      if(message.restored===true) {
        tabBlocked.set(tab.id,Math.max(0,(tabBlocked.get(tab.id)||0)-ai.hidden));
        ai.hidden=0;ai.local=0;ai.model=0;ai.memory=0;ai.cache=0;queueBadgeUpdate(tab.id);
      }
      ai.memoryError=message.memoryError===true;
      if(message.sources)for(const key of ['local','model','memory','cache']) {
        const value=message.sources[key];if(Number.isInteger(value) && value>=0 && value<=12)ai[key]+=value;
      }
      if (Number.isSafeInteger(message.hidden) && message.hidden > 0) {
        const count = Math.min(message.hidden, 12);
        ai.hidden += count;
        // Lo que oculta la IA también cuenta en el escudo de la barra.
        for (let i = 0; i < count; i++) countTabBlock(tab.id);
      }
      if (message.idle === true && (!ai.code || ai.code === 'ready')) ai.code = 'idle';
      if (typeof message.code === 'string' && /^[a-z]{2,16}$/.test(message.code)) ai.code = message.code;
      try{Promise.resolve(browser.runtime.sendMessage({type:'AI_ACTIVITY_UPDATED',tabId:tab.id})).catch(()=>{});}catch(_){}
      return Promise.resolve({ ok: true });
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
          aiDetail: await globalThis.PhantomAI.config().then(function(config) { return config.detail; }, function() { return 'basic'; }),
          host: host
        };
      });
    }

    if (message.type === 'GET_COSMETIC' || message.type === 'COSMETIC_LOOKUP') {
      const tab = sender && sender.tab;
      const topHost = tab ? (hostOf(tab.url) || tabHosts.get(tab.id) || '') : '';
      // Las reglas dependen del documento que las pide (también dentro de marcos).
      const frameHost = hostOf(sender && sender.url) || topHost;
      return ready.then(async function() {
        if (!cosmetic || !isEnabledForHost(topHost)) return { ok: true, enabled: false, generic: false, injected: false, selectors: [] };
        let selectors;
        let generic = true;
        if (message.type === 'GET_COSMETIC') {
          const result = cosmetic.forHost(frameHost);
          generic = result.generic;
          selectors = [HIDE_BASE_SELECTOR].concat(result.selectors.filter(validSelector));
        } else {
          selectors = cosmetic.lookup(frameHost, message.classes, message.ids).filter(validSelector);
        }
        // `inline`: la página comprobó que la inserción no surte efecto en este motor.
        const injected = message.inline === true ? false : await injectCosmetic(sender, selectors);
        return { ok: true, enabled: true, generic: generic, injected: injected, selectors: injected ? [] : selectors };
      });
    }

    if (['AI_MEMORY_LIST','AI_MEMORY_FORGET','AI_MEMORY_CLEAR','AI_MEMORY_EXCLUDE'].includes(message.type)) {
      const ownPages=['options/options.html','popup/popup.html'].map(path=>browser.runtime.getURL(path));
      if(!sender || sender.id!==browser.runtime.id || !ownPages.includes(sender.url) || (sender.tab && sender.tab.incognito))
        return Promise.resolve({ok:false,error:'Abre la memoria desde los ajustes normales de PhantomShield.'});
      return (async()=>{
        try {
          await ready;
          const memory=globalThis.PhantomAIMemory;
          if(message.type!=='AI_MEMORY_LIST') {
            const host=message.type==='AI_MEMORY_CLEAR'?null:message.host;
            if(host!==null && !PhantomAIConfig.publicHost(host))return {ok:false,error:'Dominio inválido.'};
            globalThis.PhantomAI.invalidate();
            if(message.type==='AI_MEMORY_EXCLUDE') {
              const config=await globalThis.PhantomAI.config();
              config.excludedHosts=[...new Set([...config.excludedHosts,host])];
              await browser.storage.local.set({aiConfig:PhantomAIConfig.validate(config)});
            }
            await memory.forget(host);
            await browser.storage.local.set({aiMemoryRevision:{nonce:Date.now()+Math.random(),host}});
            // The event targets open documents; the forgotten host is not retained as a cursor.
            await browser.storage.local.set({aiMemoryRevision:null});
          }
          return {ok:true,...await memory.summary()};
        }catch(_){return {ok:false,error:'No se pudo acceder a la memoria. Inténtalo de nuevo.'};}
      })();
    }

    if (message.type === 'AI_TEST') {
      // Solo las páginas de la propia extensión pueden probar la conexión.
      if (!sender || sender.id !== browser.runtime.id || !String(sender.url || '').startsWith(browser.runtime.getURL(''))) {
        return Promise.resolve({ ok: false, code: 'forbidden', error: 'Prueba no autorizada.' });
      }
      return ready.then(function() { return globalThis.PhantomAI.probe(message.config); });
    }

    if (message.type === 'GET_STATS') {
      const tabId = typeof message.tabId === 'number' ? message.tabId : -1;
      const host = typeof message.host === 'string' ? message.host.toLowerCase() : '';
      return Promise.resolve({
        ok: true,
        blockedCount: engine ? engine.getBlockedCount() : 0,
        tabBlocked: tabBlocked.get(tabId) || 0,
        tabAi: tabAi.has(tabId) ? Object.assign({}, tabAi.get(tabId)) : null,
        siteDisabled: isHostDisabled(host),
        rules: listManager && listManager.counts ? Object.assign({}, listManager.counts) : { network: 0, cosmetic: 0 },
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
      tabAi.clear();
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
