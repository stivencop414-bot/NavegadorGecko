(function() {
  'use strict';
  const field = id => document.getElementById(id);
  let saved = Object.assign({}, globalThis.PhantomAIConfig.defaults);
  let nativeConsent = false;
  let permissionCheckFailed = false;
  let loaded = false;
  let busy = false;
  function status(text, error) {
    field('status').textContent = text;
    field('status').className = error ? 'error' : '';
  }
  function disclosure() {
    const enabled = field('enabled').checked;
    field('ai-fields').hidden = !enabled;
    field('ai-fields').disabled = !enabled;
    field('enabled').setAttribute('aria-expanded', String(enabled));
    field('ai-off-note').hidden = enabled;
    if (!enabled) field('delete-confirm').hidden = true;
  }
  function scopeState() {
    field('selected-sites').hidden = field('scope').value === 'public';
  }
  for (const id of ['scope', 'include-subdomains']) {
    field(id).addEventListener('change', function() {
      field('consent').checked = false;
      scopeState();
    });
  }
  function keyState() {
    field('api-key').value = '';
    field('api-key').type = 'password';
    field('show-key').setAttribute('aria-pressed', 'false');
    field('show-key').setAttribute('aria-label', 'Mostrar clave escrita');
    field('api-key').placeholder = saved.apiKey ? 'Guardada; deja vacío para conservar' : 'Introduce una clave';
    field('key-status').textContent = saved.apiKey
      ? 'Hay una clave guardada en este perfil. No se muestra; escribe otra para reemplazarla.'
      : 'No hay una clave guardada. Solo se enviará a la API que configures.';
    field('delete-key').disabled = busy || !saved.apiKey;
  }
  function controls() {
    field('save').disabled = !loaded || busy;
    field('local-only').disabled = !loaded || busy;
    field('enabled').disabled = !loaded || busy;
    field('delete-key').disabled = !loaded || busy || !saved.apiKey;
    field('confirm-delete').disabled = busy;
    field('ai-fields').disabled = !field('enabled').checked || busy;
  }
  function draftHosts() {
    return field('hosts').value.split(/\s+/).filter(Boolean);
  }
  function renderDomains() {
    field('domain-chips').replaceChildren();
    for (const host of draftHosts()) {
      const chip = document.createElement('span');
      chip.className = 'domain-chip';
      const label = document.createElement('span');
      label.textContent = host;
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.textContent = '×';
      remove.setAttribute('aria-label', 'Quitar ' + host);
      remove.addEventListener('click', function() {
        field('hosts').value = draftHosts().filter(value => value !== host).join('\n');
        renderDomains();
        field('new-domain').focus();
      });
      chip.appendChild(label);
      chip.appendChild(remove);
      field('domain-chips').appendChild(chip);
    }
  }
  function addDomain() {
    const host = field('new-domain').value.trim();
    if (!host) return;
    try {
      const config = globalThis.PhantomAIConfig.validate(Object.assign({}, globalThis.PhantomAIConfig.defaults, {
        allowedHosts: draftHosts().concat([host])
      }));
      field('hosts').value = config.allowedHosts.join('\n');
      field('new-domain').value = '';
      renderDomains();
      status('Sitio añadido al borrador. Guarda para aplicar el cambio.');
    } catch (error) { status(error.message || 'Dominio inválido.', true); }
  }
  function timeoutPresets() {
    for (const seconds of [3, 5, 10]) {
      field('timeout-' + seconds).setAttribute('aria-pressed', String(Number(field('timeout').value) === seconds * 1000));
    }
  }
  field('add-domain').addEventListener('click', addDomain);
  field('new-domain').addEventListener('keydown', function(event) {
    if (event.key === 'Enter') { event.preventDefault(); addDomain(); }
  });
  for (const seconds of [3, 5, 10]) {
    field('timeout-' + seconds).addEventListener('click', function() {
      field('timeout').value = seconds * 1000;
      timeoutPresets();
    });
  }
  field('timeout').addEventListener('input', timeoutPresets);
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
      field('scope').value = saved.scope;
      field('include-subdomains').checked = saved.includeSubdomains;
      field('excluded-hosts').value = saved.excludedHosts.join('\n');
      field('requests-minute').value = saved.requestsPerMinute;
      field('requests-hour').value = saved.requestsPerHour;
      scopeState();
      field('timeout').value = saved.timeoutMs;
      renderDomains();
      timeoutPresets();
      if (browser.permissions && browser.permissions.getAll) {
        try {
          const permissions = await browser.permissions.getAll();
          nativeConsent = Array.isArray(permissions.data_collection);
        } catch (_) { permissionCheckFailed = true; }
      }
      loaded = true;
      keyState(); disclosure(); controls();
    } catch (_) { status('No se pudo cargar la configuración IA. Recarga para intentarlo de nuevo.', true); }
  }
  field('enabled').addEventListener('change', function() {
    if (!field('enabled').checked) field('consent').checked = false;
    disclosure();
  });
  field('show-key').addEventListener('click', function() {
    const reveal = field('api-key').type === 'password';
    field('api-key').type = reveal ? 'text' : 'password';
    field('show-key').setAttribute('aria-pressed', String(reveal));
    field('show-key').setAttribute('aria-label', reveal ? 'Ocultar clave escrita' : 'Mostrar clave escrita');
  });
  field('delete-key').addEventListener('click', function() {
    field('delete-confirm').hidden = false;
    field('cancel-delete').focus();
  });
  field('cancel-delete').addEventListener('click', function() {
    field('delete-confirm').hidden = true;
    field('delete-key').focus();
  });
  field('confirm-delete').addEventListener('click', async function() {
    if (busy || !loaded || !saved.apiKey) return;
    busy = true; controls();
    try {
      // Preserve other saved values even if the form contains an unsaved draft.
      const stored = await browser.storage.local.get({ aiConfig: saved });
      const next = globalThis.PhantomAIConfig.validate(Object.assign({}, stored.aiConfig, { apiKey: '', enabled: false, consent: false }));
      await browser.storage.local.set({ aiConfig: next });
      saved = next;
      field('enabled').checked = false;
      field('consent').checked = false;
      field('delete-confirm').hidden = true;
      keyState(); disclosure();
      field('enabled').focus();
      status('Clave borrada e IA desactivada. El bloqueo local sigue funcionando.');
    } catch (_) { status('No se pudo borrar la clave. No se ha cambiado tu configuración.', true); }
    finally { busy = false; controls(); }
  });
  field('ai-form').addEventListener('submit', async function(event) {
    event.preventDefault();
    if (!loaded || busy) return;
    busy = true; controls();
    try {
      const enabled = field('enabled').checked;
      const newKey = field('api-key').value.trim();
      // Turning AI off must work even when hidden draft fields are incomplete.
      const config = enabled ? globalThis.PhantomAIConfig.validate({
        enabled: true, consent: field('consent').checked,
        endpoint: field('endpoint').value, model: field('model').value,
        apiKey: newKey || saved.apiKey,
        allowedHosts: field('hosts').value.split(/\s+/).filter(Boolean),
        scope: field('scope').value,
        includeSubdomains: field('include-subdomains').checked,
        excludedHosts: field('excluded-hosts').value.split(/\s+/).filter(Boolean),
        requestsPerMinute: Number(field('requests-minute').value),
        requestsPerHour: Number(field('requests-hour').value),
        timeoutMs: Number(field('timeout').value), jsonMode: field('json-mode').checked
      }) : Object.assign({}, saved, { enabled: false, consent: false });
      if (enabled && saved.apiKey && config.endpoint !== saved.endpoint && !newKey) {
        throw new Error('Al cambiar la dirección de la API, introduce una clave nueva o borra la guardada.');
      }
      if (enabled && permissionCheckFailed) throw new Error('No se pudieron comprobar los permisos de datos. Recarga antes de activar IA.');
      if (enabled && nativeConsent) {
        const granted = await browser.permissions.request({ data_collection: globalThis.PhantomAIConfig.requiredPermissions(config) });
        if (!granted) throw new Error('Autoriza el envío opcional de datos en el navegador para activar IA.');
      }
      await browser.storage.local.set({ aiConfig: config });
      saved = config;
      keyState(); disclosure();
      status(enabled ? 'Configuración guardada. IA ' + (config.scope === 'public' ? 'automática en sitios públicos' : 'en sitios elegidos') + ', con exclusiones y presupuesto.' : 'Guardado: solo bloqueo local, sin llamadas a IA.');
    } catch (error) { status(error.message || 'No se pudo guardar la configuración.', true); }
    finally { busy = false; controls(); }
  });
  field('endpoint').addEventListener('input', function() {
    if (field('endpoint').value.trim().replace(/\/+$/, '') !== saved.endpoint) field('consent').checked = false;
  });
  field('local-only').addEventListener('click', async function() {
    if (!loaded || busy) return;
    busy = true; controls();
    try {
      const next = Object.assign({}, saved, { enabled: false, consent: false });
      await browser.storage.local.set({ aiConfig: next });
      saved = next;
      field('enabled').checked = false;
      field('consent').checked = false;
      disclosure();
      status('IA desactivada. El bloqueo local continúa y se restaura lo ocultado por IA.');
    } catch (_) { status('No se pudo guardar la elección. Inténtalo de nuevo.', true); }
    finally { busy = false; controls(); }
  });
  load();
})();
