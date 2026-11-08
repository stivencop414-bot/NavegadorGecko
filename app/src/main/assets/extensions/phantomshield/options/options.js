(function() {
  'use strict';
  const field = function(id) { return document.getElementById(id); };
  let saved = Object.assign({}, globalThis.PhantomAIConfig.defaults);
  let nativeConsent = false;
  function status(text, error) {
    field('status').textContent = text;
    field('status').className = error ? 'error' : '';
  }
  async function load() {
    try {
      const stored = await browser.storage.local.get({ aiConfig: saved });
      saved = globalThis.PhantomAIConfig.validate(stored.aiConfig);
      field('enabled').checked = saved.enabled;
      field('consent').checked = saved.consent;
      field('json-mode').checked = saved.jsonMode;
      field('endpoint').value = saved.endpoint;
      field('model').value = saved.model;
      field('hosts').value = saved.allowedHosts.join('\n');
      field('timeout').value = saved.timeoutMs;
      field('api-key').placeholder = saved.apiKey ? 'Guardada; dejar vacío para conservar' : 'Introduce una clave';
      if (browser.permissions && browser.permissions.getAll) {
        const permissions = await browser.permissions.getAll();
        nativeConsent = Array.isArray(permissions.data_collection);
      }
      field('save').disabled = false;
      field('local-only').disabled = false;
    } catch (error) { status('No se pudo cargar la configuración IA.', true); }
  }
  field('ai-form').addEventListener('submit', async function(event) {
    event.preventDefault();
    try {
      const newKey = field('api-key').value.trim();
      const clearKey = field('clear-key').checked;
      const config = globalThis.PhantomAIConfig.validate({
        enabled: field('enabled').checked, consent: field('consent').checked,
        endpoint: field('endpoint').value, model: field('model').value,
        apiKey: clearKey ? '' : (newKey || saved.apiKey),
        allowedHosts: field('hosts').value.split(/\s+/).filter(Boolean),
        timeoutMs: Number(field('timeout').value), jsonMode: field('json-mode').checked
      });
      if (saved.apiKey && config.endpoint !== saved.endpoint && !newKey && !clearKey) {
        throw new Error('Al cambiar la URL de API, introduce una clave nueva o marca borrar clave.');
      }
      field('save').disabled = true;
      if (config.enabled && nativeConsent) {
        const granted = await browser.permissions.request({ data_collection: globalThis.PhantomAIConfig.requiredPermissions(config) });
        if (!granted) throw new Error('Debes autorizar el envío opcional de datos en Firefox para activar IA.');
      }
      await browser.storage.local.set({ aiConfig: config });
      saved = config;
      field('api-key').value = '';
      field('api-key').placeholder = saved.apiKey ? 'Guardada; dejar vacío para conservar' : 'Introduce una clave';
      field('clear-key').checked = false;
      status('Configuración guardada. Se aplica a las páginas abiertas; el bloqueo local continúa activo.');
    } catch (error) { status(error.message || 'No se pudo guardar la configuración.', true); }
    finally { field('save').disabled = false; }
  });
  field('endpoint').addEventListener('input', function() {
    if (field('endpoint').value.trim().replace(/\/+$/, '') !== saved.endpoint) field('consent').checked = false;
  });
  field('local-only').addEventListener('click', async function() {
    field('local-only').disabled = true;
    try {
      saved = Object.assign({}, saved, { enabled: false, consent: false });
      await browser.storage.local.set({ aiConfig: saved });
      field('enabled').checked = false;
      field('consent').checked = false;
      status('IA desactivada. El bloqueo local continúa y se restauran los elementos ocultados por IA.');
    } catch (error) { status('No se pudo guardar la elección. Inténtalo de nuevo.', true); }
    finally { field('local-only').disabled = false; }
  });
  load();
})();
