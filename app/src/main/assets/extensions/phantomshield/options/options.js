(function() {
  'use strict';
  const field = id => document.getElementById(id);
  if(typeof ResizeObserver==='function')new ResizeObserver(()=>{
    document.documentElement.style.setProperty('--action-space',(document.querySelector('.action-bar').getBoundingClientRect().height+24)+'px');
  }).observe(document.querySelector('.action-bar'));
  let saved = Object.assign({}, globalThis.PhantomAIConfig.defaults);
  let nativeConsent = false;
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
  function detailState() {
    field('consent-detail').hidden = field('detail').value !== 'rich';
  }
  // Cambiar qué sitios o qué datos se envían exige volver a aceptar.
  for (const id of ['scope', 'include-subdomains', 'detail']) {
    field(id).addEventListener('change', function() {
      field('consent').checked = false;
      scopeState();
      detailState();
    });
  }
  function providerState(reveal = false) {
    const provider = globalThis.PhantomAIConfig.providers[field('provider').value];
    field('model').placeholder = provider.placeholder;
    field('json-mode-control').hidden = field('provider').value === 'claude';
    field('provider-advanced').open = reveal && field('provider').value === 'custom';
    // Modelos sugeridos: un toque evita errores al escribir el identificador.
    field('model-options').replaceChildren();
    field('model-chips').replaceChildren();
    for (const model of provider.models || []) {
      const option = document.createElement('option');
      option.value = model;
      field('model-options').appendChild(option);
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'model-chip';
      chip.textContent = model;
      chip.addEventListener('click', function() {
        field('model').value = model;
        field('test-result').textContent = 'Modelo elegido. Pulsa «Probar conexión» para comprobarlo.';
        field('test-result').className = 'test-result';
      });
      field('model-chips').appendChild(chip);
    }
  }
  field('provider').addEventListener('change', function() {
    field('endpoint').value = globalThis.PhantomAIConfig.providers[field('provider').value].endpoint;
    field('model').value = '';
    field('consent').checked = false;
    providerState(true);
  });
  async function showUsage() {
    const stored = await browser.storage.local.get({ aiStats: {} });
    const stats = stored.aiStats || {};
    const number = key => Number.isSafeInteger(stats[key]) && stats[key] >= 0 ? stats[key] : 0;
    const tokens = number('inputTokens') + number('outputTokens');
    field('usage-tokens').textContent = tokens === 0 && number('requests') && number('usageMissing') === number('requests') ? '—' : tokens.toLocaleString('es');
    field('usage-detected').textContent = number('detected').toLocaleString('es');
    field('usage-detail').textContent = number('requests').toLocaleString('es') + ' solicitudes · ' +
      number('analyzed').toLocaleString('es') + ' analizados · ' + number('inputTokens').toLocaleString('es') + ' entrada / ' +
      number('outputTokens').toLocaleString('es') + ' salida';
  }
  const STATE_TEXT = {
    ok: 'Última llamada correcta',
    auth: 'La API rechazó la clave',
    model: 'Modelo o dirección no encontrados',
    request: 'La API rechazó la solicitud',
    rate: 'Límite del proveedor o sin saldo',
    server: 'Fallo temporal del proveedor',
    timeout: 'Tiempo límite agotado',
    network: 'Sin conexión con el proveedor',
    format: 'Respuesta ilegible',
    refusal: 'El modelo declinó responder',
    unavailable: 'Ningún proveedor responde ahora'
  };
  async function showState() {
    const stored = await browser.storage.local.get({ aiStatus: null });
    const state = stored.aiStatus;
    const node = field('ai-state');
    if (!state || !STATE_TEXT[state.state]) { node.textContent = ''; node.className = 'ai-state'; return; }
    const when = Number.isFinite(state.at) ? new Date(state.at).toLocaleString('es', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '';
    node.textContent = (state.state === 'ok' ? '✓ ' : '✕ ') + STATE_TEXT[state.state] +
      (state.http ? ' (HTTP ' + state.http + ')' : '') +
      (state.label && state.via !== 'primary' ? ' · vía ' + state.label : '') + (when ? ' · ' + when : '');
    node.className = 'ai-state ' + (state.state === 'ok' ? 'good' : 'bad');
  }
  if (browser.storage.onChanged) browser.storage.onChanged.addListener(function(changes, area) {
    if(area==='local' && changes.aiConfig && changes.aiConfig.newValue) {
      const next=changes.aiConfig.newValue;
      if(next.excludedHosts) { saved.excludedHosts=next.excludedHosts;field('excluded-hosts').value=next.excludedHosts.join('\n'); }
    }
    if (area === 'local' && changes.aiStats) showUsage().catch(function() {});
    if (area === 'local' && changes.aiStatus) showState().catch(function() {});
    if (area === 'local' && changes.aiProviders) showProviders().catch(function() {});
  });
  showUsage().catch(function() {});
  showState().catch(function() {});
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
    field('remember-decisions').disabled = !loaded || busy;
    field('learn-domains').disabled = !loaded || busy;
    field('local-only').disabled = !loaded || busy;
    field('enabled').disabled = !loaded || busy;
    field('delete-key').disabled = !loaded || busy || !saved.apiKey;
    field('confirm-delete').disabled = busy;
    field('test-ai').disabled = !loaded || busy;
    field('add-backup').disabled = !loaded || busy;
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
  const TIMEOUT_PRESETS = [8, 12, 20];
  function timeoutPresets() {
    for (const seconds of TIMEOUT_PRESETS) {
      field('timeout-' + seconds).setAttribute('aria-pressed', String(Number(field('timeout').value) === seconds * 1000));
    }
  }
  field('add-domain').addEventListener('click', addDomain);
  field('new-domain').addEventListener('keydown', function(event) {
    if (event.key === 'Enter') { event.preventDefault(); addDomain(); }
  });
  for (const seconds of TIMEOUT_PRESETS) {
    field('timeout-' + seconds).addEventListener('click', function() {
      field('timeout').value = seconds * 1000;
      timeoutPresets();
    });
  }
  field('timeout').addEventListener('input', timeoutPresets);

  // ---------------------------------------------------------------
  // Modelos de respaldo: tarjetas creadas aquí (hasta dos), cada una con su
  // proveedor, dirección, modelo y clave. La clave guardada nunca se muestra.
  // ---------------------------------------------------------------
  const PROVIDERS = globalThis.PhantomAIConfig.providers;
  const MAX_BACKUPS = globalThis.PhantomAIConfig.MAX_BACKUPS || 2;
  let backups = [];
  function element(tag, props, children) {
    const node = document.createElement(tag);
    Object.assign(node, props || {});
    for (const child of children || []) node.appendChild(child);
    return node;
  }
  function backupState(draft) {
    const preset = PROVIDERS[draft.provider.value] || PROVIDERS.custom;
    draft.model.placeholder = preset.placeholder;
    draft.jsonRow.hidden = preset.protocol === 'claude';
    draft.endpointRow.hidden = draft.provider.value !== 'custom' && draft.endpoint.value === preset.endpoint;
    draft.chips.replaceChildren && draft.chips.replaceChildren();
    if (!draft.chips.replaceChildren) draft.chips.children = [];
    for (const model of preset.models || []) {
      const chip = element('button', { type: 'button', className: 'model-chip', textContent: model });
      chip.addEventListener('click', function() { draft.model.value = model; });
      draft.chips.appendChild(chip);
    }
    draft.key.placeholder = draft.savedKey ? 'Guardada; deja vacío para conservar' : 'Clave de este proveedor (opcional con proxy)';
  }
  function renderBackups() {
    field('backups').replaceChildren();
    backups.forEach(function(draft, index) {
      draft.title.textContent = 'Respaldo ' + (index + 1);
      field('backups').appendChild(draft.root);
    });
    field('add-backup').hidden = backups.length >= MAX_BACKUPS;
    field('add-backup').disabled = busy || !loaded;
  }
  function addBackup(saved) {
    if (backups.length >= MAX_BACKUPS) return null;
    const draft = { savedKey: saved && saved.apiKey || '', savedEndpoint: saved && saved.endpoint || '' };
    draft.title = element('span', { textContent: 'Respaldo' });
    draft.provider = element('select', { className: 'backup-provider' });
    for (const [id, preset] of Object.entries(PROVIDERS)) {
      draft.provider.appendChild(element('option', { value: id, textContent: preset.label }));
    }
    draft.provider.value = saved && saved.provider || 'openrouter';
    draft.endpoint = element('input', { type: 'url', placeholder: 'https://tu-api.example/v1', autocomplete: 'off',
      value: saved && saved.endpoint || (PROVIDERS[draft.provider.value] || {}).endpoint || '' });
    draft.endpointRow = element('div', {}, [element('label', { textContent: 'URL base de la API' }), draft.endpoint]);
    draft.model = element('input', { type: 'text', maxLength: 200, autocomplete: 'off', spellcheck: false, value: saved && saved.model || '' });
    draft.chips = element('div', { className: 'model-chips' });
    draft.key = element('input', { type: 'password', maxLength: 4096, autocomplete: 'new-password', value: '' });
    draft.json = element('input', { type: 'checkbox', checked: Boolean(saved && saved.jsonMode) });
    draft.jsonRow = element('label', { className: 'check-row' }, [draft.json, element('span', { textContent: ' Respuesta JSON estructurada (response_format)' })]);
    draft.status = element('p', { className: 'test-result', textContent: '' });
    const test = element('button', { type: 'button', className: 'secondary', textContent: 'Probar' });
    const remove = element('button', { type: 'button', className: 'secondary', textContent: 'Quitar' });
    draft.title.setAttribute && draft.title.setAttribute('role', 'heading');
    draft.root = element('article', { className: 'backup-item' }, [
      element('h3', {}, [draft.title]),
      element('label', { textContent: 'Proveedor' }), draft.provider,
      draft.endpointRow,
      element('label', { textContent: 'Modelo' }), draft.model, draft.chips,
      element('label', { textContent: 'Clave API' }), draft.key,
      draft.jsonRow,
      element('div', { className: 'backup-actions' }, [test, remove]),
      draft.status
    ]);
    draft.provider.addEventListener('change', function() {
      draft.endpoint.value = (PROVIDERS[draft.provider.value] || {}).endpoint || '';
      draft.model.value = '';
      field('consent').checked = false;
      backupState(draft);
    });
    draft.endpoint.addEventListener('input', function() { field('consent').checked = false; });
    remove.addEventListener('click', function() {
      backups = backups.filter(item => item !== draft);
      field('consent').checked = false;
      renderBackups();
      status('Respaldo quitado del borrador. Guarda para aplicar el cambio.');
    });
    test.addEventListener('click', function() { testConnection(draft); });
    backups.push(draft);
    backupState(draft);
    renderBackups();
    return draft;
  }
  /** Respaldos del formulario, validados; una clave guardada solo vale para su misma dirección. */
  function draftBackups() {
    return backups.map(function(draft, index) {
      const endpoint = String(draft.endpoint.value || '').trim().replace(/\/+$/, '');
      const newKey = String(draft.key.value || '').trim();
      if (draft.savedKey && endpoint !== draft.savedEndpoint && !newKey) {
        throw new Error('Respaldo ' + (index + 1) + ': al cambiar la dirección de la API, introduce una clave nueva o quita el respaldo.');
      }
      return globalThis.PhantomAIConfig.validateConnection({
        provider: draft.provider.value, endpoint: endpoint, model: draft.model.value,
        apiKey: newKey || (endpoint === draft.savedEndpoint ? draft.savedKey : ''), jsonMode: draft.json.checked
      }, 'Respaldo ' + (index + 1));
    });
  }
  field('add-backup').addEventListener('click', function() {
    const draft = addBackup(null);
    if (draft) {
      field('consent').checked = false;
      status('Respaldo añadido al borrador. Elige proveedor y modelo, pruébalo y guarda.');
    }
  });

  const PROVIDER_STATE = {
    ok: 'responde', auth: 'clave rechazada', model: 'modelo no encontrado', request: 'solicitud rechazada',
    rate: 'límite o sin saldo', server: 'fallo del proveedor', timeout: 'tiempo agotado', network: 'sin conexión',
    format: 'respuesta ilegible', refusal: 'declinó responder'
  };
  /** Último estado conocido de cada proveedor de la cadena. */
  async function showProviders() {
    const stored = await browser.storage.local.get({ aiProviders: {} });
    const map = stored.aiProviders && typeof stored.aiProviders === 'object' ? stored.aiProviders : {};
    const box = field('provider-health');
    box.replaceChildren();
    const order = [['primary', 'Principal'], ['backup1', 'Respaldo 1'], ['backup2', 'Respaldo 2']];
    for (const [id, label] of order) {
      const state = map[id];
      if (!state || !PROVIDER_STATE[state.state]) continue;
      const when = Number.isFinite(state.at) ? new Date(state.at).toLocaleString('es', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '';
      box.appendChild(element('p', {
        className: state.state === 'ok' ? 'good' : 'bad',
        textContent: (state.state === 'ok' ? '✓ ' : '✕ ') + label + (state.model ? ' (' + state.model + ')' : '') + ': ' +
          PROVIDER_STATE[state.state] + (state.http ? ' · HTTP ' + state.http : '') +
          (state.state === 'ok' && state.ms ? ' · ' + (state.ms / 1000).toFixed(1) + ' s' : '') + (when ? ' · ' + when : '')
      }));
    }
  }
  showProviders().catch(function() {});

  async function load() {
    try {
      const stored = await browser.storage.local.get({ aiConfig: saved });
      saved = globalThis.PhantomAIConfig.validate(stored.aiConfig);
      field('enabled').checked = saved.enabled;
      field('consent').checked = saved.consent;
      field('json-mode').checked = saved.jsonMode;
      field('remember-decisions').checked = saved.rememberDecisions;
      field('learn-domains').checked = saved.learnDomains !== false;
      field('endpoint').value = saved.endpoint;
      field('provider').value = saved.provider;
      providerState();
      field('model').value = saved.model;
      field('hosts').value = saved.allowedHosts.join('\n');
      field('scope').value = saved.scope;
      // Sin configuración previa se propone el análisis detallado; una ya
      // guardada conserva exactamente lo que el usuario aceptó.
      field('detail').value = saved.model ? saved.detail : 'rich';
      field('sensitivity').value = saved.sensitivity || 'balanced';
      detailState();
      field('include-subdomains').checked = saved.includeSubdomains;
      field('excluded-hosts').value = saved.excludedHosts.join('\n');
      field('requests-minute').value = saved.requestsPerMinute;
      field('requests-hour').value = saved.requestsPerHour;
      scopeState();
      field('timeout').value = saved.timeoutMs;
      backups = [];
      for (const backup of saved.backups || []) addBackup(backup);
      renderBackups();
      renderDomains();
      timeoutPresets();
      if (browser.permissions && browser.permissions.getAll) {
        try {
          const permissions = await browser.permissions.getAll();
          nativeConsent = Array.isArray(permissions.data_collection);
        } catch (_) { nativeConsent = false; }
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
        enabled: true, consent: field('consent').checked, provider: field('provider').value,
        endpoint: field('endpoint').value, model: field('model').value,
        apiKey: newKey || saved.apiKey,
        allowedHosts: field('hosts').value.split(/\s+/).filter(Boolean),
        scope: field('scope').value,
        detail: field('detail').value,
        sensitivity: field('sensitivity').value,
        rememberDecisions: field('remember-decisions').checked,
        learnDomains: field('learn-domains').checked,
        includeSubdomains: field('include-subdomains').checked,
        excludedHosts: field('excluded-hosts').value.split(/\s+/).filter(Boolean),
        requestsPerMinute: Number(field('requests-minute').value),
        requestsPerHour: Number(field('requests-hour').value),
        timeoutMs: Number(field('timeout').value), jsonMode: field('json-mode').checked,
        backups: draftBackups()
      }) : Object.assign({}, saved, { enabled: false, consent: false, rememberDecisions: field('remember-decisions').checked,
        learnDomains: field('learn-domains').checked });
      if (enabled && saved.apiKey && config.endpoint !== saved.endpoint && !newKey) {
        throw new Error('Al cambiar la dirección de la API, introduce una clave nueva o borra la guardada.');
      }
      if (enabled && nativeConsent) {
        // Aviso nativo del navegador. Un «no» explícito se respeta; si el
        // navegador no admite esta petición (lanza un error), vale el
        // consentimiento marcado en este formulario: antes ese fallo impedía
        // activar la IA sin explicar por qué.
        let granted = true;
        try {
          granted = await browser.permissions.request({ data_collection: globalThis.PhantomAIConfig.requiredPermissions(config) });
        } catch (_) { granted = true; }
        if (granted === false) throw new Error('Autoriza el envío opcional de datos en el navegador para activar IA.');
      }
      await browser.storage.local.set({ aiConfig: config });
      saved = config;
      backups = [];
      for (const backup of saved.backups || []) addBackup(backup);
      renderBackups();
      keyState(); disclosure();
      status(enabled ? 'Configuración guardada. IA ' + (config.scope === 'public' ? 'automática en sitios públicos' : 'en sitios elegidos') + ', con exclusiones y presupuesto' +
        (config.backups.length ? ' y ' + config.backups.length + (config.backups.length === 1 ? ' respaldo.' : ' respaldos.') : '.') : 'Guardado: solo bloqueo local, sin llamadas a IA.');
    } catch (error) { status(error.message || 'No se pudo guardar la configuración.', true); }
    finally { busy = false; controls(); }
  });
  /** Prueba el principal (draft null) o un respaldo con lo escrito en el formulario. */
  async function testConnection(draft) {
    if (!loaded || busy) return;
    const result = draft ? draft.status : field('test-result');
    busy = true; controls();
    result.className = 'test-result';
    result.textContent = 'Probando la conexión con el proveedor…';
    try {
      const index = draft ? backups.indexOf(draft) : -1;
      // Se prueba lo que hay escrito en el formulario, aunque no esté guardado.
      const config = draft ? {
        slot: 'backup' + (index + 1), provider: draft.provider.value, endpoint: draft.endpoint.value, model: draft.model.value,
        apiKey: String(draft.key.value || '').trim(), timeoutMs: Number(field('timeout').value) || 12000,
        jsonMode: draft.json.checked, detail: 'rich'
      } : {
        slot: 'primary', provider: field('provider').value, endpoint: field('endpoint').value, model: field('model').value,
        apiKey: field('api-key').value.trim(), timeoutMs: Number(field('timeout').value) || 12000,
        jsonMode: field('json-mode').checked, detail: 'rich'
      };
      const reply = await browser.runtime.sendMessage({ type: 'AI_TEST', config: config });
      if (reply && reply.ok) {
        result.className = 'test-result good';
        result.textContent = '✓ Conexión correcta en ' + (Number(reply.ms) / 1000).toFixed(1) + ' s. ' +
          (reply.detected && reply.kept ? 'El modelo distinguió el anuncio de prueba del artículo.'
            : reply.detected ? 'Detectó el anuncio de prueba, pero también marcó el artículo: considera un modelo más capaz.'
            : 'Respondió, pero no reconoció el anuncio de prueba: considera un modelo más capaz.');
      } else {
        result.className = 'test-result bad';
        result.textContent = '✕ ' + (reply && reply.error || 'No se pudo completar la prueba.') +
          (reply && reply.http ? ' (HTTP ' + reply.http + ')' : '');
      }
    } catch (_) {
      result.className = 'test-result bad';
      result.textContent = '✕ No se pudo contactar con el bloqueador. Recarga esta página.';
    } finally { busy = false; controls(); }
  }
  field('test-ai').addEventListener('click', function() { return testConnection(null); });
  field('endpoint').addEventListener('input', function() {
    if (field('endpoint').value.trim().replace(/\/+$/, '') !== saved.endpoint) field('consent').checked = false;
  });
  field('local-only').addEventListener('click', async function() {
    if (!loaded || busy) return;
    busy = true; controls();
    try {
      const next = Object.assign({}, saved, { enabled: false, consent: false, rememberDecisions: field('remember-decisions').checked,
        learnDomains: field('learn-domains').checked });
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
