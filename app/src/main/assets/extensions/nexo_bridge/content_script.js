(() => {
  "use strict";

  let badge = null;
  const colors = {
    violet: "#815bff", cyan: "#00cfff", lime: "#43dc60",
    orange: "#ff9836", pink: "#ff4f9a", red: "#ff4e4e"
  };

  function removeBadge() {
    if (badge && badge.isConnected) badge.remove();
    badge = null;
  }

  function showBadge(accent) {
    if (badge && badge.isConnected) return;
    badge = document.createElement("div");
    badge.id = "__nexo_bridge_badge";
    badge.textContent = "✓ Nexo Extension Bridge";
    Object.assign(badge.style, {
      position: "fixed", right: "12px", bottom: "12px", zIndex: "2147483647",
      padding: "9px 13px", borderRadius: "12px", background: "#11131bcc",
      color: "#fff", border: `1px solid ${colors[accent] || colors.violet}`,
      backdropFilter: "blur(12px)", font: "600 12px system-ui,sans-serif",
      boxShadow: "0 5px 18px #0008"
    });
    (document.body || document.documentElement).appendChild(badge);
  }

  function applyState(state) {
    if (!state) return;
    if (state.showBadge) showBadge(state.accent);
    else removeBadge();
  }

  browser.runtime.sendNativeMessage("browser", {
    type: "page_loaded",
    url: location.href,
    title: document.title
  }).then(applyState).catch(removeBadge);

  try {
    const port = browser.runtime.connectNative("browser");
    port.onMessage.addListener(message => {
      if (message && message.type === "browser_state") applyState(message);
    });
    port.postMessage({ type: "content_script_ready", url: location.href });
  } catch (_) {}
})();
