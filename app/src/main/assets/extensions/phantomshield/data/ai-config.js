/* Shared configuration validation; contains no provider credentials. */
(function() {
  'use strict';
  const defaults = Object.freeze({
    enabled: false, consent: false, endpoint: '', model: '', apiKey: '',
    allowedHosts: [], timeoutMs: 5000, jsonMode: false
  });

  function validate(input) {
    if (!input || typeof input !== 'object') throw new Error('Configuración IA inválida.');
    const config = Object.assign({}, defaults);
    config.enabled = input.enabled === true;
    config.consent = input.consent === true;
    config.jsonMode = input.jsonMode === true;
    config.endpoint = String(input.endpoint || '').trim().replace(/\/+$/, '');
    config.model = String(input.model || '').trim();
    config.apiKey = String(input.apiKey || '').trim();
    config.timeoutMs = input.timeoutMs === undefined ? defaults.timeoutMs : Number(input.timeoutMs);
    if (!Number.isInteger(config.timeoutMs) || config.timeoutMs < 1000 || config.timeoutMs > 15000) {
      throw new Error('El tiempo límite debe estar entre 1000 y 15000 ms.');
    }
    if (config.endpoint) {
      let url;
      try { url = new URL(config.endpoint); } catch (error) { throw new Error('URL de API inválida.'); }
      if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) {
        throw new Error('La API debe usar HTTPS, sin credenciales ni parámetros en la URL.');
      }
      config.endpoint = url.href.replace(/\/+$/, '');
    }
    if (config.model.length > 200 || config.apiKey.length > 4096 || /[\r\n]/.test(config.apiKey)) {
      throw new Error('Modelo o clave API inválidos.');
    }
    if (!Array.isArray(input.allowedHosts || []) || (input.allowedHosts || []).length > 100) {
      throw new Error('Introduce como máximo 100 sitios.');
    }
    config.allowedHosts = Array.from(new Set((input.allowedHosts || []).map(function(value) {
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
    if (config.enabled && (!config.consent || !config.endpoint || !config.model)) {
      throw new Error('Para activar IA debes indicar API y modelo y aceptar el envío de señales.');
    }
    return config;
  }
  function requiredPermissions(config) {
    return ['websiteContent', 'browsingActivity'].concat(config.apiKey ? ['authenticationInfo'] : []);
  }
  globalThis.PhantomAIConfig = { defaults: defaults, validate: validate, requiredPermissions: requiredPermissions };
})();
