# Nexo Browser

Navegador Android en Kotlin/XML con GeckoView 159 (Firefox 159).

## Incluye
- Omnibox: URL o búsqueda por palabras.
- Google, DuckDuckGo, Bing, Brave Search y Startpage.
- Multipestañas, sesiones LRU y pestañas privadas. Grupos con nombre y color, píldoras con
  contador para filtrar, mover pestañas con pulsación larga y renombrar/desagrupar/cerrar un grupo.
- Herramientas en la barra de direcciones (hoja inferior): grupo, proxy SOCKS5/HTTP por pestaña,
  Orbot externo, retardo de solicitudes y geolocalización simulada. DNS DoH configurable para todo el navegador.
- Burner: contenedor privado aislado, correo temporal de mail.tm con buzón en hoja inferior y
  limpieza al cerrar la última pestaña de la identidad. Variación experimental de Canvas/WebGL.
- Modo escritorio: User-Agent + viewport.
- Temas Midnight, OLED y claro.
- Siete colores de acento, con cobalto por defecto.
- Historial con búsqueda en vivo (título, dominio o dirección, sin distinguir tildes), descargas y compartir.
- Limpieza de datos.
- Administrador de extensiones.
- PhantomShield 8.8.0 integrado: bloqueo de red, ocultación con reglas de EasyList/uBlock,
  eliminación de anuncios de YouTube, contadores reales y controles por sitio. IA opcional
  (tu propia API) para los anuncios que las reglas no reconocen; las señales inequívocas se
  resuelven en el dispositivo sin gastar tokens y el panel muestra lo revisado/ocultado en cada página.
- Memoria IA opcional en el dispositivo: coincidencias precisas por dominio, caducidad y controles para olvidar o excluir sitios.
- IA opcional en sitios elegidos o automática, con exclusiones, presupuestos y apagado rápido.
- OpenAI, Claude y OpenRouter, direcciones personalizables y contadores acumulados de tokens y decisiones IA.
- Inicio y menú basados en el prototipo: barra inferior, accesos editables y fuentes locales.
- Logos en accesos rápidos: recursos locales y favicon HTTPS del propio sitio, sin servicios externos.
- Paneles de extensiones como hoja inferior redondeada y accesos directos en el menú.
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
- **Puente nativo** (`nexo_bridge`): solo acepta mensajes de su propia extensión y del frame principal. No envía URL, título ni contenido de la página. El puente del traductor local se retira al actualizar.
- **Privacidad**: el historial elimina parámetros sensibles (tokens, sesiones, firmas) antes de
  guardarse; las pestañas privadas no se guardan en historial, pestañas ni miniaturas; la
  notificación multimedia no muestra el título en la pantalla de bloqueo.
- **Descargas**: todas pasan por Gecko (cookies de la sesión, modo privado), se guardan en la
  carpeta privada de la app con nombres saneados, y se borran si quedan incompletas.
- **Rutas y Burner**: el componente interno `nexo_network` configura la pestaña antes de
  cargar su destino y está habilitado en privado para aplicar esas rutas. No instala una VPN
  ni incluye Tor. Buzón, IA y listas tienen conexiones propias; las simulaciones no cambian
  la IP. El borrado remoto de mail.tm depende de la conexión y de que siga vivo el proceso.

Los favicons de accesos personalizados se consultan sin cookies en el propio sitio
(`/favicon.ico`), sin seguir redirecciones, y se guardan en una caché limitada.
Se admiten imágenes raster compatibles con Android y PNG dentro de ICO; si el
servidor no ofrece un formato compatible se conserva la inicial. No se solicitan
en la página de inicio privada.

## Desarrollo
Requisitos: JDK 21, Gradle 9.8.2 y Android SDK con `platforms;android-37.2` y
`build-tools;37.0.0` (GeckoView 159 exige compilar contra la API 37.2; AGP 9.5).

```bash
node --test app/src/test/js/*.test.cjs  # regresiones de extensiones y mini reproductor
gradle :app:testDebugUnitTest   # tests unitarios (reglas de URL, buscador, nombres de archivo)
gradle :app:assembleDebug
gradle :app:lintRelease
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
