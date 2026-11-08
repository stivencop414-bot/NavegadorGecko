/**
 * PhantomShield 8.2 - Isolated-world settings bridge.
 */
(function() {
  'use strict';

  const DEFAULT_SETTINGS = {
    adblockEnabled: true,
    privacyEnabled: false
  };

  let current = Object.assign({}, DEFAULT_SETTINGS);

  function setBooleanAttribute(root, name, value) {
    root.setAttribute(name, value ? 'true' : 'false');
  }

  function publishSettings() {
    const root = document.documentElement;
    if (!root) return;
    setBooleanAttribute(root, 'data-phantomshield-adblock', current.adblockEnabled);
    setBooleanAttribute(root, 'data-phantomshield-privacy', current.privacyEnabled);
    document.dispatchEvent(new CustomEvent('phantomshield:settings'));
  }

  // Publish conservative defaults synchronously, then replace them with saved values.
  publishSettings();

  function refreshState() {
    Promise.all([
      browser.storage.local.get(DEFAULT_SETTINGS),
      browser.runtime.sendMessage({ type: 'GET_SITE_STATE' }).catch(function() { return null; })
    ]).then(function(results) {
      current = Object.assign({}, DEFAULT_SETTINGS, results[0]);
      // El bloqueo se apaga si el usuario desactivó este sitio en el popup.
      if (results[1] && typeof results[1].enabled === 'boolean') {
        current.adblockEnabled = results[1].enabled;
        current.privacyEnabled = Boolean(current.privacyEnabled && results[1].enabled);
      }
      publishSettings();
    }).catch(function() {});
  }

  refreshState();

  browser.storage.onChanged.addListener(function(changes, areaName) {
    if (areaName !== 'local') return;
    const keys = Object.keys(DEFAULT_SETTINGS).concat(['disabled_domains']);
    if (keys.some(function(key) { return key in changes; })) refreshState();
  });

  document.addEventListener('phantomshield:popup-blocked', function() {
    browser.runtime.sendMessage({ type: 'POPUP_BLOCKED' }).catch(function() {});
  });
})();
