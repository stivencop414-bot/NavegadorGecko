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
  const audioGraphs = new WeakMap();
  const captions = { active: false, live: false, video: null, recorder: null, silentTimer: 0,
    track: null, trackMode: "", trackTimer: 0 };
  const MAX_CUES = 3000;

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

  async function audioGraph(video) {
    const known = audioGraphs.get(video);
    if (known) return known;
    const Context = window.AudioContext || window.webkitAudioContext;
    if (typeof Context !== "function" || typeof MediaRecorder !== "function") throw new Error("unsupported");
    const context = new Context();
    if (context.state !== "running") {
      try { await Promise.race([context.resume(), new Promise(resolve => setTimeout(resolve, 800))]); } catch (_) {}
    }
    // Sin un contexto en marcha, desviar el audio dejaría el video en silencio.
    if (context.state !== "running") {
      try { context.close(); } catch (_) {}
      throw new Error("suspended");
    }
    const source = context.createMediaElementSource(video);
    // La salida normal se conserva para siempre: el video sigue sonando igual.
    source.connect(context.destination);
    const destination = context.createMediaStreamDestination();
    const analyser = context.createAnalyser();
    analyser.fftSize = 512;
    const graph = { context, source, destination, analyser, tapped: false };
    audioGraphs.set(video, graph);
    return graph;
  }

  /** Rama de captura conectada solo mientras se graba; fuera de eso no hay trabajo extra. */
  function tap(graph, on) {
    if (!graph || graph.tapped === on) return;
    try {
      if (on) { graph.source.connect(graph.destination); graph.source.connect(graph.analyser); }
      else { graph.source.disconnect(graph.destination); graph.source.disconnect(graph.analyser); }
      graph.tapped = on;
    } catch (_) {}
  }

  function stopRecording(notify) {
    const recorder = captions.recorder;
    captions.recorder = null;
    clearTimeout(captions.silentTimer);
    captions.silentTimer = 0;
    if (captions.video) tap(audioGraphs.get(captions.video), false);
    if (recorder) {
      try { if (recorder.state !== "inactive") recorder.stop(); } catch (_) {}
      if (notify) sendToNexo({ type: "captions_stop" });
    }
  }

  /** Un video protegido (DRM) o de otro origen sin permiso entrega silencio. */
  function watchSilence(graph, recorder) {
    const samples = new Uint8Array(graph.analyser.fftSize);
    let checks = 0;
    let heard = false;
    const check = () => {
      if (captions.recorder !== recorder) return;
      graph.analyser.getByteTimeDomainData(samples);
      for (const value of samples) if (Math.abs(value - 128) > 2) { heard = true; break; }
      checks++;
      if (heard) return;
      if (checks >= 6) { captionError("silent"); return; }
      captions.silentTimer = setTimeout(check, 2000);
    };
    captions.silentTimer = setTimeout(check, 2000);
  }

  async function startRecording() {
    if (!captions.active || !captions.live || captions.track || captions.recorder) return;
    const video = captions.video && captions.video.isConnected ? captions.video : captionVideo();
    if (!video) { captionError("no_video"); return; }
    captions.video = video;
    if (video.paused || video.ended || document.visibilityState === "hidden") return;
    let graph;
    try {
      graph = await audioGraph(video);
    } catch (error) {
      captionError(error && error.message === "suspended" ? "suspended" : "unsupported");
      return;
    }
    if (!captions.active || captions.recorder || video.paused) return;
    tap(graph, true);
    const mimeType = ["audio/webm;codecs=opus", "audio/ogg;codecs=opus"]
      .find(type => MediaRecorder.isTypeSupported(type)) || "";
    let recorder;
    try {
      recorder = new MediaRecorder(graph.destination.stream, mimeType ? { mimeType, audioBitsPerSecond: 24000 } : undefined);
    } catch (_) {
      captionError("unsupported");
      return;
    }
    captions.recorder = recorder;
    recorder.ondataavailable = event => {
      if (!event.data || !event.data.size || captions.recorder !== recorder) return;
      event.data.arrayBuffer().then(buffer => {
        if (captions.recorder === recorder) sendToNexo({ type: "captions_audio", data: bytesToBase64(buffer) });
      }).catch(() => {});
    };
    sendToNexo({ type: "captions_start", mime: mimeType });
    try {
      recorder.start(CAPTION_SLICE_MS);
    } catch (_) {
      captions.recorder = null;
      captionError("unsupported");
      return;
    }
    watchSilence(graph, recorder);
  }

  // --- Pista de subtítulos del propio video: sin Deepgram ---------------

  function cueText(cue) {
    return String(cue.text || "").replace(/<[^>]*>/g, "").replace(/\s+/g, " ").trim().slice(0, 300);
  }

  function sendCues(track) {
    const cues = track.cues;
    if (!cues || !cues.length) return false;
    const list = [];
    for (let i = 0; i < cues.length && i < MAX_CUES; i++) {
      const text = cueText(cues[i]);
      if (text) list.push({ i, s: cues[i].startTime, e: cues[i].endTime, t: text });
    }
    // Todas las líneas por adelantado, en bloques: Nexo las traduce en lotes grandes.
    for (let i = 0; i < list.length; i += 200) sendToNexo({ type: "captions_cues", cues: list.slice(i, i + 200) });
    sendActiveCues();
    return list.length > 0;
  }

  function sendActiveCues() {
    const track = captions.track;
    if (!track) return;
    const cues = Array.from(track.cues || []);
    const ids = Array.from(track.activeCues || []).map(cue => cues.indexOf(cue)).filter(i => i >= 0);
    sendToNexo({ type: "captions_active", ids });
  }

  function releaseTrack() {
    clearTimeout(captions.trackTimer);
    captions.trackTimer = 0;
    const track = captions.track;
    captions.track = null;
    if (!track) return;
    track.removeEventListener("cuechange", sendActiveCues);
    try { track.mode = captions.trackMode || "disabled"; } catch (_) {}
  }

  /** Usa la pista de subtítulos o capítulos del video si existe y se puede leer. */
  function useTextTrack(video) {
    const tracks = Array.from(video.textTracks || [])
      .filter(track => track.kind === "subtitles" || track.kind === "captions");
    if (!tracks.length) return false;
    const track = tracks.find(item => item.mode === "showing") || tracks[0];
    captions.track = track;
    captions.trackMode = track.mode;
    // "hidden" carga las líneas sin dibujarlas: Nexo muestra las suyas (traducidas).
    try { track.mode = "hidden"; } catch (_) {}
    track.addEventListener("cuechange", sendActiveCues);
    sendToNexo({ type: "captions_track", lang: track.language || "" });
    let attempts = 0;
    const load = () => {
      if (captions.track !== track) return;
      if (sendCues(track)) return;
      // Pista vacía o de otro origen sin permiso: se recurre al audio si hay clave.
      if (++attempts >= 10) {
        releaseTrack();
        if (captions.live) startRecording(); else captionError("need_key");
        return;
      }
      captions.trackTimer = setTimeout(load, 500);
    };
    load();
    return true;
  }

  function setCaptions(active, live) {
    captions.active = active === true;
    captions.live = live === true;
    if (!captions.active) {
      stopRecording(false);
      releaseTrack();
      captions.video = null;
      return;
    }
    captions.video = captionVideo();
    if (!captions.video) { captionError("no_video"); return; }
    if (useTextTrack(captions.video)) return;
    if (!captions.live) { captionError("need_key"); return; }
    startRecording();
  }

  // Grabar solo mientras el video suena: en pausa no se envía nada (ni se cobra).
  document.addEventListener("playing", event => {
    if (captions.active && (!captions.video || event.target === captions.video)) {
      if (!captions.video) captions.video = event.target;
      startRecording();
    }
  }, true);
  ["pause", "ended", "emptied"].forEach(type => {
    document.addEventListener(type, event => {
      if (captions.active && event.target === captions.video) stopRecording(true);
    }, true);
  });

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

    if (message.type === "lifecycle") {
      if (message.state === "background") {
        onBackground();
        if (captions.recorder) stopRecording(true);
      } else if (message.state === "foreground") {
        onForeground();
        if (captions.active) setTimeout(startRecording, 900);
      }
    }

    if (message.type === "captions") {
      setCaptions(message.active === true, message.live === true);
    }

    if (message.type === "captions_restart" && captions.active && !captions.track) {
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
