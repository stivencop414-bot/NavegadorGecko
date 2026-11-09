/**
 * PhantomShield 8.3 - Popup controls, optional Canvas variation and DOM video controls.
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

  let youtubeVideo = null;
  let youtubeVideoState = null;

  function restoreYouTubeVideo() {
    if (youtubeVideo && youtubeVideoState) {
      try {
        youtubeVideo.muted = youtubeVideoState.muted;
        youtubeVideo.playbackRate = youtubeVideoState.playbackRate;
      } catch (error) {}
    }
    youtubeVideo = null;
    youtubeVideoState = null;
  }

  function handleYouTubePlayer() {
    if (!hostnameMatches(location.hostname, 'youtube.com')) return;
    const video = document.querySelector('video.html5-main-video');
    const player = document.getElementById('movie_player');
    const skipButton = document.querySelector(
      '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button'
    );
    const isAd = Boolean(
      (player && (player.classList.contains('ad-showing') ||
        player.classList.contains('ad-interrupting'))) ||
      document.querySelector('.ytp-ad-preview-text, .ytp-ad-player-overlay')
    );

    if (!state.adblockEnabled || !video || !isAd) {
      restoreYouTubeVideo();
      return;
    }

    if (youtubeVideo !== video) {
      restoreYouTubeVideo();
      youtubeVideo = video;
      youtubeVideoState = { muted: video.muted, playbackRate: video.playbackRate };
    }

    if (!video.muted) video.muted = true;
    if (skipButton) {
      skipButton.click();
    } else {
      const nextRate = Math.min(8, video.playbackRate < 1 ? 1 : video.playbackRate * 2);
      if (video.playbackRate !== nextRate) video.playbackRate = nextRate;
    }
  }

  function installYouTubePlayerGuard() {
    if (!hostnameMatches(location.hostname, 'youtube.com')) return;
    let observer = null;
    let timer = null;
    const schedule = function() {
      if (!state.adblockEnabled) return;
      if (document.visibilityState === 'hidden') {
        if (observer || timer !== null) updateYouTubeMonitoring();
        return;
      }
      if (!observer) { updateYouTubeMonitoring(); return; }
      if (timer !== null) return;
      // Class animations can mutate on every frame. One scan per 250 ms is enough.
      timer = setTimeout(function() {
        timer = null;
        if (document.visibilityState === 'hidden') updateYouTubeMonitoring();
        else if (state.adblockEnabled) handleYouTubePlayer();
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
        restoreYouTubeVideo();
        return;
      }
      if (!observer) {
        observer = new MutationObserver(schedule);
        observer.observe(document.documentElement, {
          childList: true, subtree: true, attributes: true, attributeFilter: ['class']
        });
        document.addEventListener('timeupdate', schedule, true);
      }
      schedule();
    };
    document.addEventListener('visibilitychange', updateYouTubeMonitoring);
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
})();
