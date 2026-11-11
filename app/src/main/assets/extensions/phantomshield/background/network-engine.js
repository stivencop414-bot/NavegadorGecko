/**
 * PhantomShield 8.1 - Network rule engine.
 * Supports the conservative ABP subset used by the bundled and downloaded lists.
 */
(function() {
  'use strict';

  // `popup` es un tipo propio: las reglas $popup solo valen para ventanas nuevas
  // (antes se aplicaban a cualquier recurso de ese dominio).
  const REQUEST_TYPES = new Set([
    'document', 'subdocument', 'script', 'image', 'stylesheet',
    'xmlhttprequest', 'media', 'font', 'object', 'ping', 'websocket', 'other', 'popup'
  ]);

  const UNSUPPORTED_OPTIONS = new Set([
    'badfilter', 'csp', 'elemhide', 'generichide', 'genericblock',
    'removeparam', 'redirect', 'redirect-rule', 'replace', 'urltransform',
    'match-case', 'strict1p', 'strict3p'
  ]);

  const OPTION_ALIASES = Object.freeze({
    popunder: 'popup',
    '1p': '~third-party',
    '3p': 'third-party',
    css: 'stylesheet',
    frame: 'subdocument',
    img: 'image',
    xhr: 'xmlhttprequest'
  });

  const COMMON_TWO_PART_SUFFIXES = new Set([
    'co.uk', 'org.uk', 'ac.uk', 'com.au', 'net.au', 'org.au',
    'com.br', 'com.co', 'com.mx', 'co.jp', 'co.kr', 'co.nz',
    'com.ar', 'com.pe', 'com.ve', 'com.ec'
  ]);

  const NO_OPTIONS = Object.freeze(Object.create(null));

  class TrieNode {
    constructor() {
      this.children = new Map();
      this.rules = [];
    }
  }

  class PhantomNetworkEngine {
    constructor() {
      this.blockedCount = 0;
      this.resetRules();
    }

    resetRules() {
      this.domainTrie = new TrieNode();
      this.tokenIndex = new Map();
      this.exceptionDomainTrie = new TrieNode();
      this.exceptionTokenIndex = new Map();
      this.globalExceptionRules = [];
      // `||dominio^` sin opciones (la gran mayoría): basta con el nombre.
      this.plainDomains = new Set();
      // Reglas exclusivas de ventanas nuevas: aparte, para no evaluarlas en cada recurso.
      this.popupRules = [];
      this.popupExceptions = [];
      this.ruleKeys = new Set();
      this.ruleTotal = 0;
    }

    /** Libera el índice de duplicados, que solo hace falta mientras se cargan listas. */
    finalize() {
      this.ruleKeys = null;
    }

    createEmpty() {
      return new PhantomNetworkEngine();
    }

    replaceRulesFrom(other) {
      if (!(other instanceof PhantomNetworkEngine)) {
        throw new TypeError('Invalid network engine');
      }
      this.domainTrie = other.domainTrie;
      this.tokenIndex = other.tokenIndex;
      this.exceptionDomainTrie = other.exceptionDomainTrie;
      this.exceptionTokenIndex = other.exceptionTokenIndex;
      this.globalExceptionRules = other.globalExceptionRules;
      this.plainDomains = other.plainDomains;
      this.popupRules = other.popupRules;
      this.popupExceptions = other.popupExceptions;
      this.ruleKeys = other.ruleKeys;
      this.ruleTotal = other.ruleTotal;
    }

    init(adDomains, trackerDomains, popupDomains) {
      const allDomains = []
        .concat(adDomains || [])
        .concat(trackerDomains || [])
        .concat(popupDomains || []);

      let added = 0;
      for (const entry of allDomains) {
        if (typeof entry !== 'string' || !entry.trim()) continue;
        const source = entry.trim();
        const rule = this._createRuleObject(source.startsWith('@@') ? source : '||' + source + '^');
        if (this.addRule(rule)) added++;
      }
      console.info('[PhantomShield] Loaded ' + added + ' bundled network rules.');
      return added;
    }

    _parseOptions(optionsText) {
      const options = Object.create(null);
      if (!optionsText) return options;

      for (const rawOption of optionsText.split(',')) {
        const option = rawOption.trim().toLowerCase();
        if (!option) continue;
        const separator = option.indexOf('=');
        if (separator === -1) {
          if (option === '1p') {
            options['~third-party'] = true;
          } else if (option === '~1p') {
            options['third-party'] = true;
          } else {
            const negative = option.startsWith('~') ? '~' : '';
            const base = negative ? option.slice(1) : option;
            const alias = OPTION_ALIASES[base];
            options[alias ? (alias.startsWith('~') ? alias : negative + alias) : option] = true;
          }
        } else {
          const key = option.slice(0, separator);
          const value = option.slice(separator + 1);
          // `from=` es el nombre de uBO para `domain=`.
          options[key === 'from' ? 'domain' : key] = value;
        }
      }
      return options;
    }

    _createRuleObject(rawString) {
      if (typeof rawString !== 'string') return null;
      let raw = rawString.trim();
      if (!raw || raw.length > 4096 || raw.startsWith('!') || raw.startsWith('[')) return null;
      if (raw.includes('##') || raw.includes('#@#') || raw.includes('#?#')) return null;

      const optionIndex = raw.indexOf('$');
      const optionText = optionIndex === -1 ? '' : raw.slice(optionIndex + 1);
      let body = optionIndex === -1 ? raw : raw.slice(0, optionIndex);
      const options = this._parseOptions(optionText);

      for (const key of Object.keys(options)) {
        const baseKey = key.startsWith('~') ? key.slice(1) : key;
        const supported = REQUEST_TYPES.has(baseKey) ||
          key === 'third-party' ||
          key === '~third-party' ||
          key === 'domain' ||
          key === 'important';
        if (UNSUPPORTED_OPTIONS.has(baseKey) || !supported) return null;
      }

      const isException = body.startsWith('@@');
      if (isException) body = body.slice(2);

      const domainAnchor = body.startsWith('||');
      if (domainAnchor) body = body.slice(2);

      const leftAnchor = !domainAnchor && body.startsWith('|');
      if (leftAnchor) body = body.slice(1);

      const rightAnchor = body.endsWith('|');
      if (rightAnchor) body = body.slice(0, -1);

      if (!body || (body.startsWith('/') && body.endsWith('/'))) return null;

      const hasOptions = Object.keys(options).length > 0;
      const rule = {
        raw: raw,
        options: hasOptions ? options : NO_OPTIONS,
        plain: !hasOptions,
        isException: isException,
        domainAnchor: domainAnchor,
        leftAnchor: leftAnchor,
        rightAnchor: rightAnchor,
        pattern: body,
        simpleDomain: null,
        regex: null
      };

      const simpleMatch = domainAnchor ? body.match(/^([a-z0-9.-]+)\^?$/i) : null;
      if (simpleMatch) {
        const domain = simpleMatch[1].toLowerCase().replace(/^\.+|\.+$/g, '');
        if (!this._isValidHostname(domain)) return null;
        rule.simpleDomain = domain;
      }

      // Las expresiones regulares se compilan la primera vez que una regla
      // coincide por token: compilar ~60.000 al arrancar costaba CPU y memoria.
      if (!rule.simpleDomain && rule.domainAnchor) {
        const boundaryIndex = rule.pattern.search(/[\/^]/);
        const host = boundaryIndex === -1 ? rule.pattern : rule.pattern.slice(0, boundaryIndex);
        if (!this._isValidHostname(host.replace(/\*/g, 'a'))) return null;
      }
      return rule;
    }

    /** Evalúa una regla no trivial contra la URL, compilándola bajo demanda. */
    _test(rule, url) {
      if (rule.dead) return false;
      if (!rule.regex) {
        try {
          rule.regex = this._compilePattern(rule);
        } catch (error) {
          rule.dead = true;
          return false;
        }
      }
      rule.regex.lastIndex = 0;
      return rule.regex.test(url);
    }

    ruleCount() {
      return this.ruleTotal;
    }

    _compilePattern(rule) {
      const escapePart = function(value) {
        let result = '';
        for (const char of value) {
          if (char === '*') {
            result += '.*';
          } else if (char === '^') {
            result += '(?:[^a-z0-9_.%-]|$)';
          } else {
            result += char.replace(/[\\^$.*+?()[\]{}|]/g, '\\$&');
          }
        }
        return result;
      };

      let expression;
      if (rule.domainAnchor) {
        const boundaryIndex = rule.pattern.search(/[\/^]/);
        const host = boundaryIndex === -1 ? rule.pattern : rule.pattern.slice(0, boundaryIndex);
        const rest = boundaryIndex === -1 ? '' : rule.pattern.slice(boundaryIndex);
        if (!this._isValidHostname(host.replace(/\*/g, 'a'))) throw new Error('Invalid host');
        expression = '^[a-z][a-z0-9+.-]*://(?:[^./?#]+\\.)*' +
          escapePart(host) + '(?=[:/?#]|$)' + escapePart(rest);
      } else {
        expression = (rule.leftAnchor ? '^' : '') + escapePart(rule.pattern) +
          (rule.rightAnchor ? '$' : '');
      }
      return new RegExp(expression, 'i');
    }

    addRule(rule) {
      if (!rule || !rule.pattern) return false;
      const key = rule.raw.toLowerCase();
      if (!this.ruleKeys) this.ruleKeys = new Set();
      if (this.ruleKeys.has(key)) return false;
      this.ruleKeys.add(key);
      // El texto original solo servía para detectar duplicados.
      rule.raw = null;

      if (rule.options.popup && Object.keys(rule.options).filter(function(key) { return REQUEST_TYPES.has(key); }).length === 1) {
        (rule.isException ? this.popupExceptions : this.popupRules).push(rule);
        this.ruleTotal++;
        return true;
      }
      if (!rule.isException && rule.simpleDomain && rule.plain) {
        this.plainDomains.add(rule.simpleDomain);
        this.ruleTotal++;
        return true;
      }
      const stored = this._store(rule);
      if (stored) this.ruleTotal++;
      return stored;
    }

    _store(rule) {
      if (rule.isException) {
        if (rule.simpleDomain) {
          this._insertIntoTrie(rule.simpleDomain, rule, this.exceptionDomainTrie);
          return true;
        }
        const exceptionTokens = rule.pattern
          .split(/\W+/)
          .filter(function(token) { return token.length >= 4; })
          .sort(function(a, b) { return b.length - a.length; });
        if (exceptionTokens.length === 0) {
          this.globalExceptionRules.push(rule);
          return true;
        }
        const exceptionToken = exceptionTokens[0].toLowerCase();
        if (!this.exceptionTokenIndex.has(exceptionToken)) {
          this.exceptionTokenIndex.set(exceptionToken, []);
        }
        this.exceptionTokenIndex.get(exceptionToken).push(rule);
        return true;
      }

      if (rule.simpleDomain) {
        this._insertIntoTrie(rule.simpleDomain, rule, this.domainTrie);
        return true;
      }

      const tokens = rule.pattern
        .split(/\W+/)
        .filter(function(token) { return token.length >= 4; })
        .sort(function(a, b) { return b.length - a.length; });
      if (tokens.length === 0) return false;

      const primaryToken = tokens[0].toLowerCase();
      if (!this.tokenIndex.has(primaryToken)) this.tokenIndex.set(primaryToken, []);
      this.tokenIndex.get(primaryToken).push(rule);
      return true;
    }

    _insertIntoTrie(domain, rule, root) {
      let node = root;
      for (const part of domain.split('.').reverse()) {
        if (!node.children.has(part)) node.children.set(part, new TrieNode());
        node = node.children.get(part);
      }
      node.rules.push(rule);
    }

    _isValidHostname(hostname) {
      return typeof hostname === 'string' &&
        hostname.length > 0 &&
        hostname.length <= 253 &&
        hostname.split('.').every(function(label) {
          return label.length > 0 && label.length <= 63 &&
            /^[a-z0-9*](?:[a-z0-9*-]*[a-z0-9*])?$/i.test(label);
        });
    }

    _hostnameMatches(hostname, ruleDomain) {
      return hostname === ruleDomain || hostname.endsWith('.' + ruleDomain);
    }

    _siteKey(hostname) {
      const normalized = (hostname || '').toLowerCase().replace(/\.$/, '');
      if (!normalized || /^\d+(?:\.\d+){3}$/.test(normalized) || normalized.includes(':')) {
        return normalized;
      }
      const labels = normalized.split('.');
      if (labels.length <= 2) return normalized;
      const lastTwo = labels.slice(-2).join('.');
      return COMMON_TWO_PART_SUFFIXES.has(lastTwo)
        ? labels.slice(-3).join('.')
        : lastTwo;
    }

    isThirdPartyRequest(url, documentUrl) {
      if (!url || !documentUrl) return true;
      try {
        return this._siteKey(new URL(url).hostname) !== this._siteKey(new URL(documentUrl).hostname);
      } catch (error) {
        return true;
      }
    }

    _matchOptions(rule, requestType, isThirdParty, documentDomain) {
      if (rule.options['third-party'] && !isThirdParty) return false;
      if (rule.options['~third-party'] && isThirdParty) return false;

      const mappedType = this._mapRequestType(requestType);
      const positiveTypes = Object.keys(rule.options).filter(function(key) {
        return REQUEST_TYPES.has(key);
      });
      if (positiveTypes.length > 0 && !rule.options[mappedType]) return false;
      if (rule.options['~' + mappedType]) return false;

      if (typeof rule.options.domain === 'string') {
        const included = [];
        const excluded = [];
        for (const rawDomain of rule.options.domain.split('|')) {
          const domain = rawDomain.trim().toLowerCase();
          if (!domain) continue;
          if (domain.startsWith('~')) excluded.push(domain.slice(1));
          else included.push(domain);
        }
        if (excluded.some((domain) => this._hostnameMatches(documentDomain, domain))) return false;
        if (included.length > 0 &&
            !included.some((domain) => this._hostnameMatches(documentDomain, domain))) return false;
      }
      return true;
    }

    _mapRequestType(type) {
      const mapping = {
        main_frame: 'document',
        sub_frame: 'subdocument',
        script: 'script',
        image: 'image',
        stylesheet: 'stylesheet',
        xmlhttprequest: 'xmlhttprequest',
        media: 'media',
        font: 'font',
        object: 'object',
        ping: 'ping',
        beacon: 'ping',
        websocket: 'websocket',
        popup: 'popup'
      };
      return mapping[type] || 'other';
    }

    _findDomainRules(hostname, root) {
      const matches = [];
      let node = root || this.domainTrie;
      for (const part of hostname.split('.').reverse()) {
        if (!node.children.has(part)) break;
        node = node.children.get(part);
        if (node.rules.length) matches.push.apply(matches, node.rules);
      }
      return matches;
    }

    /** ¿El host o alguno de sus dominios padre está en la lista simple? */
    _matchesPlainDomain(hostname) {
      if (!this.plainDomains.size) return false;
      let candidate = hostname;
      while (candidate) {
        if (this.plainDomains.has(candidate)) return true;
        const dot = candidate.indexOf('.');
        if (dot === -1) return false;
        candidate = candidate.slice(dot + 1);
      }
      return false;
    }

    _findExceptionRules(hostname, url) {
      const candidates = new Set(this.globalExceptionRules);
      for (const rule of this._findDomainRules(hostname, this.exceptionDomainTrie)) {
        candidates.add(rule);
      }
      const tokens = new Set(
        url.toLowerCase().split(/\W+/).filter(function(token) { return token.length >= 4; })
      );
      for (const token of tokens) {
        const rules = this.exceptionTokenIndex.get(token);
        if (!rules) continue;
        for (const rule of rules) candidates.add(rule);
      }
      return candidates;
    }

    shouldBlock(url, requestType, documentUrl, countBlocked) {
      if (!url) return false;

      let hostname;
      let documentDomain = '';
      try {
        hostname = new URL(url).hostname.toLowerCase();
        if (documentUrl) documentDomain = new URL(documentUrl).hostname.toLowerCase();
      } catch (error) {
        return false;
      }

      const isThirdParty = this.isThirdPartyRequest(url, documentUrl);

      if (requestType === 'popup') {
        const matches = (rule) => (rule.simpleDomain ? this._hostnameMatches(hostname, rule.simpleDomain) : this._test(rule, url)) &&
          this._matchOptions(rule, 'popup', isThirdParty, documentDomain);
        if (this.popupExceptions.some(matches)) return false;
        if (this.popupRules.some(matches)) return true;
      }

      for (const rule of this._findExceptionRules(hostname, url)) {
        if ((rule.simpleDomain || this._test(rule, url)) &&
            this._matchOptions(rule, requestType, isThirdParty, documentDomain)) {
          return false;
        }
      }

      if (this._matchesPlainDomain(hostname)) {
        if (countBlocked !== false) this.recordBlocked();
        return true;
      }

      for (const rule of this._findDomainRules(hostname, this.domainTrie)) {
        if (this._matchOptions(rule, requestType, isThirdParty, documentDomain)) {
          if (countBlocked !== false) this.recordBlocked();
          return true;
        }
      }

      const urlTokens = new Set(
        url.toLowerCase().split(/\W+/).filter(function(token) { return token.length >= 4; })
      );
      for (const token of urlTokens) {
        const rules = this.tokenIndex.get(token);
        if (!rules) continue;
        for (const rule of rules) {
          if (this._test(rule, url) &&
              this._matchOptions(rule, requestType, isThirdParty, documentDomain)) {
            if (countBlocked !== false) this.recordBlocked();
            return true;
          }
        }
      }

      if (hostname.endsWith('.googlevideo.com') || hostname === 'googlevideo.com') {
        if (url.includes('adformat=') || url.includes('ad_type=') || url.includes('ctier=A')) {
          if (countBlocked !== false) this.recordBlocked();
          return true;
        }
      }
      return false;
    }

    isDomainBlocked(hostname) {
      const normalized = (hostname || '').toLowerCase().replace(/\.$/, '');
      if (!this._isValidHostname(normalized)) return false;
      return this._matchesPlainDomain(normalized) ||
        this._findDomainRules(normalized, this.domainTrie).length > 0;
    }

    addBlockedDomain(hostname) {
      const normalized = (hostname || '').toLowerCase().replace(/\.$/, '');
      if (!this._isValidHostname(normalized)) return false;
      return this.addRule(this._createRuleObject('||' + normalized + '^'));
    }

    recordBlocked(amount) {
      const increment = Number.isFinite(amount) ? Math.max(1, Math.floor(amount)) : 1;
      this.blockedCount += increment;
    }

    resetBlockedCount() {
      this.blockedCount = 0;
    }

    setBlockedCount(value) {
      const count = Number(value);
      this.blockedCount = Number.isFinite(count) ? Math.max(0, Math.floor(count)) : 0;
    }

    getBlockedCount() {
      return this.blockedCount;
    }
  }

  globalThis.PhantomNetworkEngineInstance = new PhantomNetworkEngine();
})();
