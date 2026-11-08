/**
 * PhantomShield 8.2 - Popup.
 * Usa las mismas claves de almacenamiento que el background:
 *  - adblockEnabled, privacyEnabled: interruptores globales.
 *  - disabled_domains: sitios en los que el usuario apagó la protección.
 *  - blockedCount (vía GET_STATS): contador total.
 */
document.addEventListener('DOMContentLoaded', async () => {
  const siteToggle = document.getElementById('site-toggle');
  const adblockToggle = document.getElementById('adblock-toggle');
  const privacyToggle = document.getElementById('privacy-toggle');
  const domainEl = document.getElementById('current-domain');
  const siteHintEl = document.getElementById('site-hint');
  const siteBlocksEl = document.getElementById('site-blocks');
  const totalBlocksEl = document.getElementById('total-blocks');
  const statusBadge = document.getElementById('shield-status');
  const updateButton = document.getElementById('update-lists');
  const resetButton = document.getElementById('reset-stats');
  const messageEl = document.getElementById('message');
  const lastUpdateEl = document.getElementById('last-update');
  const versionEl = document.getElementById('version');

  let currentTab = null;
  let domain = '';

  versionEl.textContent = 'v' + browser.runtime.getManifest().version;

  function normalizeHost(host) {
    return String(host || '').toLowerCase().replace(/^www\./, '');
  }

  function showMessage(text, isError) {
    messageEl.textContent = text;
    messageEl.className = 'message' + (isError ? ' error' : '');
  }

  function formatNumber(value) {
    return Number(value || 0).toLocaleString('es');
  }

  function updateBadge(globalOn, siteOn) {
    if (!globalOn) {
      statusBadge.textContent = 'Apagado';
      statusBadge.className = 'status-badge off';
    } else if (!siteOn) {
      statusBadge.textContent = 'Pausado aquí';
      statusBadge.className = 'status-badge';
    } else {
      statusBadge.textContent = 'Activo';
      statusBadge.className = 'status-badge active';
    }
  }

  async function refresh() {
    const stats = await browser.runtime.sendMessage({
      type: 'GET_STATS',
      tabId: currentTab ? currentTab.id : -1,
      host: domain
    });
    if (!stats || !stats.ok) return;
    const globalOn = Boolean(stats.settings.adblockEnabled);
    const siteOn = !stats.siteDisabled;
    adblockToggle.checked = globalOn;
    privacyToggle.checked = Boolean(stats.settings.privacyEnabled);
    siteToggle.checked = siteOn;
    siteToggle.disabled = !domain || !globalOn;
    siteBlocksEl.textContent = formatNumber(stats.tabBlocked);
    totalBlocksEl.textContent = formatNumber(stats.blockedCount);
    updateBadge(globalOn, siteOn);

    const stored = await browser.storage.local.get({ phantom_last_update: 0, phantom_update_results: [] });
    const last = Number(stored.phantom_last_update) || 0;
    if (stored.phantom_update_results.some(result => !result.ok)) {
      lastUpdateEl.textContent = 'Listas: actualización incompleta';
    } else {
      lastUpdateEl.textContent = last
        ? 'Listas: ' + new Date(last).toLocaleDateString('es', { day: 'numeric', month: 'short' })
        : 'Listas: sin actualizar';
    }
  }

  try {
    const tabs = await browser.tabs.query({ active: true, currentWindow: true });
    currentTab = tabs[0] || null;
    if (currentTab && currentTab.url) {
      try {
        const url = new URL(currentTab.url);
        if (url.protocol === 'http:' || url.protocol === 'https:') {
          domain = normalizeHost(url.hostname);
        }
      } catch (error) {}
    }
  } catch (error) {
    console.error('PhantomShield: no se pudo leer la pestaña activa.', error);
  }

  domainEl.textContent = domain || 'Página interna';
  if (!domain) siteHintEl.textContent = 'No aplica en esta página';

  document.getElementById('configure-ai').addEventListener('click', function() {
    browser.runtime.openOptionsPage().catch(function() { showMessage('No se pudieron abrir los ajustes IA.', true); });
  });
  const scanButton = document.getElementById('scan-ai');
  scanButton.disabled = !domain;
  scanButton.addEventListener('click', async function() {
    if (!currentTab || !domain) return;
    scanButton.disabled = true;
    showMessage('Analizando candidatos publicitarios…');
    try {
      const result = await browser.tabs.sendMessage(currentTab.id, { type: 'AI_SCAN' }, { frameId: 0 });
      if (!result || !result.ok) showMessage((result && result.error) || 'Configura IA y autoriza este sitio.', true);
      else showMessage(result.analyzed + ' candidatos analizados; ' + result.hidden + ' ocultados.');
    } catch (error) { showMessage('Recarga esta página para habilitar el análisis IA.', true); }
    finally { scanButton.disabled = false; }
  });

  siteToggle.addEventListener('change', async () => {
    if (!domain) return;
    const enabled = siteToggle.checked;
    const stored = await browser.storage.local.get({ disabled_domains: [] });
    let list = Array.isArray(stored.disabled_domains)
      ? stored.disabled_domains.map(normalizeHost)
      : [];
    list = list.filter((entry) => entry !== domain);
    if (!enabled) list.push(domain);
    await browser.storage.local.set({ disabled_domains: list });
    await refresh();
    if (currentTab) browser.tabs.reload(currentTab.id).catch(() => {});
  });

  adblockToggle.addEventListener('change', async () => {
    await browser.storage.local.set({ adblockEnabled: adblockToggle.checked });
    await refresh();
    if (currentTab && domain) browser.tabs.reload(currentTab.id).catch(() => {});
  });

  privacyToggle.addEventListener('change', async () => {
    await browser.storage.local.set({ privacyEnabled: privacyToggle.checked });
    showMessage('Se aplicará al recargar la página.');
  });

  updateButton.addEventListener('click', async () => {
    updateButton.disabled = true;
    showMessage('Descargando listas…');
    try {
      const results = await browser.runtime.sendMessage({ type: 'FORCE_UPDATE' });
      if (Array.isArray(results)) {
        const ok = results.filter((r) => r && r.ok).length;
        showMessage(ok + ' de ' + results.length + ' listas actualizadas.', ok < results.length);
      } else {
        showMessage((results && results.error) || 'No se pudieron actualizar las listas.', true);
      }
      await refresh();
    } catch (error) {
      showMessage('No se pudieron actualizar las listas.', true);
    } finally {
      updateButton.disabled = false;
    }
  });

  resetButton.addEventListener('click', async () => {
    await browser.runtime.sendMessage({ type: 'RESET_STATS' });
    showMessage('Contador reiniciado.');
    await refresh();
  });

  try {
    await refresh();
  } catch (error) {
    showMessage('No se pudo conectar con el bloqueador.', true);
  }
});
