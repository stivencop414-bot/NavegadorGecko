/**
 * PhantomShield - Dynamic Filter Lists Manager.
 * Carga las reglas de red compatibles en el motor local y las reglas de
 * ocultación (`##`) en el motor cosmético.
 */
(function() {
  'use strict';

  const MAX_DYNAMIC_RULES = 220000;

  /*
   * Catálogo único (lo usa también el actualizador). Cada lista prueba sus
   * direcciones en orden: si el servidor principal falla, usa el espejo.
   * Solo se descarga texto declarativo; nunca código.
   */
  const LISTS = Object.freeze([
    { id: 'easylist', name: 'EasyList', urls: [
      'https://easylist.to/easylist/easylist.txt',
      'https://raw.githubusercontent.com/uBlockOrigin/uAssetsCDN/main/thirdparties/easylist.txt'
    ] },
    { id: 'easyprivacy', name: 'EasyPrivacy', urls: [
      'https://easylist.to/easylist/easyprivacy.txt',
      'https://raw.githubusercontent.com/uBlockOrigin/uAssetsCDN/main/thirdparties/easyprivacy.txt'
    ] },
    { id: 'ubo-filters', name: 'uBlock filters', urls: [
      'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt'
    ] },
    { id: 'ubo-privacy', name: 'uBlock privacy', urls: [
      'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt'
    ] },
    // Excepciones que evitan que sitios se rompan con las listas anteriores.
    { id: 'ubo-unbreak', name: 'uBlock unbreak', urls: [
      'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt'
    ] },
    { id: 'easylist-es', name: 'EasyList Spanish', optional: true, urls: [
      'https://easylist-downloads.adblockplus.org/easylistspanish.txt'
    ] }
  ]);

  const UNSUPPORTED_NETWORK = /\$(?:[^,]*,)*(?:badfilter|csp|redirect(?:-rule)?|removeparam|replace|urltransform|permissions|header|uritransform|ipaddress|to|method)(?:[=,]|$)/i;
  const HIDE_EXCEPTION = /\$(?:[^,]*,)*(?:elemhide|ehide|generichide|ghide)(?:,|$)/i;

  class FilterListManager {
    constructor() {
      this.lists = LISTS;
      this.counts = { network: 0, cosmetic: 0 };
    }

    parseRule(line) {
      line = line.trim();
      if (!line || line.length > 4096 || line.startsWith('!') || line.startsWith('[Adblock')) {
        return null;
      }

      // Reglas de ocultación: `##`, `#@#` (y variantes no CSS que el motor descarta).
      if (/#@?#|#\?#|#\$#|#%#/.test(line)) {
        return /^[^#]*#@?#/.test(line) ? { type: 'cosmetic', ruleString: line } : null;
      }

      if (HIDE_EXCEPTION.test(line)) {
        return line.startsWith('@@') ? { type: 'hide-exception', ruleString: line } : null;
      }

      if ((line.startsWith('/') && line.lastIndexOf('/') > 0) || UNSUPPORTED_NETWORK.test(line) ||
          /\$(?:[^,]*,)*genericblock(?:,|$)/i.test(line)) {
        return null;
      }

      return { type: 'network', ruleString: line };
    }

    async loadRulesText(text, engine, limit, cosmetic) {
      if (!text || !engine) return 0;
      const lines = text.split('\n');
      const maxRules = Number.isFinite(limit) ? Math.max(0, limit) : MAX_DYNAMIC_RULES;
      let count = 0;
      for (let i = 0; i < lines.length; i++) {
        const parsed = this.parseRule(lines[i]);
        if (parsed) {
          if (parsed.type === 'network') {
            if (count < maxRules && engine.addRule(engine._createRuleObject(parsed.ruleString))) count++;
          } else if (cosmetic) {
            if (parsed.type === 'cosmetic') cosmetic.addLine(parsed.ruleString);
            else cosmetic.addHideException(parsed.ruleString);
          }
        }
        // Ceder el hilo: el bloqueo ya activo no debe detenerse mientras se cargan listas.
        if (i > 0 && i % 4000 === 0) {
          await new Promise(resolve => setTimeout(resolve, 0));
        }
      }
      return count;
    }

    async reloadFromStorage() {
      const engine = globalThis.PhantomNetworkEngineInstance;
      if (!engine) return 0;
      const cosmetic = globalThis.PhantomCosmeticEngineInstance || null;

      const nextEngine = engine.createEmpty();
      const nextCosmetic = cosmetic ? cosmetic.createEmpty() : null;

      // Reglas incluidas en la app: funcionan antes de la primera descarga.
      nextEngine.init(
        globalThis.PHANTOM_AD_DOMAINS,
        globalThis.PHANTOM_TRACKER_DOMAINS,
        globalThis.PHANTOM_POPUP_DOMAINS
      );
      if (nextCosmetic && Array.isArray(globalThis.PHANTOM_COSMETIC_RULES)) {
        for (const line of globalThis.PHANTOM_COSMETIC_RULES) nextCosmetic.addLine(line);
      }

      let total = 0;
      for (const list of LISTS) {
        const key = 'phantom_list_' + list.id;
        try {
          const result = await browser.storage.local.get(key);
          const text = result[key];
          if (text) {
            total += await this.loadRulesText(text, nextEngine, MAX_DYNAMIC_RULES - total, nextCosmetic);
          }
        } catch (e) {
          console.warn('[PhantomShield FilterLists] Could not load ' + list.id + ' from storage.', e);
        }
      }
      if (typeof nextEngine.finalize === 'function') nextEngine.finalize();
      engine.replaceRulesFrom(nextEngine);
      if (cosmetic && nextCosmetic) cosmetic.replaceFrom(nextCosmetic);
      this.counts = {
        network: typeof engine.ruleCount === 'function' ? engine.ruleCount() : total,
        cosmetic: cosmetic ? cosmetic.count() : 0
      };
      console.info('[PhantomShield FilterLists] ' + this.counts.network + ' network rules, ' +
        this.counts.cosmetic + ' cosmetic rules.');
      return total;
    }
  }

  const manager = new FilterListManager();
  globalThis.PhantomFilterListManager = manager;
})();
