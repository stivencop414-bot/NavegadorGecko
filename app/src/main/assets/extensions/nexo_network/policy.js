/* Pure policy: never consult page URLs or accept an alternative direct route. */
(function() {
  'use strict';
  function validate(value) {
    const p = value || {};
    if (!['direct', 'socks', 'http'].includes(p.type)) throw Error('Tipo de ruta no admitido.');
    const host = String(p.host || '').trim();
    const port = Number(p.port || 0);
    if (p.type !== 'direct' && (!host || host.length > 253 || !/^[a-zA-Z0-9.:[\]-]+$/.test(host) || !Number.isInteger(port) || port < 1 || port > 65535)) throw Error('Host o puerto inválido.');
    const latency = Number(p.latency || 0);
    if (!Number.isInteger(latency) || latency < 0 || latency > 2000) throw Error('Retardo inválido.');
    let geo = null;
    if (p.geo) {
      const lat = Number(p.geo.lat), lon = Number(p.geo.lon);
      if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) throw Error('Ubicación inválida.');
      geo = {lat, lon};
    }
    return {type:p.type, host, port, latency, geo, burner: p.burner === true, seed: Number(p.seed) >>> 0};
  }
  function proxy(p) {
    return p.type === 'direct' ? undefined : [{type:p.type, host:p.host, port:p.port, proxyDNS:p.type === 'socks', failoverTimeout:1}, null];
  }
  globalThis.NexoRoutePolicy = {validate, proxy};
})();
