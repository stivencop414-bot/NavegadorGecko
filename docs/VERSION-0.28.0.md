# Nexo 0.28.0: subtítulos, seguridad, modo nocturno y rendimiento

Base: `main` en 0.27.2. Dispositivo de referencia: POCO X6 5G con Android 16.
Este entorno no tiene ningún Android conectado. Todo lo que dice «en el
teléfono» está **pendiente de validar físicamente**, y la validación del
audio es obligatoria antes de dar el fallo por cerrado.

## 1. Subtítulos y seguridad del audio

**Fallo confirmado en el POCO.** Al elegir la captura de Android aparecía
«La página no deja grabar el audio de este reproductor». Mientras Android
pedía permiso, la página (o un iframe nuevo) volvía a intentar grabar y
fallaba. Ese error de página llamaba a `PlaybackAudioCapture.cancel()`, que
borraba la solicitud antes de que Android respondiera.

Cambios:

| Cambio | Dónde | Comprobación |
|---|---|---|
| Al aceptar la captura de Android, la página queda fuera hasta que Android escucha (`beginDeviceRequest`). Sus errores ya no cancelan nada. | `LiveCaptions`, `PlaybackAudioCapture`, `ExtensionManager` | Revisión del flujo. En el teléfono: elegir la captura en un servidor HLS en iframe. |
| Toda cancelación se explica en pantalla y la página vuelve a buscar texto. Nunca hay abortos mudos. | `PlaybackAudioCapture.abort` | En el teléfono: cancelar el diálogo de Android. |
| Diagnóstico por etapas en la hoja de CC: reproductor, pista, señal (con nivel en Android), Deepgram y texto. | `LiveCaptions.stages`, `CaptionsUi` | Inspección manual. |
| Errores separados: clave (401/403), saldo (402), idioma/modelo (400), límite (429), conexión, silencio. | `CaptionProtocol.errorFor` y estados | `CaptionProtocolTest` |
| Señal medida de forma continua (página y Android). Un silencio largo tras oír voz solo avisa; sin haber oído nada nunca, falla. | `content_script.js` `watchSilence`, `PlaybackCaptureService` | Pruebas JS existentes en verde. En el teléfono: pausa, auriculares. |
| Colas acotadas (página ≤ 40 trozos, Android ≤ 40 envíos al hilo principal, Deepgram ≤ 256 KB). Al apagar CC se liberan grabador, proyección y conexión. | idem | Revisión. |
| Pistas de subtítulos que el reproductor añade tarde (hls.js) sustituyen a la fuente provisional. | `content_script.js` `watchLateTracks` | Prueba JS «late track». |
| Un cambio solo de `#` en la dirección no detiene los subtítulos. | `TabManager` | Revisión. |
| Pestañas en procesos aislados de Android (Ajustes › Avanzado, activado por defecto; se aplica al reiniciar). | `GeckoRuntimeHolder` | Necesita el teléfono. |
| Latencias de audio de 0.27.2 tras un interruptor (CC › Más ajustes › «Audio compatible con la captura de Android») para compararlas con y sin. Siguen activas por defecto mientras no haya una comparación en el teléfono. | `GeckoRuntimeHolder.applyAudioPrefs` | Comparación controlada pendiente en el POCO. |
| La captura de Android se mantiene como alternativa explícita: el aviso dice que puede incluir otras apps y que nunca usa el micrófono. | `PlaybackAudioCapture` | Revisión de textos. |

Orden de fuentes, sin cambios: subtítulos propios (pista del video, texto del
reproductor, archivo .vtt/.srt) → audio de la página con Deepgram (con
permiso) → captura de Android (con aviso y autorización).

### Validación pendiente en el teléfono (obligatoria)

Con voz real, en MP4, en HLS y con el reproductor dentro de un iframe
(servidores SB/VO/LU/VI/DD/JK), comparar 0.27.1, 0.27.2 y 0.28.0. Probar:

- auriculares;
- pausa;
- cambio de pestaña;
- ventana flotante (PiP);
- permisos revocados;
- conexión cortada;
- clave de Deepgram incorrecta.

Se da por resuelto solo cuando aparezcan transcripciones reales.

## 2. Modo nocturno

- Dark Reader **4.9.134** (MIT; `darkreader-LICENSE.txt`), comprobado con
  el hash SHA-512 de npm. Va en el puente y se carga **solo en el marco que lo
  necesita** (`background.js` → `tabs.executeScript`).
- Se activa solo con Midnight, OLED o el tema oscuro del sistema. Fondo
  `#0c1222` (Midnight) o `#000000` (OLED).
- Respeta las páginas ya oscuras (fondo o `color-scheme` solo oscuro).
  Imágenes y videos conservan sus colores. Funciona en contenido dinámico e
  iframes: cada marco decide por sí mismo.
- Excepción por sitio en las herramientas de la dirección; el efecto es
  inmediato y reversible. `browser_state` lleva `night`, `nightException` y
  `nightBackground` para cada marco, según el sitio principal de su pestaña.
- Al desactivarlo, `DarkReader.disable()` retira estilos y observadores.
- Las hojas de estilo de otros orígenes se piden con el `fetch` del script de
  la extensión y sin cookies.

Comprobado en Chromium (Playwright) con la misma librería:

