(() => {
  "use strict";

  let port = null;
  let backgroundMedia = true;
  let visibilityHandler = null;
  let spoofed = false;

  function pageDocument() {
    try { return document.wrappedJSObject || document; }
    catch (_) { return document; }
  }

  function makePageFunction(fn) {
    try {
      if (typeof exportFunction === "function") {
        return exportFunction(fn, window.wrappedJSObject || window);
      }
    } catch (_) {}
    return fn;
  }

  function enableVisibilitySpoof() {
    if (spoofed) return;
    spoofed = true;
    const pageDoc = pageDocument();
    let pageProto = null;

    try {
      pageProto = Object.getPrototypeOf(pageDoc);
    } catch (_) {}

    const defineVisible = target => {
      if (!target) return;

      try {
        Reflect.defineProperty(target, "visibilityState", {
          configurable: true,
          get: makePageFunction(() => "visible")
        });
      } catch (_) {}

      try {
        Reflect.defineProperty(target, "hidden", {
          configurable: true,
          get: makePageFunction(() => false)
        });
      } catch (_) {}
    };

    defineVisible(pageProto);
    defineVisible(pageDoc);

    visibilityHandler = event => {
      if (backgroundMedia) event.stopImmediatePropagation();
    };

    window.addEventListener(
      "visibilitychange",
      visibilityHandler,
      true
    );
    document.addEventListener(
      "visibilitychange",
      visibilityHandler,
      true
    );
  }

  function disableVisibilitySpoof() {
    if (!spoofed) return;
    spoofed = false;
    if (visibilityHandler) {
      window.removeEventListener(
        "visibilitychange",
        visibilityHandler,
        true
      );
      document.removeEventListener(
        "visibilitychange",
        visibilityHandler,
        true
      );
      visibilityHandler = null;
    }
    const pageDoc = pageDocument();
    try { delete pageDoc.visibilityState; } catch (_) {}
    try { delete pageDoc.hidden; } catch (_) {}
  }

  function applyState(state) {
    if (!state) return;
    backgroundMedia = state.backgroundMedia !== false;
    if (backgroundMedia) enableVisibilitySpoof();
    else disableVisibilitySpoof();
  }

  enableVisibilitySpoof();

  let reportTimer = 0;
  function reportVideoState() {
    if (!port) return;
    const videos = Array.from(document.querySelectorAll("video"));
    const playing = videos.filter(video =>
      !video.paused && !video.ended && video.readyState >= 2
    );

    let best = null;
    for (const video of playing) {
      if (!best ||
          (video.videoWidth * video.videoHeight) >
          (best.videoWidth * best.videoHeight)) {
        best = video;
      }
    }

    try {
      port.postMessage({
        type: "video_state",
        playing: playing.length > 0,
        width: best ? Number(best.videoWidth || 0) : 0,
        height: best ? Number(best.videoHeight || 0) : 0
      });
    } catch (_) {}
  }

  function scheduleVideoReport() {
    clearTimeout(reportTimer);
    reportTimer = setTimeout(reportVideoState, 80);
  }

  document.addEventListener("play", scheduleVideoReport, true);
  document.addEventListener("pause", scheduleVideoReport, true);
  document.addEventListener("ended", scheduleVideoReport, true);
  document.addEventListener("emptied", scheduleVideoReport, true);

  browser.runtime.sendNativeMessage("browser", {
    type: "page_loaded",
    url: location.href,
    title: document.title
  }).then(applyState).catch(() => {});

  try {
    port = browser.runtime.connectNative("browser");
    port.onMessage.addListener(message => {
      if (message && message.type === "browser_state") applyState(message);
    });
    port.postMessage({ type: "content_script_ready", url: location.href });
    scheduleVideoReport();
  } catch (_) {}
})();
