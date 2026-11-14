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
    // Solo los mensajes que traen el modo nocturno lo cambian (otras respuestas parciales no lo apagan).
    if (typeof state.night === "boolean") setNight(state.night, typeof state.nightBackground === "string" ? state.nightBackground : "");
  }

  // ---------------------------------------------------------------
  // Modo nocturno (Dark Reader, cargado bajo demanda)
  // ---------------------------------------------------------------

  /*
   * Con Midnight, OLED o el tema oscuro del sistema, las páginas claras se
   * oscurecen con el motor dinámico de Dark Reader (también contenido que
   * aparece después e iframes: cada marco decide por sí mismo). Las páginas
   * que ya son oscuras se respetan. Imágenes y videos conservan sus colores.
   * El motor solo se carga en el marco que lo necesita; al desactivarlo se
   * retiran sus estilos y observadores.
   */
  const night = { want: false, background: "#0c1222", active: false, loading: null, waiting: false };

  function parseColor(value) {
    const match = /rgba?\(([^)]+)\)/.exec(String(value || ""));
    if (!match) return null;
    const parts = match[1].split(/[ ,/]+/).filter(Boolean).map(Number);
    return { r: parts[0], g: parts[1], b: parts[2], a: parts.length > 3 ? parts[3] : 1 };
  }

  /** La página ya es oscura (fondo oscuro o solo esquema oscuro): no se toca. */
  function pageAlreadyDark() {
    try {
      const meta = document.querySelector && document.querySelector('meta[name="color-scheme"]');
      const scheme = String(meta && meta.content || "").toLowerCase();
      if (/\bdark\b/.test(scheme) && !/\blight\b/.test(scheme)) return true;
      for (const element of [document.body, document.documentElement]) {
        if (!element) continue;
        const color = parseColor(getComputedStyle(element).backgroundColor);
        if (!color || color.a < 0.5) continue;
        const luminance = (0.2126 * color.r + 0.7152 * color.g + 0.0722 * color.b) / 255;
        return luminance < 0.35;
      }
    } catch (_) {}
    return false;
  }

  function darkReaderReady() {
    return typeof DarkReader !== "undefined" && DarkReader && typeof DarkReader.enable === "function";
  }

  function loadDarkReader() {
    if (darkReaderReady()) return Promise.resolve(true);
    if (night.loading) return night.loading;
    let request;
    try {
      request = browser.runtime.sendMessage({ type: "nexo_dark_load" });
    } catch (_) { request = null; }
    night.loading = Promise.resolve(request).then(() => darkReaderReady(), () => false)
      .then(ok => { if (!ok) night.loading = null; return ok; });
    return night.loading;
  }

  function enableNight() {
    if (!night.want || night.active) return;
    if (pageAlreadyDark()) return;
    loadDarkReader().then(ok => {
      if (!ok || !night.want || night.active || pageAlreadyDark()) return;
      try {
        // fetch del script (principal de la extensión): puede leer hojas de estilo de otros
        // orígenes; window.fetch sería el de la página, limitado por CORS.
        DarkReader.setFetchMethod(url => fetch(url, { credentials: "omit" }));
        DarkReader.enable({ brightness: 100, contrast: 95, sepia: 0,
          darkSchemeBackgroundColor: night.background, darkSchemeTextColor: "#e8e6e3" });
        night.active = true;
      } catch (_) {}
    });
  }

  function setNight(want, background) {
    if (/^#[0-9a-f]{6}$/i.test(background)) night.background = background;
    night.want = want;
    if (!want) {
      if (night.active && darkReaderReady()) { try { DarkReader.disable(); } catch (_) {} }
      night.active = false;
      return;
    }
    if (night.active) {
      // Cambió el tema (Midnight ↔ OLED): mismos estilos, otro fondo.
      try { DarkReader.enable({ brightness: 100, contrast: 95, sepia: 0,
        darkSchemeBackgroundColor: night.background, darkSchemeTextColor: "#e8e6e3" }); } catch (_) {}
      return;
    }
    // Los colores de la página solo se conocen con el documento cargado.
    if (document.readyState === "loading") {
      if (!night.waiting) {
        night.waiting = true;
        document.addEventListener("DOMContentLoaded", () => { night.waiting = false; enableNight(); }, { once: true });
      }
    } else enableNight();
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
  // Recuperación al volver a primer plano
  // ---------------------------------------------------------------

  /*
   * Al salir de la app Android destruye la superficie de video y puede
   * liberar el decodificador. Al volver, algunos reproductores (sobre todo
   * los de MSE, como YouTube) quedan con el último cuadro congelado y no
   * avanzan aunque se pulse reproducir. Un salto al mismo instante
   * reconstruye la cadena de decodificación sin recargar la página.
   */
  const RECOVERY_DELAY_MS = 450;
  const RECOVERY_CHECK_MS = 1300;
  const RECOVERY_WINDOW_MS = 20000;
  const pausedAt = new WeakMap();
  let backgroundSnapshot = null;
  let foregroundAt = 0;

  document.addEventListener("pause", event => {
    if (event.target && typeof event.target.pause === "function") pausedAt.set(event.target, Date.now());
  }, true);

  function mediaTime(video) {
    const value = Number(video.currentTime);
    return Number.isFinite(value) ? value : 0;
  }

  function nudge(video, forward) {
    try {
      const time = mediaTime(video) + (forward ? 0.05 : 0);
      if (video.seekable && video.seekable.length) {
        const end = video.seekable.end(video.seekable.length - 1);
        video.currentTime = Math.min(time, Math.max(0, end - 0.05));
      } else {
        video.currentTime = time;
      }
      return true;
    } catch (_) {
      return false;
    }
  }

  function watchProgress(video, attempt) {
    const start = mediaTime(video);
    setTimeout(() => {
      if (!video.isConnected || video.paused || video.ended || video.seeking) return;
      if (mediaTime(video) - start > 0.05) return;
      // Sigue congelado: otro salto y, la segunda vez, reinicio de la reproducción.
      if (attempt === 0) {
        nudge(video, true);
        watchProgress(video, 1);
      } else if (attempt === 1) {
        try {
          video.pause();
          const playing = video.play();
          if (playing && typeof playing.catch === "function") playing.catch(() => {});
        } catch (_) {}
      }
    }, RECOVERY_CHECK_MS);
  }

  function recoverVideo(entry) {
    const video = entry.video;
    if (!video || !video.isConnected || video.ended || !hasMedia(video)) return;
    if (video.paused) {
      // Rehace el cuadro y el decodificador; el siguiente "play" del usuario funciona.
      nudge(video, false);
      if (entry.playing && backgroundMedia && !pipUserPaused) {
        try {
          const playing = video.play();
          if (playing && typeof playing.catch === "function") playing.catch(() => {});
        } catch (_) {}
        watchProgress(video, 0);
      }
      return;
    }
    watchProgress(video, 0);
  }

  function onBackground() {
    const now = Date.now();
    backgroundSnapshot = collectVideos().map(video => ({
      video,
      playing: isPlaying(video) || (!video.ended && now - (pausedAt.get(video) || 0) < 1500)
    }));
  }

  function onForeground() {
    const snapshot = backgroundSnapshot ||
      collectVideos().map(video => ({ video, playing: isPlaying(video) }));
    backgroundSnapshot = null;
    foregroundAt = Date.now();
    if (!snapshot.length) return;
    setTimeout(() => {
      snapshot.forEach(recoverVideo);
      lastStateKey = "";
      scheduleVideoReport();
    }, RECOVERY_DELAY_MS);
  }

  // Si el usuario pulsa reproducir poco después de volver y el video no avanza, repararlo.
  document.addEventListener("play", event => {
    const video = event.target;
    if (!video || typeof video.pause !== "function" || !foregroundAt) return;
    if (Date.now() - foregroundAt > RECOVERY_WINDOW_MS) return;
    watchProgress(video, 0);
  }, true);

  // ---------------------------------------------------------------
  // Subtítulos en vivo: audio del video comprimido hacia Nexo
  // ---------------------------------------------------------------

  /*
   * El audio se toma del propio video con Web Audio (el sonido sigue
   * saliendo igual) y se comprime en Opus a ~24 kbps con el codificador
   * nativo de MediaRecorder: muy poco trabajo para el teléfono. Solo se
   * graba mientras el video se reproduce en primer plano.
   */
  const CAPTION_SLICE_MS = 250;
  const captions = {
    active: false, live: false, lang: "multi", granted: false, choosing: false, token: 0,
    video: null, recorder: null, silentTimer: 0, epoch: 0, stream: null, meter: null,
    track: null, trackMode: "", trackTimer: 0, knownCues: 0,
    dom: false, domTimer: 0, domText: "", domWatch: 0, domChecks: 0, ytEnabled: false,
    restartTimer: 0, capturing: false, waitCancel: null,
    target: "", file: null, fileTimer: 0, fileUrls: 0
  };
  const MAX_CUES = 3000;
  // Identificador estable por línea: los reproductores HLS añaden líneas
  // mientras se ve el video y los índices de la lista se desplazan.
  const cueIds = new WeakMap();
  let nextCueId = 0;

  function sendToNexo(message) {
    if (!port) return false;
    try { port.postMessage(message); return true; } catch (_) { return false; }
  }

  function captionError(code) {
    sendToNexo({ type: "captions_error", code });
  }

  function captionVideo() {
    const videos = collectVideos().filter(video => hasMedia(video));
    return (pipVideo && pipVideo.isConnected ? pipVideo : null) ||
      bestVideo(videos, true) || bestVideo(videos, false);
  }

  function bytesToBase64(buffer) {
    const bytes = new Uint8Array(buffer);
    let binary = "";
    for (let i = 0; i < bytes.length; i += 0x8000) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(binary);
  }

  // Capture a copy of the media tracks. Never reroute the element through Web Audio:
  // cross-origin MediaElementAudioSourceNode is silent by specification.
  function stopRecording(notify) {
    ++captions.epoch;
    captions.waitCancel?.(); captions.waitCancel = null;
    captions.capturing = false;
    const recorder = captions.recorder;
    captions.recorder = null;
    clearTimeout(captions.silentTimer);
    if (recorder) {
      try { recorder.stop(); } catch (_) {}
    }
    for (const track of captions.stream?.getTracks() || []) track.stop();
    captions.stream = null;
    if (captions.meter) {
      try { captions.meter.source.disconnect(); captions.meter.context.close(); } catch (_) {}
      captions.meter = null;
    }
    if (notify && recorder) sendToNexo({ type: "captions_stop" });
  }

  /** Video en silencio por el usuario o por el reproductor (autoplay sin sonido). */
  function videoMuted(video) {
    try { return !!video && (video.muted === true || Number(video.volume) === 0); } catch (_) { return false; }
  }

  /** Nivel máximo del fragmento analizado (0–1). Float si existe: detecta voz baja (~-50 dB). */
  function peakLevel(analyser, floats, bytes) {
    if (floats && typeof analyser.getFloatTimeDomainData === "function") {
      analyser.getFloatTimeDomainData(floats);
      let peak = 0;
      for (const value of floats) { const level = Math.abs(value); if (level > peak) peak = level; }
      return peak;
    }
    analyser.getByteTimeDomainData(bytes);
    let peak = 0;
    for (const value of bytes) { const level = Math.abs(value - 128) / 128; if (level > peak) peak = level; }
    return peak;
  }

  /*
   * Silencio real: solo cuenta mientras el video avanza con sonido. Un video
   * pausado, silenciado o en una intro muda no se da por perdido; si se
   * silencia, se avisa y se espera a que el usuario active el sonido.
   */
  const SILENCE_CHECK_MS = 1500;
  const SILENCE_LIMIT_CHECKS = 10; // ~15 s reproduciendo sin señal
  function watchSilence(stream, recorder) {
    const Context = window.AudioContext || window.webkitAudioContext;
    if (!Context) return;
    try {
      const context = new Context();
      const source = context.createMediaStreamSource(stream);
      const analyser = context.createAnalyser();
      analyser.fftSize = 2048;
      source.connect(analyser); // no connection to speakers; never duplicate audio
      captions.meter = { context, source };
      context.resume().catch(() => {});
      const floats = typeof Float32Array === "function" ? new Float32Array(analyser.fftSize) : null;
      const bytes = new Uint8Array(analyser.fftSize);
      let silentChecks = 0, mutedReported = false, lastTime = -1, heardOnce = false, quietReported = false;
      const check = () => {
        if (captions.recorder !== recorder) return;
        if (context.state !== "running") {
          sendToNexo({ type: "captions_status", code: "meter_unavailable" });
          return;
        }
        const video = captions.video;
        if (videoMuted(video)) {
          if (!mutedReported) { mutedReported = true; sendToNexo({ type: "captions_status", code: "muted" }); }
          silentChecks = 0;
        } else if (peakLevel(analyser, floats, bytes) > 0.003) {
          // La señal se sigue midiendo: solo se avisa al aparecer (o volver tras un silencio).
          if (!heardOnce || quietReported) sendToNexo({ type: "captions_status", code: "signal" });
          heardOnce = true; quietReported = false; silentChecks = 0; mutedReported = false;
        } else {
          mutedReported = false;
          const time = video ? Number(video.currentTime) : 0;
          const advancing = video && !video.paused && !video.ended && time !== lastTime;
          lastTime = time;
          if (advancing) ++silentChecks;
          // Sin haber oído nada nunca: la captura no funciona. Tras oír voz, un silencio largo
          // solo se avisa (pausa en la conversación, anuncio, reproductor cambiado).
          if (!heardOnce && silentChecks >= SILENCE_LIMIT_CHECKS) { captionError("silent"); return; }
          if (heardOnce && !quietReported && silentChecks >= SILENCE_LIMIT_CHECKS * 2) {
            quietReported = true;
            sendToNexo({ type: "captions_status", code: "quiet" });
          }
        }
        captions.silentTimer = setTimeout(check, SILENCE_CHECK_MS);
      };
      captions.silentTimer = setTimeout(check, SILENCE_CHECK_MS);
    } catch (_) { sendToNexo({ type: "captions_status", code: "meter_unavailable" }); }
  }

  // Gecko may populate captureStream tracks after metadata/MSE becomes ready.
  // Wait briefly, but release pending streams immediately when CC is switched off.
  function captureAudioTracks(stream, epoch) {
    return new Promise(resolve => {
      let timer = 0, attempts = 0, done = false;
      const finish = tracks => {
        if (done) return;
        done = true; clearTimeout(timer);
        stream.removeEventListener?.("addtrack", check);
        if (captions.waitCancel === cancel) captions.waitCancel = null;
        resolve(tracks);
      };
      const cancel = () => finish(null);
      const check = () => {
        clearTimeout(timer);
        if (!captions.active || epoch !== captions.epoch) { finish(null); return; }
        const tracks = stream.getAudioTracks();
        if (tracks.length || ++attempts >= 31) { finish(tracks); return; }
        timer = setTimeout(check, 100);
      };
      captions.waitCancel = cancel;
      stream.addEventListener?.("addtrack", check);
      check();
    });
  }

  async function startRecording() {
    if (!captions.active || !captions.granted || !captions.live || captions.choosing || textSource() || captions.recorder || captions.capturing) return;
    const video = captions.video;
    if (!video || !video.isConnected || video.paused || video.ended || document.visibilityState === "hidden") {
      sendToNexo({ type: "captions_status", code: "paused" }); return;
    }
    // Sin sonido no hay nada que transcribir: se espera al "volumechange" en vez de fallar.
    if (videoMuted(video)) { sendToNexo({ type: "captions_status", code: "muted" }); return; }
    const epoch = ++captions.epoch;
    captions.capturing = true;
    try {
      const capture = video.captureStream || video.mozCaptureStream;
      if (!capture || typeof MediaRecorder !== "function") throw new Error("unsupported");
      const mimeType = ["audio/webm;codecs=opus", "audio/ogg;codecs=opus"].find(type => MediaRecorder.isTypeSupported(type));
      if (!mimeType) throw new Error("mime");
      const captured = capture.call(video);
      captions.stream = captured;
      const tracks = await captureAudioTracks(captured, epoch);
      if (tracks === null || epoch !== captions.epoch) { captured.getTracks().forEach(t => t.stop()); return; }
      // Release captured video tracks immediately. The underlying player is unaffected.
      for (const track of captured.getVideoTracks()) track.stop();
      if (!tracks.length) { for (const t of captured.getTracks()) t.stop(); throw new Error("no_audio"); }
      const stream = new MediaStream(tracks);
      if (epoch !== captions.epoch || !captions.active) { stream.getTracks().forEach(t => t.stop()); return; }
      captions.stream = stream;
      const recorder = new MediaRecorder(stream, { mimeType, audioBitsPerSecond: 24000 });
      captions.recorder = recorder;
      let reads = Promise.resolve(), queuedReads = 0;
      recorder.ondataavailable = event => {
        if (!event.data?.size || captions.recorder !== recorder) return;
        if (++queuedReads > 40) { stopRecording(false); captionError("capture"); return; }
        // Preserve container byte order even when Blob reads resolve at different speeds.
        reads = reads.then(() => {
          if (captions.recorder !== recorder || epoch !== captions.epoch) return null;
          return event.data.arrayBuffer();
        }).then(buffer => {
          --queuedReads;
          if (buffer && captions.recorder === recorder && epoch === captions.epoch)
            sendToNexo({ type: "captions_audio", data: bytesToBase64(buffer) });
        }).catch(() => { --queuedReads; if (captions.recorder === recorder) { stopRecording(false); captionError("capture"); } });
      };
      recorder.onerror = () => {
        if (captions.recorder !== recorder) return;
        stopRecording(false); captionError("capture");
      };
      tracks.forEach(t => t.addEventListener("ended", () => {
        if (captions.recorder === recorder) { stopRecording(true); discoverCaptions(); }
      }));
      sendToNexo({ type: "captions_start", mime: recorder.mimeType || mimeType,
        rate: tracks[0].getSettings?.().sampleRate || 0, channels: tracks[0].getSettings?.().channelCount || 0 });
      recorder.start(CAPTION_SLICE_MS);
      watchSilence(stream, recorder);
    } catch (error) {
      if (epoch !== captions.epoch) return;
      stopRecording(false); captionError(error.message || "unsupported");
    } finally {
      if (epoch === captions.epoch) captions.capturing = false;
    }
  }

  // --- Pista de subtítulos del propio video: sin Deepgram ---------------

  function cueText(cue) {
    return String(cue.text || "").replace(/<[^>]*>/g, "").replace(/\s+/g, " ").trim().slice(0, 300);
  }

  /**
   * Envía solo las líneas que Nexo aún no conoce. Con HLS las líneas llegan
   * por partes durante la reproducción: antes solo se enviaban las primeras y
   * las siguientes nunca aparecían en pantalla.
   */
  function sendNewCues(track) {
    const cues = track.cues;
    if (!cues || !cues.length) return 0;
    if (cues.length === captions.knownCues) return cues.length;
    captions.knownCues = cues.length;
    const list = [];
    for (let i = 0; i < cues.length && nextCueId < MAX_CUES; i++) {
      const cue = cues[i];
      if (cueIds.has(cue)) continue;
      const id = nextCueId++;
      cueIds.set(cue, id);
      const text = cueText(cue);
      if (text) list.push({ i: id, s: cue.startTime, e: cue.endTime, t: text });
    }
    // Por bloques: Nexo las traduce por adelantado en lotes grandes.
    for (let i = 0; i < list.length; i += 200) sendToNexo({ type: "captions_cues", cues: list.slice(i, i + 200) });
    return cues.length;
  }

  function sendActiveCues() {
    const track = captions.track;
    if (!track) return;
    sendNewCues(track);
    let active = [];
    try { active = Array.from(track.activeCues || []); } catch (_) {}
    if (active.some(cue => !cueIds.has(cue))) {
      // La lista cambió sin cambiar de tamaño: volver a revisarla entera.
      captions.knownCues = -1;
      sendNewCues(track);
    }
    const ids = active.map(cue => cueIds.get(cue)).filter(id => id !== undefined);
    sendToNexo({ type: "captions_active", ids });
  }

  function releaseTrack() {
    clearTimeout(captions.trackTimer);
    captions.trackTimer = 0;
    const track = captions.track;
    captions.track = null;
    captions.knownCues = 0;
    if (!track) return;
    track.removeEventListener("cuechange", sendActiveCues);
    try { track.mode = captions.trackMode || "disabled"; } catch (_) {}
  }

  /** Usa la pista de subtítulos del video si existe y se puede leer. */
  function useTextTrack(video) {
    let tracks = [];
    try {
      tracks = Array.from(video.textTracks || [])
        .filter(track => track.kind === "subtitles" || track.kind === "captions");
    } catch (_) {}
    if (!tracks.length) return false;
    const track = tracks.find(item => item.mode === "showing") || tracks[0];
    captions.choosing = false;
    captions.track = track;
    captions.trackMode = track.mode;
    captions.knownCues = 0;
    nextCueId = 0;
    // "hidden" carga las líneas sin dibujarlas: Nexo muestra las suyas (traducidas).
    try { track.mode = "hidden"; } catch (_) {}
    track.addEventListener("cuechange", sendActiveCues);
    sendToNexo({ type: "captions_track", lang: track.language || "", source: "track" });
    let attempts = 0;
    const load = () => {
      if (captions.track !== track) return;
      if (sendNewCues(track) > 0) { sendActiveCues(); return; }
      // Pista vacía o de otro origen sin permiso: probar las otras fuentes.
      if (++attempts >= 10) {
        releaseTrack();
        fallbackAfterTrack();
        return;
      }
      captions.trackTimer = setTimeout(load, 500);
    };
    load();
    return true;
  }

  // --- Subtítulos que el reproductor dibuja en la página -----------------

  /*
   * YouTube, Netflix, Prime Video y otros reproductores no exponen una pista:
   * dibujan el texto en elementos de la página. Nexo lee ese texto (sin
   * Deepgram ni clave) y lo muestra con su estilo, traducido si se pidió.
   * Solo se consulta mientras los subtítulos de Nexo están activos.
   */
  const DOM_CAPTION_TEXT = [
    ".ytp-caption-segment",
    ".player-timedtext-text-container",
    ".atvwebplayersdk-captions-text",
    ".vjs-text-track-display .vjs-text-track-cue",
    ".jw-text-track-cue",
    ".shaka-text-container span",
    ".plyr__caption",
    ".bmpui-ui-subtitle-label"
  ].join(",");
  const DOM_CAPTION_BOXES = [
    ".ytp-caption-window-container",
    ".player-timedtext",
    ".atvwebplayersdk-captions-overlay",
    ".vjs-text-track-display",
    ".jw-captions",
    ".shaka-text-container",
    ".plyr__captions",
    ".bmpui-ui-subtitle-overlay"
  ].join(",");
  const DOM_POLL_MS = 250;
  const hiddenBoxes = new Set();
  let domStyle = null;

  /** Texto visible; null si el reproductor no tiene elementos de subtítulos. */
  function domCaptionText() {
    let nodes;
    try { nodes = document.querySelectorAll(DOM_CAPTION_TEXT); } catch (_) { return null; }
    if (!nodes || !nodes.length) return null;
    const parts = [];
    for (const node of nodes) {
      const text = String(node.textContent || "").replace(/\s+/g, " ").trim();
      if (text && !parts.includes(text)) parts.push(text);
    }
    return parts.join(" ").slice(0, 400);
  }

  /** Nexo dibuja el texto: el del sitio se vuelve transparente para no duplicarlo. */
  function hideSiteCaptions() {
    if (!domStyle || !domStyle.isConnected) {
      try {
        domStyle = document.createElement("style");
        domStyle.textContent = DOM_CAPTION_BOXES + "{opacity:0 !important}";
        (document.head || document.documentElement).appendChild(domStyle);
      } catch (_) { domStyle = null; }
    }
    // Además en línea: una CSP estricta puede ignorar la hoja anterior.
    let boxes = [];
    try { boxes = document.querySelectorAll(DOM_CAPTION_BOXES); } catch (_) {}
    for (const box of boxes) {
      if (hiddenBoxes.has(box)) continue;
      try { box.style.setProperty("opacity", "0", "important"); hiddenBoxes.add(box); } catch (_) {}
    }
  }

  function showSiteCaptions() {
    if (domStyle) {
      try { domStyle.remove(); } catch (_) {}
      domStyle = null;
    }
    for (const box of hiddenBoxes) {
      try { box.style.removeProperty("opacity"); } catch (_) {}
    }
    hiddenBoxes.clear();
  }

  function youtubePlayer() {
    let element = null;
    try { element = document.getElementById("movie_player") || document.querySelector(".html5-video-player"); } catch (_) {}
    if (!element) return null;
    // API del reproductor de la página (Firefox: wrappedJSObject desde el mundo aislado).
    const api = element.wrappedJSObject || element;
    try {
      return typeof api.getOption === "function" && typeof api.setOption === "function" ? api : null;
    } catch (_) {
      return null;
    }
  }

  function youtubeTracks(api) {
    try {
      const list = api.getOption("captions", "tracklist");
      return list && list.length ? Array.from(list) : [];
    } catch (_) {
      return [];
    }
  }

  /** Idioma pedido, si no una pista hecha por personas y si no la automática. */
  function pickYoutubeTrack(tracks, lang) {
    const code = track => String((track && track.languageCode) || "").toLowerCase();
    const want = String(lang || "").toLowerCase();
    return (want && want !== "multi" && tracks.find(track => code(track) === want || code(track).startsWith(want + "-"))) ||
      tracks.find(track => String((track && track.kind) || "") !== "asr") ||
      tracks[0] || null;
  }

  /** Activa los subtítulos de YouTube si el video tiene alguno. */
  function enableYoutubeCaptions(lang, token) {
    const api = youtubePlayer();
    if (!api) return Promise.resolve(false);
    try { api.loadModule("captions"); } catch (_) {}
    return new Promise(resolve => {
      let attempts = 0;
      const check = () => {
        if (!captions.active || token !== captions.token) { resolve(false); return; }
        const tracks = youtubeTracks(api);
        if (tracks.length) {
          let current = null;
          try { current = api.getOption("captions", "track"); } catch (_) {}
          if (!current || !current.languageCode) {
            const track = pickYoutubeTrack(tracks, lang);
            try {
              if (track) { api.setOption("captions", "track", track); captions.ytEnabled = true; }
            } catch (_) {}
          }
          resolve(true);
          return;
        }
        if (++attempts >= 10) { resolve(false); return; }
        setTimeout(check, 300);
      };
      check();
    });
  }

  /** Solo se apagan si los encendió Nexo: la elección del usuario se respeta. */
  function disableYoutubeCaptions() {
    if (!captions.ytEnabled) return;
    captions.ytEnabled = false;
    const api = youtubePlayer();
    try { if (api) api.unloadModule("captions"); } catch (_) {}
  }

  function pollDomCaptions() {
    if (!captions.dom) return;
    if (document.visibilityState === "hidden" && !pipActive) return;
    hideSiteCaptions();
    const text = domCaptionText() || "";
    if (text === captions.domText) return;
    captions.domText = text;
    sendToNexo({ type: "captions_text", text });
  }

  function startDomCaptions() {
    stopDomWatch();
    if (captions.recorder) stopRecording(true);
    captions.choosing = false;
    captions.dom = true;
    captions.domText = "";
    sendToNexo({ type: "captions_track", lang: "", source: "dom" });
    clearInterval(captions.domTimer);
    captions.domTimer = setInterval(pollDomCaptions, DOM_POLL_MS);
    pollDomCaptions();
  }

  function stopDomCaptions() {
    clearInterval(captions.domTimer);
    captions.domTimer = 0;
    captions.domText = "";
    if (captions.dom) {
      captions.dom = false;
      showSiteCaptions();
    }
    disableYoutubeCaptions();
  }

  /*
   * Sin pista ni texto en la página: si el usuario enciende después los
   * subtítulos del propio reproductor, Nexo los toma (y deja el audio).
   */
  function startDomWatch() {
    stopDomWatch();
    captions.domChecks = 0;
    captions.domWatch = setInterval(() => {
      if (!captions.active || !captions.granted || textSource() || ++captions.domChecks > 180) {
        stopDomWatch();
        return;
      }
      if (domCaptionText()) { startDomCaptions(); return; }
      // El usuario eligió subtítulos en el reproductor y este descargó su archivo.
      if (subtitleUrls().length !== captions.fileUrls) loadSubtitleFile(captions.token);
    }, 1000);
  }

  function stopDomWatch() {
    clearInterval(captions.domWatch);
    captions.domWatch = 0;
  }

  // --- Archivos de subtítulos que descarga la propia página ------------------

  /*
   * Muchos reproductores (JW Player, video.js, Plyr…) descargan un .vtt/.srt
   * aparte y lo dibujan ellos mismos, sin pista en el <video>. Nexo lee ese
   * mismo archivo y lo sincroniza con el video: texto, sin audio ni servidores.
   */
  const SUB_FILE = /\.(vtt|srt)(?:[?#]|$)|[?&](?:fmt|format|type)=(?:vtt|srt|webvtt)(?:&|$)/i;
  const MAX_SUB_BYTES = 2 * 1024 * 1024;

  function textSource() { return !!(captions.track || captions.dom || captions.file); }

  function subtitleUrls() {
    const urls = [];
    const add = raw => {
      try {
        const url = new URL(String(raw), location.href);
        if (/^https?:$/.test(url.protocol) && SUB_FILE.test(url.pathname + url.search) && !urls.includes(url.href)) urls.push(url.href);
      } catch (_) {}
    };
    try { for (const entry of performance.getEntriesByType("resource")) add(entry.name); } catch (_) {}
    try { for (const track of document.querySelectorAll("track[src]")) add(track.getAttribute("src")); } catch (_) {}
    return urls.slice(-40);
  }

  /** El archivo en el idioma pedido evita traducir; si no, el último cargado (el que eligió el reproductor). */
  function pickSubtitleUrl(urls, lang) {
    const code = String(lang || "").toLowerCase().split("-")[0];
    if (code && code !== "multi") {
      const token = new RegExp("(^|[^a-z])" + code + "([^a-z]|$)", "i");
      const match = urls.slice().reverse().find(url => token.test(url.split("?")[0]));
      if (match) return match;
    }
    return urls[urls.length - 1];
  }

  function parseTime(value) {
    const m = /^(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})$/.exec(String(value).trim());
    if (!m) return NaN;
    return Number(m[1] || 0) * 3600 + Number(m[2]) * 60 + Number(m[3]) + Number(m[4].padEnd(3, "0")) / 1000;
  }

  function decodeEntities(text) {
    return text.replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
      .replace(/&quot;/g, "\"").replace(/&#39;|&apos;/g, "'").replace(/&amp;/g, "&");
  }

  /**
   * WebVTT o SRT → [{s, e, t}] ordenadas. Los segmentos HLS (X-TIMESTAMP-MAP)
   * se descartan: su tiempo es relativo y hls.js ya los expone como pista.
   */
  function parseSubtitles(raw) {
    const text = String(raw || "").replace(/\r/g, "");
    if (/X-TIMESTAMP-MAP/.test(text)) return [];
    const cues = [];
    for (const block of text.split(/\n{2,}/)) {
      const lines = block.split("\n");
      const at = lines.findIndex(line => line.includes("-->"));
      if (at < 0) continue;
      const parts = lines[at].split("-->");
      const s = parseTime(parts[0]);
      const e = parseTime(String(parts[1] || "").trim().split(/\s+/)[0]);
      const t = decodeEntities(lines.slice(at + 1).join(" ").replace(/<[^>]*>/g, "").replace(/\{[^}]*\}/g, ""))
        .replace(/\s+/g, " ").trim().slice(0, 300);
      if (Number.isFinite(s) && Number.isFinite(e) && e > s && t) cues.push({ s, e, t });
      if (cues.length >= MAX_CUES) break;
    }
    return cues.sort((a, b) => a.s - b.s);
  }

  async function loadSubtitleFile(token) {
    const urls = subtitleUrls();
    captions.fileUrls = urls.length;
    // content.fetch (Firefox) pide como la propia página: el CDN responde igual que al reproductor.
    const pageFetch = typeof content !== "undefined" && content && typeof content.fetch === "function"
      ? content.fetch.bind(content) : (typeof fetch === "function" ? fetch : null);
    if (!urls.length || !pageFetch) return false;
    const url = pickSubtitleUrl(urls, captions.target || captions.lang);
    let text = "";
    try {
      const response = await pageFetch(url, { credentials: "same-origin" });
      if (!response.ok) return false;
      if (Number(response.headers.get("content-length") || 0) > MAX_SUB_BYTES) return false;
      text = (await response.text()).slice(0, MAX_SUB_BYTES);
    } catch (_) {
      return false;
    }
    if (token !== captions.token || !captions.active || !captions.granted || textSource()) return false;
    const cues = parseSubtitles(text);
    if (!cues.length) return false;
    startFileCaptions(cues);
    return true;
  }

  function startFileCaptions(cues) {
    stopDomWatch();
    if (captions.recorder) stopRecording(true);
    captions.choosing = false;
    captions.file = { cues, last: null };
    sendToNexo({ type: "captions_track", lang: "", source: "file" });
    const list = cues.map((cue, i) => ({ i, s: cue.s, e: cue.e, t: cue.t }));
    for (let i = 0; i < list.length; i += 200) sendToNexo({ type: "captions_cues", cues: list.slice(i, i + 200) });
    clearInterval(captions.fileTimer);
    captions.fileTimer = setInterval(syncFileCaptions, 250);
    syncFileCaptions();
  }

  /** Líneas activas según el tiempo del video (como haría una pista). */
  function syncFileCaptions() {
    const file = captions.file, video = captions.video;
    if (!file || !video) return;
    if (document.visibilityState === "hidden" && !pipActive) return;
    const now = Number(video.currentTime) || 0;
    const ids = [];
    for (let i = 0; i < file.cues.length; i++) {
      const cue = file.cues[i];
      if (cue.s > now) break;
      if (cue.e > now) ids.push(i);
    }
    const key = ids.slice(0, 4).join(",");
    if (key === file.last) return;
    file.last = key;
    sendToNexo({ type: "captions_active", ids: ids.slice(0, 4) });
  }

  function stopFileCaptions() {
    clearInterval(captions.fileTimer);
    captions.fileTimer = 0;
    captions.file = null;
  }

  // --- Elección de la fuente ------------------------------------------------

  /*
   * El script corre en todos los marcos: cada marco con un video en marcha se
   * ofrece y Nexo concede los subtítulos al de mayor tamaño. El marco elegido
   * usa, por orden: la pista del video, el texto que dibuja el reproductor y,
   * con clave, el audio del video (Deepgram).
   */
  function stopSources(notify) {
    captions.token++;
    clearTimeout(captions.restartTimer);
    captions.restartTimer = 0;
    captions.choosing = false;
    stopRecording(notify);
    releaseTrack();
    stopDomCaptions();
    stopFileCaptions();
    stopDomWatch();
  }

  function release() {
    stopSources(true);
    captions.granted = false;
    sendToNexo({ type: "captions_release" });
  }

  function fallbackToAudio() {
    captions.choosing = false;
    // Si el usuario enciende después el CC del reproductor, se toma ese texto.
    startDomWatch();
    if (!captions.live) { captionError("need_key"); return; }
    startRecording();
  }

  function fallbackAfterTrack() {
    if (!captions.active || !captions.granted) return;
    const token = captions.token;
    captions.choosing = true;
    if (domCaptionText() !== null) { startDomCaptions(); return; }
    loadSubtitleFile(token).then(loaded => {
      if (loaded || token !== captions.token || !captions.active || !captions.granted || textSource()) return;
      return enableYoutubeCaptions(captions.lang, token).then(found => {
        if (token !== captions.token || !captions.active || !captions.granted || textSource()) return;
        if (found) startDomCaptions(); else fallbackToAudio();
      });
    });
  }

  /*
   * Pistas tardías: hls.js, Shaka y JW añaden la pista de subtítulos cuando
   * leen el manifiesto, a veces segundos después de empezar. Si llega una
   * mientras se usa el audio u otra fuente, se pasa a la pista (gratis y exacta).
   */
  let lateTrackTimer = 0;
  function onLateTrack(event) {
    const track = event && event.track;
    if (!track || (track.kind !== "subtitles" && track.kind !== "captions")) return;
    if (!captions.active || !captions.granted || captions.track) return;
    clearTimeout(lateTrackTimer);
    lateTrackTimer = setTimeout(() => {
      if (captions.active && captions.granted && !captions.track) chooseSource();
    }, 300);
  }
  let watchedTracks = null;
  function watchLateTracks(video) {
    const list = video ? video.textTracks : null;
    if (watchedTracks === list) return;
    try { watchedTracks?.removeEventListener?.("addtrack", onLateTrack); } catch (_) {}
    watchedTracks = list;
    try { list?.addEventListener?.("addtrack", onLateTrack); } catch (_) {}
  }

  function chooseSource() {
    stopSources(false);
    captions.video = captionVideo();
    watchLateTracks(captions.video);
    if (!captions.video) { release(); return; }
    if (useTextTrack(captions.video)) return;
    fallbackAfterTrack();
  }

  function discoverCaptions() {
    if (!captions.active) return;
    const video = captionVideo();
    if (captions.granted && captions.video && (!captions.video.isConnected || captions.video.ended || video !== captions.video)) {
      release();
    }
    if (!captions.granted && video && !video.paused && !video.ended) {
      const rect = video.getBoundingClientRect();
      sendToNexo({ type: "captions_offer", score: Math.round(Math.max(0, rect.width) * Math.max(0, rect.height)) });
    }
  }

  /** Idempotente: Nexo reenvía la orden a cada marco que conecta sin reiniciar al dueño. */
  function setCaptions(active, live, lang, target) {
    if (active !== true) {
      stopSources(false);
      watchLateTracks(null);
      clearTimeout(lateTrackTimer);
      captions.active = false;
      captions.granted = false;
      captions.video = null;
      return;
    }
    const liveChanged = captions.live !== (live === true);
    captions.active = true;
    captions.live = live === true;
    captions.lang = typeof lang === "string" && lang ? lang : "multi";
    captions.target = typeof target === "string" ? target.slice(0, 12) : "";
    // El usuario eligió transcribir el audio mientras se esperaba: probarlo ahora.
    if (captions.granted && liveChanged && captions.live && !textSource() && !captions.recorder) startRecording();
    discoverCaptions();
  }

  // El sondeo también descubre reproductores nuevos (SPA) y videos en Shadow DOM abierto.
  setInterval(discoverCaptions, 1000);
  document.addEventListener("playing", event => {
    discoverCaptions();
    if (captions.granted && (!captions.video || event.target === captions.video)) startRecording();
  }, true);
  // Grabar solo mientras el video suena. La pista y el texto del reproductor siguen activos en pausa.
  ["pause", "ended", "emptied"].forEach(type => document.addEventListener(type, event => {
    if (!captions.active || !captions.granted || event.target !== captions.video) return;
    if (captions.recorder || !textSource()) release();
  }, true));

  // El usuario activa el sonido de un video que empezó silenciado: empezar a transcribir.
  document.addEventListener("volumechange", event => {
    if (!captions.active || !captions.granted || !captions.live || event.target !== captions.video) return;
    if (!videoMuted(captions.video) && !captions.recorder && !textSource()) startRecording();
  }, true);

  // Otro video en el mismo reproductor (siguiente de YouTube, lista, etc.).
  document.addEventListener("loadedmetadata", event => {
    const video = event.target;
    if (!captions.active || !captions.granted || captions.choosing || !video || typeof video.pause !== "function") return;
    if (captions.video && video !== captions.video) return;
    clearTimeout(captions.restartTimer);
    captions.restartTimer = setTimeout(() => {
      captions.restartTimer = 0;
      if (!captions.active || !captions.granted || captions.choosing) return;
      if (captions.dom) {
        if (captions.ytEnabled) enableYoutubeCaptions(captions.lang, captions.token);
        return;
      }
      // La pista era del video anterior: elegir de nuevo (también si antes no había).
      if (captions.track || !captions.recorder) chooseSource();
    }, 1200);
  }, true);

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

  /*
   * Captura de Android: un salto al mismo punto hace que Gecko cierre y vuelva
   * a abrir el flujo de audio del video. Abierto con la captura ya activa,
   * Android lo enruta por la vía que puede oír (no por la de baja latencia).
   */
  function nudgeMedia() {
    const media = new Set(collectVideos().concat(Array.from(document.getElementsByTagName("audio"))));
    for (const element of media) {
      try {
        if (element.paused || element.ended || element.muted || element.readyState < 2) continue;
        const time = Number(element.currentTime);
        if (Number.isFinite(time)) element.currentTime = time;
      } catch (_) {}
    }
  }

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

    if (message.type === "lifecycle") {
      if (message.state === "background") {
        onBackground();
        if (captions.recorder) stopRecording(true);
      } else if (message.state === "foreground") {
        onForeground();
        if (captions.active) setTimeout(startRecording, 900);
      }
    }

    if (message.type === "media_nudge") nudgeMedia();

    if (message.type === "captions") {
      setCaptions(message.active === true, message.live === true, message.lang, message.target);
    }

    if (message.type === "captions_grant" && captions.active && !captions.granted) {
      captions.granted = true;
      chooseSource();
    }
    if (message.type === "captions_restart" && captions.active && !textSource()) {
      stopRecording(false);
      startRecording();
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
      if (port === candidate) { port = null; setCaptions(false, false); }
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
