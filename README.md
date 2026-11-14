# Nexo Browser

<img src="docs/brand/nexo.svg" alt="Logo de Nexo Browser" width="112" />

Desde **0.25.0**, el identificador Android es **`com.nexo.browser`**.
Esta versión se instala como una app nueva junto a las anteriores; no transfiere
automáticamente historial, pestañas, extensiones ni ajustes. Consulta
[el cambio de identidad](docs/IDENTIDAD-NEXO.md) antes de instalarla.

Navegador Android en Kotlin/XML con GeckoView 159 (Firefox 159).

## Incluye
- **Paso desde la app anterior** (`com.ejemplo.navegador`): los releases ya solo publican la app nueva
  (`com.nexo.browser`). Si aún tienes instalada la actualización de transición 0.26.x, Ajustes › Traer datos de
  Nexo anterior sigue funcionando (ver [transferencia](docs/TRANSFERENCIA-PERFIL.md)).
- Omnibox: URL o búsqueda por palabras.
- Google, DuckDuckGo, Bing, Brave Search y Startpage.
- Multipestañas, sesiones LRU y pestañas privadas. Grupos con nombre y color, píldoras con
  contador para filtrar, mover pestañas con pulsación larga y renombrar/desagrupar/cerrar un grupo.
- Selector de pestañas fluido: deslizar una tarjeta hacia un lado la cierra (con **Deshacer**), mantenerla
  pulsada y arrastrarla la cambia de orden (sin moverla, abre sus opciones de grupo), las
  tarjetas muestran el logo del sitio y las miniaturas no parpadean ni se recargan al abrirlo.
  "Reabrir cerrada" en el menú y en la pulsación larga del contador de pestañas.
- Gestos: deslizar la barra inferior a los lados cambia de pestaña y hacia arriba abre el selector;
  pulsación larga en Recargar = recarga sin caché, en "+" = pestaña privada, en el contador = acciones
  rápidas. Todas las hojas y los paneles de extensiones se cierran arrastrándolos hacia abajo.
  Los gestos de la barra y las descargas en varias conexiones se pueden desactivar en Ajustes.
- Herramientas en la barra de direcciones (hoja inferior): grupo, proxy SOCKS5/HTTP por pestaña,
  Orbot externo, retardo de solicitudes y geolocalización simulada. DNS DoH configurable para todo el navegador.
- Burner (menú › Burner): contenedor privado aislado, correo temporal de mail.tm con buzón que se
  actualiza solo, códigos de verificación copiables y limpieza al cerrar la última pestaña de la
  identidad. La franja superior muestra la dirección y abre el buzón. Variación experimental de
  Canvas/WebGL; si ese componente falla, un Burner sin proxy abre igualmente (aislado por Gecko).
- Modo escritorio: User-Agent + viewport.
- Temas Midnight, OLED y claro.
- Siete colores de acento, con cobalto por defecto.
- Historial con búsqueda en vivo (título, dominio o dirección, sin distinguir tildes), descargas y compartir.
- Descargas aceleradas: los archivos grandes de servidores que admiten rangos se reparten en 2–4
  conexiones (misma sesión, nunca con proxy ni Burner), una descarga cortada se reanuda desde el último
  byte y ya no se guarda incompleta. Notificación con progreso y velocidad; se cancelan desde Descargas.
