(() => {
  "use strict";

  const NATIVE_APP = "cloud_translator";
  const MAX_ITEMS = 80;
  const MAX_CHARS = 12000;
  // La primera vez ML Kit puede descargar el modelo del idioma.
  const BATCH_TIMEOUT_MS = 120000;
  let port = null;
  let translating = false;

  function acceptedTextNode(node) {
    if (!node || !node.parentElement) return false;

    const parent = node.parentElement;
    const tag = parent.tagName;
    if ([
      "SCRIPT", "STYLE", "NOSCRIPT", "TEXTAREA",
      "CODE", "PRE", "OPTION", "KBD", "SAMP"
    ].includes(tag)) {
      return false;
    }

    // Respetar las marcas estándar de "no traducir" y no tocar
    // contenido que el usuario está editando.
    try {
      if (parent.closest('[translate="no"], .notranslate, [contenteditable=""], [contenteditable="true"]')) {
        return false;
      }
    } catch (_) {}

    const raw = node.textContent || "";
    const core = raw.trim();
    if (!core || !/[A-Za-zÀ-ÿ\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF\u3040-\u30FF\u3400-\u9FFF]/u.test(core)) {
      return false;
    }

    return true;
  }

  function collectRecords() {
    if (!document.body) return [];

    const walker = document.createTreeWalker(
      document.body,
      NodeFilter.SHOW_TEXT,
      {
        acceptNode(node) {
          return acceptedTextNode(node)
            ? NodeFilter.FILTER_ACCEPT
            : NodeFilter.FILTER_REJECT;
        }
      }
    );

    const records = [];
    let node;

    while ((node = walker.nextNode())) {
      const raw = node.textContent || "";
      const core = raw.trim();
      const start = raw.indexOf(core);

      records.push({
        node,
        original: raw,
        prefix: start > 0 ? raw.slice(0, start) : "",
        core,
        suffix: raw.slice(start + core.length)
      });
    }

    return records;
  }

  function makeBatches(records) {
    const batches = [];
    let batch = [];
    let chars = 0;

    for (const record of records) {
      const length = record.core.length;

      if (
        batch.length > 0 &&
        (batch.length >= MAX_ITEMS || chars + length > MAX_CHARS)
      ) {
        batches.push(batch);
        batch = [];
        chars = 0;
      }

      batch.push(record);
      chars += length;
    }

    if (batch.length) batches.push(batch);
    return batches;
  }

  function withTimeout(promise, ms) {
    let timer = 0;
    const timeout = new Promise((_, reject) => {
      timer = setTimeout(
        () => reject(new Error("La traducción tardó demasiado. Inténtalo de nuevo.")),
        ms
      );
    });
    return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
  }

  async function translatePage(target) {
    if (translating) return;
    translating = true;

    try {
      const records = collectRecords();

      if (!records.length) {
        sendStatus(true, "No encontré texto visible para traducir.");
        return;
      }

      const batches = makeBatches(records);
      let changed = 0;

      for (const batch of batches) {
        const response = await withTimeout(
          browser.runtime.sendNativeMessage(
            NATIVE_APP,
            {
              type: "translate",
              target,
              texts: batch.map(item => item.core)
            }
          ),
          BATCH_TIMEOUT_MS
        );

        if (!response || response.ok !== true) {
          throw new Error(
            response && response.error
              ? response.error
              : "La API no devolvió una traducción válida."
          );
        }

        const translated = Array.isArray(response.translations)
          ? response.translations
          : [];

        if (translated.length !== batch.length) {
          throw new Error("La respuesta no coincide con los nodos enviados.");
        }

        batch.forEach((record, index) => {
          if (
            record.node.isConnected &&
            record.node.textContent === record.original
          ) {
            record.node.textContent =
              record.prefix +
              String(translated[index] ?? record.core) +
              record.suffix;
            changed++;
          }
        });
      }

      sendStatus(
        true,
        `Página traducida · ${changed} fragmentos actualizados`
      );
    } catch (error) {
      sendStatus(
        false,
        error && error.message
          ? error.message
          : "No se pudo traducir la página."
      );
    } finally {
      translating = false;
    }
  }

  function sendStatus(ok, message) {
    try {
      if (port) {
        port.postMessage({
          type: "translator_status",
          ok,
          message
        });
      }
    } catch (_) {}
  }

  try {
    port = browser.runtime.connectNative(NATIVE_APP);

    port.onMessage.addListener(message => {
      if (
        message &&
        message.type === "translate_page"
      ) {
        const target =
          typeof message.target === "string" && /^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$/.test(message.target)
            ? message.target
            : "es";
        translatePage(target);
      }
    });

    port.postMessage({
      type: "translator_ready"
    });
  } catch (_) {}
})();
