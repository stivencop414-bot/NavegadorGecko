/**
 * PhantomShield 8.1 - Dynamic Filter Lists Manager.
 * Loads only the conservative network-rule subset supported by the local engine.
 */
(function() {
  'use strict';

  const MAX_DYNAMIC_RULES = 200000;

  class FilterListManager {
    parseRule(line) {
      line = line.trim();
      if (!line || line.length > 4096 || line.startsWith("!") || line.startsWith("[Adblock")) {
        return null;
      }

      // Ignore purely cosmetic rules in the network engine
      if (line.includes("##") || line.includes("#@#") || line.includes("#?#")) {
        return null;
      }

      if ((line.startsWith("/") && line.lastIndexOf("/") > 0) ||
          /\$(?:[^,]*,)*(?:badfilter|csp|elemhide|generichide|genericblock|redirect(?:-rule)?|removeparam|replace|urltransform)(?:[=,]|$)/i.test(line)) {
        return null;
      }

      return { type: "network", ruleString: line };
    }

    async loadRulesText(text, engine, limit) {
      if (!text || !engine) return 0;
      const lines = text.split("\n");
      const maxRules = Number.isFinite(limit) ? Math.max(0, limit) : MAX_DYNAMIC_RULES;
      let count = 0;
      for (let i = 0; i < lines.length && count < maxRules; i++) {
        const parsed = this.parseRule(lines[i]);
        if (parsed && parsed.type === "network") {
          const ruleObj = engine._createRuleObject(parsed.ruleString);
          if (engine.addRule(ruleObj)) count++;
        }
        if (i > 0 && i % 5000 === 0) {
          await new Promise(resolve => setTimeout(resolve, 0));
        }
      }
      console.log(`[PhantomShield FilterLists] Parsed and loaded ${count} dynamic rules.`);
      return count;
    }

    async reloadFromStorage() {
      const engine = globalThis.PhantomNetworkEngineInstance;
      if (!engine) return;

      console.log('[PhantomShield FilterLists] Reloading all rules from dynamic storage...');
      
      const nextEngine = engine.createEmpty();

      // Load static base domains as fallback/baseline
      nextEngine.init(
        globalThis.PHANTOM_AD_DOMAINS,
        globalThis.PHANTOM_TRACKER_DOMAINS,
        globalThis.PHANTOM_POPUP_DOMAINS
      );

      // Load dynamic lists
      const LISTS = ['easylist', 'easyprivacy', 'ubo-filters'];
      let total = 0;
      for (const id of LISTS) {
        const key = 'phantom_list_' + id;
        try {
          const result = await browser.storage.local.get(key);
          const text = result[key];
          if (text) {
            console.log(`[PhantomShield FilterLists] Loading dynamic list: ${id}`);
            total += await this.loadRulesText(
              text,
              nextEngine,
              MAX_DYNAMIC_RULES - total
            );
          }
        } catch (e) {
          console.warn(`[PhantomShield FilterLists] Could not load ${id} from storage.`, e);
        }
      }
      engine.replaceRulesFrom(nextEngine);
      console.log('[PhantomShield FilterLists] Dynamic rules reload complete: ' + total + ' rules.');
      return total;
    }
  }

  const manager = new FilterListManager();

  if (typeof window !== "undefined") {
    window.PhantomFilterListManager = manager;
  }
  if (typeof globalThis !== "undefined") {
    globalThis.PhantomFilterListManager = manager;
  }
})();
