(() => {
  "use strict";

  /*
   * Puente interno de Nexo: detecta video HTML5 para el mini
   * reproductor (PiP) y protege la reproducción en segundo plano.
   *
   * Privacidad: nunca envía URL, título ni contenido de la página.
   * Rendimiento: no trabaja en pestañas ocultas (salvo en PiP), limita el
   * escaneo de Shadow DOM y solo informa cuando el estado cambia.
   *
   * El aspecto del mini video vive en pip.css (declarado en el manifest),
   * aquí solo se marcan el video y sus ancestros con clases.
   */

  const REPORT_DEBOUNCE_MS = 250;
  const POLL_INTERVAL_MS = 2000;
  const SHADOW_SCAN_INTERVAL_MS = 5000;
  const HEARTBEAT_MS = 10000;
  const RECONNECT_MIN_MS = 500;
  const RECONNECT_MAX_MS = 30000;

  // Algunos sitios (YouTube) pausan el video al cambiar el tamaño de la
  // ventana. Durante esta ventana tras entrar en PiP se reanuda solo.
  const PIP_RESUME_WINDOW_MS = 6000;
  const PIP_MAX_AUTO_RESUMES = 3;

  let port = null;
  let reconnectTimer = 0;
  let reconnectDelay = RECONNECT_MIN_MS;
  let backgroundMedia = true;
  let visibilityHandler = null;
  let pipVideo = null;
  let pipAncestors = [];
  let pipActive = false;
  let pipStartedAt = 0;
  let pipShouldResume = false;
  let pipAutoResumes = 0;
  let pipUserPaused = false;
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

  // ---------------------------------------------------------------
  // Búsqueda de video
  // ---------------------------------------------------------------

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

  function hasMedia(video) {
    return video.readyState > 0 || !!video.currentSrc;
  }

  function bestVideo(videos, preferPlaying) {
    const candidates = preferPlaying ? videos.filter(isPlaying) : videos;
    if (!candidates.length) return null;

    return candidates
      .slice()
      .sort((a, b) => videoArea(b) - videoArea(a))[0] || null;
  }

  function pickPipVideo() {
    const all = collectVideos();
    const visible = all.filter(visibleEnough);

    // Si el video actual sigue siendo válido, no saltar a otro.
    if (pipVideo && pipVideo.isConnected && all.includes(pipVideo) && hasMedia(pipVideo)) {
      return pipVideo;
    }

    // En la ventana pequeña de PiP el sitio puede encoger u ocultar el
    // reproductor: si ninguno es "visible", usar cualquiera con medios.
    const withMedia = all.filter(hasMedia);
    return (
      bestVideo(visible, true) ||
      bestVideo(withMedia, true) ||
      bestVideo(visible, false) ||
      bestVideo(withMedia, false)
    );
  }

  // ---------------------------------------------------------------
  // Mini video (PiP)
  // ---------------------------------------------------------------

  function onPipVideoPause() {
    if (!pipActive || pipUserPaused || !pipShouldResume) return;
    if (Date.now() - pipStartedAt > PIP_RESUME_WINDOW_MS) return;
    if (pipAutoResumes >= PIP_MAX_AUTO_RESUMES) return;

    const video = pipVideo;
    if (!video) return;

    pipAutoResumes++;
    setTimeout(() => {
      if (pipActive && !pipUserPaused && video === pipVideo && video.paused && !video.ended) {
        video.play().catch(() => {});
      }
    }, 150);
  }

  function clearPipIsolation() {
    document.documentElement.classList.remove("nexo-pip-root");

    if (pipVideo) {
      pipVideo.classList.remove("nexo-pip-video");
      pipVideo.removeEventListener("pause", onPipVideoPause, true);
    }

    for (const ancestor of pipAncestors) {
      ancestor.classList.remove("nexo-pip-ancestor");
    }

    pipVideo = null;
    pipAncestors = [];
  }

  function markAncestors(video) {
    const ancestors = [];
    let current = video.parentElement;

    while (current) {
      if (current === document.body || current === document.documentElement) break;
      ancestors.push(current);

      if (current.parentElement) {
        current = current.parentElement;
      } else {
        // Salir de un Shadow DOM hacia su elemento anfitrión.
        const root = current.getRootNode && current.getRootNode();
        current = root && root.host ? root.host : null;
      }
    }

    for (const ancestor of ancestors) ancestor.classList.add("nexo-pip-ancestor");
    return ancestors;
  }

  function isolate(video) {
    if (video === pipVideo &&
        video.isConnected &&
        pipAncestors.every(a => a.isConnected && a.classList.contains("nexo-pip-ancestor")) &&
        video.classList.contains("nexo-pip-video")) {
      return;
    }

    clearPipIsolation();
    pipVideo = video;
    pipAncestors = markAncestors(video);
    video.classList.add("nexo-pip-video");
    video.addEventListener("pause", onPipVideoPause, true);
    document.documentElement.classList.add("nexo-pip-root");
  }

  function setPipIsolation(active, resume) {
    const wasActive = pipActive;
    pipActive = active === true;

    if (!pipActive) {
      clearPipIsolation();
      pipShouldResume = false;
      pipUserPaused = false;
      return;
    }

    if (!wasActive) {
      pipStartedAt = Date.now();
      pipAutoResumes = 0;
      pipUserPaused = false;
      pipShouldResume = false;
    }

    const video = pickPipVideo();
    if (!video) return;

    if (!wasActive || resume) {
      pipShouldResume = pipShouldResume || resume === true || isPlaying(video);
    }

    isolate(video);

    // Si el sitio ya pausó el video durante la transición, reanudarlo.
    if (pipShouldResume && video.paused && !video.ended) onPipVideoPause();
  }

  function refreshPipIsolation() {
    if (!pipActive) return;
    const video = pickPipVideo();
    if (video) isolate(video);
  }

  function pipCommand(command) {
    const video = pipVideo || pickPipVideo();
    if (!video) return;

    if (command === "pause" || (command === "toggle" && !video.paused)) {
      pipUserPaused = true;
      video.pause();
    } else if (command === "play" || command === "toggle") {
      pipUserPaused = false;
      video.play().catch(() => {});
    }
    lastStateKey = "";
    scheduleVideoReport();
  }

  // ---------------------------------------------------------------
  // Estado hacia Nexo
  // ---------------------------------------------------------------

  function reportVideoState() {
    refreshPipIsolation();

    if (!port) return;

    // Una pestaña oculta (en segundo plano) no necesita escanear el DOM.
    if (document.visibilityState === "hidden" && !pipActive) return;

    const videos = collectVideos().filter(visibleEnough);
    const anyVideo = pipActive ? (pipVideo || bestVideo(videos, false)) : bestVideo(videos, false);
    const playingVideo = pipActive
      ? (pipVideo && isPlaying(pipVideo) ? pipVideo : null)
      : bestVideo(videos, true);
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

  // ---------------------------------------------------------------
  // Conexión con Nexo (con reconexión)
  // ---------------------------------------------------------------

  function onNativeMessage(message) {
    if (!message || typeof message !== "object") return;

    // Un mensaje recibido confirma que la conexión funciona.
    reconnectDelay = RECONNECT_MIN_MS;

    if (message.type === "browser_state") {
      applyState(message);
    }

    if (message.type === "pip_mode") {
      setPipIsolation(message.active === true, message.resume === true);
      // Forzar un informe inmediato tras entrar/salir de PiP.
      lastStateKey = "";
      scheduleVideoReport();
    }

    if (message.type === "pip_command" && typeof message.command === "string") {
      pipCommand(message.command);
    }
  }

  function scheduleReconnect() {
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(connect, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS);
  }

  /*
   * Si la página carga antes de que Nexo registre el puente (por ejemplo,
   * la pestaña restaurada al abrir la app), la primera conexión falla. En
   * sitios de una sola página como YouTube el script vive mucho tiempo, así
   * que sin reconexión el mini video nunca funcionaría en esa pestaña.
   */
  function connect() {
    clearTimeout(reconnectTimer);

    let candidate = null;
    try {
      candidate = browser.runtime.connectNative("browser");
    } catch (_) {
      port = null;
      scheduleReconnect();
      return;
    }

    port = candidate;
    lastStateKey = "";

    candidate.onMessage.addListener(onNativeMessage);
    candidate.onDisconnect.addListener(() => {
      if (port === candidate) port = null;
      scheduleReconnect();
    });

    try {
      candidate.postMessage({ type: "content_script_ready" });
    } catch (_) {}

    scheduleVideoReport();
  }

  browser.runtime
    .sendNativeMessage("browser", { type: "get_state" })
    .then(applyState)
    .catch(() => {});

  connect();
})();
