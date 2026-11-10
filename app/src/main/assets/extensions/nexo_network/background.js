(function() {
  'use strict';
  const policies = new Map();
  let delayed = 0;
  const proxySupported = Boolean(browser.proxy && browser.proxy.onRequest);
  let privacyQueue = Promise.resolve();
  let privacyManaged = false;
  const origin = browser.runtime.getURL('');
  const sessionStorage=browser.storage && browser.storage.session;
  let loadState='loading', storageQueue=Promise.resolve();
  const blockedProxy=()=>[{type:'socks',host:'127.0.0.1',port:9,proxyDNS:true},null];
  function persist() {
    if(!sessionStorage) return Promise.resolve();
    const routes=Object.fromEntries(policies);
    storageQueue=storageQueue.catch(function(){}).then(()=>sessionStorage.set({routes}));
    return storageQueue;
  }
  function applyPrivacy() {
    privacyQueue = privacyQueue.catch(function() {}).then(async function() {
      const routed = [...policies.values()].some(p => p.type !== 'direct');
      const setting = browser.privacy && browser.privacy.network && browser.privacy.network.peerConnectionEnabled;
      if (routed) {
        if (!setting) throw Error('No se pudo proteger WebRTC.');
        await setting.set({value:false});
        privacyManaged = true;
        const actual = await setting.get({});
        if (actual.value !== false) throw Error('Otra extensión impide proteger WebRTC.');
      } else if (setting && privacyManaged) await setting.clear({});
      const prediction = browser.privacy && browser.privacy.network && browser.privacy.network.networkPredictionEnabled;
      if(routed) {
        if(!prediction) throw Error('No se pudo desactivar la precarga.');
        await prediction.set({value:false});
        if((await prediction.get({})).value !== false) throw Error('No se pudo proteger DNS especulativo.');
      } else if(prediction && privacyManaged) await prediction.clear({});
      if(!routed) privacyManaged=false;
    });
    return privacyQueue;
  }
  const ready=(async()=>{
    if(sessionStorage) {
      const stored=await sessionStorage.get({routes:{}});
      for(const [key,value] of Object.entries(stored.routes || {})) {
        const id=Number(key);if(Number.isInteger(id) && id>=0)policies.set(id,NexoRoutePolicy.validate(value));
      }
      await applyPrivacy();
    }
    loadState='ready';
  })().catch(()=>{loadState='failed'});
  function proxyFor(details) {
    if(loadState==='failed') return details.tabId>=0?blockedProxy():undefined;
    const p=policies.get(details.tabId);return p?NexoRoutePolicy.proxy(p):undefined;
  }
  if (proxySupported) browser.proxy.onRequest.addListener(details => loadState==='loading'?ready.then(()=>proxyFor(details)):proxyFor(details), {urls:['<all_urls>']});
  function beforeRequest(details) {
    if(loadState==='failed') return details.tabId>=0?{cancel:true}:{};
    const p = policies.get(details.tabId);
    if(details.tabId < 0 && [...policies.values()].some(policy => policy.type !== 'direct') &&
        !String(details.originUrl || '').startsWith('moz-extension://')) return {cancel:true};
    if (!p || !p.latency || !/^https?:/.test(details.url)) return {};
    if (delayed >= 256) return {cancel:true};
    delayed++;
    return new Promise(resolve => setTimeout(() => { delayed--; resolve({}); }, p.latency));
  }
  browser.webRequest.onBeforeRequest.addListener(details=>loadState==='loading'?ready.then(()=>beforeRequest(details)):beforeRequest(details), {urls:['<all_urls>']}, ['blocking']);
  browser.runtime.onMessage.addListener(async function(message, sender) {
    if (!sender || sender.id !== browser.runtime.id || !sender.tab) return undefined;
    await ready;
    if(loadState==='failed') return {ok:false};
    if (message && message.type === 'ROUTE_POLICY') {
      const p = policies.get(sender.tab.id);
      // No server addresses are forwarded to content scripts.
      return p ? {geo:p.geo, burner:p.burner, seed:p.seed} : {geo:null, burner:false};
    }
    if (!message || message.type !== 'APPLY_ROUTE' || sender.url !== origin + 'bootstrap.html' || sender.frameId !== 0) return undefined;
    let previous = policies.get(sender.tab.id);
    try {
      const profile = NexoRoutePolicy.validate(message.profile);
      if (profile.type !== 'direct' && (!proxySupported || !sessionStorage)) throw Error('Este motor no admite proxy por solicitud.');
      policies.set(sender.tab.id, profile);
      await applyPrivacy();
      await persist();
      return {ok:true};
    } catch (_) {
      if (previous) policies.set(sender.tab.id, previous); else policies.delete(sender.tab.id);
      await applyPrivacy().catch(function() {});
      return {ok:false};
    }
  });
  browser.tabs.onRemoved.addListener(id => {
    ready.then(async()=>{policies.delete(id);await persist();await applyPrivacy();}).catch(()=>{loadState='failed'});
  });
})();
