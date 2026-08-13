(() => {
  "use strict";

  let port = null;
  let backgroundMedia = true;
  let visibilityHandler = null;
  let spoofed = false;
  let pipVideo = null;
  let pipAncestors = [];

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
        Reflect.defineProperty(
          target,
          "visibilityState",
          {
            configurable: true,
            get: makePageFunction(() => "visible")
          }
        );
      } catch (_) {}

      try {
        Reflect.defineProperty(
          target,
          "hidden",
          {
            configurable: true,
            get: makePageFunction(() => false)
          }
        );
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

    const pageDoc = pageDocument();

    try {
      delete pageDoc.visibilityState;
    } catch (_) {}

    try {
      delete pageDoc.hidden;
    } catch (_) {}
  }

  function applyState(state) {
    if (!state) return;

    backgroundMedia =
      state.backgroundMedia !== false;

    if (backgroundMedia) {
      enableVisibilitySpoof();
    } else {
      disableVisibilitySpoof();
    }
  }

  enableVisibilitySpoof();

  function bestPlayingVideo() {
    const videos =
      Array.from(
        document.querySelectorAll("video")
      );

    const playing = videos.filter(video =>
      !video.paused &&
      !video.ended &&
      video.readyState >= 2
    );

    let best = null;

    for (const video of playing) {
      const area =
        Number(
          video.videoWidth ||
          video.clientWidth ||
          0
        ) *
        Number(
          video.videoHeight ||
          video.clientHeight ||
          0
        );

      const bestArea = best
        ? Number(
            best.videoWidth ||
            best.clientWidth ||
            0
          ) *
          Number(
            best.videoHeight ||
            best.clientHeight ||
            0
          )
        : -1;

      if (!best || area > bestArea) {
        best = video;
      }
    }

    return best;
  }

  function ensurePipStyle() {
    if (
      document.getElementById(
        "nexo-pip-style"
      )
    ) {
      return;
    }

    const style =
      document.createElement("style");

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

    (document.head ||
      document.documentElement)
      .appendChild(style);
  }

  function setPipIsolation(active) {
    if (!active) {
      document.documentElement.classList
        .remove("nexo-pip-root");

      if (pipVideo) {
        pipVideo.classList
          .remove("nexo-pip-video");
      }

      for (const ancestor of pipAncestors) {
        ancestor.classList
          .remove("nexo-pip-ancestor");
      }

      pipVideo = null;
      pipAncestors = [];
      return;
    }

    const video = bestPlayingVideo();
    if (!video) return;

    ensurePipStyle();

    pipVideo = video;
    pipAncestors = [];

    let current =
      video.parentElement;

    while (
      current &&
      current !== document.body &&
      current !== document.documentElement
    ) {
      current.classList.add(
        "nexo-pip-ancestor"
      );

      pipAncestors.push(current);
      current = current.parentElement;
    }

    document.documentElement.classList
      .add("nexo-pip-root");

    video.classList.add(
      "nexo-pip-video"
    );
  }

  let reportTimer = 0;

  function reportVideoState() {
    if (!port) return;

    const best =
      bestPlayingVideo();

    try {
      port.postMessage({
        type: "video_state",
        playing: !!best,
        width: best
          ? Number(
              best.videoWidth ||
              best.clientWidth ||
              0
            )
          : 0,
        height: best
          ? Number(
              best.videoHeight ||
              best.clientHeight ||
              0
            )
          : 0
      });
    } catch (_) {}
  }

  function scheduleVideoReport() {
    clearTimeout(reportTimer);
    reportTimer =
      setTimeout(
        reportVideoState,
        80
      );
  }

  document.addEventListener(
    "play",
    scheduleVideoReport,
    true
  );

  document.addEventListener(
    "pause",
    scheduleVideoReport,
    true
  );

  document.addEventListener(
    "ended",
    scheduleVideoReport,
    true
  );

  document.addEventListener(
    "emptied",
    scheduleVideoReport,
    true
  );

  browser.runtime.sendNativeMessage(
    "browser",
    {
      type: "page_loaded",
      url: location.href,
      title: document.title
    }
  ).then(applyState)
    .catch(() => {});

  try {
    port =
      browser.runtime.connectNative(
        "browser"
      );

    port.onMessage.addListener(
      message => {
        if (!message) return;

        if (
          message.type ===
          "browser_state"
        ) {
          applyState(message);
        }

        if (
          message.type ===
          "pip_mode"
        ) {
          setPipIsolation(
            message.active === true
          );
        }
      }
    );

    port.postMessage({
      type: "content_script_ready",
      url: location.href
    });

    scheduleVideoReport();
  } catch (_) {}
})();
