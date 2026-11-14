/* Shared configuration validation; contains no provider credentials. */
(function() {
  'use strict';
  const defaults = Object.freeze({
    enabled: false, consent: false, provider: 'custom', endpoint: '', model: '', apiKey: '',
    allowedHosts: [], scope: 'selected', includeSubdomains: false, excludedHosts: [],
    requestsPerMinute: 6, requestsPerHour: 60, timeoutMs: 12000, jsonMode: false,
    // 'basic': solo tipo, señales y tamaño. 'rich': añade un fragmento corto del
    // texto visible del candidato, dominios de sus enlaces y nombres de clase.
    detail: 'basic', rememberDecisions: false,
    // Guardar en el dispositivo los dominios de anuncios que la IA detecta y bloquearlos.
    learnDomains: true,
    // Umbral y alcance del análisis: 'conservative', 'balanced' o 'aggressive'.
    sensitivity: 'balanced',
    // Hasta dos proveedores de respaldo, en orden: si el principal no responde
    // (caído, sin saldo, clave rechazada, respuesta ilegible…) se usa el siguiente.
    backups: []
  });
  const MAX_BACKUPS = 2;

  /*
   * Proveedores. `protocol`: 'claude' (Messages) o 'chat' (Chat Completions,
   * que también hablan Gemini, Groq, DeepSeek, Mistral, xAI y OpenRouter).
   * `minimal`: el proveedor prefiere el cuerpo mínimo (max_completion_tokens).
   * Los modelos son sugerencias de un toque; se puede escribir cualquiera.
   */
  const providers = Object.freeze({
    openai: { label: 'ChatGPT (OpenAI)', protocol: 'chat', minimal: true, endpoint: 'https://api.openai.com/v1', placeholder: 'gpt-4.1-mini',
      models: ['gpt-4.1-mini', 'gpt-4.1-nano', 'gpt-4o-mini'] },
    claude: { label: 'Claude (Anthropic)', protocol: 'claude', endpoint: 'https://api.anthropic.com/v1', placeholder: 'claude-haiku-5-5',
      models: ['claude-haiku-5-5', 'claude-sonnet-5-5', 'claude-opus-5-5'] },
    openrouter: { label: 'OpenRouter', protocol: 'chat', endpoint: 'https://openrouter.ai/api/v1', placeholder: 'openai/gpt-4.1-mini',
      models: ['openai/gpt-4.1-mini', 'google/gemini-2.5-flash', 'anthropic/claude-haiku-5-5', 'meta-llama/llama-3.3-70b-instruct'] },
    gemini: { label: 'Gemini (Google)', protocol: 'chat', endpoint: 'https://generativelanguage.googleapis.com/v1beta/openai', placeholder: 'gemini-2.5-flash',
      models: ['gemini-2.5-flash', 'gemini-2.5-flash-lite', 'gemini-2.5-pro'] },
    groq: { label: 'Groq', protocol: 'chat', endpoint: 'https://api.groq.com/openai/v1', placeholder: 'llama-3.3-70b-versatile',
      models: ['llama-3.3-70b-versatile', 'llama-3.1-8b-instant'] },
    deepseek: { label: 'DeepSeek', protocol: 'chat', endpoint: 'https://api.deepseek.com/v1', placeholder: 'deepseek-chat',
      models: ['deepseek-chat'] },
    mistral: { label: 'Mistral', protocol: 'chat', endpoint: 'https://api.mistral.ai/v1', placeholder: 'mistral-small-latest',
      models: ['mistral-small-latest', 'mistral-medium-latest'] },
    xai: { label: 'xAI (Grok)', protocol: 'chat', endpoint: 'https://api.x.ai/v1', placeholder: 'grok-3-mini',
      models: ['grok-3-mini'] },
    custom: { label: 'Compatible / personalizado', protocol: 'chat', endpoint: '', placeholder: 'Identificador del modelo', models: [] }
  });

  /** Valida una conexión (principal o de respaldo): proveedor, URL HTTPS, modelo y clave. */
  function validateConnection(input, label) {
    const prefix = label ? label + ': ' : '';
    const connection = {};
    connection.provider = input.provider === undefined ? 'custom' : input.provider;
    if (!Object.hasOwn(providers, connection.provider)) throw new Error(prefix + 'Proveedor IA inválido.');
    connection.endpoint = String(input.endpoint || providers[connection.provider].endpoint).trim().replace(/\/+$/, '');
    connection.model = String(input.model || '').trim();
    connection.apiKey = String(input.apiKey || '').trim();
    connection.jsonMode = input.jsonMode === true;
    if (connection.endpoint) {
      let url;
      try { url = new URL(connection.endpoint); } catch (error) { throw new Error(prefix + 'URL de API inválida.'); }
      if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) {
        throw new Error(prefix + 'La API debe usar HTTPS, sin credenciales ni parámetros en la URL.');
      }
      connection.endpoint = url.href.replace(/\/+$/, '');
    }
    if (connection.model.length > 200 || connection.apiKey.length > 4096 || /[\r\n]/.test(connection.apiKey)) {
      throw new Error(prefix + 'Modelo o clave API inválidos.');
    }
    return connection;
  }

  /** Cadena de proveedores en orden de uso: principal y respaldos completos. */
  function chain(config) {
    const list = [];
    if (config && config.endpoint && config.model) {
      list.push({ id: 'primary', label: 'Principal', provider: config.provider, endpoint: config.endpoint, model: config.model,
        apiKey: config.apiKey, jsonMode: config.jsonMode });
    }
    (config && Array.isArray(config.backups) ? config.backups : []).forEach(function(backup, index) {
      if (backup && backup.endpoint && backup.model) {
        list.push(Object.assign({ id: 'backup' + (index + 1), label: 'Respaldo ' + (index + 1) }, backup));
      }
    });
    return list;
  }
  function validate(input) {
    if (!input || typeof input !== 'object') throw new Error('Configuración IA inválida.');
    const config = Object.assign({}, defaults);
    config.provider = input.provider === undefined ? 'custom' : input.provider;
    if (!Object.hasOwn(providers, config.provider)) throw new Error('Proveedor IA inválido.');
    config.enabled = input.enabled === true;
    config.consent = input.consent === true;
    config.jsonMode = input.jsonMode === true;
    config.rememberDecisions = input.rememberDecisions === true;
    config.learnDomains = input.learnDomains !== false;
    config.scope = input.scope === undefined ? 'selected' : input.scope;
    if (!['selected', 'public'].includes(config.scope)) throw new Error('Alcance IA inválido.');
    config.includeSubdomains = input.includeSubdomains === true;
    config.detail = input.detail === undefined ? 'basic' : input.detail;
    if (!['basic', 'rich'].includes(config.detail)) throw new Error('Nivel de detalle IA inválido.');
    config.sensitivity = input.sensitivity === undefined ? 'balanced' : input.sensitivity;
    if (!['conservative', 'balanced', 'aggressive'].includes(config.sensitivity)) throw new Error('Sensibilidad IA inválida.');
    for (const [key, min, max] of [['requestsPerMinute', 1, 12], ['requestsPerHour', 1, 120]]) {
      config[key] = input[key] === undefined ? defaults[key] : Number(input[key]);
      if (!Number.isInteger(config[key]) || config[key] < min || config[key] > max) {
        throw new Error('Presupuesto IA inválido: ' + key);
      }
    }
    Object.assign(config, validateConnection(input, ''));
    config.jsonMode = input.jsonMode === true;
    if (input.backups !== undefined && !Array.isArray(input.backups)) throw new Error('Respaldos IA inválidos.');
    if ((input.backups || []).length > MAX_BACKUPS) throw new Error('Como máximo ' + MAX_BACKUPS + ' modelos de respaldo.');
    config.backups = (input.backups || []).map(function(backup, index) {
      if (!backup || typeof backup !== 'object') throw new Error('Respaldo ' + (index + 1) + ' inválido.');
      const connection = validateConnection(backup, 'Respaldo ' + (index + 1));
      if (!connection.endpoint || !connection.model) throw new Error('Respaldo ' + (index + 1) + ': indica la API y el modelo, o quítalo.');
      return connection;
    });
    config.timeoutMs = input.timeoutMs === undefined ? defaults.timeoutMs : Number(input.timeoutMs);
    if (!Number.isInteger(config.timeoutMs) || config.timeoutMs < 1000 || config.timeoutMs > 30000) {
      throw new Error('El tiempo límite debe estar entre 1000 y 30000 ms.');
    }
    function hosts(values) {
      if (!Array.isArray(values || []) || (values || []).length > 100) {
        throw new Error('Introduce como máximo 100 sitios.');
      }
      return Array.from(new Set((values || []).map(function(value) {
        const host = String(value).trim().toLowerCase();
        let url;
        try { url = new URL('https://' + host); } catch (error) { throw new Error('Sitio autorizado inválido.'); }
        if (!host || !/^[a-z0-9.-]+$/.test(host) || host.split('.').some(label =>
          !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label)) ||
            url.hostname !== host || url.port || url.pathname !== '/' || url.search || url.hash || url.username || url.password) {
          throw new Error('Usa nombres de host exactos, sin rutas, puertos ni comodines.');
        }
        return host;
      })));
    }
    config.allowedHosts = hosts(input.allowedHosts);
    config.excludedHosts = hosts(input.excludedHosts);
    if (config.enabled && (!config.consent || !config.endpoint || !config.model)) {
      throw new Error('Para activar IA debes indicar API y modelo y aceptar el envío de señales.');
    }
    return config;
  }
  // Conservative DNS-name gate. Never send local names or IP literals in automatic mode.
  function publicHost(host) {
    return typeof host === 'string' && host.length <= 253 && host.includes('.') &&
      /^[a-z0-9.-]+$/.test(host) && !/^[0-9.]+$/.test(host) &&
      host.split('.').every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label)) &&
      !/(^|\.)(localhost|local|lan|localdomain|internal|intranet|test|invalid|example|onion|home|arpa)$/.test(host);
  }
  function hostAllowed(config, host) {
    if (!publicHost(host)) return false;
    const matches = domain => host === domain || host.endsWith('.' + domain);
    if ((config.excludedHosts || []).some(matches)) return false;
    return config.scope === 'public' || (config.allowedHosts || []).some(domain =>
      host === domain || (config.includeSubdomains && matches(domain)));
  }
  function requiredPermissions(config) {
    const keyed = Boolean(config.apiKey) || (config.backups || []).some(backup => backup && backup.apiKey);
    return ['websiteContent', 'browsingActivity'].concat(keyed ? ['authenticationInfo'] : []);
  }
  globalThis.PhantomAIConfig = { defaults: defaults, validate: validate, requiredPermissions: requiredPermissions, hostAllowed: hostAllowed, publicHost: publicHost, providers: providers,
    validateConnection: validateConnection, chain: chain, MAX_BACKUPS: MAX_BACKUPS };
})();