- Menú con captura de la página (PNG), guardar como PDF, fijar el sitio en inicio y reabrir pestañas.
- Subtítulos en vivo para cualquier video (botón CC):
  - Si el video trae subtítulos propios (WebVTT/HLS), se usan esos sin Deepgram ni clave y se traducen
    por adelantado en lotes grandes, también las líneas que llegan durante la reproducción.
  - Si el reproductor dibuja sus propios subtítulos (YouTube, Netflix, Prime Video, video.js, JW Player,
    Shaka, Plyr, Bitmovin), Nexo los lee sin clave y los muestra con su estilo y traducidos. En YouTube
    los activa solo; en otros sitios basta con encender el CC del reproductor.
  - Si el reproductor descarga su propio archivo de subtítulos (`.vtt`/`.srt`), Nexo lo lee y lo sincroniza
    con el video (prefiere el del idioma al que traduces). Texto, sin audio ni servidores.
  - Si el video no trae ningún texto, Nexo **pregunta**: subtítulos instantáneos de Android (generados en el
    teléfono, sin enviar nada) o transcribir con Deepgram. El audio nunca se envía sin elegirlo, salvo que
    actives «Transcribir el audio sin preguntar». Nunca se usa el micrófono: se toma el sonido digital del
    reproductor, así que da igual usar audífonos.
  - Con Deepgram, el audio se comprime en la página (Opus, ~24 kbps) y se transcribe en streaming desde el
    proceso nativo; la clave nunca llega a la página.
  - Traducción a 37 idiomas sin esperas artificiales: cada frase sale al momento si no hay otra petición en
    curso (lo que llega mientras tanto viaja junto en la siguiente), el texto provisional se traduce por
    anticipado y la frase final suele aparecer ya traducida. Google sin clave, DeepL o un modelo de IA
    (OpenAI, Claude, OpenRouter o propio) con contexto; con IA, Google muestra un respaldo rápido mientras
    responde el modelo. Modos: traducido, ambos u original.
  - Tocar CC con los subtítulos activos abre el selector: idioma de destino, modo e idioma del video; los
    cambios se aplican al instante sin cortar el audio.
  - Claves cifradas con el Android Keystore. Reconexión con espera creciente (máx. 3), control de flujo,
    contador de minutos y límite diario. Solo transcribe mientras el video suena en primer plano.
- Bloqueo de ventanas emergentes y "popunders" (reglas $popup de las listas y toques robados), con interruptor.
- Deslizar hacia abajo para recargar (solo cuando la página está arriba y el gesto no lo usa la propia web).
- Recuperación automática del video al volver a la app: si el reproductor quedó congelado, se repara sin recargar.
- Ajustes de navegador: tamaño del texto, zoom en todas las páginas, versión de escritorio por defecto,
  reproducción automática, JavaScript, protección estricta, aviso de sitios peligrosos, bloqueo de huellas
  digitales, limpieza de parámetros de rastreo, borrar datos al salir y depuración remota.
- Limpieza de datos.
- Administrador de extensiones.
- PhantomShield 9.1.0 integrado: bloqueo de red, ocultación con reglas de EasyList/uBlock,
  eliminación de anuncios de YouTube, contadores reales y controles por sitio. IA opcional
  (tu propia API) para los anuncios que las reglas no reconocen; las señales inequívocas se
  resuelven en el dispositivo sin gastar tokens y el panel muestra lo revisado/ocultado en cada página.
- Memoria IA opcional en el dispositivo: coincidencias precisas por dominio, caducidad y controles para olvidar o excluir sitios.
- IA opcional en sitios elegidos o automática, con exclusiones, presupuestos y apagado rápido.
- OpenAI, Claude y OpenRouter, direcciones personalizables y contadores acumulados de tokens y decisiones IA.
- Inicio y menú basados en el prototipo: barra inferior, accesos editables y fuentes locales.
- Logos en accesos rápidos y pestañas: recursos locales y el icono que declara el propio sitio
  (`apple-touch-icon`/`icon`, si no `/favicon.ico`), sin servicios externos; si no hay, inicial de color.
  Accesos de Nexo en inicio (Descargas, Historial, Burner, Extensiones) y pulsación larga en cada acceso.
- Paneles de extensiones como hoja inferior redondeada y accesos directos en el menú: todas las
  extensiones con su icono e insignia en una fila deslizable; aviso claro si una no está permitida en privado.
- Diálogos de páginas: `<select>`, alert/confirm/prompt, fecha y hora, color, subida de
  archivos, inicio de sesión HTTP, "¿salir de la página?" y `navigator.share()`.
