"use strict";
/*
 * Fondo del puente: inyecta Dark Reader (darkreader.js, MIT, v4.9.134) solo
 * en el marco que lo pide y solo una vez. Así las páginas claras pagan el
 * coste del motor únicamente cuando el modo nocturno lo necesita.
 */
browser.runtime.onMessage.addListener((message, sender) => {
  if (!message || message.type !== "nexo_dark_load") return undefined;
  const tabId = sender && sender.tab && sender.tab.id;
  if (typeof tabId !== "number" || sender.id !== browser.runtime.id) return Promise.resolve(false);
  return browser.tabs.executeScript(tabId, {
    file: "/darkreader.js",
    frameId: typeof sender.frameId === "number" ? sender.frameId : 0,
    runAt: "document_start"
  }).then(() => true, () => false);
});
