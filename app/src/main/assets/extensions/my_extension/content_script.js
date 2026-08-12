(() => {
  "use strict";

  const badge = document.createElement("div");
  badge.id = "__geckoview_extension_badge";
  badge.textContent = "✓ Extensión GeckoView activa";
  Object.assign(badge.style, {
    position: "fixed",
    right: "10px",
    bottom: "10px",
    zIndex: "2147483647",
    padding: "8px 12px",
    borderRadius: "8px",
    background: "#111",
    color: "#fff",
    fontFamily: "sans-serif",
    fontSize: "12px",
    boxShadow: "0 2px 8px rgba(0,0,0,.35)"
  });

  (document.body || document.documentElement).appendChild(badge);

  browser.runtime.sendNativeMessage("browser", {
    type: "page_loaded",
    url: location.href,
    title: document.title
  }).then(response => {
    if (response && response.ok) {
      badge.textContent = "✓ WebExtension ↔ Kotlin";
    }
    console.log("Respuesta Android:", response);
  }).catch(error => {
    badge.textContent = "⚠ Extensión activa";
    console.error("Native Messaging:", error);
  });
})();