- Enlaces `target=_blank` y `window.open` en pestaña nueva; bloqueo de ventanas emergentes.
- Permisos de sitios con confirmación: ubicación, cámara/micrófono, DRM.
- Enlaces a apps (`tel:`, `mailto:`, `intent://`, Play Store…) con intent saneado.
- Puede ser el navegador predeterminado y recibir enlaces/texto compartido de otras apps.
- Buscar en la página.
- Sitios en modo oscuro cuando Nexo usa un tema oscuro.
- Tienda Android de addons.mozilla.org.
- Importación de XPI firmado e instalación por URL.
- Traducción dentro de la página con Gecko, sin recargar ni abrir otra pestaña, con descarga de idiomas confirmada por el usuario.
- Mini reproductor (Picture-in-Picture) con título del video, botón flotante translúcido (pulsación
  larga lo oculta en esa página) y reproducción en segundo plano.
- Parcheador anti-bloqueo opcional, apagado inicialmente: modifica solo dos bibliotecas
  conocidas cuando sus bytes coinciden con un hash verificado.

## Extensiones propias
Los XPI instalados dinámicamente se validan por GeckoView y requieren firma de Mozilla.
Una extensión privada/sin firma puede integrarse dentro de
`app/src/main/assets/extensions/` como extensión built-in y compilarse en el APK.

## Modelo de seguridad
- **URLs**: las reglas están centralizadas en `SafetyRules.kt` (`UrlSafety`).
  - Barra de direcciones y pantallas propias: `http`, `https`, `about`, `resource`, `moz-extension`.
  - Otras apps (Activity exportada): solo `http`/`https`. Las pantallas de Nexo usan el
    alias no exportado `InternalOpenActivity` (`MainActivity.openUrlIntent`).
  - Pestañas abiertas por extensiones: `http`, `https`, `moz-extension` y `about:blank`.
  - `javascript:`, `data:`, `file:`, `content:` e `intent:` nunca se cargan.
- **Extensiones**: la instalación automática al navegar solo acepta XPI de
  `addons.mozilla.org`. Los paquetes importados se validan (rutas, nº de entradas y tamaño
  real descomprimido) y el temporal se borra siempre.
- **Otras apps**: `tel:`, `mailto:`, `intent://`… se abren sin componente ni selector
  explícitos, solo con actividades BROWSABLE y sin permisos de URI heredados. Las
  redirecciones sin toque del usuario o en pestaña privada piden confirmación.
- **Permisos de sitios**: se pregunta siempre mostrando el dominio real; las notificaciones
  web se rechazan porque Nexo aún no las muestra.
- **Puente nativo** (`nexo_bridge`): verifica su propia extensión y la sesión. PiP usa el frame principal; los subtítulos descubren reproductores en los marcos y capturan desde un único puerto autorizado por sesión. Solo con CC activo se reciben pistas de subtítulos o audio para transcribir; las claves permanecen en el proceso nativo. El puente del traductor local se retira al actualizar.
- **Privacidad**: el historial elimina parámetros sensibles (tokens, sesiones, firmas) antes de
  guardarse; las pestañas privadas no se guardan en historial, pestañas ni miniaturas; la
  notificación multimedia no muestra el título en la pantalla de bloqueo.
- **Descargas**: todas pasan por Gecko (cookies de la sesión, modo privado), se guardan en la
  carpeta privada de la app con nombres saneados, y se borran si quedan incompletas.
- **Rutas y Burner**: el componente interno `nexo_network` configura la pestaña antes de
  cargar su destino y está habilitado en privado para aplicar esas rutas. No instala una VPN
  ni incluye Tor. Buzón, IA y listas tienen conexiones propias; las simulaciones no cambian
  la IP. El borrado remoto de mail.tm depende de la conexión y de que siga vivo el proceso.

