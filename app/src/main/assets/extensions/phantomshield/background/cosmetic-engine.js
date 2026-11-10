/**
 * PhantomShield - Cosmetic filter engine.
 *
 * Aprovecha las reglas de ocultación (`##`) de EasyList y listas afines, que
 * antes se descartaban: por eso quedaban huecos y anuncios que la red no
 * podía bloquear (los que sirve el propio sitio).
 *
 *  - Reglas de un sitio (`ejemplo.com##.banner`) se entregan a ese sitio.
 *  - Reglas genéricas simples (`##.ad-box`, `###ad-top`), miles: solo se
 *    aplican cuando esa clase o id aparece en la página (búsqueda por token),
 *    en lugar de inyectar una hoja de estilos gigante en cada pestaña.
 *  - Reglas genéricas complejas (unos cientos) se aplican siempre.
 *  - Excepciones (`#@#`, `$generichide`, `$elemhide`) se respetan.
 *
 * Solo CSS estándar: los filtros procedurales y scriptlets se ignoran.
 */
(function() {
  'use strict';

  const MAX_SPECIFIC_ENTRIES = 120000;
  const MAX_GENERIC_SIMPLE = 40000;
  const MAX_GENERIC_COMPLEX = 1500;
  const MAX_SELECTOR_LENGTH = 400;
  const MAX_SELECTORS_PER_REPLY = 1500;

  // Sintaxis extendida de uBO/ABP/AdGuard que no es CSS: no se puede aplicar.
  const UNSUPPORTED = /:(?:has-text|contains|matches-css|matches-css-before|matches-css-after|matches-attr|matches-path|matches-media|matches-prop|upward|xpath|remove|remove-attr|remove-class|style|watch-attr|min-text-length|others|nth-ancestor|if|if-not|-abp-[a-z-]+)\(|\+js\(|^\^|[{}]|^script:|\/\*/i;
  const SIMPLE_CLASS = /^\.([\w-]+)$/;
  const SIMPLE_ID = /^#([\w-]+)$/;
  const DOMAIN = /^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$/;

  class PhantomCosmeticEngine {
    constructor() {
      this.reset();
    }

    reset() {
      this.specific = new Map();          // dominio -> [selectores]
      this.exceptions = new Map();        // dominio -> Set(selectores)
      this.genericExceptions = new Set(); // selectores desactivados en todos los sitios
      this.genericClass = new Set();
      this.genericId = new Set();
      this.genericComplex = [];
      this.genericComplexSeen = new Set();
      this.noGeneric = new Set();         // $generichide
      this.noCosmetic = new Set();        // $elemhide
      this.specificEntries = 0;
    }

    createEmpty() {
      return new PhantomCosmeticEngine();
    }

    replaceFrom(other) {
      if (!(other instanceof PhantomCosmeticEngine)) throw new TypeError('Invalid cosmetic engine');
      for (const key of ['specific', 'exceptions', 'genericExceptions', 'genericClass', 'genericId',
        'genericComplex', 'genericComplexSeen', 'noGeneric', 'noCosmetic', 'specificEntries']) {
        this[key] = other[key];
      }
    }

    count() {
      return this.specificEntries + this.genericClass.size + this.genericId.size + this.genericComplex.length;
    }

    /** `@@||sitio.com^$generichide` / `$elemhide`: el sitio pide no aplicar ocultación. */
    addHideException(line) {
      const match = /^@@\|\|([a-z0-9.-]+)\^?\$([a-z0-9,~=|._-]+)$/i.exec(line);
      if (!match) return false;
      const domain = match[1].toLowerCase().replace(/^\.+|\.+$/g, '');
      if (!DOMAIN.test(domain)) return false;
      const options = match[2].toLowerCase().split(',');
      // Con `domain=` la excepción solo vale en ciertos sitios: no generalizar.
      if (options.some(option => option.startsWith('domain='))) return false;
      let used = false;
      if (options.includes('elemhide') || options.includes('ehide')) { this.noCosmetic.add(domain); used = true; }
      if (options.includes('generichide') || options.includes('ghide')) { this.noGeneric.add(domain); used = true; }
      return used;
    }

    /** Añade una línea de lista con `##` o `#@#`. Devuelve true si se aceptó. */
    addLine(rawLine) {
      if (typeof rawLine !== 'string') return false;
      const line = rawLine.trim();
      if (!line || line.length > 4096 || line[0] === '!' || line[0] === '[') return false;
      const match = /^([^#]*)#(@?)#(.+)$/.exec(line);
      if (!match) return false;
      const selector = match[3].trim();
      if (!selector || selector.length > MAX_SELECTOR_LENGTH || UNSUPPORTED.test(selector)) return false;
      const isException = match[2] === '@';

      const included = [];
      let negated = false;
      for (const part of match[1].split(',')) {
        const domain = part.trim().toLowerCase();
        if (!domain) continue;
        if (domain[0] === '~') { negated = true; continue; }
        // `ejemplo.*` (cualquier TLD) no se puede indexar por dominio exacto.
        if (!DOMAIN.test(domain)) return false;
        included.push(domain);
      }
      // Reglas del tipo "en todos menos X": poco frecuentes; se omiten por seguridad.
      if (negated && !included.length) return false;

      if (isException) {
        if (!included.length) { this.genericExceptions.add(selector); return true; }
        for (const domain of included) {
          let set = this.exceptions.get(domain);
          if (!set) this.exceptions.set(domain, set = new Set());
          set.add(selector);
        }
        return true;
      }

      if (included.length) {
        if (this.specificEntries + included.length > MAX_SPECIFIC_ENTRIES) return false;
        for (const domain of included) {
          let list = this.specific.get(domain);
          if (!list) this.specific.set(domain, list = []);
          list.push(selector);
        }
        this.specificEntries += included.length;
        return true;
      }

      const simpleClass = SIMPLE_CLASS.exec(selector);
      const simpleId = simpleClass ? null : SIMPLE_ID.exec(selector);
      if (simpleClass || simpleId) {
        if (this.genericClass.size + this.genericId.size >= MAX_GENERIC_SIMPLE) return false;
        (simpleClass ? this.genericClass : this.genericId).add((simpleClass || simpleId)[1]);
        return true;
      }
      if (this.genericComplex.length >= MAX_GENERIC_COMPLEX || this.genericComplexSeen.has(selector)) return false;
      this.genericComplexSeen.add(selector);
      this.genericComplex.push(selector);
      return true;
    }

    /** host -> [host, dominio padre, ...] sin llegar al TLD. */
    _chain(host) {
      const clean = String(host || '').toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
      const chain = [];
      if (!clean) return chain;
      const labels = clean.split('.');
      for (let i = 0; i < labels.length - 1; i++) chain.push(labels.slice(i).join('.'));
      if (labels.length === 1) chain.push(clean);
      return chain;
    }

    _exceptionsFor(chain) {
      const result = new Set(this.genericExceptions);
      for (const domain of chain) {
        const set = this.exceptions.get(domain);
        if (set) for (const selector of set) result.add(selector);
      }
      return result;
    }

    /** Selectores que se aplican siempre en `host` (propios + genéricos complejos). */
    forHost(host) {
      const chain = this._chain(host);
      if (chain.some(domain => this.noCosmetic.has(domain))) {
        return { selectors: [], generic: false };
      }
      const generic = !chain.some(domain => this.noGeneric.has(domain));
      const exceptions = this._exceptionsFor(chain);
      const seen = new Set();
      const selectors = [];
      const push = selector => {
        if (selectors.length >= MAX_SELECTORS_PER_REPLY || seen.has(selector) || exceptions.has(selector)) return;
        seen.add(selector);
        selectors.push(selector);
      };
      for (const domain of chain) {
        const list = this.specific.get(domain);
        if (list) list.forEach(push);
      }
      if (generic) this.genericComplex.forEach(push);
      return { selectors: selectors, generic: generic };
    }

    /** Clases e ids vistos en la página -> selectores genéricos que coinciden. */
    lookup(host, classes, ids) {
      const chain = this._chain(host);
      if (chain.some(domain => this.noCosmetic.has(domain) || this.noGeneric.has(domain))) return [];
      const exceptions = this._exceptionsFor(chain);
      const selectors = [];
      const add = (values, index, prefix) => {
        if (!Array.isArray(values)) return;
        for (const value of values.slice(0, 2000)) {
          if (typeof value !== 'string' || !index.has(value)) continue;
          const selector = prefix + value;
          if (!exceptions.has(selector) && selectors.length < MAX_SELECTORS_PER_REPLY) selectors.push(selector);
        }
      };
      add(classes, this.genericClass, '.');
      add(ids, this.genericId, '#');
      return selectors;
    }
  }

  globalThis.PhantomCosmeticEngineInstance = new PhantomCosmeticEngine();
})();
