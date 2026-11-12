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
  const captions = {
    active: false, live: false, lang: "multi", granted: false, choosing: false, token: 0,
    video: null, recorder: null, silentTimer: 0, epoch: 0, stream: null, meter: null,
    track: null, trackMode: "", trackTimer: 0, knownCues: 0,
    dom: false, domTimer: 0, domText: "", domWatch: 0, domChecks: 0, ytEnabled: false,
    restartTimer: 0, capturing: false, waitCancel: null
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

  function watchSilence(stream, recorder) {
    const Context = window.AudioContext || window.webkitAudioContext;
    if (!Context) return;
    try {
      const context = new Context();
      const source = context.createMediaStreamSource(stream);
      const analyser = context.createAnalyser();
      source.connect(analyser); // no connection to speakers; never duplicate audio
      captions.meter = { context, source };
      context.resume().catch(() => {});
      const samples = new Uint8Array(analyser.fftSize);
      let checks = 0;
      const check = () => {
        if (captions.recorder !== recorder) return;
        if (context.state !== "running") {
          sendToNexo({ type: "captions_status", code: "meter_unavailable" });
          return;
        }
        analyser.getByteTimeDomainData(samples);
        if (samples.some(value => Math.abs(value - 128) > 2)) {
          sendToNexo({ type: "captions_status", code: "signal" }); return;
        }
        if (++checks >= 6) { captionError("silent"); return; }
        captions.silentTimer = setTimeout(check, 2000);
      };
      captions.silentTimer = setTimeout(check, 2000);
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
    if (!captions.active || !captions.granted || !captions.live || captions.choosing || captions.track || captions.dom || captions.recorder || captions.capturing) return;
    const video = captions.video;
    if (!video || !video.isConnected || video.paused || video.ended || document.visibilityState === "hidden") {
      sendToNexo({ type: "captions_status", code: "paused" }); return;
    }
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
      if (!captions.active || !captions.granted || captions.dom || captions.track || ++captions.domChecks > 180) {
        stopDomWatch();
        return;
      }
      if (domCaptionText()) startDomCaptions();
    }, 1000);
  }

  function stopDomWatch() {
    clearInterval(captions.domWatch);
    captions.domWatch = 0;
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
    enableYoutubeCaptions(captions.lang, token).then(found => {
      if (token !== captions.token || !captions.active || !captions.granted || captions.track || captions.dom) return;
      if (found) startDomCaptions(); else fallbackToAudio();
    });
  }

  function chooseSource() {
    stopSources(false);
    captions.video = captionVideo();
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
  function setCaptions(active, live, lang) {
    if (active !== true) {
      stopSources(false);
      captions.active = false;
      captions.granted = false;
      captions.video = null;
      return;
    }
    const liveChanged = captions.live !== (live === true);
    captions.active = true;
    captions.live = live === true;
    captions.lang = typeof lang === "string" && lang ? lang : "multi";
    // Se añadió la clave mientras se esperaba: probar el audio.
    if (captions.granted && liveChanged && captions.live && !captions.track && !captions.dom && !captions.recorder) startRecording();
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
    if (captions.recorder || (!captions.track && !captions.dom)) release();
  }, true));

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
      setCaptions(message.active === true, message.live === true, message.lang);
    }

    if (message.type === "captions_grant" && captions.active && !captions.granted) {
      captions.granted = true;
      chooseSource();
    }
    if (message.type === "captions_restart" && captions.active && !captions.track && !captions.dom) {
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
