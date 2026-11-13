# Estabilidad de navegación y captura de reproducción

## Correcciones de pestañas

- Las direcciones de SPA, fragmentos y navegación atrás/adelante se toman de `onLocationChange`, no solo de `onPageStart`. Antes, la URL persistida podía quedarse en una página anterior.
- Un estado de sesión solo se restaura si la entrada seleccionada de su historial coincide exactamente con la URL actual. Estados antiguos, incompletos o de otra página no pueden devolver al usuario a ella al recrear una sesión.
- Navegar con una sesión descartada abre y carga una sola vez. Antes `ensureSession` podía restaurar/cargar y después recibir un segundo `loadUri`. Una ruta aún en preparación carga la URL más reciente, sin lanzar una carga antes de aplicar el proxy.
- Se ignoran callbacks de progreso de sesiones sustituidas. La normalización de YouTube se limita a navegación directa; no se reinicia una redirección web desde ese callback.
- `window.focus()` y `tabs.update(active=true)` de extensiones no seleccionan pestañas en segundo plano. El usuario sigue pudiendo elegirlas desde el selector/barra.
- Las ventanas de otro origen requieren confirmación, incluso con `hasUserGesture`: los reproductores publicitarios pueden asociar ventanas a toques robados. La aceptación continúa la petición original de Gecko para conservar `window.opener`, aislamiento y logins; cancelar no abre otra pestaña. Las extensiones también necesitan consentimiento para abrir páginas web, con una sola solicitud visible por extensión y sus límites de ráfaga existentes.
- Una respuesta asíncrona de Gecko sobre el gesto de recarga no se reutiliza en otro gesto/sesión.

Esto no impide que una página recargue su propio documento por JavaScript, ni evita un descarte real por falta de memoria del teléfono. La recuperación de procesos sigue acotada; no se desactiva el multiproceso ni se impide actualizar páginas legítimas.

## El error «No hay pistas de audio capturables»

Ese mensaje ocurre antes de que Deepgram reciba audio. `captureStream`/`mozCaptureStream` devolvió una secuencia sin pistas de audio. No prueba cuál es la causa: inicialización tardía, implementación del reproductor, CORS, aislamiento de procesos o protección del contenido son posibilidades.

Nexo espera ahora hasta tres segundos por pistas tardías (incluido `addtrack`). La espera se cancela y libera al apagar CC. No se introduce `createMediaElementSource` en la salida del video: no se desvía ni duplica el sonido.

## Nueva opción explícita de Android

Con Android 10 o posterior: activar CC → volver a tocar CC → **Capturar audio con Android** → leer el alcance → autorizar el permiso de audio y la captura del sistema.

Se necesita una clave de Deepgram para reconocimiento. Google/DeepL/IA traducen el texto reconocido y no reconocen por sí solos el sonido en este flujo. Si es necesario, elige el idioma real del vídeo desde CC.

El servicio usa `AudioPlaybackCaptureConfiguration`, MediaProjection y AudioRecord. **No usa una fuente de micrófono ni guarda audio**. El filtro incluye exclusivamente el UID de Nexo y usos MEDIA/GAME/UNKNOWN; no captura otras apps. Puede incluir varias pestañas de Nexo que suenen al mismo tiempo: pausa las otras antes de autorizar. Solo se lee audio; no se crea un VirtualDisplay ni se captura imagen/pantalla.

La autorización siempre pasa por la interfaz del sistema, nunca se guarda para reutilizarla al reiniciar. Hay una notificación de captura con Detener. El servicio no es exportado, es `START_NOT_STICKY` y tiene únicamente tipo foreground `mediaProjection` (y el permiso correspondiente para Android 14+). Se registra el callback de revocación antes de usar la proyección.

AudioRecord produce PCM16 little-endian, mono, a 16 kHz. Solo esta fuente usa `encoding=linear16&sample_rate=16000&channels=1` con Deepgram. La fuente web conserva Opus WebM/Ogg sin parámetros crudos. Las claves permanecen en nativo. Se mantiene el límite diario y el control de colas de subtítulos.

Apagar CC, cambiar de página/pestaña o salir de Nexo detiene esta captura (PiP visible puede continuar). También se libera AudioRecord, la proyección y el servicio si Android revoca el permiso, falla la captura o falla definitivamente el proveedor. Una autorización que vuelve después de apagar/cambiar el flujo no inicia captura. La notificación permite detenerla sin abrir la app.

Android puede devolver silencio por DRM, política de captura del productor, procesos de Gecko con UID diferente, o ausencia de reproducción. Si no se detecta señal durante los primeros 15 s, se explica y se detiene la captura; una pausa/silencio inicial prolongado puede requerir autorizar otra vez. No es una solución universal ni un mecanismo para eludir DRM. Android 8/9 conserva solo el método web/texto existente.

