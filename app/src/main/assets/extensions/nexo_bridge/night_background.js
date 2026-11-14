/* Only inject the pinned local engine into the requesting extension content-script frame. */
browser.runtime.onMessage.addListener((message, sender) => {
  if (message?.type !== "nexo_night_load" || sender.id !== browser.runtime.id ||
      !sender.tab || !Number.isInteger(sender.frameId)) return;
  return browser.tabs.executeScript(sender.tab.id, {
    file: "vendor/darkreader.js", frameId: sender.frameId, runAt: "document_start"
  }).then(() => true, () => false);
});