Los logos de accesos y pestañas se consultan sin cookies ni referer, solo por HTTPS: la
cabecera de la página principal del sitio (redirecciones solo dentro del mismo sitio) y los
iconos que declara, o `/apple-touch-icon.png` y `/favicon.ico`. Se guardan en una caché limitada.
Se admiten imágenes raster compatibles con Android y PNG dentro de ICO (no SVG); si no hay un
formato compatible se muestra la inicial. No se solicitan desde pestañas privadas.

## Desarrollo
Requisitos: JDK 21, Gradle 9.8.2 y Android SDK con `platforms;android-37.2` y
`build-tools;37.0.0` (GeckoView 159 exige compilar contra la API 37.2; AGP 9.5).

```bash
node --test app/src/test/js/*.test.cjs  # regresiones de extensiones y mini reproductor
gradle :app:testDebugUnitTest   # tests unitarios (reglas de URL, buscador, nombres de archivo)
gradle :app:assembleDebug
gradle :app:lintRelease
```

Capturas de la interfaz nativa (Robolectric, opcional y fuera de la CI):

```bash
gradle :app:testDebugUnitTest --tests 'com.nexo.browser.ScreenshotTest' \
  -PnexoScreenshots -PnexoShotsDir=/ruta/capturas
```

Las librerías nativas van comprimidas en el APK (como Firefox para Android): el APK
arm64 pesa ~100 MB en vez de ~216 MB y Android las extrae una vez al instalar.

## CI y releases
El workflow `.github/workflows/main.yml` compila, ejecuta tests y lint en cada PR y push a `main`.

Para publicar: *Actions → Nexo Secure CI and Release → Run workflow → mode = release* desde `main`.
Requiere estos secrets del repositorio:

| Secret | Contenido |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Keystore en base64 (`base64 -w0 nexo.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | Contraseña del keystore |
| `ANDROID_KEY_ALIAS` | Alias de la clave |
| `ANDROID_KEY_PASSWORD` | Contraseña de la clave |

La compilación corre sin acceso a esos secrets; solo el paso de firma los recibe. Si el tag
`v<versionName>` ya existe, el release se rechaza: sube `versionCode`/`versionName` primero.
Usa siempre la misma clave: un APK firmado con otra clave no se puede instalar encima.

La integración se documenta en [PhantomShield](docs/PHANTOMSHIELD.md).
El nuevo traductor se documenta en [traducción en la página](docs/TRADUCCION-EN-LINEA.md).
Los alcances y límites están en [grupos, rutas y Burner](docs/PESTANAS-RED-BURNER.md).
Para conservar los datos al actualizar, consulta [actualizaciones y firma](docs/ACTUALIZACIONES-Y-FIRMA.md).

La memoria y el diagnóstico de IA se explican en [aprendizaje local](docs/IA-MEMORIA.md).

## Transición de paquete y subtítulos

Consulta [transferencia cifrada del perfil](docs/TRANSFERENCIA-PERFIL.md) para la actualización de transición `-PnexoTransition`, importación y recuperación. El [diagnóstico de subtítulos](docs/DIAGNOSTICO-SUBTITULOS.md) explica la captura, sus estados y las verificaciones reales pendientes.

Los cambios de [estabilidad de pestañas y captura de reproducción de Android](docs/ESTABILIDAD-Y-AUDIO-ANDROID.md) explican la confirmación de ventanas, la opción nativa de CC y sus permisos/límites.

La [versión 0.28.0](docs/VERSION-0.28.0.md) reúne la captura de Android que ya no cancela la página, el modo nocturno con Dark Reader, las sugerencias de la barra, la revisión de seguridad y las mediciones de rendimiento, con lo que falta validar en el teléfono. La 0.28.1 corrige las extensiones (PhantomShield, instaladas y tienda), que la 0.28.0 dejaba sin arrancar. La 0.29.0 trae PhantomShield 9.1: menos bloqueos innecesarios, más ventanas emergentes cortadas y dominios publicitarios aprendidos con la IA ([detalle](app/src/main/assets/extensions/phantomshield/README.md#cambios-en-91)).
