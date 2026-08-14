(() => {
  "use strict";

  let port = null;
  let backgroundMedia = true;
  let visibilityHandler = null;
  let pipVideo = null;
  let pipAncestors = [];
  let pipActive = false;
  let reportTimer = 0;

  function installVisibilityGuard() {
    if (visibilityHandler) return;

    visibilityHandler = event => {
      /*
       * PiP continúa visible para el usuario. Segundo plano
       * solo se protege si la opción correspondiente está activa.
       *
       * No llamamos play() aquí: dejamos al decoder/MediaSession
       * de Gecko mantener su estado sin carreras.
       */
      if (pipActive || backgroundMedia) {
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

  function applyState(state) {
    if (!state) return;
    backgroundMedia = state.backgroundMedia !== false;
    installVisibilityGuard();
  }

  installVisibilityGuard();

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
      video =>
        !video.paused &&
        !video.ended
    );

    if (preferPlaying && !playing.length) {
      return null;
    }

    const candidates =
      preferPlaying
        ? playing
        : videos;

    return (
      candidates
        .slice()
        .sort(
          (a, b) =>
            videoArea(b) -
            videoArea(a)
        )[0] || null
    );
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

      /*
       * Solo neutralizamos propiedades que pueden crear
       * containing blocks. No cambiamos position/z-index
       * de la jerarquía de YouTube porque eso puede romper
       * su compositor de video.
       */
      html.nexo-pip-root .nexo-pip-ancestor {
        transform: none !important;
        contain: none !important;
        will-change: auto !important;
        filter: none !important;
        perspective: none !important;
        backdrop-filter: none !important;
        overflow: visible !important;
      }

      html.nexo-pip-root video.nexo-pip-video {
        position: fixed !important;
        inset: 0 !important;
        width: 100vw !important;
        height: 100vh !important;
        max-width: 100vw !important;
        max-height: 100vh !important;
        object-fit: contain !important;
        object-position: center center !important;
        z-index: 2147483647 !important;
        background: #000 !important;
        margin: 0 !important;
        padding: 0 !important;
        transform: none !important;
        opacity: 1 !important;
        visibility: visible !important;
      }
    `;

    (
      document.head ||
      document.documentElement
    ).appendChild(style);
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
    pipActive = active === true;

    if (!pipActive) {
      clearPipIsolation();
      return;
    }

    const video =
      bestVideo(true) ||
      bestVideo(false);

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

  function refreshPipIsolation() {
    if (!pipActive) return;

    const video =
      bestVideo(true) ||
      bestVideo(false);

    if (!video) return;

    const currentStillValid =
      pipVideo === video &&
      video.isConnected &&
      pipAncestors.every(
        ancestor =>
          ancestor.isConnected &&
          ancestor.classList.contains(
            "nexo-pip-ancestor"
          )
      );

    if (!currentStillValid) {
      setPipIsolation(true);
    }
  }

  function reportVideoState() {
    refreshPipIsolation();

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
        120
      );
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
    const observer =
      new MutationObserver(
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

  setInterval(
    reportVideoState,
    1200
  );

  browser.runtime.sendNativeMessage(
    "browser",
    {
      type: "page_loaded",
      url: location.href,
      title: document.title
    }
  ).then(applyState).catch(() => {});

  try {
    port =
      browser.runtime
        .connectNative("browser");

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
