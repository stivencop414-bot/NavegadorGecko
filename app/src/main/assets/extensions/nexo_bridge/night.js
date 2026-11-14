(() => {
  "use strict";
  let state = { enabled: false }, epoch = 0, loaded = false, loading = null, applied = false;
  let fallback = null, observer = null, timer = 0;
  function removeFallback() { fallback?.remove(); fallback = null; }
  function stop() {
    clearTimeout(timer); timer = 0;
    observer?.disconnect(); observer = null;
    if (loaded && applied) globalThis.DarkReader.disable();
    applied = false; removeFallback();
  }
  function nativeDark() {
    for (const element of [document.body, document.documentElement]) {
      if (!element) continue;
      const style = getComputedStyle(element);
      const parts = style.backgroundColor.match(/[\d.]+/g)?.map(Number);
      if (!parts || parts.length < 3 || (parts.length > 3 && parts[3] < .8)) continue;
      const luminance = .2126 * parts[0] + .7152 * parts[1] + .0722 * parts[2];
      return luminance < 100;
    }
    return false;
  }
  function observeTheme() {
    if (observer || typeof MutationObserver !== "function") return;
    observer = new MutationObserver(() => {
      if (timer) return;
      timer = setTimeout(() => { timer = 0; stop(); render(); }, 200);
    });
    for (const target of [document.documentElement, document.body]) if (target)
      observer.observe(target, { attributes: true, attributeFilter: ["class"] });
  }
  async function render() {
    if (!state.enabled || document.hidden) { stop(); return; }
    if (!document.documentElement) return;
    if (document.readyState === "loading") {
      if (!fallback) {
        fallback = document.createElement("style");
        fallback.textContent = `html { background: ${state.background}; color-scheme: dark; }`;
        document.documentElement.appendChild(fallback);
      }
      return;
    }
    removeFallback();
    if (nativeDark() && !applied) { observeTheme(); return; }
    const revision = epoch;
    if (!loaded) {
      if (!loading) loading = browser.runtime.sendMessage({ type: "nexo_night_load" })
        .then(ok => { loaded = ok === true && !!globalThis.DarkReader; return loaded; })
        .catch(() => false).finally(() => { loading = null; });
      if (!await loading) return;
    }
    if (revision !== epoch || !state.enabled || document.hidden) return;
    // Content scripts must not turn theming into a cross-origin credentialed fetch proxy.
    globalThis.DarkReader.setFetchMethod(async raw => {
      const url = new URL(raw, location.href);
      if (!state.allowFetch || url.origin !== location.origin || url.protocol !== "https:" || url.username || url.password)
        throw new Error("Stylesheet unavailable");
      return fetch(url.href, { credentials: "omit", redirect: "error", referrerPolicy: "no-referrer" });
    });
    globalThis.DarkReader.enable({ darkSchemeBackgroundColor: state.background,
      darkSchemeTextColor: state.text, styleSystemControls: true }, {
      ignoreImageAnalysis: ["*"], disableStyleSheetsProxy: true
    });
    applied = true; observeTheme();
  }
  globalThis.NexoNight = {
    apply(value) {
      const next = { enabled: value?.enabled === true, allowFetch: value?.allowFetch === true,
        background: /^#[0-9a-f]{6}$/i.test(value?.background) ? value.background : "#181a1b",
        text: "#e8e6e3" };
      if (JSON.stringify(state) === JSON.stringify(next)) return;
      epoch++; stop(); state = next; void render();
    }
  };
  document.addEventListener("DOMContentLoaded", () => void render(), { once: true });
  // Registered before the media bridge, which may consume visibility events.
  window.addEventListener("visibilitychange", () => { epoch++; if (document.hidden) stop(); else void render(); }, true);
  window.addEventListener("pagehide", () => { epoch++; stop(); });
  window.addEventListener("pageshow", () => void render());
})();