## Cuando no se detecta el audio (0.26.2)

- **Ofrecimiento automático:** si la página no puede dar su audio (sin pistas, otro origen, grabación
  rechazada o solo silencio), Nexo explica el motivo y propone en un toque **Usar captura de Android**
  (Android 10+, con clave de Deepgram). Se ofrece una vez por activación de CC; sigue disponible en CC.
- **Video silenciado:** si el reproductor está en silencio (autoplay sin sonido o volumen 0) no se graba ni
  se da por fallido: se avisa y la transcripción empieza sola al activar el sonido (`volumechange`).
- **Silencio real:** en la página, el silencio solo cuenta mientras el video avanza con sonido (~15 s); una
  pausa o una intro muda ya no detienen CC. El medidor usa muestras float (detecta voz baja, ~-50 dB).
- **Captura de Android:** el silencio solo cuenta con el video reproduciéndose; a los 6 s sin sonido se avisa
  y a los 25 s se detiene con la explicación. Una ganancia automática suave (1×–8×, sin amplificar el ruido
  de fondo ni saturar) mejora el reconocimiento de videos con poco volumen.
- **Servicio de captura:** pasa a primer plano antes de cualquier comprobación. Antes, si la autorización
  llegaba tarde o se había cambiado de pestaña, se detenía sin `startForeground` y Android cerraba la app.
- **Procesos de Gecko:** `isolatedProcessEnabled(false)` queda explícito. La captura filtra por el UID de
  Nexo; con procesos aislados (otro UID) Android solo entregaría silencio.

## Texto primero; audio solo si se elige (0.27.0)

Orden de fuentes en el marco que recibe los subtítulos: pista del video → texto que dibuja el reproductor →
**archivo de subtítulos que descargó la página** (`.vtt`/`.srt` de `performance` o `<track src>`, pedido con
`content.fetch` como la propia página, máx. 2 MiB; los segmentos HLS con `X-TIMESTAMP-MAP` se descartan porque
su tiempo es relativo) → subtítulos de YouTube → audio.

El audio ya no se usa automáticamente aunque haya clave de Deepgram. Sin texto, Nexo pregunta: subtítulos
instantáneos de Android (Live Caption, en el dispositivo; se abre Ajustes con instrucciones porque no hay un
intent público para activarlo), transcribir con Deepgram o esperar a los subtítulos del reproductor. La opción
«Transcribir el audio sin preguntar» de Ajustes de subtítulos recupera el comportamiento anterior.

Ni la captura de la página ni la de Android usan el micrófono: toman el sonido digital del reproductor antes
de la salida, por lo que altavoz o audífonos no cambian el resultado.

## Evidencia y límites

Se revisó únicamente el HTML y los cargadores de reproductor de la página de ejemplo proporcionada por el usuario. Las opciones SB/VO/LU/DD/JK se insertan dinámicamente; la página también carga scripts e iframes de terceros. No se descargó ni se reprodujo el vídeo y no se atribuye un fallo concreto a un servidor solo por sus siglas. La captura de pantalla del usuario confirmó el error de pistas vacías, no un error del traductor.

Las pruebas JS comprueban pistas tardías, cancelación, tiempo límite, orden de fragmentos y liberación. Las JVM comprueban selección de estado de historial, consentimiento entre orígenes, muestras PCM y configuración de ambos formatos. Compilar y pasar lint no sustituyen una prueba en Android. ADB no encontró dispositivos conectados en esta tarea; no se afirma que MediaProjection, YouTube o los servidores del ejemplo funcionen en hardware real.

Antes de distribuir, probar en Android 10 y 14+ con audio controlado: aceptar/denegar/revocar consentimiento, apagar durante el selector, cambiar página/pestaña, Home/PiP, notificación Detener, silencio, red cortada y límites diarios. Verificar que el micrófono y otras apps nunca entran en la captura, que el sonido no cambia, y que la memoria/proyección se liberan. Después probar los reproductores habituales. Para navegación: varias SPA, volver/avanzar, cambio de pestaña descartada, popunders con toque, login con ventana y extensiones que pidan abrir/activar pestañas.

Fuentes oficiales: [captura de reproducción de Android](https://developer.android.com/media/platform/av-capture), [ciclo de vida y consentimiento de MediaProjection](https://developer.android.com/media/grow/media-projection), y API de GeckoView 159 contrastada con el AAR instalado (NavigationDelegate.onLocationChange, SessionState.currentIndex y HistoryItem.uri).
