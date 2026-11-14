/*
 * Dominios publicitarios aprendidos con la IA. Todo queda en este dispositivo.
 *
 * Cuando la IA (o una regla local) oculta un anuncio, se anotan los dominios
 * de su *infraestructura*: el marco incrustado y los enlaces de clic o
 * seguimiento (no la tienda anunciada). Un dominio pasa de "observando" a
 * "bloqueado" cuando aparece en anuncios de al menos dos sitios distintos, o
 * cuatro veces en uno. Desde entonces se cortan sus peticiones de terceros y
 * las ventanas que una página intente abrir hacia él; las páginas que el
 * usuario abre a propósito nunca se cancelan.
 *
 * No se aprenden: el propio sitio, servicios comunes (Google, CDN, redes
 * sociales, pagos…), dominios que las listas ya bloquean ni los que el
 * usuario marcó "Nunca bloquear". De cada sitio solo se guarda una huella
 * con sal (para contar sitios distintos), nunca su nombre.
 */
(function() {
  'use strict';

  const KEY = 'aiAdDomains';
  const LIMIT = 400;
  const MAX_ALLOW = 200;
  const PROMOTE_SITES = 2;
  const PROMOTE_HITS = 4;
  const MAX_SITES = 8;
  const DAY = 86400000;
  const TTL_WATCH = 30 * DAY;
  const TTL_BLOCKED = 90 * DAY;

  // Alojamientos compartidos: se aprende el host exacto, no todo el dominio.
  const SHARED_HOSTING = /(?:^|\.)(?:cloudfront\.net|amazonaws\.com|azureedge\.net|azurewebsites\.net|akamaized\.net|akamaihd\.net|fastly\.net|b-cdn\.net|herokuapp\.com|github\.io|netlify\.app|vercel\.app|pages\.dev|workers\.dev|web\.app|firebaseapp\.com|appspot\.com|blogspot\.com|wordpress\.com|wixsite\.com)$/;

  // Servicios que aparecen en anuncios pero cuyo bloqueo rompería sitios.
  const SAFE = new Set([
    'google.com', 'googleapis.com', 'gstatic.com', 'youtube.com', 'ytimg.com', 'googlevideo.com', 'youtu.be',
    'facebook.com', 'fbcdn.net', 'instagram.com', 'whatsapp.com', 'twitter.com', 'x.com', 'twimg.com',
    'apple.com', 'icloud.com', 'microsoft.com', 'live.com', 'bing.com', 'msn.com', 'yahoo.com', 'duckduckgo.com',
    'wikipedia.org', 'wikimedia.org', 'amazon.com', 'amazon.es', 'media-amazon.com', 'cloudflare.com', 'jsdelivr.net',
    'unpkg.com', 'jquery.com', 'bootstrapcdn.com', 'recaptcha.net', 'hcaptcha.com', 'gravatar.com', 'wp.com',
    'disqus.com', 'vimeo.com', 'dailymotion.com', 'tiktok.com', 'linkedin.com', 'reddit.com', 'redd.it',
    'pinterest.com', 'paypal.com', 'stripe.com', 'mercadolibre.com', 'mercadopago.com', 'spotify.com',
    'twitch.tv', 'github.com', 'githubusercontent.com', 'gitlab.com', 'telegram.org', 't.me', 'wa.me',
    'discord.com', 'typekit.net', 'adobe.com', 'shopify.com', 'shopifycdn.com', 'cdnjs.com', 'bit.ly'
  ]);

  // Enlace de clic o seguimiento publicitario (redirige al anunciante).
  const CLICK = /(?:[?&](?:ad)?url=|[?&]u=https?|[?&](?:redirect|redir|dest|destination|landing|clickurl|click_url|lp)=|\/(?:click|clk|aclk|clicks|track|tracking|redirect|out|go)(?:[/?.]|$)|[?&](?:campaign_?id|creative_?id|crid|adid|ad_id|zoneid|zone_id)=)/i;

  const TWO_PART_SUFFIX = /\.(?:com?|org|net|gov|edu|ac|gob)\.[a-z]{2}$/;

  function siteOf(host) {
    const parts = String(host || '').toLowerCase().split('.').filter(Boolean);
    return parts.slice(TWO_PART_SUFFIX.test(host) ? -3 : -2).join('.');
  }

  function validHost(host) {
    return typeof host === 'string' && host.length <= 253 && host.includes('.') && !/^[0-9.]+$/.test(host) &&
      host.split('.').every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label));
  }

  function hostOf(url) {
    try {
      const parsed = new URL(url);
      return /^https?:$/.test(parsed.protocol) ? parsed.hostname.toLowerCase().replace(/\.$/, '') : '';
    } catch (error) { return ''; }
  }

  /** Dominio a recordar: el sitio registrable, salvo en alojamientos compartidos. */
  function domainFor(host) {
    return SHARED_HOSTING.test(host) ? host : siteOf(host);
  }

  function isSafe(domain) {
    const site = siteOf(domain);
    return SAFE.has(domain) || SAFE.has(site) || /(?:^|\.)google\.[a-z.]+$/.test(domain);
  }

  /** Huella corta con sal (FNV-1a doble): sirve para contar sitios, no para revelarlos. */
  function fingerprint(salt, value) {
    let a = 0x811c9dc5, b = 0x01000193 ^ 0x5bd1e995;
    const text = salt + '|' + value;
    for (let i = 0; i < text.length; i++) {
      const code = text.charCodeAt(i);
      a = Math.imul(a ^ code, 0x01000193) >>> 0;
      b = Math.imul(b ^ code, 0x5bd1e995) >>> 0;
    }
    return a.toString(16).padStart(8, '0') + b.toString(16).padStart(8, '0').slice(0, 4);
  }

  function randomSalt() {
    try {
      const values = new Uint32Array(2);
      globalThis.crypto.getRandomValues(values);
      return values[0].toString(36) + values[1].toString(36);
    } catch (error) {
      return Math.random().toString(36).slice(2) + Date.now().toString(36);
    }
  }

  class AdDomainMemory {
    constructor() {
      this.entries = new Map();   // dominio -> { domain, sites, hits, first, last, state }
      this.allowed = new Set();   // "Nunca bloquear"
      this.blocked = new Set();
      this.salt = '';
      this.saveTimer = null;
      this.ready = browser.storage.local.get({ aiAdDomains: null }).then(stored => {
        const data = stored.aiAdDomains && typeof stored.aiAdDomains === 'object' ? stored.aiAdDomains : {};
        this.salt = typeof data.salt === 'string' && /^[a-z0-9]{6,40}$/.test(data.salt) ? data.salt : randomSalt();
        for (const raw of Array.isArray(data.entries) ? data.entries.slice(0, LIMIT * 2) : []) {
          const entry = this.clean(raw);
          if (entry) this.entries.set(entry.domain, entry);
        }
        for (const domain of Array.isArray(data.allowed) ? data.allowed.slice(0, MAX_ALLOW) : []) {
          if (validHost(domain)) this.allowed.add(domain);
        }
        this.prune();
      }).catch(() => { this.salt = this.salt || randomSalt(); });
    }

    clean(raw) {
      if (!raw || !validHost(raw.domain) || !Number.isFinite(raw.first) || !Number.isFinite(raw.last)) return null;
      const sites = Array.isArray(raw.sites) ? raw.sites.filter(site => /^[a-f0-9]{12}$/.test(site)).slice(0, MAX_SITES) : [];
      const hits = Number.isSafeInteger(raw.hits) && raw.hits >= 0 ? raw.hits : sites.length;
      return { domain: raw.domain, sites: sites, hits: hits, first: raw.first, last: raw.last,
        state: raw.state === 'blocked' ? 'blocked' : 'watch' };
    }

    /** Caducidad, tope de tamaño y conjunto de bloqueados. */
    prune() {
      const now = Date.now();
      for (const [domain, entry] of this.entries) {
        const ttl = entry.state === 'blocked' ? TTL_BLOCKED : TTL_WATCH;
        if (now - entry.last > ttl || entry.last > now + DAY || this.allowed.has(domain)) this.entries.delete(domain);
      }
      if (this.entries.size > LIMIT) {
        // Se conservan primero los bloqueados y los vistos más recientemente.
        const ordered = [...this.entries.values()].sort((a, b) =>
          (b.state === 'blocked') - (a.state === 'blocked') || b.last - a.last);
        this.entries = new Map(ordered.slice(0, LIMIT).map(entry => [entry.domain, entry]));
      }
      this.blocked = new Set([...this.entries.values()].filter(entry => entry.state === 'blocked').map(entry => entry.domain));
    }

    save() {
      if (this.saveTimer !== null) return;
      this.saveTimer = setTimeout(() => {
        this.saveTimer = null;
        this.flush();
      }, 800);
    }

    flush() {
      return browser.storage.local.set({ aiAdDomains: {
        salt: this.salt,
        entries: [...this.entries.values()],
        allowed: [...this.allowed]
      } }).catch(() => {});
    }

    /** ¿El host (o un dominio padre) está aprendido como bloqueado? Síncrono, para webRequest. */
    isBlocked(host) {
      if (!this.blocked.size || !host) return false;
      let candidate = String(host).toLowerCase().replace(/\.$/, '');
      while (candidate.includes('.')) {
        if (this.blocked.has(candidate)) return true;
        candidate = candidate.slice(candidate.indexOf('.') + 1);
      }
      return false;
    }

    /**
     * Dominios que se pueden aprender de un anuncio ocultado en `pageHost`:
     * su marco y sus enlaces de clic, de terceros y no protegidos.
     * `known(host)`: true si las listas ya lo bloquean.
     */
    evidence(pageHost, frameUrl, linkUrls, known) {
      const pageSite = siteOf(pageHost);
      const found = new Set();
      const consider = (url, requireClick) => {
        if (typeof url !== 'string' || url.length > 512) return;
        const host = hostOf(url);
        if (!validHost(host) || siteOf(host) === pageSite) return;
        if (requireClick && !CLICK.test(url)) return;
        const domain = domainFor(host);
        if (!validHost(domain) || isSafe(domain) || this.allowed.has(domain)) return;
        if (typeof known === 'function' && known(host)) return;
        found.add(domain);
      };
      consider(frameUrl, false);
      for (const url of Array.isArray(linkUrls) ? linkUrls.slice(0, 4) : []) consider(url, true);
      return [...found];
    }

    /**
     * Anota dominios vistos en un anuncio de `pageHost`. Devuelve los que
     * acaban de pasar a bloqueados. El llamador evita repetir el mismo
     * dominio en una misma carga de página.
     */
    async learn(pageHost, domains) {
      await this.ready;
      const promoted = [];
      if (!Array.isArray(domains) || !domains.length || !validHost(pageHost)) return promoted;
      const site = fingerprint(this.salt, siteOf(pageHost));
      const now = Date.now();
      for (const domain of domains.slice(0, 8)) {
        if (!validHost(domain) || this.allowed.has(domain) || isSafe(domain)) continue;
        let entry = this.entries.get(domain);
        if (!entry) {
          entry = { domain: domain, sites: [], hits: 0, first: now, last: now, state: 'watch' };
          this.entries.set(domain, entry);
        }
        entry.last = now;
        entry.hits = Math.min(1e6, entry.hits + 1);
        if (!entry.sites.includes(site)) entry.sites = entry.sites.concat(site).slice(-MAX_SITES);
        if (entry.state !== 'blocked' && (entry.sites.length >= PROMOTE_SITES || entry.hits >= PROMOTE_HITS)) {
          entry.state = 'blocked';
          promoted.push(domain);
        }
      }
      this.prune();
      this.save();
      return promoted;
    }

    async list() {
      await this.ready;
      this.prune();
      const domains = [...this.entries.values()]
        .map(entry => ({ domain: entry.domain, state: entry.state, sites: entry.sites.length, hits: entry.hits, last: entry.last }))
        .sort((a, b) => (b.state === 'blocked') - (a.state === 'blocked') || b.last - a.last);
      return { domains: domains, allowed: [...this.allowed].sort(), limit: LIMIT,
        blocked: this.blocked.size, watching: this.entries.size - this.blocked.size };
    }

    counts() {
      return { blocked: this.blocked.size, watching: Math.max(0, this.entries.size - this.blocked.size) };
    }

    async forget(domain) {
      await this.ready;
      if (domain === null) this.entries.clear();
      else this.entries.delete(domain);
      this.prune();
      await this.flush();
    }

    /** "Nunca bloquear": se olvida y no se vuelve a aprender. */
    async allow(domain, allowed) {
      await this.ready;
      if (!validHost(domain)) return false;
      if (allowed === false) this.allowed.delete(domain);
      else {
        this.allowed.add(domain);
        while (this.allowed.size > MAX_ALLOW) this.allowed.delete(this.allowed.values().next().value);
        this.entries.delete(domain);
      }
      this.prune();
      await this.flush();
      return true;
    }
  }

  globalThis.PhantomAdDomainsTools = { siteOf: siteOf, domainFor: domainFor, isSafe: isSafe, CLICK: CLICK };
  globalThis.PhantomAdDomains = new AdDomainMemory();
})();
