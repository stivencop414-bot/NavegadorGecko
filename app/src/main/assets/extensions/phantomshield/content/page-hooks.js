/**
 * PhantomShield - Popup controls, optional Canvas variation and YouTube ad removal.
 * This file intentionally contains no WebExtension APIs because it runs in MAIN.
 */
(function() {
  'use strict';

  if (window.__phantomShieldPageHooks) return;
  Object.defineProperty(window, '__phantomShieldPageHooks', {
    value: true,
    configurable: false,
    enumerable: false
  });

  const state = {
    adblockEnabled: false,
    privacyEnabled: false
  };
  let canvasRestore = null;
  let updateYouTubeMonitoring = function() {};

  function readBooleanAttribute(name, fallback) {
    const root = document.documentElement;
    if (!root) return fallback;
    const value = root.getAttribute(name);
    if (value === null) return fallback;
    return value === 'true';
  }

  function syncSettings() {
    state.adblockEnabled = readBooleanAttribute('data-phantomshield-adblock', false);
    state.privacyEnabled = readBooleanAttribute('data-phantomshield-privacy', false);
    if (state.privacyEnabled) installCanvasPrivacy();
    else if (canvasRestore) { canvasRestore(); canvasRestore = null; }
    if (!state.adblockEnabled) restoreYouTubeVideo();
    updateYouTubeMonitoring();
  }

  document.addEventListener('phantomshield:settings', syncSettings, false);

  function reportBlockedPopup() {
    document.dispatchEvent(new CustomEvent('phantomshield:popup-blocked'));
  }

  function hostnameMatches(hostname, domain) {
    return hostname === domain || hostname.endsWith('.' + domain);
  }

  const POPUP_DOMAINS = [
    'popads.net', 'popcash.net', 'exoclick.com', 'juicyads.com',
    'propellerads.com', 'propellerclicks.com', 'hilltopads.com',
    'adsterra.com', 'clickadu.com', 'trafficjunky.net', 'trafficjunky.com'
  ];

  function isKnownPopupUrl(value) {
    if (!value) return false;
    try {
      const hostname = new URL(String(value), location.href).hostname.toLowerCase();
      return POPUP_DOMAINS.some(function(domain) {
        return hostnameMatches(hostname, domain);
      });
    } catch (error) {
      return false;
    }
  }

  function installPopupGuard() {
    const originalOpen = window.open;
    if (typeof originalOpen !== 'function') return;

    let lastTrustedGesture = 0;
    const recordGesture = function(event) {
      if (event.isTrusted) lastTrustedGesture = Date.now();
    };
    window.addEventListener('pointerup', recordGesture, true);
    window.addEventListener('touchend', recordGesture, true);
    window.addEventListener('keydown', recordGesture, true);

    window.open = function(url) {
      if (!state.adblockEnabled) return originalOpen.apply(this, arguments);

      const recentlyActivated = Date.now() - lastTrustedGesture <= 2500;
      const userActivated = Boolean(
        navigator.userActivation && navigator.userActivation.isActive
      );
      const blankWithoutGesture = (!url || url === 'about:blank') &&
        !recentlyActivated && !userActivated;

      if (isKnownPopupUrl(url) || blankWithoutGesture) {
        reportBlockedPopup();
        return null;
      }
      return originalOpen.apply(this, arguments);
    };

    document.addEventListener('click', function(event) {
      if (!state.adblockEnabled || event.isTrusted) return;
      const element = event.target instanceof Element ? event.target.closest('a[href]') : null;
      if (element && isKnownPopupUrl(element.href)) {
        event.preventDefault();
        event.stopImmediatePropagation();
        reportBlockedPopup();
      }
    }, true);
  }

  function installCanvasPrivacy() {
    if (canvasRestore || !window.HTMLCanvasElement || !window.CanvasRenderingContext2D) return;

    const originalToDataURL = HTMLCanvasElement.prototype.toDataURL;
    const originalGetImageData = CanvasRenderingContext2D.prototype.getImageData;
    const originalPutImageData = CanvasRenderingContext2D.prototype.putImageData;
    let seed = Math.floor(Math.random() * 0xffffffff) >>> 0;
    try {
      const values = new Uint32Array(1);
      crypto.getRandomValues(values);
      seed = values[0];
    } catch (error) {}

    function randomIndex(length, salt) {
      let value = (seed ^ salt) >>> 0;
      value ^= value << 13;
      value ^= value >>> 17;
      value ^= value << 5;
      return length > 0 ? (value >>> 0) % length : 0;
    }

    const variedGetImageData = function() {
      const imageData = originalGetImageData.apply(this, arguments);
      if (!state.privacyEnabled || !imageData || imageData.data.length < 4) return imageData;
      const pixelCount = Math.floor(imageData.data.length / 4);
      const offset = randomIndex(pixelCount, 0x13579bdf) * 4;
      imageData.data[offset] = (imageData.data[offset] + 1) % 256;
      return imageData;
    };

    const variedToDataURL = function() {
      if (!state.privacyEnabled || this.width === 0 || this.height === 0) {
        return originalToDataURL.apply(this, arguments);
      }
      try {
        const copy = document.createElement('canvas');
        copy.width = this.width;
        copy.height = this.height;
        const context = copy.getContext('2d');
        context.drawImage(this, 0, 0);
        const imageData = originalGetImageData.call(context, 0, 0, copy.width, copy.height);
        const pixelCount = Math.floor(imageData.data.length / 4);
        const offset = randomIndex(pixelCount, 0x2468ace0) * 4;
        imageData.data[offset] = (imageData.data[offset] + 1) % 256;
        originalPutImageData.call(context, imageData, 0, 0);
        return originalToDataURL.apply(copy, arguments);
      } catch (error) {
        return originalToDataURL.apply(this, arguments);
      }
    };
    CanvasRenderingContext2D.prototype.getImageData = variedGetImageData;
    HTMLCanvasElement.prototype.toDataURL = variedToDataURL;
    canvasRestore = function() {
      if (CanvasRenderingContext2D.prototype.getImageData === variedGetImageData) {
        CanvasRenderingContext2D.prototype.getImageData = originalGetImageData;
      }
      if (HTMLCanvasElement.prototype.toDataURL === variedToDataURL) {
        HTMLCanvasElement.prototype.toDataURL = originalToDataURL;
      }
    };
  }

  // ---------------------------------------------------------------
  // YouTube: quitar los anuncios de los datos del reproductor
  // ---------------------------------------------------------------

  const YOUTUBE_HOSTS = ['youtube.com', 'youtube-nocookie.com', 'youtubekids.com'];
  const isYouTube = YOUTUBE_HOSTS.some(function(domain) {
    return hostnameMatches(location.hostname, domain);
  });
  // Campos con los que YouTube programa anuncios antes, durante y después del video.
  const AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];

  function stripAdKeys(target) {
    if (!target || typeof target !== 'object') return false;
    let changed = false;
    for (const key of AD_KEYS) {
      if (key in target) {
        try { delete target[key]; changed = true; } catch (error) {}
      }
    }
    return changed;
  }

  function isAdEntry(entry) {
    try {
      return Boolean(entry && entry.command && entry.command.reelWatchEndpoint &&
        entry.command.reelWatchEndpoint.adClientParams &&
        entry.command.reelWatchEndpoint.adClientParams.isAd);
    } catch (error) {
      return false;
    }
  }

  function pruneEntries(holder) {
    if (!holder || !Array.isArray(holder.entries) || !holder.entries.some(isAdEntry)) return;
    // Shorts: quitar los videos publicitarios de la secuencia.
    try { holder.entries = holder.entries.filter(function(entry) { return !isAdEntry(entry); }); } catch (error) {}
  }

  /**
   * Elimina la publicidad de una respuesta de YouTube ya convertida en objeto.
   * Solo comprueba un puñado de propiedades conocidas: no recorre el árbol.
   */
  function pruneYouTubeData(data) {
    if (!state.adblockEnabled || !data || typeof data !== 'object') return data;
    try {
      if (Array.isArray(data)) {
        // Respuestas agrupadas (m.youtube.com): [{ playerResponse }, { response }].
        for (let i = 0; i < data.length && i < 12; i++) {
          const item = data[i];
          if (item && typeof item === 'object') {
            stripAdKeys(item);
            stripAdKeys(item.playerResponse);
          }
        }
        return data;
      }
      stripAdKeys(data);
      stripAdKeys(data.playerResponse);
      const prefetch = data.endpoint && data.endpoint.watchEndpoint &&
        data.endpoint.watchEndpoint.unserializedPrefetchData;
      if (prefetch) stripAdKeys(prefetch.playerResponse);
      pruneEntries(data);
      pruneEntries(data.reelWatchSequenceResponse);
    } catch (error) {}
    return data;
  }

  /** Envuelve una función nativa conservando su apariencia (toString, name, length). */
  function wrapNative(owner, name, handler) {
    const original = owner && owner[name];
    if (typeof original !== 'function') return;
    try {
      owner[name] = new Proxy(original, {
        apply: function(target, thisArg, args) {
          return handler(target, thisArg, args);
        }
      });
    } catch (error) {}
  }

  function installYouTubeAdPruning() {
    if (!isYouTube) return;

    // 1. Datos incrustados en el HTML de la página del video.
    for (const name of ['ytInitialPlayerResponse', 'playerResponse']) {
      try {
        let current = pruneYouTubeData(window[name]);
        Object.defineProperty(window, name, {
          configurable: true,
          enumerable: true,
          get: function() { return current; },
          set: function(value) { current = pruneYouTubeData(value); }
        });
      } catch (error) {}
    }

    // 2. Respuestas de la API interna (cambio de video, listas, Shorts).
    wrapNative(JSON, 'parse', function(target, thisArg, args) {
      const result = Reflect.apply(target, thisArg, args);
      return result && typeof result === 'object' ? pruneYouTubeData(result) : result;
    });
    if (window.Response && Response.prototype) {
      wrapNative(Response.prototype, 'json', function(target, thisArg, args) {
        const promise = Reflect.apply(target, thisArg, args);
        return promise && typeof promise.then === 'function' ? promise.then(pruneYouTubeData) : promise;
      });
    }
    // XMLHttpRequest con responseType "json" no pasa por JSON.parse.
    try {
      const descriptor = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'response');
      if (descriptor && typeof descriptor.get === 'function' && descriptor.configurable) {
        const getter = descriptor.get;
        Object.defineProperty(XMLHttpRequest.prototype, 'response', Object.assign({}, descriptor, {
          get: new Proxy(getter, {
            apply: function(target, thisArg, args) {
              const value = Reflect.apply(target, thisArg, args);
              return value && typeof value === 'object' && thisArg && thisArg.responseType === 'json'
                ? pruneYouTubeData(value) : value;
            }
          })
        }));
      }
    } catch (error) {}
  }

  let youtubeVideo = null;
  let youtubeVideoState = null;

  function restoreYouTubeVideo() {
    if (youtubeVideo && youtubeVideoState) {
      try {
        if(youtubeVideo.muted === true && youtubeVideoState.changedMuted) youtubeVideo.muted = youtubeVideoState.muted;
      } catch (error) {}
    }
    youtubeVideo = null;
    youtubeVideoState = null;
  }

  const SKIP_SELECTOR = '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, ' +
    '.ytp-ad-skip-button-slot button, .ytp-ad-skip-button-container button, ' +
    '.ytp-ad-overlay-close-button, .ytp-ad-overlay-close-container';

  let quarantinedVideo = null;
  const skippedButtons = new WeakMap();
  function pipVisible() { return readBooleanAttribute('data-nexo-pip-active', false); }
  let currentPlayer = null;
  function handleYouTubePlayer() {
    if (!isYouTube) return;
    const player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
    currentPlayer = player;
    const video = (player && player.querySelector('video')) || document.querySelector('video.html5-main-video');
    const skipButton = document.querySelector(SKIP_SELECTOR);
    const adUI = document.querySelector('.ytp-ad-preview-text, .ytp-ad-player-overlay, .ytp-ad-player-overlay-layout');
    const adClass = Boolean(player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')));
    if(!state.adblockEnabled || !video || !adClass || (!adUI && !skipButton)) {
      restoreYouTubeVideo(); quarantinedVideo=null; return;
    }
    if(skipButton && !skipButton.disabled) {
      const rect = typeof skipButton.getBoundingClientRect === 'function' ? skipButton.getBoundingClientRect() : {width:1,height:1};
      const last=skippedButtons.get(skipButton);
      if(rect.width>0 && rect.height>0 && (last===undefined || Date.now()-last>=1000)) {
        skippedButtons.set(skipButton,Date.now());skipButton.click();
      }
    }
    if(youtubeVideo === video && youtubeVideoState &&
        (youtubeVideoState.source !== video.currentSrc || youtubeVideoState.duration !== video.duration)) {
      restoreYouTubeVideo();quarantinedVideo=video;
    }
    // Stale ad classes must not seek a newly loaded content video or a live stream.
    if(quarantinedVideo===video || !adUI || !Number.isFinite(video.duration) || video.duration<=0.5 || video.duration>180) return;
    if(youtubeVideo !== video) {
      restoreYouTubeVideo();youtubeVideo=video;
      youtubeVideoState={muted:video.muted,changedMuted:false,source:video.currentSrc,duration:video.duration};
    }
    if(!video.muted) { video.muted=true;youtubeVideoState.changedMuted=true; }
    try {
      // A paused player is intentional. Never call play() or overwrite playback speed.
      if(!video.paused && !video.seeking && video.currentTime < video.duration-0.3) video.currentTime=video.duration-0.1;
    } catch(_) {}
  }

  function installYouTubePlayerGuard() {
    if (!isYouTube) return;
    let observer = null;
    let observedPlayer = null;
    let timer = null;
    // Solo el reproductor: observar todo el documento costaba fluidez en cada cambio de clase.
    const observePlayer = function(player) {
      if (observer) observer.disconnect();
      observer = new MutationObserver(schedule);
      observedPlayer = player || null;
      if (player) observer.observe(player, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
      else observer.observe(document.documentElement, { childList: true, subtree: true });
    };
    const schedule = function() {
      if (!state.adblockEnabled) return;
      if (document.visibilityState === 'hidden') {
        if(observer) { observer.disconnect();observer=null; }
        if(!pipVisible()) { if(timer!==null) updateYouTubeMonitoring();return; }
        if(timer!==null) return;
        timer=setTimeout(function(){timer=null;if(state.adblockEnabled && pipVisible())handleYouTubePlayer();},500);
        return;
      }
      if (!observer) { updateYouTubeMonitoring(); return; }
      if (timer !== null) return;
      // Class animations can mutate on every frame. One scan per 250 ms is enough.
      timer = setTimeout(function() {
        timer = null;
        if (document.visibilityState === 'hidden') updateYouTubeMonitoring();
        else if (state.adblockEnabled) {
          handleYouTubePlayer();
          if (observer && currentPlayer && currentPlayer !== observedPlayer) observePlayer(currentPlayer);
        }
      }, 250);
    };
    updateYouTubeMonitoring = function() {
      if (!state.adblockEnabled || document.visibilityState === 'hidden' || !document.documentElement) {
        if (observer) observer.disconnect();
        observer = null;
        clearTimeout(timer);
        timer = null;
        // Nexo may consume visibilitychange to keep media playing in PiP.
        // A cheap media event can wake us after returning; there is no polling.
        if (state.adblockEnabled) document.addEventListener('timeupdate', schedule, true);
        else document.removeEventListener('timeupdate', schedule, true);
        if(!pipVisible()) restoreYouTubeVideo();
        return;
      }
      if (!observer) {
        observePlayer(document.querySelector('#movie_player, .html5-video-player'));
        document.addEventListener('timeupdate', schedule, true);
      }
      schedule();
    };
    document.addEventListener('visibilitychange', updateYouTubeMonitoring);
    document.addEventListener('nexo:pip-state',updateYouTubeMonitoring);
    window.addEventListener('focus', updateYouTubeMonitoring);
    window.addEventListener('pageshow', updateYouTubeMonitoring);
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', updateYouTubeMonitoring, { once: true });
    }
    updateYouTubeMonitoring();
  }

  installPopupGuard();
  installYouTubePlayerGuard();
  syncSettings();
  // Después de leer el estado: la poda respeta el interruptor del sitio.
  installYouTubeAdPruning();
})();
