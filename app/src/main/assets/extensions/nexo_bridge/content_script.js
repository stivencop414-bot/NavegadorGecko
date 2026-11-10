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
  // Si una página no usa Shadow DOM, espaciar los escaneos (hasta 1 min).
  const SHADOW_SCAN_MAX_INTERVAL_MS = 60000;
  const HEARTBEAT_MS = 10000;
  const RECONNECT_MIN_MS = 500;
  const RECONNECT_MAX_MS = 30000;

  // Algunos sitios pausan el video al redimensionarlo, cambiar anuncios o
  // renovar el reproductor. Limitar la recuperación por ventana móvil.
  const PIP_RESUME_WINDOW_MS = 10000;
  const PIP_MAX_AUTO_RESUMES = 3;

  let port = null;
  let reconnectTimer = 0;
  let reconnectDelay = RECONNECT_MIN_MS;
  let backgroundMedia = true;
  let visibilityHandler = null;
  let pipVideo = null;
  let pipAncestors = [];
  let pipActive = false;
  let pipResumeAttempts = [];
  let pipResumeTimer = 0;
  let pipShouldResume = false;
  let pipUserPaused = false;
  let reportTimer = 0;
  let lastStateKey = "";
  let lastReportAt = 0;
  let lastShadowScanAt = 0;
  let shadowScanInterval = SHADOW_SCAN_INTERVAL_MS;
  let shadowVideos = [];
  let videoKnown = false;

  function installVisibilityGuard() {
    if (visibilityHandler) return;

    visibilityHandler = event => {
      // Nexo necesita reevaluar el estado al volver a primer plano.
      scheduleVideoReport();

      /*
       * PiP continúa visible para el usuario. Segundo plano solo se
       * protege si la opción está activa Y algo se está reproduciendo:
       * ocultar el evento a todas las páginas las mantenía animando y
       * consumiendo batería en segundo plano.
       * No llamamos play() aquí: Gecko mantiene el estado del decoder.
       */
      if (pipActive || (backgroundMedia && mediaPlaying())) {
        event.stopImmediatePropagation();
      }
    };

    window.addEventListener("visibilitychange", visibilityHandler, true);
    document.addEventListener("visibilitychange", visibilityHandler, true);
  }

  function mediaPlaying() {
    try {
      if (navigator.mediaSession && navigator.mediaSession.playbackState === "playing") return true;
    } catch (_) {}
    try {
      for (const media of document.querySelectorAll("video, audio")) {
        if (!media.paused && !media.ended) return true;
      }
    } catch (_) {}
    return collectVideos().some(isPlaying);
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

    // El recorrido completo del DOM es caro: hacerlo con poca frecuencia
    // y cada vez menos si la página no tiene videos en Shadow DOM.
    const now = Date.now();
    if (now - lastShadowScanAt >= shadowScanInterval) {
      lastShadowScanAt = now;
      const found = [];
      collectShadowVideos(document, found, 0);
      shadowVideos = found;
      shadowScanInterval = found.length
        ? SHADOW_SCAN_INTERVAL_MS
        : Math.min(shadowScanInterval * 2, SHADOW_SCAN_MAX_INTERVAL_MS);
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
      // Los sitios pueden conservar el elemento anterior al cambiar de anuncio/video.
      return isPlaying(pipVideo) || pipUserPaused
        ? pipVideo
        : bestVideo(all.filter(hasMedia), true) || pipVideo;
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
    if (!pipActive || pipUserPaused || !pipShouldResume || pipResumeTimer) return;
    const video = pipVideo;
    if (!video || !video.isConnected || !video.paused || video.ended || video.error) return;

    // Presupuesto móvil: recuperar pausas tardías sin entrar en un bucle de play().
    const now = Date.now();
    pipResumeAttempts = pipResumeAttempts.filter(time => now - time < PIP_RESUME_WINDOW_MS);
    if (pipResumeAttempts.length >= PIP_MAX_AUTO_RESUMES) return;
    pipResumeTimer = setTimeout(() => {
      pipResumeTimer = 0;
      if (pipActive && !pipUserPaused && pipShouldResume && video === pipVideo &&
          video.isConnected && video.paused && !video.ended && !video.error) {
        if (pickPipVideo() !== video) {
          refreshPipIsolation();
          return;
        }
        pipResumeAttempts.push(Date.now());
        video.play().catch(() => {});
      }
    }, 150);
  }

  function clearPipIsolation() {
    removePipMask();
    clearTimeout(pipResumeTimer);
    pipResumeTimer = 0;
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

  function ancestorsFor(video) {
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

    return ancestors;
  }

  function isolate(video) {
    const ancestors = ancestorsFor(video);
    if (video === pipVideo &&
        ancestors.length === pipAncestors.length &&
        ancestors.every((ancestor, index) => ancestor === pipAncestors[index]) &&
        video.isConnected &&
        pipAncestors.every(a => a.isConnected && a.classList.contains("nexo-pip-ancestor")) &&
        video.classList.contains("nexo-pip-video")) {
      // YouTube y otros reescriben className del <html>: volver a ponerla.
      document.documentElement.classList.add("nexo-pip-root");
      return;
    }

    clearPipIsolation();
    pipVideo = video;
    pipAncestors = ancestors;
    for (const ancestor of pipAncestors) ancestor.classList.add("nexo-pip-ancestor");
    video.classList.add("nexo-pip-video");
    video.addEventListener("pause", onPipVideoPause, true);
    document.documentElement.classList.add("nexo-pip-root");
  }

  /*
   * isolateCss=false: Nexo hace zoom nativo sobre el rectángulo del video y la
   * página no se modifica ni se redimensiona. Solo se vigilan las pausas.
   */
  let pipIsolateCss = true;

  function trackWithoutIsolation(video) {
    if (pipVideo !== video) {
      clearPipIsolation();
      pipVideo = video;
      video.addEventListener("pause", onPipVideoPause, true);
    }
    updatePipMask();
  }

  /*
   * Máscara negra con un hueco exacto sobre el video (modo recorte nativo).
   * Android dibuja la ventana PiP con un margen para la sombra; sin máscara,
   * en ese margen asomaba el encabezado del sitio. Es un elemento fijo y
   * transparente en el centro: no cambia el layout ni el tamaño de la página.
   */
  let pipMask = null;

  function updatePipMask() {
    if (!pipActive || pipIsolateCss || !pipVideo || !pipVideo.isConnected) {
      removePipMask();
      return;
    }
    let r;
    try { r = pipVideo.getBoundingClientRect(); } catch (_) { return; }

    if (!pipMask || !pipMask.isConnected) {
      pipMask = document.createElement("div");
      pipMask.setAttribute("aria-hidden", "true");
      document.documentElement.appendChild(pipMask);
    }
    const set = (name, value) => pipMask.style.setProperty(name, value, "important");
    set("all", "initial");
    set("position", "fixed");
    set("left", `${r.left}px`);
    set("top", `${r.top}px`);
    set("width", `${r.width}px`);
    set("height", `${r.height}px`);
    set("box-shadow", "0 0 0 100vmax #000");
    set("background", "transparent");
    set("pointer-events", "none");
    set("z-index", "2147483647");
    set("display", "block");
  }

  function removePipMask() {
    if (pipMask) {
      try { pipMask.remove(); } catch (_) {}
      pipMask = null;
    }
  }

  function setPipIsolation(active, resume, isolateCss) {
    const wasActive = pipActive;
    pipActive = active === true;
    if(document.documentElement && document.documentElement.setAttribute) document.documentElement.setAttribute("data-nexo-pip-active",String(pipActive));
    if(typeof Event === "function")document.dispatchEvent(new Event("nexo:pip-state"));
    if (pipActive) pipIsolateCss = isolateCss !== false;

    if (!pipActive) {
      clearPipIsolation();
      pipShouldResume = false;
      pipUserPaused = false;
      return;
    }

    if (!wasActive) {
      pipResumeAttempts = [];
      pipUserPaused = false;
      pipShouldResume = resume === true;
    }

    const video = pickPipVideo();
    if (!video) return;

    if (!wasActive || resume) {
      pipShouldResume = pipShouldResume || resume === true || isPlaying(video);
    }

    if (pipIsolateCss) isolate(video); else trackWithoutIsolation(video);

    // Si el sitio ya pausó el video durante la transición, reanudarlo.
    if (pipShouldResume && video.paused && !video.ended) onPipVideoPause();
  }

  function refreshPipIsolation() {
    if (!pipActive) return;
    const video = pickPipVideo();
    if (video) {
      if (pipIsolateCss) isolate(video); else trackWithoutIsolation(video);
      onPipVideoPause();
    }
  }

  function pipCommand(command) {
    const video = pipVideo || pickPipVideo();
    if (!video) return;

    if (command === "pause" || (command === "toggle" && !video.paused)) {
      pipUserPaused = true;
      pipShouldResume = false;
      clearTimeout(pipResumeTimer);
      pipResumeTimer = 0;
      video.pause();
    } else if (command === "play" || command === "toggle") {
      pipUserPaused = false;
      pipShouldResume = true;
      pipResumeAttempts = [];
      video.play().catch(() => {});
    } else if (command === "seek_backward" || command === "seek_forward") {
      // En directo, limitar el salto a la ventana realmente disponible.
      if (video.seekable.length) {
        const start = video.seekable.start(0);
        const end = video.seekable.end(video.seekable.length - 1);
        const delta = command === "seek_backward" ? -10 : 10;
        try { video.currentTime = Math.max(start, Math.min(end, video.currentTime + delta)); } catch (_) {}
      }
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

    const rect = best ? screenRect(best) : null;
    const state = {
      type: "video_state",
      present: !!anyVideo,
      playing: !!playingVideo,
      width: best ? Number(best.videoWidth || best.clientWidth || 0) : 0,
      height: best ? Number(best.videoHeight || best.clientHeight || 0) : 0,
      // Rectángulo del video en píxeles de la vista (para el mini video nativo).
      rx: rect ? rect.x : 0,
      ry: rect ? rect.y : 0,
      rw: rect ? rect.w : 0,
      rh: rect ? rect.h : 0
    };

    const key = `${state.present}|${state.playing}|${state.width}|${state.height}|` +
      `${state.rx}|${state.ry}|${state.rw}|${state.rh}`;
    const now = Date.now();

    if (key === lastStateKey && now - lastReportAt < HEARTBEAT_MS) return;

    lastStateKey = key;
    lastReportAt = now;
    videoKnown = state.present;

    try {
      port.postMessage(state);
    } catch (_) {}
  }

  function screenRect(video) {
    try {
      const r = video.getBoundingClientRect();
      const vv = window.visualViewport;
      const zoom = vv ? vv.scale : 1;
      const ox = vv ? vv.offsetLeft : 0;
      const oy = vv ? vv.offsetTop : 0;
      const dpr = window.devicePixelRatio || 1;
      return {
        x: Math.round((r.left - ox) * zoom * dpr),
        y: Math.round((r.top - oy) * zoom * dpr),
        w: Math.round(r.width * zoom * dpr),
        h: Math.round(r.height * zoom * dpr)
      };
    } catch (_) {
      return null;
    }
  }

  function scheduleVideoReport() {
    if (reportTimer) return;
    reportTimer = setTimeout(() => {
      reportTimer = 0;
      reportVideoState();
    }, REPORT_DEBOUNCE_MS);
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

  // El desplazamiento solo importa si hay un video (su rectángulo cambia).
  window.addEventListener("scroll", () => {
    if (videoKnown || pipActive) scheduleVideoReport();
  }, { passive: true, capture: true });

  /*
   * Sin MutationObserver sobre todo el documento: en páginas dinámicas se
   * disparaba con cada cambio del DOM. Los eventos multimedia (capturados
   * arriba) y el sondeo cada 2 s bastan para detectar videos nuevos.
   */

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
      setPipIsolation(message.active === true, message.resume === true, message.isolate !== false);
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
