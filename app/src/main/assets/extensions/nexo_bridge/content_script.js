(() => {
  "use strict";

  let port = null;
  let backgroundMedia = true;
  let visibilityHandler = null;
  let spoofed = false;
  let pipVideo = null;
  let pipAncestors = [];
  let reportTimer = 0;

  function pageDocument() {
    try {
      return document.wrappedJSObject || document;
    } catch (_) {
      return document;
    }
  }

  function makePageFunction(fn) {
    try {
      if (typeof exportFunction === "function") {
        return exportFunction(
          fn,
          window.wrappedJSObject || window
        );
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
      if (backgroundMedia) {
        event.stopImmediatePropagation();
      }
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
  }

  function applyState(state) {
    if (!state) return;
    backgroundMedia = state.backgroundMedia !== false;
    if (backgroundMedia) enableVisibilitySpoof();
    else disableVisibilitySpoof();
  }

  enableVisibilitySpoof();

  function collectVideos(root, output) {
    if (!root) return output;

    try {
      if (root.querySelectorAll) {
        for (const video of root.querySelectorAll("video")) {
          if (!output.includes(video)) output.push(video);
        }

        for (const element of root.querySelectorAll("*")) {
          if (element.shadowRoot) {
            collectVideos(element.shadowRoot, output);
          }
        }
      }
    } catch (_) {}

    return output;
  }

  function visibleEnough(video) {
    try {
      const rect = video.getBoundingClientRect();
      const style = getComputedStyle(video);
      return (
        rect.width >= 80 &&
        rect.height >= 45 &&
        style.display !== "none" &&
        style.visibility !== "hidden"
      );
    } catch (_) {
      return true;
    }
  }

  function videoArea(video) {
    return (
      Number(video.videoWidth || video.clientWidth || 0) *
      Number(video.videoHeight || video.clientHeight || 0)
    );
  }

  function videoList() {
    return collectVideos(document, []).filter(visibleEnough);
  }

  function bestVideo(preferPlaying) {
    const videos = videoList();
    if (!videos.length) return null;

    const playing = videos.filter(
      video => !video.paused && !video.ended
    );

    const candidates =
      preferPlaying && playing.length
        ? playing
        : videos;

    return candidates
      .slice()
      .sort((a, b) => videoArea(b) - videoArea(a))[0]
      || null;
  }

  function ensurePipStyle() {
    if (document.getElementById("nexo-pip-style")) {
      return;
    }

    const style = document.createElement("style");
    style.id = "nexo-pip-style";
    style.textContent = `
      html.nexo-pip-root,
      html.nexo-pip-root body {
        background: #000 !important;
        overflow: hidden !important;
      }

      html.nexo-pip-root .nexo-pip-ancestor {
        position: relative !important;
        z-index: 2147483646 !important;
        overflow: visible !important;
        clip: auto !important;
      }

      html.nexo-pip-root video.nexo-pip-video {
        position: fixed !important;
        inset: 0 !important;
        width: 100vw !important;
        height: 100vh !important;
        max-width: none !important;
        max-height: none !important;
        object-fit: contain !important;
        z-index: 2147483647 !important;
        background: #000 !important;
        margin: 0 !important;
        padding: 0 !important;
      }
    `;

    (document.head || document.documentElement)
      .appendChild(style);
  }

  function clearPipIsolation() {
    document.documentElement.classList
      .remove("nexo-pip-root");

    if (pipVideo) {
      pipVideo.classList.remove("nexo-pip-video");
    }

    for (const ancestor of pipAncestors) {
      ancestor.classList.remove("nexo-pip-ancestor");
    }

    pipVideo = null;
    pipAncestors = [];
  }

  function setPipIsolation(active) {
    if (!active) {
      clearPipIsolation();
      return;
    }

    const video = bestVideo(true) || bestVideo(false);
    if (!video) return;

    clearPipIsolation();
    ensurePipStyle();

    pipVideo = video;
    let current = video.parentElement;

    while (
      current &&
      current !== document.body &&
      current !== document.documentElement
    ) {
      current.classList.add("nexo-pip-ancestor");
      pipAncestors.push(current);
      current = current.parentElement;
    }

    document.documentElement.classList
      .add("nexo-pip-root");

    video.classList.add("nexo-pip-video");
  }

  function reportVideoState() {
    if (!port) return;

    const anyVideo = bestVideo(false);
    const playingVideo = bestVideo(true);
    const best = playingVideo || anyVideo;

    try {
      port.postMessage({
        type: "video_state",
        present: !!anyVideo,
        playing:
          !!playingVideo &&
          !playingVideo.paused &&
          !playingVideo.ended,
        width: best
          ? Number(best.videoWidth || best.clientWidth || 0)
          : 0,
        height: best
          ? Number(best.videoHeight || best.clientHeight || 0)
          : 0
      });
    } catch (_) {}
  }

  function scheduleVideoReport() {
    clearTimeout(reportTimer);
    reportTimer = setTimeout(reportVideoState, 100);
  }

  [
    "play",
    "playing",
    "pause",
    "ended",
    "emptied",
    "loadedmetadata",
    "canplay",
    "waiting"
  ].forEach(type => {
    document.addEventListener(
      type,
      scheduleVideoReport,
      true
    );
  });

  try {
    const observer = new MutationObserver(
      scheduleVideoReport
    );

    observer.observe(
      document.documentElement,
      {
        childList: true,
        subtree: true
      }
    );
  } catch (_) {}

  // YouTube y otras SPA pueden reemplazar el player
  // sin recargar el documento.
  setInterval(reportVideoState, 900);

  browser.runtime.sendNativeMessage(
    "browser",
    {
      type: "page_loaded",
      url: location.href,
      title: document.title
    }
  ).then(applyState).catch(() => {});

  try {
    port = browser.runtime.connectNative("browser");

    port.onMessage.addListener(message => {
      if (!message) return;

      if (message.type === "browser_state") {
        applyState(message);
      }

      if (message.type === "pip_mode") {
        setPipIsolation(message.active === true);
      }
    });

    port.postMessage({
      type: "content_script_ready",
      url: location.href
    });

    scheduleVideoReport();
  } catch (_) {}
})();
