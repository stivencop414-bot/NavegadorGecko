/** PhantomShield panel. Reads real counters; no polling or automatic AI requests. */
document.addEventListener('DOMContentLoaded', async () => {
  const field = id => document.getElementById(id);
  let currentTab = null;
  let host = '';
  let domain = '';
  let loaded = false;
  let busy = false;
  let scanAvailable = false;
  let refreshId = 0;
  field('version').textContent = 'v' + browser.runtime.getManifest().version;
  const normalizeHost = value => String(value || '').trim().toLowerCase().replace(/^www\./, '');
  const excludedBy = (site, exception) => site === exception || site.endsWith('.' + exception);
  function showMessage(text, error) {
    field('message').textContent = text;
    field('message').className = error ? 'message error' : 'message';
  }
  function controls() {
    field('adblock-toggle').disabled = !loaded || busy;
    field('privacy-toggle').disabled = !loaded || busy;
    field('site-toggle').disabled = !loaded || busy || !domain || !field('adblock-toggle').checked;
    field('scan-ai').disabled = !loaded || busy || !scanAvailable;
    field('update-lists').disabled = !loaded || busy;
    field('reset-stats').disabled = !loaded || busy;
  }
  function userError(message) {
    const error = new Error(message);
    error.userFacing = true;
    return error;
  }
  async function refresh() {
    const id = ++refreshId;
    const [stats, stored] = await Promise.all([
      browser.runtime.sendMessage({ type: 'GET_STATS', tabId: currentTab ? currentTab.id : -1, host: domain }),
      browser.storage.local.get({ phantom_last_update: 0, phantom_update_results: [], disabled_domains: [], aiConfig: {} })
    ]);
    if (id !== refreshId) return;
    if (!stats || stats.ok !== true || !stats.settings) throw userError('No se pudo leer el estado de protección.');
    const globalOn = stats.settings.adblockEnabled === true;
    const siteOn = !stats.siteDisabled;
    field('adblock-toggle').checked = globalOn;
    field('privacy-toggle').checked = stats.settings.privacyEnabled === true;
    field('site-toggle').checked = globalOn && siteOn;
    field('site-control').hidden = !domain;
    field('current-domain').textContent = domain || 'Página segura de Nexo';
    const inherited = (Array.isArray(stored.disabled_domains) ? stored.disabled_domains : [])
      .filter(value => typeof value === 'string').map(normalizeHost)
      .find(value => value !== domain && excludedBy(domain, value));
    field('site-hint').textContent = !domain
      ? 'PhantomShield se activa al navegar en sitios web.'
      : !globalOn ? 'Activa el bloqueo general para proteger este sitio.'
      : !siteOn && inherited ? 'Pausado por ' + inherited + '. Activar quitará esa excepción.'
      : siteOn ? 'Reglas locales activas. Puedes pausarlas para este sitio.' : 'Protección pausada para este sitio.';
    field('site-blocks').textContent = domain ? Number(stats.tabBlocked || 0).toLocaleString('es') : '—';
    field('total-blocks').textContent = Number(stats.blockedCount || 0).toLocaleString('es');
    const badge = field('shield-status');
    badge.textContent = !globalOn ? 'Apagado' : !domain ? 'Listo' : siteOn ? 'Activo' : 'Pausado aquí';
    badge.className = 'status-badge' + (!globalOn ? ' off' : siteOn && domain ? ' active' : '');
    const config = stored.aiConfig && typeof stored.aiConfig === 'object' ? stored.aiConfig : {};
    const authorized = globalThis.PhantomAIConfig.hostAllowed(config, host);
    scanAvailable = Boolean(domain && globalOn && siteOn && config.enabled === true && config.consent === true && authorized && !(currentTab && currentTab.incognito));
    field('exclude-ai').hidden = !config.enabled || !authorized || !domain;
    field('exclude-ai').disabled = busy;
    field('disable-ai').hidden = !config.enabled;
    field('disable-ai').disabled = busy;
    field('ai-hint').textContent = !config.enabled ? 'IA apagada. Tu protección local funciona sin modelos ni claves.'
      : currentTab && currentTab.incognito ? 'La IA no se utiliza en pestañas privadas.'
      : !authorized ? 'Este sitio no está autorizado para IA. Puedes gestionarlo en ajustes.'
      : !globalOn || !siteOn ? 'Activa la protección de este sitio para analizarlo.'
      : (config.scope === 'public' ? 'IA automática en sitios públicos' : 'IA en sitios elegidos') + '. Con caché y presupuesto compartido; analizar puede consumir cuota.';
    const last = Number(stored.phantom_last_update) || 0;
    const results = Array.isArray(stored.phantom_update_results) ? stored.phantom_update_results : [];
    field('last-update').textContent = results.some(result => !result || !result.ok)
      ? 'Listas: actualización parcial'
      : last ? 'Listas: ' + new Date(last).toLocaleDateString('es', { day: 'numeric', month: 'short' }) : 'Listas incluidas';
    loaded = true;
    controls();
  }
  async function mutate(operation) {
    if (busy || !loaded) return;
    busy = true; controls();
    try { await operation(); await refresh(); }
    catch (error) {
      try { await refresh(); } catch (_) { loaded = false; }
      showMessage(error.userFacing ? error.message : 'No se pudo guardar el cambio. Inténtalo de nuevo.', true);
    } finally { busy = false; controls(); }
  }
  try {
    const tabs = await browser.tabs.query({ active: true, currentWindow: true });
    currentTab = tabs[0] || null;
    if (currentTab && currentTab.url) {
      const url = new URL(currentTab.url);
      if (url.protocol === 'http:' || url.protocol === 'https:') { host = url.hostname.toLowerCase(); domain = normalizeHost(host); }
    }
  } catch (_) { showMessage('No se pudo leer la pestaña. Abre el panel de nuevo desde una web.', true); }
  field('close-panel').addEventListener('click', () => window.close());
  field('configure-ai').addEventListener('click', () => {
    browser.runtime.openOptionsPage().catch(() => showMessage('No se pudieron abrir los ajustes de IA.', true));
  });
  field('exclude-ai').addEventListener('click', () => mutate(async () => {
    if (!host) return;
    const stored = await browser.storage.local.get({ aiConfig: {} });
    const config = globalThis.PhantomAIConfig.validate(Object.assign({}, stored.aiConfig, {
      excludedHosts: [...new Set([...(stored.aiConfig.excludedHosts || []), host])]
    }));
    await browser.storage.local.set({ aiConfig: config });
    showMessage('IA excluida en este sitio y sus subdominios. El bloqueo local continúa.');
  }));
  field('disable-ai').addEventListener('click', () => mutate(async () => {
    const stored = await browser.storage.local.get({ aiConfig: {} });
    await browser.storage.local.set({ aiConfig: Object.assign({}, stored.aiConfig, { enabled: false, consent: false }) });
    showMessage('IA desactivada en todos los sitios. El bloqueo local continúa.');
  }));
  field('scan-ai').addEventListener('click', () => mutate(async () => {
    if (!scanAvailable || !currentTab) return;
    showMessage('Analizando posibles anuncios…');
    let result;
    try { result = await browser.tabs.sendMessage(currentTab.id, { type: 'AI_SCAN' }, { frameId: 0 }); }
    catch (_) { throw userError('Recarga esta página para habilitar el análisis de IA.'); }
    if (!result || !result.ok) throw userError(result && result.error || 'Revisa la configuración y los permisos de IA.');
    showMessage(Number(result.analyzed || 0) + ' candidatos analizados · ' + Number(result.hidden || 0) + ' ocultados.');
  }));
  field('site-toggle').addEventListener('change', () => {
    const enabled = field('site-toggle').checked;
    mutate(async () => {
      if (!domain) return;
      const stored = await browser.storage.local.get({ disabled_domains: [] });
      let list = (Array.isArray(stored.disabled_domains) ? stored.disabled_domains : []).filter(value => typeof value === 'string').map(normalizeHost);
      // The background applies parent-domain exceptions to their subdomains too.
      // Remove the responsible parent when re-enabling, not just an exact hostname.
      list = list.filter(entry => enabled ? !excludedBy(domain, entry) : entry !== domain);
      if (!enabled) list.push(domain);
      await browser.storage.local.set({ disabled_domains: [...new Set(list)] });
      if (currentTab) browser.tabs.reload(currentTab.id).catch(() => {});
      showMessage(enabled ? 'Protección activada. Se recargará la página.' : 'Protección pausada. Se recargará la página.');
    });
  });
  field('adblock-toggle').addEventListener('change', () => {
    const enabled = field('adblock-toggle').checked;
    mutate(async () => {
      await browser.storage.local.set({ adblockEnabled: enabled });
      if (currentTab && domain) browser.tabs.reload(currentTab.id).catch(() => {});
      showMessage(enabled ? 'Bloqueo local activado.' : 'Bloqueo local desactivado.');
    });
  });
  field('privacy-toggle').addEventListener('change', () => {
    const enabled = field('privacy-toggle').checked;
    mutate(async () => { await browser.storage.local.set({ privacyEnabled: enabled }); showMessage('El cambio de Canvas se aplica al recargar la página.'); });
  });
  field('update-lists').addEventListener('click', () => mutate(async () => {
    showMessage('Descargando listas…');
    const results = await browser.runtime.sendMessage({ type: 'FORCE_UPDATE' });
    if (!Array.isArray(results)) throw userError(results && results.error || 'No se pudieron actualizar las listas.');
    const count = results.filter(result => result && result.ok).length;
    showMessage(count + ' de ' + results.length + ' listas actualizadas.', count < results.length);
  }));
  field('reset-stats').addEventListener('click', () => { field('reset-confirm').hidden = false; field('cancel-reset').focus(); });
  field('cancel-reset').addEventListener('click', () => { field('reset-confirm').hidden = true; field('reset-stats').focus(); });
  field('confirm-reset').addEventListener('click', () => mutate(async () => {
    const result = await browser.runtime.sendMessage({ type: 'RESET_STATS' });
    if (!result || !result.ok) throw userError('No se pudieron reiniciar los contadores.');
    field('reset-confirm').hidden = true;
    field('reset-stats').focus();
    showMessage('Contadores reiniciados.');
  }));
  const changed = (values, area) => {
    if (area === 'local' && ['adblockEnabled','privacyEnabled','disabled_domains','aiConfig','blockedCount','phantom_last_update','phantom_update_results'].some(key => key in values)) {
      refresh().catch(() => showMessage('No se pudo actualizar el estado de protección.', true));
    }
  };
  if (browser.storage.onChanged) browser.storage.onChanged.addListener(changed);
  window.addEventListener('pagehide', () => {
    if (browser.storage.onChanged && browser.storage.onChanged.removeListener) browser.storage.onChanged.removeListener(changed);
  }, { once: true });
  try { await refresh(); }
  catch (_) { showMessage('No se pudo conectar con el bloqueador. Abre el panel de nuevo.', true); }
});
