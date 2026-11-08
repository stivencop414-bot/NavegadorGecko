(() => {
  "use strict";

  /*
   * Puente interno de Nexo: detecta video HTML5 para el mini
   * reproductor (PiP) y protege la reproducción en segundo plano.
   *
   * Privacidad: nunca envía URL, título ni contenido de la página.
   * Rendimiento: no trabaja en pestañas ocultas (salvo en PiP), limita el
   * escaneo de Shadow DOM y solo informa cuando el estado cambia.
   */

  const REPORT_DEBOUNCE_MS = 250;
  const POLL_INTERVAL_MS = 2000;
  const SHADOW_SCAN_INTERVAL_MS = 5000;
  const HEARTBEAT_MS = 10000;

  let port = null;
  let backgroundMedia = true;
  let visibilityHandler = null;
  let pipVideo = null;
  let pipAncestors = [];
  let pipActive = false;
  let reportTimer = 0;
  let lastStateKey = "";
  let lastReportAt = 0;
  let lastShadowScanAt = 0;
  let shadowVideos = [];

  function installVisibilityGuard() {
    if (visibilityHandler) return;

    visibilityHandler = event => {
      // Nexo necesita reevaluar el estado al volver a primer plano.
      scheduleVideoReport();

      /*
       * PiP continúa visible para el usuario. Segundo plano
       * solo se protege si la opción correspondiente está activa.
       * No llamamos play() aquí: Gecko mantiene el estado del decoder.
       */
      if (pipActive || backgroundMedia) {
        event.stopImmediatePropagation();
      }
    };

    window.addEventListener("visibilitychange", visibilityHandler, true);
    document.addEventListener("visibilitychange", visibilityHandler, true);
  }

  function applyState(state) {
    if (!state || typeof state !== "object") return;
    backgroundMedia = state.backgroundMedia !== false;
  }

  installVisibilityGuard();

  function collectShadowVideos(root, output, depth) {
    if (!root || depth > 8) return;

    try {
      for (const element of root.querySelectorAll("*")) {
        const shadow = element.shadowRoot;
        if (!shadow) continue;

        for (const video of shadow.querySelectorAll("video")) {
          if (!output.includes(video)) output.push(video);
        }
        collectShadowVideos(shadow, output, depth + 1);
      }
    } catch (_) {}
  }

  function collectVideos() {
    const videos = Array.from(document.getElementsByTagName("video"));
    if (videos.length) return videos;

    // El recorrido completo del DOM es caro: hacerlo con poca frecuencia.
    const now = Date.now();
    if (now - lastShadowScanAt >= SHADOW_SCAN_INTERVAL_MS) {
      lastShadowScanAt = now;
      const found = [];
      collectShadowVideos(document, found, 0);
      shadowVideos = found;
    }

    return shadowVideos.filter(video => video.isConnected);
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

  function isPlaying(video) {
    return !video.paused && !video.ended;
  }

  function bestVideo(videos, preferPlaying) {
    const candidates = preferPlaying ? videos.filter(isPlaying) : videos;
    if (!candidates.length) return null;

    return candidates
      .slice()
      .sort((a, b) => videoArea(b) - videoArea(a))[0] || null;
  }

  function ensurePipStyle() {
    if (document.getElementById("nexo-pip-style")) return;

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
       * de la jerarquía del sitio porque eso puede romper
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

    (document.head || document.documentElement).appendChild(style);
  }

  function clearPipIsolation() {
    document.documentElement.classList.remove("nexo-pip-root");

    if (pipVideo) pipVideo.classList.remove("nexo-pip-video");

    for (const ancestor of pipAncestors) {
      ancestor.classList.remove("nexo-pip-ancestor");
    }

    pipVideo = null;
    pipAncestors = [];
  }

  function pickPipVideo() {
    const videos = collectVideos().filter(visibleEnough);
    return bestVideo(videos, true) || bestVideo(videos, false);
  }

  function setPipIsolation(active) {
    pipActive = active === true;

    if (!pipActive) {
      clearPipIsolation();
      return;
    }

    const video = pickPipVideo();
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

    document.documentElement.classList.add("nexo-pip-root");
    video.classList.add("nexo-pip-video");
  }

  function refreshPipIsolation() {
    if (!pipActive) return;

    const video = pickPipVideo();
    if (!video) return;

    const currentStillValid =
      pipVideo === video &&
      video.isConnected &&
      pipAncestors.every(
        ancestor =>
          ancestor.isConnected &&
          ancestor.classList.contains("nexo-pip-ancestor")
      );

    if (!currentStillValid) setPipIsolation(true);
  }

  function reportVideoState() {
    refreshPipIsolation();

    if (!port) return;

    // Una pestaña oculta (en segundo plano) no necesita escanear el DOM.
    if (document.visibilityState === "hidden" && !pipActive) return;

    const videos = collectVideos().filter(visibleEnough);
    const anyVideo = bestVideo(videos, false);
    const playingVideo = bestVideo(videos, true);
    const best = playingVideo || anyVideo;

    const state = {
      type: "video_state",
      present: !!anyVideo,
      playing: !!playingVideo,
      width: best ? Number(best.videoWidth || best.clientWidth || 0) : 0,
      height: best ? Number(best.videoHeight || best.clientHeight || 0) : 0
    };

    const key = `${state.present}|${state.playing}|${state.width}|${state.height}`;
    const now = Date.now();

    if (key === lastStateKey && now - lastReportAt < HEARTBEAT_MS) return;

    lastStateKey = key;
    lastReportAt = now;

    try {
      port.postMessage(state);
    } catch (_) {}
  }

  function scheduleVideoReport() {
    clearTimeout(reportTimer);
    reportTimer = setTimeout(reportVideoState, REPORT_DEBOUNCE_MS);
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
    document.addEventListener(type, scheduleVideoReport, true);
  });

  try {
    const observer = new MutationObserver(scheduleVideoReport);
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true
    });
  } catch (_) {}

  setInterval(reportVideoState, POLL_INTERVAL_MS);

  browser.runtime
    .sendNativeMessage("browser", { type: "get_state" })
    .then(applyState)
    .catch(() => {});

  try {
    port = browser.runtime.connectNative("browser");

    port.onMessage.addListener(message => {
      if (!message || typeof message !== "object") return;

      if (message.type === "browser_state") {
        applyState(message);
      }

      if (message.type === "pip_mode") {
        setPipIsolation(message.active === true);
        // Forzar un informe inmediato tras entrar/salir de PiP.
        lastStateKey = "";
        scheduleVideoReport();
      }
    });

    port.postMessage({ type: "content_script_ready" });

    scheduleVideoReport();
  } catch (_) {}
})();
