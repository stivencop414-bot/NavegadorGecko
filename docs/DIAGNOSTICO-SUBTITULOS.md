# Subtítulos: captura y diagnóstico

El traductor Google/DeepL/IA necesita texto: no sustituye a Deepgram como reconocedor de audio. Si el video tiene una pista TextTrack legible, se usa directamente sin Deepgram. YouTube no garantiza exponer sus subtítulos como TextTrack estándar.

El puente anterior se inyectaba y aceptaba mensajes solo del frame principal. Además, `createMediaElementSource(video)` estaba en la ruta de sonido. La especificación exige silencio para recursos CORS no autorizados y una redirección permanente puede afectar la reproducción. El puente 1.11.0 descubre reproductores en cada frame y Shadow DOM abierto, ofrece candidatos a nativo y solo captura tras una concesión por sesión. No se confía en mensajes de otra extensión. El control PiP sigue limitado al frame principal, conservando su comportamiento previo.

La captura usa `captureStream`/`mozCaptureStream` del elemento, descarta las pistas de video y graba una MediaStream solo de audio. Web Audio es únicamente un medidor de esa copia, sin conexión a altavoces y sin `createMediaElementSource`. Un medidor suspendido se informa, pero no impide grabar. No se usa micrófono ni se pide su permiso para transcribir el reproductor.

Se negocia explícitamente Opus en WebM u Ogg (~24 kbps solicitados al codificador, no una garantía de bitrate real). El mensaje de inicio incluye MIME y sampleRate/channelCount reportados por la pista; se valida MIME y la cabecera EBML/Ogg del primer fragmento en nativo. La frecuencia y los canales reales están descritos por el contenedor; **no** se añaden `encoding`, `sample_rate` ni `channels` de audio crudo a la URL Deepgram. La clave se envía exclusivamente en la cabecera nativa `Authorization: Token …`. Una reconexión exige una grabación nueva con cabecera nueva; ante congestión se reinicia en lugar de continuar un contenedor con fragmentos descartados.

Estados visibles: búsqueda, espera de reproducción, conexión, captura/señal, bytes recibidos sin resultado y errores de captura/autenticación/proveedor/traducción. El CC está disponible aunque el detector de video del frame principal no encuentre el reproductor. Apagar CC detiene grabador, pistas copiadas y medidor; invalida callbacks, cierra WebSocket y cancela peticiones de traducción. La pausa y el cambio de elemento liberan la captura; navegación dinámica ofrece el reproductor nuevo. Al volver de Ajustes se reaplican configuración/clave a las sesiones activas.

Una captura puede fallar o entregar silencio por DRM, CORS, implementación de Gecko, pistas no disponibles, Shadow DOM cerrado o limitaciones del reproductor. Una señal en el medidor no demuestra reconocimiento correcto; recibir bytes tampoco demuestra que contengan voz. Sin resultados durante 15 s se muestran bytes y confirmación de señal, sin atribuir automáticamente la causa a Deepgram. Los errores no se registran con claves ni contenido del audio.

## Validación manual requerida

En Android con una clave propia configurada de forma segura: probar primero un video local/controlado con voz y una pista TextTrack, después YouTube y un iframe de otro origen. Comprobar MIME, canales, frecuencia reportada, cabecera inicial, señal y respuestas reales de Deepgram. No exportar claves ni audio privado en registros. Probar modo original, Google e IA, clave incorrecta/saldo agotado, red interrumpida, vídeo reemplazado/anuncios, pausa/reanudación, apagar mientras hay una lectura Blob y cambiar de pestaña. Comprobar que el sonido no cambia, no se duplica y que PiP y reproducción en segundo plano mantienen su comportamiento. Verificar que apagar CC deja de generar tráfico de audio y traducción.

Las pruebas JS usan dobles de DOM/MediaRecorder, no codecs ni Gecko. Las pruebas JVM validan protocolo y estado portable, no WebSocket de un proveedor real. Compilar y pasar lint no demuestra que un servidor de video permita captura. En este entorno ADB no encontró dispositivos y no hay comprobación real de YouTube/proveedores.

## Alternativa Android evaluada

`AudioPlaybackCapture` (API 29+) requeriría consentimiento explícito de MediaProjection, RECORD_AUDIO y, según versión/target, servicio foreground de tipo mediaProjection y sus permisos. Solo captura usos/políticas permitidos dentro del mismo perfil de usuario; el productor puede impedir la captura y DRM no se sortea. Puede incluir audio de otras aplicaciones si el filtro no se limita correctamente; necesita una UX de consentimiento y aislamiento de sesión. No se implementa como fallback oculto ni se promete cobertura universal. Mantener la captura web permite informar la limitación sin iniciar una grabación global del dispositivo.

## Fuentes oficiales consultadas

- [Deepgram, especificación oficial de streaming](https://github.com/deepgram/deepgram-api-specs/blob/main/asyncapi.yml): listen v1, autenticación, configuración opcional y mensajes Results/Error/KeepAlive/CloseStream. El sitio developers.deepgram.com devolvió HTTP 403 desde esta máquina; se consultó su especificación oficial publicada en GitHub.
- [MediaRecorder](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder): negociación MIME, eventos de datos y ciclo de inicio/parada.
- [HTMLMediaElement.captureStream](https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/captureStream), limitaciones y variante mozCaptureStream.
- [Web Audio: seguridad de MediaElementAudioSourceNode](https://webaudio.github.io/web-audio-api/#MediaElementAudioSourceNode-security), silencio para recursos CORS no autorizados.
- [GeckoRuntime: shutdown y Delegate](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntime.html).
- [Mozilla: backend storage.local](https://github.com/mozilla-firefox/firefox/blob/main/toolkit/components/extensions/ExtensionStorageIDB.sys.mjs), contrastado con el módulo del AAR fijado por el repositorio.
- [Android: capturar reproducción](https://developer.android.com/media/platform/av-capture).