| Elemento | Activado | Desactivado |
|---|---|---|
| Página clara | rgb(6,6,6) | rgb(255,255,255) |
| Tarjeta | rgb(16,16,16) | rgb(243,243,243) |
| Campo de formulario | rgb(6,6,6) | rgb(255,255,255) |
| Nodo añadido después | rgb(10,10,10) | — |
| Imagen | `filter: none` | — |

Al desactivarlo no queda ningún estilo de Dark Reader. Pruebas JS: página
clara, página ya oscura, cambio de fondo sin recargar y apagado.

**Pendiente en el teléfono (Gecko):** páginas claras, oscuras, dinámicas,
formularios e iframes, y la restauración del diseño original.

## 3. Sugerencias de la barra

- Panel encima de la barra: hasta 2 sugerencias del buscador arriba y 3 del
  historial debajo. Son tipos distintos (`OmniboxSuggestion.Search` y
  `.History`): el historial abre la URL; la sugerencia ejecuta la búsqueda.
- Índice en memoria de las mismas 500 entradas (`SuggestionIndex`), sin otra
  base de datos:
  - busca por título, dominio y URL, sin distinguir mayúsculas ni tildes;
  - ordena por coincidencia y después por fecha, sin duplicados;
  - se reconstruye solo cuando cambia el historial y se consulta fuera del
    hilo principal.
- Buscador:
  - desde 2 caracteres y tras 250 ms sin escribir;
  - cancela la consulta anterior y descarta respuestas desordenadas;
  - caché de 10 min (64 entradas);
  - solo HTTPS, sin redirecciones ni cookies, respuesta ≤ 64 KB;
  - si falla, se mantienen los resultados locales.
- Sin consultas remotas en pestañas privadas, Burner o con proxy. Nunca se
  envían direcciones, IPs, nombres locales ni texto con credenciales.

| Medición | Resultado |
|---|---|
| Latencia local en JVM, 500 entradas, 200 consultas | p50 = 0,35 ms, p95 = 0,65 ms (objetivo < 50 ms) |

**Pendiente:** medir en el POCO con escritura rápida, respuestas
desordenadas, tildes, historial vacío o borrado y sin red.

## 4. Seguridad

| Defecto | Corrección | Prueba |
|---|---|---|
| Un permiso (ubicación, cámara, micrófono, DRM…) pedido por una página podía concederse después de cambiar de página o de pestaña. | Solicitudes ligadas a pestaña visible, origen principal y navegación. Se cierran (rechazadas) al navegar, cambiar o cerrar la pestaña; un «Permitir» tardío se rechaza. | `PermissionScope` (JVM) |
| URLs con `usuario:contraseña@` guardadas en el historial e importadas. | `UrlSafety.stripCredentials` / `forHistory` al guardar, al leer entradas antiguas y al importar. | `SuggestionsTest` |
| Peticiones con clave que seguían redirecciones y aceptaban `http://` hacia cualquier host (IA propia). | Sin redirecciones. Solo HTTPS sin credenciales; HTTP únicamente hacia el propio teléfono (también al guardar el ajuste). Respuestas ≤ 1 MB. | `SecurityLimitsTest` |
| Mensajes del puente sin límite de tamaño. | `BridgeLimits`: ≤ 16 claves, cadenas ≤ 2000 (audio ≤ 350 000), listas acotadas, sin objetos anidados. | `SecurityLimitsTest` |

Revisado sin defectos nuevos:

- **Instalación de extensiones:** XPI firmados, AMO oficial, tamaño real
  descomprimido limitado.
- **Correo Burner (mail.tm):** HTTPS fijo y sin redirecciones.
- **Claves:** guardadas cifradas en `SecretStore`.
- **Transferencia de perfil:** permiso de firma.
- **Dependencias:** OkHttp 4.12.0, AndroidX actuales, GeckoView 159.

## 5. Rendimiento

| Cambio | Detalle |
|---|---|
| Filtrado cosmético por tramos | Revisión de nodos añadidos fuera del callback del observador: 1500 elementos u 8 ms por pasada, cola de 4000. Pausado en pestañas ocultas. Prueba JS. |
| Búsqueda de video en Shadow DOM | Como mucho 20 000 elementos por pasada; ningún recorrido en pestañas ocultas. |
| Estado del navegador | Se envía solo al marco que se conecta. Antes iba a todos en cada conexión, así que se multiplicaba en páginas con muchos iframes. |
| Historial | Historial e índice preparados fuera del hilo principal al arrancar. |
| Mediciones en el teléfono | Ajustes › Avanzado › Mediciones de rendimiento: arranque, carga, memoria y porcentaje de fotogramas lentos (mediana y p95 de las últimas 50 muestras, copiables). |

**Pendiente:** al menos 10 ejecuciones por escenario en el POCO con 0.27.2 y
con 0.28.0, con las mismas páginas, red y batería. Hay que documentar los
resultados y corregir regresiones antes de integrar.

## Validación hecha en este entorno

- Pruebas Kotlin (`testDebugUnitTest`, `testReleaseUnitTest`) y 179 pruebas
  JavaScript.
- Compilación debug y release.
- `lintDebug` y `lintRelease`.
- `check_release_reflection.py` y `check_apk_identity.py`.
- La firma (`verify_apk_signature.py`) la hace el flujo de publicación con
  los secretos del repositorio.

Se conservan el paquete (`com.nexo.browser`), los datos y la actualización
sobre la versión instalada.
